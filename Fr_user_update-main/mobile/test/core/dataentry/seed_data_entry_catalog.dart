import 'package:crypto/crypto.dart';
import 'package:mobile/core/network/manifest_dto.dart';
import 'package:mobile/core/reference/reference_list_codes.dart';

import '../reference/fake_reference_api.dart';

/// Seeds [api] with a minimal, hash-valid manifest + document for every one of the five reference
/// lists `DataEntryRepository.prepareCatalog` needs (country, admin_division, occupation,
/// income_source, education_level) — one item each is enough for `prepareCatalog()` to find an
/// active version for all five. Shared by `channel_verification_screen_test.dart` (the Stage 2→3
/// boundary call) and `data_entry_repository_test.dart`.
void seedDataEntryCatalog(FakeReferenceApi api) {
  const listCodes = [
    ReferenceListCodes.country,
    ReferenceListCodes.adminDivision,
    ReferenceListCodes.occupation,
    ReferenceListCodes.incomeSource,
    ReferenceListCodes.educationLevel,
  ];

  final entries = <ManifestListEntryDto>[];
  for (final listCode in listCodes) {
    final isAdminDivision = listCode == ReferenceListCodes.adminDivision;
    final items = switch (listCode) {
      ReferenceListCodes.adminDivision => [
        buildItem(itemCode: 'SD', labelAr: 'السودان', sortOrdinal: 1),
        buildItem(itemCode: '31', parentCode: 'SD', labelAr: 'الخرطوم', sortOrdinal: 2),
        buildItem(itemCode: '3101', parentCode: '31', labelAr: 'الخرطوم بحري', sortOrdinal: 3),
      ],
      ReferenceListCodes.country => [
        buildItem(itemCode: 'SD', labelAr: 'السودان', sortOrdinal: 1),
        buildItem(itemCode: 'EG', labelAr: 'مصر', sortOrdinal: 2),
      ],
      ReferenceListCodes.incomeSource => [
        buildItem(itemCode: 'RATIB', labelAr: 'راتب', sortOrdinal: 1),
        buildItem(itemCode: 'OTHER', labelAr: 'أخرى', sortOrdinal: 2),
      ],
      _ => [buildItem(itemCode: '1', labelAr: 'عنصر', sortOrdinal: 1)],
    };
    final bytes = buildDocumentBytes(
      listCode: listCode,
      version: 1,
      isHierarchical: isAdminDivision,
      rootItemCode: isAdminDivision ? 'SD' : null,
      items: items,
    );
    final hash = sha256.convert(bytes).toString();
    api.bytesByListVersion['$listCode/1'] = bytes;
    entries.add(
      ManifestListEntryDto(
        listCode: listCode,
        version: 1,
        itemCount: items.length,
        contentHash: hash,
        isHierarchical: isAdminDivision,
        rootItemCode: isAdminDivision ? 'SD' : null,
        rootCountryVersion: isAdminDivision ? 1 : null,
        publishedAt: DateTime.utc(2026, 1, 1),
        documentPath: '/api/v1/reference/lists/$listCode/1',
      ),
    );
  }

  api.manifestToServe = ManifestDto(
    catalogHash: 'catalog-hash-irrelevant-for-these-tests',
    generatedAt: DateTime.utc(2026, 1, 1),
    lists: entries,
    verifiableChannels: const ['sms', 'whatsapp', 'email'],
  );
}
