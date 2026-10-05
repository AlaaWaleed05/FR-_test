import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/app.dart';
import 'package:mobile/core/router/app_router.dart';
import 'package:mobile/core/theme/app_theme.dart';

/// Guards the app SHELL — the two things `FruApp` itself is responsible for, as opposed to the
/// things its screens are.
///
/// **Why this file exists at all.** Until 2026-09-07 the only test that ever pumped `FruApp` lived
/// inside `test/features/reference_demo/occupation_demo_screen_test.dart`, which was deleted with
/// the demo route it covered. That left `lib/core/app.dart` imported by nothing but `main.dart` —
/// and CLAUDE.md's own coverage caveat is explicit that a source file no test imports leaves the
/// percentage unmoved rather than lowering it, so the 80% gate could not have reported the gap.
/// The invariants below are real: `app.dart` is where RTL is forced EXPLICITLY rather than
/// inferred from the locale, and where the brand theme is actually wired into the tree.
void main() {
  /// A one-route router, so this exercises the shell without dragging in a screen's providers.
  GoRouter stubRouter() => GoRouter(
    initialLocation: '/',
    routes: [
      GoRoute(
        path: '/',
        builder: (context, state) => const Text('probe', key: Key('probe')),
      ),
    ],
  );

  Future<BuildContext> pumpShell(WidgetTester tester) async {
    late BuildContext captured;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [goRouterProvider.overrideWith((ref) => stubRouter())],
        child: const FruApp(),
      ),
    );
    await tester.pumpAndSettle();
    captured = tester.element(find.byKey(const Key('probe')));
    return captured;
  }

  testWidgets('the shell forces RTL for the whole tree', (tester) async {
    final context = await pumpShell(tester);

    // The app sets this at the `builder` level rather than letting `Locale('ar')` resolve it —
    // "the app's own text direction set rather than inherited by accident". A screen that
    // accidentally relied on LTR would render mirrored without this.
    expect(Directionality.of(context), TextDirection.rtl);
  });

  testWidgets('the shell wires the brand theme in, not just the default Material one', (
    tester,
  ) async {
    final context = await pumpShell(tester);
    final theme = Theme.of(context);

    // `app_theme_test.dart` proves `AppTheme.light()` is correct in isolation. This proves the
    // shell actually uses it — the two are different failures, and only this one is visible to a
    // customer.
    expect(theme.colorScheme.primary, AppTheme.brandNavy);
    expect(theme.textTheme.bodyLarge?.fontFamily, 'IBMPlexSansArabic');
    expect(theme.textTheme.bodyLarge?.letterSpacing, 0);
  });

  testWidgets('the Android page transition stays direction-neutral', (tester) async {
    final context = await pumpShell(tester);

    // The regression this catches: leaving `pageTransitionsTheme` unset resolves Android to a
    // builder whose fallback slides HORIZONTALLY with LTR semantics hardcoded, so an Arabic app
    // animates every forward navigation the wrong way — and no existing test would fail.
    final builders = Theme.of(context).pageTransitionsTheme.builders;
    expect(builders[TargetPlatform.android], isA<FadeUpwardsPageTransitionsBuilder>());
  });

  testWidgets('Arabic is the only supported locale and it is applied', (tester) async {
    final context = await pumpShell(tester);
    expect(Localizations.localeOf(context), const Locale('ar'));
  });
}
