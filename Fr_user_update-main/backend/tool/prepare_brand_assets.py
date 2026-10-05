"""Derive the printed form's brand assets from the design sources.

WHY THIS EXISTS RATHER THAN A HAND-TRIMMED PNG. `Design_3/assets/sfb-wordmark-navy.png` is the
bank name drawn in its logo's own calligraphy -- navy on transparent, two lines: the Arabic
«البنك السوداني الفرنسي» above "SUDANESE FRENCH BANK". The printed form's header takes the ARABIC
LINE ONLY (product-owner ruling, AD-022 (h), 2026-09-18), which means cropping. A crop done by eye
in an image editor is a number nobody can check and nobody can reproduce when the source is
redrawn; this script derives it from the image's own alpha channel, so re-running it against a new
source produces the right crop instead of the old one's coordinates.

It is NOT a font. Setting the bank's name as text in any installed typeface gives a different
shape from the logo and is the wrong answer -- that is the whole reason the header carries an
image here and text everywhere else.

HOW THE CROP IS DERIVED. Count non-transparent pixels per row; the rows split into contiguous ink
bands separated by fully empty runs. The topmost band is the Arabic line. Its own column extent
trims the side whitespace at the same time. At the 2026-09-18 source that is rows 8..136 of 207
and columns 7..623 of 631 -- 617x129, ratio 4.78 -- but those numbers are OUTPUT, not input.

WHY WIDE MATTERS. The header band is 17mm on every page and the form is asserted at exactly two
pages (BL-162), so height in the header is the scarce resource. Cropping to the Arabic line alone
drops a 3.05:1 image to 4.78:1: at a given height it is wider and, more to the point, it is not
carrying a second line of Latin text that would force the whole band taller.

Requires: Python 3, Pillow (a build-time tool, and NOT a backend dependency -- nothing in the jar
imports this).

Run from the repository root:  python backend/tool/prepare_brand_assets.py
"""

from __future__ import annotations

import pathlib
import sys

try:
    from PIL import Image
except ImportError:  # pragma: no cover - a developer-machine setup error, not a runtime path
    sys.exit("Pillow is required: pip install Pillow")

# parents[2] is the repository root: backend/tool/ -> backend/ -> repo.
ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "Design_3" / "assets" / "sfb-wordmark-navy.png"
DEST = ROOT / "backend" / "src" / "main" / "resources" / "brand" / "sfb-wordmark-ar-navy.png"

# A pixel counts as ink above this alpha. Not zero: PNG anti-aliasing leaves a skirt of nearly
# transparent pixels around the strokes, and counting those merges the two lines into one band.
ALPHA_FLOOR = 8


def ink_bands(alpha: Image.Image) -> list[tuple[int, int]]:
    """Contiguous runs of rows containing ink, top to bottom."""
    width, height = alpha.size
    px = alpha.load()
    bands: list[tuple[int, int]] = []
    start: int | None = None
    for y in range(height):
        has_ink = any(px[x, y] > ALPHA_FLOOR for x in range(width))
        if has_ink and start is None:
            start = y
        elif not has_ink and start is not None:
            bands.append((start, y - 1))
            start = None
    if start is not None:
        bands.append((start, height - 1))
    return bands


def main() -> None:
    if not SOURCE.exists():
        sys.exit(f"missing design source: {SOURCE}")

    image = Image.open(SOURCE).convert("RGBA")
    alpha = image.getchannel("A")
    bands = ink_bands(alpha)

    # Two bands is what the source has and what the crop means. One band would mean the Latin line
    # is gone (already cropped, or the source was redrawn); three or more means the rows split
    # somewhere unexpected. Either way the "topmost band is the Arabic line" assumption no longer
    # holds, and guessing would silently ship the wrong mark.
    if len(bands) != 2:
        sys.exit(
            f"expected 2 ink bands (Arabic line, Latin line), found {len(bands)}: {bands}. "
            "The source has changed shape -- re-read it before trusting this crop."
        )

    top, bottom = bands[0]
    px = alpha.load()
    columns = [
        x
        for x in range(image.width)
        if any(px[x, y] > ALPHA_FLOOR for y in range(top, bottom + 1))
    ]
    box = (min(columns), top, max(columns) + 1, bottom + 1)
    cropped = image.crop(box)

    DEST.parent.mkdir(parents=True, exist_ok=True)
    cropped.save(DEST, format="PNG", optimize=True)

    ratio = cropped.width / cropped.height
    print(f"source {SOURCE.name}: {image.width}x{image.height}, bands {bands}")
    print(f"crop box {box} -> {cropped.width}x{cropped.height}, ratio {ratio:.4f}")
    print(f"written {DEST.relative_to(ROOT)} ({DEST.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
