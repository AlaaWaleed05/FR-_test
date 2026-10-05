import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:mobile/core/images/image_downscale.dart';
import 'package:mobile/core/signature/signature_models.dart';

/// Stage 11's upload route, at the only layer a test on this machine can reach.
///
/// Opening the camera or the gallery goes through a platform channel no test here can drive —
/// which is why Stage 6's own picker is untested too. What is testable, and what actually matters,
/// is the rule applied to the bytes that come back. This is the half BL-060's "unreachable from
/// the mobile app" justification rests on, so it is proven rather than asserted in a comment.
void main() {
  Uint8List jpegOf(int width, int height) =>
      Uint8List.fromList(img.encodeJpg(img.Image(width: width, height: height), quality: 90));

  test('accepts an ordinary photo and re-encodes it as an uploaded JPEG', () {
    final result = signatureFromPickedBytes(jpegOf(800, 600));

    expect(result.failure, isNull);
    expect(result.capture!.method, SignatureCaptureMethod.uploaded);
    expect(result.capture!.contentType, 'image/jpeg');
  });

  test('downscales a large photo to the shared 1600 px long edge', () {
    final result = signatureFromPickedBytes(jpegOf(4000, 3000));

    final decoded = img.decodeImage(result.capture!.bytes)!;
    expect(decoded.width, maxImageDimension);
    expect(decoded.height, 1200);
  });

  test('resizes a PORTRAIT photo on its height — the S5-05 bug, still guarded after the lift', () {
    final decoded = img.decodeImage(signatureFromPickedBytes(jpegOf(1200, 4000)).capture!.bytes)!;

    expect(decoded.height, maxImageDimension);
    expect(decoded.width, lessThan(1200));
  });

  test('the ceiling is applied to the ORIGINAL picked bytes, at Stage 6\'s 10 MB', () {
    // Same measurement and same value as `SalaryCertificateField`. Not a signature-specific rule,
    // and deliberately not tighter: a signature photo is exactly as large as any other phone photo.
    final result = signatureFromPickedBytes(Uint8List(maxPickedImageBytes + 1));

    expect(result.failure, SignaturePickFailure.tooLarge);
    expect(result.capture, isNull);
  });

  test('undecodable bytes are distinguished from oversized ones — different copy', () {
    final result = signatureFromPickedBytes(Uint8List.fromList([1, 2, 3, 4]));

    expect(result.failure, SignaturePickFailure.unreadable);
    expect(result.capture, isNull);
  });

  test('what reaches the wire is far below the backend ceiling — why BL-060 is unreachable here', () {
    // The band between SignatureService's 5 MB (coded SIGNATURE_REJECTED) and
    // SignatureController's ~5.72 MB base64 cap (a BARE, uncoded 400) is a real backend defect.
    // Downscaling before encoding is what puts it out of reach from this app: a 4000x3000 photo,
    // near the top of what a phone camera produces, comes out orders of magnitude under it.
    final result = signatureFromPickedBytes(jpegOf(4000, 3000));

    expect(result.capture!.bytes.length, lessThan(backendSignatureMaxBytes));
    // And under the controller's base64 cap too, which is the one that answers uncoded.
    expect(result.capture!.bytes.length * 4 / 3, lessThan(8000000));
  });
}
