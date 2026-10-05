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
import 'package:mobile/features/submission/confirmation_screen.dart';
import 'package:mobile/features/submission/final_stages_gate_screen.dart';
import 'package:mobile/features/submission/outcome_screens.dart';
import 'package:mobile/features/submission/stage12_screen.dart';

import '../../core/liveness/fakes.dart';

/// Stages 12 and 13 — submission, the confirmation screen, and the resume gate.
///
/// The hard constraint this file exists to protect is BL-058: the confirmation screen's channels
/// come from `POST /submission/current` on EVERY path, never from the submit response.
void main() {
  late SessionDatabase db;
  late FakeJourneyApi api;
  String? landed;
  String? landedExtra;

  setUp(() async {
    db = SessionDatabase.forTesting();
    api = FakeJourneyApi();
    landed = null;
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
  });

  tearDown(() => db.close());

  /// Pumps with the fake already seeded, so a screen that reads the pointer in `initState` sees the
  /// intended answer on its first frame.
  Future<void> pump(
    WidgetTester tester,
    String initialLocation, {
    double textScale = 1.0,
  }) async {
    GoRoute stub(String path) => GoRoute(
      path: path,
      builder: (context, state) {
        landed = path;
        return Scaffold(body: Text('landed $path'));
      },
    );

    final router = GoRouter(
      initialLocation: initialLocation,
      routes: [
        GoRoute(
          path: '/stage-12',
          builder: (context, state) => const Stage12Screen(),
        ),
        GoRoute(
          path: '/final-stages',
          builder: (context, state) => const FinalStagesGateScreen(),
        ),
        GoRoute(
          path: '/confirmation',
          builder: (context, state) {
            landed = '/confirmation';
            return ConfirmationScreen(args: state.extra! as ConfirmationArgs);
          },
        ),
        stub('/'),
        stub('/stage-10'),
        stub('/stage-11'),
        stub('/ended'),
        stub('/stage-8'),
        // BL-106: the two outcome routes take the reference number as `extra`, so these stubs
        // record it — a screen that landed without one would be the defect, not a passing test.
        // The REAL screens, not stubs: their `initState` is what discharges customer.md
        // l.1137-1138's clear, so a stub here would make the privacy assertion below untestable
        // — the same trap `@agent-reviewer` found in `account_entry_screen_test`.
        GoRoute(
          path: '/approved',
          builder: (context, state) {
            landed = '/approved';
            landedExtra = state.extra as String?;
            return ApprovedScreen(referenceNumber: state.extra! as String);
          },
        ),
        GoRoute(
          path: '/rejected',
          builder: (context, state) {
            landed = '/rejected';
            landedExtra = state.extra as String?;
            return RejectedScreen(referenceNumber: state.extra! as String);
          },
        ),
        GoRoute(
          path: '/terminal',
          builder: (context, state) {
            landed = '/terminal';
            return Scaffold(body: Text(state.extra as String));
          },
        ),
      ],
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          sessionDatabaseProvider.overrideWithValue(db),
          journeyApiProvider.overrideWithValue(api),
        ],
        child: MaterialApp.router(
          routerConfig: router,
          builder: (context, child) => MediaQuery(
            // `textScaler` so the 1.3x case S8-03 committed to supporting can be pumped.
            data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(textScale)),
            child: Directionality(textDirection: TextDirection.rtl, child: child!),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  /// **The submit button must stay on screen on a small handset at a large text scale.**
  ///
  /// This screen's `_padded` is a bare `Padding` + `Column` with NO scroll view, and the file's
  /// own comment at `_summary` records that five list rows once *"pushed the irreversibility
  /// warning and the submit control below the fold on a small handset — which on the last
  /// checkpoint before an irreversible action is a worse defect than the sparseness it was meant
  /// to fix."* The journey progress strip (walk comments 1/5, 2026-09-10) then took another 44 dp
  /// off that fold, so the margin is measured here rather than assumed.
  ///
  /// 360x640 at 1.3x is the case EXECUTION_PLAN's S8-03 signed up for. Raised by
  /// `@agent-reviewer`: the default 800x600 test window proves nothing about it.
  testWidgets('the submit control survives 360x640 at a 1.3x text scale', (tester) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    // The ready view only renders when the BACKEND says the customer is at SUBMIT.
    api.pointerToServe = const JourneyPointer(profileId: 'p1', stage: JourneyStage.submit);
    await pump(tester, '/stage-12', textScale: 1.3);

    expect(tester.takeException(), isNull, reason: 'no RenderFlex overflow');

    final submit = find.widgetWithText(FilledButton, 'إرسال الطلب');
    expect(submit, findsOneWidget);

    // **REACHABLE, not necessarily above the fold.** At this size and scale the warning, the
    // summary and the button genuinely cannot all fit, and pretending otherwise would mean
    // cutting content from the last checkpoint before an irreversible action. What must be true
    // is that the customer can GET to the button — which is the difference between a view that
    // scrolls and one that overflows. Before this session's fix it did neither: the column had no
    // scroll view, so the button was simply off-screen with no way to reach it.
    await tester.ensureVisible(submit);
    await tester.pumpAndSettle();

    final rect = tester.getRect(submit);
    expect(rect.top, greaterThanOrEqualTo(0));
    expect(rect.bottom, lessThanOrEqualTo(640), reason: 'reachable by scrolling');
    expect(tester.takeException(), isNull);
  });

  Future<void> tapSubmit(WidgetTester tester) async {
    await tester.tap(find.widgetWithText(FilledButton, 'إرسال الطلب'));
    await tester.pumpAndSettle();
  }

  JourneyPointer submitted({List<String> channels = const ['sms']}) =>
      JourneyPointer(
        profileId: 'p1',
        stage: JourneyStage.submitted,
        referenceNumber: 'REF-0001',
        verifiedChannels: channels,
      );

  group('Stage 12 reads readiness rather than computing it', () {
    testWidgets(
      'offers submit only once the BACKEND says the customer is at SUBMIT',
      (tester) async {
        api.pointerToServe = const JourneyPointer(
          profileId: 'p1',
          stage: JourneyStage.submit,
        );
        await pump(tester, '/stage-12');

        expect(api.currentPointerCallCount, 1);
        expect(
          find.widgetWithText(FilledButton, 'إرسال الطلب'),
          findsOneWidget,
        );
        // Said BEFORE the button: the customer must know this is final before they take it.
        expect(
          find.textContaining('لا يمكن التعديل من التطبيق'),
          findsOneWidget,
        );
      },
    );

    testWidgets(
      'a SIGNATURE pointer sends the customer back rather than offering submit',
      (tester) async {
        api.pointerToServe = const JourneyPointer(
          profileId: 'p1',
          stage: JourneyStage.signature,
        );
        await pump(tester, '/stage-12');

        expect(landed, '/stage-11');
        expect(api.submitCallCount, 0);
      },
    );

    testWidgets('a LIVENESS pointer sends them further back still', (
      tester,
    ) async {
      api.pointerToServe = const JourneyPointer(
        profileId: 'p1',
        stage: JourneyStage.liveness,
      );
      await pump(tester, '/stage-12');

      expect(landed, '/stage-10');
    });

    testWidgets('SIGNATURE_REQUIRED on submit routes back to Stage 11', (
      tester,
    ) async {
      api.pointerToServe = const JourneyPointer(
        profileId: 'p1',
        stage: JourneyStage.submit,
      );
      await pump(tester, '/stage-12');
      api.submitError = const JourneyConflictException(
        JourneyCode.signatureRequired,
      );

      await tapSubmit(tester);

      expect(landed, '/stage-11');
    });
  });

  group('BL-058 — the confirmation channels come from the POINTER, on every path', () {
    testWidgets(
      'after a FRESH submit the screen re-reads /submission/current for its channels',
      (tester) async {
        api.pointerToServe = const JourneyPointer(
          profileId: 'p1',
          stage: JourneyStage.submit,
        );
        await pump(tester, '/stage-12');
        expect(api.currentPointerCallCount, 1);

        // The submit response carries its own `verifiedChannels`, which this app deliberately never
        // decodes — `SubmissionReceipt` has no such field. The pointer is what answers.
        api.pointerToServe = submitted(channels: ['email', 'sms']);
        await tapSubmit(tester);

        // ONE extra call on the happy path, by design. Branching on "did the submit response carry
        // channels" is exactly what reintroduces the empty-channels bug, because that field means
        // "channels this call enqueued" and is correctly empty on an idempotent re-submit.
        expect(api.currentPointerCallCount, 2);
        expect(landed, '/confirmation');
        expect(find.text('REF-0001'), findsOneWidget);
        expect(find.textContaining('البريد الإلكتروني'), findsOneWidget);
      },
    );

    testWidgets(
      'a LOST-ACKNOWLEDGEMENT resume lands on confirmation without submitting again',
      (tester) async {
        // customer.md:998-999's case: the submit landed, the app never saw the answer. Without the
        // pointer this customer has no way to recover their only artifact.
        api.pointerToServe = submitted(channels: ['whatsapp']);
        await pump(tester, '/stage-12');

        expect(landed, '/confirmation');
        expect(find.text('REF-0001'), findsOneWidget);
        expect(find.textContaining('واتساب'), findsOneWidget);
        expect(api.submitCallCount, 0);
      },
    );

    testWidgets(
      'a failed pointer read degrades honestly AND keeps local state for a resume',
      (tester) async {
        api.pointerToServe = const JourneyPointer(
          profileId: 'p1',
          stage: JourneyStage.submit,
        );
        await pump(tester, '/stage-12');
        api.currentPointerError = const BackendUnreachableException('offline');

        await tapSubmit(tester);

        expect(landed, '/confirmation');
        // The reference number — the thing that actually matters — is still shown.
        expect(find.text('REF-0001'), findsOneWidget);
        // The missing channels are said out loud rather than rendered as an empty list.
        expect(find.textContaining('تعذر عرض قنوات التواصل'), findsOneWidget);

        // NOT cleared: the submission succeeded but the screen is incomplete, and keeping local state
        // is what lets a resume finish the job.
        final progress = await (db.select(
          db.localProgress,
        )..where((t) => t.id.equals(0))).getSingleOrNull();
        expect(progress?.profileId, 'p1');
      },
    );
  });

  group('the confirmation screen says what customer.md requires', () {
    testWidgets('submitted for review — never "approved", never "done"', (
      tester,
    ) async {
      api.pointerToServe = submitted();
      await pump(tester, '/final-stages');

      expect(find.textContaining('للمراجعة والاعتماد'), findsOneWidget);
      expect(find.textContaining('لم يتم اعتماد التحديث بعد'), findsOneWidget);
      expect(find.textContaining('سيتم إشعارك'), findsOneWidget);
    });

    testWidgets('the customer is ASKED to save the reference number, not just shown it', (
      tester,
    ) async {
      // **Walk comment 15b.** R-019 is why this is not decoration: the reference number is the
      // customer's ONLY artifact once local storage clears, and this screen shows it exactly
      // once. Showing it prominently — which this screen already did — is not the same as
      // telling the customer that now is the moment to keep it.
      //
      // Pinned because the sentence is the kind of thing a later copy pass trims as redundant
      // beside a number that is already large and selectable, which is precisely the reading
      // R-019 warns against.
      api.pointerToServe = submitted();
      await pump(tester, '/final-stages');

      expect(find.text('REF-0001'), findsOneWidget);
      expect(find.textContaining('احتفظ بهذا الرقم'), findsOneWidget);
    });

    testWidgets(
      'clears local state only AFTER the screen has everything it needs',
      (tester) async {
        // customer.md Stage 12 step 5. The ordering is the point: the clear destroys the profileId
        // the pointer read needs, and the reference number is the customer's only artifact once it
        // runs.
        api.pointerToServe = submitted();
        await pump(tester, '/final-stages');
        await tester.pumpAndSettle();

        expect(find.text('REF-0001'), findsOneWidget);
        final progress = await (db.select(
          db.localProgress,
        )..where((t) => t.id.equals(0))).getSingleOrNull();
        expect(
          progress,
          isNull,
          reason: 'local state is cleared once the screen is complete',
        );
      },
    );
  });

  group('the resume gate is the single consumer of the pointer', () {
    testWidgets('LIVENESS and LIVENESS_BLOCKED both land on Stage 10', (
      tester,
    ) async {
      for (final stage in [
        JourneyStage.liveness,
        JourneyStage.livenessBlocked,
      ]) {
        landed = null;
        api.pointerToServe = JourneyPointer(profileId: 'p1', stage: stage);
        await pump(tester, '/final-stages');
        // The block is not a separate destination: Stage 10's own /token answers LIVENESS_BLOCKED
        // with the deadline, so the countdown is rendered once, there.
        expect(landed, '/stage-10', reason: stage.wire);
      }
    });

    testWidgets('SIGNATURE lands on Stage 11', (tester) async {
      api.pointerToServe = const JourneyPointer(
        profileId: 'p1',
        stage: JourneyStage.signature,
      );
      await pump(tester, '/final-stages');
      expect(landed, '/stage-11');
    });

    /// BL-106. These two used to land on `/confirmation`, which says «تم إرسال طلبك إلى البنك
    /// للمراجعة والاعتماد» and «لم يتم اعتماد التحديث بعد. سيتم إشعارك بالنتيجة.» — telling a
    /// customer the bank had already REJECTED that their request was still pending and that a
    /// result would follow. Direct wrong-destination assertions: they cannot pass against the
    /// collapsed arm, so no revert-restore proof is needed to show they bite.
    testWidgets('APPROVED lands on its own screen, not on "awaiting review"', (tester) async {
      api.pointerToServe = const JourneyPointer(
        profileId: 'p1',
        stage: JourneyStage.approved,
        referenceNumber: 'SFB-000000009',
        verifiedChannels: ['sms'],
      );
      await pump(tester, '/final-stages');

      expect(landed, '/approved');
      expect(landed, isNot('/confirmation'));
      expect(landedExtra, 'SFB-000000009');
    });

    testWidgets('REJECTED lands on its own screen, not on "awaiting review"', (tester) async {
      api.pointerToServe = const JourneyPointer(
        profileId: 'p1',
        stage: JourneyStage.rejected,
        referenceNumber: 'SFB-000000009',
        verifiedChannels: ['sms'],
      );
      await pump(tester, '/final-stages');

      expect(landed, '/rejected');
      expect(landed, isNot('/confirmation'));
      expect(landedExtra, 'SFB-000000009');
    });

    /// **Regression test for the second defect this session introduced.** The first version of
    /// `ApprovedScreen`/`RejectedScreen` did not clear local state, on the reasoning that
    /// clearing would cost the app its ability to keep telling the customer the truth on
    /// relaunch. `@agent-reviewer` refuted it: the destination these screens REPLACED already
    /// cleared (`confirmation_screen.dart:62-64`, and `currentJourneyPointer` always populates
    /// `verifiedChannels` on this path), so it was a silent regression of shipped behaviour —
    /// and customer.md l.1137-1138 makes the clear a PRIVACY requirement on a shared device, not
    /// storage hygiene. Local state here holds the data-entry draft: name, address, phone, email.
    testWidgets('APPROVED and REJECTED clear local state — customer.md l.1137 privacy rule', (
      tester,
    ) async {
      for (final (stage, route) in [
        (JourneyStage.approved, '/approved'),
        (JourneyStage.rejected, '/rejected'),
      ]) {
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
        api.pointerToServe = JourneyPointer(
          profileId: 'p1',
          stage: stage,
          referenceNumber: 'SFB-000000009',
          verifiedChannels: const ['sms'],
        );
        await pump(tester, '/final-stages');
        // Second settle: the clear is an unawaited future started in `initState`, exactly as
        // the confirmation-screen clear test above also has to wait for.
        await tester.pumpAndSettle();

        expect(landed, route, reason: stage.wire);
        expect(
          await (db.select(db.localProgress)).getSingleOrNull(),
          isNull,
          reason: 'local state must not survive a finished journey on a shared device (${stage.wire})',
        );
      }
    });

    testWidgets('SUBMITTED still lands on confirmation — that one was always true', (
      tester,
    ) async {
      api.pointerToServe = const JourneyPointer(
        profileId: 'p1',
        stage: JourneyStage.submitted,
        referenceNumber: 'SFB-000000009',
        verifiedChannels: ['sms'],
      );
      await pump(tester, '/final-stages');

      expect(landed, '/confirmation');
    });

    /// BL-110. Every arm below used to fall into the gate's bare `on Object`, which set
    /// «حدث خطأ غير متوقع. يرجى المحاولة مرة أخرى.» behind a retry button that re-issued the
    /// SAME refused call. The customer could tap it forever and never move.
    testWidgets('PROFILE_TERMINAL routes to /ended instead of an infinite retry', (tester) async {
      api.currentPointerError = const JourneyConflictException(
        JourneyCode.profileTerminal,
      );
      await pump(tester, '/final-stages');

      expect(landed, '/ended');
      expect(find.text('إعادة المحاولة'), findsNothing);
    });

    /// **This is the regression test for a defect this session INTRODUCED and `@agent-reviewer`
    /// caught.** The first version of this arm routed to `/`, reasoning that the launch check
    /// places a conflicted customer correctly. It does not: `_resume` reads the on-disk
    /// `resumeStage`, which is still `beyondStage9`, and `AccountCheckService` answers `PROCEED`
    /// for `blocked_scan` / `awaiting_registry` / `abandoned` alike — so `/` returns
    /// `ResumeFinalStages` and lands straight back here. That is an unattended hot loop with two
    /// network calls a lap, strictly worse than the retry button it replaced.
    ///
    /// The load-bearing assertion is the POINTER one: routing alone cannot fix this, because a
    /// screen change with a stale pointer re-forms the loop on the very next launch.
    testWidgets('STATE_CONFLICT moves the stale resume pointer and hands off to Stage 8', (
      tester,
    ) async {
      api.currentPointerError = const JourneyConflictException(
        JourneyCode.stateConflict,
      );
      await pump(tester, '/final-stages');

      expect(landed, '/stage-8');
      // Never back to the launch check — that is the loop.
      expect(landed, isNot('/'));
      expect(find.text('إعادة المحاولة'), findsNothing);

      // The pointer must have MOVED off `beyondStage9`, or the next launch resumes into
      // /final-stages and the loop re-forms without the customer touching anything.
      final progress = await (db.select(db.localProgress)).getSingleOrNull();
      expect(progress?.resumeStage, IdentityScanStage.stage8.name);
      expect(progress?.resumeStage, isNot(resumeStageBeyondStage9));
    });

    testWidgets('an unknown code degrades the same way, never to a guessed screen', (
      tester,
    ) async {
      api.currentPointerError = const JourneyConflictException(JourneyCode.unknown);
      await pump(tester, '/final-stages');

      expect(landed, '/stage-8');
      final progress = await (db.select(db.localProgress)).getSingleOrNull();
      expect(progress?.resumeStage, isNot(resumeStageBeyondStage9));
    });

    testWidgets('a 404 clears the stale local session and restarts', (tester) async {
      api.currentPointerError = const JourneyProfileNotFoundException();
      await pump(tester, '/final-stages');

      expect(landed, '/');
      // The dead profile id must be GONE, or the next launch resumes straight back into the same
      // 404. This is the half a routing-only fix would have missed.
      expect(await (db.select(db.localProgress)).getSingleOrNull(), isNull);
    });

    testWidgets(
      'an unreachable backend is shown up front, not as a failure on the next tap',
      (tester) async {
        api.currentPointerError = const BackendUnreachableException('offline');
        await pump(tester, '/final-stages');

        // customer.md Stage 13's offline resume case. There is no offline path past this point —
        // every one of stages 10-12 needs the network.
        expect(
          find.textContaining('يحتاج استكمال الطلب إلى اتصال'),
          findsOneWidget,
        );
        expect(
          find.widgetWithText(FilledButton, 'إعادة المحاولة'),
          findsOneWidget,
        );
      },
    );
  });

  test('an unknown stage throws rather than guessing a screen', () {
    // The opposite call from JourneyCode's: an unrecognised STAGE is a successful 200 whose meaning
    // this app version does not understand, and guessing a screen from it is how a customer ends up
    // somewhere they should not be.
    expect(() => JourneyStage.fromWire('SOMETHING_NEW'), throwsFormatException);
  });
}
