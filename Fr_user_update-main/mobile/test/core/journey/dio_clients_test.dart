import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/entry/entry_models.dart'
    show BackendUnreachableException;
import 'package:mobile/core/journey/dio_journey_api.dart';
import 'package:mobile/core/journey/journey_error.dart';
import 'package:mobile/core/journey/journey_models.dart';
import 'package:mobile/core/liveness/dio_liveness_api.dart';
import 'package:mobile/core/signature/dio_signature_api.dart';
import 'package:mobile/core/signature/signature_models.dart';

/// The three Stage 10-12 `dio` clients, exercised for real.
///
/// Every screen and repository test overrides these with fakes — correctly, because what those
/// tests are about is behaviour, not transport. That leaves the hand-written decoding here as the
/// one piece of this slice nothing else touches: the nullable fields, the field the app
/// deliberately does NOT decode, and the request body shapes. This file covers it, mirroring
/// `dio_identity_scan_api_test.dart`'s `_StubAdapter` pattern.
class _StubAdapter extends Interceptor {
  int? statusCode;
  Object? body;
  DioExceptionType? rejectWithType;
  RequestOptions? lastRequest;

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    lastRequest = options;
    final type = rejectWithType;
    if (type != null) {
      handler.reject(DioException(requestOptions: options, type: type));
      return;
    }
    final status = statusCode ?? 200;
    final response = Response<dynamic>(
      requestOptions: options,
      statusCode: status,
      data: body,
    );
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

void main() {
  late _StubAdapter stub;
  late Dio dio;

  setUp(() {
    stub = _StubAdapter();
    dio = Dio(BaseOptions(baseUrl: 'http://localhost:8080'))
      ..interceptors.add(stub);
  });

  Map<String, Object?> requestBody() =>
      jsonDecode(jsonEncode(stub.lastRequest!.data)) as Map<String, Object?>;

  group('DioJourneyApi — the resume pointer', () {
    test(
      'decodes a mid-journey pointer, leaving the complete-only fields null',
      () {
        stub.body = {
          'profileId': 'p1',
          'stage': 'SIGNATURE',
          'blockedUntil': null,
          'referenceNumber': null,
          'verifiedChannels': <String>[],
        };

        return DioJourneyApi(dio).currentPointer('p1').then((pointer) {
          expect(pointer.stage, JourneyStage.signature);
          expect(pointer.blockedUntil, isNull);
          expect(pointer.referenceNumber, isNull);
          expect(pointer.verifiedChannels, isEmpty);
        });
      },
    );

    test(
      'decodes a submitted pointer with its reference number and channels',
      () async {
        stub.body = {
          'profileId': 'p1',
          'stage': 'SUBMITTED',
          'referenceNumber': 'REF-0001',
          'verifiedChannels': ['email', 'sms'],
        };

        final pointer = await DioJourneyApi(dio).currentPointer('p1');

        expect(pointer.stage, JourneyStage.submitted);
        expect(pointer.stage.isComplete, isTrue);
        expect(pointer.referenceNumber, 'REF-0001');
        expect(pointer.verifiedChannels, ['email', 'sms']);
      },
    );

    test(
      'an ABSENT verifiedChannels decodes as empty rather than throwing',
      () async {
        // The confirmation screen renders an honest "unavailable" for an empty list; a crash here
        // would take the reference number down with it, which is the one thing the customer needs.
        stub.body = {
          'profileId': 'p1',
          'stage': 'SUBMITTED',
          'referenceNumber': 'REF-0002',
        };

        final pointer = await DioJourneyApi(dio).currentPointer('p1');

        expect(pointer.verifiedChannels, isEmpty);
        expect(pointer.referenceNumber, 'REF-0002');
      },
    );

    test(
      'a LIVENESS_BLOCKED pointer parses its deadline; an unparseable one degrades to null',
      () async {
        stub.body = {
          'profileId': 'p1',
          'stage': 'LIVENESS_BLOCKED',
          'blockedUntil': '2026-09-06T12:00:00Z',
        };
        expect(
          (await DioJourneyApi(dio).currentPointer('p1')).blockedUntil,
          DateTime.utc(2026, 9, 6, 12),
        );

        stub.body = {
          'profileId': 'p1',
          'stage': 'LIVENESS_BLOCKED',
          'blockedUntil': 'nonsense',
        };
        expect(
          (await DioJourneyApi(dio).currentPointer('p1')).blockedUntil,
          isNull,
        );
      },
    );

    test(
      'an unknown stage throws rather than landing the customer on a guessed screen',
      () {
        stub.body = {'profileId': 'p1', 'stage': 'SOMETHING_NEW'};
        expect(
          () => DioJourneyApi(dio).currentPointer('p1'),
          throwsFormatException,
        );
      },
    );

    test(
      'POST, not GET — the profile id stays out of the request line and every access log',
      () async {
        // S5-11's reasoning, carried to this endpoint: the surface is unauthenticated by design
        // (R-051), so a GET would put the id into proxies and logs along the way.
        stub.body = {'profileId': 'p1', 'stage': 'SUBMIT'};
        await DioJourneyApi(dio).currentPointer('p1');

        expect(stub.lastRequest!.method, 'POST');
        expect(stub.lastRequest!.path, '/api/v1/submission/current');
        expect(stub.lastRequest!.uri.query, isEmpty);
      },
    );
  });

  group('DioJourneyApi — submit', () {
    test(
      'decodes the receipt and DELIBERATELY does not decode verifiedChannels',
      () async {
        // BL-058, enforced structurally: the field is on the wire and `SubmissionReceipt` has no
        // place to put it, so no future reader can reach for it. The confirmation screen's channels
        // come from the pointer read on every path.
        stub.body = {
          'profileId': 'p1',
          'referenceNumber': 'REF-0003',
          'status': 'submitted',
          'verifiedChannels': ['sms', 'email'],
        };

        final receipt = await DioJourneyApi(dio).submit('p1');

        expect(receipt.referenceNumber, 'REF-0003');
        expect(receipt.status, 'submitted');
        expect(
          receipt.toString(),
          isNot(contains('sms')),
          reason: 'the receipt must carry no channel data at all',
        );
      },
    );

    test('a coded 409 comes back through the shared mapper', () async {
      stub.statusCode = 409;
      stub.body = {'status': 409, 'detail': 'x', 'code': 'SIGNATURE_REQUIRED'};

      await expectLater(
        DioJourneyApi(dio).submit('p1'),
        throwsA(
          isA<JourneyConflictException>().having(
            (e) => e.code,
            'code',
            JourneyCode.signatureRequired,
          ),
        ),
      );
    });
  });

  group('DioLivenessApi', () {
    test(
      'decodes a token issuance — and there is no nonce to decode',
      () async {
        stub.body = {
          'profileId': 'p1',
          'accessToken': 'tok',
          'faceSessionId': 'fs-1',
          'usableUntil': '2026-09-12T10:10:00Z',
        };

        final issuance = await DioLivenessApi(dio).issueToken('p1');

        expect(issuance.accessToken, 'tok');
        expect(issuance.faceSessionId, 'fs-1');
        // BL-114(a). On this stage the backend resolves usableUntil to the FACE SESSION's 600s
        // deadline, not the token's ~1800s -- the client just reads whichever instant it is sent.
        expect(
          issuance.usableUntil,
          DateTime.parse('2026-09-12T10:10:00Z').toLocal(),
        );
      },
    );

    test(
      'decodes a result, with blockedUntil null unless this attempt exhausted the budget',
      () async {
        stub.body = {'profileId': 'p1', 'passed': true, 'matchLevel': 5};
        final passed = await DioLivenessApi(
          dio,
        ).submitResult(profileId: 'p1', faceSessionId: 'fs-1', jws: 'j');
        expect(passed.passed, isTrue);
        expect(passed.blockedUntil, isNull);

        stub.body = {
          'profileId': 'p1',
          'passed': false,
          'matchLevel': 1,
          'blockedUntil': '2026-09-06T12:00:00Z',
        };
        final blocked = await DioLivenessApi(
          dio,
        ).submitResult(profileId: 'p1', faceSessionId: 'fs-1', jws: 'j');
        expect(blocked.passed, isFalse);
        expect(blocked.blockedUntil, DateTime.utc(2026, 9, 6, 12));
      },
    );

    test('the JWS goes up untouched', () async {
      const jws = 'eyJhbGciOiJSUzI1NiJ9.eyJkYXRhIjp7fX0.sig';
      stub.body = {'profileId': 'p1', 'passed': true, 'matchLevel': 5};

      await DioLivenessApi(
        dio,
      ).submitResult(profileId: 'p1', faceSessionId: 'fs-1', jws: jws);

      expect(requestBody()['jws'], jws);
    });

    test(
      'a partial JWS is sent when present and the key is OMITTED when absent',
      () async {
        stub.body = {'profileId': 'p1'};

        await DioLivenessApi(dio).reportTerminated(
          profileId: 'p1',
          faceSessionId: 'fs-1',
          sdkErrorCode: 'USER_CANCEL',
          partialJws: 'partial.face.jws',
        );
        expect(requestBody()['partialJws'], 'partial.face.jws');

        await DioLivenessApi(dio).reportTerminated(
          profileId: 'p1',
          faceSessionId: 'fs-1',
          sdkErrorCode: 'USER_CANCEL',
        );
        // Omitted, not present-and-null. The backend treats the two identically, but a null value on
        // the wire invites a reader to think it means something different from absence.
        expect(requestBody().containsKey('partialJws'), isFalse);
      },
    );
  });

  group('DioSignatureApi', () {
    test(
      'sends base64 JSON with the wire values SignatureService allows',
      () async {
        stub.body = {'profileId': 'p1'};
        final bytes = Uint8List.fromList([1, 2, 3, 4]);

        await DioSignatureApi(dio).submit(
          profileId: 'p1',
          signature: CapturedSignature(
            method: SignatureCaptureMethod.drawn,
            bytes: bytes,
          ),
        );

        final body = requestBody();
        expect(stub.lastRequest!.path, '/api/v1/signature');
        expect(body['captureMethod'], 'drawn');
        expect(body['contentType'], 'image/png');
        expect(body['contentBase64'], base64Encode(bytes));
      },
    );

    test('an uploaded signature declares JPEG', () async {
      stub.body = {'profileId': 'p1'};

      await DioSignatureApi(dio).submit(
        profileId: 'p1',
        signature: CapturedSignature(
          method: SignatureCaptureMethod.uploaded,
          bytes: Uint8List.fromList([9]),
        ),
      );

      expect(requestBody()['captureMethod'], 'uploaded');
      expect(requestBody()['contentType'], 'image/jpeg');
    });

    test(
      'a connection failure is connectivity, not a rejected signature',
      () async {
        stub.rejectWithType = DioExceptionType.connectionError;

        await expectLater(
          DioSignatureApi(dio).submit(
            profileId: 'p1',
            signature: CapturedSignature(
              method: SignatureCaptureMethod.drawn,
              bytes: Uint8List.fromList([1]),
            ),
          ),
          throwsA(isA<BackendUnreachableException>()),
        );
      },
    );
  });
}
