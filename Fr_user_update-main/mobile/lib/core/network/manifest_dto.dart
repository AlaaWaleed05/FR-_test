import 'dart:convert';

/// Mirrors `com.sfbank.bayanati.reference.web.ManifestResponse` field-for-field
/// (`GET /api/v1/reference/manifest`, docs/components/reference-data.md).
class ManifestDto {
  const ManifestDto({
    required this.catalogHash,
    required this.generatedAt,
    required this.lists,
    required this.verifiableChannels,
  });

  factory ManifestDto.fromJson(Map<String, dynamic> json) {
    return ManifestDto(
      catalogHash: json['catalogHash'] as String,
      generatedAt: DateTime.parse(json['generatedAt'] as String),
      lists: (json['lists'] as List<dynamic>)
          .map((e) => ManifestListEntryDto.fromJson(e as Map<String, dynamic>))
          .toList(),
      verifiableChannels: (json['verifiableChannels'] as List<dynamic>)
          .map((e) => e as String)
          .toList(),
    );
  }

  final String catalogHash;
  final DateTime generatedAt;
  final List<ManifestListEntryDto> lists;
  final List<String> verifiableChannels;
}

/// Mirrors `com.sfbank.bayanati.reference.web.ManifestListEntryResponse`.
class ManifestListEntryDto {
  const ManifestListEntryDto({
    required this.listCode,
    required this.version,
    required this.itemCount,
    required this.contentHash,
    required this.isHierarchical,
    required this.rootItemCode,
    required this.rootCountryVersion,
    required this.publishedAt,
    required this.documentPath,
  });

  factory ManifestListEntryDto.fromJson(Map<String, dynamic> json) {
    return ManifestListEntryDto(
      listCode: json['listCode'] as String,
      version: json['version'] as int,
      itemCount: json['itemCount'] as int,
      contentHash: json['contentHash'] as String,
      isHierarchical: json['isHierarchical'] as bool,
      rootItemCode: json['rootItemCode'] as String?,
      rootCountryVersion: json['rootCountryVersion'] as int?,
      publishedAt: DateTime.parse(json['publishedAt'] as String),
      documentPath: json['documentPath'] as String,
    );
  }

  final String listCode;
  final int version;
  final int itemCount;
  final String contentHash;
  final bool isHierarchical;
  final String? rootItemCode;
  final int? rootCountryVersion;
  final DateTime publishedAt;
  final String documentPath;
}

/// Mirrors the list-document JSON body produced by
/// `com.sfbank.bayanati.reference.domain.ReferenceDocumentGenerator` — the exact bytes
/// `contentHash` is computed over. Parsed only AFTER the received bytes have been hashed and
/// found to match the manifest's `contentHash` (docs/components/reference-data.md's one
/// integrity primitive) — never before.
class ReferenceDocumentDto {
  const ReferenceDocumentDto({
    required this.listCode,
    required this.version,
    required this.itemCount,
    required this.nameAr,
    required this.nameEn,
    required this.isHierarchical,
    required this.rootItemCode,
    required this.items,
  });

  factory ReferenceDocumentDto.fromJson(Map<String, dynamic> json) {
    return ReferenceDocumentDto(
      listCode: json['listCode'] as String,
      version: json['version'] as int,
      itemCount: json['itemCount'] as int,
      nameAr: json['nameAr'] as String,
      nameEn: json['nameEn'] as String,
      isHierarchical: json['isHierarchical'] as bool,
      rootItemCode: json['rootItemCode'] as String?,
      items: (json['items'] as List<dynamic>)
          .map((e) => ReferenceDocumentItemDto.fromJson(e as Map<String, dynamic>))
          .toList(),
    );
  }

  final String listCode;
  final int version;
  final int itemCount;
  final String nameAr;
  final String nameEn;
  final bool isHierarchical;
  final String? rootItemCode;
  final List<ReferenceDocumentItemDto> items;
}

/// One item within a `ReferenceDocumentDto` — exactly `ref.reference_item`'s served shape.
class ReferenceDocumentItemDto {
  const ReferenceDocumentItemDto({
    required this.itemCode,
    required this.parentCode,
    required this.labelAr,
    required this.labelEn,
    required this.searchAr,
    required this.searchEn,
    required this.sortOrdinal,
    required this.isActive,
    required this.extraJson,
  });

  factory ReferenceDocumentItemDto.fromJson(Map<String, dynamic> json) {
    return ReferenceDocumentItemDto(
      itemCode: json['itemCode'] as String,
      parentCode: json['parentCode'] as String?,
      labelAr: json['labelAr'] as String,
      // labelEn/searchEn ARE nullable on the wire, observed live (2026-09-01) against a real
      // published `branch` document: several branches have no English name seeded, so
      // label_en/search_en (generated from it) are both null for those rows. Not the common
      // case (occupation/country/etc. always carry both), but real for `branch` today.
      labelEn: json['labelEn'] as String?,
      searchAr: json['searchAr'] as String,
      searchEn: json['searchEn'] as String?,
      sortOrdinal: json['sortOrdinal'] as int,
      isActive: json['isActive'] as bool,
      extraJson: json['extra'] == null ? null : jsonEncode(json['extra']),
    );
  }

  final String itemCode;
  final String? parentCode;
  final String labelAr;
  final String? labelEn;
  final String searchAr;
  final String? searchEn;
  final int sortOrdinal;
  final bool isActive;

  /// Re-encoded back to a JSON string for storage (drift stores it as text) — never otherwise
  /// interpreted or transformed by app logic.
  final String? extraJson;
}
