import 'package:flutter/material.dart';

import '../text/ltr_value.dart';
import '../theme/app_theme.dart';

/// Where the customer is in the mandated journey — walk comments 1 and 5 of the 2026-09-10 walk,
/// both of which are the same report: *"progress bar is missing, implement it"*.
///
/// **The design existed before this widget did.** `Design_3/Bayanati Component Library.dc.html`
/// §03 "Stage header & progress" draws it and marks it *"Proposal — not in the app today"*: a
/// stage label above the screen title and a segment strip below it, 3 dp tall with 3 dp gaps,
/// **done = navy, current = steel, remaining = steel-100**. This is that drawing, not a new one.
///
/// **Thirteen segments, not the design's twelve** (product-owner ruling, 2026-09-10). The
/// handoff's caption says "twelve segments covering 1a, 1b, 2 and stages 3-12" — but that list is
/// thirteen screens, and its drawing has twelve bars only because twelve were drawn. The count was
/// put to the product owner rather than guessed; the ruling is one segment per screen the customer
/// actually sees. See [JourneyStep], which is the list.
///
/// **When the strip shows, and when it does not — one rule, because the first pass had two.**
/// The strip renders whenever the customer is ON one of the thirteen screens, INCLUDING while
/// that screen is loading, retrying, or showing a 24-hour block: they are still at that step of
/// the journey, and the counter is about where they are, not about whether the screen is busy.
///
/// The one exception is Stage 1a before its reference lists resolve. That screen can fail to
/// load at all — and «الخطوة ١ من ١٣» drawn over a screen that cannot proceed is a claim about
/// progress that has not been made. Everywhere else the customer has, by definition, already
/// reached the step.
///
/// `@agent-reviewer` found the first pass applying account-entry's reasoning to stages 3-7 and
/// the opposite to stages 8-12, with the justification written only on account entry. Nothing
/// was wrong on screen; the rule just existed in two places and disagreed with itself.
///
/// **On the page, below the bar, not inside it.** `BrandBanner`'s bottom 28 dp is the dune curve,
/// so a strip inside the bar would need the bar to grow and the curve to move — re-opening a
/// layout S8-16 spent a session tuning, on every screen at once. Sitting under the curve keeps
/// the bar untouched and reads as the top of the screen's own content. Also a product-owner
/// ruling, not a shortcut.
class JourneyProgress extends StatelessWidget {
  const JourneyProgress({super.key, required this.step});

  final JourneyStep step;

  /// The handoff's dimensions.
  static const double _barHeight = 3;
  static const double _gap = 3;

  /// The handoff's `--steel-100`, read from `Design_3/tokens/colors.css`.
  static const Color _remaining = Color(0xFFE7ECF2);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final position = step.position;
    final total = JourneyStep.total;

    // The bars are decoration; the counter's `semanticsLabel` below is what a screen reader
    // actually reads, so the strip announces itself as one sentence rather than thirteen boxes.
    //
    // **A note for the next reader, because the first version of this comment got it wrong.**
    // Adding this widget to Stage 4 made that screen assert «Radio groups must not have multiple
    // checked children», and the first explanation blamed a `Semantics(container: true)` wrapper
    // this widget briefly had. That was not the cause, and neither was visibility. `@agent-reviewer`
    // traced the real one: `SemanticsNode._addToUpdate` opens with `assert(_dirty)` and only then
    // runs the role checks, so a node's role is validated ONLY on an update in which that node is
    // dirty. Ticking a checkbox dirties the tile, not the enclosing `RadioGroup` — so Stage 4's
    // malformed tree was never checked. Anything that MOVES the group dirties its geometry and
    // gets it validated: 44 dp of strip, a bare `SizedBox`, or a larger text scale. The defect was
    // always there; this widget only made the framework look. See BL-130.
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 10, 14, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  step.label,
                  style: theme.textTheme.labelLarge?.copyWith(
                    color: AppTheme.brandNavy,
                    fontWeight: FontWeight.w600,
                  ),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              const SizedBox(width: 8),
              // Latin digits, tabular, and direction-isolated — the repo's numeral rule. The
              // counter is Arabic text with two numbers interpolated into one `Text`, so there is
              // no paragraph boundary to isolate them: this is `isolate`'s case, not `LtrValue`'s.
              Text(
                'الخطوة ${LtrValue.isolate('$position')} من ${LtrValue.isolate('$total')}',
                // Without this a screen reader announces the FSI/PDI controls around each number.
                // The spoken form names the step too, so it is a sentence, not two bare digits.
                semanticsLabel: 'الخطوة $position من $total، ${step.label}',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: AppTheme.brandSteel,
                  fontFeatures: const [FontFeature.tabularFigures()],
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          // The bars repeat what the counter already says, so to a screen reader they are thirteen
          // meaningless boxes. `ExcludeSemantics` drops them WITHOUT introducing a container,
          // which is the distinction that matters here.
          ExcludeSemantics(
            child: Row(
              children: [
                for (var i = 1; i <= total; i++) ...[
                  if (i > 1) const SizedBox(width: _gap),
                  Expanded(
                    child: Container(
                      height: _barHeight,
                      color: i < position
                          ? AppTheme.brandNavy
                          : i == position
                              ? AppTheme.brandSteel
                              : _remaining,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// The thirteen screens the customer walks through, in order.
///
/// **Only screens the customer SEES.** `/final-stages` is the handoff's own stated exclusion — it
/// is a routing gate that renders nothing they act on — and the terminal screens (approved,
/// rejected, ended, blocked, session-complete) are outcomes rather than steps, so they carry no
/// strip at all. Stage 8's rescan and Stage 9's review are each one step, not one per attempt:
/// the counter measures the journey, not the customer's luck with a camera.
///
/// The labels are short on purpose — they sit beside a counter on a 360 dp frame, and the screen's
/// own `BrandBanner` title says the same thing at length one line above.
enum JourneyStep {
  accountEntry('بيانات الحساب'),
  contactChannels('قنوات الاتصال'),
  channelVerification('التحقق من القنوات'),
  personalData('البيانات الشخصية'),
  occupation('المهنة والدخل'),
  homeAddress('عنوان السكن'),
  workAddress('عنوان العمل'),
  documentType('نوع الوثيقة'),
  documentScan('مسح الوثيقة'),
  registryReview('مراجعة النتائج'),
  liveness('التحقق الشخصي'),
  signature('التوقيع'),
  confirmation('الإقرار والإرسال');

  const JourneyStep(this.label);

  final String label;

  /// 1-based, because it is shown to a human.
  int get position => index + 1;

  static int get total => values.length;
}
