import 'dart:async';

import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/database/database_providers.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/entry/entry_models.dart';
import 'package:mobile/core/journey/journey_error.dart';
import 'package:mobile/core/journey/journey_models.dart';
import 'package:mobile/core/journey/journey_providers.dart';
import 'package:mobile/core/journey/journey_repository.dart';
import 'package:mobile/core/liveness/liveness_models.dart';
import 'package:mobile/core/liveness/liveness_providers.dart';
import 'package:mobile/core/liveness/liveness_repository.dart';
import 'package:mobile/core/liveness/uqudo_face_scanner.dart';
import 'package:mobile/features/liveness/stage10_screen.dart';

import '../../core/liveness/fakes.dart';

/// Stage 10 — liveness and face matching. Like Stage 8's file, this is a consumer proof: every code
/// the backend can answer with drives the screen customer.md specifies, and nothing counts attempts
/// locally.
void main() {
  Future<
    ({
      FakeLivenessApi api,
      FakeJourneyApi journeyApi,
      FakeUqudoFaceScanner scanner,
      String? Function() landedPath,
    })
  >
  pumpStage10(
    WidgetTester tester, {
    RetainedFaceStore? retainedFaces,
    Duration? faceTimeout,
  }) async {
    final db = SessionDatabase.forTesting();
    addTearDown(db.close);
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

    final api = FakeLivenessApi();
    final journeyApi = FakeJourneyApi();
    final scanner = FakeUqudoFaceScanner();
    String? landedPath;

    GoRoute stub(String path) => GoRoute(
      path: path,
      builder: (context, state) {
        landedPath = path;
        return Scaffold(body: Text('landed $path'));
      },
    );

    final router = GoRouter(
      initialLocation: '/stage-10',
      routes: [
        GoRoute(
          path: '/stage-10',
          builder: (context, state) => const Stage10Screen(),
        ),
        stub('/stage-8'),
        stub('/stage-9'),
        stub('/stage-11'),
        stub('/final-stages'),
        // BL-109: where the block screen's exit now goes.
        stub('/'),
        GoRoute(
          path: '/terminal',
          builder: (context, state) {
            landedPath = '/terminal';
            return Scaffold(body: Text(state.extra as String));
          },
        ),
        GoRoute(
          path: '/ended',
          builder: (context, state) {
            landedPath = '/ended';
            return const Scaffold(body: Text('ended'));
          },
        ),
      ],
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          sessionDatabaseProvider.overrideWithValue(db),
          livenessApiProvider.overrideWithValue(api),
          journeyApiProvider.overrideWithValue(journeyApi),
          uqudoFaceScannerProvider.overrideWithValue(scanner),
          if (retainedFaces != null)
            retainedFaceStoreProvider.overrideWithValue(retainedFaces),
          if (faceTimeout != null)
            livenessRepositoryProvider.overrideWith(
              (ref) => LivenessRepository(
                api: api,
                scanner: scanner,
                journey: JourneyRepository(api: journeyApi, sessionDb: db),
                retainedFaces: retainedFaces ?? RetainedFaceStore(),
                faceTimeout: faceTimeout,
              ),
            ),
        ],
        child: MaterialApp.router(
          routerConfig: router,
          builder: (context, child) =>
              Directionality(textDirection: TextDirection.rtl, child: child!),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return (
      api: api,
      journeyApi: journeyApi,
      scanner: scanner,
      landedPath: () => landedPath,
    );
  }

  Future<void> begin(WidgetTester tester) async {
    await tester.tap(find.widgetWithText(FilledButton, 'ابدأ التحقق'));
    await tester.pumpAndSettle();
  }

  group('preparation', () {
    testWidgets(
      'carries the environmental guidance that actually changes pass rates',
      (tester) async {
        await pumpStage10(tester);

        // customer.md: liveness failures are "overwhelmingly environmental — backlight, low light,
        // angle — and a customer told to move somewhere brighter *before* starting often passes
        // first time". This screen is the only place that advice can land.
        expect(find.textContaining('إضاءة جيدة'), findsOneWidget);
        expect(find.textContaining('مستوى العينين'), findsOneWidget);
        expect(find.textContaining('النظارات الشمسية'), findsOneWidget);
        // And it must say what is captured and that it is stored.
        expect(find.textContaining('حفظها مع ملفك'), findsOneWidget);
      },
    );

    testWidgets('no token is minted until the customer actually taps', (
      tester,
    ) async {
      final h = await pumpStage10(tester);

      // The face session lives 600s; minting one on arrival would leave a customer who read the
      // guidance holding a dead token.
      expect(h.api.issueTokenCallCount, 0);
      await begin(tester);
      expect(h.api.issueTokenCallCount, 1);
    });
  });

  group('outcomes', () {
    testWidgets('passing both liveness and face match goes to Stage 11', (
      tester,
    ) async {
      final h = await pumpStage10(tester);
      await begin(tester);
      expect(h.landedPath(), '/stage-11');
    });

    testWidgets(
      'a failed match shows one generic failure — no match level, no attempt count',
      (tester) async {
        final h = await pumpStage10(tester);
        h.api.resultToServe = const FaceResult(
          profileId: 'p1',
          passed: false,
          matchLevel: 2,
        );

        await begin(tester);

        // customer.md: "the customer sees one outcome: it worked, or it did not." The match level is
        // recorded for the bank and never shown, and no count is displayed because none exists.
        expect(find.text('لم يكتمل التحقق'), findsOneWidget);
        expect(find.textContaining('2'), findsNothing);
      },
    );

    testWidgets(
      'a failure that exhausts the budget shows the block with its deadline instead',
      (tester) async {
        final h = await pumpStage10(tester);
        h.api.resultToServe = FaceResult(
          profileId: 'p1',
          passed: false,
          matchLevel: 1,
          blockedUntil: DateTime.now().add(const Duration(hours: 24)),
        );

        await begin(tester);

        expect(find.text('التحقق من الهوية غير متاح مؤقتًا'), findsOneWidget);
        // No probe needed on this path — /result already carries the deadline.
        expect(h.journeyApi.currentPointerCallCount, 0);
      },
    );
  });

  group('the terminated paths all probe for the block (gap 1)', () {
    testWidgets(
      'a cancel that TRIGGERED the block shows the block, not "try again"',
      (tester) async {
        final h = await pumpStage10(tester);
        h.scanner.errorToThrow = const UqudoFaceFailure('USER_CANCEL');
        h.journeyApi.pointerToServe = JourneyPointer(
          profileId: 'p1',
          stage: JourneyStage.livenessBlocked,
          blockedUntil: DateTime.now().add(const Duration(hours: 24)),
        );

        await begin(tester);

        // Without the probe this would say "try again", the customer would tap, and only THEN learn
        // they are blocked — `/terminated` answers a bare ack that cannot distinguish the fifth
        // attempt from the second.
        expect(find.text('التحقق من الهوية غير متاح مؤقتًا'), findsOneWidget);
        expect(h.api.reportTerminatedCallCount, 1);
        expect(h.journeyApi.currentPointerCallCount, 1);
      },
    );

    testWidgets('an ordinary cancel spends the attempt and offers a retry', (
      tester,
    ) async {
      final h = await pumpStage10(tester);
      h.scanner.errorToThrow = const UqudoFaceFailure('USER_CANCEL');

      await begin(tester);

      expect(find.text('لم يكتمل التحقق'), findsOneWidget);
      expect(h.api.lastTerminatedArgs!['sdkErrorCode'], 'USER_CANCEL');
    });

    testWidgets(
      'the SDK exhausting its own liveness retries reports and forwards the partial JWS',
      (tester) async {
        final h = await pumpStage10(tester);
        h.scanner.errorToThrow = const UqudoFaceFailure(
          'SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS',
          partialJws: 'partial.face.jws',
        );

        await begin(tester);

        // BL-028's whole point: this is the only documented route to a signed artifact from a
        // terminated session, and it goes up raw.
        expect(h.api.lastTerminatedArgs!['partialJws'], 'partial.face.jws');
      },
    );

    testWidgets(
      'an UNPARSEABLE SDK error still reports a non-blank code and no partial JWS',
      (tester) async {
        // Named for what it actually drives. `errorToThrow` throws immediately, so the timeout is
        // never engaged here — this is the "the SDK said something we could not read" path, which
        // reuses the app-minted code rather than inventing a second one.
        final h = await pumpStage10(tester);
        h.scanner.errorToThrow = const UqudoFaceFailure(null);

        await begin(tester);

        expect(h.api.lastTerminatedArgs!['sdkErrorCode'], appNoSdkResponseCode);
        expect(h.api.lastTerminatedArgs!['partialJws'], isNull);
      },
    );

    testWidgets(
      'a SILENT SDK resolves the screen instead of spinning forever — F-1, at the screen level',
      (tester) async {
        // The repository test pins the 120 s duration; this one proves the screen actually leaves
        // its `working` state when the SDK never answers, which is the F-1 failure mode itself.
        // The duration is injected so the test does not wait two real minutes.
        final h = await pumpStage10(tester, faceTimeout: const Duration(milliseconds: 50));
        h.scanner.pendingSession = Completer<String>();

        await tester.tap(find.widgetWithText(FilledButton, 'ابدأ التحقق'));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 200));
        await tester.pumpAndSettle();

        expect(find.byType(CircularProgressIndicator), findsNothing);
        expect(find.text('لم يكتمل التحقق'), findsOneWidget);
        expect(h.api.lastTerminatedArgs!['sdkErrorCode'], appNoSdkResponseCode);
        expect(h.api.lastTerminatedArgs!['partialJws'], isNull);
      },
    );

    testWidgets('a camera-permission failure spends NOTHING and says so', (
      tester,
    ) async {
      final h = await pumpStage10(tester);
      h.scanner.errorToThrow = const UqudoFaceFailure(
        'SESSION_INVALIDATED_CAMERA_PERMISSION_NOT_GRANTED',
      );

      await begin(tester);

      expect(find.text('تعذر الوصول إلى الكاميرا'), findsOneWidget);
      expect(find.textContaining('لم تُحتسب هذه المحاولة'), findsOneWidget);
      // The one class of failure that must not reach `/terminated`.
      expect(h.api.reportTerminatedCallCount, 0);
    });
  });

  group('the conflict codes each drive their own screen', () {
    testWidgets(
      'LIVENESS_ALREADY_PASSED moves the customer on rather than showing an error',
      (tester) async {
        final h = await pumpStage10(tester);
        h.api.issueTokenError = const JourneyConflictException(
          JourneyCode.livenessAlreadyPassed,
        );

        await begin(tester);

        expect(h.landedPath(), '/stage-11');
      },
    );

    testWidgets(
      'RESCAN_REQUIRED sends them back to the scan — there is no portrait to match',
      (tester) async {
        final h = await pumpStage10(tester);
        h.api.issueTokenError = const JourneyConflictException(
          JourneyCode.rescanRequired,
        );

        await begin(tester);

        expect(h.landedPath(), '/stage-8');
      },
    );

    testWidgets('REGISTRY_PENDING sends them to Stage 9\'s pause', (
      tester,
    ) async {
      final h = await pumpStage10(tester);
      h.api.issueTokenError = const JourneyConflictException(
        JourneyCode.registryPending,
      );

      await begin(tester);

      expect(h.landedPath(), '/stage-9');
    });

    testWidgets('PROFILE_TERMINAL ends the journey', (tester) async {
      final h = await pumpStage10(tester);
      h.api.issueTokenError = const JourneyConflictException(
        JourneyCode.profileTerminal,
      );

      await begin(tester);

      expect(h.landedPath(), '/ended');
    });

    testWidgets(
      'LIVENESS_BLOCKED on the token call shows the block with its deadline',
      (tester) async {
        final h = await pumpStage10(tester);
        h.api.issueTokenError = JourneyConflictException(
          JourneyCode.livenessBlocked,
          blockedUntil: DateTime.now().add(const Duration(hours: 24)),
        );

        await begin(tester);

        expect(find.text('التحقق من الهوية غير متاح مؤقتًا'), findsOneWidget);
      },
    );

    testWidgets(
      'BL-109 — the Stage 10 block screen has a way out, which it shipped without',
      (tester) async {
        // Stage 10 was the ONE block screen in the journey that passed no exit control at all,
        // while Stage 8 and Stage 9 both did — and it is reached by `context.go`, so there was no
        // AppBar back either. A customer who hit the liveness block could only force-quit.
        final h = await pumpStage10(tester);
        h.api.issueTokenError = JourneyConflictException(
          JourneyCode.livenessBlocked,
          blockedUntil: DateTime.now().add(const Duration(hours: 24)),
        );

        await begin(tester);

        expect(find.widgetWithText(OutlinedButton, 'العودة إلى البداية'), findsOneWidget);
        await tester.tap(find.widgetWithText(OutlinedButton, 'العودة إلى البداية'));
        await tester.pumpAndSettle();
        expect(h.landedPath(), '/');
      },
    );

    testWidgets(
      'BL-114(b) — Stage 10 gets the same honest refusal copy as Stage 8, not "try again now"',
      (tester) async {
        // The liveness block is the same widget and the same three cases. `LivenessService` has
        // its own non-cap arm that returns an already-elapsed stored deadline, so this screen can
        // reach the refusal case without any lifetime cap being involved — which is exactly why
        // the copy describes the observation rather than naming a limit.
        final h = await pumpStage10(tester);
        h.api.issueTokenError = JourneyConflictException(
          JourneyCode.livenessBlocked,
          blockedUntil: DateTime.now().subtract(const Duration(hours: 3)),
        );

        await begin(tester);

        expect(
          find.text(
            'انتهت مدة الانتظار، ومع ذلك لا يزال هذا الإجراء غير متاح. يرجى زيارة أقرب فرع.',
          ),
          findsOneWidget,
        );
        expect(find.widgetWithText(FilledButton, 'إعادة المحاولة'), findsNothing);
      },
    );

    testWidgets(
      'STATE_CONFLICT and an UNKNOWN code both re-sync through the pointer, never a '
      'guessed screen',
      (tester) async {
        for (final code in [JourneyCode.stateConflict, JourneyCode.unknown]) {
          final h = await pumpStage10(tester);
          h.api.issueTokenError = JourneyConflictException(code);

          await begin(tester);

          expect(h.landedPath(), '/final-stages', reason: code.name);
        }
      },
    );

    testWidgets(
      'ARTIFACT_EXPIRED says the capture is unusable, not that the upload can retry',
      (tester) async {
        final h = await pumpStage10(tester);
        h.api.submitResultError = const JourneyConflictException(
          JourneyCode.artifactExpired,
        );

        await begin(tester);

        expect(find.text('انتهت صلاحية التحقق'), findsOneWidget);
      },
    );
  });

  group('connectivity costs nothing, and a lost acknowledgement is retryable', () {
    testWidgets('a token failure says plainly that no attempt was counted', (
      tester,
    ) async {
      final h = await pumpStage10(tester);
      h.api.issueTokenError = const BackendUnreachableException('offline');

      await begin(tester);

      expect(find.textContaining('لم تُحتسب هذه المحاولة'), findsOneWidget);
      expect(h.api.reportTerminatedCallCount, 0);
    });

    testWidgets(
      'a failed upload offers a re-send, and says minutes rather than Stage 8\'s hours',
      (tester) async {
        final h = await pumpStage10(tester);
        h.api.submitResultError = const BackendUnreachableException('offline');

        await begin(tester);

        expect(find.text('تعذر إرسال نتيجة التحقق'), findsOneWidget);
        // The face artifact's window is ~600s, roughly a twelfth of the scan's two hours. Reusing
        // Stage 8's copy here would over-promise by an order of magnitude.
        expect(find.textContaining('دقائق قليلة'), findsOneWidget);

        h.api.submitResultError = null;
        await tester.tap(find.widgetWithText(FilledButton, 'إعادة الإرسال'));
        await tester.pumpAndSettle();

        // Re-sent without a second face session.
        expect(h.scanner.callCount, 1);
        expect(h.landedPath(), '/stage-11');
      },
    );

    testWidgets(
      'a retained capture from ANOTHER session is discarded, not re-offered',
      (tester) async {
        final store = RetainedFaceStore()
          ..keep(
            const RetainedFaceCapture(
              profileId: 'a-different-profile',
              faceSessionId: 'fs-old',
              jws: 'stale.jws',
            ),
          );

        await pumpStage10(tester, retainedFaces: store);

        // The store is process-scoped and abandonment does not clear it, so a capture can outlive
        // the session it belongs to. Re-offering it would post the previous session's profile id.
        expect(
          find.widgetWithText(FilledButton, 'ابدأ التحقق'),
          findsOneWidget,
        );
        expect(store.retained, isNull);
      },
    );
  });

  testWidgets('no attempt count appears anywhere on this screen, in any state', (
    tester,
  ) async {
    // R-052's named failure mode. `attemptsRemaining` is on no liveness wire; the customer learns
    // their budget is spent from a server-sent block, never from arithmetic done here.
    final h = await pumpStage10(tester);
    h.api.resultToServe = const FaceResult(
      profileId: 'p1',
      passed: false,
      matchLevel: 3,
    );
    await begin(tester);

    for (final n in ['1', '2', '3', '4', '5']) {
      expect(
        find.textContaining('محاولات $n'),
        findsNothing,
        reason: 'no remaining-attempt count may be rendered',
      );
    }
  });
}
