import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:drift/drift.dart' hide isNull;
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/database/reference_database.dart';
import 'package:mobile/core/network/manifest_dto.dart';
import 'package:mobile/core/reference/reference_repository.dart';
import 'package:mobile/core/reference/reference_sync_result.dart';

import 'fake_reference_api.dart';

ManifestListEntryDto _entry({
  required String listCode,
  required int version,
  required String contentHash,
  int itemCount = 1,
}) {
  return ManifestListEntryDto(
    listCode: listCode,
    version: version,
    itemCount: itemCount,
    contentHash: contentHash,
    isHierarchical: false,
    rootItemCode: null,
    rootCountryVersion: null,
    publishedAt: DateTime.utc(2026, 1, 1),
    documentPath: '/api/v1/reference/lists/$listCode/$version',
  );
}

ManifestDto _manifest(
  List<ManifestListEntryDto> lists, {
  String catalogHash = 'irrelevant-for-these-tests',
}) {
  return ManifestDto(
    catalogHash: catalogHash,
    generatedAt: DateTime.utc(2026, 1, 1),
    lists: lists,
    verifiableChannels: const ['sms', 'whatsapp', 'email'],
  );
}

void main() {
  late ReferenceDatabase db;
  late FakeReferenceApi api;
  late ReferenceRepository repository;

  setUp(() {
    db = ReferenceDatabase.forTesting();
    api = FakeReferenceApi();
    repository = ReferenceRepository(api: api, db: db);
  });

  tearDown(() async {
    await db.close();
  });

  /// Simulates the backoff window having elapsed, so a test can drive a second real retry
  /// attempt without waiting on `backoffFor`'s real durations (15s+).
  Future<void> expireBackoff(String listCode, int version) async {
    await (db.update(db.referenceListFailures)..where(
          (t) => t.listCode.equals(listCode) & t.version.equals(version),
        ))
        .write(
          ReferenceListFailuresCompanion(
            lastFailureAt: Value(DateTime.now().subtract(const Duration(minutes: 10))),
          ),
        );
  }

  test('hash match stages and activates the first-ever version, queryable afterward', () async {
    final bytes = buildDocumentBytes(
      listCode: 'occupation',
      version: 1,
      items: [
        buildItem(itemCode: 'A', labelAr: 'ألف', sortOrdinal: 2),
        buildItem(itemCode: 'B', labelAr: 'باء', sortOrdinal: 1),
      ],
    );
    final hash = sha256.convert(bytes).toString();
    api.manifestToServe = _manifest([_entry(listCode: 'occupation', version: 1, contentHash: hash)]);
    api.bytesByListVersion['occupation/1'] = bytes;

    final result = await repository.syncCatalog();

    expect(result.allSucceeded, isTrue);
    expect(result.results.single.outcome, ListSyncOutcome.verifiedAndActivated);

    final items = await repository.watchActiveItems('occupation').first;
    expect(items.map((i) => i.itemCode).toList(), ['B', 'A']); // sortOrdinal order, not label order
  });

  test('an inactive item is cached but excluded from watchActiveItems', () async {
    final bytes = buildDocumentBytes(
      listCode: 'occupation',
      version: 1,
      items: [
        buildItem(itemCode: 'A', labelAr: 'ألف', sortOrdinal: 1),
        buildItem(itemCode: 'B', labelAr: 'باء', sortOrdinal: 2, isActive: false),
      ],
    );
    final hash = sha256.convert(bytes).toString();
    api.manifestToServe = _manifest([_entry(listCode: 'occupation', version: 1, contentHash: hash)]);
    api.bytesByListVersion['occupation/1'] = bytes;

    await repository.syncCatalog();

    final allCached = await db.select(db.referenceItems).get();
    expect(allCached, hasLength(2)); // both cached...

    final active = await repository.watchActiveItems('occupation').first;
    expect(active.map((i) => i.itemCode).toList(), ['A']); // ...only the enabled one is shown
  });

  test('null labelEn/searchEn round-trip through fetch, verify, cache and read', () async {
    // Real production data (branch list, observed live 2026-09-01): some items have no English
    // name seeded at all.
    final bytes = buildDocumentBytes(
      listCode: 'branch',
      version: 1,
      items: [buildItem(itemCode: 'A', labelAr: 'الخرطوم', labelEn: null, searchEn: null, sortOrdinal: 1)],
    );
    final hash = sha256.convert(bytes).toString();
    api.manifestToServe = _manifest([_entry(listCode: 'branch', version: 1, contentHash: hash)]);
    api.bytesByListVersion['branch/1'] = bytes;

    final result = await repository.syncCatalog();
    expect(result.allSucceeded, isTrue);

    final row = await db.select(db.referenceItems).getSingle();
    expect(row.labelEn, isNull);
    expect(row.searchEn, isNull);
    expect(row.labelAr, 'الخرطوم');
  });

  test('hash mismatch leaves the cache unchanged, never parses the bytes, and paces via backoff '
      'without poisoning on the first failure', () async {
    final bytes = buildDocumentBytes(
      listCode: 'occupation',
      version: 1,
      items: [buildItem(itemCode: 'A', labelAr: 'ألف', sortOrdinal: 1)],
    );
    api.manifestToServe = _manifest([
      _entry(listCode: 'occupation', version: 1, contentHash: 'not-the-real-hash'),
    ]);
    api.bytesByListVersion['occupation/1'] = bytes;

    final result = await repository.syncCatalog();

    expect(result.results.single.outcome, ListSyncOutcome.hashMismatch);
    expect(await db.select(db.referenceLists).get(), isEmpty);
    expect(await db.select(db.referenceItems).get(), isEmpty);

    final failure = await db.select(db.referenceListFailures).getSingle();
    expect(failure.verificationFailureCount, 1);
    expect(failure.totalFailureCount, 1);
    expect(failure.isPoisoned, isFalse);
  });

  test(
    'an unresolved failure forces a full manifest re-fetch on the next sync even when the '
    'server reports no change — the fix for the BLOCKER where a 304 permanently starved a '
    'failed list of retries',
    () async {
      final bytes = buildDocumentBytes(
        listCode: 'occupation',
        version: 1,
        items: [buildItem(itemCode: 'A', labelAr: 'ألف', sortOrdinal: 1)],
      );
      api.manifestToServe = _manifest([
        _entry(listCode: 'occupation', version: 1, contentHash: 'not-the-real-hash'),
      ], catalogHash: 'same-catalog-hash-throughout');
      api.bytesByListVersion['occupation/1'] = bytes;

      await repository.syncCatalog(); // failure 1 — manifest_state is still written (see below)
      expect(api.fetchManifestCallCount, 1);

      await expireBackoff('occupation', 1);
      final second = await repository.syncCatalog();
      // Despite the catalogue's own hash never changing, the second sync must NOT have been
      // short-circuited by a 304 — it must have reached the list again.
      expect(second.results, isNotEmpty);
      expect(second.results.single.outcome, ListSyncOutcome.hashMismatch);
      expect(api.fetchListBytesCallCount, 2);
    },
  );

  test(
    'a second consecutive VERIFICATION failure poisons the version and stops further fetch '
    'attempts',
    () async {
      final bytes = buildDocumentBytes(
        listCode: 'occupation',
        version: 1,
        items: [buildItem(itemCode: 'A', labelAr: 'ألف', sortOrdinal: 1)],
      );
      api.manifestToServe = _manifest([
        _entry(listCode: 'occupation', version: 1, contentHash: 'not-the-real-hash'),
      ]);
      api.bytesByListVersion['occupation/1'] = bytes;

      await repository.syncCatalog(); // verificationFailureCount=1
      await expireBackoff('occupation', 1);
      final second = await repository.syncCatalog(); // verificationFailureCount=2, poisoned
      expect(second.results.single.outcome, ListSyncOutcome.hashMismatch);
      final callCountAfterTwoMismatches = api.fetchListBytesCallCount;
      expect(callCountAfterTwoMismatches, 2);

      final failure = await db.select(db.referenceListFailures).getSingle();
      expect(failure.isPoisoned, isTrue);

      // A poisoned list is reported as `poisoned` the next time it is actually evaluated — which
      // requires a fresh (non-304) manifest fetch, since a poisoned list no longer forces one on
      // its own.
      api.manifestToServe = _manifest([
        _entry(listCode: 'occupation', version: 1, contentHash: 'not-the-real-hash'),
      ], catalogHash: 'a-different-catalog-hash');
      final third = await repository.syncCatalog();
      expect(third.results.single.outcome, ListSyncOutcome.poisoned);
      expect(api.fetchListBytesCallCount, callCountAfterTwoMismatches); // never fetched again
    },
  );

  test(
    'a transient network error paces retries via backoff but never poisons, even after many '
    'failures',
    () async {
      api.manifestToServe = _manifest([
        _entry(listCode: 'occupation', version: 1, contentHash: 'irrelevant-never-reached'),
      ]);
      api.throwOnFetch['occupation/1'] = Exception('simulated dropped connection');

      for (var i = 0; i < 5; i++) {
        final result = await repository.syncCatalog();
        expect(result.results.single.outcome, ListSyncOutcome.networkError);
        await expireBackoff('occupation', 1);
      }

      final failure = await db.select(db.referenceListFailures).getSingle();
      expect(failure.totalFailureCount, 5);
      expect(failure.verificationFailureCount, 0);
      expect(failure.isPoisoned, isFalse); // never poisoned by transport failures alone
    },
  );

  test('a recent failure blocks an immediate retry (backoff), without re-fetching', () async {
    final bytes = buildDocumentBytes(
      listCode: 'occupation',
      version: 1,
      items: [buildItem(itemCode: 'A', labelAr: 'ألف', sortOrdinal: 1)],
    );
    api.manifestToServe = _manifest([
      _entry(listCode: 'occupation', version: 1, contentHash: 'not-the-real-hash'),
    ]);
    api.bytesByListVersion['occupation/1'] = bytes;

    await repository.syncCatalog(); // failure 1, lastFailureAt = now
    final immediateRetry = await repository.syncCatalog(); // no expireBackoff() call
    expect(immediateRetry.results.single.outcome, ListSyncOutcome.backingOff);
    expect(api.fetchListBytesCallCount, 1); // not re-fetched while backing off
  });

  test(
    'a document that hash-verifies but fails to parse is reported distinctly, records a '
    'verification failure, and does not abort the rest of the catalogue sync',
    () async {
      final malformedBytes = Uint8List.fromList(
        utf8.encode(
          jsonEncode({
            'listCode': 'branch',
            'version': 1,
            'itemCount': 1,
            'nameAr': 'a',
            'nameEn': 'b',
            'isHierarchical': false,
            'rootItemCode': null,
            'items': [
              {'labelAr': 'x'}, // missing required itemCode -> throws during parse, not hashing
            ],
          }),
        ),
      );
      final malformedHash = sha256.convert(malformedBytes).toString();

      final goodBytes = buildDocumentBytes(
        listCode: 'occupation',
        version: 1,
        items: [buildItem(itemCode: 'A', labelAr: 'ألف', sortOrdinal: 1)],
      );
      final goodHash = sha256.convert(goodBytes).toString();

      api.manifestToServe = _manifest([
        _entry(listCode: 'branch', version: 1, contentHash: malformedHash),
        _entry(listCode: 'occupation', version: 1, contentHash: goodHash),
      ]);
      api.bytesByListVersion['branch/1'] = malformedBytes;
      api.bytesByListVersion['occupation/1'] = goodBytes;

      final result = await repository.syncCatalog();

      final branchResult = result.forList('branch')!;
      expect(branchResult.outcome, ListSyncOutcome.malformedDocument);
      expect(branchResult.isFailure, isTrue);

      // The OTHER list in the same sync still succeeded — one list's failure does not stop the
      // rest of the catalogue, per the class's own documented guarantee.
      final occupationResult = result.forList('occupation')!;
      expect(occupationResult.outcome, ListSyncOutcome.verifiedAndActivated);

      final branchFailure = await (db.select(
        db.referenceListFailures,
      )..where((t) => t.listCode.equals('branch'))).getSingle();
      expect(branchFailure.verificationFailureCount, 1); // counts toward poisoning, like a mismatch
    },
  );

  test('a list already cached at the manifest (version, contentHash) is not re-fetched', () async {
    final bytes = buildDocumentBytes(
      listCode: 'occupation',
      version: 1,
      items: [buildItem(itemCode: 'A', labelAr: 'ألف', sortOrdinal: 1)],
    );
    final hash = sha256.convert(bytes).toString();
    api.manifestToServe = _manifest([_entry(listCode: 'occupation', version: 1, contentHash: hash)]);
    api.bytesByListVersion['occupation/1'] = bytes;

    await repository.syncCatalog();
    expect(api.fetchListBytesCallCount, 1);

    // The manifest itself is genuinely unchanged (same catalogHash, no pending failures), so
    // the second sync legitimately gets a 304 and never re-evaluates any list at all — the
    // strongest form of "not re-fetched." (When the catalogue DOES change but this list's own
    // version/hash didn't, `_isCached` is what returns `upToDate` — see the still-passing
    // "second consecutive VERIFICATION failure" test's manifest-refresh step for that path.)
    final second = await repository.syncCatalog();
    expect(second.results, isEmpty);
    expect(api.fetchListBytesCallCount, 1); // not re-fetched
  });

  test('manifest_state stays a single row across repeated successful syncs', () async {
    final bytes = buildDocumentBytes(
      listCode: 'occupation',
      version: 1,
      items: [buildItem(itemCode: 'A', labelAr: 'ألف', sortOrdinal: 1)],
    );
    final hash = sha256.convert(bytes).toString();
    api.manifestToServe = _manifest([_entry(listCode: 'occupation', version: 1, contentHash: hash)]);
    api.bytesByListVersion['occupation/1'] = bytes;

    await repository.syncCatalog();
    await repository.syncCatalog();
    await repository.syncCatalog();

    final rows = await db.select(db.manifestState).get();
    expect(rows, hasLength(1)); // not a new row on every sync (found live, 2026-09-01)
  });

  test(
    'a newer version stages without disturbing the currently active version, until activated',
    () async {
      final v1Bytes = buildDocumentBytes(
        listCode: 'occupation',
        version: 1,
        items: [buildItem(itemCode: 'A', labelAr: 'ألف', sortOrdinal: 1)],
      );
      final v1Hash = sha256.convert(v1Bytes).toString();
      api.manifestToServe = _manifest([
        _entry(listCode: 'occupation', version: 1, contentHash: v1Hash),
      ]);
      api.bytesByListVersion['occupation/1'] = v1Bytes;
      await repository.syncCatalog();

      final v2Bytes = buildDocumentBytes(
        listCode: 'occupation',
        version: 2,
        items: [buildItem(itemCode: 'C', labelAr: 'جيم', sortOrdinal: 1)],
      );
      final v2Hash = sha256.convert(v2Bytes).toString();
      // A genuinely different catalogue must carry a different catalogHash — reusing the
      // default here would make the fake 304 the request, exactly the bug this file's own
      // "unresolved failure forces a re-fetch" test exists to catch on the failure path.
      api.manifestToServe = _manifest([
        _entry(listCode: 'occupation', version: 2, contentHash: v2Hash),
      ], catalogHash: 'v2-catalog');
      api.bytesByListVersion['occupation/2'] = v2Bytes;
      final result = await repository.syncCatalog();

      expect(result.results.single.outcome, ListSyncOutcome.verifiedAndStaged);
      // The device still reads v1 until activated.
      final activeItems = await repository.watchActiveItems('occupation').first;
      expect(activeItems.map((i) => i.itemCode).toList(), ['A']);

      final activated = await repository.activateStagedVersion('occupation');
      expect(activated, isTrue);
      final afterActivation = await repository.watchActiveItems('occupation').first;
      expect(afterActivation.map((i) => i.itemCode).toList(), ['C']);
    },
  );

  test(
    'a republished version under a different contentHash (a server immutability violation) is '
    'refused outright — the cache is left exactly as it was',
    () async {
      final v1Bytes = buildDocumentBytes(
        listCode: 'occupation',
        version: 1,
        items: [buildItem(itemCode: 'A', labelAr: 'ألف', sortOrdinal: 1)],
      );
      final v1Hash = sha256.convert(v1Bytes).toString();
      api.manifestToServe = _manifest([
        _entry(listCode: 'occupation', version: 1, contentHash: v1Hash),
      ]);
      api.bytesByListVersion['occupation/1'] = v1Bytes;
      await repository.syncCatalog();
      expect((await db.select(db.referenceLists).getSingle()).isActiveVersion, isTrue);

      // Same version, DIFFERENT bytes/hash — the server violating AD-002f's own immutability
      // assumption. AD-002f declares versions immutable, and there is no way to "stage" a
      // same-key conflict the way a genuinely new version stages, so this must be refused, not
      // fetched and absorbed (found under review, 2026-09-01 — an earlier fix preserved
      // `isActiveVersion` on this path but still let the bytes/hash themselves overwrite).
      final v1RepublishedBytes = buildDocumentBytes(
        listCode: 'occupation',
        version: 1,
        items: [buildItem(itemCode: 'A', labelAr: 'ألف معدّل', sortOrdinal: 1)],
      );
      final v1RepublishedHash = sha256.convert(v1RepublishedBytes).toString();
      api.manifestToServe = _manifest([
        _entry(listCode: 'occupation', version: 1, contentHash: v1RepublishedHash),
      ], catalogHash: 'republished');
      api.bytesByListVersion['occupation/1'] = v1RepublishedBytes;

      final result = await repository.syncCatalog();

      expect(result.results.single.outcome, ListSyncOutcome.immutabilityViolation);
      expect(api.fetchListBytesCallCount, 1); // never even fetched the republished bytes
      final row = await db.select(db.referenceLists).getSingle();
      expect(row.isActiveVersion, isTrue);
      expect(row.contentHash, v1Hash); // unchanged — the original version stands
    },
  );

  test('deleting a reference_lists row cascades to delete its items (foreign_keys pragma is ON)', () async {
    final bytes = buildDocumentBytes(
      listCode: 'occupation',
      version: 1,
      items: [buildItem(itemCode: 'A', labelAr: 'ألف', sortOrdinal: 1)],
    );
    final hash = sha256.convert(bytes).toString();
    api.manifestToServe = _manifest([_entry(listCode: 'occupation', version: 1, contentHash: hash)]);
    api.bytesByListVersion['occupation/1'] = bytes;
    await repository.syncCatalog();

    expect(await db.select(db.referenceItems).get(), isNotEmpty);

    await (db.delete(
      db.referenceLists,
    )..where((t) => t.listCode.equals('occupation') & t.version.equals(1))).go();

    expect(await db.select(db.referenceItems).get(), isEmpty);
  });

  test(
    'a failure thrown mid-transaction during the real fetch-verify-cache path rolls back '
    'completely — no half-replaced cache',
    () async {
      // A real SQLite trigger forces a genuine write failure partway through the actual
      // production `_stageVerifiedList` transaction, rather than a hand-rolled transaction
      // exercising only drift's own primitive (the prior version of this test did that, and
      // would still have passed even if the production code stopped using a transaction at all).
      await db.customStatement('''
        CREATE TRIGGER force_fail_on_marker_item
        BEFORE INSERT ON reference_items
        WHEN NEW.item_code = 'FORCE_FAIL'
        BEGIN
          SELECT RAISE(ABORT, 'simulated interruption mid-transaction');
        END;
      ''');

      final bytes = buildDocumentBytes(
        listCode: 'occupation',
        version: 1,
        items: [
          buildItem(itemCode: 'A', labelAr: 'ألف', sortOrdinal: 1),
          buildItem(itemCode: 'FORCE_FAIL', labelAr: 'x', sortOrdinal: 2),
        ],
      );
      final hash = sha256.convert(bytes).toString();
      api.manifestToServe = _manifest([
        _entry(listCode: 'occupation', version: 1, contentHash: hash, itemCount: 2),
      ]);
      api.bytesByListVersion['occupation/1'] = bytes;

      await expectLater(repository.syncCatalog(), throwsA(anything));

      expect(await db.select(db.referenceLists).get(), isEmpty);
      expect(await db.select(db.referenceItems).get(), isEmpty);
    },
  );
}
