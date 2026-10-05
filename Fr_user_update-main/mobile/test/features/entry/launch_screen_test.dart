import 'dart:async' show Completer;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/entry/entry_models.dart';
import 'package:mobile/core/entry/entry_providers.dart';
import 'package:mobile/features/entry/blocked_screen.dart';
import 'package:mobile/features/entry/launch_screen.dart';

/// The asset name behind an [Image], unwrapping [ResizeImage].
///
/// `Image.asset(..., cacheWidth: ...)` wraps its provider in a `ResizeImage` — the decode cap
/// D6.3 makes a house rule — so a test that reaches for `AssetImage` directly finds nothing and
/// fails with a bare "No element". Unwrap once here rather than in every assertion.
String? _assetNameOf(Image image) {
  final provider = image.image;
  if (provider is ResizeImage) {
    final inner = provider.imageProvider;
    return inner is AssetImage ? inner.assetName : null;
  }
  return provider is AssetImage ? provider.assetName : null;
}

void main() {
  Future<String?> pumpAndNavigate(WidgetTester tester, LaunchDecision decision) async {
    String? landedPath;
    final router = GoRouter(
      initialLocation: '/',
      routes: [
        GoRoute(path: '/', builder: (context, state) => const LaunchScreen()),
        for (final path in [
          '/account-entry',
          '/contact-channels',
          '/channel-verification',
          '/session-pending',
          '/terminal',
          '/ended',
        ])
          GoRoute(
            path: path,
            builder: (context, state) {
              landedPath = path;
              return Scaffold(body: Text('landed:$path'));
            },
          ),
        // BL-021's destination, given a REAL builder rather than a shared stub: the stub ignores
        // `state.extra`, so a test asserting the destination alone would prove nothing about the
        // deadline being carried — the "asserts more than it proves" shape (`@agent-reviewer`).
        GoRoute(
          path: '/blocked',
          builder: (context, state) {
            landedPath = '/blocked';
            final args = state.extra as BlockedArgs?;
            return Scaffold(
              body: Text('blocked until ${args?.blockedUntil} kept ${args?.progressPreserved}'),
            );
          },
        ),
      ],
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [launchDecisionProvider.overrideWith((ref) async => decision)],
        child: MaterialApp.router(
          routerConfig: router,
          builder: (context, child) =>
              Directionality(textDirection: TextDirection.rtl, child: child!),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return landedPath;
  }

  testWidgets('FreshStart navigates to /account-entry', (tester) async {
    expect(await pumpAndNavigate(tester, const FreshStart()), '/account-entry');
  });

  testWidgets('ResumeContactChannels navigates to /contact-channels', (tester) async {
    expect(
      await pumpAndNavigate(
        tester,
        const ResumeContactChannels(branchCode: '2', accountNumber: '12345', offline: false),
      ),
      '/contact-channels',
    );
  });

  testWidgets('ResumeAwaitingVerification navigates to /channel-verification', (tester) async {
    expect(
      await pumpAndNavigate(
        tester,
        const ResumeAwaitingVerification(profileId: 'p1', channels: [], offline: false),
      ),
      '/channel-verification',
    );
  });

  testWidgets('ResumeVerified navigates to /session-pending', (tester) async {
    expect(
      await pumpAndNavigate(
        tester,
        const ResumeVerified(profileId: 'p1', verifiedChannels: [], offline: false),
      ),
      '/session-pending',
    );
  });

  testWidgets('LaunchTerminal (inactive account) navigates to /terminal', (tester) async {
    expect(await pumpAndNavigate(tester, const LaunchTerminal('done')), '/terminal');
  });

  /// BL-123. Stage 0 relaunch is the MOST common way a finished customer reaches an end screen —
  /// their local state survives, the launch check answers TERMINAL, and before S8-16 they were
  /// told «تم استكمال تحديث بيانات هذا الحساب من قبل» whether the bank had approved them,
  /// rejected them, or terminated the profile on a registry mismatch. This arm had no test until
  /// `@agent-reviewer` pointed out the gap: `account_entry_screen_test` covered the OTHER entry
  /// site and `entry_repository_test` covered the model, so the routing itself was unproven.
  testWidgets('LaunchEnded navigates to /ended, never to /terminal', (tester) async {
    final landed = await pumpAndNavigate(tester, const LaunchEnded());

    expect(landed, '/ended');
    expect(landed, isNot('/terminal'));
  });

  testWidgets('shows an error message if the launch decision itself throws', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          launchDecisionProvider.overrideWith((ref) async => throw StateError('boom')),
        ],
        child: MaterialApp(
          home: Directionality(textDirection: TextDirection.rtl, child: LaunchScreen()),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.textContaining('تعذر بدء التطبيق'), findsOneWidget);
  });

  group('the splash animation is decoration and never a gate', () {
    /// **This test was inverted by the product owner's 4-second ruling (2026-09-12).** It used to
    /// assert D7.3's property — that navigation never waits on the animation, so the app never
    /// manufactures a wait. The client now wants the brand moment held, so the property it guards
    /// is the opposite one, and it is recorded here rather than deleted so the reversal is
    /// visible to whoever reads this file next.
    testWidgets('a proceeding launch is HELD for the 4-second minimum', (tester) async {
      String? landedPath;
      final router = GoRouter(
        initialLocation: '/',
        routes: [
          GoRoute(path: '/', builder: (context, state) => const LaunchScreen()),
          GoRoute(
            path: '/account-entry',
            builder: (context, state) {
              landedPath = '/account-entry';
              return const Scaffold(body: Text('landed'));
            },
          ),
        ],
      );

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            launchDecisionProvider.overrideWith((ref) async => const FreshStart()),
          ],
          child: MaterialApp.router(
            routerConfig: router,
            builder: (context, child) =>
                Directionality(textDirection: TextDirection.rtl, child: child!),
          ),
        ),
      );

      // The decision resolves almost immediately, and the animation finishes at 1800ms. Neither
      // is what governs any more.
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 2500));
      expect(
        landedPath,
        isNull,
        reason: 'the brand hold runs to 4000ms even once the decision and the animation are done',
      );

      await tester.pump(const Duration(milliseconds: 1600));
      expect(landedPath, '/account-entry');

      await tester.pumpAndSettle();
    });

    /// The exception to the hold, and the reason it exists: an answer the customer cannot act on
    /// until they see it should not be sat on. Four seconds spent telling someone their account is
    /// inactive is four seconds of brand moment in the one situation they will not enjoy it.
    testWidgets('a journey-ending answer BYPASSES the hold', (tester) async {
      for (final (decision, route) in [
        (const LaunchTerminal('inactive'), '/terminal'),
        (const LaunchEnded(), '/ended'),
        // The member with the concrete correctness argument, and the only one carrying a
        // payload — its screen renders a live countdown, so four held seconds is four seconds
        // that countdown is already wrong by when it first appears. It was missing from this
        // loop until `@agent-reviewer` pointed out that the one case with a real reason to
        // bypass was the one case untested.
        (LaunchBlocked(blockedUntil: DateTime.now().add(const Duration(minutes: 15))), '/blocked'),
      ]) {
        String? landedPath;
        final router = GoRouter(
          initialLocation: '/',
          routes: [
            GoRoute(path: '/', builder: (context, state) => const LaunchScreen()),
            for (final path in ['/terminal', '/ended', '/blocked', '/account-entry'])
              GoRoute(
                path: path,
                builder: (context, state) {
                  landedPath = path;
                  return const Scaffold(body: Text('landed'));
                },
              ),
          ],
        );

        await tester.pumpWidget(
          ProviderScope(
            overrides: [
              launchDecisionProvider.overrideWith((ref) async => decision),
            ],
            child: MaterialApp.router(
              key: ValueKey(route),
              routerConfig: router,
              builder: (context, child) =>
                  Directionality(textDirection: TextDirection.rtl, child: child!),
            ),
          ),
        );

        await tester.pump();
        await tester.pump(const Duration(milliseconds: 50));
        expect(
          landedPath,
          route,
          reason: '$route must not wait out the brand hold',
        );

        await tester.pumpAndSettle();
        // Tear the tree down between iterations. Without this the SECOND iteration reuses the
        // first's `_LaunchScreenState`, whose `_navigated` guard is already set, and the test
        // fails on the guard rather than on the behaviour it is checking — which is exactly what
        // it did on the first run of this test.
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pumpAndSettle();
      }
    });

    /// **Regression test for a defect this session introduced.** `_shownAt` was
    /// `late final ... = DateTime.now()`, which initialises on FIRST READ — and its only read is
    /// inside `_scheduleNavigation`, after the launch check resolves. So the elapsed time was
    /// always ~0 and the hold was a full 4 seconds ON TOP of the check rather than a minimum the
    /// check runs inside. The existing tests could not see it: a provider that resolves at t=0
    /// makes both readings identical. This one gives the check a real duration.
    testWidgets('the 4 seconds is a MINIMUM the check runs inside, not 4s added to it', (
      tester,
    ) async {
      String? landedPath;
      final router = GoRouter(
        initialLocation: '/',
        routes: [
          GoRoute(path: '/', builder: (context, state) => const LaunchScreen()),
          GoRoute(
            path: '/account-entry',
            builder: (context, state) {
              landedPath = '/account-entry';
              return const Scaffold(body: Text('landed'));
            },
          ),
        ],
      );

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            // A launch check that takes 3 seconds — well inside the 4-second minimum.
            launchDecisionProvider.overrideWith((ref) async {
              await Future<void>.delayed(const Duration(seconds: 3));
              return const FreshStart();
            }),
          ],
          child: MaterialApp.router(
            routerConfig: router,
            builder: (context, child) =>
                Directionality(textDirection: TextDirection.rtl, child: child!),
          ),
        ),
      );

      await tester.pump();
      await tester.pump(const Duration(milliseconds: 3100));
      await tester.pump();
      expect(landedPath, isNull, reason: 'the check is done at ~3s but the minimum is not');

      // Total elapsed ~4.6s. Under the fix the hold ended at 4.0s; under the defect it would end
      // at 3.0 + 4.0 = 7.0s, so this window separates them without depending on the exact frame
      // the future resolved on.
      await tester.pump(const Duration(milliseconds: 1500));
      await tester.pump();
      expect(
        landedPath,
        '/account-entry',
        reason: 'the hold is a minimum measured from the first frame, not 4s added after the check',
      );

      await tester.pumpAndSettle();
    });

    /// **Regression test for a hang the 4-second hold made invisible.** Seven screens route back
    /// to `/`. `launchDecisionProvider` is a plain non-autoDispose `FutureProvider`, so it keeps
    /// its `AsyncData` for the life of the process, and `ref.listen` fires only on CHANGE — so a
    /// re-entry got a cached answer, no callback, and a splash that spun forever. `initState`
    /// now invalidates the provider in `didChangeDependencies` — NOT `initState`, where `ref`
    /// may not touch an inherited widget — which is what makes Stage 0's "runs every time the
    /// app opens" true of a re-entry too.
    testWidgets('re-entering the splash re-runs the launch check instead of hanging', (
      tester,
    ) async {
      var checks = 0;
      String? landedPath;
      final router = GoRouter(
        initialLocation: '/',
        routes: [
          GoRoute(path: '/', builder: (context, state) => const LaunchScreen()),
          GoRoute(
            path: '/account-entry',
            builder: (context, state) {
              landedPath = '/account-entry';
              return Scaffold(
                body: TextButton(
                  onPressed: () => context.go('/'),
                  child: const Text('back to splash'),
                ),
              );
            },
          ),
        ],
      );

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            launchDecisionProvider.overrideWith((ref) async {
              checks++;
              return const FreshStart();
            }),
          ],
          child: MaterialApp.router(
            routerConfig: router,
            builder: (context, child) =>
                Directionality(textDirection: TextDirection.rtl, child: child!),
          ),
        ),
      );

      await tester.pump();
      await tester.pump(const Duration(milliseconds: 4100));
      await tester.pumpAndSettle();
      expect(landedPath, '/account-entry');
      expect(checks, 1);

      // Go back to the splash, exactly as BlockedScreen's recheck and five other screens do.
      landedPath = null;
      await tester.tap(find.text('back to splash'));
      await tester.pumpAndSettle();
      await tester.pump(const Duration(milliseconds: 4100));
      await tester.pumpAndSettle();

      expect(checks, 2, reason: 'the launch check must actually re-run, not serve a cached answer');
      expect(
        landedPath,
        '/account-entry',
        reason: 'and the customer must leave the splash rather than spin on it forever',
      );
    });

    /// **Four seconds is long enough to rotate the device or background the app.** This asserts
    /// the property that matters — one navigation, however many rebuilds happen mid-hold.
    ///
    /// **It does NOT exercise `_navigated`**, and saying so is the honest version
    /// (`@agent-reviewer`): Riverpod replaces an element's subscription on rebuild rather than
    /// adding one, and this provider emits once, so the guard has nothing to catch here and the
    /// test would pass with it removed. Driving the real race needs two decisions from one
    /// provider, which no production path produces today.
    testWidgets('a rebuild during the hold does not double-navigate', (tester) async {
      var landings = 0;
      final router = GoRouter(
        initialLocation: '/',
        routes: [
          GoRoute(path: '/', builder: (context, state) => const LaunchScreen()),
          GoRoute(
            path: '/account-entry',
            builder: (context, state) {
              landings++;
              return const Scaffold(body: Text('landed'));
            },
          ),
        ],
      );

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            launchDecisionProvider.overrideWith((ref) async => const FreshStart()),
          ],
          child: MaterialApp.router(
            routerConfig: router,
            builder: (context, child) =>
                Directionality(textDirection: TextDirection.rtl, child: child!),
          ),
        ),
      );

      await tester.pump();
      await tester.pump(const Duration(milliseconds: 1000));
      // Stand in for a rotation: a resize forces a rebuild mid-hold.
      tester.view.physicalSize = const Size(800, 400);
      addTearDown(tester.view.reset);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 4000));
      await tester.pumpAndSettle();

      expect(landings, 1, reason: 'exactly one navigation, however many rebuilds happened');
    });

    /// A timer that outlives the screen would call `context.go` on a dead element. `pumpWidget`
    /// with a different tree disposes this one; the test fails on a pending-timer assertion if
    /// `dispose` does not cancel it.
    testWidgets('the hold timer is cancelled when the screen goes away', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            launchDecisionProvider.overrideWith((ref) async => const FreshStart()),
          ],
          child: const MaterialApp(
            home: Directionality(textDirection: TextDirection.rtl, child: LaunchScreen()),
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));

      // Replace the tree while the hold is still pending.
      await tester.pumpWidget(const SizedBox.shrink());
      // **Deliberately LESS than the 4000 ms hold.** Pumping past it let the orphaned timer fire
      // inside this pump, so nothing was pending at teardown and the test passed with
      // `_minimumTimer?.cancel()` removed — it proved nothing (`@agent-reviewer`). Stopping short
      // leaves the timer genuinely pending, which is what flutter_test's teardown assertion
      // catches.
      await tester.pump(const Duration(milliseconds: 500));

      expect(tester.takeException(), isNull);
    });

    /// D6.5. The customer still gets the whole brand lockup — it is simply already there.
    testWidgets('with the OS "remove animations" setting on, the lockup is fully visible at once', (
      tester,
    ) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            // Never completes, so the splash stays on screen and nothing navigates away.
            launchDecisionProvider.overrideWith((ref) => Completer<LaunchDecision>().future),
          ],
          // `copyWith` on the ambient MediaQuery rather than a fresh MediaQueryData: a bare one
          // carries `size: Size.zero`, which collapses the layout and makes the assertion below
          // fail for a reason that has nothing to do with animations.
          child: MaterialApp(
            builder: (context, child) => MediaQuery(
              data: MediaQuery.of(context).copyWith(disableAnimations: true),
              child: Directionality(textDirection: TextDirection.rtl, child: child!),
            ),
            home: const LaunchScreen(),
          ),
        ),
      );
      await tester.pump();

      // AD-012 changed WHAT the proof is, because it changed the mechanism again. S8-05's light
      // pass is gone with the old lockup; Splash C's entrances are a trek and a rise, and
      // `_controller.value = 1` puts both at their settled end state on the first frame.
      //
      // The proof is now that the mark has finished travelling: `_pearl` translates it by
      // `190 * (1 - t)`, so a non-zero offset means the trek was caught mid-flight.
      final travelling = tester
          .widgetList<Transform>(find.byType(Transform))
          .where((t) => t.transform.getTranslation().x.abs() > 0.5);
      expect(
        travelling,
        isEmpty,
        reason: 'with animations disabled the trek must be complete, not caught mid-flight',
      );

      // And every line is laid out and legible on that same first frame.
      for (final line in ['بيــانــاتــي', 'لؤلؤة المصارف']) {
        expect(
          find.text(line, findRichText: true),
          findsOneWidget,
          reason: '$line must be fully visible immediately when animations are disabled',
        );
      }
    });

    testWidgets('Splash C carries the app name, the slogan and the bank wordmark', (
      tester,
    ) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            launchDecisionProvider.overrideWith((ref) => Completer<LaunchDecision>().future),
          ],
          child: const MaterialApp(
            home: Directionality(textDirection: TextDirection.rtl, child: LaunchScreen()),
          ),
        ),
      );
      await tester.pump(const Duration(seconds: 2));

      // AD-012: the app name is Splash C's own string, tatweel (U+0640) elongation included.
      // The BANK's name is no longer set in type beside it — Splash C carries it as the
      // wordmark artwork near the bottom edge, which is why «الفرنسي» is gone from the lockup.
      expect(find.text('بيــانــاتــي', findRichText: true), findsOneWidget);
      expect(find.textContaining('الفرنسي'), findsNothing);

      // D1.5 survives the redesign: the slogan appears on the splash and NOWHERE else.
      expect(find.text('لؤلؤة المصارف'), findsOneWidget);

      final assets = tester.widgetList<Image>(find.byType(Image)).map(_assetNameOf).toList();
      expect(assets, contains('assets/brand/sfb-wordmark-navy.png'));
    });

    testWidgets('the app name is announced without its typographic elongation', (tester) async {
      // The kashida is a TYPOGRAPHIC device. Without `semanticsLabel` a screen reader announces
      // a stretched spelling of the bank's own product name, which is the defect the previous
      // lockup also had to guard against — the redesign must not lose the guard with the design.
      final handle = tester.ensureSemantics();
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            launchDecisionProvider.overrideWith((ref) => Completer<LaunchDecision>().future),
          ],
          child: const MaterialApp(
            home: Directionality(textDirection: TextDirection.rtl, child: LaunchScreen()),
          ),
        ),
      );
      await tester.pump(const Duration(seconds: 2));

      expect(find.bySemanticsLabel('بياناتي'), findsOneWidget);
      handle.dispose();
    });

    /// **Walk comment 10, the direction defect.** The mark and the progress hairline animated
    /// AGAINST each other: `LinearProgressIndicator` mirrors its indeterminate sweep under RTL
    /// (`_LinearProgressIndicatorPainter` computes `left = (1 - endFraction) * width`), so the
    /// bar always ran right-to-left — while the trek entered from the layout END side, which in
    /// RTL is the LEFT, and so flew left-to-right. On an Arabic-first splash the two read as
    /// fighting.
    ///
    /// Asserted as a TRAVEL DIRECTION rather than a start coordinate: the sign of the offset is
    /// an implementation detail, "it arrives moving the way Arabic reads" is the requirement.
    testWidgets('the pearl treks right-to-left under RTL, with the progress bar', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            launchDecisionProvider.overrideWith((ref) => Completer<LaunchDecision>().future),
          ],
          child: const MaterialApp(
            home: Directionality(textDirection: TextDirection.rtl, child: LaunchScreen()),
          ),
        ),
      );

      final pearl = find.byWidgetPredicate(
        (w) => w is Image && _assetNameOf(w) == 'assets/brand/sfb-pearl.png',
      );

      await tester.pump(const Duration(milliseconds: 100));
      final entering = tester.getCenter(pearl).dx;

      await tester.pump(const Duration(milliseconds: 1700));
      final settled = tester.getCenter(pearl).dx;

      expect(
        entering,
        greaterThan(settled),
        reason:
            'RTL: the mark must enter from the RIGHT and travel leftward. '
            'entering=$entering settled=$settled — entering < settled is the defect',
      );
    });

    /// The mirror case. The side is derived from the ambient `Directionality`, not hardcoded, so
    /// flipping the fix's sign to satisfy RTL alone would fail here.
    testWidgets('the same trek mirrors under LTR', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            launchDecisionProvider.overrideWith((ref) => Completer<LaunchDecision>().future),
          ],
          child: const MaterialApp(
            home: Directionality(textDirection: TextDirection.ltr, child: LaunchScreen()),
          ),
        ),
      );

      final pearl = find.byWidgetPredicate(
        (w) => w is Image && _assetNameOf(w) == 'assets/brand/sfb-pearl.png',
      );

      await tester.pump(const Duration(milliseconds: 100));
      final entering = tester.getCenter(pearl).dx;
      await tester.pump(const Duration(milliseconds: 1700));
      final settled = tester.getCenter(pearl).dx;

      expect(entering, lessThan(settled), reason: 'LTR: enters from the left, travels rightward');
    });

    testWidgets('the pearl is drawn from the generated asset, not the old emblem', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            launchDecisionProvider.overrideWith((ref) => Completer<LaunchDecision>().future),
          ],
          child: const MaterialApp(
            home: Directionality(textDirection: TextDirection.rtl, child: LaunchScreen()),
          ),
        ),
      );
      await tester.pump(const Duration(seconds: 2));

      final assets = tester.widgetList<Image>(find.byType(Image)).map(_assetNameOf).toList();
      expect(assets, contains('assets/brand/sfb-pearl.png'));
      // The old lockup's emblem is the LAUNCHER icon's artwork now; it must not also be here.
      expect(assets, isNot(contains('assets/brand/sfb-emblem.png')));
    });
  });

  testWidgets('BL-021 — LaunchBlocked navigates to /blocked, carrying the real deadline', (
    tester,
  ) async {
    // The surface `@agent-reviewer` flagged as untested on the S8-14 diff. Before that session
    // this decision did not exist at all: the backend's BLOCKED answer threw out of the decode and
    // the customer was told to check their internet connection.
    final until = DateTime.now().add(const Duration(minutes: 15));

    expect(await pumpAndNavigate(tester, LaunchBlocked(blockedUntil: until)), '/blocked');
    // The deadline is the whole point — a destination assertion alone would not show it arrived.
    expect(find.textContaining('blocked until $until'), findsOneWidget);
    // The launch path always has a session behind it, so the reassurance is kept here.
    expect(find.textContaining('kept true'), findsOneWidget);
  });

  testWidgets('BL-021 — a BLOCKED launch with no deadline still reaches the block screen', (
    tester,
  ) async {
    // Defensive: the backend only sends BLOCKED with a live deadline, but the screen renders a
    // null one correctly and must still be reachable rather than falling through to an error.
    expect(
      await pumpAndNavigate(tester, const LaunchBlocked(blockedUntil: null)),
      '/blocked',
    );
  });
}
