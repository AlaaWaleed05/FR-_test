import 'package:crypto/crypto.dart';
import 'package:drift/drift.dart';

import '../database/reference_database.dart';
import '../database/session_database.dart';
import '../network/dio_reference_api.dart';
import '../network/manifest_dto.dart';
import '../network/reference_api.dart';
import 'reference_sync_result.dart';
import 'retry_backoff.dart';

/// The reference-data client's testable core (docs/components/reference-data.md). Depends only
/// on the [ReferenceApi] interface and [ReferenceDatabase] — no dio/drift wiring details leak
/// past this class, so it is exercised in tests with a hand-written fake and an in-memory
/// database, no real network.
class ReferenceRepository {
  ReferenceRepository({required ReferenceApi api, required ReferenceDatabase db})
    : _api = api,
      _db = db;

  final ReferenceApi _api;
  final ReferenceDatabase _db;

  /// Client rule 1: fetch the manifest, then fetch only lists whose `(version, contentHash)`
  /// differ from what is already cached. Every list is handled independently for every failure
  /// kind THIS method itself can produce (a bad fetch, a hash mismatch, an unparseable
  /// document) — one list's failure does not stop the others from syncing. The one exception is
  /// a write failure inside `_stageVerifiedList` (a local database error): that is allowed to
  /// propagate and abort the remaining lists in this sync, deliberately — a local write failure
  /// is a more severe class of problem than bad server data, and letting it surface as a real
  /// thrown exception is preferable to silently downgrading it to a per-list outcome.
  ///
  /// The manifest's own `If-None-Match` conditional GET is bypassed whenever a list is still
  /// eligible for retry (a failure that hasn't been poisoned yet) — otherwise a 304 response
  /// would short-circuit the whole sync before any list is even looked at, permanently starving
  /// a failed list of retries as long as the server's catalogue itself never changes (found
  /// live, 2026-09-01: this made poison-after-two-failures unreachable in practice, since the
  /// second attempt would already have been swallowed by the 304 path). `manifest_state` itself
  /// is still written unconditionally after every sync, regardless of any list's outcome — it is
  /// only a change-detection cache for the manifest response itself, decoupled from per-list
  /// success; the bypass above is what actually guarantees a retry happens, not this write.
  Future<ReferenceSyncResult> syncCatalog() async {
    final mustBypassConditionalGet = await _hasRetryEligibleFailure();
    final cachedManifest = mustBypassConditionalGet
        ? null
        : await (_db.select(_db.manifestState)).getSingleOrNull();
    final manifest = await _api.fetchManifest(
      ifNoneMatch: cachedManifest == null ? null : '"${cachedManifest.catalogHash}"',
    );
    if (manifest == null) {
      // 304 Not Modified — nothing changed since our last manifest fetch, and (per the check
      // above) nothing was waiting on a retry either.
      return const ReferenceSyncResult([]);
    }

    final results = <ListSyncResult>[];
    for (final entry in manifest.lists) {
      results.add(await _syncOne(entry));
    }

    await _db
        .into(_db.manifestState)
        .insertOnConflictUpdate(
          ManifestStateCompanion.insert(
            // Explicit id: SQLite's INTEGER PRIMARY KEY autoincrements when NULL is bound
            // explicitly — which is what `Companion.insert()` does for any column left out,
            // even one declared `withDefault(Constant(0))`. Omitting this line silently turns
            // "the one singleton row" into a new row on every sync (found live, 2026-09-01).
            id: const Value(0),
            catalogHash: manifest.catalogHash,
            generatedAt: manifest.generatedAt,
            fetchedAt: DateTime.now(),
          ),
        );

    return ReferenceSyncResult(results);
  }

  Future<ListSyncResult> _syncOne(ManifestListEntryDto entry) async {
    final failureState = await _failureState(entry.listCode, entry.version);
    if (failureState != null) {
      if (failureState.isPoisoned) {
        return ListSyncResult(
          listCode: entry.listCode,
          version: entry.version,
          outcome: ListSyncOutcome.poisoned,
        );
      }
      final elapsedSinceFailure = DateTime.now().difference(
        failureState.lastFailureAt ?? DateTime.now(),
      );
      if (elapsedSinceFailure < backoffFor(failureState.totalFailureCount)) {
        return ListSyncResult(
          listCode: entry.listCode,
          version: entry.version,
          outcome: ListSyncOutcome.backingOff,
        );
      }
    }

    if (await _isCached(entry.listCode, entry.version, entry.contentHash)) {
      return ListSyncResult(
        listCode: entry.listCode,
        version: entry.version,
        outcome: ListSyncOutcome.upToDate,
      );
    }

    if (await _isImmutabilityViolation(entry.listCode, entry.version, entry.contentHash)) {
      // This exact (listCode, version) is already cached under a DIFFERENT hash. Refuse rather
      // than fetch-and-overwrite: AD-002f declares versions immutable, and there is no way to
      // "stage" a same-key conflict the way a genuinely new version stages (found under review,
      // 2026-09-01 — the original code fetched and absorbed this via an upsert instead).
      await _recordFailure(entry.listCode, entry.version, countsTowardPoison: true);
      return ListSyncResult(
        listCode: entry.listCode,
        version: entry.version,
        outcome: ListSyncOutcome.immutabilityViolation,
      );
    }

    final Uint8List bytes;
    try {
      bytes = await _api.fetchListBytes(entry.listCode, entry.version);
    } catch (_) {
      // Transient by nature (dropped connection, 5xx) — paced by backoff, but does NOT count
      // toward poisoning, which client rule 5 reserves for verification failures.
      await _recordFailure(entry.listCode, entry.version, countsTowardPoison: false);
      return ListSyncResult(
        listCode: entry.listCode,
        version: entry.version,
        outcome: ListSyncOutcome.networkError,
      );
    }

    // The one integrity primitive: hash the exact bytes received, BEFORE any JSON parse.
    final actualHash = sha256.convert(bytes).toString();
    if (actualHash != entry.contentHash) {
      // Discard entirely. Never parsed. Never written to the cache. Never "log and continue."
      await _recordFailure(entry.listCode, entry.version, countsTowardPoison: true);
      return ListSyncResult(
        listCode: entry.listCode,
        version: entry.version,
        outcome: ListSyncOutcome.hashMismatch,
      );
    }

    final ReferenceDocumentDto document;
    try {
      document = parseReferenceDocument(bytes);
    } catch (_) {
      // Hash-verified but unparseable: a server-side bug, not a transport problem. Treated the
      // same as a hash mismatch for poisoning — the artifact itself is bad — rather than left to
      // silently abort the whole catalogue sync (found live, 2026-09-01: an unguarded parse
      // failure on one list used to propagate out of `syncCatalog` and skip every list after it).
      await _recordFailure(entry.listCode, entry.version, countsTowardPoison: true);
      return ListSyncResult(
        listCode: entry.listCode,
        version: entry.version,
        outcome: ListSyncOutcome.malformedDocument,
      );
    }

    final activated = await _stageVerifiedList(entry, document);
    return ListSyncResult(
      listCode: entry.listCode,
      version: entry.version,
      outcome: activated ? ListSyncOutcome.verifiedAndActivated : ListSyncOutcome.verifiedAndStaged,
    );
  }

  Future<bool> _hasRetryEligibleFailure() async {
    final rows = await (_db.select(
      _db.referenceListFailures,
    )..where((t) => t.isPoisoned.equals(false))).get();
    return rows.isNotEmpty;
  }

  Future<ReferenceListFailure?> _failureState(String listCode, int version) async {
    return (_db.select(_db.referenceListFailures)..where(
          (t) => t.listCode.equals(listCode) & t.version.equals(version),
        ))
        .getSingleOrNull();
  }

  Future<bool> _isCached(String listCode, int version, String contentHash) async {
    final row = await (_db.select(_db.referenceLists)..where(
          (t) =>
              t.listCode.equals(listCode) &
              t.version.equals(version) &
              t.contentHash.equals(contentHash),
        ))
        .getSingleOrNull();
    return row != null;
  }

  /// True if this exact `(listCode, version)` is cached under a hash OTHER than [contentHash] —
  /// the server violating AD-002f's own version-immutability declaration.
  Future<bool> _isImmutabilityViolation(String listCode, int version, String contentHash) async {
    final row = await (_db.select(_db.referenceLists)..where(
          (t) => t.listCode.equals(listCode) & t.version.equals(version),
        ))
        .getSingleOrNull();
    return row != null && row.contentHash != contentHash;
  }

  /// [countsTowardPoison] is `false` for transient transport failures (client rule 5 poisons on
  /// verification failure, not on a dropped connection): `totalFailureCount` still increments
  /// (so backoff paces the next attempt regardless of failure kind), but
  /// `verificationFailureCount` — the one poisoning actually checks — only increments for a
  /// hash mismatch or a malformed document.
  Future<void> _recordFailure(
    String listCode,
    int version, {
    required bool countsTowardPoison,
  }) async {
    await _db.transaction(() async {
      final existing = await _failureState(listCode, version);
      final nextTotalCount = (existing?.totalFailureCount ?? 0) + 1;
      final nextVerificationCount = countsTowardPoison
          ? (existing?.verificationFailureCount ?? 0) + 1
          : (existing?.verificationFailureCount ?? 0);
      await _db
          .into(_db.referenceListFailures)
          .insertOnConflictUpdate(
            ReferenceListFailuresCompanion.insert(
              listCode: listCode,
              version: version,
              totalFailureCount: Value(nextTotalCount),
              verificationFailureCount: Value(nextVerificationCount),
              lastFailureAt: Value(DateTime.now()),
              isPoisoned: Value(isPoisonedAfter(nextVerificationCount)),
            ),
          );
    });
  }

  /// Writes the list row, every item row, and clears any failure record — all in ONE
  /// transaction, so a failure partway through (a constraint violation, a thrown exception)
  /// leaves the previous cache state completely untouched. This is what "no half-replaced
  /// cache" (client rule 2) and "interrupted fetch leaves no partial list" mean mechanically.
  ///
  /// Returns `true` if the version activated immediately (first-ever version cached for this
  /// list — nothing else to serve in the interim), `false` if it was staged only, per AD-002f.
  Future<bool> _stageVerifiedList(
    ManifestListEntryDto entry,
    ReferenceDocumentDto document,
  ) async {
    late bool activateImmediately;
    await _db.transaction(() async {
      final existingVersions = await (_db.select(
        _db.referenceLists,
      )..where((t) => t.listCode.equals(entry.listCode))).get();
      activateImmediately = existingVersions.isEmpty;

      await _db
          .into(_db.referenceLists)
          .insert(
            ReferenceListsCompanion.insert(
              listCode: entry.listCode,
              version: entry.version,
              contentHash: entry.contentHash,
              itemCount: entry.itemCount,
              isHierarchical: entry.isHierarchical,
              rootItemCode: Value(entry.rootItemCode),
              rootCountryVersion: Value(entry.rootCountryVersion),
              nameAr: document.nameAr,
              nameEn: document.nameEn,
              publishedAt: entry.publishedAt,
              fetchedAt: DateTime.now(),
              isActiveVersion: Value(activateImmediately),
            ),
            // On conflict (a version already cached under a DIFFERENT contentHash — a server
            // that republished the "same" version with different bytes, violating AD-002f's own
            // immutability assumption), deliberately leave `isActiveVersion` untouched: the plain
            // `activateImmediately` value computed above assumes a brand-new key, and blindly
            // reapplying it here could silently demote an already-active row to `false` with
            // nothing left active for the list (found live, 2026-09-01, latent — no server-side
            // violation has been observed, but the write path must not assume one never happens).
            onConflict: DoUpdate(
              (old) => ReferenceListsCompanion(
                contentHash: Value(entry.contentHash),
                itemCount: Value(entry.itemCount),
                isHierarchical: Value(entry.isHierarchical),
                rootItemCode: Value(entry.rootItemCode),
                rootCountryVersion: Value(entry.rootCountryVersion),
                nameAr: Value(document.nameAr),
                nameEn: Value(document.nameEn),
                publishedAt: Value(entry.publishedAt),
                fetchedAt: Value(DateTime.now()),
              ),
            ),
          );

      await _db.batch((batch) {
        batch.insertAllOnConflictUpdate(
          _db.referenceItems,
          document.items
              .map(
                (item) => ReferenceItemsCompanion.insert(
                  listCode: entry.listCode,
                  version: entry.version,
                  itemCode: item.itemCode,
                  parentCode: Value(item.parentCode),
                  labelAr: item.labelAr,
                  labelEn: Value(item.labelEn),
                  searchAr: item.searchAr,
                  searchEn: Value(item.searchEn),
                  sortOrdinal: item.sortOrdinal,
                  isActive: item.isActive,
                  extraJson: Value(item.extraJson),
                ),
              )
              .toList(),
        );
      });

      // Clears failure rows for EVERY version of this list, not only the one just cached: a
      // stale row for an older version the server has since superseded would otherwise never be
      // touched again (nothing ever re-evaluates that exact old version once a newer one is
      // being fetched instead), permanently keeping `_hasRetryEligibleFailure()` true and
      // disabling the manifest's `If-None-Match` optimisation for the rest of the install (found
      // under review, 2026-09-01). A successful fetch of ANY version is good evidence the list
      // as a whole is healthy again.
      await (_db.delete(
        _db.referenceListFailures,
      )..where((t) => t.listCode.equals(entry.listCode))).go();
    });
    return activateImmediately;
  }

  /// Promotes the newest staged row for [listCode] to active, demoting whatever was previously
  /// active, in one transaction. Called manually — standing in for the real "next session
  /// boundary" hook (client rule 4) since no such hook exists yet. Returns `false` if there was
  /// nothing to promote (no cached rows, or the newest row is already active).
  Future<bool> activateStagedVersion(String listCode) async {
    return _db.transaction(() async {
      final rows =
          await (_db.select(_db.referenceLists)
                ..where((t) => t.listCode.equals(listCode))
                ..orderBy([(t) => OrderingTerm.desc(t.version)]))
              .get();
      if (rows.isEmpty) return false;

      final newest = rows.first;
      if (newest.isActiveVersion) return false;

      // Demote before promoting — the partial unique index (at most one active row per list)
      // is checked per-statement, not deferred, so setting the new row active before the old
      // one is demoted would momentarily leave two active rows and fail the constraint (found
      // live, 2026-09-01).
      await (_db.update(_db.referenceLists)..where(
            (t) => t.listCode.equals(listCode) & t.isActiveVersion.equals(true),
          ))
          .write(const ReferenceListsCompanion(isActiveVersion: Value(false)));

      await (_db.update(_db.referenceLists)..where(
            (t) => t.listCode.equals(listCode) & t.version.equals(newest.version),
          ))
          .write(const ReferenceListsCompanion(isActiveVersion: Value(true)));
      return true;
    });
  }

  /// Whether [listCode] has ANY active cached version right now — the signal client rule 5's
  /// "block stage 3 if there is no prior cache" is actually about. Deliberately independent of
  /// the most recent `syncCatalog()` outcome: a list that synced successfully in an earlier
  /// session and is merely failing to get a NEWER version today still has a good cache and must
  /// not be treated as broken (rule 5: "discard, keep the last verified version").
  Future<bool> hasActiveVersion(String listCode) async {
    final row = await (_db.select(_db.referenceLists)..where(
          (t) => t.listCode.equals(listCode) & t.isActiveVersion.equals(true),
        ))
        .getSingleOrNull();
    return row != null;
  }

  /// The declared cascade root for [listCode]'s currently-ACTIVE version — e.g. `admin_division`'s
  /// `rootItemCode`, which is the Sudan country code (reference-data.md client rule 7: "Never
  /// hardcode 'SD' in Dart" — this is the mechanism that avoids it, mirroring the backend's own
  /// `ReferenceCatalog.currentRootItemCode`). `null` for a non-hierarchical list (`rootItemCode`
  /// is itself nullable on `ReferenceLists`) or if [listCode] has no active version cached yet.
  Future<String?> rootItemCode(String listCode) async {
    final row = await (_db.select(_db.referenceLists)..where(
          (t) => t.listCode.equals(listCode) & t.isActiveVersion.equals(true),
        ))
        .getSingleOrNull();
    return row?.rootItemCode;
  }

  /// Records today's active version for [listCode] into [sessionDb] — client rule 4's real
  /// stage-3-entry pinning mechanism (`DataEntryRepository.prepareCatalog`, S5-05) as well as the
  /// original manual stand-in the demo screen still calls.
  Future<void> pinCurrentSessionVersion(String listCode, SessionDatabase sessionDb) async {
    final row = await (_db.select(_db.referenceLists)..where(
          (t) => t.listCode.equals(listCode) & t.isActiveVersion.equals(true),
        ))
        .getSingleOrNull();
    if (row == null) return;
    await sessionDb.pinVersion(listCode, row.version);
  }

  /// The query the demo screen renders from: the currently-active version's ENABLED items, in
  /// `sort_ordinal` order — server-computed, never re-sorted client-side. `isActive == false`
  /// marks an item withdrawn from new selection while remaining valid for historical profiles
  /// (V0011) — a picker must not offer it.
  Stream<List<ReferenceItem>> watchActiveItems(String listCode) {
    final query =
        _db.select(_db.referenceItems).join([
            innerJoin(
              _db.referenceLists,
              _db.referenceLists.listCode.equalsExp(_db.referenceItems.listCode) &
                  _db.referenceLists.version.equalsExp(_db.referenceItems.version) &
                  _db.referenceLists.isActiveVersion.equals(true),
            ),
          ])
          ..where(
            _db.referenceItems.listCode.equals(listCode) &
                _db.referenceItems.isActive.equals(true),
          )
          ..orderBy([OrderingTerm.asc(_db.referenceItems.sortOrdinal)]);

    return query.watch().map(
      (rows) => rows.map((row) => row.readTable(_db.referenceItems)).toList(),
    );
  }
}
