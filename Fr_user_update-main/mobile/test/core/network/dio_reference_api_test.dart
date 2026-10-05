import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/network/dio_reference_api.dart';

/// Simulates the backend without a real socket: intercepts every request and resolves a
/// synthetic response. Exercises the REAL `DioReferenceApi` code (URL construction, header
/// handling, response-type handling), unlike `FakeReferenceApi`, which replaces this layer
/// entirely for `ReferenceRepository`'s own tests.
class _FakeAdapter extends Interceptor {
  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    if (options.path.endsWith('/api/v1/reference/manifest')) {
      if (options.headers['If-None-Match'] == '"same-hash"') {
        handler.resolve(
          Response(requestOptions: options, statusCode: 304),
        );
        return;
      }
      handler.resolve(
        Response(
          requestOptions: options,
          statusCode: 200,
          data: {
            'catalogHash': 'same-hash',
            'generatedAt': '2026-01-01T00:00:00Z',
            'lists': [
              {
                'listCode': 'occupation',
                'version': 1,
                'itemCount': 1,
                'contentHash': 'abc',
                'isHierarchical': false,
                'rootItemCode': null,
                'rootCountryVersion': null,
                'publishedAt': '2026-01-01T00:00:00Z',
                'documentPath': '/api/v1/reference/lists/occupation/1',
              },
            ],
            'verifiableChannels': ['sms'],
          },
        ),
      );
      return;
    }

    if (options.path.endsWith('/api/v1/reference/lists/occupation/1')) {
      final bytes = utf8.encode(
        jsonEncode({
          'listCode': 'occupation',
          'version': 1,
          'itemCount': 1,
          'nameAr': 'a',
          'nameEn': 'b',
          'isHierarchical': false,
          'rootItemCode': null,
          'items': [],
        }),
      );
      handler.resolve(
        Response(requestOptions: options, statusCode: 200, data: bytes),
      );
      return;
    }

    handler.reject(DioException(requestOptions: options, message: 'unexpected path'));
  }
}

void main() {
  late Dio dio;
  late DioReferenceApi api;

  setUp(() {
    dio = Dio(BaseOptions(baseUrl: 'http://unit-test.invalid'))..interceptors.add(_FakeAdapter());
    api = DioReferenceApi(dio);
  });

  test('fetchManifest parses a 200 response into a ManifestDto', () async {
    final manifest = await api.fetchManifest();
    expect(manifest, isNotNull);
    expect(manifest!.catalogHash, 'same-hash');
    expect(manifest.lists.single.listCode, 'occupation');
  });

  test('fetchManifest surfaces a 304 as null, never as an error', () async {
    final manifest = await api.fetchManifest(ifNoneMatch: '"same-hash"');
    expect(manifest, isNull);
  });

  test('fetchListBytes returns the raw bytes, unparsed', () async {
    final bytes = await api.fetchListBytes('occupation', 1);
    expect(bytes, isA<Uint8List>());
    final decoded = parseReferenceDocument(bytes);
    expect(decoded.listCode, 'occupation');
  });
}
