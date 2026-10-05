import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/entry/entry_models.dart'
    show BackendUnreachableException;
import 'package:mobile/core/journey/journey_error.dart';

/// The Stage 10-12 error contract, which is what S5-13 built and this slice consumes.
///
/// The mapper's five arms are ORDERED, and the order is the contract. Two of those orderings are
/// load-bearing rather than stylistic and each has its own test below: 5xx before anything
/// status-specific, and coded-400 before uncoded-400.
void main() {
  DioException response(int status, Object? body) => DioException(
    requestOptions: RequestOptions(path: '/x'),
    response: Response<Object?>(
      requestOptions: RequestOptions(path: '/x'),
      statusCode: status,
      data: body,
    ),
    type: DioExceptionType.badResponse,
  );

  Map<String, Object?> problem(String code, {String? blockedUntil}) => {
    'type': 'about:blank',
    'title': 'Conflict',
    'status': 409,
    'detail': 'something',
    'code': code,
    'blockedUntil': ?blockedUntil,
  };

  group('the twelve codes are exactly what the three controllers emit', () {
    test('every wire value round-trips', () {
      // Counted from source, not from S5-13's prose (which says eleven). If the backend renames
      // one, this is what notices.
      const expected = {
        'PROFILE_TERMINAL',
        'STATE_CONFLICT',
        'LIVENESS_REQUIRED',
        'LIVENESS_BLOCKED',
        'LIVENESS_ALREADY_PASSED',
        'RESCAN_REQUIRED',
        'REGISTRY_PENDING',
        'ARTIFACT_EXPIRED',
        'AUDIT_TRAIL_UNAVAILABLE',
        'LIVENESS_REJECTED',
        'SIGNATURE_REJECTED',
        'SIGNATURE_REQUIRED',
      };
      final actual = JourneyCode.values
          .where((c) => c != JourneyCode.unknown)
          .map((c) => c.wire)
          .toSet();
      expect(actual, expected);
      for (final wire in expected) {
        expect(JourneyCode.fromWire(wire).wire, wire);
      }
    });

    test('an unrecognised code degrades rather than throwing', () {
      // Deliberately the opposite call from JourneyStage.fromWire: an unrecognised ERROR code is
      // already an error path, and crashing would replace a degraded message with no message.
      expect(
        JourneyCode.fromWire('SOMETHING_NEW_IN_2027'),
        JourneyCode.unknown,
      );
    });
  });

  group('code detection never parses a bare body', () {
    test('finds a top-level string code', () {
      expect(
        JourneyCode.fromResponseBody(problem('PROFILE_TERMINAL')),
        'PROFILE_TERMINAL',
      );
    });

    test('answers null for the two uncoded body shapes that actually occur', () {
      // A live Spring Boot 4.1.0 server renders `{timestamp,status,error,path}`; MockMvc renders an
      // EMPTY body for the same exception. Both are real, which is why presence-of-code is the only
      // safe discriminator.
      expect(
        JourneyCode.fromResponseBody({
          'timestamp': '2026-09-05T00:00:00Z',
          'status': 409,
          'error': 'Conflict',
          'path': '/api/v1/liveness/result',
        }),
        isNull,
      );
      expect(JourneyCode.fromResponseBody(const <String, Object?>{}), isNull);
    });

    test('answers null for a non-Map body rather than guessing', () {
      expect(JourneyCode.fromResponseBody('LIVENESS_BLOCKED'), isNull);
      expect(JourneyCode.fromResponseBody(null), isNull);
      expect(JourneyCode.fromResponseBody(const {'code': ''}), isNull);
    });
  });

  group('arm order', () {
    test('connection-class failures come first, before any status', () {
      for (final type in [
        DioExceptionType.connectionError,
        DioExceptionType.connectionTimeout,
        DioExceptionType.receiveTimeout,
        DioExceptionType.sendTimeout,
        DioExceptionType.unknown,
      ]) {
        final mapped = mapJourneyError(
          DioException(
            requestOptions: RequestOptions(path: '/x'),
            type: type,
          ),
        );
        expect(mapped, isA<BackendUnreachableException>(), reason: '$type');
      }
    });

    test(
      'any 5xx is connectivity — including /liveness/token\'s unmapped 500 when Uqudo is down',
      () {
        // customer.md Stage 10 makes a token failure a connectivity failure explicitly NOT counted
        // against the budget, and it is indistinguishable on the wire from any other 500. Folding it
        // in here is what lets the screen say "no attempt was counted" rather than guess.
        expect(
          mapJourneyError(response(500, null)),
          isA<BackendUnreachableException>(),
        );
        expect(
          mapJourneyError(response(503, null)),
          isA<BackendUnreachableException>(),
        );
      },
    );

    test(
      'a CODED 400 outranks the status-only arm — this is the whole reason for the ordering',
      () {
        // LIVENESS_REJECTED spent one of five attempts. An uncoded 400 spent nothing. Collapsing them
        // would silently mislead the customer about their remaining budget.
        expect(
          mapJourneyError(response(400, {'code': 'LIVENESS_REJECTED'})),
          isA<LivenessRejectedException>(),
        );
        expect(
          mapJourneyError(response(400, null)),
          isA<JourneyClientErrorException>(),
        );
      },
    );

    test(
      'SIGNATURE_REJECTED is its own type and costs nothing — Stage 11 has no budget',
      () {
        expect(
          mapJourneyError(response(400, {'code': 'SIGNATURE_REJECTED'})),
          isA<SignatureRejectedException>(),
        );
      },
    );

    test(
      'an UNKNOWN coded 400 degrades to the harmless meaning, never to "an attempt was spent"',
      () {
        // A backend change this app version does not know about. Guessing in the alarming direction
        // would tell a customer they lost an attempt they may still have.
        expect(
          mapJourneyError(response(400, {'code': 'SOME_FUTURE_400'})),
          isA<JourneyClientErrorException>(),
        );
      },
    );
  });

  group('409s carry their code, and blockedUntil is never trusted', () {
    test('the code selects the screen, not the status', () {
      expect(
        (mapJourneyError(response(409, problem('LIVENESS_ALREADY_PASSED')))
                as JourneyConflictException)
            .code,
        JourneyCode.livenessAlreadyPassed,
      );
      expect(
        (mapJourneyError(response(409, problem('RESCAN_REQUIRED')))
                as JourneyConflictException)
            .code,
        JourneyCode.rescanRequired,
      );
    });

    test(
      'LIVENESS_BLOCKED carries the deadline when the controller sends one',
      () {
        final mapped =
            mapJourneyError(
                  response(
                    409,
                    problem(
                      'LIVENESS_BLOCKED',
                      blockedUntil: '2026-09-06T12:00:00Z',
                    ),
                  ),
                )
                as JourneyConflictException;
        expect(mapped.code, JourneyCode.livenessBlocked);
        expect(mapped.blockedUntil, DateTime.utc(2026, 9, 6, 12));
      },
    );

    test('an absent, unparseable or elapsed deadline all map without throwing', () {
      // The controller omits the member when null; the refusal keys on stored state, not on the
      // block still being live, so an already-elapsed instant is a real answer. All three are
      // handled where it is rendered.
      expect(
        (mapJourneyError(response(409, problem('LIVENESS_BLOCKED')))
                as JourneyConflictException)
            .blockedUntil,
        isNull,
      );
      expect(
        (mapJourneyError(
                  response(
                    409,
                    problem('LIVENESS_BLOCKED', blockedUntil: 'not-a-date'),
                  ),
                )
                as JourneyConflictException)
            .blockedUntil,
        isNull,
      );
      expect(
        (mapJourneyError(
                  response(
                    409,
                    problem(
                      'LIVENESS_BLOCKED',
                      blockedUntil: '2020-01-01T00:00:00Z',
                    ),
                  ),
                )
                as JourneyConflictException)
            .blockedUntil,
        DateTime.utc(2020),
      );
    });

    test(
      'an UNCODED 409 degrades to a re-sync rather than inventing a meaning',
      () {
        final mapped =
            mapJourneyError(response(409, null)) as JourneyConflictException;
        expect(mapped.code, JourneyCode.unknown);
      },
    );
  });

  test('404 is its own type, and anything else is rethrown untouched', () {
    expect(
      mapJourneyError(response(404, null)),
      isA<JourneyProfileNotFoundException>(),
    );
    // Not flattened into a wrong specific error.
    expect(mapJourneyError(response(418, null)), isA<DioException>());
  });
}
