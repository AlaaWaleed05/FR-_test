import 'dart:async' show unawaited;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/entry/entry_models.dart' show BackendUnreachableException;
import '../../core/journey/journey_error.dart';
import '../../core/journey/journey_models.dart';
import '../../core/journey/journey_providers.dart';
import '../entry/offline_banner.dart';
import 'confirmation_screen.dart';
import '../../core/widgets/brand_banner.dart';
import '../../core/widgets/journey_progress.dart';

enum _Stage12View { loading, ready, submitting, error }

/// Journey Stage 12 — completion and submission (docs/journeys/customer.md).
///
/// **This is the point of no return.** Submitting moves the profile to `submitted`, which is
/// terminal for the customer: "There is no re-entry, including after a rejection." So what this
/// screen must get right is (a) not offering submit until the backend agrees everything before it
/// is done, and (b) never losing the reference number if something fails partway.
///
/// **What is "confirmed complete" is read, never computed.** This screen enters by asking
/// `POST /api/v1/submission/current` and offers submit only on [JourneyStage.submit], which the
/// backend derives from liveness having passed AND a signature being stored. Nothing here infers
/// readiness from local state — customer.md Stage 13: "The backend alone decides whether the session
/// is still open. Every resume begins by asking."
///
/// **The strict ordering, and why the local clear is last.** customer.md Stage 12 numbers it:
/// submit → backend confirms → *only then* the app clears local storage. This screen adds one step
/// between, and the position is load-bearing:
///
/// 1. `POST /submission` → reference number and status
/// 2. `POST /submission/current` → the verified channels for the confirmation screen
/// 3. render the confirmation screen from values now held in memory
/// 4. **only then** clear local state
///
/// Step 2 needs the `profileId` that step 4 destroys, and after step 4 the reference number is the
/// customer's only artifact. Clearing any earlier would strand a customer who submitted
/// successfully with nothing to quote at a branch.
class Stage12Screen extends ConsumerStatefulWidget {
  const Stage12Screen({super.key, this.offline = false});

  final bool offline;

  @override
  ConsumerState<Stage12Screen> createState() => _Stage12ScreenState();
}

class _Stage12ScreenState extends ConsumerState<Stage12Screen> {
  _Stage12View _view = _Stage12View.loading;
  String? _error;

  @override
  void initState() {
    super.initState();
    unawaited(_check());
  }

  /// The pre-submit read. Also the thing that stops a customer submitting twice: a profile already
  /// `SUBMITTED` goes straight to confirmation rather than being offered a button.
  Future<void> _check() async {
    try {
      final pointer = await ref
          .read(journeyRepositoryProvider)
          .currentPointer();
      if (!mounted) return;
      switch (pointer.stage) {
        case JourneyStage.submit:
          setState(() => _view = _Stage12View.ready);
        case JourneyStage.liveness:
        case JourneyStage.livenessBlocked:
          context.go('/stage-10', extra: widget.offline);
        case JourneyStage.signature:
          context.go('/stage-11', extra: widget.offline);
        case JourneyStage.submitted:
        case JourneyStage.approved:
        case JourneyStage.rejected:
          // The lost-acknowledgement path: the submit landed, the app never saw the answer. The
          // pointer carries both the reference number and the channels, so the customer gets the
          // confirmation screen they were owed.
          _goToConfirmation(pointer.referenceNumber, pointer.verifiedChannels);
      }
    } on BackendUnreachableException {
      if (!mounted) return;
      setState(() {
        _view = _Stage12View.error;
        _error = 'تعذر الاتصال. تأكد من اتصالك بالإنترنت ثم أعد المحاولة.';
      });
    } on Object {
      if (!mounted) return;
      setState(() {
        _view = _Stage12View.error;
        _error = 'حدث خطأ غير متوقع. يرجى المحاولة مرة أخرى.';
      });
    }
  }

  Future<void> _submit() async {
    setState(() {
      _view = _Stage12View.submitting;
      _error = null;
    });
    final journey = ref.read(journeyRepositoryProvider);
    try {
      // Step 1. Terminal from here on.
      final receipt = await journey.submit();

      // Step 2. **The channels come from the pointer read, on EVERY path including this one.**
      //
      // The submit response DOES carry a `verifiedChannels`, and it is deliberately not decoded
      // (see `SubmissionReceipt`). That field means "channels this call enqueued a notification
      // for" and is correctly EMPTY on an idempotent re-submit — so a screen that read it would
      // show a customer who lost their acknowledgement and retried an empty list of channels, which
      // is BL-058. There is no "did the submit response carry channels" branch here on purpose: one
      // source, all three paths, at the cost of one extra call on the happy path.
      List<String> channels = const [];
      try {
        channels = (await journey.currentPointer()).verifiedChannels;
      } on Object {
        // The submission succeeded and the reference number is in hand; only the channel list is
        // missing. Fall through with an empty list — `ConfirmationScreen` says so honestly and
        // offers a retry, and local state is NOT cleared, so a resume can still recover.
      }

      if (!mounted) return;
      // Steps 3 and 4 — in that order, inside ConfirmationScreen.
      _goToConfirmation(receipt.referenceNumber, channels);
    } on JourneyConflictException catch (e) {
      if (!mounted) return;
      _applyConflict(e);
    } on BackendUnreachableException {
      // Nothing is cleared, so this is fully recoverable: the customer retries, and if the submit
      // had actually landed, the idempotent re-call returns the same reference number.
      if (!mounted) return;
      setState(() {
        _view = _Stage12View.ready;
        _error =
            'تعذر الاتصال. لم يتم الإرسال. تأكد من اتصالك بالإنترنت ثم أعد المحاولة.';
      });
    } on Object {
      if (!mounted) return;
      setState(() {
        _view = _Stage12View.ready;
        _error = 'حدث خطأ غير متوقع. يرجى المحاولة مرة أخرى.';
      });
    }
  }

  void _goToConfirmation(String? referenceNumber, List<String> channels) {
    if (referenceNumber == null) {
      // Should not happen: all three complete stages carry one. Recover through the gate rather
      // than showing a confirmation screen with nothing on it.
      context.go('/final-stages', extra: widget.offline);
      return;
    }
    context.go(
      '/confirmation',
      extra: ConfirmationArgs(
        referenceNumber: referenceNumber,
        verifiedChannels: channels,
      ),
    );
  }

  void _applyConflict(JourneyConflictException conflict) {
    switch (conflict.code) {
      case JourneyCode.livenessRequired:
        context.go('/stage-10', extra: widget.offline);
      case JourneyCode.signatureRequired:
        context.go('/stage-11', extra: widget.offline);
      case JourneyCode.profileTerminal:
        // Already finished. The gate re-reads and lands on confirmation with the reference number.
        context.go('/final-stages', extra: widget.offline);
      default:
        context.go('/final-stages', extra: widget.offline);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: BrandBanner(
        title: const Text('إرسال الطلب'),
        automaticallyImplyLeading: false,
      ),
      body: SafeArea(
        child: Column(
          children: [
            const JourneyProgress(step: JourneyStep.confirmation),
            if (widget.offline) const OfflineBanner(),
            Expanded(child: _body()),
          ],
        ),
      ),
    );
  }

  Widget _body() {
    switch (_view) {
      case _Stage12View.loading:
      case _Stage12View.submitting:
        return const Center(child: CircularProgressIndicator());
      case _Stage12View.error:
        return _padded([
          Text(_error ?? '', textAlign: TextAlign.center),
          const SizedBox(height: 24),
          FilledButton(
            onPressed: () {
              setState(() => _view = _Stage12View.loading);
              unawaited(_check());
            },
            child: const Text('إعادة المحاولة'),
          ),
        ]);
      case _Stage12View.ready:
        final theme = Theme.of(context);
        return _padded([
          // D9.3, NARROWED to presentation only (2026-09-07). The screen was sparse — a heading,
          // one sentence and a button — but it is deliberately NOT given a "check your answers"
          // listing of the values entered. The local draft has a queue-and-flush path, so it can
          // hold values the backend never received; showing those as settled fact on the last
          // screen before an irreversible submit would be a correctness hazard, not completeness.
          // Making that listing real needs a backend read endpoint, which is a separate task.
          Icon(
            Icons.check_circle_outline,
            size: 64,
            color: theme.colorScheme.primary,
          ),
          const SizedBox(height: 16),
          Text(
            'اكتملت جميع الخطوات',
            style: theme.textTheme.headlineSmall,
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 12),
          const Text(
            'سيتم إرسال بياناتك إلى البنك للمراجعة والاعتماد.',
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 24),
          _completedSteps(theme),
          const SizedBox(height: 24),
          // The irreversibility warning, kept ABOVE the submit control (an S7-12 pass that must
          // not regress) and now visually separated so it reads as a warning rather than as the
          // tail of an ordinary sentence. The wording is UNCHANGED — this splits the existing
          // approved sentence in two, it does not author new copy.
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: theme.colorScheme.secondaryContainer,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  Icons.info_outline,
                  size: 20,
                  color: theme.colorScheme.onSecondaryContainer,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    'بعد الإرسال لا يمكن التعديل من التطبيق.',
                    style: TextStyle(color: theme.colorScheme.onSecondaryContainer),
                  ),
                ),
              ],
            ),
          ),
          if (_error != null) ...[
            const SizedBox(height: 12),
            Text(
              _error!,
              textAlign: TextAlign.center,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ],
          const SizedBox(height: 24),
          FilledButton(
            onPressed: () => unawaited(_submit()),
            child: const Text('إرسال الطلب'),
          ),
        ]);
    }
  }

  /// BL-087, the presentation-only half. The screen was a heading, a sentence and a button, which
  /// the product owner called meaningless — it did not look like the last checkpoint before an
  /// irreversible action.
  ///
  /// **This lists the STEPS that are complete. It does not list any value the customer entered,
  /// and that distinction is the whole point of the row.** The honest-summary hazard BL-087 names
  /// is that `data_entry_repository.dart` has a queue-and-flush path, so the local draft can hold
  /// values the backend never received; presenting those as settled fact immediately before an
  /// irreversible submit would state something the bank has not actually got. Step names are not
  /// customer data — they are the journey's own structure, and the screen is only reachable when
  /// the backend's readiness check says every stage is complete, so the ticks are the backend's
  /// claim rather than the draft's.
  ///
  /// A real "check your answers" listing still needs a backend read endpoint and remains BL-087's
  /// open half.
  Widget _completedSteps(ThemeData theme) {
    const steps = [
      'بيانات الحساب',
      'قنوات الاتصال',
      'البيانات الشخصية والاجتماعية',
      'إثبات الهوية',
      'التوقيع',
    ];
    // A COMPACT wrap, not a column of list tiles. Five full-height rows filled the screen but
    // pushed the irreversibility warning and the submit control below the fold on a small handset
    // — which on the last checkpoint before an irreversible action is a worse defect than the
    // sparseness it was meant to fix. This says the same thing in two or three lines.
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        // **Each chip is width-capped and its label is `Flexible`.** A `Wrap` hands its children
        // UNBOUNDED width, so a `Row(mainAxisSize: min)` inside one never wraps its text — it just
        // grows past the screen. At the 1.3x text scale S8-03 committed to, «البيانات الشخصية
        // والاجتماعية» overflowed by 177 px on a 360 dp frame. The `Wrap` still wraps between
        // chips; this is what makes a single chip able to wrap WITHIN itself.
        //
        // Pre-existing and unrelated to the progress strip, fixed here because the same test now
        // measures this view at that scale and a known overflow left in place is a test weakened
        // to pass. Found by `@agent-reviewer`.
        child: LayoutBuilder(
          builder: (context, constraints) => Wrap(
            spacing: 12,
            runSpacing: 6,
            children: [
              for (final step in steps)
                ConstrainedBox(
                  constraints: BoxConstraints(maxWidth: constraints.maxWidth),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.check_circle, size: 16, color: theme.colorScheme.primary),
                      const SizedBox(width: 4),
                      Flexible(child: Text(step, style: theme.textTheme.bodySmall)),
                    ],
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  /// **Scrollable, because «إرسال الطلب» must always be reachable.**
  ///
  /// This was a bare `Padding` + `Column` with no scroll view, so anything taller than the
  /// viewport simply overflowed and the submit control went off-screen — not clipped-but-scrollable,
  /// unreachable. On the last checkpoint before an irreversible action that is the worst place in
  /// the journey for it, and `_summary`'s own comment above says as much about an earlier version
  /// of the same defect.
  ///
  /// **Measured, not assumed** (`@agent-reviewer` asked for 360x640 at the 1.3x text scale S8-03
  /// committed to): the ready view overflowed by **228 px** before this session and **287 px**
  /// after the journey progress strip took another 44 dp. So the defect is pre-existing and the
  /// strip made it worse — both true, and the fix belongs here either way. A separate ~177 px
  /// HORIZONTAL overflow at the same scale is fixed in `_summary` above.
  ///
  /// `ConstrainedBox(minHeight: maxHeight)` is what keeps the block centred when there IS room,
  /// so nothing changes on an ordinary handset; the scroll only engages when it must. No
  /// `IntrinsicHeight` is needed — neither caller puts an `Expanded` directly in this column (the
  /// one `Expanded` in this file is inside a `Row`), so the unbounded height is safe.
  Widget _padded(List<Widget> children) {
    return LayoutBuilder(
      builder: (context, constraints) => SingleChildScrollView(
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: constraints.maxHeight),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: children,
            ),
          ),
        ),
      ),
    );
  }
}
