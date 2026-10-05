import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/features/entry/blocked_screen.dart';

/// BL-021's screen. `@agent-reviewer` flagged on the S8-14 diff that this file was wholly
/// untested — and per CLAUDE.md's coverage caveat (R-009) a file no test imports leaves the
/// percentage unmoved rather than lowering it, so the gate could not have caught its absence.
void main() {
  Future<String? Function()> pump(
    WidgetTester tester, {
    required DateTime? blockedUntil,
    bool progressPreserved = true,
  }) async {
    String? landed;
    GoRoute stub(String path) => GoRoute(
      path: path,
      builder: (context, state) {
        landed = path;
        return Scaffold(body: Text('at $path'));
      },
    );

    final router = GoRouter(
      initialLocation: '/blocked',
      routes: [
        GoRoute(
          path: '/blocked',
          builder: (context, state) => BlockedScreen(
            blockedUntil: blockedUntil,
            progressPreserved: progressPreserved,
          ),
        ),
        stub('/'),
        stub('/account-entry'),
      ],
    );

    await tester.pumpWidget(
      MaterialApp.router(
        routerConfig: router,
        builder: (context, child) =>
            Directionality(textDirection: TextDirection.rtl, child: child!),
      ),
    );
    await tester.pumpAndSettle();
    return () => landed;
  }

  testWidgets('a live lock counts down and offers no retry yet', (tester) async {
    await pump(tester, blockedUntil: DateTime.now().add(const Duration(minutes: 15)));

    expect(find.text('تم إيقاف المحاولات مؤقتًا'), findsOneWidget);
    expect(find.textContaining('يمكنك المحاولة مرة أخرى بعد'), findsOneWidget);
    // The whole point of BL-021: the customer is told when it lifts, not that their internet is
    // at fault.
    expect(find.textContaining('الإنترنت'), findsNothing);
    expect(find.widgetWithText(FilledButton, 'إعادة المحاولة'), findsNothing);
  });

  testWidgets('the launch path reassures the customer their data survives the block', (
    tester,
  ) async {
    await pump(
      tester,
      blockedUntil: DateTime.now().add(const Duration(minutes: 15)),
      progressPreserved: true,
    );

    expect(find.textContaining('كل ما أدخلته وتم التحقق منه محفوظ'), findsOneWidget);
  });

  testWidgets('Stage 1a suppresses that reassurance, having entered nothing this session', (
    tester,
  ) async {
    await pump(
      tester,
      blockedUntil: DateTime.now().add(const Duration(minutes: 15)),
      progressPreserved: false,
    );

    expect(find.textContaining('كل ما أدخلته وتم التحقق منه محفوظ'), findsNothing);
  });

  testWidgets('there is always a way off this screen', (tester) async {
    // BL-109 is what a block screen without one looks like; Stage 0 must not repeat it.
    final landed = await pump(
      tester,
      blockedUntil: DateTime.now().add(const Duration(minutes: 15)),
    );

    await tester.tap(find.widgetWithText(OutlinedButton, 'العودة إلى البداية'));
    await tester.pumpAndSettle();

    expect(landed(), '/account-entry');
  });

  testWidgets('with no deadline it says the action is unavailable, claiming no wait ended', (
    tester,
  ) async {
    await pump(tester, blockedUntil: null);

    expect(find.text('هذا الإجراء غير متاح حاليًا. يرجى زيارة أقرب فرع.'), findsOneWidget);
    expect(find.textContaining('انتهت مدة الانتظار'), findsNothing);
    expect(find.widgetWithText(FilledButton, 'إعادة المحاولة'), findsNothing);
  });
}
