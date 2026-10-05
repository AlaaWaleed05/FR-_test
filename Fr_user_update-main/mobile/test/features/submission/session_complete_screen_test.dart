import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/router/app_router.dart';
import 'package:mobile/features/submission/session_complete_screen.dart';
import 'package:mobile/core/widgets/brand_banner.dart';

void main() {
  Future<void> pumpEndScreen(WidgetTester tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Directionality(
          textDirection: TextDirection.rtl,
          child: SessionCompleteScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('states that the SESSION ended, and offers a way out of the app', (tester) async {
    // Walk comment 15c / BL-091 item 3. «إنهاء» used to call `context.go('/')`, which put the
    // customer back on the splash as though they were starting the journey over.
    await pumpEndScreen(tester);

    expect(find.text('انتهت الجلسة'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'إغلاق التطبيق'), findsOneWidget);
  });

  testWidgets('NEVER tells the customer their request is approved or done', (tester) async {
    // customer.md requires the app describe the request as submitted for review and never as
    // approved or complete — the bank has not decided anything at this point. A screen headed
    // "you have finished" is precisely where that distinction gets lost, so it is pinned here
    // rather than left to whoever next edits the copy.
    await pumpEndScreen(tester);

    expect(find.textContaining('للمراجعة'), findsOneWidget);

    for (final forbidden in ['تمت الموافقة', 'مقبول', 'معتمد', 'اكتمل طلبك', 'تم قبول']) {
      expect(
        find.textContaining(forbidden),
        findsNothing,
        reason: 'the end screen must not claim an outcome the bank has not reached: $forbidden',
      );
    }
  });

  testWidgets('is terminal — no back affordance into the finished journey', (tester) async {
    // Reached by `go`, which replaces the stack. Since AD-012 the screen DOES carry a bar — the
    // mark-only brand banner — so the assertion is about the affordance, not about the bar's
    // absence: `BrandBanner.markOnly` forces `automaticallyImplyLeading: false`, so nothing in it
    // offers a route back into a journey that is over.
    await pumpEndScreen(tester);

    expect(find.byType(BackButton), findsNothing);
    expect(find.byType(BrandBanner), findsOneWidget);
  });

  test('the route «إنهاء» targets is actually registered', () {
    // The screen above can be perfect and still be unreachable if the route is missing, so the
    // wiring is asserted separately from the rendering.
    final container = ProviderContainer();
    addTearDown(container.dispose);

    final paths = container
        .read(goRouterProvider)
        .configuration
        .routes
        .whereType<GoRoute>()
        .map((route) => route.path);

    expect(paths, contains('/session-complete'));
  });
}
