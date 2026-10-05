import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/dataentry/dio_data_entry_api.dart';

/// Simulates the backend without a real socket, exercising the real `DioDataEntryApi` — mirrors
/// `dio_entry_api_test.dart`'s `_FakeAdapter` pattern.
class _FakeAdapter extends Interceptor {
  Map<String, Object?>? lastStage6Body;

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    if (options.path.endsWith('/api/v1/data-entry/stage6')) {
      lastStage6Body = Map<String, Object?>.from(options.data as Map);
    }
    handler.resolve(
      Response(requestOptions: options, statusCode: 200, data: <String, Object?>{}),
    );
  }
}

void main() {
  late Dio dio;
  late DioDataEntryApi api;
  late _FakeAdapter adapter;

  setUp(() {
    adapter = _FakeAdapter();
    dio = Dio(BaseOptions(baseUrl: 'http://unit-test.invalid'))..interceptors.add(adapter);
    api = DioDataEntryApi(dio);
  });

  Future<void> submit({bool? attached}) => api.submitStage6(
    profileId: 'p1',
    employer: 'Acme',
    countryCode: 'SD',
    stateCode: '11',
    localityCode: '1101',
    city: 'Halfa',
    area: 'Area',
    street: 'Street',
    block: 'Block',
    salaryCertificateAttached: attached,
  );

  // BL-122. The repository and screen tests both stop at the API interface, so without this
  // nothing exercises the real Dio serialisation of the new key — a field that never reached the
  // JSON body would pass every other test in the change, and the backend would then read every
  // profile as a decline.
  group('BL-122 — the salary-certificate claim reaches the wire', () {
    test('a true claim is serialised into the stage 6 body', () async {
      await submit(attached: true);

      expect(adapter.lastStage6Body!['salaryCertificateAttached'], isTrue);
    });

    test('a false claim is sent explicitly, since the backend treats it as a correction', () async {
      await submit(attached: false);

      expect(adapter.lastStage6Body!.containsKey('salaryCertificateAttached'), isTrue);
      expect(adapter.lastStage6Body!['salaryCertificateAttached'], isFalse);
    });

    test('the rest of the stage 6 body is unchanged by the new field', () async {
      await submit(attached: true);

      expect(adapter.lastStage6Body!['profileId'], 'p1');
      expect(adapter.lastStage6Body!['employer'], 'Acme');
      expect(adapter.lastStage6Body!['stateCode'], '11');
      expect(adapter.lastStage6Body!['localityCode'], '1101');
    });
  });
}
