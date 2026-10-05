/// The device-side boundary around the Uqudo SDK's **face session** (journey Stage 10).
///
/// A sibling of `core/identityscan/uqudo_scanner.dart`, not a reuse of it: the two flows take
/// different configuration, produce different artifacts and fail in different ways. What they share
/// is the reason this interface exists at all — the SDK does not run on an x86_64 emulator and
/// supports `armeabi-v7a`/`arm64-v8a` only, so no automated test on this machine can ever invoke
/// it. Every widget test overrides this seam with a fake. What the gates prove is the Dart wiring
/// around the SDK, never the SDK itself, which is why the live on-device run is owed and is not
/// replaced by green gates.
library;

/// A launched face session that ended without a usable JWS.
///
/// [code] is the `SessionStatusCode` the SDK reported. For the face flow the one that matters is
/// `SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS` — the SDK exhausting its own internal
/// liveness retries — alongside the same generic set the scan flow sees (`USER_CANCEL`,
/// `SESSION_EXPIRED`, `UNEXPECTED_ERROR`, `REQUEST_TIMEOUT`, the two camera ones), or null when the
/// error object could not be parsed at all.
///
/// **What is NOT distinguishable here, and it matters.** customer.md Stage 10 wants liveness
/// failure (environmental, no fraud signal) told apart from face-match failure (the strongest fraud
/// signal the journey produces). The evidence at S1-02 is that the SDK gates a non-matching face
/// INTERNALLY and terminates through this same channel, so an impostor and a dark room can arrive
/// as the same `SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS`. R-016 records that as an
/// accepted Phase 1 residual, retired by product-owner decision on 2026-09-04. [partialJws] is the
/// mitigation, not a fix.
class UqudoFaceFailure implements Exception {
  const UqudoFaceFailure(this.code, {this.partialJws});

  final String? code;

  /// BL-028's partial artifact, raw and untouched, or null.
  ///
  /// With `returnDataForIncompleteSession()` set on the builder, a terminated session that got at
  /// least one facial-recognition attempt in carries a signed partial JWS in the `data` field of the
  /// SDK's `{code, message, task, data}` error object. The app forwards it verbatim and **never
  /// decodes it** (CLAUDE.md: verification and parsing are server-side only) and never logs it.
  ///
  /// Whether it actually carries `face.match:false` is `[UNVERIFIED]` — undocumented by Uqudo, and
  /// closable only by a second person's face (R-016, out of scope here). Worst case it is an audit
  /// artifact without the match detail, which the backend already tolerates:
  /// `LivenessService.reportLivenessTerminated` records `partialJwsStatus` verified/rejected and
  /// never lets a bad partial stop the attempt being counted.
  final String? partialJws;

  /// The two codes that mean the SDK never got as far as a camera.
  ///
  /// The one class of failure Stage 10 does **not** spend an attempt on, for the same reason Stage
  /// 8 does not: a session that died on a missing camera permission consumed no Uqudo operation,
  /// and charging a device-permission problem the customer can fix in Settings against a 5-attempt
  /// budget would be charging them for something that is not a liveness check at all. The screen
  /// shows a distinct "grant camera access" state and does not call `/terminated`.
  bool get isCameraUnavailable =>
      code == 'SESSION_INVALIDATED_CAMERA_NOT_AVAILABLE' ||
      code == 'SESSION_INVALIDATED_CAMERA_PERMISSION_NOT_GRANTED';

  @override
  String toString() => 'UqudoFaceFailure(${code ?? 'unparseable'})';
}

/// A launched face session whose native side never answered at all — neither a JWS nor an error.
///
/// **Stage 10 needs its own, and does not inherit Stage 8's.** F-1's guard is applied by
/// `IdentityScanRepository.runScan` around the enrol await; its reach is that seam, not the app. The
/// underlying defect is shared, though: the pinned `uqudosdk_flutter` 3.10.0 plugin handles
/// Android's `RESULT_CANCELED` only `if (data != null)` with no `else` branch, so a
/// `RESULT_CANCELED` carrying a null Intent would complete its `pendingResult` neither with a
/// success nor with an error. Nothing would throw, every Stage 10 error arm keys off a throw, and
/// the screen would spin forever.
///
/// As with F-1, that the face activity ever actually returns that shape is **not proven** — it
/// lives in the native AAR, not the pub package. The timeout is the right defence either way: it
/// converts a silent native side into a handled outcome whatever the cause.
///
/// **Deliberately not a [UqudoFaceFailure].** That type means the SDK *told* us the session ended
/// and carries what it said; this one means we never heard anything, which is a different fact and
/// has to be visible as one at the catch site. It also has a concrete consequence for BL-028: there
/// is no `PlatformException` on this path, so there is no `data` field and **`partialJws` is
/// necessarily null here**. The two terminated paths are not symmetric.
///
/// Carries the same accepted residual as BL-053: the plugin never nulls `pendingResult` after
/// completing it, so a very late `onActivityResult` could deliver an abandoned session's result
/// against a retry's handle. Unfixable from Dart without editing pinned third-party Kotlin.
class UqudoFaceNoResponse implements Exception {
  const UqudoFaceNoResponse();

  @override
  String toString() =>
      'UqudoFaceNoResponse(no answer from the Uqudo face session)';
}

/// How long to wait for the face SDK to answer before treating its silence as
/// [UqudoFaceNoResponse].
///
/// **120 seconds, reasoned for the face flow — Stage 8's 180 s is NOT transferable.**
///
/// The evidence, all from the S1-02 device spike on the 2 GB / API 28 handset that also produced
/// the 52 s scan figure behind Stage 8's 180 s (`docs/components/uqudo-sdk.md:581-582`):
///
/// - **Observed face latency is 42–49 s**, n=2, own face, both passing. Faster to first frame than
///   the scan, too: SDK screen up 0.9 s after tap versus 3.5 s.
/// - **The SDK's own retry loop is bounded at 3** (`setMaxAttempts(3)`), so the realistic worst
///   case is roughly three times a single attempt rather than the scan's unbounded internal loop.
/// - **There is a hard ceiling the scan flow does not have.** The face session's JWS lives
///   `exp − iat` = 600 s (`uqudo-sdk.md:560`) and Uqudo deletes the session and its reference image
///   at 600 s (`uqudo-sdk.md:364`, customer.md:806-808). Any timeout at or above 600 s is therefore
///   meaningless — the artifact is already dead.
///
/// **Why erring long is safe here specifically.** A JWS that arrives after `exp` is refused by the
/// backend's own check as `ARTIFACT_EXPIRED` and **costs the customer no attempt**
/// (`LivenessService.java:302-312`). So unlike Stage 8, where a false trip both spends an attempt
/// and discards a capture still being produced, the too-long direction here cannot take anything
/// from the customer that the 600 s clock was not already going to take. That collapses the
/// asymmetry F-1 had to reason around and leaves a straightforward interval: comfortably above the
/// 42–49 s observation with room for the internal retries, comfortably below 600 s.
///
/// 120 s is ~2.5× the observed upper figure and ~1/5 of the ceiling.
const uqudoFaceNoAnswerTimeout = Duration(seconds: 120);

/// The `sdkErrorCode` this app sends to `POST /api/v1/liveness/terminated` when the SDK never
/// answered — the [UqudoFaceNoResponse] path.
///
/// **This value is invented by this app, and the backend will accept anything.**
/// `LivenessController.java:74` requires only a non-blank string of at most 64 characters with no
/// control characters; there is no allowlist, and `LivenessService.java:556` writes it verbatim
/// into the audit payload, where it sits beside real Uqudo `SessionStatusCode`s that an operator
/// reads the same way. Nothing else in the system will catch a badly chosen value, so it is chosen
/// deliberately and named here once.
///
/// The `APP_` prefix is the load-bearing part: no Uqudo status code carries it, so an operator
/// reading an audit trail can tell an app-minted token from a vendor one at a glance and will not
/// go looking for it in Uqudo's documentation.
///
/// Stage 8 needed no equivalent — its `/cancel` request carries only `profileId` and
/// `documentType`, so "silent native" and `USER_CANCEL` are indistinguishable there by
/// construction. Stage 10's endpoint requires a code, so this app must supply one.
const appNoSdkResponseCode = 'APP_NO_SDK_RESPONSE';

/// Launches the Uqudo face session and returns the raw JWS.
abstract class UqudoFaceScanner {
  /// Runs one face session. Returns the **raw JWS compact string, untouched** — the device never
  /// decodes it, never inspects it and never extracts anything from it (CLAUDE.md: signature
  /// verification and parsing are server-side only; the `../FIB` reference does the opposite and
  /// must not be copied).
  ///
  /// **There is no `nonce` parameter, and that is not an omission.** The backend mints no nonce for
  /// face sessions: `FaceSessionIssuance` and `FaceTokenResponse` carry `accessToken` +
  /// `faceSessionId` only, migration `V0044` adds only `pending_face_session_id`, and
  /// `UqudoJwsParser.boundFaceClaims` binds on `data.sessionId` alone. The SDK builder does expose
  /// `setNonce`, and `UqudoScanner.enroll` does take one — do not copy either here. AD-002a was
  /// corrected by S5-13 to scope nonce minting to `enroll()`.
  ///
  /// Throws [UqudoFaceFailure] when the session ended without a JWS, [UqudoFaceNoResponse] when it
  /// ended without saying anything at all.
  Future<String> faceSession({
    required String accessToken,
    required String faceSessionId,
  });
}
