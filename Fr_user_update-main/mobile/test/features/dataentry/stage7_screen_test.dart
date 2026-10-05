import 'dart:async' show Completer;
import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/dataentry/data_entry_providers.dart';
import 'package:mobile/core/database/database_providers.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/entry/entry_models.dart';
import 'package:mobile/core/theme/app_theme.dart';
import 'package:mobile/features/dataentry/stage7_screen.dart';

import '../../core/dataentry/fake_data_entry_api.dart';

/// Stage 7 — identity type. The real screen, a real `GoRouter` and a real in-memory
/// `SessionDatabase` with the real `DataEntryRepository`; only the HTTP layer is faked, matching
/// `stage4_screen_test.dart`'s pattern exactly.
void main() {
  Future<SessionDatabase> seedProfile() async {
    final db = SessionDatabase.forTesting();
    await db
        .into(db.localProgress)
        .insertOnConflictUpdate(
          LocalProgressCompanion.insert(
            id: const Value(0),
            verifiedBranchCode: '16',
            verifiedAccountNumber: '0000000001',
            resumeStage: 'stage7',
            profileId: const Value('p1'),
            updatedAt: DateTime.now(),
          ),
        );
    return db;
  }

  Future<({FakeDataEntryApi api, SessionDatabase db, String? Function() landedPath})> pumpStage7(
    WidgetTester tester, {
    String? existingIdentityType,
  }) async {
    final sessionDb = await seedProfile();
    addTearDown(sessionDb.close);
    if (existingIdentityType != null) {
      await sessionDb
          .into(sessionDb.dataEntryDraft)
          .insertOnConflictUpdate(
            DataEntryDraftCompanion.insert(
              id: const Value(0),
              identityType: Value(existingIdentityType),
              updatedAt: DateTime.now(),
            ),
          );
    }
    final api = FakeDataEntryApi();
    String? landedPath;

    GoRoute stub(String path) => GoRoute(
      path: path,
      builder: (context, state) {
        landedPath = path;
        return Scaffold(body: Text('landed $path'));
      },
    );

    final router = GoRouter(
      initialLocation: '/stage-7',
      routes: [
        GoRoute(path: '/stage-7', builder: (context, state) => const Stage7Screen()),
        stub('/stage-6'),
        stub('/stage-8'),
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
          sessionDatabaseProvider.overrideWithValue(sessionDb),
          dataEntryApiProvider.overrideWithValue(api),
        ],
        child: MaterialApp.router(
          // The REAL theme, not Material's defaults. These cards are the one place in the app
          // where a container IS the control, so the border role they resolve is a behaviour of
          // this screen worth testing — and it can only be tested against the theme that ships.
          theme: AppTheme.light(),
          routerConfig: router,
          builder: (context, child) =>
              Directionality(textDirection: TextDirection.rtl, child: child!),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return (api: api, db: sessionDb, landedPath: () => landedPath);
  }

  testWidgets('offers exactly the two identity types the wire accepts', (tester) async {
    await pumpStage7(tester);
    expect(find.text('جواز السفر'), findsOneWidget);
    expect(find.text('البطاقة القومية'), findsOneWidget);
  });

  /// **Walk comment 4 (2026-09-10) replaced this test's premise.** It used to assert that
  /// pressing «التالي» with nothing selected showed «اختر نوع وثيقة الهوية.» — the only path that
  /// ever produced that message. The card IS the forward action now, so there is no way to ask
  /// this screen to advance without naming a document, and the unselected-submit state is
  /// unreachable rather than merely untested.
  ///
  /// What replaces it: the screen offers no forward control of its own, so a customer cannot
  /// advance by any route except choosing.
  testWidgets('there is no way to advance without choosing a document', (tester) async {
    final (:api, :db, :landedPath) = await pumpStage7(tester);

    expect(find.widgetWithText(FilledButton, 'التالي'), findsNothing);
    expect(find.text('التالي'), findsNothing);
    expect(api.submitStage7CallCount, 0);
    expect(landedPath(), isNull);
  });

  testWidgets('a passport selection submits the app vocabulary and advances to /stage-8', (
    tester,
  ) async {
    final (:api, :db, :landedPath) = await pumpStage7(tester);

    // Walk comment 4: ONE tap. The card submits and advances; there is no second press.
    await tester.tap(find.text('جواز السفر'));
    await tester.pumpAndSettle();

    expect(api.submitStage7CallCount, 1);
    // The journey's own vocabulary goes on the wire — never Uqudo's SDN_ID/PASSPORT, which exist
    // only inside PluginUqudoScanner.
    expect(api.lastStage7Args!['identityType'], 'passport');
    expect(landedPath(), '/stage-8');

    final progress = await (db.select(
      db.localProgress,
    )..where((t) => t.id.equals(0))).getSingle();
    expect(progress.resumeStage, 'stage8');
  });

  testWidgets('a national-ID selection sends national_id, not SDN_ID', (tester) async {
    final (:api, :db, :landedPath) = await pumpStage7(tester);

    await tester.tap(find.text('البطاقة القومية'));
    await tester.pumpAndSettle();

    expect(api.lastStage7Args!['identityType'], 'national_id');
  });

  /// **Walk comment 4 (2026-09-10): a second tap while the first is in flight must not submit
  /// twice.** The cards became the forward action, so the double-submit guard that used to live
  /// on the «التالي» button (`_submitting ? null : _onNext`) had to move onto them. Tapping the
  /// OTHER card mid-flight is the dangerous shape — it would submit a different document type
  /// over the one already in flight, and the customer would arrive at a scan for a document they
  /// did not choose last.
  ///
  /// **What this test reaches, and what it does not.** It covers the already-submitting state.
  /// It does NOT reach the window between the tap and `_submitting` going true, because
  /// `SessionDatabase.forTesting()` is `NativeDatabase.memory()` on the main isolate, so the
  /// draft write resolves inside the first `pump()` — whereas production opens the database with
  /// `createInBackground` and the write is an isolate round trip with pointer events deliverable
  /// inside it. `@agent-reviewer` found that window open in the first version of this screen and
  /// pointed out this harness cannot fail on it; the fix was to raise `_submitting` in
  /// `_selectType` before the await, and the honest note is that this test is not what proves it.
  testWidgets('a card tapped while a submission is in flight does not submit again', (
    tester,
  ) async {
    final (:api, :db, :landedPath) = await pumpStage7(tester);
    final gate = Completer<void>();
    api.stage7Gate = gate.future;

    await tester.tap(find.text('جواز السفر'));
    await tester.pump();

    await tester.tap(find.byKey(const ValueKey('doc_card_national_id')), warnIfMissed: false);
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('doc_card_passport')), warnIfMissed: false);
    await tester.pump();

    // **Asserted AFTER the gate releases, and that is the whole point of the test.**
    // Checking the count while the first call is still in flight proves nothing: the repository
    // holds a per-stage mutex (`_stageLocks`, `data_entry_repository.dart`), so a second
    // submission waits there and never reaches this fake until the first returns. The first
    // version of this test asserted mid-flight and passed with BOTH screen guards removed — it
    // was measuring the repository's lock, not the screen's guard.
    gate.complete();
    await tester.pumpAndSettle();

    expect(api.submitStage7CallCount, 1, reason: 'three taps, one submission');
    expect(api.lastStage7Args!['identityType'], 'passport');
    expect(landedPath(), '/stage-8');
  });

  /// The working card says so. With «التالي» gone this is the only feedback that anything is
  /// happening between the tap and the next screen.
  testWidgets('the tapped card shows it is working', (tester) async {
    final (:api, :db, :landedPath) = await pumpStage7(tester);
    final gate = Completer<void>();
    api.stage7Gate = gate.future;

    await tester.tap(find.text('جواز السفر'));
    await tester.pump();

    expect(
      find.descendant(
        of: find.byKey(const ValueKey('doc_card_passport')),
        matching: find.byType(CircularProgressIndicator),
      ),
      findsOneWidget,
    );
    // ...and only that card.
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('doc_card_national_id')),
        matching: find.byType(CircularProgressIndicator),
      ),
      findsNothing,
    );

    gate.complete();
    await tester.pumpAndSettle();
  });

  testWidgets('the selection is restored from the draft on reopen', (tester) async {
    await pumpStage7(tester, existingIdentityType: 'national_id');

    // S8-05 (walk comment 7a) replaced the radio list with two tappable cards, so the restored
    // selection is now read off the card rather than off a RadioGroup. Asserted two ways, because
    // the visible emphasis and the accessible state are separately losable: the selected card
    // carries the heavier border, and it reports itself as selected to a screen reader — which a
    // RadioListTile did for free and a bare Card does not.
    final card = tester.widget<Card>(find.byKey(const ValueKey('doc_card_national_id')));
    expect((card.shape! as RoundedRectangleBorder).side.width, 2);

    final other = tester.widget<Card>(find.byKey(const ValueKey('doc_card_passport')));
    expect((other.shape! as RoundedRectangleBorder).side.width, 1);

    final semantics = tester.widget<Semantics>(
      find
          .ancestor(
            of: find.byKey(const ValueKey('doc_card_national_id')),
            matching: find.byType(Semantics),
          )
          .first,
    );
    expect(semantics.properties.selected, isTrue);
  });

  testWidgets('an UNSELECTED card is identifiable by its border, at the same 3:1 role a field is', (
    tester,
  ) async {
    await pumpStage7(tester, existingIdentityType: 'national_id');

    // `app_theme.dart` treats the 1dp `outline` hairline on a resting field as an ACCESSIBILITY
    // requirement, not decoration — WCAG 1.4.11 wants 3:1 for whatever identifies a control, and
    // no fill difference on M3's light ladder can reach it. These cards were shipped with
    // `outlineVariant`, which measures 1.39:1 against the tinted canvas versus `outline`'s
    // 3.66:1, so the rule was being applied everywhere EXCEPT the one screen where a container
    // had become the button.
    //
    // Asserted as the ROLE rather than as a ratio, deliberately: `app_theme_test.dart` already
    // proves `outline` clears 3:1 both ways, so pinning the role here chains onto that proof
    // instead of copying a WCAG helper into a second file where the two could drift apart.
    final scheme = AppTheme.light().colorScheme;
    final unselected = tester.widget<Card>(find.byKey(const ValueKey('doc_card_passport')));
    final side = (unselected.shape! as RoundedRectangleBorder).side;

    expect(side.color, scheme.outline);
    expect(side.color, isNot(scheme.outlineVariant));
  });

  testWidgets(
    'an unreachable backend queues the submission but does NOT advance into Stage 8 — the one '
    'place this app deliberately diverges from the stage 3-6 pattern',
    (tester) async {
      // customer.md Stage 7's "Exits": "Backend unreachable → the entered data is on the device
      // and queued. The customer is told a connection is needed to continue." Stage 8 opens the
      // Uqudo SDK and cannot run offline, so walking the customer forward would fail them one
      // screen after attempts start costing something. Stages 3-6 discard this return value and
      // advance anyway, correctly, because they ARE offline-capable.
      final (:api, :db, :landedPath) = await pumpStage7(tester);
      api.stage7ErrorToThrow = const BackendUnreachableException('offline');

      await tester.tap(find.text('جواز السفر'));
      await tester.pumpAndSettle();

      expect(landedPath(), isNull);
      expect(
        find.textContaining('يلزم الاتصال بالإنترنت لمتابعة مسح الوثيقة'),
        findsOneWidget,
      );

      // Queued, not lost — it lands on the next opportunity.
      final pending = await db.select(db.pendingStageSync).get();
      expect(pending.map((r) => r.stage), contains('stage7'));

      // And the resume pointer did not move, so a relaunch comes back here rather than to a scan
      // the customer cannot start.
      final progress = await (db.select(
        db.localProgress,
      )..where((t) => t.id.equals(0))).getSingle();
      expect(progress.resumeStage, 'stage7');

      // **The screen must still WORK.** Raised by `@agent-reviewer`: since walk comment 4 the
      // card is the only forward control, so a `_submitting` left stuck by this error path would
      // strand the customer at Stage 7 with no way onward — and every assertion above would still
      // pass. Proving the card still submits is what closes that.
      api.stage7ErrorToThrow = null;
      await tester.tap(find.text('جواز السفر'));
      await tester.pumpAndSettle();
      expect(api.submitStage7CallCount, 2, reason: 'the card is live again after a queued attempt');
      expect(landedPath(), '/stage-8');
    },
  );

  testWidgets('a terminal profile ends the journey', (tester) async {
    final (:api, :db, :landedPath) = await pumpStage7(tester);
    api.stage7ErrorToThrow = const ProfileAlreadyCompleteException();

    await tester.tap(find.text('جواز السفر'));
    await tester.pumpAndSettle();

    expect(landedPath(), '/ended');
  });

  testWidgets('Back returns to /stage-6 — Stage 7 is the last freely-revisitable stage', (
    tester,
  ) async {
    final (:api, :db, :landedPath) = await pumpStage7(tester);

    await tester.tap(find.widgetWithText(OutlinedButton, 'السابق'));
    await tester.pumpAndSettle();

    expect(landedPath(), '/stage-6');
  });
}
