import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/database/database_providers.dart';

import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/entry/entry_models.dart';
import 'package:mobile/core/entry/entry_providers.dart';
import 'package:mobile/features/entry/account_entry_screen.dart';
import 'package:mobile/features/entry/ended_screen.dart';
import 'package:mobile/features/entry/blocked_screen.dart';

import '../../core/entry/fake_entry_api.dart';

void main() {
  Future<FakeEntryApi> pump(WidgetTester tester) async {
    final sessionDb = SessionDatabase.forTesting();
    addTearDown(sessionDb.close);
    final fakeApi = FakeEntryApi();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          sessionDatabaseProvider.overrideWithValue(sessionDb),
          entryApiProvider.overrideWithValue(fakeApi)],
          
        child: const MaterialApp(
          home: Directionality(textDirection: TextDirection.rtl, child: AccountEntryScreen()),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return fakeApi;
  }

  Future<void> fillForm(WidgetTester tester) async {
    await tester.enterText(find.byType(TextField), '12345');
    await tester.pump();
   
  }

  /// For submit tests, which navigate on success — needs a real `GoRouter` ancestor.
  Future<({FakeEntryApi api, String? Function() landedPath, SessionDatabase sessionDb})>
  pumpWithRouter(WidgetTester tester) async {
    final sessionDb = SessionDatabase.forTesting();
    addTearDown(sessionDb.close);
    final fakeApi = FakeEntryApi();
    String? landedPath;
    final router = GoRouter(
      initialLocation: '/account-entry',
      routes: [
        GoRoute(path: '/account-entry', builder: (context, state) => const AccountEntryScreen()),
        GoRoute(
          path: '/contact-channels',
          builder: (context, state) {
            landedPath = '/contact-channels';
            return const Scaffold(body: Text('contact channels'));
          },
        ),
        GoRoute(
          path: '/terminal',
          builder: (context, state) {
            landedPath = '/terminal';
            return Scaffold(body: Text('terminal: ${state.extra}'));
          },
        ),
        GoRoute(
          path: '/ended',
          builder: (context, state) {
            landedPath = '/ended';
            // The REAL screen, not a stub. `@agent-reviewer` found the first version stubbed this
            // with `Text('ended')`, which made the "does not show the false sentence" assertion
            // below vacuously true — it would have passed no matter what EndedScreen said.
            return const EndedScreen();
          },
        ),
        // BL-021: where a phone-locked customer must now land.
        GoRoute(
          path: '/blocked',
          builder: (context, state) {
            landedPath = '/blocked';
            final args = state.extra as BlockedArgs?;
            return Scaffold(
              body: Text('blocked until ${args?.blockedUntil} preserved ${args?.progressPreserved}'),
            );
          },
        ),
      ],
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          sessionDatabaseProvider.overrideWithValue(sessionDb),
          entryApiProvider.overrideWithValue(fakeApi),
          branchCatalogInitProvider.overrideWith((ref) async {}),
          branchItemsProvider.overrideWith(
            (ref) => Stream.value([
              ReferenceItem(
                listCode: 'branch',
                version: 1,
                itemCode: '2',
                labelAr: 'بورتسودان',
                labelEn: 'Port Sudan',
                searchAr: 'بورتسودان',
                searchEn: 'port sudan',
                sortOrdinal: 1,
                isActive: true,
              ),
            ]),
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
    return (api: fakeApi, landedPath: () => landedPath, sessionDb: sessionDb);
  }

  testWidgets('renders in RTL', (tester) async {
    await pump(tester);

    final directionality = tester.widget<Directionality>(
      find
          .ancestor(of: find.byType(AccountEntryScreen), matching: find.byType(Directionality))
          .first,
    );
    expect(directionality.textDirection, TextDirection.rtl);
  });

  testWidgets('the sparse screen is a boxed section with the action anchored, not two loose '
      'fields at the top', (tester) async {
    // **Walk comment 3a** — "this screen doesn't have much detail, make use of the screen and
    // don't push them both on top and leave the rest of the screen empty".
    //
    // Two structural claims, both of which a screenshot review would catch but no test did:
    // the two inputs are one boxed section on the tinted canvas (the same treatment used for
    // Stage 9's sections), and the action button is pinned OUTSIDE the scrolling content rather
    // than trailing the last field. The second is also why the button cannot be pushed off the
    // viewport by the keyboard — the defect class batch one hit on Stage 12, where a `Spacer` in
    // a non-scrolling column would have reproduced it exactly.
    await pump(tester);

    expect(
      find.ancestor(of: find.byType(TextField), matching: find.byType(Card)),
      findsOneWidget,
    );
    // Both halves are needed. `findsNothing` alone would pass trivially on a screen with no
    // scroll view at all — including the pre-change version this test exists to distinguish
    // from — so the positive assertion is what makes "outside the scroll view" mean anything.
    expect(find.byType(SingleChildScrollView), findsOneWidget);
    expect(
      find.ancestor(of: find.byType(FilledButton), matching: find.byType(SingleChildScrollView)),
      findsNothing,
    );
    expect(find.textContaining('أدخل رقم حسابك والفرع'), findsOneWidget);
  });

 

  testWidgets('Arabic-Indic digits typed into the account number field render as ASCII', (
    tester,
  ) async {
    await pump(tester);

    await tester.enterText(find.byType(TextField), '٠٩١٢٣');
    await tester.pump();

    expect(find.text('09123'), findsOneWidget);
  });

  testWidgets('PROCEED navigates to /contact-channels and records LocalProgress', (
    tester,
  ) async {
    final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
    api.accountCheckResultToServe = const AccountCheckResult(
      outcome: AccountOutcome.active,
      continuation: AccountContinuation.proceed,
      requestId: 'r1',
    );

    await fillForm(tester);
    await tester.tap(find.byType(FilledButton));
    await tester.pumpAndSettle();

    expect(landedPath(), '/contact-channels');
    expect(await (sessionDb.select(sessionDb.localProgress)).getSingleOrNull(), isNotNull);
  });

  testWidgets('RETRY (invalid account) shows an inline field error, stays on Stage 1a, no session', (
    tester,
  ) async {
    final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
    api.accountCheckResultToServe = const AccountCheckResult(
      outcome: AccountOutcome.invalid,
      continuation: AccountContinuation.retry,
      requestId: 'r1',
    );

    await fillForm(tester);
    await tester.tap(find.byType(FilledButton));
    await tester.pumpAndSettle();

    expect(landedPath(), isNull);
    expect(find.textContaining('تعذر العثور على هذا الحساب'), findsOneWidget);
    expect(await (sessionDb.select(sessionDb.localProgress)).getSingleOrNull(), isNull);
  });

  testWidgets(
    'TERMINAL (inactive) navigates to /terminal with the branch-visit message and clears '
    'local state (F6, S5-02)',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.accountCheckResultToServe = const AccountCheckResult(
        outcome: AccountOutcome.inactive,
        continuation: AccountContinuation.terminal,
        requestId: 'r1',
      );

      await fillForm(tester);
      await tester.tap(find.byType(FilledButton));
      await tester.pumpAndSettle();

      expect(landedPath(), '/terminal');
      expect(find.textContaining('غير نشط'), findsOneWidget);
      expect(await (sessionDb.select(sessionDb.localDraft)).getSingleOrNull(), isNull);
    },
  );

  testWidgets(
    'TERMINAL (already-complete account) navigates to /ended — which owns its copy — and '
    'clears local state (F6, S5-02, BL-123)',
    (tester) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.accountCheckResultToServe = const AccountCheckResult(
        outcome: AccountOutcome.active,
        continuation: AccountContinuation.terminal,
        requestId: 'r1',
      );

      await fillForm(tester);
      await tester.tap(find.byType(FilledButton));
      await tester.pumpAndSettle();

      expect(landedPath(), '/ended');
      // BL-123: this used to assert the screen SHOWED «تم استكمال تحديث بيانات هذا الحساب من قبل»
      // — a sentence false for three of the four terminal statuses that reach here. Stage 1a
      // cannot tell them apart (the wire carries no status by deliberate design, BL-119), so it
      // must now hand off to a screen that claims nothing. Asserting the absence is the point.
      expect(find.textContaining('استكمال تحديث بيانات هذا الحساب من قبل'), findsNothing);
      // …and positively: the customer sees the honest screen, which claims no outcome.
      expect(find.text('انتهى تحديث بيانات هذا الحساب'), findsOneWidget);
      expect(await (sessionDb.select(sessionDb.localDraft)).getSingleOrNull(), isNull);
    },
  );

  testWidgets('a backend-unreachable submit shows the inline retry message and stays on Stage 1a', (
    tester,
  ) async {
    final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
    api.accountCheckErrorToThrow = const BackendUnreachableException('down');

    await fillForm(tester);
    await tester.tap(find.byType(FilledButton));
    await tester.pumpAndSettle();

    expect(landedPath(), isNull);
    expect(find.textContaining('تعذر الاتصال'), findsOneWidget);
    expect(await (sessionDb.select(sessionDb.localProgress)).getSingleOrNull(), isNull);
  });

  testWidgets('an unmapped non-HTTP failure shows a generic message, not silence (F4, S5-02; 5xx became BackendUnreachableException at S3-02)', (
    tester,
  ) async {
    final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
    api.accountCheckErrorToThrow = StateError('unexpected 500');

    await fillForm(tester);
    await tester.tap(find.byType(FilledButton));
    await tester.pumpAndSettle();

    expect(landedPath(), isNull);
    expect(find.textContaining('حدث خطأ غير متوقع'), findsOneWidget);
    expect(await (sessionDb.select(sessionDb.localProgress)).getSingleOrNull(), isNull);
  });

 

  group('BL-021 — a phone lock at Stage 1a', () {
    testWidgets(
      'BLOCKED sends the customer to the block screen, NOT to an account-number field error',
      (tester) async {
        // The defect this replaces: `BLOCKED` was not in the enum, so the decode threw, the bare
        // `catch` caught it, and the customer was told something was wrong with the ACCOUNT
        // NUMBER — the one field that was not the problem. A phone lock has nothing to do with
        // the number they just typed correctly.
        final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
        final until = DateTime.now().add(const Duration(minutes: 15));
        api.accountCheckResultToServe = AccountCheckResult(
          outcome: AccountOutcome.active,
          continuation: AccountContinuation.blocked,
          requestId: 'r1',
          blockedUntil: until,
        );

        await fillForm(tester);
        await tester.tap(find.widgetWithText(FilledButton, 'التالي'));
        await tester.pumpAndSettle();

        expect(landedPath(), '/blocked');
        expect(find.textContaining('blocked until $until'), findsOneWidget);
        // Stage 1a has entered nothing this session, so the "your data is saved" reassurance is
        // deliberately suppressed here even though it is true one screen later.
        expect(find.textContaining('preserved false'), findsOneWidget);
        expect(find.textContaining('حدث خطأ غير متوقع'), findsNothing);
      },
    );

    testWidgets('a phone lock does NOT clear the session, unlike a terminal answer', (
      tester,
    ) async {
      final (:api, :landedPath, :sessionDb) = await pumpWithRouter(tester);
      api.accountCheckResultToServe = AccountCheckResult(
        outcome: AccountOutcome.active,
        continuation: AccountContinuation.blocked,
        requestId: 'r1',
        blockedUntil: DateTime.now().add(const Duration(minutes: 15)),
      );

      await fillForm(tester);
      await tester.tap(find.widgetWithText(FilledButton, 'التالي'));
      await tester.pumpAndSettle();

      // `abandon()` deletes the draft row; the terminal branch calls it and this one must not.
      final draft = await (sessionDb.select(
        sessionDb.localDraft,
      )..where((t) => t.id.equals(0))).getSingleOrNull();
      expect(draft?.accountNumber, '12345');
    });
  });
}
