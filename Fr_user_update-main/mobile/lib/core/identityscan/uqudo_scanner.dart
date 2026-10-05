/// The device-side boundary around the Uqudo SDK.
///
/// **Why this interface exists at all.** The SDK does not run on an x86_64 emulator and supports
/// `armeabi-v7a`/`arm64-v8a` only (customer.md Stage 8's own note), so no automated test on this
/// machine can ever invoke it. Every widget test overrides this seam with a fake, exactly as the
/// stage 3-6 tests override `DataEntryApi`. That is also precisely why the live on-device run is
/// owed and is not replaced by green gates: what these tests prove is the Dart wiring around the
/// SDK, never the SDK itself.
library;

/// A launched SDK session that ended without a JWS.
///
/// [code] is the `SessionStatusCode` the SDK reported — `USER_CANCEL`, `SESSION_EXPIRED`,
/// `UNEXPECTED_ERROR`, `REQUEST_TIMEOUT`, `SESSION_INVALIDATED_CAMERA_NOT_AVAILABLE`,
/// `SESSION_INVALIDATED_CAMERA_PERMISSION_NOT_GRANTED`, or null when the error object could not be
/// parsed at all.
///
/// Note what is NOT in this list: scan-quality problems. A printed, on-screen or tampered document
/// is rejected in-SDK or arrives as a perfectly ordinary JWS that the backend then judges, never as
/// an error code (`docs/components/uqudo-sdk.md`: "journey stage 8's 'scan failed' mostly arrives
/// as USER_CANCEL or as a JWS the backend then judges"). So this exception means "no capture
/// happened", not "the capture was bad".
class UqudoScanFailure implements Exception {
  const UqudoScanFailure(this.code);

  final String? code;

  /// The two codes that mean the SDK never got as far as a camera.
  ///
  /// These are the one class of failure Stage 8 does **not** spend a retry attempt on. customer.md
  /// makes a cancel cost an attempt because "a launched SDK session consumes a real Uqudo
  /// operation whether or not a document was captured" — but a session that died on a missing
  /// camera permission consumed no Uqudo operation, and charging the customer an attempt for a
  /// device-permission problem they can fix in Settings would burn a 3-attempt budget on something
  /// that is not a scan at all. The screen therefore shows a distinct "grant camera access" state
  /// and does not call `/cancel`.
  bool get isCameraUnavailable =>
      code == 'SESSION_INVALIDATED_CAMERA_NOT_AVAILABLE' ||
      code == 'SESSION_INVALIDATED_CAMERA_PERMISSION_NOT_GRANTED';

  @override
  String toString() => 'UqudoScanFailure(${code ?? 'unparseable'})';
}

/// A launched SDK session whose native side never answered at all — neither a JWS nor an error.
///
/// **Deliberately not a [UqudoScanFailure].** That type means the SDK *told* us the session ended
/// without a JWS and carries the `SessionStatusCode` it reported. This one means we never heard
/// anything back, which is a different fact and has to be visible as one at the catch site.
///
/// **Why it exists (F-1). Read the two halves separately — one is proven, one is not.**
///
/// Proven from source: the pinned `uqudosdk_flutter` 3.10.0 plugin handles Android's
/// `RESULT_CANCELED` only `if (data != null)`, with no `else` branch, so a `RESULT_CANCELED`
/// carrying a null Intent would complete its `pendingResult` neither with a success nor with an
/// error. Nothing would throw, and every Stage 8 error arm keys off a throw, so the screen would
/// spin indefinitely.
///
/// NOT proven: that the Uqudo scan activity ever actually returns that shape. A null Intent is the
/// ordinary Android result of a system back-press out of an activity that does not set a result
/// before finishing — but the scan activity lives in the native AAR, not in the pub package, so
/// whether it sets one cannot be determined from source.
/// `docs/sessions/2026-09-05-s5-07-device-run.md` records this as unproven-not-disproven, and the
/// hang has never been observed on hardware because S1-02 never cancelled a scan. **The device run
/// still owes that observation.**
///
/// The timeout is the right defence either way, which is why it was written before the observation:
/// it converts a silent native side into a handled outcome whatever the cause. Its reach is the
/// enrol seam, not the whole app — it is applied by `IdentityScanRepository.runScan`, so a future
/// native call (S5-08's face session) needs its own.
///
/// **Accepted residual, unfixable from Dart.** That same plugin never nulls `pendingResult` after
/// completing it. A very late `onActivityResult` arriving after this timeout has already fired
/// could therefore deliver the abandoned session's result against a retry's handle. Narrow in
/// practice — Android delivers `onActivityResult` before the next `startActivityForResult` —
/// and closing it would mean editing pinned third-party Kotlin, which CLAUDE.md forbids.
/// Recorded as BL-053 rather than worked around.
class UqudoScanNoResponse implements Exception {
  const UqudoScanNoResponse();

  @override
  String toString() => 'UqudoScanNoResponse(no answer from the Uqudo SDK)';
}

/// How long to wait for the SDK to answer before treating its silence as [UqudoScanNoResponse].
///
/// **180s is a deliberately generous margin, not a precise figure.** The only latency measurement
/// that exists anywhere is 52s tap-to-completion on the exact target handset — the 2 GB / API 28
/// Huawei Y5 2019 of `docs/sessions/2026-09-03-s1-02-device-spike.md:3`, measured at `:46` — and
/// that 52s already included one SDK-prompted blur retry. It is n=1: one document, one operator
/// who knew the flow, and the SDK's own internal retry loop has no known ceiling. 180s is ~3.5x
/// that single observation.
///
/// The margin is asymmetric on purpose. A false trip is expensive and silent: it spends a real
/// customer's attempt via `/cancel` AND discards the capture they are still producing, from behind
/// the SDK's own full-screen UI where they see nothing wrong. A generous timeout only delays
/// surfacing a rare hang. Those costs are not comparable, so the duration errs long.
const uqudoScanNoAnswerTimeout = Duration(minutes: 3);

/// Launches the Uqudo document-scan flow and returns the raw JWS.
abstract class UqudoScanner {
  /// Runs one enrolment. Returns the **raw JWS compact string, untouched** — the device never
  /// decodes it, never inspects it and never extracts anything from it (CLAUDE.md: signature
  /// verification and parsing are server-side only; the `../FIB` reference does the opposite and
  /// must not be copied).
  ///
  /// [documentType] is the journey's own vocabulary (`passport`/`national_id`). The translation to
  /// Uqudo's `DocumentType` enum happens inside the implementation and nowhere else.
  ///
  /// Throws [UqudoScanFailure] when the session ended without a JWS.
  Future<String> enroll({
    required String accessToken,
    required String sessionId,
    required String nonce,
    required String documentType,
  });
}
