/// Stages 13's approved and rejected outcomes — BL-106.
///
/// **What was wrong.** `FinalStagesGateScreen` mapped `JourneyStage.submitted`, `.approved` and
/// `.rejected` to the SAME confirmation screen, which says «تم إرسال طلبك إلى البنك للمراجعة
/// والاعتماد» and «لم يتم اعتماد التحديث بعد. سيتم إشعارك بالنتيجة.» — *your request has been sent
/// for review; you will be notified of the result*. Told to a customer the bank has already
/// REJECTED, that is false twice over: the matter is not pending, and no further notification is
/// coming. The back office renders «مرفوض» correctly for the same profile, so the two tiers
/// disagreed about one fact.
///
/// **Why this needs no wire change.** `POST /api/v1/submission/current` already answers
/// `JourneyStage.submitted` / `.approved` / `.rejected` distinctly and carries the reference
/// number with it (`SubmissionService.currentJourneyPointer:132-139`). The status was always on
/// this wire; the gate simply collapsed three values into one destination.
///
/// **This does NOT re-open BL-119.** That ruling is about Stage 1a's account check, which is
/// `permitAll()` and must not publish a bank adjudication about a named account. This endpoint is
/// reached only with a `profileId` already held in local storage, which is a different surface
/// with a different exposure. A customer who has lost local state still gets `EndedScreen`, which
/// claims nothing.
///
/// **Local state IS cleared here, and the first draft of this file got that wrong** (caught by
/// `@agent-reviewer`, S8-16). The reasoning for not clearing was that it would cost the app its
/// ability to keep telling this customer the truth on relaunch. Two things refute it. First,
/// customer.md l.1137-1138 makes the clear a **privacy requirement on a shared device**, not
/// storage hygiene — and local state here includes the data-entry draft (name, address, phone,
/// email) and the salary-certificate file on plain disk. Second, the destination these screens
/// REPLACED already performed it: `/confirmation` clears whenever `verifiedChannels` is non-empty
/// (`confirmation_screen.dart:62-64`), and `currentJourneyPointer` always populates that list on
/// the approved/rejected path. So "not clearing" was not a deliberate deviation — it was a silent
/// regression of behaviour that already shipped.
///
/// **Where these screens are actually reached, stated precisely** (narrowed after
/// `@agent-reviewer`'s second pass, which caught this comment overclaiming). NOT on relaunch:
/// `EntryRepository.launchDecision` calls `checkAccount` FIRST, and `AccountCheckService`
/// overrides the continuation to `TERMINAL` for every terminal status, so a relaunching customer
/// gets `LaunchEnded` → `EndedScreen` and `/submission/current` is never consulted. These two
/// screens are reached only from an IN-SESSION navigation into `/final-stages` — the customer is
/// sitting in stages 10-12 and the profile becomes approved or rejected underneath them, which is
/// what an operator's manual completion and approval does.
///
/// That window is narrow, and it is the whole of what BL-106's in-app consequence can be without
/// a wire change. The other half of BL-106 — the relaunching customer being told their update
/// "was already completed" — is fixed by `EndedScreen` (BL-123), which claims nothing and sends
/// them to a branch, where a rejected customer is going anyway. The relaunch courtesy itself was
/// the accepted cost of [[BL-119]]'s closure.
library;


import 'dart:async' show unawaited;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/entry/entry_providers.dart';
import '../../core/text/ltr_value.dart';
import '../../core/widgets/brand_banner.dart';

/// The bank approved the update. The one screen in this app that may say so.
class ApprovedScreen extends StatelessWidget {
  const ApprovedScreen({super.key, required this.referenceNumber});

  final String referenceNumber;

  @override
  Widget build(BuildContext context) {
    return _OutcomeLayout(
      icon: Icons.check_circle_outline,
      // The one place a success tick is honest — the backend has actually said `approved`.
      tone: _OutcomeTone.good,
      title: 'تم اعتماد تحديث بياناتك',
      body: 'تم تحديث بياناتك لدى البنك. لا حاجة لأي إجراء آخر.',
      referenceNumber: referenceNumber,
    );
  }
}

/// The bank did not approve the update.
///
/// **This screen ships without a reason, and that is a deliberate gap, not an oversight.** The
/// seven rejection reasons (REJ-01..07) and their customer-facing Arabic copy are [[BL-005]] and
/// are UNAPPROVED — they were translated for a working session, never sourced from the bank, and
/// they are compliance-sensitive customer communication. Inventing copy here, or shipping a
/// plausible-looking placeholder, would put unreviewed text in front of a customer being told
/// their bank refused something. So the screen states the outcome, gives the reference number to
/// quote, and sends them to a branch.
///
/// **What this screen needs before it can name a reason**, when the wording is approved:
/// 1. the approved Arabic label for each of REJ-01..07 (BL-005);
/// 2. a field carrying the code on the resume read — `JourneyPointer` has no reason member today,
///    so `/submission/current` would have to add one;
/// 3. a product decision on whether the customer sees the operator's INTERNAL reason label or the
///    customer-facing SMS text (`ref.reference_item.extra.customerMessageAr`), which are
///    different strings written for different readers.
///
/// Until then the coded reason reaches the customer by SMS only, which is where it reaches them
/// today.
class RejectedScreen extends StatelessWidget {
  const RejectedScreen({super.key, required this.referenceNumber});

  final String referenceNumber;

  @override
  Widget build(BuildContext context) {
    return _OutcomeLayout(
      // Not an error cross. The bank made a decision; the app did not fail. `info` keeps this a
      // statement of outcome rather than a malfunction the customer might try to retry past.
      icon: Icons.info_outline,
      tone: _OutcomeTone.plain,
      title: 'لم يتم اعتماد تحديث بياناتك',
      // Says a branch visit is how this is taken further, without promising it can be fixed —
      // some rejection reasons are not correctable from the customer's side at all.
      body:
          'لمعرفة السبب أو لتقديم طلب جديد، يرجى زيارة أقرب فرع ومعك الرقم المرجعي أدناه ووثيقة الهوية.',
      referenceNumber: referenceNumber,
    );
  }
}

enum _OutcomeTone { good, plain }

/// Shared layout only — every word above it, never here. The two screens differ in what they
/// CLAIM, which is exactly the thing that must not be shared between them.
class _OutcomeLayout extends ConsumerStatefulWidget {
  const _OutcomeLayout({
    required this.icon,
    required this.tone,
    required this.title,
    required this.body,
    required this.referenceNumber,
  });

  final IconData icon;
  final _OutcomeTone tone;
  final String title;
  final String body;
  final String referenceNumber;

  @override
  ConsumerState<_OutcomeLayout> createState() => _OutcomeLayoutState();
}

class _OutcomeLayoutState extends ConsumerState<_OutcomeLayout> {
  @override
  void initState() {
    super.initState();
    // customer.md l.1137-1138's privacy requirement, discharged after the first frame for the
    // same reason `ConfirmationScreen` does it there: everything this screen displays is already
    // in hand (the reference number came through navigation), so the clear cannot race what the
    // customer is reading.
    unawaited(_clearLocalState());
  }

  /// Best-effort and deliberately silent, mirroring `ConfirmationScreen._clearLocalState`. The
  /// journey is over and the customer is being told its outcome; replacing that with an error
  /// they can do nothing about would be a worse screen. A relaunch re-runs the launch check,
  /// which lands on the terminal path and clears again.
  Future<void> _clearLocalState() async {
    try {
      await ref.read(entryRepositoryProvider).clearAfterSubmission();
    } on Object {
      // See above.
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      // AD-012's mark-only banner: no title (each of these screens is one centred heading) and
      // no back affordance. Terminal — reached by `go`, which replaces the stack.
      appBar: const BrandBanner.markOnly(),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Icon(
                  widget.icon,
                  size: 64,
                  color: widget.tone == _OutcomeTone.good
                      ? theme.colorScheme.primary
                      : theme.colorScheme.onSurfaceVariant,
                ),
                const SizedBox(height: 24),
                Text(
                  widget.title,
                  style: theme.textTheme.titleMedium,
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 12),
                Text(widget.body, textAlign: TextAlign.center),
                const SizedBox(height: 24),
                Text(
                  'الرقم المرجعي',
                  style: theme.textTheme.titleSmall,
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 4),
                // Same treatment and same reason as the confirmation screen: `SFB-000000001`
                // opens with a Latin run, so inheriting the page's RTL direction can reorder it
                // and the customer would quote a number that does not match the one on file.
                // Selectable rather than FSI/PDI-wrapped so a copy carries no format characters.
                LtrValue.selectable(
                  widget.referenceNumber,
                  textAlign: TextAlign.center,
                  style: theme.textTheme.headlineSmall,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
