#!/usr/bin/env python3
"""Generates the Design_3 brand assets the app bundles, from the supplied handoff artwork.

Source of truth: Design_3/assets/ (product-owner handoff, see PROJECT_PLAN.md AD-012).

**This is now the ONLY generator of the app's mark**, in every place the mark appears.
generate_brand_assets.py -- which derived the octagonal emblem from the JPEG master and owned the
launcher icons -- was deleted when the launcher moved to the pearl (product-owner ruling
2026-09-12, reversing the previous session's "leave it"). Keeping it would have left two scripts
each claiming to produce the app's identity from a different source, which is exactly how the
launcher came to disagree with the splash in the first place.

NEVER hand-edit the PNGs this writes -- edit this script and re-run it (CLAUDE.md).

What it writes:
  mobile/assets/brand/sfb-pearl.png           circular pearl mark, splash + banner
  mobile/assets/brand/sfb-wordmark-navy.png   wordmark for paper grounds -- the SPLASH's only
  mobile/assets/brand/sfb-wordmark-white.png  the navy-ground variant; generated but NOT bundled
  android/.../mipmap-<density>/ic_launcher.png             legacy launcher, pearl on navy
  android/.../mipmap-<density>/ic_launcher_foreground.png  adaptive layer, pearl on transparency

WHY THE LAUNCHER IS HERE AND NOT IN ITS OWN SCRIPT: the launcher icon, the Android 12+ native
splash icon and the Flutter splash must all show the SAME mark, and the only way to guarantee
that is for one file to produce all three from one source. `values-v31/styles.xml` points
`windowSplashScreenAnimatedIcon` at `@mipmap/ic_launcher_foreground`, so the native splash picks
up whatever this writes -- it is not a fourth thing to remember.

WHY DOWNSIZED: the supplied pearl is 1318x1318 / 1.1 MB, for a mark drawn at 112 dp on the
splash and 34 dp in the banner. At a 3x device pixel ratio the largest real need is 336 px.
512 is kept as headroom for a 4x display and still cuts the asset by roughly 90%, which is
APK size the customer pays for on a Sudanese mobile connection.

WHY THE WHITE WORDMARK IS GENERATED BUT NOT BUNDLED: the handoff's banner draws it on the navy
bar, but this app's banner does not -- AD-012's documented adaptation gives that width to the
screen title instead, because every screen here has one and the handoff's dashboard did not. It
is still emitted so the asset exists the moment that adaptation is revisited, and it is left out
of pubspec.yaml's `assets:` so it costs the customer no APK bytes meanwhile. The two wordmarks
are different artwork, not one asset recoloured at runtime -- each was extracted from a
different supplied screenshot.

WHY THE RASTER IS SHIPPED AS SUPPLIED: the handoff states these wordmarks are extractions
from JPEG screenshots and asks for the vector originals from the bank's brand team before
release. That ask is the product owner's and has not been made -- so this script does not
pretend to clean them up, it only resizes. Tracked in AD-012.

Requires: Python 3 and `pillow` (a build-time tool -- this adds NO Flutter/pub package).
Run from the repo root:  python mobile/tool/generate_design3_assets.py
"""

from __future__ import annotations

import pathlib

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[2]
HANDOFF = ROOT / "Design_3" / "assets"
BRAND_ASSETS = ROOT / "mobile" / "assets" / "brand"
RES = ROOT / "mobile" / "android" / "app" / "src" / "main" / "res"

# AD-012's brand ground. Declared again in android/.../values/colors.xml, which every Android
# resource reads; this copy paints the LEGACY raster, which cannot reference a colour resource.
LAUNCHER_GROUND = (0x0B, 0x1C, 0x47)

# Android density buckets. mdpi is the 1x baseline.
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

LEGACY_DP = 48  # a legacy launcher icon is 48 dp
ADAPTIVE_DP = 108  # an adaptive icon layer is 108 dp ...
SAFE_DP = 66  # ... of which only the central 66 dp is guaranteed visible under any mask

# A CIRCLE inscribed in a square wastes its corners, so the pearl carries a higher fill than the
# octagonal emblem it replaced (0.72) -- at the old value it read as a small dot on a navy tile.
LEGACY_FILL = 0.86
# The adaptive layer must stay inside the safe zone whatever mask the launcher applies. The pearl
# is already a circle, so a circular mask costs it nothing; a squircle or square mask simply shows
# navy around it.
ADAPTIVE_FILL = (SAFE_DP / ADAPTIVE_DP) * 0.98

# 112 dp (splash) at a 4x device pixel ratio, rounded to a power of two.
PEARL_PX = 512
# Unbundled (see the docstring). Kept at the handoff's 36 dp banner height x 4.
WORDMARK_WHITE_PX = 144
# Splash C draws the navy wordmark at 210 dp WIDE, which is ~69 dp high at its aspect ratio;
# 280 covers 4x.
WORDMARK_NAVY_PX = 280


def _load(name: str) -> Image.Image:
    path = HANDOFF / name
    if not path.exists():
        raise SystemExit(
            f"missing handoff asset: {path}\n"
            "Design_3/ is the product-owner handoff and is expected to be present."
        )
    return Image.open(path).convert("RGBA")


def write_pearl() -> None:
    """Resize the circular mark, preserving its existing alpha clip.

    LANCZOS rather than the default: this is a photographic mark being reduced by ~60%, and
    a box filter leaves the camel rider's fine strokes muddy at 46 dp in the banner.
    """
    pearl = _load("sfb-logo-circle.png")
    if pearl.width != pearl.height:
        raise SystemExit(f"expected a square mark, got {pearl.size}")
    out = pearl.resize((PEARL_PX, PEARL_PX), Image.LANCZOS)
    target = BRAND_ASSETS / "sfb-pearl.png"
    out.save(target, optimize=True)
    print(f"  {target.relative_to(ROOT)}  {PEARL_PX}x{PEARL_PX}  {target.stat().st_size // 1024} KB")


def write_wordmark(name: str, height_px: int) -> None:
    """Resize one wordmark to a fixed HEIGHT, keeping its aspect ratio.

    Height-driven because both consumers lay it out by height -- the banner beside the mark,
    the splash above the progress hairline -- so deriving width from a fixed height is what
    keeps the proportions right at every size either draws it.
    """
    wordmark = _load(name)
    scale = height_px / wordmark.height
    size = (max(1, round(wordmark.width * scale)), height_px)
    out = wordmark.resize(size, Image.LANCZOS)
    target = BRAND_ASSETS / name
    out.save(target, optimize=True)
    print(f"  {target.relative_to(ROOT)}  {size[0]}x{size[1]}  {target.stat().st_size // 1024} KB")


def _centred(mark: Image.Image, canvas: int, fill: float, background) -> Image.Image:
    """Scale `mark` to `fill` of the canvas and centre it on a square canvas."""
    size = max(1, round(canvas * fill))
    scaled = mark.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), background)
    out.paste(scaled, ((canvas - size) // 2, (canvas - size) // 2), scaled)
    return out


def write_launcher_icons() -> None:
    """Both launcher layers, at every density, from the pearl.

    The legacy raster bakes the navy ground because a pre-API-26 launcher draws the PNG as-is and
    cannot reference @color/brand_blue. The adaptive foreground is transparent: the adaptive icon's
    own <background> layer supplies the navy, so baking it here would double it and defeat any
    parallax the launcher applies between the layers.
    """
    pearl = _load("sfb-logo-circle.png")
    for bucket, scale in DENSITIES.items():
        out_dir = RES / f"mipmap-{bucket}"
        out_dir.mkdir(parents=True, exist_ok=True)

        legacy = round(LEGACY_DP * scale)
        _centred(pearl, legacy, LEGACY_FILL, (*LAUNCHER_GROUND, 255)).save(
            out_dir / "ic_launcher.png"
        )

        adaptive = round(ADAPTIVE_DP * scale)
        _centred(pearl, adaptive, ADAPTIVE_FILL, (0, 0, 0, 0)).save(
            out_dir / "ic_launcher_foreground.png"
        )
        print(f"  mipmap-{bucket}: ic_launcher {legacy}px, ic_launcher_foreground {adaptive}px")


def main() -> None:
    BRAND_ASSETS.mkdir(parents=True, exist_ok=True)
    print("Design_3 brand assets ->")
    write_pearl()
    write_wordmark("sfb-wordmark-white.png", WORDMARK_WHITE_PX)
    write_wordmark("sfb-wordmark-navy.png", WORDMARK_NAVY_PX)
    print("launcher icons (pearl, AD-012 ruling 2026-09-12) ->")
    write_launcher_icons()


if __name__ == "__main__":
    main()
