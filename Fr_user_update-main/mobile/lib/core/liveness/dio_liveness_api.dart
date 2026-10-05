import 'package:dio/dio.dart';

import '../journey/journey_error.dart';
import 'liveness_api.dart';
import 'liveness_models.dart';

/// The real [LivenessApi], backed by `dio`. Raw calls, hand-written decoding, and the shared
/// [mapJourneyError] — see `core/journey/journey_error.dart` for why the mapper is shared across
/// Stages 10-12 rather than copied three times.
class DioLivenessApi implements LivenessApi {
  DioLivenessApi(this._dio);

  final Dio _dio;

  static const _base = '/api/v1/liveness';

  @override
  Future<FaceTokenIssuance> issueToken(String profileId) async {
    final body = await _post('$_base/token', {'profileId': profileId});
    return FaceTokenIssuance(
      profileId: body['profileId'] as String,
      accessToken: body['accessToken'] as String,
      faceSessionId: body['faceSessionId'] as String,
      // BL-114(a). Strict on purpose, and not free -- see DioIdentityScanApi.issueToken for the
      // cost a throw here has already incurred.
      usableUntil: DateTime.parse(body['usableUntil'] as String).toLocal(),
    );
  }

  @override
  Future<FaceResult> submitResult({
    required String profileId,
    required String faceSessionId,
    required String jws,
  }) async {
    final body = await _post('$_base/result', {
      'profileId': profileId,
      'faceSessionId': faceSessionId,
      'jws': jws,
    });
    final blockedUntil = body['blockedUntil'];
    return FaceResult(
      profileId: body['profileId'] as String,
      passed: body['passed'] as bool,
      matchLevel: body['matchLevel'] as int,
      // Nullable by design: non-null only when this call's own failed attempt exhausted the budget.
      blockedUntil: blockedUntil is String
          ? DateTime.tryParse(blockedUntil)
          : null,
    );
  }

  @override
  Future<void> reportTerminated({
    required String profileId,
    required String faceSessionId,
    required String sdkErrorCode,
    String? partialJws,
  }) async {
    await _post('$_base/terminated', {
      'profileId': profileId,
      'faceSessionId': faceSessionId,
      'sdkErrorCode': sdkErrorCode,
      // Omitted rather than sent null when absent. The backend treats absent and blank identically
      // (`LivenessController` nulls a blank before the service sees it), so this is presentation
      // only — but sending the key with a null value invites a future reader to think a null
      // partial means something different from no partial. It does not.
      'partialJws': ?partialJws,
    });
  }

  Future<Map<String, dynamic>> _post(
    String path,
    Map<String, dynamic> data,
  ) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(path, data: data);
      return response.data ?? const {};
    } on DioException catch (e) {
      throw mapJourneyError(e);
    }
  }
}
