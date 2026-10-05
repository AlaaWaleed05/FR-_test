/// Wire models and retention for journey Stage 10
/// (`backend/.../liveness/web/LivenessController`).
///
/// The error contract is NOT here — it is shared with Stages 11 and 12 in
/// `core/journey/journey_error.dart`, because all three controllers emit overlapping codes.
library;

/// What `POST /api/v1/liveness/token` returns.
///
/// **No nonce.** `FaceTokenResponse` carries no nonce; the backend mints none for face sessions.
/// See `UqudoFaceScanner.faceSession`.
class FaceTokenIssuance {
  const FaceTokenIssuance({
    required this.profileId,
    required this.accessToken,
    required this.faceSessionId,
    required this.usableUntil,
  });

  final String profileId;

  /// Held in a local only for the duration of one launch. **Never stored, never logged.** Minted at
  /// the moment the customer taps to begin, because the face session and its uploaded reference
  /// image are deleted by Uqudo after 600 seconds — a much tighter clock than the enrolment
  /// token's 1800.
  final String accessToken;

  final String faceSessionId;

  /// When this whole issuance stops working (BL-114(a), S8-15) — the server's own answer.
  ///
  /// **On this stage that is the FACE SESSION's deadline, not the token's**, and the difference is
  /// the reason the backend resolves it rather than the app: Uqudo deletes the session and its
  /// reference image 600 s after creation, while the access token lives about 1800 s. An app that
  /// reasoned from the token would be wrong by roughly three times.
  ///
  /// **The backend sends this and NOTHING IN THIS APP READS IT YET** — see
  /// `TokenIssuance.usableUntil` for the reuse it was built for and why that was not shipped.
  final DateTime usableUntil;
}

/// What `POST /api/v1/liveness/result` returns — the outcome of one submitted face JWS.
class FaceResult {
  const FaceResult({
    required this.profileId,
    required this.passed,
    required this.matchLevel,
    this.blockedUntil,
  });

  final String profileId;

  /// Liveness AND face match both satisfied. `true` sends the customer to Stage 11.
  final bool passed;

  /// 1–5 as Uqudo scores it. **Never displayed to the customer** — customer.md Stage 10 is explicit
  /// that "the customer sees one outcome: it worked, or it did not", while the profile and audit
  /// trail record the detail. Carried here because the wire carries it, not because a screen wants
  /// it.
  final int matchLevel;

  /// Non-null only when THIS failed attempt also exhausted the budget
  /// (`FaceResultResponse`'s own doc). This is why the `/result` path needs no follow-up probe,
  /// unlike `/terminated` — see `LivenessRepository.reportTerminated`.
  final DateTime? blockedUntil;
}

/// A launched face session that ended without a JWS, carrying everything the screen needs to
/// decide what it costs and to report it.
///
/// **Why the repository re-wraps the SDK's own two exceptions into this one.** `POST
/// /api/v1/liveness/terminated` requires the `faceSessionId`, and only the repository has it — it
/// mints the token internally, at the moment of use, and deliberately never hands the access token
/// out. Rather than leak the whole issuance to the screen so it can fish one field out, the
/// repository attaches what is needed here.
///
/// **The decision itself stays with the screen**, which is the point: reporting a termination is
/// what SPENDS one of the five attempts, and [cameraUnavailable] is the one case where no attempt
/// is owed. A caller that decides nothing is owed simply does not call
/// `LivenessRepository.reportTerminated`.
class FaceAttemptTerminated implements Exception {
  const FaceAttemptTerminated({
    required this.faceSessionId,
    required this.sdkErrorCode,
    required this.partialJws,
    required this.cameraUnavailable,
  });

  final String faceSessionId;

  /// The SDK's own `SessionStatusCode`, or `appNoSdkResponseCode` when there was no answer to read
  /// one from. Goes verbatim into the audit payload.
  final String sdkErrorCode;

  /// BL-028's partial artifact, raw, or null. Always null on the no-response path.
  final String? partialJws;

  /// The SDK never reached a camera — a device-permission problem, not a liveness attempt.
  /// **No attempt is owed**, so the screen does not report this one.
  final bool cameraUnavailable;

  @override
  String toString() => 'FaceAttemptTerminated($sdkErrorCode)';
}

/// A face capture waiting for an acknowledgement.
///
/// Mirrors `RetainedScan`'s role at Stage 8 (BL-034): the JWS is retained BEFORE the upload is
/// attempted, so a lost acknowledgement becomes a retry rather than a repeated face check —
/// customer.md Stage 13's "Scan or liveness succeeded, upload failed ... the app retains it and
/// retries the upload on reconnect".
///
/// Two differences from the scan's version, both load-bearing:
///
/// 1. **There is no `documentType`**, so the staleness guard is `profileId` alone — see
///    `Stage10Screen`'s entry guard for why no [faceSessionId] comparison is possible there.
/// 2. **The usable window is ~600 seconds, not two hours.** Uqudo deletes the face session and its
///    reference image at 600 s (`uqudo-sdk.md:364`, customer.md:806-808), roughly a twelfth of the
///    scan's retry window. Retry copy on screen must reflect that rather than reusing Stage 8's.
class RetainedFaceCapture {
  const RetainedFaceCapture({
    required this.profileId,
    required this.faceSessionId,
    required this.jws,
  });

  final String profileId;
  final String faceSessionId;

  /// The raw JWS compact string, forwarded untouched. **Never decoded on the device** (CLAUDE.md)
  /// and never logged.
  final String jws;
}

/// Process-lifetime holder for [RetainedFaceCapture]. One mutable slot behind a Riverpod
/// `Provider`, so it survives screen rebuilds and Stage 10's internal state changes while dying
/// with the process.
///
/// **In-memory only, never persisted** — the same architectural decision as `RetainedScanStore`.
/// Writing a face artifact to `session.sqlite` would put biometric material on disk under a
/// retention rule nobody has written. An app restart legitimately loses the capture.
///
/// **Abandonment is deliberately not handled here.** `EntryRepository` owns abandonment and knows
/// nothing about this store, so a capture can outlive the session it belongs to for the life of the
/// process. Rather than reach across that boundary, the guard lives where the stale value would
/// actually be used: `Stage10Screen` re-offers a retained capture only when its `profileId` still
/// matches what is on screen, and discards it otherwise. Same shape as the fix
/// `@agent-reviewer` found for `RetainedScanStore` at S5-07.
class RetainedFaceStore {
  RetainedFaceCapture? _retained;

  RetainedFaceCapture? get retained => _retained;

  void keep(RetainedFaceCapture capture) => _retained = capture;

  /// Called on every terminal outcome for a capture: accepted, `LIVENESS_REJECTED`,
  /// `ARTIFACT_EXPIRED` and `AUDIT_TRAIL_UNAVAILABLE`. Anything else — most importantly
  /// `BackendUnreachableException` — leaves it in place, because anything else is a retryable
  /// upload.
  void clear() => _retained = null;
}
