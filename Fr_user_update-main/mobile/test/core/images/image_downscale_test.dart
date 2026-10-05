import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:mobile/core/images/image_downscale.dart';

void main() {
  group(
    'imageResizeTarget (moved from salary_certificate_field_test at S5-08 with the code)',
    () {
      test('returns null when the longer edge is already within the limit', () {
        expect(imageResizeTarget(800, 600, maxDimension: 1600), isNull);
        expect(imageResizeTarget(1600, 900, maxDimension: 1600), isNull);
      });

      test(
        'resizes a landscape image on its width — found under review, S5-05: the original '
        'guard only ever compared width',
        () {
          final target = imageResizeTarget(3200, 1600, maxDimension: 1600);
          expect(target, (width: 1600, height: 800));
        },
      );

      test(
        'resizes a PORTRAIT image on its height, not its (already-small) width — the exact bug '
        'found under review: comparing width alone left a tall image untouched',
        () {
          final target = imageResizeTarget(1200, 4000, maxDimension: 1600);
          expect(target!.height, 1600);
          expect(
            target.width,
            lessThan(1200),
          ); // scaled down proportionally, not left at 1200
        },
      );

      test('a square image over the limit resizes both edges equally', () {
        final target = imageResizeTarget(3000, 3000, maxDimension: 1600);
        expect(target, (width: 1600, height: 1600));
      });
    },
  );

  test('maxPickedImageBytes is customer.md Stage 6\'s stated 10 MB', () {
    expect(maxPickedImageBytes, 10 * 1024 * 1024);
  });

  test('maxImageDimension is the long-edge ceiling both callers share', () {
    expect(maxImageDimension, 1600);
    // The default must equal the constant, or Stage 11 and Stage 6 would silently diverge.
    expect(imageResizeTarget(3200, 1600), (width: 1600, height: 800));
  });

  group('downscaleToJpeg answers null for anything it cannot read', () {
    test('malformed bytes that make the decoder THROW, not return null', () {
      // Found by test at S5-08. `img.decodeImage` probes formats in turn, and `PsdDecoder`'s
      // validity check reads a uint16 past the end of a 4-byte buffer — a RangeError, not a null.
      // Before the catch was added this propagated out of Stage 6's and Stage 11's tap handlers as
      // an unhandled async error instead of "this file could not be read".
      expect(downscaleToJpeg(Uint8List.fromList([1, 2, 3, 4])), isNull);
    });

    test('empty bytes', () {
      expect(downscaleToJpeg(Uint8List(0)), isNull);
    });

    test('a valid image still round-trips', () {
      final png = Uint8List.fromList(img.encodePng(img.Image(width: 10, height: 10)));
      expect(downscaleToJpeg(png), isNotNull);
    });
  });
}
