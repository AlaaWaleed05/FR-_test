import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../database/database_providers.dart';
import '../database/reference_database.dart';
import '../network/dio_provider.dart';
import '../network/dio_reference_api.dart';
import '../network/reference_api.dart';
import 'reference_list_codes.dart';
import 'reference_repository.dart';
import 'reference_sync_result.dart';

final referenceApiProvider = Provider<ReferenceApi>((ref) {
  return DioReferenceApi(ref.watch(dioProvider));
});

final referenceRepositoryProvider = Provider<ReferenceRepository>((ref) {
  return ReferenceRepository(
    api: ref.watch(referenceApiProvider),
    db: ref.watch(referenceDatabaseProvider),
  );
});

/// Thrown by [demoInitProvider] when, after a sync attempt, [listCode] has NO active cached
/// version at all — client rule 5's "block stage 3 if there is no prior cache." [lastResult] is
/// the most recent sync outcome for that list when one exists, for diagnostics only; it is NOT
/// what decides whether this throws.
///
/// Deliberately keyed on cache STATE, not sync OUTCOME: checking only `isFailure` on the sync
/// result missed two real paths to the same empty-forever state — `backingOff` is intentionally
/// excluded from `isFailure` (it is pacing, not a defect), and a poisoned list stops appearing in
/// `syncCatalog()`'s results at all once the manifest itself goes back to 304-ing, since a
/// poisoned list no longer forces a full re-fetch on its own. Both leave the cache permanently
/// empty with nothing thrown, under the original `occupationResult.isFailure` check (found under
/// review, 2026-09-01).
class ReferenceSyncFailure implements Exception {
  const ReferenceSyncFailure(this.listCode, {this.lastResult});

  final String listCode;
  final ListSyncResult? lastResult;

  @override
  String toString() => lastResult != null
      ? 'reference list "$listCode" has no cached version (last attempt: '
            '${lastResult!.outcome.name})'
      : 'reference list "$listCode" has no cached version';
}

/// Runs once per app start: syncs the catalogue, then activates and pins the occupation list —
/// the manual stand-in for the real stage-2→stage-3 session-boundary hook (see
/// `ReferenceRepository.activateStagedVersion`'s doc comment).
///
/// **No production caller since 2026-09-07.** Its only consumer was S5-01's `/reference-demo`
/// proof screen, deleted with the route (D8.3); the provider is now exercised by
/// `reference_providers_test.dart` alone. Kept rather than deleted because it is the one worked
/// example of the activate-then-pin sequence that the real session-boundary hook has to perform,
/// and re-deriving that from the repository API is harder than reading it here. **Do not treat it
/// as live wiring** — if the real hook lands, this should go with it.
final demoInitProvider = FutureProvider<void>((ref) async {
  final repository = ref.watch(referenceRepositoryProvider);
  final sessionDb = ref.watch(sessionDatabaseProvider);
  final result = await repository.syncCatalog();
  await repository.activateStagedVersion(ReferenceListCodes.occupation);
  await repository.pinCurrentSessionVersion(ReferenceListCodes.occupation, sessionDb);

  if (!await repository.hasActiveVersion(ReferenceListCodes.occupation)) {
    throw ReferenceSyncFailure(
      ReferenceListCodes.occupation,
      lastResult: result.forList(ReferenceListCodes.occupation),
    );
  }
});

final occupationItemsProvider = StreamProvider<List<ReferenceItem>>((ref) {
  final repository = ref.watch(referenceRepositoryProvider);
  return repository.watchActiveItems(ReferenceListCodes.occupation);
});

/// Stages 3/5/6 (country of residence, birth/home/work country) — the same non-hierarchical list,
/// watched unfiltered like [occupationItemsProvider].
final countryItemsProvider = StreamProvider<List<ReferenceItem>>((ref) {
  final repository = ref.watch(referenceRepositoryProvider);
  return repository.watchActiveItems(ReferenceListCodes.country);
});

/// Every `admin_division` row (root + states + localities) for the currently active version —
/// callers filter by `parentCode` themselves (151 rows total, small enough that client-side
/// filtering after one watch is simpler than a second provider per cascade level).
final adminDivisionItemsProvider = StreamProvider<List<ReferenceItem>>((ref) {
  final repository = ref.watch(referenceRepositoryProvider);
  return repository.watchActiveItems(ReferenceListCodes.adminDivision);
});

/// Stage 4's income-source multi-select (9 items).
final incomeSourceItemsProvider = StreamProvider<List<ReferenceItem>>((ref) {
  final repository = ref.watch(referenceRepositoryProvider);
  return repository.watchActiveItems(ReferenceListCodes.incomeSource);
});

/// Stage 3's education-level field (7 items, ordinal — rendered inline, no search picker).
final educationLevelItemsProvider = StreamProvider<List<ReferenceItem>>((ref) {
  final repository = ref.watch(referenceRepositoryProvider);
  return repository.watchActiveItems(ReferenceListCodes.educationLevel);
});
