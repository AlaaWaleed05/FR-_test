import 'dart:async';

import 'package:flutter/material.dart';

import '../text/arabic_noun_agreement.dart';

/// The one block-state screen, shared by every surface that can be told "not now": Stage 8's and
/// Stage 9's scan block, Stage 10's liveness block, and Stage 0/1a's phone lock (BL-021).
///
/// **Promoted out of `features/identityscan/scan_blocked_view.dart` at S8-14.** BL-021 and
/// BL-114(b) are the same customer situation — the customer is at a block and is being told
/// something — and product-owner decision 2026-09-11 is that they get ONE honest treatment rather
/// than two that can drift apart.
///
/// ## What this widget may and may not say
///
/// `blockedUntil` is not a trustworthy future instant, and this is where that is absorbed. The old
/// version collapsed "no deadline" and "deadline already passed" into a single «يمكنك المحاولة
/// مرة أخرى الآن» — *you can try again now* — with a working-looking retry button. For a profile
/// that has spent its lifetime token cap that sentence is false and stays false forever, and the
/// button can never succeed (BL-114(b)).
///
/// The fix deliberately does **not** try to detect the cap. It cannot *from the deadline*: a past
/// deadline is produced by at least four non-cap server paths and by this widget's own countdown
/// reaching zero, so any "you have reached your limit" copy keyed on it would fire on ordinary
/// blocks (`@agent-reviewer`, pre-build audit 2026-09-11 — this was the second analysis-level
/// refutation in two sessions, and the copy below is what replaced the design it sank).
///
/// **AMENDED S8-15 (2026-09-12): a cap discriminator NOW EXISTS ON THE WIRE, and this widget still
/// does not read it.** BL-118's backend half ships a nullable `blockReason` alongside
/// `blockedUntil` on the unchanged `SCAN_BLOCKED`/`LIVENESS_BLOCKED` codes, so "it cannot detect
/// the cap" is no longer true in general — only from the deadline, as above. **BL-118's mobile half
/// was then CUT from V1 by product-owner ruling:** this screen is already honest without it, and
/// naming the cap is an improvement rather than a fix. Two things to carry if that is revisited —
/// an ABSENT `blockReason` means "the arm that refused does not know", never "not capped", because
/// six server paths report a block they did not apply; and ruling A still forbids naming manual
/// completion or promising a remedy. Everything below stands: it is why this widget describes what
/// it OBSERVED rather than why.
///
/// Instead the copy is built on what the app **observed**, never on why:
///
/// 1. **A deadline in the future.** Count it down. No retry control — a button that is present but
///    disabled for 24 hours invites tapping.
/// 2. **A deadline that ran out while the customer watched this screen.** Offer the retry. Nothing
///    has been refused since the wait ended, so "you can try again" is still an honest thing to
///    say — and the retry is exactly what lifts an ordinary block server-side.
/// 3. **A deadline that had ALREADY passed when the server refused.** The server refused *and*
///    its own deadline says the wait is over. That is an observation, not an inference: waiting
///    did not help. Say so, point to a branch, and offer no retry control, because the app has
///    just watched one fail.
/// 4. **No deadline at all.** Its own case since `@agent-reviewer`'s S8-14 review, which caught
///    the first version folding it into case 3 and so telling a customer «انتهت مدة الانتظار» —
///    *the waiting period has ended* — about a wait that was never announced. That is an
///    unobserved claim, and this widget's whole rule is to state only what was observed. The state
///    is real: BL-049 records a `blocked_scan` profile whose `scan_blocked_until` is legitimately
///    null, and the controller then omits the member entirely. So: say the action is unavailable,
///    point to a branch, claim nothing about waiting, and offer no retry.
///
/// Cases 3 and 4 are true under a lifetime cap and under an ordinary block alike, which is the
/// point: they need no discriminator — and that is now a CHOICE rather than a constraint, since
/// S8-15 put one on the wire and V1 declined to use it. Neither claims anything about limits,
/// neither promises an outcome, and neither names manual completion — product-owner ruling A
/// (2026-09-11).
///
/// **The case-2/case-3 split is what makes this honest.** It is recomputed both at [initState]
/// and whenever `blockedUntil` changes ([didUpdateWidget]).
///
/// **What actually breaks the loop, corrected after `@agent-reviewer`'s S8-14 review:** it is the
/// re-created State, not [didUpdateWidget]. A capped profile's re-refusal returns the *same*
/// stored instant untouched, so the `didUpdateWidget` guard does not fire on it. Termination comes
/// from the callers: `stage8_screen`'s `_startScan` and `stage10_screen`'s `_begin` set their view
/// to `working` before re-requesting, which disposes this State and re-runs [initState] against
/// the new refusal; Stage 9 and `BlockedScreen` navigate away entirely. [didUpdateWidget] is
/// therefore defence in depth rather than the mechanism — **but a future caller that swaps the
/// deadline in place with no intervening working state would rely on it**, which is why it stays.
///
/// **No attempt count is shown, in any case.** `attemptsRemaining` is deliberately not on the
/// wire, and counting locally is the client-side-counter anti-pattern the server-side budget
/// exists to prevent (AD-002a, R-052).
class BlockedView extends StatefulWidget {
  const BlockedView({
    super.key,
    required this.blockedUntil,
    required this.onRecheck,
    this.onLeave,
    this.title = 'مسح الوثيقة غير متاح مؤقتًا',
    this.showProgressPreserved = true,
  });

  /// The heading. Defaults to Stage 8's; every other surface passes its own.
  final String title;

  /// When the block lifts. Null, past and future are all expected — see the class comment.
  final DateTime? blockedUntil;

  /// Invoked when the deadline ran out while this screen was open and the customer chooses to try
  /// again. **Only ever reachable in case 2** — the retry itself is what clears an ordinary block
  /// server-side.
  final VoidCallback onRecheck;

  /// Optional secondary action — a way back out of the journey. Every surface should pass one;
  /// Stage 10 shipped without it, which is BL-109.
  final VoidCallback? onLeave;

  /// Whether to reassure the customer that entered data survives the block. True everywhere in the
  /// journey; false at Stage 0/1a, where a customer who has not started has nothing to preserve
  /// and the sentence would be noise.
  final bool showProgressPreserved;

  @override
  State<BlockedView> createState() => _BlockedViewState();
}

class _BlockedViewState extends State<BlockedView> {
  Timer? _ticker;

  /// True when the deadline was already spent (or absent) at the moment this block was received —
  /// case 3. False when a live countdown was running and later reached zero — case 2.
  late bool _elapsedOnArrival;

  @override
  void initState() {
    super.initState();
    _applyDeadline();
  }

  @override
  void didUpdateWidget(BlockedView oldWidget) {
    super.didUpdateWidget(oldWidget);
    // A new refusal carries a new deadline. Without this the retry in case 2 would rebuild the
    // same State, keep `_elapsedOnArrival == false`, and offer the retry again — the loop
    // BL-114(b) exists to break.
    if (oldWidget.blockedUntil != widget.blockedUntil) {
      _ticker?.cancel();
      _applyDeadline();
    }
  }

  void _applyDeadline() {
    _elapsedOnArrival = _remaining() == null;
    // Only tick when there is something to tick towards.
    if (!_elapsedOnArrival) {
      _ticker = Timer.periodic(const Duration(seconds: 1), _tick);
    }
  }

  @override
  void dispose() {
    _ticker?.cancel();
    super.dispose();
  }

  void _tick(Timer timer) {
    if (!mounted) {
      timer.cancel();
      return;
    }
    if (_remaining() == null) {
      // Reached zero — stop ticking and let the build below switch to case 2.
      timer.cancel();
    }
    setState(() {});
  }

  /// Time left, or null when there is nothing left to wait for. Never returns a negative duration.
  Duration? _remaining() {
    final until = widget.blockedUntil;
    if (until == null) return null;
    final remaining = until.difference(DateTime.now());
    return remaining.isNegative || remaining == Duration.zero ? null : remaining;
  }

  /// Rounds UP, matching `ChannelVerificationScreen._goToBlockedTerminal`'s reasoning: truncating a
  /// server-computed "now + 24 hours" reads as 23 the instant any network latency elapses.
  static String _phrase(Duration remaining) {
    if (remaining.inMinutes >= 60) {
      final hours = (remaining.inMinutes + 59) ~/ 60;
      return ArabicNounAgreement.hours.phrase(hours);
    }
    final minutes = (remaining.inSeconds + 59) ~/ 60;
    return ArabicNounAgreement.minutes.phrase(minutes);
  }

  @override
  Widget build(BuildContext context) {
    final remaining = _remaining();
    // Case 4: no deadline was ever given. Kept apart from case 3 because "the wait is over" is a
    // claim about a wait that was never announced.
    final noDeadline = widget.blockedUntil == null;
    // Case 3: refused with the wait already over. The app has observed that waiting did not help,
    // so it neither promises a retry nor offers a control that has just failed.
    final refusedAfterWaiting = remaining == null && _elapsedOnArrival && !noDeadline;

    final String body;
    if (remaining != null) {
      body = 'يمكنك المحاولة مرة أخرى بعد ${_phrase(remaining)}، أو زيارة أقرب فرع.';
    } else if (noDeadline) {
      body = 'هذا الإجراء غير متاح حاليًا. يرجى زيارة أقرب فرع.';
    } else if (refusedAfterWaiting) {
      body = 'انتهت مدة الانتظار، ومع ذلك لا يزال هذا الإجراء غير متاح. يرجى زيارة أقرب فرع.';
    } else {
      body = 'يمكنك المحاولة مرة أخرى الآن، أو زيارة أقرب فرع.';
    }

    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            widget.title,
            style: Theme.of(context).textTheme.titleMedium,
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 12),
          Text(body, textAlign: TextAlign.center),
          if (widget.showProgressPreserved) ...[
            const SizedBox(height: 12),
            // customer.md Stage 8: "The block applies to this stage only. Everything already
            // entered and verified stays intact and resumable." Saying so is the difference
            // between a pause and an apparent loss of the whole session. True in all three cases.
            const Text(
              'كل ما أدخلته وتم التحقق منه محفوظ، وستتابع من هذه الخطوة عند عودتك.',
              textAlign: TextAlign.center,
            ),
          ],
          const SizedBox(height: 24),
          // Offered ONLY in case 2 — the wait ended while the customer was here and nothing has
          // been refused since. Never in case 3, where the app has just watched a retry fail.
          if (remaining == null && !refusedAfterWaiting && !noDeadline)
            FilledButton(
              onPressed: widget.onRecheck,
              child: const Text('إعادة المحاولة'),
            ),
          if (remaining == null && !refusedAfterWaiting && !noDeadline && widget.onLeave != null)
            const SizedBox(height: 12),
          if (widget.onLeave != null)
            OutlinedButton(
              onPressed: widget.onLeave,
              child: const Text('العودة إلى البداية'),
            ),
        ],
      ),
    );
  }
}
