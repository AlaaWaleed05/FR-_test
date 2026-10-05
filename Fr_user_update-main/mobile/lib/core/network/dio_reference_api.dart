import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';

import 'manifest_dto.dart';
import 'reference_api.dart';

/// The real `ReferenceApi`, backed by `dio`. See `ReferenceApi`'s doc comment for why the list
/// fetch returns raw bytes rather than a parsed body.
class DioReferenceApi implements ReferenceApi {
  DioReferenceApi(this._dio);

  final Dio _dio;

  @override
  Future<ManifestDto?> fetchManifest({String? ifNoneMatch}) async {
    final response = await _dio.get<Map<String, dynamic>>(
      '/api/v1/reference/manifest',
      options: Options(
        headers: ifNoneMatch == null ? null : {'If-None-Match': ifNoneMatch},
        validateStatus: (status) => status != null && (status == 200 || status == 304),
      ),
    );
    if (response.statusCode == 304) {
      return null;
    }
    return ManifestDto.fromJson(response.data!);
  }

  @override
  Future<Uint8List> fetchListBytes(String listCode, int version) async {
    final response = await _dio.get<List<int>>(
      '/api/v1/reference/lists/$listCode/$version',
      options: Options(responseType: ResponseType.bytes),
    );
    return Uint8List.fromList(response.data!);
  }
}

/// Parses the raw, hash-verified bytes of a list document into a [ReferenceDocumentDto]. Kept
/// separate from [DioReferenceApi] so the parse step is reachable from tests with no network at
/// all, and to make the "hash first, parse second" ordering visible at the call site rather than
/// implicit inside the HTTP client.
ReferenceDocumentDto parseReferenceDocument(Uint8List bytes) {
  final json = jsonDecode(utf8.decode(bytes)) as Map<String, dynamic>;
  return ReferenceDocumentDto.fromJson(json);
}
