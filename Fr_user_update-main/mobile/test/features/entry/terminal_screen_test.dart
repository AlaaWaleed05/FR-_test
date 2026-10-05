import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/features/entry/terminal_screen.dart';

void main() {
  testWidgets('renders the given message and "back to start" returns to /account-entry', (
    tester,
  ) async {
    var landedOnAccountEntry = false;
    final router = GoRouter(
      initialLocation: '/terminal',
      routes: [
        GoRoute(
          path: '/terminal',
          builder: (context, state) =>
              const TerminalScreen(message: 'الحساب غير نشط. يرجى مراجعة أقرب فرع.'),
        ),
        GoRoute(
          path: '/account-entry',
          builder: (context, state) {
            landedOnAccountEntry = true;
            return const Scaffold(body: Text('account entry'));
          },
        ),
      ],
    );

    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp.router(
          routerConfig: router,
          builder: (context, child) =>
              Directionality(textDirection: TextDirection.rtl, child: child!),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('الحساب غير نشط. يرجى مراجعة أقرب فرع.'), findsOneWidget);

    await tester.tap(find.text('العودة إلى البداية'));
    await tester.pumpAndSettle();

    expect(landedOnAccountEntry, isTrue);
  });
}
