import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../features/dataentry/stage3_screen.dart';
import '../../features/dataentry/stage4_screen.dart';
import '../../features/dataentry/stage5_screen.dart';
import '../../features/dataentry/stage6_screen.dart';
import '../../features/dataentry/stage7_screen.dart';
import '../../features/entry/account_entry_screen.dart';
import '../../features/entry/blocked_screen.dart';
import '../../features/entry/channel_verification_screen.dart';
import '../../features/entry/contact_channels_screen.dart';
import '../../features/entry/launch_screen.dart';
import '../../features/entry/session_pending_screen.dart';
import '../../features/entry/ended_screen.dart';
import '../../features/entry/terminal_screen.dart';
import '../../features/identityscan/stage8_screen.dart';
import '../../features/identityscan/stage9_screen.dart';
import '../../features/liveness/stage10_screen.dart';
import '../../features/signature/stage11_screen.dart';
import '../../features/submission/confirmation_screen.dart';
import '../../features/submission/session_complete_screen.dart';
import '../../features/submission/final_stages_gate_screen.dart';
import '../../features/submission/outcome_screens.dart';
import '../../features/submission/stage12_screen.dart';

/// `/` runs Stage 0's launch check (docs/journeys/customer.md) before any journey screen is
/// shown, then redirects to whatever it decides.
///
/// S5-01's `/reference-demo` proof screen was DELETED on 2026-09-07 (D8.3). It had served its purpose,
/// was reachable by deep link, and its two untranslated error strings interpolated a raw Dart
/// exception — deleting the route was cheaper and safer than translating a demo.
final goRouterProvider = Provider<GoRouter>((ref) {
  return GoRouter(
    initialLocation: '/',
    routes: [
      GoRoute(path: '/', builder: (context, state) => const LaunchScreen()),
      // The terminal end state reached from «إنهاء» on the confirmation screen (walk comment 15c).
      GoRoute(
        path: '/session-complete',
        builder: (context, state) => const SessionCompleteScreen(),
      ),
      GoRoute(
        path: '/account-entry',
        builder: (context, state) => const AccountEntryScreen(),
      ),
      // BL-021. Reached from Stage 0's launch check and from Stage 1a's submit, which are the
      // only two places `account-check` is called.
      GoRoute(
        path: '/blocked',
        builder: (context, state) {
          final args = state.extra as BlockedArgs?;
          return BlockedScreen(
            blockedUntil: args?.blockedUntil,
            progressPreserved: args?.progressPreserved ?? true,
          );
        },
      ),
      GoRoute(
        path: '/contact-channels',
        builder: (context, state) =>
            ContactChannelsScreen(offline: (state.extra as bool?) ?? false),
      ),
      GoRoute(
        path: '/channel-verification',
        builder: (context, state) =>
            ChannelVerificationScreen(offline: (state.extra as bool?) ?? false),
      ),
      GoRoute(
        path: '/stage-3',
        builder: (context, state) =>
            Stage3Screen(offline: (state.extra as bool?) ?? false),
      ),
      GoRoute(
        path: '/stage-4',
        builder: (context, state) =>
            Stage4Screen(offline: (state.extra as bool?) ?? false),
      ),
      GoRoute(
        path: '/stage-5',
        builder: (context, state) =>
            Stage5Screen(offline: (state.extra as bool?) ?? false),
      ),
      GoRoute(
        path: '/stage-6',
        builder: (context, state) =>
            Stage6Screen(offline: (state.extra as bool?) ?? false),
      ),
      GoRoute(
        path: '/stage-7',
        builder: (context, state) =>
            Stage7Screen(offline: (state.extra as bool?) ?? false),
      ),
      // Stages 8-9 take the same `offline` extra as every other journey screen for consistency,
      // but neither can actually proceed without the network — Stage 7 is where the customer is
      // stopped if there is none (customer.md Stage 7's "Exits").
      GoRoute(
        path: '/stage-8',
        builder: (context, state) =>
            Stage8Screen(offline: (state.extra as bool?) ?? false),
      ),
      GoRoute(
        path: '/stage-9',
        builder: (context, state) =>
            Stage9Screen(offline: (state.extra as bool?) ?? false),
      ),
      // Stages 10-12 (S5-08). `/final-stages` is the gate: it performs the ONE
      // `POST /submission/current` read and redirects to whichever of the three the backend names,
      // so no other screen has to infer where the customer belongs.
      GoRoute(
        path: '/final-stages',
        builder: (context, state) =>
            FinalStagesGateScreen(offline: (state.extra as bool?) ?? false),
      ),
      GoRoute(
        path: '/stage-10',
        builder: (context, state) =>
            Stage10Screen(offline: (state.extra as bool?) ?? false),
      ),
      GoRoute(
        path: '/stage-11',
        builder: (context, state) =>
            Stage11Screen(offline: (state.extra as bool?) ?? false),
      ),
      GoRoute(
        path: '/stage-12',
        builder: (context, state) =>
            Stage12Screen(offline: (state.extra as bool?) ?? false),
      ),
      // Reached only with its `extra` in hand — the reference number and channels are held in
      // memory because this screen's own last act clears the local state they came from.
      GoRoute(
        path: '/confirmation',
        builder: (context, state) =>
            ConfirmationScreen(args: state.extra! as ConfirmationArgs),
      ),
      // Still mounted, but no longer reachable from `beyondStage9`: S5-08 repurposed that value
      // from "past everything this app builds" to "in stages 10-12". Kept because nothing else
      // supersedes it and removing a route is not this slice's business.
      GoRoute(
        path: '/session-pending',
        builder: (context, state) =>
            SessionPendingScreen(offline: (state.extra as bool?) ?? false),
      ),
      GoRoute(
        path: '/terminal',
        builder: (context, state) =>
            TerminalScreen(message: state.extra as String),
      ),
      // BL-123. Deliberately takes NO `extra`: this screen owns its own copy, which is what
      // stops a caller ever passing a sentence that is false for three of the four terminal
      // statuses that reach it. See `EndedScreen`'s own doc comment.
      GoRoute(path: '/ended', builder: (context, state) => const EndedScreen()),
      // BL-106. Both take the reference number and nothing else — the status itself is expressed
      // by WHICH route the gate chose, never by a parameter a caller could get wrong.
      GoRoute(
        path: '/approved',
        builder: (context, state) =>
            ApprovedScreen(referenceNumber: state.extra! as String),
      ),
      GoRoute(
        path: '/rejected',
        builder: (context, state) =>
            RejectedScreen(referenceNumber: state.extra! as String),
      ),
    ],
  );
});
