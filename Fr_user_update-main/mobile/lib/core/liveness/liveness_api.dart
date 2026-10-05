import 'liveness_models.dart';

/// Transport boundary for journey Stage 10's endpoints
/// (`backend/.../liveness/web/LivenessController`).
///
/// **Every method can throw the shared Stage 10-12 set** — see `core/journey/journey_error.dart`.
/// One of them is specific to this stage and is the reason the mapper cannot switch on status
/// alone: a `400` carrying `code: LIVENESS_REJECTED` means the backend examined the capture,
/// refused it, and **spent one of the customer's five attempts**; an uncoded `400` means the
/// request was malformed and spent nothing.
abstract class LivenessApi {
  /// `POST /token` — mints a Uqudo face-session access token plus the backend's own
  /// `faceSessionId`.
  ///
  /// **Called at the moment the customer taps to begin, never earlier.** The face session and its
  /// reference image live 600 seconds; a token minted on arriving at the preparation screen would
  /// be dead by the time a customer who read the guidance tapped.
  ///
  /// **This mints a real Uqudo operation** (`LivenessService` calls `createFaceSession` and writes
  /// `pending_face_session_id`), which is exactly why it must never be used as a "where am I" probe.
  /// That job belongs to `POST /api/v1/submission/current`.
  ///
  /// Spends no attempt itself. A blocked profile is refused here BEFORE the Uqudo call, with
  /// `LIVENESS_BLOCKED` and `blockedUntil`.
  Future<FaceTokenIssuance> issueToken(String profileId);

  /// `POST /result` — submit the face JWS for verification.
  ///
  /// The JWS goes up raw and untouched. A `passed: false` result **has already spent an attempt**,
  /// and carries `blockedUntil` when that attempt was the fifth.
  Future<FaceResult> submitResult({
    required String profileId,
    required String faceSessionId,
    required String jws,
  });

  /// `POST /terminated` — record that a launched session ended without a usable JWS.
  ///
  /// **This is what spends the attempt** on the no-JWS paths, not the SDK failure itself, so a
  /// caller that decides no attempt is owed simply does not call it (see
  /// `UqudoFaceFailure.isCameraUnavailable`).
  ///
  /// [sdkErrorCode] is required and unvalidated by the backend — see `appNoSdkResponseCode`.
  /// [partialJws] is BL-028's optional artifact, forwarded raw; null on the timeout path, which has
  /// no SDK error object to carry one.
  ///
  /// **Answers a bare ack.** It does not say whether this call triggered the 24-hour block, which
  /// is why `LivenessRepository.reportTerminated` follows it with a pointer read.
  Future<void> reportTerminated({
    required String profileId,
    required String faceSessionId,
    required String sdkErrorCode,
    String? partialJws,
  });
}
