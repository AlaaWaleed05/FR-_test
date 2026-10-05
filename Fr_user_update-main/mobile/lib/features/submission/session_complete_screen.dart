import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/widgets/brand_banner.dart';

/// The end of the journey. Walk comment 15c, a product-owner decision taken 2026-09-07 and
/// recorded on [BL-091]: «إنهاء» used to call `context.go('/')`, which put the customer back on
/// the splash — a launch screen reads as "start again", which is the opposite of what finishing
/// should feel like, and the customer had already had their reference number cleared from local
/// state by then.
///
/// **What this screen must not say.** customer.md requires the app describe the request as
/// SUBMITTED FOR REVIEW and never as approved or done, and a screen headed "you have finished" is
/// exactly where that distinction gets lost. So the completion here is about the SESSION ending,
/// and the sentence underneath still describes the request as being under review. Nothing on this
/// screen claims an outcome the bank has not reached.
///
/// No data is shown. The reference number lives on the confirmation screen before this one; local
/// state is already cleared by the time the customer arrives, so there is nothing honest to show.
class SessionCompleteScreen extends StatelessWidget {
  const SessionCompleteScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      // AD-012's mark-only banner: no title (this screen is one centred heading) and no back
      // affordance. This is terminal — the customer arrives by `go`, which replaces the stack,
      // so there is nothing behind it to return to.
      appBar: const BrandBanner.markOnly(),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Icon(
                Icons.check_circle_outline,
                size: 72,
                color: theme.colorScheme.primary,
              ),
              const SizedBox(height: 24),
              Text(
                'انتهت الجلسة',
                textAlign: TextAlign.center,
                style: theme.textTheme.headlineSmall,
              ),
              const SizedBox(height: 12),
              Text(
                'تم إرسال طلبك للمراجعة. سنتواصل معك عبر قنوات الاتصال التي اخترتها.',
                textAlign: TextAlign.center,
                style: theme.textTheme.bodyLarge,
              ),
              const SizedBox(height: 40),
              FilledButton(
                // The product owner asked for the button to close the app rather than leave the
                // customer somewhere. `SystemNavigator.pop` is the framework's own way to do that
                // and needs no package.
                //
                // Android only in practice, which is correct for this pilot: Apple's guidelines
                // treat an app terminating itself as a defect, so if iOS is ever in scope this
                // must become a different affordance rather than the same call.
                onPressed: () => SystemNavigator.pop(),
                child: const Text('إغلاق التطبيق'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
