import 'dart:async';

// `show Value` only: drift also exports `isNull`/`isNotNull` as SQL expression builders, which
// collide with the matchers of the same name.
import 'package:drift/drift.dart' show Value;
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/entry/entry_models.dart';
import 'package:mobile/core/journey/journey_error.dart';
import 'package:mobile/core/journey/journey_models.dart';
import 'package:mobile/core/journey/journey_repository.dart';
import 'package:mobile/core/liveness/liveness_models.dart';
import 'package:mobile/core/liveness/liveness_repository.dart';
import 'package:mobile/core/liveness/uqudo_face_scanner.dart';

import 'fakes.dart';

/// `LivenessRepository`'s three jobs: driving one liveness attempt end to end, owning the retained
/// face-JWS lifecycle, and making a terminated attempt tell the truth about the attempt budget.
void main() {
  late SessionDatabase db;
  late FakeLivenessApi api;
  late FakeJourneyApi journeyApi;
  late FakeUqudoFaceScanner scanner;
  late RetainedFaceStore store;
  late JourneyRepository journey;
  late LivenessRepository repository;

  setUp(() async {
    db = SessionDatabase.forTesting();
    await db
        .into(db.localProgress)
        .insertOnConflictUpdate(
          LocalProgressCompanion.insert(
            id: const Value(0),
            verifiedBranchCode: '16',
            verifiedAccountNumber: '0000000001',
            resumeStage: resumeStageBeyondStage9,
            profileId: const Value('p1'),
            updatedAt: DateTime.now(),
          ),
        );
    api = FakeLivenessApi();
    journeyApi = FakeJourneyApi();
    scanner = FakeUqudoFaceScanner();
    store = RetainedFaceStore();
    journey = JourneyRepository(api: journeyApi, sessionDb: db);
    repository = LivenessRepository(
      api: api,
      scanner: scanner,
      journey: journey,
      retainedFaces: store,
    );
  });

  tearDown(() => db.close());

  LivenessRepository withFastTimeout() => LivenessRepository(
    api: api,
    scanner: scanner,
    journey: journey,
    retainedFaces: store,
    faceTimeout: const Duration(milliseconds: 50),
  );

  group('runFaceCheck', () {
    test(
      'mints a token at the point of use and hands the SDK the BACKEND\'s face session id',
      () async {
        final result = await repository.runFaceCheck();

        expect(api.issueTokenCallCount, 1);
        expect(scanner.lastArgs!['faceSessionId'], 'fs-1');
        expect(scanner.lastArgs!['accessToken'], 'token-never-stored');
        // The same id goes back up with the result, so the backend can check it against its own
        // record rather than against what this request claims.
        expect(api.lastSubmitArgs!['faceSessionId'], 'fs-1');
        expect(api.lastSubmitArgs!['jws'], 'header.face.signature');
        expect(result.passed, isTrue);
      },
    );

    test(
      'a passing result clears the retained capture — there is nothing left to re-send',
      () async {
        await repository.runFaceCheck();
        expect(repository.retainedFace, isNull);
      },
    );

    test(
      'a judged FAILURE also clears it: the attempt is spent and re-sending would be judged '
      'identically',
      () async {
        api.resultToServe = FaceResult(
          profileId: 'p1',
          passed: false,
          matchLevel: 1,
          blockedUntil: DateTime.now().add(const Duration(hours: 24)),
        );

        final result = await repository.runFaceCheck();

        expect(result.passed, isFalse);
        expect(result.blockedUntil, isNotNull);
        expect(repository.retainedFace, isNull);
      },
    );
  });

  group(
    'the silent-SDK timeout — Stage 10 needs its own, it does not inherit F-1\'s',
    () {
      test('the shipped no-answer timeout is the 120s reasoned for the FACE flow, not the scan\'s '
          '180s', () {
        // Not a magic number and not a copy of Stage 8's: face latency was observed at 42-49s
        // (n=2) against the scan's 52s, and unlike the scan flow there is a hard 600s ceiling —
        // the face session and its reference image are deleted then, and a JWS arriving after
        // `exp` is refused as ARTIFACT_EXPIRED at no cost to the customer. See
        // `uqudoFaceNoAnswerTimeout`'s own doc comment. Pinned so changing it is deliberate.
        expect(uqudoFaceNoAnswerTimeout, const Duration(seconds: 120));
        expect(uqudoFaceNoAnswerTimeout.inSeconds, lessThan(600));
      });

      test(
        'an SDK that never answers resolves as a terminated attempt instead of hanging',
        () async {
          scanner.pendingSession = Completer<String>();
          final repo = withFastTimeout();

          final terminated = await _captureTerminated(repo.runFaceCheck());

          expect(terminated.sdkErrorCode, appNoSdkResponseCode);
          expect(terminated.faceSessionId, 'fs-1');
          expect(terminated.cameraUnavailable, isFalse);
          expect(api.submitResultCallCount, 0);
          expect(repo.retainedFace, isNull);
        },
      );

      test(
        'the timeout path carries NO partial JWS — the two terminated paths are not symmetric',
        () async {
          // There is no PlatformException on this path, so there is no `data` field for BL-028's
          // partial artifact to arrive in. Asserted rather than assumed, because a future refactor
          // that "helpfully" defaulted it to something non-null would be forwarding an artifact the
          // SDK never produced.
          scanner.pendingSession = Completer<String>();

          final terminated = await _captureTerminated(
            withFastTimeout().runFaceCheck(),
          );

          expect(terminated.partialJws, isNull);
        },
      );

      test(
        'a JWS arriving AFTER the timeout is dropped, never retained and never uploaded',
        () async {
          // This is why the timeout wraps the faceSession await alone. `Future.timeout` does not cancel
          // what it wraps, so the late answer genuinely arrives — what must be true is that it reaches
          // nothing. Wrapped further out it would still `keep()` into the process-scoped store and
          // still fire an upload, against an attempt the screen has by then already terminated.
          final pending = Completer<String>();
          scanner.pendingSession = pending;
          final repo = withFastTimeout();

          await _captureTerminated(repo.runFaceCheck());

          pending.complete('late.face.jws');
          await Future<void>.delayed(const Duration(milliseconds: 50));

          expect(repo.retainedFace, isNull);
          expect(api.submitResultCallCount, 0);
        },
      );
    },
  );

  group('BL-028 — the partial JWS is forwarded raw on the SDK-error path', () {
    test(
      'a terminated session\'s partial artifact reaches the terminated report untouched',
      () async {
        scanner.errorToThrow = const UqudoFaceFailure(
          'SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS',
          partialJws: 'partial.face.jws',
        );

        final terminated = await _captureTerminated(repository.runFaceCheck());
        await repository.reportTerminated(
          faceSessionId: terminated.faceSessionId,
          sdkErrorCode: terminated.sdkErrorCode,
          partialJws: terminated.partialJws,
        );

        expect(
          terminated.sdkErrorCode,
          'SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS',
        );
        // Byte-identical: the device never decodes a JWS, so it cannot have altered it.
        expect(api.lastTerminatedArgs!['partialJws'], 'partial.face.jws');
      },
    );

    test(
      'a camera-permission failure is flagged so no attempt is spent on it',
      () async {
        scanner.errorToThrow = const UqudoFaceFailure(
          'SESSION_INVALIDATED_CAMERA_PERMISSION_NOT_GRANTED',
        );

        final terminated = await _captureTerminated(repository.runFaceCheck());

        expect(terminated.cameraUnavailable, isTrue);
        // The repository does not report it either — spending an attempt is the caller's decision.
        expect(api.reportTerminatedCallCount, 0);
      },
    );

    test(
      'an unparseable SDK error still sends a non-blank code, which the endpoint requires',
      () async {
        scanner.errorToThrow = const UqudoFaceFailure(null);

        final terminated = await _captureTerminated(repository.runFaceCheck());

        expect(terminated.sdkErrorCode, appNoSdkResponseCode);
        expect(terminated.sdkErrorCode, isNotEmpty);
      },
    );

    test('the app-minted code cannot be mistaken for a Uqudo status code', () {
      // It lands verbatim in the audit payload beside real SessionStatusCodes and the backend
      // validates nothing about it, so the prefix is the only thing telling an operator that this
      // token came from the app rather than the vendor.
      expect(appNoSdkResponseCode.startsWith('APP_'), isTrue);
      expect(appNoSdkResponseCode.length, lessThanOrEqualTo(64));
    });
  });

  group('reportTerminated probes for the block — the ack alone cannot say', () {
    test(
      'a terminated attempt that TRIGGERED the block returns its deadline',
      () async {
        // `/terminated` answers a bare ack: `LivenessService` applies the 24-hour block and returns
        // normally, so the call that spends the fifth attempt looks exactly like the one that spends
        // the second. Without this read the customer is shown "try again", taps it, and only THEN
        // learns they are blocked.
        final deadline = DateTime.utc(2026, 9, 6, 12);
        journeyApi.pointerToServe = JourneyPointer(
          profileId: 'p1',
          stage: JourneyStage.livenessBlocked,
          blockedUntil: deadline,
        );

        final blockedUntil = await repository.reportTerminated(
          faceSessionId: 'fs-1',
          sdkErrorCode: 'USER_CANCEL',
        );

        expect(blockedUntil, deadline);
        expect(journeyApi.currentPointerCallCount, 1);
      },
    );

    test(
      'an ordinary terminated attempt returns null and stays on the failure screen',
      () async {
        journeyApi.pointerToServe = const JourneyPointer(
          profileId: 'p1',
          stage: JourneyStage.liveness,
        );

        expect(
          await repository.reportTerminated(
            faceSessionId: 'fs-1',
            sdkErrorCode: 'USER_CANCEL',
          ),
          isNull,
        );
      },
    );

    test(
      'the probe is the non-mutating pointer read, NEVER /token — /token mints a real Uqudo '
      'face session',
      () async {
        await repository.reportTerminated(
          faceSessionId: 'fs-1',
          sdkErrorCode: 'USER_CANCEL',
        );

        expect(journeyApi.currentPointerCallCount, 1);
        // The whole point: probing with `/token` would burn a Uqudo operation (and write
        // `pending_face_session_id`) just to ask a question.
        expect(api.issueTokenCallCount, 0);
      },
    );

    test(
      'a failing probe does not turn a recorded attempt into a visible error',
      () async {
        // The attempt HAS been recorded by the time the probe runs. Surfacing the probe's failure
        // would misreport what happened; degrading to "no block known" shows the ordinary failure
        // screen, which is exactly the pre-probe behaviour.
        journeyApi.currentPointerError = const BackendUnreachableException(
          'offline',
        );

        expect(
          await repository.reportTerminated(
            faceSessionId: 'fs-1',
            sdkErrorCode: 'USER_CANCEL',
          ),
          isNull,
        );
        expect(api.reportTerminatedCallCount, 1);
      },
    );
  });

  group(
    'retention — a lost acknowledgement retries the same JWS, never a repeated face check',
    () {
      test(
        'an unreachable backend leaves the capture retained and retryable',
        () async {
          api.submitResultError = const BackendUnreachableException('offline');

          await expectLater(
            repository.runFaceCheck(),
            throwsA(isA<BackendUnreachableException>()),
          );
          expect(repository.retainedFace, isNotNull);
          expect(repository.retainedFace!.jws, 'header.face.signature');
          expect(repository.retainedFace!.faceSessionId, 'fs-1');

          api.submitResultError = null;
          final result = await repository.retryUpload();

          expect(result.passed, isTrue);
          // Re-posted byte-identically, and NOT via a second face session.
          expect(scanner.callCount, 1);
          expect(api.issueTokenCallCount, 1);
          expect(repository.retainedFace, isNull);
        },
      );

      test(
        'LIVENESS_REJECTED clears it — an attempt is gone and the same bytes would fail the '
        'same way',
        () async {
          api.submitResultError = const LivenessRejectedException();

          await expectLater(
            repository.runFaceCheck(),
            throwsA(isA<LivenessRejectedException>()),
          );
          expect(repository.retainedFace, isNull);
        },
      );

      for (final code in [
        JourneyCode.artifactExpired,
        JourneyCode.auditTrailUnavailable,
      ]) {
        test(
          '${code.wire} clears it — the capture can no longer be used, it is not a retryable '
          'upload',
          () async {
            api.submitResultError = JourneyConflictException(code);

            await expectLater(
              repository.runFaceCheck(),
              throwsA(isA<JourneyConflictException>()),
            );
            expect(repository.retainedFace, isNull);
          },
        );
      }

      test('every OTHER conflict leaves it in place', () async {
        api.submitResultError = const JourneyConflictException(
          JourneyCode.stateConflict,
        );

        await expectLater(
          repository.runFaceCheck(),
          throwsA(isA<JourneyConflictException>()),
        );
        expect(repository.retainedFace, isNotNull);
      });

      test(
        'retryUpload with nothing retained is a caller bug, not a silent no-op',
        () {
          expect(repository.retryUpload, throwsA(isA<StateError>()));
        },
      );
    },
  );

  test(
    'no attempt counter exists anywhere on this seam — R-052\'s named failure mode',
    () {
      // `attemptsRemaining` is on no liveness wire by deliberate backend decision, and the app must
      // not invent one. The models carry no count, and nothing here accumulates one. Asserted rather
      // than left to review, the way S5-07 asserted the same thing for Stage 8.
      const result = FaceResult(profileId: 'p1', passed: false, matchLevel: 2);
      expect(result.toString(), isNot(contains('attempts')));
      const capture = RetainedFaceCapture(
        profileId: 'p1',
        faceSessionId: 'fs-1',
        jws: 'j',
      );
      expect(capture.toString(), isNot(contains('attempt')));
    },
  );
}

/// Runs [future], expecting it to fail with a [FaceAttemptTerminated], and hands back the exception
/// so a test can assert on what the screen would receive.
Future<FaceAttemptTerminated> _captureTerminated(Future<Object?> future) async {
  try {
    await future;
  } on FaceAttemptTerminated catch (e) {
    return e;
  }
  fail('expected a FaceAttemptTerminated');
}
