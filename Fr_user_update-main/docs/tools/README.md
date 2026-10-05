# Hosting-document generators

`docs/bank-hosting-requirements.md` is the source of truth. The Word file at the repository
root is generated from it and must never be hand-edited: edit the markdown, then re-run the
build below.

## Regenerating

```sh
# 1. the topology diagram
python docs/tools/hosting-topology-diagram.py          # writes diagram.png in the cwd
mv diagram.png docs/assets/bank-hosting-topology.png

# 2. the Word document, rendered onto the company letterhead
python docs/tools/build-hosting-docx.py \
    docs/bank-hosting-requirements.md \
    <letterhead.docx> \
    Bayanati_Hosting_Requirements_AZT-BYN-HOST-02.docx \
    docs/assets/bank-hosting-topology.png
```

Requires `python-docx` and `Pillow`. The diagram script reads Segoe UI from
`C:/Windows/Fonts` and needs adjusting to run elsewhere.

## The sizing variants, and the order they are built in

**This order was not written down until S9-08 and had to be reconstructed by running it.** Every
script below is a ONE-SHOT, IN-PLACE patch: each raises `SystemExit("NOT FOUND")` if its anchor is
already gone, so re-running one over its own output fails rather than corrupting the file. That is
the safety property, but it also means a variant is rebuilt from the base document, never patched
further from its current state.

```sh
# option B, then option C from it
python docs/tools/make_sizing_b.py  docs/bank-hosting-requirements.md \
                                    docs/bank-hosting-requirements-sizing-b.md
python docs/tools/make_sizing_c.py  docs/bank-hosting-requirements-sizing-b.md \
                                    docs/bank-hosting-requirements-sizing-c.md

# then the in-place patches, IN THIS ORDER — add_concurrency inserts AP-7 and
# apply_s9_08_capacity rewrites it, so the two cannot be swapped
python docs/tools/fix_c_stale.py          docs/bank-hosting-requirements-sizing-c.md
python docs/tools/add_factors.py          docs/bank-hosting-requirements-sizing-c.md
python docs/tools/add_concurrency.py      docs/bank-hosting-requirements-sizing-c.md
python docs/tools/apply_s9_08_capacity.py docs/bank-hosting-requirements-sizing-c.md
```

`apply_s9_08_capacity.py` is what makes AP-7 a measured statement rather than an estimate: the
application's own capability, the end-to-end figure with the bank's SMS gateway in the path, and the
declared number. It also corrects NW-2A's messages-per-customer, BR-13's wording and the summary
table, because all three depended on the figures AP-7 used to carry.

## When the letterhead is not on the machine

`build-hosting-docx.py` needs `AZSUDAN NEW LETTERHEAD 2026.docx`, which is in Google Drive and not
in this repository — so on a machine without it the Word file cannot be regenerated at all, which
makes "edit the markdown and re-run the build" untrue exactly when someone needs it.

Every generated document already carries the letterhead in its section HEADER part, which the build
only re-anchors and never rewrites. So the template can be recovered from any previously built file:

```sh
python docs/tools/letterhead_from_output.py \
    Bayanati_Hosting_Requirements_AZT-BYN-HOST-02_sizing-C.docx /tmp/letterhead.docx
python docs/tools/build-hosting-docx.py \
    docs/bank-hosting-requirements-sizing-c.md /tmp/letterhead.docx \
    Bayanati_Hosting_Requirements_AZT-BYN-HOST-02_sizing-C.docx \
    docs/assets/bank-hosting-topology.png
```

**Prefer the Google Drive original whenever it is to hand, and say which was used.** A document
rebuilt this way carries whatever artwork the source file was printed on, so if the letterhead is
ever revised, this route silently reproduces the old one.

## The letterhead

`AZSUDAN NEW LETTERHEAD 2026.docx`, not held in this repository. It is a company asset kept in
Google Drive. Two things about it the build depends on:

- It is an empty document whose only content is a full-bleed A4 image anchored in the page
  header, repeated on every page.
- That image is anchored **relative to the margin**, so changing a page margin would drag the
  artwork with it. `build-hosting-docx.py` re-anchors it to the page before setting margins.
  Without that step the letterhead slides off the page.

Margins are then set to clear the artwork: 4.6 cm top for the logo band, 2.3 cm bottom for the
contact strip.

## What the build does with the markdown

Headings, tables, fenced code, bold and inline code are converted directly. Two conversions are
not literal:

- The fenced block containing the ASCII topology diagram is **replaced** by the rendered PNG.
  The ASCII version stays in the markdown so the source file reads correctly on its own.
- A wrapped list item is folded back into one item. Markdown continuation lines would otherwise
  become separate paragraphs.
