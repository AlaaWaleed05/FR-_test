import 'package:crypto/crypto.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/database/database_providers.dart';
import 'package:mobile/core/database/reference_database.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/network/manifest_dto.dart';
import 'package:mobile/core/reference/reference_list_codes.dart';
import 'package:mobile/core/reference/reference_providers.dart';

import 'fake_reference_api.dart';

void main() {
  test(
    'demoInitProvider and occupationItemsProvider compose end-to-end through the real '
    'referenceRepositoryProvider',
    () async {
      final bytes = buildDocumentBytes(
        listCode: ReferenceListCodes.occupation,
        version: 1,
        items: [buildItem(itemCode: 'A', labelAr: 'ألف', sortOrdinal: 1)],
      );
      final hash = sha256.convert(bytes).toString();
      final fakeApi = FakeReferenceApi()
        ..manifestToServe = ManifestDto(
          catalogHash: 'x',
          generatedAt: DateTime.utc(2026, 1, 1),
          lists: [
            ManifestListEntryDto(
              listCode: ReferenceListCodes.occupation,
              version: 1,
              itemCount: 1,
              contentHash: hash,
              isHierarchical: false,
              rootItemCode: null,
              rootCountryVersion: null,
              publishedAt: DateTime.utc(2026, 1, 1),
              documentPath: '/x',
            ),
          ],
          verifiableChannels: const [],
        )
        ..bytesByListVersion['${ReferenceListCodes.occupation}/1'] = bytes;

      final container = ProviderContainer(
        overrides: [
          referenceDatabaseProvider.overrideWithValue(ReferenceDatabase.forTesting()),
          sessionDatabaseProvider.overrideWithValue(SessionDatabase.forTesting()),
          referenceApiProvider.overrideWithValue(fakeApi),
        ],
      );
      addTearDown(container.dispose);

      await container.read(demoInitProvider.future);

      final sessionDb = container.read(sessionDatabaseProvider);
      expect(await sessionDb.pinnedVersionFor(ReferenceListCodes.occupation), 1);

      final items = await container.read(occupationItemsProvider.future);
      expect(items.single.labelAr, 'ألف');
    },
  );

  test(
    'demoInitProvider throws ReferenceSyncFailure when the occupation list has no cache at all',
    () async {
      final fakeApi = FakeReferenceApi()
        ..manifestToServe = ManifestDto(
          catalogHash: 'x',
          generatedAt: DateTime.utc(2026, 1, 1),
          lists: [
            ManifestListEntryDto(
              listCode: ReferenceListCodes.occupation,
              version: 1,
              itemCount: 1,
              contentHash: 'not-the-real-hash', // guarantees a hash mismatch, no cache written
              isHierarchical: false,
              rootItemCode: null,
              rootCountryVersion: null,
              publishedAt: DateTime.utc(2026, 1, 1),
              documentPath: '/x',
            ),
          ],
          verifiableChannels: const [],
        )
        ..bytesByListVersion['${ReferenceListCodes.occupation}/1'] = buildDocumentBytes(
          listCode: ReferenceListCodes.occupation,
          version: 1,
          items: [buildItem(itemCode: 'A', labelAr: 'ألف', sortOrdinal: 1)],
        );

      final container = ProviderContainer(
        overrides: [
          referenceDatabaseProvider.overrideWithValue(ReferenceDatabase.forTesting()),
          sessionDatabaseProvider.overrideWithValue(SessionDatabase.forTesting()),
          referenceApiProvider.overrideWithValue(fakeApi),
        ],
      );
      addTearDown(container.dispose);

      await expectLater(
        container.read(demoInitProvider.future),
        throwsA(isA<ReferenceSyncFailure>()),
      );
    },
  );

  test(
    'demoInitProvider does NOT throw when a prior cache exists, even if this sync attempt fails '
    '— client rule 5: keep the last verified version',
    () async {
      final goodBytes = buildDocumentBytes(
        listCode: ReferenceListCodes.occupation,
        version: 1,
        items: [buildItem(itemCode: 'A', labelAr: 'ألف', sortOrdinal: 1)],
      );
      final goodHash = sha256.convert(goodBytes).toString();
      final fakeApi = FakeReferenceApi()
        ..manifestToServe = ManifestDto(
          catalogHash: 'v1',
          generatedAt: DateTime.utc(2026, 1, 1),
          lists: [
            ManifestListEntryDto(
              listCode: ReferenceListCodes.occupation,
              version: 1,
              itemCount: 1,
              contentHash: goodHash,
              isHierarchical: false,
              rootItemCode: null,
              rootCountryVersion: null,
              publishedAt: DateTime.utc(2026, 1, 1),
              documentPath: '/x',
            ),
          ],
          verifiableChannels: const [],
        )
        ..bytesByListVersion['${ReferenceListCodes.occupation}/1'] = goodBytes;

      final referenceDb = ReferenceDatabase.forTesting();
      final container = ProviderContainer(
        overrides: [
          referenceDatabaseProvider.overrideWithValue(referenceDb),
          sessionDatabaseProvider.overrideWithValue(SessionDatabase.forTesting()),
          referenceApiProvider.overrideWithValue(fakeApi),
        ],
      );
      addTearDown(container.dispose);

      await container.read(demoInitProvider.future); // establishes a good cache

      // Now a NEW version is advertised, but its bytes are broken — the cache from above must be
      // left standing and demoInitProvider must NOT throw.
      fakeApi.manifestToServe = ManifestDto(
        catalogHash: 'v2',
        generatedAt: DateTime.utc(2026, 1, 2),
        lists: [
          ManifestListEntryDto(
            listCode: ReferenceListCodes.occupation,
            version: 2,
            itemCount: 1,
            contentHash: 'not-the-real-hash',
            isHierarchical: false,
            rootItemCode: null,
            rootCountryVersion: null,
            publishedAt: DateTime.utc(2026, 1, 2),
            documentPath: '/x',
          ),
        ],
        verifiableChannels: const [],
      );
      fakeApi.bytesByListVersion['${ReferenceListCodes.occupation}/2'] = goodBytes;
      container.invalidate(demoInitProvider);

      await container.read(demoInitProvider.future); // must not throw
      final repository = container.read(referenceRepositoryProvider);
      expect(await repository.hasActiveVersion(ReferenceListCodes.occupation), isTrue);
    },
  );
}
