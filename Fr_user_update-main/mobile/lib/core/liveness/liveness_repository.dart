import '../journey/journey_error.dart';
import '../journey/journey_models.dart';
import '../journey/journey_repository.dart';
import 'liveness_api.dart';
import 'liveness_models.dart';
import 'uqudo_face_scanner.dart';

/// Stage 10's testable core, depending only on [LivenessApi] + [UqudoFaceScanner] +
/// [JourneyRepository] + [RetainedFaceStore]. Mirrors `IdentityScanRepository`'s own separation.
///
/// **No offline queue**, same reason as Stages 8-9: after Stage 7's boundary every step consumes a
/// real Uqudo operation, and a queued face check is a contradiction in terms. Every method
/// propagates `BackendUnreachableException` to a screen.
class LivenessRepository {
  LivenessRepository({
    required LivenessApi api,
    required UqudoFaceScanner scanner,
    required JourneyRepository journey,
    required RetainedFaceStore retainedFaces,
    Duration faceTimeout = uqudoFaceNoAnswerTimeout,
  }) : _api = api,
       _scanner = scanner,
       _journey = journey,
       _retainedFaces = retainedFaces,
       _faceTimeout = faceTimeout;

  final LivenessApi _api;
  final UqudoFaceScanner _scanner;
  final JourneyRepository _journey;
  final RetainedFaceStore _retainedFaces;

  /// Injectable only so a test can prove the timeout without waiting two real minutes; every
  /// production construction takes the default. See [uqudoFaceNoAnswerTimeout] for the duration's
  /// reasoning.
  final Duration _faceTimeout;

  /// The capture waiting for an acknowledgement, or null. A screen reads this to decide between
  /// offering "retry the upload" and offering "try the check again".
  RetainedFaceCapture? get retainedFace => _retainedFaces.retained;

  /// Discards a retained capture that can no longer be re-sent as-is. See [RetainedFaceStore] for
  /// why the staleness guard lives in the screen rather than here.
  void discardRetainedFace() => _retainedFaces.clear();

  Future<String> requireProfileId() => _journey.requireProfileId();

  /// One whole liveness attempt: mint a token, launch the face session, retain what it returns,
  /// upload it.
  ///
  /// The failures are deliberately NOT collapsed, because they cost the customer different things:
  ///
  /// - the token call failing is connectivity and costs **nothing** (customer.md Stage 10: "Token
  ///   or upload failure → connectivity, not liveness. Not counted");
  /// - [UqudoFaceFailure] means a launched session produced no JWS — the caller decides whether to
  ///   spend the attempt via [reportTerminated], which is the only thing that actually spends it;
  /// - [UqudoFaceNoResponse] means the session was launched and never answered either way. It was
  ///   still launched, so it costs the same as a cancel;
  /// - a failed upload leaves the JWS retained, so [retryUpload] can finish the job without
  ///   repeating the check.
  Future<FaceResult> runFaceCheck() async {
    final profileId = await requireProfileId();

    // Minted at the point of use. Held in this local only — never stored, never logged.
    final issuance = await _api.issueToken(profileId);

    // The timeout wraps the faceSession await and NOTHING ELSE, which is load-bearing rather than
    // stylistic — the same reasoning F-1 settled for the scan seam. `Future.timeout` does not
    // cancel the future it wraps, it only stops listening to it. A wrap placed further out (around
    // this whole method, or at the screen's call site) would let a late-arriving JWS still run
    // `keep()` below into the process-scoped store and still fire `_upload` in the background,
    // while the screen had already spent the attempt via `/terminated` and moved on. That leaves an
    // orphaned upload racing a terminated attempt, and a retained capture that Stage 10's entry
    // guard would accept on the next visit — same profileId, same faceSessionId — offering a
    // re-send of a session the backend was told was abandoned. Scoped here, the throw happens
    // before anything is retained and a late JWS is simply dropped.
    final String jws;
    try {
      jws = await _scanner
          .faceSession(
            accessToken: issuance.accessToken,
            faceSessionId: issuance.faceSessionId,
          )
          .timeout(
            _faceTimeout,
            onTimeout: () => throw const UqudoFaceNoResponse(),
          );
    } on UqudoFaceFailure catch (e) {
      throw FaceAttemptTerminated(
        faceSessionId: issuance.faceSessionId,
        // A null code means the error object could not be parsed at all. The endpoint requires a
        // non-blank string, and "the SDK said something we could not read" is nearer to "no answer"
        // than to any real status code, so it reuses the app-minted token rather than inventing a
        // second one.
        sdkErrorCode: e.code ?? appNoSdkResponseCode,
        partialJws: e.partialJws,
        cameraUnavailable: e.isCameraUnavailable,
      );
    } on UqudoFaceNoResponse {
      throw FaceAttemptTerminated(
        faceSessionId: issuance.faceSessionId,
        sdkErrorCode: appNoSdkResponseCode,
        // Necessarily null: there is no PlatformException on this path, so there is no `data` field
        // for BL-028's partial artifact to arrive in. The two terminated paths are not symmetric.
        partialJws: null,
        cameraUnavailable: false,
      );
    }

    // Retained BEFORE the upload is attempted. That ordering is the whole retry mechanism: if the
    // acknowledgement is lost, this is the difference between a retry and a repeated face check.
    _retainedFaces.keep(
      RetainedFaceCapture(
        profileId: profileId,
        faceSessionId: issuance.faceSessionId,
        jws: jws,
      ),
    );

    return _upload(_retainedFaces.retained!);
  }

  /// Re-post the retained capture unchanged.
  ///
  /// Throws [StateError] if nothing is retained, which is a caller bug: a screen offers this only
  /// when [retainedFace] is non-null.
  Future<FaceResult> retryUpload() {
    final retained = _retainedFaces.retained;
    if (retained == null) {
      throw StateError('no retained face capture to retry');
    }
    return _upload(retained);
  }

  /// The single upload path, shared by the first attempt and every retry so they cannot drift.
  Future<FaceResult> _upload(RetainedFaceCapture capture) async {
    try {
      final result = await _api.submitResult(
        profileId: capture.profileId,
        faceSessionId: capture.faceSessionId,
        jws: capture.jws,
      );
      // A `passed: false` is a completed, judged attempt — the capture is spent either way, and
      // re-sending it would be judged identically.
      _retainedFaces.clear();
      return result;
    } on LivenessRejectedException {
      // Examined and refused, and an attempt is gone. Retrying the same bytes would fail
      // identically and spend another.
      _retainedFaces.clear();
      rethrow;
    } on JourneyConflictException catch (e) {
      // customer.md Stage 13: the app must "distinguish retrying an upload from this capture can no
      // longer be used". These two codes are that second case — Uqudo deletes the face session and
      // its image at 600 s, and a JWS that still verifies perfectly is still unusable without them.
      // Every other conflict leaves the capture retained.
      if (e.code == JourneyCode.artifactExpired ||
          e.code == JourneyCode.auditTrailUnavailable) {
        _retainedFaces.clear();
      }
      rethrow;
    }
    // Everything else — most importantly BackendUnreachableException — leaves the capture in place,
    // which is what makes the retry possible at all.
  }

  /// Records that a launched face session ended without a usable JWS, then finds out whether that
  /// was the attempt that triggered the 24-hour block.
  ///
  /// **Why the follow-up read exists.** `POST /terminated` answers `AckResponse(profileId)` and
  /// nothing else. `LivenessService` computes `blockTriggered`, applies the block and returns
  /// normally, and its `activeBlockUntil` is populated only from the *pre-check* branch — so the
  /// call that spends the fifth attempt is indistinguishable on the wire from the one that spends
  /// the second. Without this read the customer would be shown "try again", tap it, and only then
  /// be told they are blocked for 24 hours. Stage 8 gets this for free because its next `/token`
  /// answers a coded `SCAN_BLOCKED`; Stage 10's `/token` would too, but only after the customer has
  /// tapped, which is the experience being avoided.
  ///
  /// **The probe is `POST /submission/current`, and it must not be `/token`.** `/token` mints a real
  /// Uqudo Face Session (`LivenessService.java:200`) and writes `pending_face_session_id`; the
  /// pointer read issues two plain `SELECT`s and changes nothing.
  ///
  /// Returns the block deadline when the customer is now blocked, else null. A failure of the probe
  /// itself is swallowed: the attempt HAS been recorded by then, and turning a successful
  /// `/terminated` into a visible error because a follow-up read failed would misreport what
  /// happened. The customer sees the ordinary "it did not work" screen and discovers the block on
  /// their next tap, which is exactly the pre-probe behaviour — degraded, not broken.
  Future<DateTime?> reportTerminated({
    required String faceSessionId,
    required String sdkErrorCode,
    String? partialJws,
  }) async {
    final profileId = await requireProfileId();
    await _api.reportTerminated(
      profileId: profileId,
      faceSessionId: faceSessionId,
      sdkErrorCode: sdkErrorCode,
      partialJws: partialJws,
    );
    try {
      final pointer = await _journey.currentPointer();
      return pointer.stage == JourneyStage.livenessBlocked
          ? pointer.blockedUntil
          : null;
    } on Exception {
      return null;
    }
  }
}
