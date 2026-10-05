import 'dart:convert';
import 'dart:typed_data';

import 'package:mobile/core/network/manifest_dto.dart';
import 'package:mobile/core/network/reference_api.dart';

/// Hand-written test double — matches this codebase's own stub-not-mock culture
/// (`fru.core-banking.client=stub`, etc.) rather than pulling in a mocking framework for a
/// two-method interface.
class FakeReferenceApi implements ReferenceApi {
  ManifestDto? manifestToServe;
  final Map<String, Uint8List> bytesByListVersion = {};
  final Map<String, Object> throwOnFetch = {};

  int fetchManifestCallCount = 0;
  int fetchListBytesCallCount = 0;

  @override
  Future<ManifestDto?> fetchManifest({String? ifNoneMatch}) async {
    fetchManifestCallCount++;
    final manifest = manifestToServe;
    if (manifest != null && ifNoneMatch == '"${manifest.catalogHash}"') {
      return null; // simulates a real 304 — the repository must decide when to send this header
    }
    return manifest;
  }

  @override
  Future<Uint8List> fetchListBytes(String listCode, int version) async {
    fetchListBytesCallCount++;
    final key = '$listCode/$version';
    if (throwOnFetch.containsKey(key)) {
      throw throwOnFetch[key]!;
    }
    final bytes = bytesByListVersion[key];
    if (bytes == null) {
      throw StateError('FakeReferenceApi: no bytes registered for $key');
    }
    return bytes;
  }
}

/// Builds the exact JSON shape `ReferenceDocumentGenerator` produces on the backend, so tests
/// exercise the real parse path.
Uint8List buildDocumentBytes({
  required String listCode,
  required int version,
  String nameAr = 'قائمة',
  String nameEn = 'List',
  bool isHierarchical = false,
  String? rootItemCode,
  required List<Map<String, Object?>> items,
}) {
  final json = {
    'listCode': listCode,
    'version': version,
    'itemCount': items.length,
    'nameAr': nameAr,
    'nameEn': nameEn,
    'isHierarchical': isHierarchical,
    'rootItemCode': rootItemCode,
    'items': items,
  };
  return Uint8List.fromList(utf8.encode(jsonEncode(json)));
}

Map<String, Object?> buildItem({
  required String itemCode,
  String? parentCode,
  required String labelAr,
  String? labelEn = 'Label',
  String? searchAr,
  String? searchEn,
  required int sortOrdinal,
  bool isActive = true,
}) {
  return {
    'itemCode': itemCode,
    'parentCode': parentCode,
    'labelAr': labelAr,
    'labelEn': labelEn,
    'searchAr': searchAr ?? labelAr,
    'searchEn': searchEn ?? labelEn,
    'sortOrdinal': sortOrdinal,
    'isActive': isActive,
    'extra': null,
  };
}
