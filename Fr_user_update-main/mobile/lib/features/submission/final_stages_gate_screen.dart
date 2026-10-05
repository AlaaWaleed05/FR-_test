import 'dart:async' show unawaited;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/entry/entry_models.dart'
    show BackendUnreachableException, IdentityScanStage;
import '../../core/entry/entry_providers.dart';
import '../../core/identityscan/identity_scan_providers.dart';
import '../../core/journey/journey_error.dart';
import '../../core/journey/journey_models.dart';
import '../../core/journey/journey_providers.dart';
import 'confirmation_screen.dart';

/// The one place this app decides which of Stages 10, 11 and 12 a customer belongs on.
///
/// **Why a gate screen and not a local pointer.** Stages 8 and 9 each have their own resume value on
/// disk. Stages 10-12 deliberately share ONE (`beyondStage9`) meaning "in the final stages — ask
/// the backend", and this screen is the ask. customer.md Stage 13 is unambiguous that "the backend
/// alone decides whether the session is still open. Every resume begins by asking", and Stages
/// 10-12's real state is backend-owned in every respect: whether liveness passed, whether a
/// signature is stored, whether the profile is submitted, whether a 24-hour block is running. A
/// per-stage local pointer would be a second source for facts the device does not own, and the two
/// would eventually disagree.
///
/// All three stages need the network anyway, so nothing is lost by requiring the read.
///
/// **This is a PLACEMENT answer, never an AUTHORIZATION one — the AD-008/BL-041 line.**
/// [JourneyStage.signature] and [JourneyStage.submit] are derived server-side from `facePassed`,
/// which makes "the backend says liveness passed, so skip Stage 10" a tempting shortcut. It is not
/// built here and must not be: this gate is reached only when local state already holds a
/// `profileId` (`JourneyRepository.requireProfileId` throws otherwise), which today means the device
/// that did Stage 10 is the device being placed. When BL-041's device-less supersession lands, a
/// new-device re-entry will be able to reach a profile whose prior `face_result` it inherited, and a
/// shortcut here would let an impostor skip liveness entirely. The pointer says where a journey got
/// to; it never says who is entitled to be there.
class FinalStagesGateScreen extends ConsumerStatefulWidget {
  const FinalStagesGateScreen({super.key, this.offline = false});

  final bool offline;

  @override
  ConsumerState<FinalStagesGateScreen> createState() =>
      _FinalStagesGateScreenState();
}

class _FinalStagesGateScreenState extends ConsumerState<FinalStagesGateScreen> {
  String? _error;

  @override
  void initState() {
    super.initState();
    unawaited(_resolve());
  }

  Future<void> _resolve() async {
    setState(() => _error = null);
    try {
      final pointer = await ref
          .read(journeyRepositoryProvider)
          .currentPointer();
      if (!mounted) return;
      switch (pointer.stage) {
        case JourneyStage.liveness:
        case JourneyStage.livenessBlocked:
          // Both land on Stage 10. The block is not a separate destination: Stage 10's own
          // `/token` answers `LIVENESS_BLOCKED` with the deadline, so the screen shows the block
          // with a live countdown rather than this gate rendering a second version of it.
          context.go('/stage-10', extra: widget.offline);
        case JourneyStage.signature:
          context.go('/stage-11', extra: widget.offline);
        case JourneyStage.submit:
          context.go('/stage-12', extra: widget.offline);
        case JourneyStage.submitted:
          // customer.md Stage 13: "Returning to a submitted, approved or rejected profile". The
          // reference number and channels come from this same read, which is what makes a customer
          // whose app died before the confirmation screen recoverable at all.
          _goToConfirmation(pointer);
        // BL-106. These two used to fall into `_goToConfirmation` alongside `submitted`, so the
        // confirmation screen told an APPROVED customer their request was still awaiting review
        // and told a REJECTED one the same — while the back office rendered «معتمد»/«مرفوض»
        // correctly for the very same profile. The status was always on this wire; only the
        // routing collapsed it.
        case JourneyStage.approved:
          _goToOutcome('/approved', pointer);
        case JourneyStage.rejected:
          _goToOutcome('/rejected', pointer);
      }
    } on BackendUnreachableException {
      if (!mounted) return;
      // customer.md Stage 13's offline resume case: shown up front rather than as a failure at the
      // moment the customer taps Next. There is no offline path past this point — every one of
      // stages 10-12 needs the network.
      setState(
        () => _error =
            'تعذر الاتصال. يحتاج استكمال الطلب إلى اتصال بالإنترنت.',
      );
    } on JourneyConflictException catch (conflict) {
      if (!mounted) return;
      _applyConflict(conflict);
    } on JourneyProfileNotFoundException {
      if (!mounted) return;
      // BL-110. The backend has no such profile, so the `profileId` on this device is stale —
      // most likely a purge or a restored backup. Retrying re-sends the same dead id forever.
      // Clearing local state and starting again is the only recovery, and it is safe: there is
      // no server-side work to lose, because there is no profile.
      unawaited(_restart());
    } on Object {
      if (!mounted) return;
      setState(() => _error = 'حدث خطأ غير متوقع. يرجى المحاولة مرة أخرى.');
    }
  }

  /// BL-110's conflict arms. Before S8-16 this screen caught `BackendUnreachableException` and
  /// then a bare `on Object`, so every coded 409 landed on «حدث خطأ غير متوقع» behind a retry
  /// button that re-issued the identical refused call — forever. A customer whose status was
  /// `blocked_scan`, `awaiting_registry`, `abandoned` or terminated had no route to Stage 8,
  /// Stage 9 or `/ended` from the one screen whose entire job is placing a resuming customer.
  ///
  /// **Only three codes are reachable here**, verified against `SubmissionService
  /// .currentJourneyPointer` rather than taken from the shared enum: `UnknownProfileException`
  /// (404), `NotInFinalStagesException` (`STATE_CONFLICT`) and
  /// `ProfileNotEligibleException.terminalStatus` (`PROFILE_TERMINAL`). `LIVENESS_REQUIRED` and
  /// `SIGNATURE_REQUIRED` come from `submit`, never from `/current`. Anything else is a backend
  /// this app version does not know, and degrades to the same re-sync as `STATE_CONFLICT`.
  void _applyConflict(JourneyConflictException conflict) {
    switch (conflict.code) {
      case JourneyCode.profileTerminal:
        // `terminated_registry_mismatch` — the only terminal status that reaches this arm, since
        // submitted/approved/rejected all return a pointer instead of throwing. Still routed to
        // the screen that claims nothing, because this app must not start reasoning from which
        // exception it happened to catch.
        context.go('/ended');
      case JourneyCode.stateConflict:
      default:
        // The customer is not in stages 10-12 at all — `blocked_scan`, `awaiting_registry`,
        // `abandoned`, or in-progress with no accepted cycle.
        //
        // **Routing to `/` here is a LOOP, and the first draft of this arm did exactly that**
        // (caught by @agent-reviewer, S8-16). The launch check reads the on-disk `resumeStage`,
        // which is still `beyondStage9`; `AccountCheckService` answers `PROCEED` for every one of
        // those non-terminal statuses; so `_resume` returns `ResumeFinalStages` and lands the
        // customer straight back here — an unattended hot loop with two network calls a lap,
        // strictly worse than the retry button it replaced. The stale pointer is the actual
        // defect, so the pointer is what has to move.
        unawaited(_resyncToScan());
    }
  }

  /// Moves the on-disk resume pointer off `beyondStage9` and hands the customer to Stage 8.
  ///
  /// **Stage 8, not a guess between 8 and 9.** This screen cannot tell `blocked_scan` from
  /// `awaiting_registry` — `STATE_CONFLICT` is one code for both — and Stage 8 is the screen that
  /// re-derives the answer and forwards: `REGISTRY_PENDING` advances the pointer again and goes
  /// to Stage 9 (`stage8_screen.dart:_goToReview`), `SCAN_BLOCKED` renders the block with its
  /// countdown, and its own `STATE_CONFLICT` falls back to Stage 7. All of them terminate, which
  /// is what matters here — the loop cannot re-form once the pointer has moved off
  /// `beyondStage9`, and this method is what moves it. (Not all of them WRITE the pointer:
  /// `SCAN_BLOCKED` renders in place and the Stage 7 fallback does not write either. Corrected
  /// after `@agent-reviewer`'s second pass; the earlier claim that every arm rewrites it was
  /// wrong and was doing load-bearing work in this comment.)
  ///
  /// **Moving the pointer BACKWARDS is deliberate and has precedent.** It is the same move
  /// `stage9_screen.dart:_goToScan` already makes for the same reason: a correction forced by
  /// backend state, not the back-navigation S5-05 exempted. Nothing about entitlement is derived
  /// from it, so the AD-008/BL-041 placement-not-authorization line is untouched.
  Future<void> _resyncToScan() async {
    try {
      await ref
          .read(identityScanRepositoryProvider)
          .advanceToStage(IdentityScanStage.stage8.name);
    } on Object {
      // Best-effort: a failed write must not strand the customer on the dead end this exists to
      // escape, so the navigation happens either way.
      //
      // **What that costs, stated honestly.** Stage 8 does NOT re-read journey state on arrival
      // — `Stage8Screen._load` reads the local draft and the profile id only, and nothing is
      // fetched until the customer taps «ابدأ المسح» (corrected after `@agent-reviewer`'s second
      // pass; this comment previously claimed the opposite and was using it to justify the
      // swallow). So if this write fails, the customer is placed correctly NOW but a relaunch
      // still reads `beyondStage9` and returns here. That is the pre-existing dead end, not a
      // new loop — it needs the customer to relaunch, and it is strictly better than failing
      // the navigation too.
    }
    if (!mounted) return;
    context.go('/stage-8');
  }

  /// Clears the stale local session, then restarts at the launch check.
  Future<void> _restart() async {
    try {
      await ref.read(entryRepositoryProvider).abandon();
    } on Object {
      // Best-effort. A failed clear must not strand the customer on the dead end this method
      // exists to escape — the launch check re-runs the account check either way.
    }
    if (!mounted) return;
    context.go('/');
  }

  void _goToOutcome(String route, JourneyPointer pointer) {
    final reference = pointer.referenceNumber;
    if (reference == null) {
      // Same guard as `_goToConfirmation` — all three complete stages carry one, so this is
      // unreachable in practice and is handled rather than asserted.
      setState(() => _error = 'تعذر عرض تفاصيل الطلب. يرجى المحاولة مرة أخرى.');
      return;
    }
    context.go(route, extra: reference);
  }

  void _goToConfirmation(JourneyPointer pointer) {
    final reference = pointer.referenceNumber;
    if (reference == null) {
      // Should be unreachable — all three complete stages carry one.
      setState(() => _error = 'تعذر عرض تفاصيل الطلب. يرجى المحاولة مرة أخرى.');
      return;
    }
    context.go(
      '/confirmation',
      extra: ConfirmationArgs(
        referenceNumber: reference,
        verifiedChannels: pointer.verifiedChannels,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: _error == null
              ? const CircularProgressIndicator()
              : Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(_error!, textAlign: TextAlign.center),
                      const SizedBox(height: 24),
                      FilledButton(
                        onPressed: () => unawaited(_resolve()),
                        child: const Text('إعادة المحاولة'),
                      ),
                    ],
                  ),
                ),
        ),
      ),
    );
  }
}
