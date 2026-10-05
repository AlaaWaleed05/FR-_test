import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/widgets/blocked_view.dart';
import '../../core/widgets/screen_title.dart';
import '../../core/widgets/brand_banner.dart';

/// Navigation payload for [BlockedScreen]. A small named type rather than a bare `DateTime` in
/// `extra`, because the second field decides whether the screen makes a promise about the
/// customer's saved data and a positional pair would make that easy to get backwards.
class BlockedArgs {
  const BlockedArgs({required this.blockedUntil, required this.progressPreserved});

  final DateTime? blockedUntil;
  final bool progressPreserved;
}

/// Stage 0 / Stage 1a's phone-lock screen — customer.md Stage 13's "Returning after a temporary
/// block: the backend answers *blocked until X* and the customer sees that, not the stage they
/// were blocked on."
///
/// **BL-021.** Before S8-14 this screen did not exist and the backend's `BLOCKED` answer was not
/// decoded at all, so a phone-locked customer was told «تعذر بدء التطبيق. تأكد من اتصالك
/// بالإنترنت» — the network blamed for a bank block, on a launch that could never succeed by
/// retrying. Stage 1a was worse still: the same value surfaced through a bare `catch` as an
/// *account-number* field error, pointing the customer at the one field that was not the problem.
///
/// It renders [BlockedView], the same widget Stages 8, 9 and 10 use, because product-owner
/// decision 2026-09-11 is that every block in this journey gets ONE honest treatment. That widget
/// owns all three deadline cases; this screen only supplies the heading and the two actions.
class BlockedScreen extends StatelessWidget {
  const BlockedScreen({
    super.key,
    required this.blockedUntil,
    this.progressPreserved = true,
  });

  /// When the lock lifts, as the backend reported it.
  final DateTime? blockedUntil;

  /// Whether the customer has a session whose data survives the block. True on the launch path
  /// (a `LocalProgress` row is what caused the check at all); false from Stage 1a, where this
  /// session has entered nothing yet and the reassurance would be noise.
  final bool progressPreserved;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      // No back affordance: this screen IS the app's current state, not a step within it.
      appBar: BrandBanner(
        title: const ScreenTitle('الدخول غير متاح مؤقتًا'),
        automaticallyImplyLeading: false,
      ),
      body: SafeArea(
        child: BlockedView(
          title: 'تم إيقاف المحاولات مؤقتًا',
          blockedUntil: blockedUntil,
          showProgressPreserved: progressPreserved,
          // Re-running Stage 0 is the whole retry: `/` re-asks the backend, and a lock that has
          // lifted simply decodes as PROCEED and resumes. Offered by `BlockedView` only once the
          // countdown has actually run out in front of the customer.
          onRecheck: () => context.go('/'),
          // Always a way out — BL-109 is what a block screen without one looks like.
          onLeave: () => context.go('/account-entry'),
        ),
      ),
    );
  }
}
