import 'package:dio/dio.dart';

import 'entry_api.dart';
import 'entry_models.dart';

/// The real [EntryApi], backed by `dio`. Same shape as `DioReferenceApi`: raw calls, explicit
/// status-code-to-exception mapping, connection-class [DioException]s re-thrown as
/// [BackendUnreachableException] so callers can tell "the backend said no" from "the backend
/// could not be reached" — customer.md Stage 0's offline-vs-rejected distinction depends on it.
class DioEntryApi implements EntryApi {
  DioEntryApi(this._dio);

  final Dio _dio;

  static const _connectionErrorTypes = {
    DioExceptionType.connectionError,
    DioExceptionType.connectionTimeout,
    DioExceptionType.receiveTimeout,
    DioExceptionType.sendTimeout,
  };

  @override
  Future<AccountCheckResult> checkAccount(String branch, String accountNumber) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/v1/account-check',
        data: {'branch': branch, 'accountNumber': accountNumber},
      );
      final body = response.data!;
      return AccountCheckResult(
        outcome: AccountOutcome.values.byName((body['outcome'] as String).toLowerCase()),
        continuation: AccountContinuation.values.byName(
          (body['continuation'] as String).toLowerCase(),
        ),
        requestId: body['requestId'] as String,
        // Present only on BLOCKED (BL-021). Parsed defensively: a malformed instant must not turn
        // a block into a crash, because the screen behind it renders a null deadline correctly.
        blockedUntil: DateTime.tryParse(body['blockedUntil'] as String? ?? '')?.toLocal(),
      );
    } on DioException catch (e) {
      throw _mapAccountCheckError(e);
    }
  }

  @override
  Future<ContactChannelsResult> submitContactChannels({
    required String branch,
    required String accountNumber,
    required String phoneNumber,
    required bool sms,
    required bool whatsapp,
    String? emailAddress,
  }) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/v1/contact-channels',
        data: {
          'branch': branch,
          'accountNumber': accountNumber,
          'phoneNumber': phoneNumber,
          'sms': sms,
          'whatsapp': whatsapp,
          'emailAddress': emailAddress,
        },
      );
      final body = response.data!;
      final channels = (body['channels'] as List<dynamic>)
          .cast<Map<String, dynamic>>()
          .map(
            (c) => ChannelSummary(
              channel: c['channel'] as String,
              state: ChannelState.values.byName(c['state'] as String),
              maskedDestination: c['maskedDestination'] as String,
            ),
          )
          .toList();
      return ContactChannelsResult(profileId: body['profileId'] as String, channels: channels);
    } on DioException catch (e) {
      throw _mapContactChannelsError(e);
    }
  }

  @override
  Future<OtpVerifyResult> verifyChannel({
    required String profileId,
    required String channel,
    required String code,
  }) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/v1/otp/verify',
        data: {'profileId': profileId, 'channel': channel, 'code': code},
      );
      final body = response.data!;
      final blockedUntil = body['sessionBlockedUntilIso'] as String?;
      return OtpVerifyResult(
        channel: body['channel'] as String,
        outcome: _decodeVerifyOutcome(body['outcome'] as String),
        state: ChannelState.values.byName(body['state'] as String),
        sessionBlockedUntil: blockedUntil == null ? null : DateTime.parse(blockedUntil),
      );
    } on DioException catch (e) {
      throw _mapOtpError(e);
    }
  }

  @override
  Future<OtpResendResult> resendChannel({
    required String profileId,
    required String channel,
    String? correctedEmailAddress,
  }) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/v1/otp/resend',
        // The key is present only when a correction was actually made, so an ordinary resend
        // sends exactly the two-field body it always has. `OtpResendRequest.correctedEmailAddress`
        // is nullable server-side and a missing key deserialises to null identically to an
        // explicit `null`, but omitting it keeps the wire proof-readable: a body carrying the key
        // means the customer edited something.
        data: {
          'profileId': profileId,
          'channel': channel,
          'correctedEmailAddress': ?correctedEmailAddress,
        },
      );
      final body = response.data!;
      final secondsUntilAllowed = body['secondsUntilAllowed'] as int;
      return OtpResendResult(
        channel: body['channel'] as String,
        outcome: _decodeResendOutcome(body['outcome'] as String),
        maskedDestination: body['maskedDestination'] as String,
        secondsUntilAllowed: secondsUntilAllowed < 0 ? null : secondsUntilAllowed,
      );
    } on DioException catch (e) {
      throw _mapOtpError(e);
    }
  }

  /// Backend outcome strings are Java enum names (`SCREAMING_CASE`, `OtpVerificationController`'s
  /// `result.outcome().name()`), not the single-word wire values `.byName(.toLowerCase())` handles
  /// elsewhere in this file — an explicit switch is required.
  static OtpVerifyOutcome _decodeVerifyOutcome(String wireValue) {
    switch (wireValue) {
      case 'VERIFIED':
        return OtpVerifyOutcome.verified;
      case 'WRONG_CODE':
        return OtpVerifyOutcome.wrongCode;
      case 'EXPIRED':
        return OtpVerifyOutcome.expired;
      case 'CHANNEL_LOCKED':
        return OtpVerifyOutcome.channelLocked;
      default:
        throw FormatException('unrecognised verify outcome: $wireValue');
    }
  }

  static OtpResendOutcome _decodeResendOutcome(String wireValue) {
    switch (wireValue) {
      case 'ISSUED':
        return OtpResendOutcome.issued;
      case 'CAP_EXHAUSTED':
        return OtpResendOutcome.capExhausted;
      case 'TOO_SOON':
        return OtpResendOutcome.tooSoon;
      case 'CHANNEL_LOCKED':
        return OtpResendOutcome.channelLocked;
      case 'ALREADY_VERIFIED':
        return OtpResendOutcome.alreadyVerified;
      default:
        throw FormatException('unrecognised resend outcome: $wireValue');
    }
  }

  Exception _mapOtpError(DioException e) {
    if (_connectionErrorTypes.contains(e.type)) {
      return BackendUnreachableException(e.message ?? e.type.name);
    }
    if (e.response?.statusCode == 400) {
      return const UnknownOtpChannelException();
    }
    return e;
  }

  Exception _mapAccountCheckError(DioException e) {
    if (_connectionErrorTypes.contains(e.type)) {
      return BackendUnreachableException(e.message ?? e.type.name);
    }
    // S3-02: the backend answers 503 when the bank's core-banking middleware gives no usable
    // answer (its own "System Error", a timeout, an unreadable reply). Any 5xx is "the backend
    // could not answer", the same situation as a dropped connection -- so `launchDecision()`
    // resumes offline rather than surfacing a raw error, and the entry screen shows its
    // "could not reach the server, try again" state.
    final status = e.response?.statusCode;
    if (status != null && status >= 500) {
      return BackendUnreachableException('HTTP $status');
    }
    return e;
  }

  Exception _mapContactChannelsError(DioException e) {
    if (_connectionErrorTypes.contains(e.type)) {
      return BackendUnreachableException(e.message ?? e.type.name);
    }
    switch (e.response?.statusCode) {
      case 400:
        return const ContactChannelsRejectedException();
      case 409:
        return const ProfileAlreadyCompleteException();
      case 429:
        return const SessionTemporarilyBlockedException();
      default:
        return e;
    }
  }
}
