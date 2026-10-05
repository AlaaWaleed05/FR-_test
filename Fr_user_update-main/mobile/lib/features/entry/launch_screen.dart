import 'dart:async' show Timer;

import 'package:flutter/foundation.dart' show kDebugMode;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/entry/entry_models.dart';
import '../../core/entry/entry_providers.dart';
import '../../core/theme/app_theme.dart';
import '../../core/widgets/brand_dune.dart';
import 'blocked_screen.dart';

/// The app's entry point (docs/journeys/customer.md Stage 0 — "runs every time the app opens,
/// before any screen is shown"). Watches [launchDecisionProvider] and, once it resolves, navigates
/// to whatever it decided — fresh Stage 1a, a resumed Stage 1b/awaiting-verification, or a
/// terminal screen.
///
/// **Design_3's "Splash C — horizon" since AD-012** (product-owner ruling 2026-09-12), replacing
/// D7's flat brand-blue lockup: a navy sky cut by the dune curve, the pearl mark riding the crest,
/// and the app name in Amiri on the paper half below. Fork 4 of that ruling takes the ENTRANCES
/// only — the handoff's camel-gait bob, halo pulse and dust dashes all loop forever and are
/// dropped.
///
/// **The motion here is decoration and nothing else** — the controller is never a gate, nothing
/// awaits it, and the animation finishing is not what moves the customer on.
///
/// **D7.3's "no minimum display time" no longer holds.** It said this screen fills a wait that
/// already exists and must never manufacture one; AD-012's fork 6 (product-owner decision,
/// 2026-09-12) manufactures one deliberately, because the client wants the brand moment held.
/// See [_minimumDisplay] for the rule, its one exception and its cost. Stated here because this
/// paragraph is the first thing a reader of this file sees, and the rule it used to state is now
/// the opposite of what the code does.
class LaunchScreen extends ConsumerStatefulWidget {
  const LaunchScreen({super.key});

  @override
  ConsumerState<LaunchScreen> createState() => _LaunchScreenState();
}

class _LaunchScreenState extends ConsumerState<LaunchScreen>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1800),
  );

  bool _reducedMotionApplied = false;

  /// One-shot latch for the launch-check invalidation below. `didChangeDependencies` runs after
  /// `initState` AND on every dependency change, and invalidating on each of those would re-run
  /// the backend check repeatedly.
  bool _checkInvalidated = false;

  /// True once the brand hold has elapsed. Driven by a [Timer] started in `initState`.
  ///
  /// **Deliberately a timer and a latch, NOT `DateTime.now()` arithmetic.** The first version
  /// stamped a wall-clock instant and subtracted it when the decision arrived. That is wrong in a
  /// way only a device would have shown: `Timer` runs on the scheduler's clock, which
  /// `tester.pump(duration)` advances, while `DateTime.now()` is real wall time, which it does
  /// not — so in every widget test the elapsed time was ~0 and the hold became a full 4 seconds
  /// ADDED to the launch check. `@agent-reviewer` caught the `late final` lazy-init half of it;
  /// the clock mismatch underneath survived that fix and was only found by measuring a real
  /// timeline. One clock throughout removes both.
  bool _minimumElapsed = false;

  /// Held when the decision arrives before the hold has finished.
  LaunchDecision? _pendingDecision;

  Timer? _minimumTimer;

  /// Set the instant a destination is chosen, and never unset.
  ///
  /// **The double-navigation guard.** Its real job is narrower than the first version of this
  /// comment claimed (`@agent-reviewer`): Riverpod REPLACES an element's subscription on rebuild
  /// rather than adding one, and notifies only on change, so a rotation does not by itself
  /// schedule a second navigation. What it guards is the two routes into [_navigateOnce] racing
  /// — a journey-ending decision bypassing the hold while the timer holds a parked one — which
  /// needs the provider to emit twice in one screen lifetime. With today's single invalidate it
  /// emits once, so the race is **not currently reachable**; the guard is cheap insurance against
  /// that changing, not a fix for a live defect.
  bool _navigated = false;

  @override
  void initState() {
    super.initState();
    _minimumTimer = Timer(_minimumDisplay, _onMinimumElapsed);
    _controller.forward();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();

    // **Re-entering `/` must re-run the launch check, and without this it does not.**
    // `launchDecisionProvider` is a plain, non-autoDispose `FutureProvider`, so once it settles
    // to `AsyncData` it stays there for the life of the process. `ref.listen` below notifies only
    // on CHANGE, so a customer routed back here — `BlockedScreen`'s recheck, Stage 8/9/10's
    // "leave", Stage 9's profile-not-found, the final-stages restart: SEVEN call sites — got a
    // cached answer, no listener callback, and a splash that spun forever.
    //
    // Invalidating HERE rather than at each `go('/')` is deliberate: seven callers each
    // remembering to invalidate is the shape that produced BL-123, and Stage 0's own rule is that
    // the check "runs every time the app opens, before any screen is shown". This screen IS that
    // gate, so it is the honest place to say it once.
    //
    // **In `didChangeDependencies`, not `initState`** — `ref` reads an inherited widget
    // (`ProviderScope`), which `initState` may not do; the first attempt did and threw
    // `dependOnInheritedWidgetOfExactType() was called before initState() completed` on every
    // test in this file.
    //
    // Found by `@agent-reviewer`. The hang predates the 4-second hold, but the hold made it
    // indistinguishable from a working splash for the first four seconds.
    if (!_checkInvalidated) {
      _checkInvalidated = true;
      ref.invalidate(launchDecisionProvider);
    }
    // D6.5: honour the OS "remove animations" setting. Jump to the finished composition rather
    // than playing it — the customer still sees the full brand lockup, just without the motion.
    if (!_reducedMotionApplied && MediaQuery.disableAnimationsOf(context)) {
      _reducedMotionApplied = true;
      _controller.value = 1;
    }
  }

  @override
  void dispose() {
    // Belt and braces. `_navigateOnce` already refuses when unmounted, so an uncancelled timer
    // would be harmless rather than fatal — cancelling is simply not leaving a timer running for
    // a screen that no longer exists. Proven to matter: removing this line fails
    // `the hold timer is cancelled when the screen goes away` with flutter_test's
    // "A Timer is still pending even after the widget tree was disposed".
    _minimumTimer?.cancel();
    _controller.dispose();
    super.dispose();
  }

  /// A sub-interval of the ONE controller, so the whole sequence stays a single timeline rather
  /// than five controllers that can drift apart.
  Animation<double> _step(double begin, double end, {Curve curve = Curves.easeOutCubic}) {
    return CurvedAnimation(parent: _controller, curve: Interval(begin, end, curve: curve));
  }

  /// Design_3's "Splash C — horizon", the approved artboard (AD-012).
  ///
  /// **Laid out proportionally, not at the artboard's absolute pixels.** The handoff authors
  /// everything against a 390 × 844 iPhone frame with absolute `top:` values. Transcribing those
  /// literally would put the app name off-screen on the 5-inch API-28 pilot handset, so each
  /// anchor below is expressed as a fraction of the artboard and multiplied by the real height.
  /// The composition is the design; the pixel numbers were only ever one device's expression
  /// of it.
  @override
  Widget build(BuildContext context) {
    ref.listen<AsyncValue<LaunchDecision>>(launchDecisionProvider, (previous, next) {
      final decision = next.valueOrNull;
      if (decision == null) return;
      // Deliberately NOT awaiting `_controller` — see the class comment. The 4-second brand hold
      // is a separate clock from the animation and is applied in `_scheduleNavigation`.
      _scheduleNavigation(decision);
    });

    final decision = ref.watch(launchDecisionProvider);
    return Scaffold(
      // Splash C's ground is PAPER, not navy: the navy is the sky above the dune and the lower
      // half of the screen is white. The Android launch window stays navy, so the native-to-
      // Flutter handoff still lands navy-on-navy at the top of the frame — D7.2's "continuous
      // ground" property survives the redesign, it just applies to the sky rather than the
      // whole screen.
      backgroundColor: Colors.white,
      body: LayoutBuilder(
        builder: (context, constraints) {
          final h = constraints.maxHeight;
          // Fractions of the 844 dp artboard.
          double at(double y) => h * (y / 844);
          return Stack(
            children: [
              // 1. The navy sky with its curved dune bottom edge.
              Positioned(
                top: 0,
                left: 0,
                right: 0,
                height: at(500),
                child: const BrandDune.sky(),
              ),
              // 2. The pearl on the crest — centre at y = 404, in a 156 dp stage.
              Positioned(
                top: at(404) - 78,
                left: 0,
                right: 0,
                height: 156,
                child: Center(child: _pearl()),
              ),
              // 3. The name block — app name, hairline, tagline.
              Positioned(
                top: at(520),
                left: 24,
                right: 24,
                child: _rise(_step(0.42, 0.78), child: _nameBlock(context)),
              ),
              // 4. The bank wordmark, with 5. the status area beneath it.
              Positioned(
                left: 0,
                right: 0,
                bottom: at(44),
                child: _rise(
                  _step(0.58, 1),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Image.asset(
                        'assets/brand/sfb-wordmark-navy.png',
                        width: 210,
                        // D6.3's house rule: cap the decode rather than decoding the full asset.
                        cacheWidth: (210 * MediaQuery.devicePixelRatioOf(context)).round(),
                      ),
                      const SizedBox(height: 30),
                      // Splash C's "progress hairline" is where this app's launch-check state
                      // lives — the design's own slot for "still working", spent on the real
                      // thing rather than on a decorative loop.
                      SizedBox(height: 28, child: Center(child: _status(decision))),
                    ],
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }

  /// The trek: the rider travels in from the side and settles.
  ///
  /// **AD-012 fork 4 — entrances only.** The handoff specifies six animations, three of them
  /// infinite: a camel-gait bob on the mark, a halo pulse and two dust dashes. Those are dropped
  /// by product-owner ruling. The pilot handset is a Huawei Y5 2019 on API 28, and three
  /// forever-loops keep the GPU awake for the whole splash in order to decorate a wait the
  /// launch check may end at any moment. The handoff itself specifies this settled end state
  /// under `prefers-reduced-motion`, so it is a documented variant of the design rather than a
  /// departure from it.
  ///
  /// **The trek enters from the side reading STARTS on, and travels with the text** — under RTL
  /// that is in from the right, moving leftward. Corrected from the walk test (comment 10): the
  /// previous version entered from the **end** side, so on this Arabic-first app the mark flew
  /// left-to-right while the progress hairline below it swept right-to-left, and the two read as
  /// fighting each other.
  ///
  /// The hairline is not something this file controls. Flutter's own `LinearProgressIndicator`
  /// mirrors its indeterminate sweep under RTL — `_LinearProgressIndicatorPainter` computes
  /// `left = (1 - endFraction) * width` when `textDirection` is rtl — so the bar was always
  /// right-to-left and correct, and the mark was the half that disagreed. Flipping the mark is
  /// therefore the whole fix; nothing about the bar changes.
  ///
  /// Still derived from the ambient [Directionality] rather than hardcoded, which is D6.1's rule
  /// and the part the previous version got right. Only the sign moved.
  Widget _pearl() {
    final animation = _step(0, 0.55);
    final fromStart = Directionality.of(context) == TextDirection.rtl ? 1.0 : -1.0;
    return AnimatedBuilder(
      animation: animation,
      builder: (context, child) => Transform.translate(
        offset: Offset(fromStart * 190 * (1 - animation.value), 0),
        child: Opacity(opacity: animation.value.clamp(0, 1), child: child),
      ),
      child: SizedBox(
        width: 156,
        height: 156,
        child: Stack(
          alignment: Alignment.center,
          children: [
            // The halo, at its settled opacity rather than pulsing.
            DecoratedBox(
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: RadialGradient(
                  colors: [
                    Colors.white.withValues(alpha: 0.30),
                    Colors.white.withValues(alpha: 0),
                  ],
                  stops: const [0, 0.68],
                ),
              ),
              child: const SizedBox(width: 156, height: 156),
            ),
            Container(
              width: 140,
              height: 140,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                border: Border.all(color: AppTheme.brandSteel.withValues(alpha: 0.55)),
              ),
            ),
            ClipOval(
              child: Image.asset(
                'assets/brand/sfb-pearl.png',
                width: 112,
                height: 112,
                fit: BoxFit.cover,
                cacheWidth: (112 * MediaQuery.devicePixelRatioOf(context)).round(),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// App name, hairline, tagline — Splash C's block, in navy on the paper half.
  Widget _nameBlock(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        // FittedBox rather than a smaller size: the name must not wrap or shrink the rest of the
        // stack on a narrow screen, and it is the line that should dominate.
        FittedBox(
          fit: BoxFit.scaleDown,
          child: Text(
            _appName,
            textAlign: TextAlign.center,
            // The tatweel is a TYPOGRAPHIC device, not part of the name. Without this, the
            // semantics node and anything extracting text see the elongated form, so a screen
            // reader would announce a stretched spelling of the product's own name.
            semanticsLabel: 'بياناتي',
            // Amiri at display size — the one place AD-012 fork 2 puts it to real work.
            style: theme.textTheme.displayLarge?.copyWith(
              fontSize: 62,
              height: 1,
              color: AppTheme.brandNavy,
            ),
          ),
        ),
        const SizedBox(height: 18),
        Container(
          width: 46,
          height: 1,
          color: AppTheme.brandNavy.withValues(alpha: 0.35),
        ),
        const SizedBox(height: 10),
        Text(
          // The slogan appears HERE AND NOWHERE ELSE (D1.5) — not in the banner, not on the
          // confirmation screen. Splash C keeps it in the same slot, so the rule survives.
          'لؤلؤة المصارف',
          textAlign: TextAlign.center,
          style: theme.textTheme.headlineSmall?.copyWith(
            fontSize: 23,
            height: 1.5,
            color: AppTheme.brandNavy,
          ),
        ),
      ],
    );
  }

  /// «بيــانــاتــي» — the tatweel elongation (U+0640, twice) is part of the handoff's own string,
  /// not padding added here.
  static const String _appName = 'بيــانــاتــي';

  /// Fade in while rising a few logical pixels. The distance is small on purpose — this is a bank
  /// identity screen, not a product launch.
  ///
  /// `FadeTransition`, not `Opacity`: Flutter's own performance guidance is to *"avoid using the
  /// `Opacity` widget, and particularly avoid it in an animation"*.
  Widget _rise(Animation<double> animation, {required Widget child}) {
    return FadeTransition(
      opacity: animation,
      child: AnimatedBuilder(
        animation: animation,
        builder: (context, inner) =>
            Transform.translate(offset: Offset(0, 14 * (1 - animation.value)), child: inner),
        child: child,
      ),
    );
  }

  Widget _status(AsyncValue<LaunchDecision> decision) {
    return decision.when(
      loading: _progress,
      error: (error, stackTrace) => _error(error),
      data: (_) => _progress(),
    );
  }

  /// Splash C's progress hairline: 120 × 1.5, steel on a faint navy track.
  ///
  /// Indeterminate, deliberately: a determinate bar would be a claim about how long the launch
  /// check takes, which nothing here knows. (D7.3's "never manufacture a wait" is no longer the
  /// rule — fork 6 holds the splash on purpose — but that changes how long the bar runs, not
  /// whether it may promise a duration.)
  Widget _progress() {
    return const SizedBox(
      width: 120,
      height: 1.5,
      child: LinearProgressIndicator(
        minHeight: 1.5,
        color: AppTheme.brandSteel,
        backgroundColor: Color(0x260B1C47),
      ),
    );
  }

  /// D7.5/D8.3. The previous string interpolated the raw exception, rendering English Dart text to
  /// a Sudanese retail customer — and a network exception's text can carry the request URI and
  /// response content, so it was a UX defect and a hygiene defect with one fix. The customer gets a
  /// fixed Arabic sentence naming the next action; the detail is kept out of the UI entirely.
  ///
  /// **The copy no longer blames the connection (S8-14, BL-021).** A connectivity failure never
  /// reaches here — `launchDecision()` catches `BackendUnreachableException` and resumes offline
  /// — so everything that lands in this branch is by definition NOT a network problem. Telling
  /// the customer to check their internet was therefore wrong for every case it could ever show,
  /// and for a phone-locked customer it was wrong in the specific way that hid a bank block behind
  /// a network excuse for two sprints.
  ///
  /// **Navy on paper since AD-012**, not white: this sits on the white half of Splash C, so the
  /// old `Colors.white` would have rendered it invisible.
  Widget _error(Object error) {
    // Debug builds only: a release APK must not write response content to logcat.
    if (kDebugMode) debugPrint('launch decision failed: $error');
    return const Padding(
      padding: EdgeInsets.symmetric(horizontal: 24),
      child: Text(
        'تعذر بدء التطبيق. يرجى إعادة فتح التطبيق، وإذا تكرر ذلك يرجى زيارة أقرب فرع.',
        textAlign: TextAlign.center,
        style: TextStyle(color: AppTheme.brandNavy, fontSize: 14),
      ),
    );
  }

  /// **The 4-second brand hold — product-owner decision, 2026-09-12.**
  ///
  /// The mechanism is the handoff's own: Splash C states "hold the splash until app bootstrap
  /// resolves, minimum ~1.6 s so the trek animation isn't cut", and "the progress hairline loops
  /// until routing completes". This is that minimum, set to the client's value.
  ///
  /// **This inverts D7.3**, which said there is no minimum display time and that this screen must
  /// never manufacture a wait. It now does manufacture one, deliberately, because the client wants
  /// the brand moment held. Recorded rather than quietly reversed.
  ///
  /// **The cost, stated because it is paid on EVERY launch.** Four seconds is added to a cold
  /// start AND to every resume. A customer working through twelve stages may open the app several
  /// times — after an OTP arrives, after a document scan, after being interrupted — and pays it
  /// each time. On the API-28 pilot handset the launch check itself typically resolves well inside
  /// this, so in practice the customer is usually waiting on the minimum rather than on the work.
  ///
  /// **Nothing was added to fill the time.** AD-012's fourth fork ruled entrances-then-settle and
  /// dropped the handoff's three infinite animations; padding the extra seconds with motion would
  /// be that ruling reversed in a different shape. The composition settles at 1.8 s and simply
  /// holds. The progress hairline keeps running, which is the handoff's own instruction and stays
  /// truthful: it means "not yet routed", which is exactly what is still true.
  static const Duration _minimumDisplay = Duration(milliseconds: 4000);

  /// Navigates now, or after the brand hold — with one deliberate exception.
  ///
  /// **A decision that ENDS the journey bypasses the hold entirely.** [LaunchTerminal] (the
  /// account is not active), [LaunchEnded] (the update has already finished) and [LaunchBlocked]
  /// (a live phone lock) are all answers the app already has and the customer cannot act on until
  /// they see them. Making someone wait four seconds to be told their account is inactive spends
  /// their time on a brand moment in the one situation where they are not going to feel warmly
  /// about the brand. [LaunchBlocked] has a second, concrete reason: its screen renders a live
  /// countdown to the moment the lock lifts, and four seconds of held splash is four seconds that
  /// countdown is already wrong by when it first appears.
  ///
  /// The hold therefore applies to a launch that is PROCEEDING into the journey, which is the
  /// case the brand moment is for.
  ///
  /// A launch-check ERROR is not routed through here at all: it renders in place on this screen
  /// (`_error`), so there is no navigation to delay and nothing to decide. It appears the instant
  /// it is known.
  void _scheduleNavigation(LaunchDecision decision) {
    if (_navigated) return;

    final endsTheJourney =
        decision is LaunchTerminal || decision is LaunchEnded || decision is LaunchBlocked;

    if (endsTheJourney || _minimumElapsed) {
      _navigateOnce(decision);
      return;
    }
    // The hold is still running. Park the answer; the timer delivers it.
    _pendingDecision = decision;
  }

  /// The brand hold has finished. Go if the answer is already in hand; otherwise
  /// [_scheduleNavigation] will go the moment it arrives.
  void _onMinimumElapsed() {
    _minimumElapsed = true;
    final pending = _pendingDecision;
    if (pending != null) _navigateOnce(pending);
  }

  /// The single navigation point, and the only place `_navigate` is called.
  ///
  /// Both guards are defensive rather than load-bearing today, which is worth saying plainly
  /// (`@agent-reviewer`): `dispose` cancels the timer, so an unmounted callback should not occur,
  /// and the provider emits once per screen, so the two entries below should not race. They are
  /// kept because each becomes reachable the moment that stops being true — a second emission,
  /// or a timer path that outlives `dispose`.
  void _navigateOnce(LaunchDecision decision) {
    if (_navigated || !mounted) return;
    _navigated = true;
    _navigate(context, decision);
  }

  /// Every destination re-reads its actual field values from `EntryRepository` itself — the
  /// branch/account pair, or the profile id and channel summary, are all still on disk, so loading
  /// them directly (rather than trusting a value carried through navigation) works identically
  /// whether the screen was reached from here or from ordinary in-app navigation (e.g. "back to
  /// start" from the terminal screen). Two things have no other source and are carried via
  /// `extra`: the `offline` flag (ephemeral — this launch's own connectivity result, not persisted
  /// anywhere) for the two resume cases, and [LaunchTerminal]'s message (`launchDecision()` already
  /// cleared local state before returning it).
  void _navigate(BuildContext context, LaunchDecision decision) {
    switch (decision) {
      case FreshStart():
        context.go('/account-entry');
      case ResumeContactChannels(:final offline):
        context.go('/contact-channels', extra: offline);
      case ResumeAwaitingVerification(:final offline):
        context.go('/channel-verification', extra: offline);
      case ResumeDataEntry(:final stage, :final offline):
        context.go(_dataEntryRoute(stage), extra: offline);
      case ResumeIdentityScan(:final stage, :final offline):
        context.go(_identityScanRoute(stage), extra: offline);
      case ResumeVerified(:final offline):
        context.go('/session-pending', extra: offline);
      // Stages 10-12 do not name a screen here — the gate asks the backend which one. See
      // `FinalStagesGateScreen`.
      case ResumeFinalStages(:final offline):
        context.go('/final-stages', extra: offline);
      case LaunchTerminal(:final message):
        context.go('/terminal', extra: message);
      // BL-123. The already-finished profile has its own screen, which owns its own copy.
      case LaunchEnded():
        context.go('/ended');
      // BL-021. A live phone lock, carried with its real deadline so the screen can count it down
      // instead of blaming the customer's connection.
      case LaunchBlocked(:final blockedUntil):
        context.go(
          '/blocked',
          extra: BlockedArgs(blockedUntil: blockedUntil, progressPreserved: true),
        );
    }
  }

  String _dataEntryRoute(DataEntryStage stage) {
    switch (stage) {
      case DataEntryStage.stage3:
        return '/stage-3';
      case DataEntryStage.stage4:
        return '/stage-4';
      case DataEntryStage.stage5:
        return '/stage-5';
      case DataEntryStage.stage6:
        return '/stage-6';
      case DataEntryStage.stage7:
        return '/stage-7';
    }
  }

  String _identityScanRoute(IdentityScanStage stage) {
    switch (stage) {
      case IdentityScanStage.stage8:
        return '/stage-8';
      case IdentityScanStage.stage9:
        return '/stage-9';
    }
  }
}
