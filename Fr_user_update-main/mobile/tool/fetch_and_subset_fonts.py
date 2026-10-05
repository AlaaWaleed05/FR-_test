#!/usr/bin/env python3
"""Fetches and Unicode-range-subsets the app's Arabic typefaces into mobile/assets/fonts/.

Decision D3 (docs/sessions/2026-09-07-research-ui-ux-design-plan.md): IBM Plex Sans Arabic,
Regular 400 + SemiBold 600. Its matched Latin is the deciding factor -- nearly every screen
sets Arabic labels beside Latin digits, `SFB-` reference numbers and scanned names, and an
unmatched pair produces a visible family jump mid-line.

AMIRI 400 IS DISPLAY ONLY (AD-012 fork 2, product-owner ruling 2026-09-12). The Design_3
handoff sets the splash name in Amiri at 62 pt. Amiri is a Naskh serif with NO matched
Latin, so it carries the splash lockup and screen headings and nothing else; every label,
field and number stays on IBM Plex Sans Arabic for the reason above. Bundling it does not
make it the body face -- see AppTheme, which wires it to `displayLarge`/`headlineMedium`
only.

TWO THINGS HERE ARE DELIBERATE AND MUST NOT BE "OPTIMISED":

1. SUBSET BY UNICODE RANGE, NEVER BY THE CHARACTERS THIS REPO'S OWN STRINGS USE (D3.3).
   Customer names arrive from the Uqudo scan and the Civil Registry, not from our source,
   and can contain characters no literal in this repo contains. A string-scanned subset
   would render a customer's own name as missing-glyph boxes.

2. `--layout-features='*'` RETAINS ALL OpenType FEATURES. pyftsubset's default feature set
   is not guaranteed to keep `init`/`medi`/`fina`/`rlig`/`ccmp`/`calt`, and dropping those
   is exactly how a connected script's joins break -- the letters render as disconnected
   isolated forms. Verified present in the upstream files: calt ccmp dnom fina frac init
   liga locl medi numr rlig.

Requires: Python 3, `requests`, `fonttools` (a build-time tool, not an app dependency --
this adds NO Flutter/pub package). Network access to fonts.googleapis.com / fonts.gstatic.com.
Licence: SIL Open Font Licence 1.1; OFL.txt is written alongside the fonts.

THE BACKEND GETS ITS OWN COPY, FULL AND UNSUBSETTED (S8-33). `backend/src/main/resources/
fonts/` receives the UPSTREAM .ttf for the two IBM Plex Sans Arabic weights, with no
pyftsubset pass at all. Three reasons, and none of them is a preference:

  * The backend must not read across into `mobile/` -- nothing else does, and a renderer
    that loads its font from another tier's asset directory breaks the moment either tier
    is packaged on its own.
  * Apache FOP (AD-014) SUBSETS TrueType on embed by default, so shipping the full face
    costs nothing in the produced PDF and removes any chance of a customer's name hitting
    a hole this script's Unicode ranges left behind.
  * .ttf, NEVER .otf. FOP extracts CFF data from an OpenType/CFF font and embeds it as
    Type1C, losing the very GSUB layout features that join the script.

Amiri IS copied to the backend, since S9-06. It was not until then, and the reason it was
not still mostly holds: wayfinder ticket 08 decision 2 sets the whole form in IBM Plex Sans
Arabic, and a second backend face that nothing references would be dead weight in the jar.
What changed is that something references it. The printed form's header now carries the
mobile app's name «بياناتي» exactly as the splash screen sets it -- Amiri, and the splash's
decorative tatweel spelling -- which is a product-owner ruling, AD-022 (i), 2026-09-18. The
ticket 08 rule is otherwise untouched: Amiri is used for that ONE span and nothing else.

Amiri is Regular-only here, deliberately (see FAMILIES), and that has a sharp edge at the
FOP end rather than this one: with no 700 face registered, an `fo:` span that inherits
`font-weight="bold"` silently falls back to Times and renders no Arabic at all. fop.xconf
registers the normal triplet only and the form pins that span's weight explicitly.

Run from the repo root:  python mobile/tool/fetch_and_subset_fonts.py
"""

from __future__ import annotations

import io
import pathlib
import re
import sys

OUT = pathlib.Path(__file__).resolve().parents[1] / "assets" / "fonts"
# parents[2] is the repository root: mobile/tool/ -> mobile/ -> repo.
BACKEND_OUT = (
    pathlib.Path(__file__).resolve().parents[2]
    / "backend"
    / "src"
    / "main"
    / "resources"
    / "fonts"
)
# Which families the backend PDF renderer embeds, full and unsubsetted. See the module
# docstring; keep this a subset of FAMILIES' basenames.
BACKEND_FAMILIES = {"IBMPlexSansArabic", "Amiri"}

# One entry per bundled family: (css query, asset basename, [(style name, weight), ...],
# OFL source). Kept as data rather than duplicated code so a third family cannot drift from
# the two subsetting rules above.
FAMILIES = [
    (
        "IBM+Plex+Sans+Arabic:wght@400;600",
        "IBMPlexSansArabic",
        [("Regular", 400), ("SemiBold", 600)],
        "ibmplexsansarabic",
    ),
    # Regular only: Amiri is used at display sizes where its own weight is sufficient, and a
    # bold Naskh at 62 pt reads as heavy rather than emphatic.
    ("Amiri:wght@400", "Amiri", [("Regular", 400)], "amiri"),
]
# A non-browser UA is what makes Google Fonts serve the FULL STATIC .ttf per weight. A
# browser UA gets woff/woff2 split into dynamic per-unicode-range slices, which is the
# opposite of what we want to bundle.
UA = {"User-Agent": "python-requests"}

# Arabic + its supplements and presentation forms, Basic Latin (digits, `FRU-`, Latin names
# from the scan) and General Punctuation (the em-dash, and the range the FSI/PDI isolate
# characters U+2068/U+2069 that `LtrValue` uses sit in -- those two are invisible bidi
# FORMAT characters resolved by the layout algorithm and correctly carry no glyph of their
# own, so do not read their absence from the subset's cmap as a missing-coverage defect).
UNICODES = ",".join(
    [
        "U+0000-00FF",  # Basic Latin + Latin-1 Supplement
        "U+0600-06FF",  # Arabic
        "U+0750-077F",  # Arabic Supplement
        "U+08A0-08FF",  # Arabic Extended-A
        "U+2000-206F",  # General Punctuation (incl. FSI U+2068 / PDI U+2069)
        "U+FB50-FDFF",  # Arabic Presentation Forms-A
        "U+FE70-FEFF",  # Arabic Presentation Forms-B
    ]
)

OFL_URL = "https://raw.githubusercontent.com/google/fonts/main/ofl/{slug}/OFL.txt"


def main() -> int:
    import requests
    from fontTools.subset import main as pyftsubset

    OUT.mkdir(parents=True, exist_ok=True)
    BACKEND_OUT.mkdir(parents=True, exist_ok=True)

    for query, basename, weights, ofl_slug in FAMILIES:
        css = requests.get(
            f"https://fonts.googleapis.com/css2?family={query}", headers=UA, timeout=30
        )
        css.raise_for_status()
        urls = re.findall(r"url\((.*?)\)", css.text)
        if len(urls) != len(weights):
            print(
                f"{basename}: expected {len(weights)} font urls, got {len(urls)}: {urls}",
                file=sys.stderr,
            )
            return 1

        for url, (name, weight) in zip(urls, weights):
            raw = requests.get(url, headers=UA, timeout=60).content
            src = OUT / f".{basename}-{name}.full.ttf"
            src.write_bytes(raw)

            # The backend's copy is written from `raw` BEFORE the subsetting call below,
            # so it is the upstream file byte for byte -- not a wider subset, which would
            # still be a subset. See the module docstring.
            if basename in BACKEND_FAMILIES:
                backend_dst = BACKEND_OUT / f"{basename}-{name}.ttf"
                backend_dst.write_bytes(raw)
                print(f"backend/{backend_dst.name}: {len(raw):,} bytes, full upstream face")

            dst = OUT / f"{basename}-{name}.ttf"
            pyftsubset(
                [
                    str(src),
                    f"--unicodes={UNICODES}",
                    "--layout-features=*",  # see module docstring, point 2
                    "--notdef-outline",
                    "--name-IDs=*",
                    "--recalc-bounds",
                    f"--output-file={dst}",
                ]
            )
            src.unlink()
            print(f"{dst.name}: {len(raw):,} -> {dst.stat().st_size:,} bytes (weight {weight})")

        # Each family carries its own OFL, named for the family -- one shared OFL.txt would
        # be a licence file that does not name the fonts it covers.
        ofl = requests.get(OFL_URL.format(slug=ofl_slug), timeout=30)
        if ofl.ok:
            (OUT / f"OFL-{basename}.txt").write_bytes(ofl.content)
            print(f"OFL-{basename}.txt written")
            # The licence travels with every copy of the font, not just the first one.
            if basename in BACKEND_FAMILIES:
                (BACKEND_OUT / f"OFL-{basename}.txt").write_bytes(ofl.content)
                print(f"backend/OFL-{basename}.txt written")
        else:
            print(
                f"WARNING: could not fetch {basename}'s OFL.txt; add the licence manually",
                file=sys.stderr,
            )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
