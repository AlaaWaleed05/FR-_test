import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/database/database_providers.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/entry/entry_models.dart';
import 'package:mobile/core/identityscan/identity_scan_models.dart';
import 'package:mobile/core/identityscan/identity_scan_providers.dart';
import 'package:mobile/features/identityscan/stage9_screen.dart';

import '../../core/identityscan/fake_identity_scan_api.dart';
import '../../core/identityscan/fake_uqudo_scanner.dart';

/// Stage 9 — Civil Registry review.
const _notReady = ScanDisplay(
  profileId: 'p1',
  cycleId: 'c1',
  documentType: IdentityDocumentTypes.passport,
  nationalNumber: 'NID-TEST-0001',
  registryReady: false,
  availableImageKinds: [ScanImageKinds.docFront],
);

void main() {
  /// [configure] runs BEFORE the first frame. It has to: `initState` fires `_load()` immediately,
  /// and the fake answers from whatever its fields hold at call time — so anything this screen
  /// reads on load (the payload, a load-time error) must be set before pumping, while anything it
  /// reads on an action can be set after.
  /// [bottomInset] simulates a 3-button Android navigation bar (walk comment 9, 2026-09-10).
  /// Zero by default — which is what the test binding supplies, and what let the defect ship.
  Future<({FakeIdentityScanApi api, SessionDatabase db, String? Function() landedPath})> pumpStage9(
    WidgetTester tester, {
    void Function(FakeIdentityScanApi api)? configure,
    double bottomInset = 0,
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
            resumeStage: 'stage9',
            profileId: const Value('p1'),
            updatedAt: DateTime.now(),
          ),
        );

    final api = FakeIdentityScanApi();
    configure?.call(api);
    String? landedPath;

    GoRoute stub(String path) => GoRoute(
      path: path,
      builder: (context, state) {
        landedPath = path;
        return Scaffold(body: Text('landed $path'));
      },
    );

    final router = GoRouter(
      initialLocation: '/stage-9',
      routes: [
        GoRoute(path: '/stage-9', builder: (context, state) => const Stage9Screen()),
        stub('/'),
        stub('/stage-8'),
        stub('/session-pending'),
        stub('/final-stages'),
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
          identityScanApiProvider.overrideWithValue(api),
          uqudoScannerProvider.overrideWithValue(FakeUqudoScanner()),
        ],
        child: MaterialApp.router(
          routerConfig: router,
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context).copyWith(
              padding: EdgeInsets.only(bottom: bottomInset),
            ),
            child: Directionality(textDirection: TextDirection.rtl, child: child!),
          ),
        ),
      ),
    );
    return (api: api, db: db, landedPath: () => landedPath);
  }

  /// **The screen-level guard for the body-`SafeArea` mechanism (walk comment 9, 2026-09-10).**
  /// `stage_action_bar_test.dart` proves the WIDGET insets correctly; this proves a screen that
  /// takes the inset at the BODY rather than through `StageActionBar` really does clear the bar.
  ///
  /// Stage 9, not Stage 8, and that choice is load-bearing: the review view ends in a
  /// bottom-anchored action row under an `Expanded` scroll view, so its accept button genuinely
  /// sat flush with the screen edge. Stage 8's views are all top-aligned or centred — a guard
  /// written there passes with the fix reverted, which is exactly what happened on the first
  /// attempt.
  ///
  /// Raised by `@agent-reviewer`: with only the widget's unit test, deleting a screen's
  /// `SafeArea` left the whole suite green.
  testWidgets('walk comment 9 — the review actions clear the system navigation bar', (
    tester,
  ) async {
    await pumpStage9(tester, bottomInset: 48);
    await tester.pumpAndSettle();

    final screenBottom = tester.getSize(find.byType(MaterialApp)).height;

    // **The BOTTOM-MOST control, not the most prominent one.** The first version of this test
    // measured the accept `FilledButton` and passed with the fix reverted (clearance 128), because
    // two `OutlinedButton`s sit BELOW it in the same action column — so the button being measured
    // was never the one at risk. Measuring the last child is what makes the assertion about the
    // screen edge rather than about one widget's happy position.
    final lastControl = find.widgetWithText(
      OutlinedButton,
      'الرقم صحيح لكن بياناتي غير صحيحة',
    );
    expect(lastControl, findsOneWidget, reason: 'the premise: the review view is showing');

    expect(
      screenBottom - tester.getRect(lastControl).bottom,
      greaterThanOrEqualTo(48),
      reason: 'the last review action must sit above the navigation bar, not underneath it',
    );
  });

  group('loading', () {
    testWidgets('reads through the RESUME endpoint, never by re-triggering a lookup', (
      tester,
    ) async {
      // S5-11's whole reason for existing: before it, the only way to obtain this payload was the
      // body of a mutating POST that made a live Civil Registry call and overwrote the stored
      // result — so simply returning to this screen re-queried the registry.
      final (:api, :db, :landedPath) = await pumpStage9(tester);
      await tester.pumpAndSettle();

      expect(api.currentReviewCallCount, 1);
      expect(api.retryRegistryLookupCallCount, 0);
    });

    testWidgets('shows the national number prominently and separated, before anything else', (
      tester,
    ) async {
      // customer.md: "The national number is shown first, alone and prominent, separated from
      // everything else ... it must not sit buried in a list of fields."
      await pumpStage9(tester);
      await tester.pumpAndSettle();

      expect(find.text('NID-TEST-0001'), findsOneWidget);
      expect(find.text('الرقم الوطني'), findsOneWidget);
      expect(find.textContaining('قارن هذا الرقم بالرقم المدوّن في وثيقتك'), findsOneWidget);

      final card = find.ancestor(of: find.text('NID-TEST-0001'), matching: find.byType(Card));
      expect(card, findsOneWidget);
    });

    testWidgets('renders the registry data and fetches only the images the payload names', (
      tester,
    ) async {
      final (:api, :db, :landedPath) = await pumpStage9(tester);
      await tester.pumpAndSettle();

      expect(find.text('محمد أحمد علي'), findsOneWidget);
      expect(find.text('ذكر'), findsOneWidget);

      // D4.2: DD/MM/YYYY on screen, never ISO. The backend still SENDS `1990-04-17` — the fixture
      // is unchanged and the wire format is untouched — and the conversion happens purely at the
      // presentation boundary. Asserting the rendered form here is what would catch a regression
      // that let the raw ISO value reach a customer screen again.
      expect(find.text('17/04/1990'), findsOneWidget);
      expect(find.text('1990-04-17'), findsNothing);

      // Exactly the two kinds the payload listed — every absent kind is one indistinguishable 404
      // by design, so probing for others would cost a request each and tell us nothing.
      expect(api.reviewImageKinds, [ScanImageKinds.docFront, ScanImageKinds.portraitUqudo]);
    });

    testWidgets('the data-source label is GONE — the customer is not told who supplied this', (
      tester,
    ) async {
      // **Walk comment 10b**, a product-owner decision taken 2026-09-08: the customer is not told
      // which authority supplied the data they are confirming. Pinned because it is a DISCLOSURE
      // policy, not a wording preference — the phrase is natural to reinstate while "improving"
      // this screen, and without a test that would pass every gate.
      //
      // The SECTION HEADING is asserted here; the registry portrait's caption is the other label
      // site and is asserted in the test immediately below, which needs its own payload to render
      // that image at all. Comment 10a had already taken the phrase out of the screen TITLE in an
      // earlier session; these two were where it survived as a label.
      await pumpStage9(tester);
      await tester.pumpAndSettle();

      expect(find.text('بيانات السجل المدني'), findsNothing);

      // The data itself is untouched — 10b removes the attribution, not the review.
      expect(find.text('محمد أحمد علي'), findsOneWidget);
    });

    testWidgets('the registry PORTRAIT caption names no authority either', (tester) async {
      // 10b's second string, and it needs its own pump: the default fake payload lists only
      // `docFront` and `portraitUqudo`, so `_imageLabel`'s registry branch never renders under
      // any other test in this file. Asserting `findsNothing` for the old caption in the test
      // above would therefore have passed against the UNCHANGED code — a vacuous assertion
      // guarding nothing, which is why the caption gets a payload that actually contains it.
      await pumpStage9(
        tester,
        configure: (api) => api.display = ScanDisplay(
          profileId: api.display.profileId,
          cycleId: api.display.cycleId,
          documentType: api.display.documentType,
          nationalNumber: api.display.nationalNumber,
          registryReady: true,
          availableImageKinds: const [ScanImageKinds.portraitRegistry],
          nameArGiven: api.display.nameArGiven,
          nameArFather: api.display.nameArFather,
          nameArGrandfather: api.display.nameArGrandfather,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('صورة السجل المدني'), findsNothing);
      expect(find.text('الصورة المسجّلة'), findsOneWidget);
    });

    testWidgets('each section of the review is a container, not a loose run of fields', (
      tester,
    ) async {
      // **Walk comment 10c** — "every segment of this screen should be emphasized as a section".
      // It is built here as structure rather than as another heading style, because 10b had just
      // removed the only heading the field list had: without a container those fields would have
      // become an unlabelled run on the bare canvas, which is a worse reading of the same screen.
      //
      // Three cards: the national number (which customer.md already required to stand alone), the
      // registry field list, and the images.
      await pumpStage9(tester);
      await tester.pumpAndSettle();

      expect(
        find.ancestor(of: find.text('محمد أحمد علي'), matching: find.byType(Card)),
        findsOneWidget,
      );
      expect(find.ancestor(of: find.text('الصور'), matching: find.byType(Card)), findsOneWidget);
    });

    testWidgets('nothing on the screen is editable', (tester) async {
      await pumpStage9(tester);
      await tester.pumpAndSettle();
      expect(find.byType(TextField), findsNothing);
    });
  });

  group('the pause — registryReady=false is a 200, not an error', () {
    testWidgets('shows the distinct pause state and offers no review actions', (tester) async {
      // customer.md: "The session pauses. It does not fail ... The customer sees a distinct state
      // — the service is unavailable, progress is intact, come back shortly — not an error and not
      // a failure." Offering the three actions here would only earn a REGISTRY_NOT_READY.
      await pumpStage9(tester, configure: (api) => api.display = _notReady);
      await tester.pumpAndSettle();

      expect(find.text('الخدمة غير متاحة مؤقتًا'), findsOneWidget);
      expect(find.textContaining('لن تحتاج إلى إعادة المسح'), findsOneWidget);
      expect(find.text('البيانات صحيحة، متابعة'), findsNothing);
      expect(find.text('الرقم الوطني غير صحيح'), findsNothing);
    });

    testWidgets('a payload with every registry field null renders without crashing', (
      tester,
    ) async {
      await pumpStage9(tester, configure: (api) => api.display = _notReady);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });

    testWidgets('retry calls the WRITING lookup endpoint, and success reveals the review', (
      tester,
    ) async {
      final (:api, :db, :landedPath) = await pumpStage9(
        tester,
        configure: (api) => api.display = _notReady,
      );
      await tester.pumpAndSettle();
      expect(find.text('الخدمة غير متاحة مؤقتًا'), findsOneWidget);

      // The retry succeeds this time — the registry came back.
      api.display = FakeIdentityScanApi().display;

      await tester.tap(find.widgetWithText(FilledButton, 'إعادة المحاولة'));
      await tester.pumpAndSettle();

      expect(api.retryRegistryLookupCallCount, 1);
      expect(find.text('NID-TEST-0001'), findsOneWidget);
    });
  });

  group('the three actions', () {
    testWidgets('Accept advances past Stage 9 and hands off to the stages 10-12 GATE, not the '
        'old placeholder — S5-08', (tester) async {
      final (:api, :db, :landedPath) = await pumpStage9(tester);
      await tester.pumpAndSettle();

      await tester.tap(find.widgetWithText(FilledButton, 'البيانات صحيحة، متابعة'));
      await tester.pumpAndSettle();

      expect(api.acceptReviewCallCount, 1);
      // Until S5-08 this landed on `/session-pending`, the honest placeholder for "stages 10-12 do
      // not exist yet". They exist now, and the forward path must go through the same door a
      // relaunch does: the gate asks `POST /submission/current` and lands the customer where the
      // backend says. Going straight to `/stage-10` would infer a stage the backend owns; staying
      // on the placeholder left stages 10-12 reachable only by relaunching the app.
      expect(landedPath(), '/final-stages');
      final progress = await (db.select(
        db.localProgress,
      )..where((t) => t.id.equals(0))).getSingle();
      expect(progress.resumeStage, resumeStageBeyondStage9);
    });

    testWidgets('"the number is wrong" returns to the scan — it is a scanning problem', (
      tester,
    ) async {
      // customer.md: "Framed as a scanning problem, because that is what it is: the wrong number
      // was read." It counts against the Stage 8 budget, which the backend applies.
      final (:api, :db, :landedPath) = await pumpStage9(tester);
      await tester.pumpAndSettle();

      await tester.tap(find.widgetWithText(OutlinedButton, 'الرقم الوطني غير صحيح'));
      await tester.pumpAndSettle();

      expect(api.reportWrongNumberCallCount, 1);
      expect(landedPath(), '/stage-8');
    });

    testWidgets('a "wrong number" that exhausts the budget shows the block instead', (
      tester,
    ) async {
      final (:api, :db, :landedPath) = await pumpStage9(tester);
      api.wrongNumberBlockedUntil = DateTime.now().add(const Duration(hours: 24));
      await tester.pumpAndSettle();

      await tester.tap(find.widgetWithText(OutlinedButton, 'الرقم الوطني غير صحيح'));
      await tester.pumpAndSettle();

      expect(find.text('مسح الوثيقة غير متاح مؤقتًا'), findsOneWidget);
      expect(landedPath(), isNull);
    });

    testWidgets('"my details are wrong" is terminal, and the message EXPLAINS rather than refuses', (
      tester,
    ) async {
      // customer.md: "The terminal message must explain rather than refuse ... 'Go to the branch'
      // without a reason reads as the app failing."
      final (:api, :db, :landedPath) = await pumpStage9(tester);
      await tester.pumpAndSettle();

      await tester.tap(
        find.widgetWithText(OutlinedButton, 'الرقم صحيح لكن بياناتي غير صحيحة'),
      );
      await tester.pumpAndSettle();

      expect(api.reportWrongDetailsCallCount, 1);
      expect(landedPath(), '/terminal');
      expect(find.textContaining('لا يمكن للبنك تسجيل بيانات هوية تختلف'), findsOneWidget);
      expect(find.textContaining('يرجى زيارة أي فرع'), findsOneWidget);

      // **This sentence still names the registry, and that is deliberate under walk comment 10b.**
      // 10b removes provenance as a LABEL; here the registry is not a label but the reason the
      // customer must go to a branch — strip it and "go to a branch" loses what it is for, which
      // is the exact failure customer.md's "explain rather than refuse" rule exists to prevent.
      // Pinned so a later pass at 10b does not read "delete every mention" and break the remedy.
      expect(find.textContaining('السجل المدني'), findsOneWidget);
    });
  });

  group('the FOUR conflict arms a Stage 9 action now needs (BL-042/BL-043)', () {
    testWidgets('PROFILE_TERMINAL ends the journey', (tester) async {
      final (:api, :db, :landedPath) = await pumpStage9(tester);
      api.acceptReviewErrorToThrow = const ScanConflictException(
        ScanConflictCode.profileTerminal,
      );
      await tester.pumpAndSettle();

      await tester.tap(find.widgetWithText(FilledButton, 'البيانات صحيحة، متابعة'));
      await tester.pumpAndSettle();

      expect(landedPath(), '/ended');
    });

    testWidgets('SCAN_BLOCKED from wrong-number shows the block — an arm it could not answer before', (
      tester,
    ) async {
      // S5-12/BL-043 widened this endpoint: before it, wrong-number could not return SCAN_BLOCKED
      // at all, so a Stage 9 handler written against the older contract would have had one arm.
      final (:api, :db, :landedPath) = await pumpStage9(tester);
      api.reportWrongNumberErrorToThrow = ScanConflictException(
        ScanConflictCode.scanBlocked,
        blockedUntil: DateTime.now().add(const Duration(hours: 12)),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.widgetWithText(OutlinedButton, 'الرقم الوطني غير صحيح'));
      await tester.pumpAndSettle();

      expect(find.text('مسح الوثيقة غير متاح مؤقتًا'), findsOneWidget);
    });

    testWidgets('REGISTRY_PENDING from wrong-number falls back to the pause — also new', (
      tester,
    ) async {
      final (:api, :db, :landedPath) = await pumpStage9(tester);
      api.reportWrongNumberErrorToThrow = const ScanConflictException(
        ScanConflictCode.registryPending,
      );
      await tester.pumpAndSettle();

      await tester.tap(find.widgetWithText(OutlinedButton, 'الرقم الوطني غير صحيح'));
      await tester.pumpAndSettle();

      expect(find.text('الخدمة غير متاحة مؤقتًا'), findsOneWidget);
    });

    testWidgets('REGISTRY_NOT_READY shows the same pause as REGISTRY_PENDING', (tester) async {
      final (:api, :db, :landedPath) = await pumpStage9(tester);
      api.acceptReviewErrorToThrow = const ScanConflictException(
        ScanConflictCode.registryNotReady,
      );
      await tester.pumpAndSettle();

      await tester.tap(find.widgetWithText(FilledButton, 'البيانات صحيحة، متابعة'));
      await tester.pumpAndSettle();

      expect(find.text('الخدمة غير متاحة مؤقتًا'), findsOneWidget);
    });

    testWidgets('STATE_CONFLICT re-reads the authoritative state instead of guessing', (
      tester,
    ) async {
      final (:api, :db, :landedPath) = await pumpStage9(tester);
      api.acceptReviewErrorToThrow = const ScanConflictException(ScanConflictCode.stateConflict);
      await tester.pumpAndSettle();
      expect(api.currentReviewCallCount, 1);

      await tester.tap(find.widgetWithText(FilledButton, 'البيانات صحيحة، متابعة'));
      await tester.pumpAndSettle();

      // A second read — the re-sync actually performed, using S5-11's read-only endpoint.
      expect(api.currentReviewCallCount, 2);
      expect(api.retryRegistryLookupCallCount, 0);
    });
  });

  group('a conflict raised BY the load is not the same as one raised by an action', () {
    testWidgets(
      'STATE_CONFLICT on load sends the customer to the scan ONCE — it does not re-enter the '
      'loader forever',
      (tester) async {
        // Found by @agent-reviewer against this diff. The re-sync arm re-reads through _load, so
        // re-syncing a conflict that _load itself produced looped: an unresolving spinner
        // hammering POST /registry-review/current. Reachable without a race —
        // currentReviewPayload answers STATE_CONFLICT whenever there is no accepted snapshot, and
        // reportWrongNumber supersedes the active cycle, so Stage 9 → "the number is wrong" → app
        // killed → relaunch lands exactly here.
        final (:api, :db, :landedPath) = await pumpStage9(
          tester,
          configure: (api) => api.currentReviewErrorToThrow = const ScanConflictException(
            ScanConflictCode.stateConflict,
          ),
        );
        await tester.pumpAndSettle();

        expect(landedPath(), '/stage-8');
        // Exactly one read. Against the unbounded version this never settles at all.
        expect(api.currentReviewCallCount, 1);
        // And the pointer moved with the customer, so a relaunch does not land back here.
        final progress = await (db.select(
          db.localProgress,
        )..where((t) => t.id.equals(0))).getSingle();
        expect(progress.resumeStage, IdentityScanStage.stage8.name);
      },
    );

    testWidgets('an unknown code on load is bounded the same way', (tester) async {
      final (:api, :db, :landedPath) = await pumpStage9(
        tester,
        configure: (api) =>
            api.currentReviewErrorToThrow = const ScanConflictException(ScanConflictCode.unknown),
      );
      await tester.pumpAndSettle();

      expect(landedPath(), '/stage-8');
      expect(api.currentReviewCallCount, 1);
    });
  });

  testWidgets('"the number is wrong" moves the resume pointer to Stage 8, not just the screen', (
    tester,
  ) async {
    // The backend supersedes the cycle on this action, so a pointer left on stage9 names a stage
    // whose state cannot exist — the concrete route into the loop guarded above.
    final (:api, :db, :landedPath) = await pumpStage9(tester);
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(OutlinedButton, 'الرقم الوطني غير صحيح'));
    await tester.pumpAndSettle();

    final progress = await (db.select(
      db.localProgress,
    )..where((t) => t.id.equals(0))).getSingle();
    expect(progress.resumeStage, IdentityScanStage.stage8.name);
  });

  testWidgets('an unreachable backend on load offers a retry rather than a dead screen', (
    tester,
  ) async {
    await pumpStage9(
      tester,
      configure: (api) =>
          api.currentReviewErrorToThrow = const BackendUnreachableException('offline'),
    );
    await tester.pumpAndSettle();

    expect(find.text('تعذر عرض البيانات'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'إعادة المحاولة'), findsOneWidget);
  });
}
