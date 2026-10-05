import 'dart:convert';

import 'package:dio/dio.dart';

import '../journey/journey_error.dart';
import 'signature_api.dart';
import 'signature_models.dart';

/// The real [SignatureApi], backed by `dio`.
class DioSignatureApi implements SignatureApi {
  DioSignatureApi(this._dio);

  final Dio _dio;

  static const _path = '/api/v1/signature';

  @override
  Future<void> submit({
    required String profileId,
    required CapturedSignature signature,
  }) async {
    try {
      await _dio.post<Map<String, dynamic>>(
        _path,
        data: {
          'profileId': profileId,
          'captureMethod': signature.method.wire,
          'contentType': signature.contentType,
          // The image bytes are base64'd here and nowhere else. Not logged, at any level: a
          // signature is customer PII exactly as much as an identity document is.
          'contentBase64': base64Encode(signature.bytes),
        },
      );
    } on DioException catch (e) {
      throw mapJourneyError(e);
    }
  }
}
