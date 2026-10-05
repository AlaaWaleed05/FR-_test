import 'dart:typed_data';

import 'package:dio/dio.dart';

import '../entry/entry_models.dart'
    show BackendUnreachableException, ProfileAlreadyCompleteException;
import 'identity_scan_api.dart';
import 'identity_scan_models.dart';

/// The real [IdentityScanApi], backed by `dio`. Same shape as `DioDataEntryApi`: raw calls, one
/// explicit error mapper, hand-written decoding with no `json_serializable`.
///
/// What differs from every other client in this app is [_mapError], and the reason is that this is
/// the only controller emitting a machine-readable `code`. See [_mapError]'s own comment.
class DioIdentityScanApi implements IdentityScanApi {
  DioIdentityScanApi(this._dio);

  final Dio _dio;

  static const _base = '/api/v1/identity-scan';

  /// Same set as `DioDataEntryApi`'s, including `unknown` (a mid-request connection reset on the
  /// network conditions this project targets). Stage 8's upload is the single most expensive call
  /// in the journey to lose — it carries a JWS that cost the customer a real scan — so classifying
  /// a dropped connection as anything other than "unreachable" would turn a retryable upload into
  /// a rescan, which is exactly what BL-034 exists to prevent.
  static const _connectionErrorTypes = {
    DioExceptionType.connectionError,
    DioExceptionType.connectionTimeout,
    DioExceptionType.receiveTimeout,
    DioExceptionType.sendTimeout,
    DioExceptionType.unknown,
  };

  @override
  Future<TokenIssuance> issueToken({
    required String profileId,
    required String documentType,
  }) async {
    final body = await _post('$_base/token', {
      'profileId': profileId,
      'documentType': documentType,
    });
    return TokenIssuance(
      profileId: body['profileId'] as String,
      accessToken: body['accessToken'] as String,
      sessionId: body['sessionId'] as String,
      nonce: body['nonce'] as String,
      documentType: body['documentType'] as String,
      // BL-114(a). Parsed strictly, unlike blockedUntil's defensive tryParse: the field is always
      // present on this response, so absence or garbage is a wire-contract break worth failing
      // loudly on rather than papering over.
      //
      // Be clear about the cost, because it is NOT free: the backend increments the lifetime mint
      // counter before this body is written, so a throw here has already burned one of the twenty.
      // Contained rather than crashing -- Stage 8's catch-all renders the connectivity screen --
      // but it is the same coin BL-114(a) is about. Accepted only because the field is
      // unconditionally present today; if that ever stops being true, make it nullable instead.
      usableUntil: DateTime.parse(body['usableUntil'] as String).toLocal(),
    );
  }

  @override
  Future<ScanDisplay> submitScan({
    required String profileId,
    required String sessionId,
    required String nonce,
    required String documentType,
    required String jws,
  }) async {
    final body = await _post('$_base/scan-result', {
      'profileId': profileId,
      'sessionId': sessionId,
      'nonce': nonce,
      'documentType': documentType,
      'jws': jws,
    });
    return _scanDisplay(body);
  }

  @override
  Future<void> cancelScan({required String profileId, required String documentType}) async {
    await _post('$_base/cancel', {'profileId': profileId, 'documentType': documentType});
  }

  @override
  Future<ScanDisplay> currentReview(String profileId) async {
    return _scanDisplay(await _post('$_base/registry-review/current', {'profileId': profileId}));
  }

  @override
  Future<ScanDisplay> retryRegistryLookup(String profileId) async {
    return _scanDisplay(await _post('$_base/registry-review/retry', {'profileId': profileId}));
  }

  @override
  Future<void> acceptReview(String profileId) async {
    await _post('$_base/registry-review/accept', {'profileId': profileId});
  }

  @override
  Future<DateTime?> reportWrongNumber(String profileId) async {
    final body = await _post('$_base/registry-review/wrong-number', {'profileId': profileId});
    final blockedUntil = body['blockedUntil'];
    // Nullable by design (`WrongNumberResponse`): non-null only when this call's own attempt
    // happened to exhaust the budget.
    return blockedUntil is String ? DateTime.tryParse(blockedUntil) : null;
  }

  @override
  Future<void> reportWrongDetails(String profileId) async {
    await _post('$_base/registry-review/wrong-details', {'profileId': profileId});
  }

  @override
  Future<Uint8List> reviewImage({required String profileId, required String kind}) async {
    try {
      final response = await _dio.get<List<int>>(
        '$_base/image/$kind',
        queryParameters: {'profileId': profileId},
        options: Options(responseType: ResponseType.bytes),
      );
      return Uint8List.fromList(response.data ?? const []);
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Future<Map<String, dynamic>> _post(String path, Map<String, dynamic> data) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(path, data: data);
      return response.data ?? const {};
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  /// Hand-written, field by field, matching this codebase's own decoding idiom. Every `registry*`
  /// field and `dateOfBirth` is read as a nullable `String?` because the backend leaves all of them
  /// null while `registryReady` is false; `nationalNumber` is read as non-null because the parser
  /// fails closed on a missing identity number, so no payload exists without one.
  static ScanDisplay _scanDisplay(Map<String, dynamic> body) {
    return ScanDisplay(
      profileId: body['profileId'] as String,
      cycleId: body['cycleId'] as String,
      documentType: body['documentType'] as String,
      nationalNumber: body['nationalNumber'] as String,
      registryReady: body['registryReady'] as bool,
      availableImageKinds:
          (body['availableImageKinds'] as List<dynamic>?)?.cast<String>().toList() ?? const [],
      nameArGiven: body['nameArGiven'] as String?,
      nameArFather: body['nameArFather'] as String?,
      nameArGrandfather: body['nameArGrandfather'] as String?,
      nameArGreatGrandfather: body['nameArGreatGrandfather'] as String?,
      nameArMother: body['nameArMother'] as String?,
      nameArMotherFather: body['nameArMotherFather'] as String?,
      nameArMotherGrandfather: body['nameArMotherGrandfather'] as String?,
      nameArMotherGreatGrandfather: body['nameArMotherGreatGrandfather'] as String?,
      firstNamesEn: body['firstNamesEn'] as String?,
      lastNameEn: body['lastNameEn'] as String?,
      sexRegistry: body['sexRegistry'] as String?,
      dateOfBirth: body['dateOfBirth'] as String?,
      rawAddressAr: body['rawAddressAr'] as String?,
    );
  }

  /// Five arms, in this order. The order is the contract, not a convenience.
  ///
  /// 1. **Connection-class** — nothing reached the backend.
  /// 2. **Any 5xx** — including `POST /token`'s unmapped `500` when Uqudo's own token endpoint is
  ///    down. customer.md Stage 8 makes that a connectivity failure explicitly **not counted
  ///    against the retry budget**, and it is indistinguishable on the wire from any other 500, so
  ///    it is folded in here and the screen says "no attempt was counted" rather than guessing.
  /// 3. **A body carrying a top-level `code`** — BL-033's eight `409`s and BL-037's one `400`.
  ///    `SCAN_REJECTED` is the whole reason this arm precedes the status-only arm below: it is a
  ///    `400` that SPENT an attempt, and an uncoded `400` is a `400` that spent nothing.
  /// 4. **No code, by status** — the uncoded arm. `409` lands on the existing shared
  ///    [ProfileAlreadyCompleteException] rather than a new type, because that is what
  ///    `/api/v1/data-entry/stage7` (Stage 7's own endpoint, still on the old bare-exception style)
  ///    answers for a terminal profile, and it means the same thing.
  /// 5. **Anything else** — rethrown untouched rather than flattened into a wrong specific error.
  ///
  /// The discriminator between 3 and 4 is the PRESENCE of the code member, never the body's shape:
  /// an uncoded body is `{timestamp,status,error,path}` on a live server and EMPTY under MockMvc,
  /// and treating either as authoritative would break against the other. See
  /// [ScanConflictCode.fromResponseBody].
  Exception _mapError(DioException e) {
    if (_connectionErrorTypes.contains(e.type)) {
      return BackendUnreachableException(e.message ?? e.type.name);
    }

    final status = e.response?.statusCode;
    if (status != null && status >= 500) {
      return BackendUnreachableException('HTTP $status');
    }

    final body = e.response?.data;
    final code = ScanConflictCode.fromResponseBody(body);
    if (code != null) {
      if (status == 400) {
        // Only one 400 is ever coded. Any other coded 400 is a backend change this app version
        // does not know about; treating it as "an attempt was spent" would be a guess in the more
        // alarming direction, so it falls through to the uncoded 400 meaning instead.
        return code == 'SCAN_REJECTED'
            ? const ScanRejectedException()
            : const IdentityScanClientErrorException();
      }
      if (status == 409) {
        final blockedUntil = (body as Map)['blockedUntil'];
        return ScanConflictException(
          ScanConflictCode.fromWire(code),
          // May be absent, unparseable, or an instant already in the past — all three are handled
          // where it is rendered, never here.
          blockedUntil: blockedUntil is String ? DateTime.tryParse(blockedUntil) : null,
        );
      }
    }

    switch (status) {
      case 400:
        return const IdentityScanClientErrorException();
      case 404:
        return const ProfileNotFoundException();
      case 409:
        return const ProfileAlreadyCompleteException();
      default:
        return e;
    }
  }
}
