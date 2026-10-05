import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/entry/dio_entry_api.dart';
import 'package:mobile/core/entry/entry_models.dart';

/// Simulates the backend without a real socket, exercising the real `DioEntryApi` code — mirrors
/// `dio_reference_api_test.dart`'s `_FakeAdapter` pattern.
class _FakeAdapter extends Interceptor {
  /// The last `/api/v1/otp/resend` request body seen, so a test can assert what actually went on
  /// the wire. Reset in `setUp`.
  static Map<String, Object?>? lastResendBody;

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    if (options.path.endsWith('/api/v1/account-check')) {
      final accountNumber = (options.data as Map)['accountNumber'];
      if (accountNumber == 'unreachable') {
        handler.reject(
          DioException(requestOptions: options, type: DioExceptionType.connectionError),
        );
        return;
      }
      if (accountNumber == 'outage') {
        // S3-02: the backend's 503 for a core-banking middleware outage.
        handler.reject(
          DioException(
            requestOptions: options,
            response: Response(requestOptions: options, statusCode: 503),
            type: DioExceptionType.badResponse,
          ),
        );
        return;
      }
      final Map<String, Object?> body = switch (accountNumber) {
        'invalid' => {'outcome': 'INVALID', 'continuation': 'RETRY', 'requestId': 'r1'},
        'inactive' => {'outcome': 'INACTIVE', 'continuation': 'TERMINAL', 'requestId': 'r1'},
        _ => {'outcome': 'ACTIVE', 'continuation': 'PROCEED', 'requestId': 'r1'},
      };
      handler.resolve(Response(requestOptions: options, statusCode: 200, data: body));
      return;
    }

    if (options.path.endsWith('/api/v1/contact-channels')) {
      final accountNumber = (options.data as Map)['accountNumber'];
      switch (accountNumber) {
        case 'no-channel':
          handler.reject(
            DioException(
              requestOptions: options,
              response: Response(requestOptions: options, statusCode: 400),
              type: DioExceptionType.badResponse,
            ),
          );
          return;
        case 'complete':
          handler.reject(
            DioException(
              requestOptions: options,
              response: Response(requestOptions: options, statusCode: 409),
              type: DioExceptionType.badResponse,
            ),
          );
          return;
        case 'blocked':
          handler.reject(
            DioException(
              requestOptions: options,
              response: Response(requestOptions: options, statusCode: 429),
              type: DioExceptionType.badResponse,
            ),
          );
          return;
        case 'unreachable':
          handler.reject(
            DioException(requestOptions: options, type: DioExceptionType.connectionTimeout),
          );
          return;
        default:
          handler.resolve(
            Response(
              requestOptions: options,
              statusCode: 200,
              data: {
                'profileId': 'p1',
                'channels': [
                  {'channel': 'sms', 'state': 'unverified', 'maskedDestination': '••••1234'},
                ],
              },
            ),
          );
      }
      return;
    }

    if (options.path.endsWith('/api/v1/otp/verify')) {
      final profileId = (options.data as Map)['profileId'];
      switch (profileId) {
        case 'unreachable':
          handler.reject(
            DioException(requestOptions: options, type: DioExceptionType.connectionError),
          );
          return;
        case 'unknown-channel':
          handler.reject(
            DioException(
              requestOptions: options,
              response: Response(requestOptions: options, statusCode: 400),
              type: DioExceptionType.badResponse,
            ),
          );
          return;
      }
      final Map<String, Object?> body = switch (profileId) {
        'verified' => {
          'channel': 'sms',
          'outcome': 'VERIFIED',
          'state': 'verified',
          'sessionBlockedUntilIso': null,
        },
        'wrong' => {
          'channel': 'sms',
          'outcome': 'WRONG_CODE',
          'state': 'unverified',
          'sessionBlockedUntilIso': null,
        },
        'expired' => {
          'channel': 'sms',
          'outcome': 'EXPIRED',
          'state': 'unverified',
          'sessionBlockedUntilIso': null,
        },
        'locked' => {
          'channel': 'sms',
          'outcome': 'CHANNEL_LOCKED',
          'state': 'unverified',
          'sessionBlockedUntilIso': null,
        },
        'blocked' => {
          'channel': 'whatsapp',
          'outcome': 'CHANNEL_LOCKED',
          'state': 'unverified',
          'sessionBlockedUntilIso': '2026-09-01T12:30:00.000Z',
        },
        _ => throw StateError('unmapped test profileId: $profileId'),
      };
      handler.resolve(Response(requestOptions: options, statusCode: 200, data: body));
      return;
    }

    if (options.path.endsWith('/api/v1/otp/resend')) {
      // Captured so a test can assert on the request BODY, not just the decoded response —
      // BL-101 turns on whether `correctedEmailAddress` reaches the wire at all.
      lastResendBody = Map<String, Object?>.from(options.data as Map);
      final profileId = (options.data as Map)['profileId'];
      switch (profileId) {
        case 'unreachable':
          handler.reject(
            DioException(requestOptions: options, type: DioExceptionType.connectionError),
          );
          return;
        case 'unknown-channel':
          handler.reject(
            DioException(
              requestOptions: options,
              response: Response(requestOptions: options, statusCode: 400),
              type: DioExceptionType.badResponse,
            ),
          );
          return;
      }
      final Map<String, Object?> body = switch (profileId) {
        'issued' => {
          'channel': 'sms',
          'outcome': 'ISSUED',
          'maskedDestination': '••••1234',
          'secondsUntilAllowed': -1,
        },
        'too-soon' => {
          'channel': 'sms',
          'outcome': 'TOO_SOON',
          'maskedDestination': '••••1234',
          'secondsUntilAllowed': 45,
        },
        'cap' => {
          'channel': 'sms',
          'outcome': 'CAP_EXHAUSTED',
          'maskedDestination': '••••1234',
          'secondsUntilAllowed': -1,
        },
        'locked' => {
          'channel': 'sms',
          'outcome': 'CHANNEL_LOCKED',
          'maskedDestination': '••••1234',
          'secondsUntilAllowed': -1,
        },
        'already' => {
          'channel': 'sms',
          'outcome': 'ALREADY_VERIFIED',
          'maskedDestination': '••••1234',
          'secondsUntilAllowed': -1,
        },
        _ => throw StateError('unmapped test profileId: $profileId'),
      };
      handler.resolve(Response(requestOptions: options, statusCode: 200, data: body));
      return;
    }

    handler.reject(DioException(requestOptions: options, message: 'unexpected path'));
  }
}

void main() {
  late Dio dio;
  late DioEntryApi api;

  setUp(() {
    _FakeAdapter.lastResendBody = null;
    dio = Dio(BaseOptions(baseUrl: 'http://unit-test.invalid'))..interceptors.add(_FakeAdapter());
    api = DioEntryApi(dio);
  });

  group('checkAccount', () {
    test('decodes an ACTIVE/PROCEED response', () async {
      final result = await api.checkAccount('2', '12345');
      expect(result.outcome, AccountOutcome.active);
      expect(result.continuation, AccountContinuation.proceed);
      expect(result.requestId, 'r1');
    });

    test('decodes an INVALID/RETRY response', () async {
      final result = await api.checkAccount('2', 'invalid');
      expect(result.outcome, AccountOutcome.invalid);
      expect(result.continuation, AccountContinuation.retry);
    });

    test('decodes an INACTIVE/TERMINAL response', () async {
      final result = await api.checkAccount('2', 'inactive');
      expect(result.outcome, AccountOutcome.inactive);
      expect(result.continuation, AccountContinuation.terminal);
    });

    test('a connection error surfaces as BackendUnreachableException', () async {
      await expectLater(
        () => api.checkAccount('2', 'unreachable'),
        throwsA(isA<BackendUnreachableException>()),
      );
    });

    test('a 503 core-banking outage surfaces as BackendUnreachableException (S3-02)', () async {
      // Not a raw DioException: launchDecision() only catches BackendUnreachableException, and a
      // middleware outage at cold launch must resume offline, not surface an error on the launch
      // screen or -- worse -- be read as a business outcome.
      await expectLater(
        () => api.checkAccount('2', 'outage'),
        throwsA(isA<BackendUnreachableException>()),
      );
    });
  });

  group('submitContactChannels', () {
    Future<ContactChannelsResult> submit(String accountNumber) {
      return api.submitContactChannels(
        branch: '2',
        accountNumber: accountNumber,
        phoneNumber: '+249912345678',
        sms: true,
        whatsapp: true,
      );
    }

    test('decodes a successful response', () async {
      final result = await submit('12345');
      expect(result.profileId, 'p1');
      expect(result.channels.single.maskedDestination, '••••1234');
      expect(result.channels.single.state, ChannelState.unverified);
    });

    test('400 surfaces as ContactChannelsRejectedException', () async {
      await expectLater(
        () => submit('no-channel'),
        throwsA(isA<ContactChannelsRejectedException>()),
      );
    });

    test('409 surfaces as ProfileAlreadyCompleteException', () async {
      await expectLater(() => submit('complete'), throwsA(isA<ProfileAlreadyCompleteException>()));
    });

    test('429 surfaces as SessionTemporarilyBlockedException', () async {
      await expectLater(
        () => submit('blocked'),
        throwsA(isA<SessionTemporarilyBlockedException>()),
      );
    });

    test('a connection error surfaces as BackendUnreachableException', () async {
      await expectLater(
        () => submit('unreachable'),
        throwsA(isA<BackendUnreachableException>()),
      );
    });
  });

  group('verifyChannel', () {
    Future<OtpVerifyResult> verify(String profileId) {
      return api.verifyChannel(profileId: profileId, channel: 'sms', code: '123456');
    }

    test('decodes VERIFIED', () async {
      final result = await verify('verified');
      expect(result.outcome, OtpVerifyOutcome.verified);
      expect(result.state, ChannelState.verified);
      expect(result.sessionBlockedUntil, isNull);
    });

    test('decodes WRONG_CODE', () async {
      expect((await verify('wrong')).outcome, OtpVerifyOutcome.wrongCode);
    });

    test('decodes EXPIRED', () async {
      expect((await verify('expired')).outcome, OtpVerifyOutcome.expired);
    });

    test('decodes CHANNEL_LOCKED with no session block', () async {
      final result = await verify('locked');
      expect(result.outcome, OtpVerifyOutcome.channelLocked);
      expect(result.sessionBlockedUntil, isNull);
    });

    test('decodes CHANNEL_LOCKED with a session block — every phone channel now locked', () async {
      final result = await verify('blocked');
      expect(result.outcome, OtpVerifyOutcome.channelLocked);
      expect(result.sessionBlockedUntil, DateTime.parse('2026-09-01T12:30:00.000Z'));
    });

    test('400 surfaces as UnknownOtpChannelException', () async {
      await expectLater(
        () => verify('unknown-channel'),
        throwsA(isA<UnknownOtpChannelException>()),
      );
    });

    test('a connection error surfaces as BackendUnreachableException', () async {
      await expectLater(() => verify('unreachable'), throwsA(isA<BackendUnreachableException>()));
    });
  });

  group('resendChannel', () {
    Future<OtpResendResult> resend(String profileId) {
      return api.resendChannel(profileId: profileId, channel: 'sms');
    }

    test('decodes ISSUED with secondsUntilAllowed mapped to null', () async {
      final result = await resend('issued');
      expect(result.outcome, OtpResendOutcome.issued);
      expect(result.secondsUntilAllowed, isNull);
    });

    test('decodes TOO_SOON with the real secondsUntilAllowed', () async {
      final result = await resend('too-soon');
      expect(result.outcome, OtpResendOutcome.tooSoon);
      expect(result.secondsUntilAllowed, 45);
    });

    test('decodes CAP_EXHAUSTED', () async {
      expect((await resend('cap')).outcome, OtpResendOutcome.capExhausted);
    });

    test('decodes CHANNEL_LOCKED', () async {
      expect((await resend('locked')).outcome, OtpResendOutcome.channelLocked);
    });

    test('decodes ALREADY_VERIFIED', () async {
      expect((await resend('already')).outcome, OtpResendOutcome.alreadyVerified);
    });

    test('400 surfaces as UnknownOtpChannelException', () async {
      await expectLater(
        () => resend('unknown-channel'),
        throwsA(isA<UnknownOtpChannelException>()),
      );
    });

    // ----- BL-101 — the corrected-email field on the wire -----

    test('a corrected email address is sent in the request body', () async {
      await api.resendChannel(
        profileId: 'issued',
        channel: 'email',
        correctedEmailAddress: 'nour@example.sd',
      );

      expect(_FakeAdapter.lastResendBody!['correctedEmailAddress'], 'nour@example.sd');
      expect(_FakeAdapter.lastResendBody!['channel'], 'email');
    });

    test(
      'an ordinary resend omits the key entirely — absent, not an explicit null, so the wire '
      'payload is unchanged from before BL-101',
      () async {
        await resend('issued');

        expect(_FakeAdapter.lastResendBody!.containsKey('correctedEmailAddress'), isFalse);
        expect(_FakeAdapter.lastResendBody, {'profileId': 'issued', 'channel': 'sms'});
      },
    );

    test('a connection error surfaces as BackendUnreachableException', () async {
      await expectLater(() => resend('unreachable'), throwsA(isA<BackendUnreachableException>()));
    });
  });
}
