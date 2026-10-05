import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/entry/entry_models.dart';
import 'package:mobile/core/identityscan/dio_identity_scan_api.dart';
import 'package:mobile/core/identityscan/identity_scan_models.dart';

/// Simulates the backend without a real socket, exercising the real `DioIdentityScanApi` code —
/// mirrors `dio_entry_api_test.dart`'s `_FakeAdapter` pattern, made settable so one adapter can
/// stand in for every arm of the mapper.
class _StubAdapter extends Interceptor {
  int? statusCode;
  Object? body;
  DioExceptionType? rejectWithType;

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    final type = rejectWithType;
    if (type != null) {
      handler.reject(DioException(requestOptions: options, type: type));
      return;
    }
    final status = statusCode ?? 200;
    final response = Response<dynamic>(requestOptions: options, statusCode: status, data: body);
    if (status >= 400) {
      handler.reject(
        DioException(
          requestOptions: options,
          response: response,
          type: DioExceptionType.badResponse,
        ),
      );
      return;
    }
    handler.resolve(response);
  }
}

({DioIdentityScanApi api, _StubAdapter stub}) build() {
  final stub = _StubAdapter();
  final dio = Dio(BaseOptions(baseUrl: 'http://localhost:8080'))..interceptors.add(stub);
  return (api: DioIdentityScanApi(dio), stub: stub);
}

/// One `application/problem+json` body as the backend actually renders it —
/// `{type,title,status,detail,instance,code[,blockedUntil]}`. The extension members are top-level,
/// not nested, which is what `ScanConflictCode.fromResponseBody` reads.
Map<String, Object?> problem(int status, String code, {String? blockedUntil}) => {
  'type': 'about:blank',
  'title': 'Conflict',
  'status': status,
  'detail': 'a fixed generic string per code',
  'instance': '/api/v1/identity-scan/token',
  'code': code,
  // Null-aware map entry: the member is OMITTED entirely when null, which is exactly what the
  // controller does (`IdentityScanController.java:241-243`) — not present-but-null.
  'blockedUntil': ?blockedUntil,
};

void main() {
  group('coded errors — the code selects, not the status', () {
    test("every one of BL-033's eight 409 codes maps to its own enum value", () async {
      const expected = {
        'PROFILE_TERMINAL': ScanConflictCode.profileTerminal,
        'SCAN_BLOCKED': ScanConflictCode.scanBlocked,
        'SCAN_TYPE_EXHAUSTED': ScanConflictCode.scanTypeExhausted,
        'IMAGES_UNAVAILABLE': ScanConflictCode.imagesUnavailable,
        'ARTIFACT_EXPIRED': ScanConflictCode.artifactExpired,
        'REGISTRY_PENDING': ScanConflictCode.registryPending,
        'REGISTRY_NOT_READY': ScanConflictCode.registryNotReady,
        'STATE_CONFLICT': ScanConflictCode.stateConflict,
      };
      for (final entry in expected.entries) {
        final t = build();
        t.stub
          ..statusCode = 409
          ..body = problem(409, entry.key);
        await expectLater(
          t.api.issueToken(profileId: 'p', documentType: 'passport'),
          throwsA(isA<ScanConflictException>().having((e) => e.code, entry.key, entry.value)),
        );
      }
    });

    test('SCAN_BLOCKED carries blockedUntil when the member is present', () async {
      final t = build();
      t.stub
        ..statusCode = 409
        ..body = problem(409, 'SCAN_BLOCKED', blockedUntil: '2026-09-06T10:15:30Z');
      await expectLater(
        t.api.issueToken(profileId: 'p', documentType: 'passport'),
        throwsA(
          isA<ScanConflictException>()
              .having((e) => e.code, 'code', ScanConflictCode.scanBlocked)
              .having(
                (e) => e.blockedUntil?.toUtc().toIso8601String(),
                'blockedUntil',
                '2026-09-06T10:15:30.000Z',
              ),
        ),
      );
    });

    test('SCAN_BLOCKED with the member ABSENT yields a null blockedUntil, not a throw', () async {
      // The controller omits the member entirely when the stored instant is null
      // (IdentityScanController.java:241-243), so the client must treat it as absent rather than
      // as a null-valued string. BL-049's edge is exactly this shape.
      final t = build();
      t.stub
        ..statusCode = 409
        ..body = problem(409, 'SCAN_BLOCKED');
      await expectLater(
        t.api.issueToken(profileId: 'p', documentType: 'passport'),
        throwsA(isA<ScanConflictException>().having((e) => e.blockedUntil, 'blockedUntil', isNull)),
      );
    });

    test('a blockedUntil already in the PAST parses normally and is not rejected', () async {
      // The block clears lazily — only issueToken resets it — so an elapsed deadline is a real,
      // expected wire value, not a malformed one. Rendering is what has to cope; parsing must not
      // second-guess it.
      final t = build();
      t.stub
        ..statusCode = 409
        ..body = problem(409, 'SCAN_BLOCKED', blockedUntil: '2020-01-01T00:00:00Z');
      await expectLater(
        t.api.issueToken(profileId: 'p', documentType: 'passport'),
        throwsA(
          isA<ScanConflictException>().having(
            (e) => e.blockedUntil!.isBefore(DateTime.now()),
            'is in the past',
            isTrue,
          ),
        ),
      );
    });

    test('an unparseable blockedUntil degrades to null rather than throwing', () async {
      final t = build();
      t.stub
        ..statusCode = 409
        ..body = problem(409, 'SCAN_BLOCKED', blockedUntil: 'not-a-date');
      await expectLater(
        t.api.issueToken(profileId: 'p', documentType: 'passport'),
        throwsA(isA<ScanConflictException>().having((e) => e.blockedUntil, 'blockedUntil', isNull)),
      );
    });

    test('a 409 code this app version does not know becomes ScanConflictCode.unknown', () async {
      final t = build();
      t.stub
        ..statusCode = 409
        ..body = problem(409, 'SOME_FUTURE_CODE');
      await expectLater(
        t.api.issueToken(profileId: 'p', documentType: 'passport'),
        throwsA(
          isA<ScanConflictException>().having((e) => e.code, 'code', ScanConflictCode.unknown),
        ),
      );
    });
  });

  group('BL-037 — the two 400s are told apart by the code, never by the status', () {
    test('400 + SCAN_REJECTED means an attempt WAS spent', () async {
      final t = build();
      t.stub
        ..statusCode = 400
        ..body = problem(400, 'SCAN_REJECTED');
      await expectLater(
        t.api.submitScan(
          profileId: 'p',
          sessionId: 's',
          nonce: 'n',
          documentType: 'passport',
          jws: 'a.b.c',
        ),
        throwsA(isA<ScanRejectedException>()),
      );
    });

    test('a coded 400 that is NOT SCAN_REJECTED falls back to the nothing-was-spent meaning', () async {
      final t = build();
      t.stub
        ..statusCode = 400
        ..body = problem(400, 'SOMETHING_ELSE');
      await expectLater(
        t.api.submitScan(
          profileId: 'p',
          sessionId: 's',
          nonce: 'n',
          documentType: 'passport',
          jws: 'a.b.c',
        ),
        throwsA(isA<IdentityScanClientErrorException>()),
      );
    });
  });

  group('uncoded errors — keyed off the ABSENCE of a code, never off parsing the body', () {
    // The three shapes an uncoded body can actually take. A live Spring Boot 4.1.0 server returns
    // {timestamp,status,error,path} (captured live, IdentityScanController.java:214-218); MockMvc
    // renders an EMPTY body for the same exception (IdentityScanControllerTest.java:195-198). Both
    // are real. A mapper that parsed either shape would break against the other, so all three must
    // reach the same arm.
    final uncodedBodies = <String, Object?>{
      'live Spring Boot body': {
        'timestamp': '2026-09-05T09:00:00.000+00:00',
        'status': 400,
        'error': 'Bad Request',
        'path': '/api/v1/identity-scan/scan-result',
      },
      'empty string body': '',
      'null body': null,
    };

    for (final entry in uncodedBodies.entries) {
      test('400 with a ${entry.key} is a client error, nothing spent', () async {
        final t = build();
        t.stub
          ..statusCode = 400
          ..body = entry.value;
        await expectLater(
          t.api.issueToken(profileId: 'p', documentType: 'passport'),
          throwsA(isA<IdentityScanClientErrorException>()),
        );
      });
    }

    test('404 is a profile the backend does not have — the app re-syncs', () async {
      final t = build();
      t.stub
        ..statusCode = 404
        ..body = null;
      await expectLater(t.api.currentReview('p'), throwsA(isA<ProfileNotFoundException>()));
    });

    test('a BARE 409 reuses the shared ProfileAlreadyCompleteException', () async {
      // This is the shape /api/v1/data-entry/stage7 answers for a terminal profile — that endpoint
      // is still on the old bare-ResponseStatusException style and carries no code at all. Without
      // this arm a Stage 7 conflict would fall through to a raw DioException.
      final t = build();
      t.stub
        ..statusCode = 409
        ..body = null;
      await expectLater(t.api.acceptReview('p'), throwsA(isA<ProfileAlreadyCompleteException>()));
    });
  });

  group("connectivity — including the token endpoint's unmapped 500", () {
    test('a 500 is unreachable, not a scan failure', () async {
      // POST /token answers an unmapped 500 when Uqudo's own token endpoint is down
      // (HttpUqudoClient.mintToken throws IllegalStateException and nothing catches it).
      // customer.md Stage 8 classifies that as a connectivity failure NOT counted against the
      // retry budget, so it must not reach any screen that implies an attempt was lost.
      final t = build();
      t.stub
        ..statusCode = 500
        ..body = null;
      await expectLater(
        t.api.issueToken(profileId: 'p', documentType: 'passport'),
        throwsA(isA<BackendUnreachableException>()),
      );
    });

    test('a 503 is unreachable too', () async {
      final t = build();
      t.stub
        ..statusCode = 503
        ..body = null;
      await expectLater(
        t.api.issueToken(profileId: 'p', documentType: 'passport'),
        throwsA(isA<BackendUnreachableException>()),
      );
    });

    for (final type in [
      DioExceptionType.connectionError,
      DioExceptionType.connectionTimeout,
      DioExceptionType.receiveTimeout,
      DioExceptionType.sendTimeout,
      DioExceptionType.unknown,
    ]) {
      test('${type.name} is unreachable, so the capture stays retryable', () async {
        final t = build();
        t.stub.rejectWithType = type;
        await expectLater(
          t.api.submitScan(
            profileId: 'p',
            sessionId: 's',
            nonce: 'n',
            documentType: 'passport',
            jws: 'a.b.c',
          ),
          throwsA(isA<BackendUnreachableException>()),
        );
      });
    }
  });

  group('decoding', () {
    test('a token issuance decodes every field', () async {
      final t = build();
      t.stub.body = {
        'profileId': 'p1',
        'accessToken': 'tok',
        'sessionId': 's1',
        'nonce': 'n1',
        'documentType': 'passport',
        'usableUntil': '2026-09-12T10:30:00Z',
      };
      final issuance = await t.api.issueToken(profileId: 'p1', documentType: 'passport');
      expect(issuance.sessionId, 's1');
      expect(issuance.nonce, 'n1');
      expect(issuance.documentType, 'passport');
      expect(issuance.accessToken, 'tok');
      // BL-114(a). Asserted because the field is decoded strictly and `required` on the model, so
      // a wire change that dropped or renamed it would fail here rather than at a customer. Local
      // time, like every other instant this app decodes. Nothing READS the value yet.
      expect(
        issuance.usableUntil,
        DateTime.parse('2026-09-12T10:30:00Z').toLocal(),
      );
    });

    test('registryReady=false leaves every registry field null and still decodes', () async {
      final t = build();
      t.stub.body = {
        'profileId': 'p1',
        'cycleId': 'c1',
        'documentType': 'passport',
        'nationalNumber': 'NID-TEST-0001',
        'registryReady': false,
        'nameArGiven': null,
        'dateOfBirth': null,
        'availableImageKinds': <String>['doc_front'],
      };
      final display = await t.api.currentReview('p1');
      expect(display.registryReady, isFalse);
      expect(display.nationalNumber, 'NID-TEST-0001');
      expect(display.nameArGiven, isNull);
      expect(display.dateOfBirth, isNull);
      expect(display.availableImageKinds, ['doc_front']);
    });

    test('a missing availableImageKinds decodes as an empty list, never null', () async {
      final t = build();
      t.stub.body = {
        'profileId': 'p1',
        'cycleId': 'c1',
        'documentType': 'passport',
        'nationalNumber': 'NID-TEST-0001',
        'registryReady': true,
      };
      final display = await t.api.currentReview('p1');
      expect(display.availableImageKinds, isEmpty);
    });

    test('wrong-number returns null when the action did not exhaust the budget', () async {
      final t = build();
      t.stub.body = {'profileId': 'p1', 'blockedUntil': null};
      expect(await t.api.reportWrongNumber('p1'), isNull);
    });

    test('wrong-number returns the deadline when the action DID exhaust the budget', () async {
      final t = build();
      t.stub.body = {'profileId': 'p1', 'blockedUntil': '2026-09-06T10:15:30Z'};
      final blockedUntil = await t.api.reportWrongNumber('p1');
      expect(blockedUntil?.toUtc().toIso8601String(), '2026-09-06T10:15:30.000Z');
    });

    test('reviewImage returns raw bytes', () async {
      final t = build();
      t.stub.body = [1, 2, 3, 4];
      final bytes = await t.api.reviewImage(profileId: 'p1', kind: ScanImageKinds.docFront);
      expect(bytes, [1, 2, 3, 4]);
    });

    test('an image 404 is a ProfileNotFoundException, not a crash on a bytes body', () async {
      // The bytes response type means the error body is not a Map, which must route to the uncoded
      // arm rather than blowing up in fromResponseBody.
      final t = build();
      t.stub
        ..statusCode = 404
        ..body = <int>[];
      await expectLater(
        t.api.reviewImage(profileId: 'p1', kind: ScanImageKinds.docBack),
        throwsA(isA<ProfileNotFoundException>()),
      );
    });
  });
}
