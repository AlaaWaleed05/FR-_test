import 'package:crypto/crypto.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/database/database_providers.dart';
import 'package:mobile/core/database/reference_database.dart';
import 'package:mobile/core/database/session_database.dart';
import 'package:mobile/core/entry/entry_models.dart';
import 'package:mobile/core/entry/entry_providers.dart';
import 'package:mobile/core/network/manifest_dto.dart';
import 'package:mobile/core/reference/reference_list_codes.dart';
import 'package:mobile/core/reference/reference_providers.dart';

import '../reference/fake_reference_api.dart';
import 'fake_entry_api.dart';

void main() {
  test('entryRepositoryProvider composes the real EntryApi + SessionDatabase providers', () async {
    final fakeApi = FakeEntryApi()
      ..accountCheckResultToServe = const AccountCheckResult(
        outcome: AccountOutcome.active,
        continuation: AccountContinuation.proceed,
        requestId: 'r1',
      );

    final container = ProviderContainer(
      overrides: [
        sessionDatabaseProvider.overrideWithValue(SessionDatabase.forTesting()),
        entryApiProvider.overrideWithValue(fakeApi),
      ],
    );
    addTearDown(container.dispose);

    await container.read(entryRepositoryProvider).checkAccount('2', '12345');

    final sessionDb = container.read(sessionDatabaseProvider);
    final progress = await (sessionDb.select(
      sessionDb.localProgress,
    )..where((t) => t.id.equals(0))).getSingleOrNull();
    expect(progress, isNotNull);
    expect(progress!.verifiedAccountNumber, '12345');
  });

  test('launchDecisionProvider composes end-to-end to a bare FreshStart with no local state', () async {
    final container = ProviderContainer(
      overrides: [
        sessionDatabaseProvider.overrideWithValue(SessionDatabase.forTesting()),
        entryApiProvider.overrideWithValue(FakeEntryApi()),
      ],
    );
    addTearDown(container.dispose);

    final decision = await container.read(launchDecisionProvider.future);
    expect(decision, isA<FreshStart>());
  });

  test(
    'branchCatalogInitProvider and branchItemsProvider compose end-to-end through the real '
    'referenceRepositoryProvider',
    () async {
      final bytes = buildDocumentBytes(
        listCode: ReferenceListCodes.branch,
        version: 1,
        items: [buildItem(itemCode: '2', labelAr: 'بورتسودان', sortOrdinal: 1)],
      );
      final hash = sha256.convert(bytes).toString();
      final fakeApi = FakeReferenceApi()
        ..manifestToServe = ManifestDto(
          catalogHash: 'x',
          generatedAt: DateTime.utc(2026, 1, 1),
          lists: [
            ManifestListEntryDto(
              listCode: ReferenceListCodes.branch,
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
        ..bytesByListVersion['${ReferenceListCodes.branch}/1'] = bytes;

      final container = ProviderContainer(
        overrides: [
          referenceDatabaseProvider.overrideWithValue(ReferenceDatabase.forTesting()),
          sessionDatabaseProvider.overrideWithValue(SessionDatabase.forTesting()),
          referenceApiProvider.overrideWithValue(fakeApi),
        ],
      );
      addTearDown(container.dispose);

      await container.read(branchCatalogInitProvider.future);

      final items = await container.read(branchItemsProvider.future);
      expect(items.single.labelAr, 'بورتسودان');
    },
  );
}
