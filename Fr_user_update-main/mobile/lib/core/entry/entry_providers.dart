import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../database/database_providers.dart';
import '../database/reference_database.dart';
import '../network/dio_provider.dart';

import 'dio_entry_api.dart';
import 'entry_api.dart';
import 'entry_models.dart';
import 'entry_repository.dart';

final entryApiProvider = Provider<EntryApi>((ref) {
  return DioEntryApi(ref.watch(dioProvider));
});

final entryRepositoryProvider = Provider<EntryRepository>((ref) {
  return EntryRepository(api: ref.watch(entryApiProvider), db: ref.watch(sessionDatabaseProvider));
});

/// Syncs the reference catalogue and activates the `branch` list — Stage 1a's picker source.
/// Mirrors `demoInitProvider`'s own sync-then-activate sequence, scoped to `branch` only; the
/// `occupation` list stays the demo screen's own concern, untouched by this provider.




/// Stage 0's launch check, run once per app start (mirrors `demoInitProvider`'s
/// run-once-on-watch shape). `LaunchScreen` watches this and navigates once it resolves.
final launchDecisionProvider = FutureProvider<LaunchDecision>((ref) async {
  final repository = ref.watch(entryRepositoryProvider);
  return repository.launchDecision();
});
