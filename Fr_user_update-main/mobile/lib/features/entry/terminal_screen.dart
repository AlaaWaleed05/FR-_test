import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import '../../core/widgets/brand_banner.dart';

/// Journey Stage 1a's terminal outcomes (docs/journeys/customer.md) — an inactive account, or an
/// account whose profile is already complete. "no re-entry, no supersede path" applies to the
/// SAME account; the "back to start" action below simply returns to a fresh Stage 1a, useful for
/// checking a different account on the same device.
class TerminalScreen extends StatelessWidget {
  const TerminalScreen({super.key, required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      // AD-012: the mark-only banner. No title — this screen is one centred
      // heading, and a titled bar would print it twice.
      appBar: const BrandBanner.markOnly(),
      // Walk comment 9 (walk of 2026-09-10). The content is centred, so it was never actually
      // under the navigation bar — but a short viewport plus a large text scale can push it down
      // into that strip.
      body: SafeArea(
        top: false,
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(message, textAlign: TextAlign.center, style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 24),
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
