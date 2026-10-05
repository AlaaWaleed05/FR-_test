import 'package:flutter/widgets.dart';

/// The decode width to pass as `cacheWidth` when showing a customer-supplied image on screen.
///
/// D6.3/D9.4, a house rule rather than an optimisation: `Image.memory` with no `cacheWidth`
/// decodes at the file's FULL resolution and holds it as uncompressed ARGB. A modern phone camera
/// image is tens of megabytes decoded — on the 2 GB reference device that is a memory-safety
/// problem, and on newer hardware it is still waste, which is why this applies everywhere and not
/// only to the old phone.
///
/// Deliberately kept OUT of `image_downscale.dart`: that library is pure Dart with no
/// `BuildContext` on purpose, so a plain unit test can exercise all of it. This needs the device
/// pixel ratio, so it belongs at the widget layer instead of breaking that property.
///
/// Sized to the full screen width, since every current call site renders edge to edge inside its
/// padding. Rounding up to the screen is right: it never under-samples a visible image, and it
/// keeps the value independent of a caller's exact box.
int displayDecodeWidth(BuildContext context) {
  final media = MediaQuery.of(context);
  return (media.size.width * media.devicePixelRatio).round();
}
