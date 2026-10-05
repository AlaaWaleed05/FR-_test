/// Shared image sizing rules for every customer-supplied picture this app uploads.
///
/// **Why this is one place and not two.** Stage 6's salary certificate and Stage 11's signature are
/// the same kind of thing — a file the customer picked from their camera roll or shot just now —
/// and customer.md gives them the same problem: "a modern phone camera produces files far larger
/// than a legible certificate needs". S5-08 lifted these two out of
/// `features/dataentry/salary_certificate_field.dart`, where they were written for Stage 6 at
/// S5-05, so Stage 11 shares the implementation rather than carrying a copy that can drift from it.
/// Behaviour is unchanged for Stage 6; the tests that proved it moved with the code.
///
/// Pure Dart on purpose: no platform channel, no `BuildContext`, so a plain unit test exercises the
/// whole thing.
library;

import 'dart:typed_data';

import 'package:image/image.dart' as img;

/// The ceiling on the bytes the customer PICKED, before any downscaling.
///
/// customer.md Stage 6: "10 MB; JPEG, PNG or PDF." Applied to the original, not to what is
/// eventually uploaded — a legible attachment should never approach it, so tripping this is a
/// rejection of an oversized or wrong file, not a normal path.
///
/// **Stage 11 deliberately uses this same value** rather than a tighter signature-specific one. A
/// signature can be uploaded as a photo, so it is exactly as large as any other phone photo, and
/// inventing a narrower client-side limit would reject files Stage 6 accepts and push a customer
/// toward "sign more simply", which is not a thing to ask of a human being. The backend's own
/// signature ceiling is 5 MB on the DECODED upload (`SignatureService.MAX_BYTES`) — a different
/// measurement at a different point in the pipeline, and one that [downscaleToJpeg] puts two
/// orders of magnitude out of reach. See `docs/sessions/2026-09-05-s5-08-*` for the arithmetic.
const maxPickedImageBytes = 10 * 1024 * 1024;

/// The long-edge ceiling every uploaded image is resized to.
const maxImageDimension = 1600;

/// Pure resize-target calculation, extracted so it is unit-testable with no `image`-package decode
/// or platform channel involved. `null` means "no resize needed."
///
/// Resizes on whichever edge is actually the longer one — found under review, S5-05: the original
/// guard compared `width` alone against [maxDimension], so a portrait image's height (its real long
/// edge) could sail past the limit untouched.
({int width, int height})? imageResizeTarget(
  int width,
  int height, {
  int maxDimension = maxImageDimension,
}) {
  final longerEdge = width > height ? width : height;
  if (longerEdge <= maxDimension) return null;
  if (width >= height) {
    return (
      width: maxDimension,
      height: (height * maxDimension / width).round(),
    );
  }
  return (width: (width * maxDimension / height).round(), height: maxDimension);
}

/// Decodes, downscales via [imageResizeTarget] (preserving aspect ratio, resizing whichever edge is
/// actually longer), and re-encodes as JPEG. Returns `null` when the bytes are not a decodable
/// image, which every caller surfaces as "this file could not be read" rather than crashing.
///
/// **The `catch` is load-bearing, and was found by test at S5-08.** `img.decodeImage` does not
/// merely return null for input it cannot read — on short or malformed bytes it can THROW from
/// inside a format probe (`RangeError` out of `PsdDecoder.isValidFile`, reading past the end of a
/// 4-byte buffer). The version of this code that lived in `SalaryCertificateField` checked only for
/// null, so a truncated or non-image file picked at Stage 6 would have thrown out of the tap
/// handler as an unhandled async error rather than showing "this file could not be read". Neither
/// caller catches it, and no test had ever fed either one genuinely malformed bytes.
///
/// Deliberately broad: the failure modes are a decoder's internal assumptions about a buffer, not a
/// documented exception set, so enumerating types would be guessing at a third-party package's
/// internals. Every failure means the same thing to a customer.
Uint8List? downscaleToJpeg(Uint8List bytes, {int quality = 85}) {
  final img.Image? decoded;
  try {
    decoded = img.decodeImage(bytes);
  } on Object {
    return null;
  }
  if (decoded == null) return null;
  final target = imageResizeTarget(decoded.width, decoded.height);
  final resized = target == null
      ? decoded
      : img.copyResize(decoded, width: target.width, height: target.height);
  return img.encodeJpg(resized, quality: quality);
}
