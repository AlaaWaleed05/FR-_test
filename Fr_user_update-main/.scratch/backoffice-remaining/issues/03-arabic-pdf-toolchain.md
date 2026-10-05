# Which JVM toolchain renders a right-to-left Arabic PDF correctly?

Type: research
Status: resolved
Blocked by: —

## Question

The printed form is Arabic, right-to-left, and must carry a header, a numbered footer, the
operator's name, the print timestamp, the customer's name and reference number, the submission
date, and the full profile.

The hard part is not layout. Most JVM PDF libraries write Arabic as **disconnected, reversed
letters** — every glyph in its isolated form, in logical rather than visual order — and report no
error while doing it. The page looks plausible to a non-reader and is unreadable to a customer.

Find, against real primary sources and real output:

1. Which library actually performs Arabic **glyph shaping** (initial/medial/final/isolated forms)
   and **bidi reordering** on the JVM, rather than requiring the caller to pre-shape the string?
   Candidates to check and rule in or out with evidence: OpenPDF, Apache PDFBox, iText 7 with
   pdfCalligraph, and an HTML→PDF path (e.g. a headless browser, or Flying Saucer / openhtmltopdf).
2. Licensing. iText 7 is AGPL or commercial — a bank deployment makes that a real constraint, not
   a footnote. State each candidate's licence.
3. Which Arabic font is embedded, and is its licence redistributable inside a PDF? (The project
   already uses IBM Plex Sans Arabic and — **CORRECTION 2026-09-13: this ticket claimed “Noto-family faces”; there is NO Noto reference anywhere in the repo. The faces on disk are IBM Plex Sans Arabic Regular/SemiBold and Amiri Regular** — Amiri — check what is actually in
   `mobile/` and `backoffice/` before proposing a new one.)
4. Mixed content: Arabic body text with Latin/ASCII account numbers, E.164 phone numbers and
   reference numbers like `FRU-000000001` in the same line. Confirm the candidate gets the bidi
   run boundaries right — this is where naive implementations reverse the digits.
5. Page numbering in a footer ("page N of M") requires a two-pass or deferred write in most
   libraries. Confirm the candidate supports it.

Produce a recommendation with a rendered sample as evidence, not a library comparison table.

## Context

- `CLAUDE.md`: third-party integration requires `@agent-researcher` first — this ticket is that.
- Arabic-first, full RTL, Sudan market.
- Output goes in `docs/` as a research note, linked back from this ticket.

## Answer

Resolved 2026-09-13 by `@agent-researcher`. Full report, with every citation:
`docs/sessions/2026-09-13-research-arabic-pdf-toolchain.md`
(filed under `docs/sessions/` — this repo's actual convention; `docs/research/`, which the ticket
originally named, does not exist).

**Apache FOP 2.11 (`org.apache.xmlgraphics:fop-core`), XSL-FO to PDF, embedding the repo's existing
IBM Plex Sans Arabic `.ttf`.** The only candidate that is Apache-2.0 *and* documented by its own
project to apply the font's OpenType GSUB/GPOS tables rather than pre-shaping to U+FE70
compatibility forms — which the Unicode standard itself says should not be used for interchange.
It also has native `writing-mode="rl-tb"`, an implicit UAX#9 bidi pass, and
`fo:page-number-citation-last` for "page N of M".

**Runner-up: headless Chromium `Page.printToPDF`.** Better output — Blink + HarfBuzz is the
reference implementation — and the best footer templating of the field. It lost on deployment cost:
a ~400 MB browser and its CVE stream inside a bank's container image, a subprocess on a path whose
premise is one render, and the documented Fargate `sharedMemorySize` restriction. At roughly three
submissions an hour, none of that buys throughput we need.

**Licence position.** FOP Apache-2.0, nothing owed, same footing as `poi-ooxml` 5.5.1 already in
the pom. **iText is out** — AGPLv3 forbids closed-source network deployment in iText's own words,
and pdfCalligraph is commercial-only and needs a deployed licence key. OpenPDF is clean on licence
(elect MPL-2.0) but loses on an open, unanswered upstream bug where enabling RTL layout renders
English right-to-left too. openhtmltopdf is LGPL and shapes to presentation forms. Fonts are SIL
OFL 1.1, which permits embedding and does not license the document.

**Two things the build session must not skip.**

1. **The acceptance render has not happened.** The agent had no shell, so no sample PDF exists. §8
   of the report specifies a five-check spike — shaping with a lam-alef, bidi with a synthetic
   account number and an E.164 phone on one line, RTL page furniture, page N of M, and an FSI/PDI
   negative control — to be run and read by someone who reads Arabic **before any form layout is
   written**. If it fails, the layout is thrown away with the library, because XSL-FO does not port
   to HTML.
2. **`mobile/lib/core/text/ltr_value.dart`'s FSI/PDI idiom must NOT be copied into the renderer.**
   `fop-core`'s `BidiConstants` defines no LRI/RLI/FSI/PDI classes — FOP's bidi predates Unicode
   6.3. Use separate `fo:block`s (the safe default, and what a table cell gives you for free), or
   LRM/RLM. This belongs in a comment at the renderer, not only in a report.

Also carried out of the report: a `docs/components/pdf-rendering.md` card is drafted in §10, and
R-2 (FO injection through customer-supplied fields — the FO must be generated with escaping, never
concatenated) is a build-time requirement, not a nice-to-have.
