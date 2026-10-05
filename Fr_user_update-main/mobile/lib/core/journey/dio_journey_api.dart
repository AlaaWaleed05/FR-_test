import 'package:dio/dio.dart';

import 'journey_api.dart';
import 'journey_error.dart';
import 'journey_models.dart';

/// The real [JourneyApi], backed by `dio`. Same shape as every other client in this app: raw
/// calls, hand-written decoding, no `json_serializable`, and one shared error mapper
/// ([mapJourneyError]).
class DioJourneyApi implements JourneyApi {
  DioJourneyApi(this._dio);

  final Dio _dio;

  static const _base = '/api/v1/submission';

  @override
  Future<JourneyPointer> currentPointer(String profileId) async {
    final body = await _post('$_base/current', {'profileId': profileId});
    final blockedUntil = body['blockedUntil'];
    return JourneyPointer(
      profileId: body['profileId'] as String,
      stage: JourneyStage.fromWire(body['stage'] as String),
      // Absent for every stage but LIVENESS_BLOCKED, and even there it may be unparseable or
      // already elapsed — handled where it is rendered, never here.
      blockedUntil: blockedUntil is String
          ? DateTime.tryParse(blockedUntil)
          : null,
      referenceNumber: body['referenceNumber'] as String?,
      verifiedChannels:
          (body['verifiedChannels'] as List<dynamic>?)
              ?.cast<String>()
              .toList() ??
          const [],
    );
  }

  @override
  Future<SubmissionReceipt> submit(String profileId) async {
    final body = await _post(_base, {'profileId': profileId});
    // `verifiedChannels` IS on this response and is deliberately not read. See SubmissionReceipt.
    return SubmissionReceipt(
      profileId: body['profileId'] as String,
      referenceNumber: body['referenceNumber'] as String,
      status: body['status'] as String,
    );
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
