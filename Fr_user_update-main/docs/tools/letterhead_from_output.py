"""Reconstruct the letterhead TEMPLATE from an already-generated hosting document.

    python docs/tools/letterhead_from_output.py <a generated .docx> <template out .docx>

WHY THIS EXISTS. build-hosting-docx.py needs `AZSUDAN NEW LETTERHEAD 2026.docx`, which is a company
asset in Google Drive and deliberately not in this repository (see README). On a machine without it
the document cannot be regenerated at all — which makes "edit the markdown and re-run the build"
untrue exactly when someone needs it.

Every generated document already carries the letterhead: the artwork lives in the section's HEADER
part, which build-hosting-docx.py never rewrites, only re-anchors. So stripping a generated file's
BODY back to empty restores something the build can use as its template. The header, the section
properties and the page geometry all survive because none of them live in the body.

WHAT THIS IS NOT. It is not a substitute for the real asset. If the letterhead artwork itself is
ever revised, a document rebuilt this way carries the OLD artwork, because it is recovering the
letterhead from a file that was printed on it. Use the Google Drive original whenever it is to hand;
use this when it is not, and say which was used.
"""
import sys

from docx import Document
from docx.oxml.ns import qn

SOURCE, OUT = sys.argv[1], sys.argv[2]

doc = Document(SOURCE)
body = doc.element.body

# Remove every body child except the trailing sectPr, which carries page size, margins and the
# header reference. Iterate over a copy: removing from a live element list skips entries.
removed = 0
for child in list(body):
    if child.tag == qn("w:sectPr"):
        continue
    body.remove(child)
    removed += 1

if body.find(qn("w:sectPr")) is None:
    raise SystemExit("no sectPr survived — this file cannot serve as a template")

# The build adds its own named styles and refuses to add one that already exists, so a template
# recovered from a built document must give them back. These are exactly the names
# build-hosting-docx.py creates.
#
# Matched CASE-INSENSITIVELY, which is not fussiness: Word ships a built-in style called `caption`,
# python-docx resolves the build's `Caption` onto it, and an exact-case match leaves it behind and
# fails the build on that one style alone. Found by running this.
BUILD_STYLES = (
    "DocTitle", "DocSub", "H1", "H2", "Body", "Bullet", "Mono", "Caption", "Cell", "CellHead",
)
styles_element = doc.styles.element
dropped = 0
for style_element in list(styles_element):
    name_element = style_element.find(qn("w:name"))
    name = None if name_element is None else name_element.get(qn("w:val"))
    if name is not None and name.lower() in {n.lower() for n in BUILD_STYLES}:
        styles_element.remove(style_element)
        dropped += 1
print("dropped %d build-added styles" % dropped)

header_images = 0
for section in doc.sections:
    header_images += len(section.header.part.element.findall(".//" + qn("wp:posOffset")))

if header_images == 0:
    raise SystemExit(
        "no anchored header artwork found — the source does not carry the letterhead, so the"
        " template this would produce is a blank page rather than headed paper")

doc.save(OUT)
print("stripped %d body elements; header artwork anchors found: %d" % (removed, header_images))
print("wrote", OUT)
