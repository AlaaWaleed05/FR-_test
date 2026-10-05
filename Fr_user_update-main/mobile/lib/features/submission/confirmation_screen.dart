import 'dart:async' show unawaited;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/entry/channel_labels.dart';
import '../../core/entry/entry_providers.dart';
import '../../core/journey/journey_providers.dart';
import '../../core/text/ltr_value.dart';
import '../../core/widgets/brand_banner.dart';

/// What the confirmation screen is shown.
///
/// Carried through navigation rather than re-read on arrival, because by the time this screen has
/// finished its work the local `profileId` is gone — clearing it is this screen's own last step.
class ConfirmationArgs {
  const ConfirmationArgs({
    required this.referenceNumber,
    required this.verifiedChannels,
  });

  final String referenceNumber;

  /// Channel wire values, sourced from `POST /api/v1/submission/current` on every path. Empty only
  /// when that read failed, which the screen says plainly rather than hiding.
  final List<String> verifiedChannels;
}

/// customer.md Stage 12's confirmation screen.
///
/// **The wording is constrained by the spec, not by taste.** "The update is complete and submitted
/// to the bank for review and approval. Not 'approved', not 'done' — the customer must not leave
/// believing the matter is closed."
///
/// **Clearing local state is this screen's job, and it happens after the first frame.** customer.md
/// Stage 12 step 5: only once the backend has confirmed does the app clear local storage — and it
/// is a privacy requirement on a shared device, not storage hygiene, so it is not deferred until
/// the customer taps something. It runs here rather than in `Stage12Screen` because everything this
/// screen displays must already be in hand first: the clear destroys the `profileId` that
/// `/submission/current` needs, and the reference number is the customer's only artifact afterwards.
///
/// **The one case where it does NOT clear**: [ConfirmationArgs.verifiedChannels] empty, meaning the
/// pointer read failed. Then the submission succeeded but the screen is incomplete, and keeping
/// local state is what lets a resume finish the job — the pointer will answer `SUBMITTED` and this
/// screen will be reached again with the channels filled in.
class ConfirmationScreen extends ConsumerStatefulWidget {
  const ConfirmationScreen({super.key, required this.args});

  final ConfirmationArgs args;

  @override
  ConsumerState<ConfirmationScreen> createState() => _ConfirmationScreenState();
}

class _ConfirmationScreenState extends ConsumerState<ConfirmationScreen> {
  late List<String> _channels = widget.args.verifiedChannels;
  bool _retrying = false;

  @override
  void initState() {
    super.initState();
    if (_channels.isNotEmpty) {
      unawaited(_clearLocalState());
    }
  }

  Future<void> _clearLocalState() async {
    try {
      await ref.read(entryRepositoryProvider).clearAfterSubmission();
    } on Object {
      // Best-effort, and deliberately silent: the submission is complete and the customer is being
      // shown their reference number. Failing to clear is a privacy problem to fix, not a reason to
      // replace a success screen with an error the customer can do nothing about. A relaunch
      // re-runs the launch check, which lands on the terminal path and clears again.
    }
  }

  /// Retry for the channel list alone — the submission itself is already done.
  Future<void> _retryChannels() async {
    setState(() => _retrying = true);
    try {
      final pointer = await ref
          .read(journeyRepositoryProvider)
          .currentPointer();
      if (!mounted) return;
      setState(() {
        _channels = pointer.verifiedChannels;
        _retrying = false;
      });
      if (_channels.isNotEmpty) await _clearLocalState();
    } on Object {
      if (!mounted) return;
      setState(() => _retrying = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: BrandBanner(
        title: const Text('تم الإرسال'),
        automaticallyImplyLeading: false,
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Icon(Icons.check_circle_outline, size: 64),
              const SizedBox(height: 16),
              Text(
                'تم إرسال طلبك إلى البنك للمراجعة والاعتماد',
                style: Theme.of(context).textTheme.titleMedium,
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 8),
              // Not "approved" and not "done" — customer.md is explicit that the customer must not
              // leave believing the matter is closed.
              const Text(
                'لم يتم اعتماد التحديث بعد. سيتم إشعارك بالنتيجة.',
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 24),
              Text(
                'الرقم المرجعي',
                style: Theme.of(context).textTheme.titleSmall,
              ),
              const SizedBox(height: 4),
              // Prominent, because "it is the customer's only artifact once local storage clears,
              // and it is what they quote at a branch if the profile is later rejected".
              //
              // `FRU-000000001` OPENS with a Latin run, so inheriting the page's RTL direction can
              // reorder it — the customer would then read out a reference number that does not
              // match the one on file. `LtrValue.selectable` gives it its own bidi paragraph.
              // It deliberately does NOT use FSI/PDI: this is selectable so the customer can copy
              // it, and format characters would travel into whatever they paste.
              LtrValue.selectable(
                widget.args.referenceNumber,
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const SizedBox(height: 12),
              // **Walk comment 15b** — "the app should ask the user to save this number".
              // R-019 is why this is not cosmetic: this screen shows the number exactly once,
              // and local state is cleared right after it. Stated fully, because R-019 has two
              // halves and quoting only the first overstates the case — its mitigation records
              // that the number ALSO goes out in the submission notification to every verified
              // channel, so the customer does have a durable copy. What that copy is not is
              // guaranteed to be in front of them at a branch counter, which is what this
              // sentence is for. The screen copy says «مرجعك الوحيد» — their only REFERENCE, not
              // their only copy — and that distinction is deliberate. One sentence, no journey rule
              // touched, nothing new stored or sent.
              //
              // Deliberately says WHY rather than just "save this": the reason is what makes a
              // customer act on it, and «عند مراجعة الفرع» is the concrete occasion they will
              // need it — the same one R-019 names.
              Text(
                'احتفظ بهذا الرقم، فهو مرجعك الوحيد عند مراجعة الفرع للاستفسار عن طلبك.',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: 24),
              _channelsSection(),
              const SizedBox(height: 32),
              FilledButton(
                // W-10 / walk comment 15c: this used to `go('/')`, landing the customer back on
                // the splash as though they were starting over. It now reaches a real end state.
                onPressed: () => context.go('/session-complete'),
                child: const Text('إنهاء'),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _channelsSection() {
    if (_channels.isEmpty) {
      // Honest degrade. The alternative — showing nothing, or worse, inferring a list — is what
      // BL-058 exists to prevent.
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text(
            'تعذر عرض قنوات التواصل التي سيصلك عليها القرار. سيتم إشعارك على القنوات التي تم '
            'التحقق منها.',
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 8),
          if (_retrying)
            const Center(child: CircularProgressIndicator())
          else
            TextButton(
              onPressed: () => unawaited(_retryChannels()),
              child: const Text('إعادة المحاولة'),
            ),
        ],
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text('سيصلك القرار عبر', style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 4),
        Text(
          _channels.map(channelLabel).join('، '),
          textAlign: TextAlign.center,
        ),
      ],
    );
  }
}
