import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/dataentry/data_entry_providers.dart';
import 'package:mobile/core/database/database_providers.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/entry/entry_models.dart';
import 'package:mobile/core/identityscan/identity_scan_models.dart';
import 'package:mobile/core/identityscan/identity_scan_providers.dart';
import 'package:mobile/features/dataentry/stage7_screen.dart';
import 'package:mobile/features/identityscan/stage8_screen.dart';
import 'package:mobile/features/identityscan/stage9_screen.dart';

import '../../core/dataentry/fake_data_entry_api.dart';
import '../../core/identityscan/fake_identity_scan_api.dart';
import '../../core/identityscan/fake_uqudo_scanner.dart';

/// The three stages as one flow, mounted together over a single shared `SessionDatabase` — the
/// counterpart to `data_entry_navigation_test.dart` for stages 3-6.
///
/// This is what the task means by "the three stages work as a flow": pick a document type, scan it,
/// see the Civil Registry result and act on it, with the resume pointer moving correctly at each
/// hand-off. What it does NOT prove is that a real scan works — `FakeUqudoScanner` stands in for an
/// SDK that cannot run on this machine at all.
void main() {
  Future<
    ({
      FakeDataEntryApi dataEntryApi,
      FakeIdentityScanApi scanApi,
      FakeUqudoScanner scanner,
      SessionDatabase db,
    })
  >
  pumpFlow(WidgetTester tester) async {
    final db = SessionDatabase.forTesting();
    addTearDown(db.close);
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

    final dataEntryApi = FakeDataEntryApi();
    final scanApi = FakeIdentityScanApi();
    final scanner = FakeUqudoScanner();

    final router = GoRouter(
      initialLocation: '/stage-7',
      routes: [
        GoRoute(path: '/stage-7', builder: (context, state) => const Stage7Screen()),
        GoRoute(path: '/stage-8', builder: (context, state) => const Stage8Screen()),
        GoRoute(path: '/stage-9', builder: (context, state) => const Stage9Screen()),
        // S5-08: Stage 9's acceptance now hands off to the stages 10-12 GATE, which asks the
        // backend which of 10/11/12 the customer belongs on. `/session-pending` was the honest
        // placeholder while those stages did not exist; it is no longer reachable from here.
        GoRoute(
          path: '/final-stages',
          builder: (context, state) => const Scaffold(body: Text('final stages gate')),
        ),
        GoRoute(
          path: '/stage-6',
          builder: (context, state) => const Scaffold(body: Text('stage 6')),
        ),
        GoRoute(
          path: '/terminal',
          builder: (context, state) => Scaffold(body: Text(state.extra as String)),
        ),
      ],
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          sessionDatabaseProvider.overrideWithValue(db),
          dataEntryApiProvider.overrideWithValue(dataEntryApi),
          identityScanApiProvider.overrideWithValue(scanApi),
          uqudoScannerProvider.overrideWithValue(scanner),
        ],
        child: MaterialApp.router(
          routerConfig: router,
          builder: (context, child) =>
              Directionality(textDirection: TextDirection.rtl, child: child!),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return (dataEntryApi: dataEntryApi, scanApi: scanApi, scanner: scanner, db: db);
  }

  Future<String> resumeStageOf(SessionDatabase db) async {
    final progress = await (db.select(
      db.localProgress,
    )..where((t) => t.id.equals(0))).getSingle();
    return progress.resumeStage;
  }

  testWidgets('7 → 8 → 9 → accept, with the resume pointer advancing at each hand-off', (
    tester,
  ) async {
    final (:dataEntryApi, :scanApi, :scanner, :db) = await pumpFlow(tester);

    // --- Stage 7: choose the document type.
    expect(find.text('نوع وثيقة الهوية'), findsOneWidget);
    // Walk comment 4 (2026-09-10): the card submits and advances in one tap.
    await tester.tap(find.text('جواز السفر'));
    await tester.pumpAndSettle();

    expect(dataEntryApi.lastStage7Args!['identityType'], IdentityDocumentTypes.passport);
    expect(await resumeStageOf(db), IdentityScanStage.stage8.name);

    // --- Stage 8: the preparation screen, then the scan.
    expect(find.text('قبل أن تبدأ:'), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, 'ابدأ المسح'));
    await tester.pumpAndSettle();

    // The type chosen at Stage 7 is what was actually scanned and submitted — the draft carried it
    // across the screen boundary, not a navigation argument.
    expect(scanApi.lastIssueTokenArgs!['documentType'], IdentityDocumentTypes.passport);
    expect(scanner.lastEnrollArgs!['documentType'], IdentityDocumentTypes.passport);
    expect(await resumeStageOf(db), IdentityScanStage.stage9.name);

    // --- Stage 9: the review, loaded through the read-only resume endpoint.
    expect(find.text('NID-TEST-0001'), findsOneWidget);
    expect(scanApi.currentReviewCallCount, 1);
    expect(scanApi.retryRegistryLookupCallCount, 0);

    await tester.tap(find.widgetWithText(FilledButton, 'البيانات صحيحة، متابعة'));
    await tester.pumpAndSettle();

    expect(scanApi.acceptReviewCallCount, 1);
    expect(find.text('final stages gate'), findsOneWidget);
    // The local pointer still advances to the same value — what changed at S5-08 is what that
    // value MEANS: "somewhere in stages 10-12, ask the backend" rather than "past everything".
    expect(await resumeStageOf(db), resumeStageBeyondStage9);
  });

  testWidgets(
    'a national-ID choice reaches the scanner as national_id — the SDN_ID translation never '
    'touches the wire or the draft',
    (tester) async {
      final (:dataEntryApi, :scanApi, :scanner, :db) = await pumpFlow(tester);

      await tester.tap(find.text('البطاقة القومية'));
      await tester.pumpAndSettle();

      // Walk comment 8 (2026-09-10): the two screens name the same document one after the other,
      // so they must not disagree. Stage 7's card said «الرقم الوطني» — the NUMBER printed on the
      // card — while Stage 8 said «بطاقة الرقم الوطني». Both are now the bank's own term. Asserted
      // across the transition, because a per-screen string test would let them drift apart.
      expect(find.textContaining('البطاقة القومية'), findsOneWidget);
      expect(find.textContaining('الرقم الوطني'), findsNothing);
      await tester.tap(find.widgetWithText(FilledButton, 'ابدأ المسح'));
      await tester.pumpAndSettle();

      expect(dataEntryApi.lastStage7Args!['identityType'], IdentityDocumentTypes.nationalId);
      expect(scanApi.lastIssueTokenArgs!['documentType'], IdentityDocumentTypes.nationalId);
      // The seam receives the journey vocabulary; only `PluginUqudoScanner` (not exercised here,
      // and not exercisable on this machine) maps it to Uqudo's SDN_ID.
      expect(scanner.lastEnrollArgs!['documentType'], IdentityDocumentTypes.nationalId);

      final draft = await (db.select(
        db.dataEntryDraft,
      )..where((t) => t.id.equals(0))).getSingle();
      expect(draft.identityType, IdentityDocumentTypes.nationalId);
    },
  );

  testWidgets('"the number is wrong" returns from Stage 9 to a real Stage 8 preparation screen', (
    tester,
  ) async {
    final (:dataEntryApi, :scanApi, :scanner, :db) = await pumpFlow(tester);

    // Walk comment 4 (2026-09-10): the card submits and advances in one tap.
    await tester.tap(find.text('جواز السفر'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'ابدأ المسح'));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(OutlinedButton, 'الرقم الوطني غير صحيح'));
    await tester.pumpAndSettle();

    // Back on the real Stage 8, ready to scan again — customer.md frames the wrong number as a
    // scanning problem, and this is the loop that closes it.
    expect(find.widgetWithText(FilledButton, 'ابدأ المسح'), findsOneWidget);
    expect(scanApi.reportWrongNumberCallCount, 1);
  });

  testWidgets('the BL-034 retry survives navigating away from Stage 8 and back', (tester) async {
    // The retained capture lives in a Riverpod provider for exactly this reason: it has to outlive
    // the screen's own state, or leaving and returning would silently cost the customer a rescan.
    final (:dataEntryApi, :scanApi, :scanner, :db) = await pumpFlow(tester);
    scanApi.submitScanErrorToThrowOnce = const BackendUnreachableException('connection reset');

    // Walk comment 4 (2026-09-10): the card submits and advances in one tap.
    await tester.tap(find.text('جواز السفر'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'ابدأ المسح'));
    await tester.pumpAndSettle();
    expect(find.text('تم مسح الوثيقة، ولم يكتمل الإرسال'), findsOneWidget);

    // Leave Stage 8 entirely and come back, so the screen state object is destroyed and rebuilt.
    await tester.tap(find.widgetWithText(OutlinedButton, 'تغيير الوثيقة'));
    await tester.pumpAndSettle();
    expect(find.text('نوع وثيقة الهوية'), findsOneWidget);

    // Walk comment 4: re-advancing is a card tap now. This is also the path that answers the
    // BL-039 objection to tap-to-advance — «تغيير الوثيقة» brings the customer back here, so a
    // mis-tap costs a screen transition and no scan attempt.
    await tester.tap(find.text('جواز السفر'));
    await tester.pumpAndSettle();

    // The rebuilt Stage 8 finds the retained capture and offers the re-send instead of a fresh
    // preparation screen — a fresh scan here would cost an attempt the capture already paid for.
    expect(find.text('تم مسح الوثيقة، ولم يكتمل الإرسال'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'ابدأ المسح'), findsNothing);

    await tester.tap(find.widgetWithText(FilledButton, 'إعادة الإرسال'));
    await tester.pumpAndSettle();

    expect(scanApi.submitScanCalls, hasLength(2));
    expect(scanApi.submitScanCalls[1], equals(scanApi.submitScanCalls[0]));
    expect(scanner.enrollCallCount, 1);
    expect(find.text('NID-TEST-0001'), findsOneWidget);
  });
}
