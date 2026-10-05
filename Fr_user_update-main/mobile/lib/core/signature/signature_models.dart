/// Journey Stage 11's capture routes and wire values
/// (`backend/.../signature/web/SignatureRequest`).
///
/// **The size and format limits here mirror the backend's and are NOT a settled policy.**
/// customer.md:879 carries an open `[POLICY: signature file size and format limits]` marker, and
/// customer.md:1181-1182 confirms it is still unset. `SignatureService`'s own javadoc calls its
/// 5 MB / PNG-or-JPEG values "an operational placeholder, not a resolved policy". This app mirrors
/// them so a capture is never refused by surprise; it does not present them as settled, and it does
/// not invent anything narrower.
library;

import 'dart:typed_data';

import '../images/image_downscale.dart';

/// customer.md Stage 11's two routes. "Neither is preferred; the customer chooses."
enum SignatureCaptureMethod {
  /// A signature pad with a clear-and-retry control. Exported as PNG.
  drawn('drawn', 'image/png'),

  /// An image from the device's photo library or camera. Downscaled and re-encoded as JPEG, exactly
  /// as Stage 6's salary certificate is.
  uploaded('uploaded', 'image/jpeg');

  const SignatureCaptureMethod(this.wire, this.contentType);

  /// `SignatureService.ALLOWED_CAPTURE_METHODS`.
  final String wire;

  /// `SignatureService.ALLOWED_CONTENT_TYPES`. Fixed per route rather than sniffed: this app
  /// controls the encoding on both paths, so the type is known rather than guessed.
  final String contentType;
}

/// A captured signature, ready to upload.
class CapturedSignature {
  const CapturedSignature({required this.method, required this.bytes});

  final SignatureCaptureMethod method;

  /// The encoded image. **Already downscaled** — see `core/images/image_downscale.dart`.
  final Uint8List bytes;

  String get contentType => method.contentType;
}

/// The backend's decoded-bytes ceiling (`SignatureService.MAX_BYTES`), mirrored for a last-resort
/// client-side check.
///
/// **This is not the limit the customer experiences, and it is not meant to be.** What a customer
/// can pick is governed by `maxPickedImageBytes` (10 MB, the same as Stage 6's attachment), and
/// what actually reaches the wire is whatever the shared downscale produces from it — a 1600 px
/// JPEG, on the order of 100-400 KB. The gap is deliberate: it means the ~5.72 MB point above which
/// `SignatureController`'s base64 length cap answers a BARE, uncoded 400 (rather than a coded
/// `SIGNATURE_REJECTED`) is unreachable from this app, so a customer can never be shown "bad
/// request" for a photograph that was merely large.
///
/// It is applied as a last-resort check in `Stage11Screen._submit` — against a future path that
/// skips the downscale, not as a rule any present path is expected to hit. If it ever fires, the
/// customer gets copy they can act on instead of the backend's bare uncoded 400.
const backendSignatureMaxBytes = 5 * 1024 * 1024;

/// Why a picked image could not become a signature.
enum SignaturePickFailure {
  /// Over [maxPickedImageBytes] as PICKED, before any downscaling — the same measurement and the
  /// same 10 MB ceiling Stage 6's salary certificate applies.
  tooLarge,

  /// The bytes are not a decodable image.
  unreadable,
}

/// The result of turning picked bytes into an uploadable signature.
typedef SignaturePickResult = ({CapturedSignature? capture, SignaturePickFailure? failure});

/// Turns bytes the customer picked into an uploadable signature, applying exactly Stage 6's rules.
///
/// **Extracted from `Stage11Screen` so it can be tested at all.** Everything around it — opening the
/// camera or the gallery — goes through a platform channel that no test on this machine can drive,
/// which is why Stage 6's own picker is untested too. The rules are the part worth proving, and
/// they are the reason BL-060's uncoded-400 band is unreachable from this app: the ceiling is
/// checked on the ORIGINAL bytes, and what survives is a ≤1600 px JPEG two orders of magnitude
/// below the backend's limit.
SignaturePickResult signatureFromPickedBytes(Uint8List bytes) {
  if (bytes.length > maxPickedImageBytes) {
    return (capture: null, failure: SignaturePickFailure.tooLarge);
  }
  final jpeg = downscaleToJpeg(bytes);
  if (jpeg == null) {
    return (capture: null, failure: SignaturePickFailure.unreadable);
  }
  return (
    capture: CapturedSignature(method: SignatureCaptureMethod.uploaded, bytes: jpeg),
    failure: null,
  );
}
