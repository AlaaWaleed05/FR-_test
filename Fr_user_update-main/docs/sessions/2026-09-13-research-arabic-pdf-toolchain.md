# 2026-09-13 — Research: which JVM toolchain renders a right-to-left Arabic PDF correctly

`@agent-researcher` report for `.scratch/backoffice-remaining/issues/03-arabic-pdf-toolchain.md`.
Scope: the server-side renderer for the printed update form (استمارة تحديث البيانات). **No code was
run** — this agent has no shell, so the rendered sample the ticket asks for was NOT produced. §8
specifies exactly what the build session must render and what it must check, in place of it. No
customer data, no account numbers and no font binaries were transmitted anywhere.

Sources: files on local disk in this repo; vendor documentation; third-party project source read
from `raw.githubusercontent.com` and project wikis. **The `CLAUDE.md` "never the GitHub API, never
web fetch for repo contents" rule was honoured** — every file belonging to *this* repository and to
`../FIB` was read from disk with `Read`/`Grep`; the network was used only for third-party libraries'
own source and documentation.

Ordered: reading of the task → recommendation → mechanism → candidates → licence → font → mixed
direction → page numbering → what I could not determine → risks → card draft → noticed in passing.

## Reading of the task

The defensible reading taken: *choose the renderer*, on the assumption already settled by ticket 05
that the server renders once and streams the same bytes it stores. Readings not pursued: the storage
question (ticket 05), the visual design of the form (ticket 08), the field set (ticket 04), and
whether print is an operator-only capability (already answered in `map.md`).

One decision is treated as settled input and not relitigated: the PDF is rendered **server-side in
the Java backend**. That rules out the whole browser-side family (`jsPDF`, `pdfmake`,
`react-to-print`) without argument.

## 1. Recommendation

**Apache FOP 2.11 (`org.apache.xmlgraphics:fop-core`), XSL-FO → PDF, embedding the repo's existing
IBM Plex Sans Arabic TrueType face.**

It is the only candidate that is simultaneously (a) Apache-2.0, so nothing is owed to anyone on
delivery to the bank, (b) documented by its own project to apply the font's **OpenType GSUB/GPOS
tables** rather than pre-shaping to compatibility codepoints, and (c) carrying a first-class
`writing-mode="rl-tb"` plus an implicit UAX#9 bidi pass and a native "page N of M" mechanism. It is
pure Java with no native binary, no browser and no per-render subprocess, which matters because the
backend ships as a single image to ECS Fargate.

**Runner-up: a headless Chromium render** (CDP `Page.printToPDF` via Playwright or a pinned
`chrome --headless`). It renders Arabic better than everything else on this list — Blink + HarfBuzz
is the reference implementation of both shaping and UAX#9 — and its footer templating is the best of
the field. It lost on deployment cost, not on output: it puts a ~400 MB browser, its font stack and
its CVE stream inside a bank's container image, adds a subprocess boundary to a path whose whole
premise is "one render, two destinations", and hits a documented Fargate constraint (§9, R-3).
Print volume here is roughly three submissions an hour at peak (PROJECT_PLAN.md scale section), so
none of that cost buys throughput we need.

**Ruled out, in order of how firmly:** iText + pdfCalligraph (licence), PDFBox alone (does no
shaping at all), openhtmltopdf (shapes to compatibility presentation forms; project's own README
says "Limited support for RTL"), OpenPDF (mixed-direction bug open and unanswered upstream).

**Conditions under which this flips** — stated because a recommendation with no failure condition is
a recommendation that wasn't understood:

1. If the §8 acceptance render shows FOP mis-shaping IBM Plex Sans Arabic (lam-alef, or a medial
   form), flip to **headless Chromium**, not to another Java library — the remaining Java options are
   all weaker on the same axis.
2. If ticket 08's design pass concludes the print layout must be authored in the same HTML/CSS as
   the back-office screen and stay in step with it, flip to **headless Chromium**. XSL-FO is a
   separate authoring language; keeping two layouts in sync by hand is a cost FOP does not remove.
3. If the bank's security function refuses a browser binary in the image **and** FOP fails (1), the
   remaining answer is **iText Core + pdfCalligraph with a purchased commercial licence** as a
   budget line — not OpenPDF, and not a hand-rolled PDFBox shaper.

## 2. The mechanism that decides this

Every candidate does one of exactly three things with Arabic, and this is what separates them:

| Approach | How letters get their form | Consequence |
|---|---|---|
| **(a) Pre-shape to presentation forms** — rewrite ا/ب/… into U+FE70–FEFF before writing | Needs the embedded font's `cmap` to map the U+FE70 block | Works with legacy faces (Arial, Traditional Arabic); silently produces `.notdef` or isolated forms with a modern GSUB-only face |
| **(b) Apply the font's OpenType GSUB/GPOS** — `init`/`medi`/`fina`/`rlig`/`ccmp` | Needs a TrueType/OpenType font with those tables | This is how the font's designer intended it to work; it is what every modern Arabic webfont ships |
| **(c) Do nothing** | — | Disconnected, isolated letters. No error. |

[DOC] The Unicode Standard 16.0, chapter 9, is explicit that (a) targets compatibility characters:
Presentation Forms-A and Forms-B are "encoded as characters primarily for compatibility reasons" /
"included here for compatibility with preexisting standards and legacy implementations", and for
both, "letters from the Arabic block (U+0600..U+06FF) should be used for interchange."
<https://www.unicode.org/versions/Unicode16.0.0/core-spec/chapter-9/>

Bidi is a **separate axis** from shaping, and a library can get one right and the other wrong:
full UAX#9 paragraph algorithm (ICU, FOP, Chromium) · per-chunk direction heuristics (OpenPDF) ·
nothing (PDFBox).

## 3. Candidates

### 3.1 Apache FOP 2.11 — **shaping (b), full implicit UAX#9 bidi. Recommended.**

- [DOC] "FOP applies advanced substitution, reordering, and positioning of glyphs according to
  language and script sensitive rules… if the author makes use of a font that contains OpenType
  GSUB and/or GPOS tables, then those tables will be automatically used." Complex scripts are
  **enabled by default** (disable with `-nocs` / `setComplexScriptFeatures(false)`), and "only the
  PDF output format fully supports complex scripts features" — which is the only output we want.
  <https://xmlgraphics.apache.org/fop/2.11/complexscripts.html>
- [DOC] Bidi: FOP uses the UCD and the Unicode Bidirectional Algorithm to determine inline
  progression implicitly; `fo:bidi-override` and explicit control characters (LRM/RLM, and the
  LRE/RLE/PDF embedding set) are available on top. Same page.
- [DOC] `writing-mode` is supported for "horizontal left-to-right and right-to-left modes" — i.e.
  `rl-tb` is in. The compliance table separately marks the properties `direction` and `unicode-bidi`
  "no", so **direction is expressed by `writing-mode` and control characters, not by those two
  properties**. <https://xmlgraphics.apache.org/fop/compliance.html>
- [DOC] History: FOP 1.1 added "support for complex scripts, including: full bidi support, support
  for advanced typographic tables"; FOP 2.0 added "FOP-2416: add support for Arabic Joiners
  (ZWJ/ZWNJ) - preliminary" and, relevant to §7, "**FOP-2410: fix `fo:page-number` in bidi
  context**". <https://xmlgraphics.apache.org/fop/changes.html>
- [DOC] **Embed the `.ttf`, never an `.otf`.** FOP's font page states that for OpenType/CFF fonts it
  "extracts the Compact Font Format (CFF) data… and embeds the result as a Type1C font", "losing the
  features mentioned above" (ligatures, alternates). That limitation is specific to `.otf`; plain
  TrueType is listed as fully supported. TrueType is subset on embed by default
  (`embedding-mode="full"` to override). <https://xmlgraphics.apache.org/fop/2.11/fonts.html>
  This matters because our on-disk faces are `.ttf` — the right format by luck, and it must stay
  that way.
- [DOC] Stated limitations, both acceptable here: shaping context does not cross an element
  boundary (so you cannot colour one Arabic letter without breaking its join), and ZWJ/ZWNJ support
  is preliminary.
- Licence: Apache-2.0. Transitive set includes `xmlgraphics-commons`, Batik, Avalon
  (`avalon-framework-api`, Apache-2.0) — see risk R-4.
- [UNVERIFIED] FOP 2.11's release date and minimum JDK: the release-notes page lists the changes
  (PDFBox 3 upgrade, PDF object streams) but states neither. Confirm from the artifact's POM before
  pinning.

### 3.2 Headless Chromium — **shaping (b) via HarfBuzz, reference-grade bidi. Runner-up.**

- [DOC] `Page.printToPDF` accepts `headerTemplate`/`footerTemplate` as "valid HTML markup with
  following classes used to inject printing values": `date`, `title`, `url`, `pageNumber`,
  `totalPages`. That answers §7 outright.
  <https://chromedevtools.github.io/devtools-protocol/tot/Page/>
- [UNVERIFIED] Arabic correctness in Blink is not in dispute, but I did not render a sample, and
  Chromium's header/footer templates are rendered in a **separate document** from the page body —
  whether an RTL footer inherits the page's direction, and whether the font used for the footer is
  the embedded one or a system fallback, must be checked, not assumed.
- Licence: Chromium BSD-3-Clause with LGPL components; Playwright Apache-2.0. No obligation on our
  code either way.
- Cost: a browser binary and its fonts in the ECR image; a process per render; a CVE stream that a
  bank's scanner will raise every month whether or not the browser ever touches untrusted input.

### 3.3 Apache PDFBox alone — **shaping (c): none. Ruled out.**

- [OBSERVED] PDFBOX-3550 "OpenType Shaping" (created 2016-11-01) reports Arabic rendering with
  separated letters; it is **Closed as Duplicate with no fix version** — PDFBox did not take on
  shaping as a feature. <https://issues.apache.org/jira/browse/PDFBOX-3550>
- [OBSERVED] PDFBOX-1216 records the same failure mode from the other direction: PDFBox looks for
  the *isolated* variant while the embedded font carries only the connected ones.
  PDFBOX-5023 records that OpenType Layout tables in an Arabic font "are not implemented in PDFBox
  and will be ignored". <https://issues.apache.org/jira/browse/PDFBOX-5023>
- `PDPageContentStream.showText` writes the codepoints it is handed, in the order it is handed them.
  Using it means writing our own shaper and our own bidi pass — the ticket's exact failure mode,
  built deliberately.
- Licence: Apache-2.0. Not the problem.
- **PDFBox is still in the picture as a substrate** — openhtmltopdf renders through it, and FOP 2.11
  now bundles PDFBox 3 internally. It is not a candidate as a *text* API.

### 3.4 iText Core + pdfCalligraph — **shaping (b), excellent. Ruled out on licence.**

- [DOC] pdfCalligraph is the add-on whose "main function" is "to correctly render complex writing
  systems such as right-to-left Hebrew and Arabic scripts"; iText's layout module looks for it in
  the classpath and, when found, "the writing system will be automatically changed from L2R to R2L
  if Hebrew or Arabic is detected". <https://itextpdf.com/products/pdfcalligraph>
- **Without pdfCalligraph, iText Core does not shape Arabic.** That is the design — the add-on is
  optional because shaping "requires more extensive processing power".
- [DOC] Licence, and this is decisive: "You may not deploy it on a network without disclosing the
  full source code of your own applications under the AGPL license" / "Disclose and distribute all
  source code, including your own product and web-based applications." A commercial licence is
  needed when "you cannot comply with the AGPLv3 terms".
  <https://itextpdf.com/how-buy/AGPLv3-license>
- [DOC] pdfCalligraph additionally "won't work without an official license key" — it is not among
  the open-source add-ons.
  <https://kb.itextpdf.com/itext/installing-the-itext-license-key-and-license-key-l>
- **Position for this project: AGPL is unusable.** This backend is a closed-source deliverable
  deployed on a network for a bank. Taking iText means buying a licence — a recurring cost, a
  procurement cycle, and a licence-key file that must be deployed and rotated with the app. That is
  a defensible choice only if the Apache-2.0 option fails on quality; it is not the default.

### 3.5 openhtmltopdf (the Flying Saucer line) — **shaping (a), real ICU bidi. Ruled out.**

- [OBSERVED] Its shaping is ICU's `ArabicShaping`, read from the project's own source:
  `ICUBidiReorderer` constructs
  `new ArabicShaping(TEXT_DIRECTION_LOGICAL | LETTERS_SHAPE | LENGTH_GROW_SHRINK)`.
  <https://raw.githubusercontent.com/openhtmltopdf/openhtmltopdf/main/openhtmltopdf-rtl-support/src/main/java/com/openhtmltopdf/bidi/support/ICUBidiReorderer.java>
- [DOC] ICU defines `LETTERS_SHAPE` as: "replace normative letter characters in the U+0600 (Arabic)
  block, by shaped ones in the U+FE70 (Presentation Forms B) block. Performs Lam-Alef ligature
  substitution." <https://unicode-org.github.io/icu-docs/apidoc/released/icu4j/com/ibm/icu/text/ArabicShaping.html>
  That is approach (a): **the output is only as good as the embedded font's legacy `cmap`**, and it
  applies none of the font's own `rlig`/`calt`/`ccmp`, nor GPOS mark positioning.
- [OBSERVED] Its bidi, by contrast, is genuinely correct: `ICUBidiSplitter` calls
  `Bidi.setPara` / `countRuns` / `getVisualRun` — the real UAX#9 implementation, ICU4J's.
  So this candidate would likely get §6's digits right and §2's letter forms wrong, which is the
  more insidious of the two failures.
- [DOC] The project's own README: "**Limited support for RTL and bi-directional documents**", plus
  "you can not throw modern HTML5+ at this engine", no flexbox, no grid.
  <https://github.com/danfickle/openhtmltopdf>
- [OBSERVED] Maintenance: the original `danfickle/openhtmltopdf` is inactive (issue #1005 is titled
  "Project is obviously not active anymore. SWITCH TO ACTIVE FORK"); the live line is
  `io.github.openhtmltopdf` at `github.com/openhtmltopdf/openhtmltopdf`, on PDFBox 3.
  [UNVERIFIED] Its current version: the fork's README rendered as 1.1.8 (April 2024) while
  libraries.io lists 1.1.37/1.1.40. The README appears stale; confirm from Maven Central before
  quoting a version anywhere.
- Licence: LGPL-2.1-or-later — see §4.

### 3.6 OpenPDF — **shaping (b) via Java2D/HarfBuzz, but its bidi story is unsettled. Ruled out.**

This is the closest call on the list, and worth stating fairly because OpenPDF is the obvious
"just add one dependency" answer.

- [DOC] OpenPDF's README claims it "supports OpenType layout, glyph positioning, reordering and
  substitution which is e.g. required for… the rendering of non-Latin and right-to-left scripts".
  <https://github.com/LibrePDF/OpenPDF>
- [DOC] The mechanism, from the project wiki: OpenPDF "internally uses Java2D builtin routines for
  glyph layout, reordering and substitution. Since Java 9 these routines rely on the HarfBuzz
  shaping library", and "Java's `Bidi`-class is used to deduce the text direction for each chunk of
  text". `LayoutProcessor` is "the predecessor of `GlyphLayoutManager` and is deprecated now".
  <https://github.com/LibrePDF/OpenPDF/wiki/Accents,-DIN-91379,-non-Latin-scripts>
  That is real (b)-class shaping — better than openhtmltopdf's approach.
- **Why it loses anyway.** Two things, both from the project itself:
  1. [OBSERVED] Issue #779, "RTL Issue with english and arabic content in single PDF": enabling
     `LayoutProcessor.enable(java.awt.Font.LAYOUT_RIGHT_TO_LEFT)` renders *both* Arabic and English
     right-to-left. **Open, no maintainer response, no workaround.**
     <https://github.com/LibrePDF/OpenPDF/issues/779> That is §6 — our exact requirement — failing
     upstream with nobody answering.
  2. [DOC] The wiki's own framing of complex-script support is "try it and share the results". Bidi
     derived "per chunk of text" is not the UAX#9 paragraph algorithm; it is a heuristic that
     happens to be right when each chunk is uniform.
- The legacy path (`ArabicLigaturizer` + `ColumnText`/`PdfPTable` with `RUN_DIRECTION_RTL`, inherited
  from iText 2) is approach (a) and inherits §2's presentation-form dependency.
- Licence: **MPL-2.0 OR LGPL-2.1+** [DOC README]. Choosing MPL-2.0 makes this file-level copyleft
  and clean for a bank deliverable — so OpenPDF is **not** ruled out on licence. It is ruled out on
  evidence.

## 4. Licensing, plainly

| Candidate | Licence | Position for a bank deliverable |
|---|---|---|
| **Apache FOP** | Apache-2.0 (deps: xmlgraphics-commons, Batik, Avalon — all Apache-2.0) | **Nothing owed.** Same footing as `poi-ooxml` 5.5.1, already in `backend/pom.xml` |
| Headless Chromium / Playwright | BSD-3 + LGPL parts / Apache-2.0 | Nothing owed for our code; the browser is a runtime artifact, not a linked library |
| Apache PDFBox | Apache-2.0 | Nothing owed |
| OpenPDF | MPL-2.0 **OR** LGPL-2.1+ | Elect MPL-2.0 and nothing is owed on our own files |
| openhtmltopdf | LGPL-2.1-or-later | Manageable but not free of thought. LGPL obligations bite on **distribution**, and handing a fat jar to the bank is distribution. LGPL 2.1 §6 requires the recipient be able to relink a modified library; a Boot fat jar keeps the dependency as a nested jar, which is generally taken to satisfy that — but it is a question for the bank's counsel that Apache-2.0 never raises |
| **iText Core + pdfCalligraph** | **AGPLv3 or commercial; pdfCalligraph is commercial-only and needs a licence key** | **AGPL is unusable here** — a closed-source app deployed on a network. Commercial means procurement, recurring cost, and a key file in the deployment |
| Fonts (IBM Plex Sans Arabic, Amiri) | SIL OFL 1.1 | Embedding is explicitly permitted — see §5 |

## 5. Font — use what is already here

[OBSERVED] The repo already carries, on disk, exactly the faces this needs:

- `mobile/assets/fonts/IBMPlexSansArabic-Regular.ttf`
- `mobile/assets/fonts/IBMPlexSansArabic-SemiBold.ttf`
- `mobile/assets/fonts/Amiri-Regular.ttf`
- `mobile/assets/fonts/OFL-IBMPlexSansArabic.txt`, `mobile/assets/fonts/OFL-Amiri.txt`

[OBSERVED] `mobile/tool/fetch_and_subset_fonts.py` is the generator, and its docstring settles two
things that carry straight over to the PDF:

- It subsets **by Unicode range, never by the characters this repo's own strings use** — "customer
  names arrive from the Uqudo scan and the Civil Registry, not from our source". The identical
  argument applies to the printed form, which prints those same names.
- It passes `--layout-features='*'` deliberately, because dropping `init`/`medi`/`fina`/`rlig`/
  `ccmp`/`calt` "is exactly how a connected script's joins break". It records those features as
  **verified present in the upstream files**: `calt ccmp dnom fina frac init liga locl medi numr
  rlig`.

That last line is the single most important fact in this report: **IBM Plex Sans Arabic joins via
GSUB.** It is built for approach (b). It tells us nothing either way about whether it also carries
legacy U+FE70 `cmap` entries for approach (a) — see §8.

Recommendation, three parts:

1. **Body face: IBM Plex Sans Arabic Regular + SemiBold.** This is AD-012 fork 2's ruling applied to
   print: Amiri is display-only because it has no matched Latin, and "nearly every screen sets an
   Arabic label beside Latin digits". A printed form is that problem at its worst — every line pairs
   an Arabic label with a Latin account number, `FRU-` reference or E.164 phone number.
2. **Amiri Regular for the form's title only** (استمارة تحديث البيانات), consistent with the same
   ruling. Optional; drop it if the design pass (ticket 08) doesn't want it.
3. **The backend gets its own copy under `backend/src/main/resources/fonts/`, generated by the same
   script, embedding the FULL upstream TTF rather than the mobile subset.** Two reasons: the backend
   must not read across into `mobile/` (nothing else does), and [DOC] FOP subsets TrueType on embed
   by default anyway — so bundling the full face costs nothing in the output PDF and removes any
   chance of a customer's name hitting a subset hole. Extend `fetch_and_subset_fonts.py` rather than
   adding a second script; AD-012 fork 5 records what happens when two scripts each own the
   identity.

**Licence position** [DOC], SIL OFL FAQ:

- Q1.12 — embedding, "either in full or a subset": "Yes… The restrictions regarding font
  modification and redistribution do not apply, as the font is not intended for use outside the
  document."
- Q1.13 — "Referencing or embedding an OFL font in any document does not change the license of the
  document itself."
- Q1.10 — embedding is one of the situations where the OFL text need not travel with the font.

<https://openfontlicense.org/ofl-faq/> So the stored PDF carries no licence obligation, and the
repo's existing `OFL-*.txt` files already discharge the obligation on the *source* fonts.

## 6. Mixed direction — Arabic body, Latin account/phone/reference

This is where the ticket says naive implementations reverse digits, and it is right. The repo has
already solved this problem once, on the mobile side, and the solution transfers.

[OBSERVED] `mobile/lib/core/text/ltr_value.dart` documents two mechanisms and insists they are not
interchangeable:

> 1. `LtrValue` — for a value that STANDS ALONE in its own widget. A `Text` carrying its own
>    `textDirection` is already its own bidi paragraph, and a paragraph boundary is the strongest
>    isolation there is. No control characters are inserted.
> 2. `isolate` — for a value INTERPOLATED INTO an Arabic sentence… Only there are the FSI/PDI
>    (U+2068/U+2069) characters the right tool.

It also records why the distinction is load-bearing: embedding format characters into a value the
customer will copy means "a support agent then searches for a reference number that does not match"
(`confirmation_screen.dart`). A PDF the operator copies text out of has the same failure.

**The PDF mapping, and a trap specific to FOP:**

- A value in its own table cell is its own `fo:block` — its own bidi paragraph. Set
  `writing-mode="lr-tb"` (or `text-align="left"`) on that block and **insert no control characters
  at all**. This covers the account number, the phone numbers and the reference number, which on a
  form all live in their own cells. This is `LtrValue` case 1, and it is the safe default.
- **[OBSERVED] Do NOT copy `LtrValue.isolate` into the renderer. FOP's bidi predates the isolate
  characters.** `BidiConstants` in `fop-core` defines exactly nineteen bidi classes — L, LRE, LRO,
  R, AL, RLE, RLO, PDF, EN, ES, ET, AN, CS, NSM, BN, B, S, WS, ON — plus an implementation-specific
  SURROGATE at 20. **There are no LRI/RLI/FSI/PDI classes.** FOP's implementation dates to the
  Unicode 6.1 era; isolates arrived in Unicode 6.3.
  <https://raw.githubusercontent.com/apache/xmlgraphics-fop/trunk/fop-core/src/main/java/org/apache/fop/complexscripts/bidi/BidiConstants.java>
  [INFERRED] U+2068/U+2069 will therefore be classified as a neutral rather than acting as an
  isolate, and — being format characters with no glyph in any font — may additionally surface as
  `.notdef` boxes or "glyph not available" warnings. For a value genuinely interpolated into an
  Arabic sentence under FOP, use **U+200E LRM / U+200F RLM** or an LRE…PDF (U+202A…U+202C)
  embedding, both of which FOP does know — or restructure so the value gets its own block.
- For the other candidates: openhtmltopdf and Chromium both run modern ICU/Blink bidi and do
  understand FSI/PDI. OpenPDF's per-chunk heuristic is the one with an open upstream bug (#779).

**How I know FOP gets the digits right**: [INFERRED, not observed] FOP implements UAX#9 against the
UCD, so `EN`/`AN`/`CS`/`ES` handling — the rules that keep `+249912345678` and `FRU-000000001` in
logical order inside an RTL paragraph — come from the algorithm rather than from a heuristic. That
inference is exactly what §8's proof exists to confirm, and it is the reason the proof is
non-negotiable before any build work starts.

## 7. Page "N of M" in the footer

| Candidate | Mechanism | Verdict |
|---|---|---|
| **Apache FOP** | `fo:page-number` + `fo:page-number-citation-last ref-id="…"` in `fo:static-content` for `xsl-region-after` | **Supported, with a documented caveat.** [DOC] compliance table: `fo:page-number` "yes"; `page-number-citation-last` partial — "Works only for page-sequence so far. After the page number is known, no relayout is performed. The appearance may be suboptimal." Harmless for a fixed-position footer; it would matter only if the total's width changed the layout. [DOC] FOP-2410 specifically fixed `fo:page-number` in a bidi context |
| Headless Chromium | `footerTemplate` with `<span class="pageNumber">`/`<span class="totalPages">` | **Supported, best in class** [DOC, CDP `Page.printToPDF`] |
| OpenPDF | The iText-2 `PdfPageEvent` idiom: stamp the footer per page, and patch the total in a second pass (the classic template/`PdfTemplate` placeholder) | Supported, but it is caller-implemented plumbing, not a feature |
| PDFBox | Caller writes both passes by hand | Caller-implemented |
| iText Core | Same event/second-pass idiom as OpenPDF | Supported |

## 8. What I could not determine — and the proof that must run first

**The ticket asks for a rendered sample as evidence. I could not produce one: this agent has no
shell.** Everything above is documentary and source evidence. The build session's *first* task,
before any form layout exists, is a one-page spike that renders and is read by someone who reads
Arabic. Specifically:

1. **Shaping.** A word requiring all four forms and a lam-alef ligature — e.g. مؤسسة، لا، بيانات،
   استمارة. Check: joined, correct medial forms, the lam-alef as one glyph, no `.notdef` boxes, and
   no "glyph not available in font" warnings in the FOP log.
2. **Bidi with numbers.** One Arabic sentence containing, on the same line, a synthetic account
   number, a `+249`-prefixed E.164 number and `FRU-000000001`. Check each reads left-to-right,
   digits in order, the `+` and the `-` on the correct side. Use synthetic values only — CLAUDE.md
   forbids real account numbers in the repo, in fixtures and in session reports.
3. **RTL page furniture.** `writing-mode="rl-tb"`: does the two-column label/value table put labels
   on the right; does the footer's page number land on the correct side of the page.
4. **Page N of M** across a forced three-page document.
5. **FSI/PDI negative control.** Render one line with U+2068/U+2069 around a Latin value under FOP
   and confirm the §6 finding — whether they are ignored, or worse, printed.

**Other explicit unknowns:**

- **[UNVERIFIED] Does `IBMPlexSansArabic-Regular.ttf` map the U+FE70 block in its `cmap`?** I could
  not inspect the binary (no shell). It does not affect the recommendation — FOP uses GSUB, not
  presentation forms — but it is the gate on whether openhtmltopdf or OpenPDF's legacy path were
  ever viable with this font at all. The check, run against the upstream (unsubsetted) file:
  `python -c "from fontTools.ttLib import TTFont; f=TTFont('IBMPlexSansArabic-Regular.ttf'); print(len([c for c in f.getBestCmap() if 0xFE70<=c<=0xFEFF]))"`
  `fonttools` is already a build-time tool for this repo (`fetch_and_subset_fonts.py`).
- **[UNVERIFIED] FOP 2.11's release date and minimum JDK.** Neither appears on the release-notes
  page. Confirm before pinning, given the Java 21 enforcer rule.
- **[UNVERIFIED] The live version of the openhtmltopdf fork.** README says 1.1.8; libraries.io says
  1.1.37/1.1.40. Immaterial unless the recommendation flips.
- **Not investigated, deliberately:** PDF/A conformance, and whether the stored artifact needs it.
  Nothing in the ticket or in ticket 05 asks for it. It is worth asking the bank, because retrofitting
  PDF/A changes font-embedding rules (full embedding, no subsetting shortcuts) and would be cheaper
  to decide now than later. FOP supports PDF/A output; that is why it is cheap to ask.

## 9. Risks

- **R-1 — the recommendation is wrong about FOP's Arabic quality (likelihood: low-moderate; cost to
  reverse: moderate).** FOP's complex-script engine was contributed in 2012 and has seen only
  intermittent Arabic work since ("preliminary" ZWJ/ZWNJ support). If §8 fails, the layout is thrown
  away with the library, because XSL-FO templates do not port to HTML. **Mitigation: run §8 before
  any form layout is written.** The exposure is one spike, not a slice.
- **R-2 — FO injection through customer data.** Every field on this form is customer- or
  scan-supplied. A name containing `<` or `&` breaks the FO document, and a crafted one could inject
  markup. **The FO must be produced by a generator that escapes, not by string concatenation**, and
  the `TransformerFactory` must be configured for secure processing with external entities disabled.
  The same hazard exists verbatim on the Chromium path as HTML injection.
- **R-3 — [DOC] Fargate does not support `sharedMemorySize`.** AWS's ECS task-definition parameter
  reference: "If you're using tasks that use Fargate, the `sharedMemorySize` parameter isn't
  supported."
  <https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definition_parameters.html>
  Chromium's default `/dev/shm` use is a well-known crash source under that constraint
  (`--disable-dev-shm-usage`). This is a cost of the runner-up, and a reason not to pick it casually
  if R-1 fires. [OBSERVED] The backend targets ECS/Fargate — `docs/sessions/2026-09-06-aws-provisioning.md`
  (RDS SG scoped to "the ECS task SG"; S7-07 "backend on ECR/Fargate").
- **R-4 — supply chain.** FOP pulls Batik, which has its own CVE history. Our renderer processes only
  FO we generate ourselves, never an uploaded document, so the exposure is low — but a bank's scanner
  will still flag it. Decide up front whether SVG is needed at all (the bank logo could be a PNG),
  and keep FOP current.
- **R-5 — the mobile bidi idiom does not transfer.** §6: `LtrValue.isolate` uses FSI/PDI, which
  FOP's bidi does not implement. A developer reasonably copying the mobile helper into the renderer
  would produce silently-wrong or box-littered output. **This belongs in the component card and in a
  comment at the renderer, not only in this report.**
- **R-6 — `poi-ooxml` and `fop-core` both pull XML and commons libraries.** Version convergence is
  managed by neither Spring Boot's BOM nor POI's. Expect to pin, and check `mvn dependency:tree`
  when the dependency lands.

## 10. Card draft — `docs/components/pdf-rendering.md`

No card exists. Draft, to be created by the build session and corrected against the §8 render:

```markdown
# Component: printed-form PDF rendering (backend)

Toolchain: Apache FOP 2.11 (`org.apache.xmlgraphics:fop-core`), XSL-FO -> PDF. Apache-2.0. [DOC]
Decided 2026-09-13, ticket 03. Alternatives and why they lost:
docs/sessions/2026-09-13-research-arabic-pdf-toolchain.md

- Complex scripts are ON by default; FOP applies the font's OpenType GSUB/GPOS tables. Never pass
  `-nocs` / `setComplexScriptFeatures(false)`. [DOC fop/2.11/complexscripts.html]
- EMBED THE .ttf, NEVER AN .otf. FOP converts OpenType/CFF to Type1C and loses layout features;
  plain TrueType keeps them. [DOC fop/2.11/fonts.html]
- Font: IBM Plex Sans Arabic Regular + SemiBold, full upstream TTF, under
  backend/src/main/resources/fonts/. OFL 1.1 permits embedding and does not license the document.
  [DOC openfontlicense.org/ofl-faq 1.12/1.13]. Amiri Regular for the title only (AD-012 fork 2).
  FOP subsets on embed by default, so the full face costs nothing in the output. [DOC]
- Page direction: writing-mode="rl-tb". The properties `direction` and `unicode-bidi` are NOT
  implemented by FOP; use writing-mode, fo:bidi-override, or control characters. [DOC compliance.html]
- MIXED DIRECTION. A Latin value (account number, E.164 phone, FRU- reference) gets its own
  fo:block -- a block is its own bidi paragraph, and that is the strongest isolation. Insert NO
  control characters there. Same rule, same reason, as mobile/lib/core/text/ltr_value.dart case 1.
- DO NOT use FSI/PDI (U+2068/U+2069) here. FOP's BidiConstants defines no isolate classes -- its
  bidi predates Unicode 6.3. [OBSERVED fop-core BidiConstants.java] For a value interpolated into
  an Arabic sentence use LRM/RLM (U+200E/U+200F) or LRE..PDF (U+202A..U+202C).
- Footer "page N of M": fo:page-number + fo:page-number-citation-last ref-id. Partial per the
  compliance table -- no relayout after the number is known; fine for a fixed footer. [DOC]
- The FO document is GENERATED WITH ESCAPING, never string-concatenated: every field on the form is
  customer-supplied. TransformerFactory runs with secure processing and external entities disabled.
- FopFactory is a thread-safe singleton bean; a Fop instance is per document.
- Render once, into a byte[]; the same bytes are streamed to the operator and stored (ticket 05).
- [UNVERIFIED until the acceptance render] shaping quality for IBM Plex Sans Arabic; lam-alef;
  digit order in a mixed line; FSI/PDI behaviour.
```

Also worth a one-line pointer in `docs/components/backoffice-components.md` when the print button
lands, and a line in `docs/journeys/operator.md` once ticket 08 settles what the form looks like.

## 11. Noticed in passing

- `.scratch/backoffice-remaining/issues/05-printed-artifact-lifecycle.md` asks whether the printed
  PDF gets the `app.artifact_read()` checksum treatment. Whichever renderer wins, the render is
  **not** byte-reproducible across runs — FOP, iText and Chromium all stamp a creation date and a
  document ID. That is fine for "one render, two destinations", but it means a stored print can
  never be re-derived and compared; the checksum is the only proof of what was printed. Worth
  recording in ticket 05 rather than discovered later.
- `backoffice/src/index.css:19` sets `font-family: system-ui, 'Segoe UI', Tahoma, Arial, sans-serif`
  — the back office uses **no** bundled Arabic face at all, while mobile carries two. Not this
  ticket's problem, but it is squarely ticket 08's (the UI design pass), and the fonts it needs are
  already in the repo under a licence that permits it.
