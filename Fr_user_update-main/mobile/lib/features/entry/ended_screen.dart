import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import '../../core/widgets/brand_banner.dart';

/// The one screen shown when this account's update has already ended — BL-123.
///
/// **This screen owns its copy, and that is the whole point of it.** Before S8-16 the sentence
/// «تم استكمال تحديث بيانات هذا الحساب من قبل.» ("this account's data update was already
/// completed") was a string literal hard-coded at **14 separate call sites**, every one of them
/// passing it into `/terminal`'s generic `message` parameter. It was false at three of the four
/// statuses that reach it, and being a literal at the callers is *why* it survived four sessions:
/// each site had to be found and reasoned about separately, so fixing twelve and missing two
/// looked exactly like fixing all fourteen.
///
/// **The choke point is this route, not an exception class.** BL-123 records the mobile tier as
/// having "one Dart class" behind the fourteen sites. Verified at source at S8-16, it does not:
/// there are THREE mechanisms — [ProfileAlreadyCompleteException] (stages 3-8 and the channels
/// screen), `ScanConflictCode.profileTerminal` (stage 9) and `JourneyCode.profileTerminal`
/// (stages 10-11) — because the three feature areas have separate error contracts by design. So
/// there is no single handler to put a screen behind. What all twelve non-entry sites DO share
/// is the navigation: `context.go('/terminal', extra: <literal>)`. Hence a dedicated route with
/// no message parameter. A caller can route here; it cannot say anything on arrival.
///
/// **`/terminal` is deliberately still alive and still takes a String.** It carries messages that
/// are TRUE — the inactive account (from `LaunchScreen` and `AccountEntryScreen`), Stage 2's
/// phone-lock terminal, and Stage 9's Civil-Registry mismatch, which customer.md requires to
/// EXPLAIN — so rewriting it wholesale would have replaced four honest screens to fix twelve
/// dishonest ones.
///
/// **What this may and may not claim.** `app.status_code` marks FOUR statuses terminal —
/// `submitted`, `approved`, `rejected` and `terminated_registry_mismatch` (V0005) — and all four
/// are reachable here. The wire carries no discriminator and deliberately will not: BL-119 was
/// closed as unnecessary by product-owner ruling on 2026-09-12, because publishing a bank
/// adjudication about a named account on `permitAll()` Stage 1a
/// (`SecurityConfiguration.java:182`) trades a security property for a courtesy. So this screen
/// must be true for all four at once. It says the update has ENDED and sends the customer to the
/// bank. It never says completed, never says approved, never says rejected, and never promises
/// a remedy — the same technique `BlockedView` uses (S8-14): describe what was observed, never
/// why.
///
/// **Do not add a status parameter to this screen.** If a discriminator ever arrives it will be
/// behind the customer authentication R-051 tracks, and behind auth is where it belongs.
class EndedScreen extends StatelessWidget {
  const EndedScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      // AD-012's mark-only banner: the brand, with no title (this screen is one centred heading,
      // and a titled bar would print it twice) and NO BACK AFFORDANCE. This is terminal, and the
      // customer arrives by `go`, which replaces the stack — `markOnly` forces
      // `automaticallyImplyLeading: false` so nothing here offers a route back.
      appBar: const BrandBanner.markOnly(),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // Deliberately NOT a success tick and NOT an error cross. Both would claim an
                // outcome. An informational mark is the only honest glyph for "this ended, and
                // this screen is not telling you how".
                Icon(
                  Icons.info_outline,
                  size: 64,
                  color: theme.colorScheme.primary,
                ),
                const SizedBox(height: 24),
                Text(
                  'انتهى تحديث بيانات هذا الحساب',
                  style: theme.textTheme.titleMedium,
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 12),
                // "No longer available from this app" is an observation about this app, not a
                // claim about the bank's decision — which is the distinction the whole screen
                // rests on.
                const Text(
                  'لم يعد بالإمكان متابعة التحديث من التطبيق. لمعرفة حالة طلبك أو لأي استفسار، يرجى زيارة أقرب فرع.',
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 24),
                // Returns to a FRESH Stage 1a, which is useful for checking a different account
                // on a shared device. It is not a retry of this account — customer.md's "no
                // re-entry, no supersede path" applies to the same account, and the backend
                // refuses it again anyway, landing the customer back here.
                OutlinedButton(
                  onPressed: () => context.go('/account-entry'),
                  child: const Text('العودة إلى البداية'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
