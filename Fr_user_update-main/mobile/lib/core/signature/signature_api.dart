import 'signature_models.dart';

/// Transport boundary for journey Stage 11's endpoint
/// (`backend/.../signature/web/SignatureController`).
///
/// **Every method can throw the shared Stage 10-12 set** — see `core/journey/journey_error.dart`.
/// Stage 11's own code is `SIGNATURE_REJECTED`, a `400`. Unlike Stage 10's coded `400` it costs
/// **nothing**: Stage 11 has no attempt budget, which `SignatureController` states explicitly.
abstract class SignatureApi {
  /// `POST /api/v1/signature`.
  ///
  /// **Base64 JSON, not multipart** — consistent with how this backend already carries the Uqudo
  /// JWS as a JSON string; multipart has no precedent anywhere in the codebase.
  ///
  /// **No client idempotency key, and none is needed.** `JdbcSignatureRepository` upserts on
  /// `ON CONFLICT (profile_id) WHERE kind = 'signature'`, so a re-submission replaces rather than
  /// duplicating. Idempotency is structural here, as it is across this backend.
  ///
  /// Refuses with `LIVENESS_REQUIRED` unless Stage 10 has passed — which is also why customer.md
  /// disables Back on this screen.
  Future<void> submit({
    required String profileId,
    required CapturedSignature signature,
  });
}
