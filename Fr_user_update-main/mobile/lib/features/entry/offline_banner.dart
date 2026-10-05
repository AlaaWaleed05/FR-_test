import 'package:flutter/material.dart';

/// Shown up front whenever Stage 0 could not reach the backend to confirm a resume
/// (docs/journeys/customer.md Stage 0: "backend-dependent steps are unavailable ... shown up
/// front rather than as a failure when the customer taps Next"). Deliberately a plain statement,
/// not an error colour scheme — going offline mid-journey is an expected condition, not a fault.
class OfflineBanner extends StatelessWidget {
  const OfflineBanner({super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Text(
        'لا يوجد اتصال بالإنترنت. يتم عرض بياناتك المحفوظة، وبعض الإجراءات غير متاحة حتى يعود الاتصال.',
        style: Theme.of(context).textTheme.bodyMedium,
      ),
    );
  }
}
