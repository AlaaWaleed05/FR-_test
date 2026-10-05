import 'dart:async' show unawaited;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/dataentry/data_entry_providers.dart';
import '../../core/database/database_providers.dart';
import '../../core/entry/channel_labels.dart';
import '../../core/entry/entry_models.dart';
import '../../core/entry/entry_providers.dart';
import 'offline_banner.dart';
import '../../core/widgets/brand_banner.dart';
import '../../core/widgets/stage_action_bar.dart';

/// **The honest placeholder for "the journey has no more screens yet" — and as of S5-08 there is no
/// such gap left, so nothing routes here.** While it was live it showed what got verified and
/// offered the one action the build could provide: abandon (docs/journeys/customer.md Stage 13 — "the
/// customer must be able to abandon an in-progress session and return the app to a fresh start").
///
/// **Repurposed three times.** Before Stage 2 existed (pre-S5-04), this screen sat right after
/// Stage 1b and listed *unverified* channels — that role became `ChannelVerificationScreen`'s.
/// Before stages 3-6 existed (pre-S5-05), it sat right after Stage 2, reached from
/// `resumeStage == 'verified'`. Before stages 7-9 existed (pre-S5-07) it sat after Stage 6,
/// reached from `'beyondStage6'`. It sat after Stage 9's acceptance until S5-08 built stages
/// 10-12; `resumeStageBeyondStage9` now routes to `FinalStagesGateScreen` instead, so **this
/// screen is currently UNREACHABLE** — retained for the same reason `ResumeVerified` is, since
/// each new "past everything" point has repurposed exactly this shape. Its own content (verified
/// channels, abandon action) and self-loading pattern (re-read from `LocalProgress`, never trust
/// nav `extra`, per `LaunchScreen`'s own doctrine) are otherwise unchanged — same screen, new
/// trigger condition.
///
/// Also the natural landing point for the offline-queue "headline proof" (S5-05): a customer who
/// completed all four data-entry stages fully offline reaches this screen on reconnect, and its
/// `_load()` opportunistically flushes any still-queued stage.
class SessionPendingScreen extends ConsumerStatefulWidget {
  const SessionPendingScreen({super.key, this.offline = false});

  final bool offline;

  @override
  ConsumerState<SessionPendingScreen> createState() => _SessionPendingScreenState();
}

class _SessionPendingScreenState extends ConsumerState<SessionPendingScreen> {
  List<ChannelSummary>? _channels;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    // Opportunistic flush — the headline reconnection point for the offline-queue proof (S5-05):
    // a customer who completed stages 3-6 fully offline lands here on reconnect.
    unawaited(ref.read(dataEntryRepositoryProvider).flushPending());

    final db = ref.read(sessionDatabaseProvider);
    final row = await (db.select(db.localProgress)..where((t) => t.id.equals(0))).getSingleOrNull();
    if (!mounted) return;
    // Reached only once Stage 2 has advanced `resumeStage` to `'verified'` — every channel in
    // the cache at that point is either `verified` or was never proven (see
    // `ChannelVerificationScreen`'s own doc comment on the disclosed resume-staleness gap for
    // `locked`); this screen only ever lists the former, per customer.md Stage 2 "What is
    // recorded" — unproven channels are never saved to the profile, so there's nothing useful to
    // show about them here.
    final all = ChannelSummary.decodeList(row?.channelsSummary);
    setState(() => _channels = all.where((c) => c.state == ChannelState.verified).toList());
  }

  Future<void> _abandon() async {
    await ref.read(entryRepositoryProvider).abandon();
    if (!mounted) return;
    context.go('/account-entry');
  }

  @override
  Widget build(BuildContext context) {
    final channels = _channels;
    return Scaffold(
      appBar: BrandBanner(title: const Text('تم حفظ البيانات')),
      body: Column(
        children: [
          if (widget.offline) const OfflineBanner(),
          Expanded(
            child: channels == null
                ? const Center(child: CircularProgressIndicator())
                : Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        const Text('تم التحقق من:'),
                        const SizedBox(height: 8),
                        for (final channel in channels)
                          Text('${channelLabel(channel.channel)}: ${channel.maskedDestination}'),
                        const SizedBox(height: 24),
                        const Text('تم حفظ بياناتك وتأكيد بيانات هويتك. باقي خطوات التحديث '
                            'ستتوفر في إصدار لاحق من التطبيق.'),
                      ],
                    ),
                  ),
          ),
          StageActionBar(
            child: OutlinedButton(onPressed: _abandon, child: const Text('التراجع عن الجلسة')),
          ),
        ],
      ),
    );
  }
}
