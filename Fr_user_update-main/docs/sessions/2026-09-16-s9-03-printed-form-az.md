# S9-03 — the printed form restructured and branded AZ (part 1 of 2)

**2026-09-16.** Four of six planned commits, all gated and pushed. Commits 5 and 6 carry to a
new session; the handoff prompt is the last section of this report.

**PART 2 IS `2026-09-16-s9-03-page-furniture.md`, and S9-03 is COMPLETE.** Commits 5 and 6 shipped
there the same day, plus two the plan did not contain: a product-owner refinement taken on the
rendered pages, and BL-161.

| Commit | | |
|---|---|---|
| `e7b3728` | the AZ brand assets, and the watermark mechanism measured | ✅ |
| `c99fcea` | the document model the approved layout needs | ✅ |
| `7ffeba5` | the two reads the approved form needed and the code could not do | ✅ |
| `b907e37` | the form restructured from nine sections to the approved three | ✅ |
| `661e08c` | page furniture: header, footer, watermark, palette, identity band, verification chips | ✅ part 2 |
| `20798fe` | the section frames and field rules, on a product-owner review of the rendered pages | ✅ part 2 |
| `45a6d86` | BL-161, and the plan and journey reconciliation | ✅ part 2 |

---

## The blocking question, and seven rulings

The session opened with §4's three-sections-versus-nine question already answered: the approved
design over-rules the bank paper form's running order. Worth recording that AD-021 and AD-022
**already bound the printed form to three sections and to verified-only channels** before that
ruling — it confirmed the plan of record rather than departing from it.

Seven further rulings were taken and are now recorded under AD-022 in PROJECT_PLAN.md. Four came
from the pre-build audit; two came out of the field-by-field comparison against the artboards; one
came mid-session and removed a whole class of concern.

| | Ruling |
|---|---|
| a | Identity band → a **compact line**: name, submission time, print time. Reference, «المشغّل الطابع» and «مصدر البيانات» all go. |
| b | An unverified or declined channel **omits its row entirely** — not «غير متاح». |
| c | The liveness frame **leaves the page-1 tiles and keeps its attachment sheet**. |
| d | Fields 4 and 48 **resolve to the Arabic country name**. |
| e | Field 19 **keeps its thousands separator** and gains « ج.س» — a deliberate departure from the artboard, which is ungrouped. |
| f | Field 12 is **inflected by sex** — the second and last deliberate departure. |
| g | **Existing profiles are not a constraint**: everything is flushed once the design ships in full. |

Ruling (g) settled a question commit 3 had raised carefully and at length — what a legacy manually
completed profile should print now that «يدوي» is gone. The answer is that it does not matter.
Recorded because the reasoning it retires is still in the code comments and should not be
re-litigated.

---

## What the audit found before a line was written

`@agent-reviewer` against the brief and the real code, as `docs/backoffice-redesign.md` §5 requires.
Every mechanical claim in the brief checked out. Three things it understated:

- **«معدَّل» had no data path at all.** `ProfileDetail.editableFields` says which fields *may* be
  edited, never which *were*. Both V0073 and `FieldEditRepository` name S9-03 as the intended
  reader — the storage was built for this and the reader deliberately left.
- **AD-014's acceptance render cannot gate this work.** `FopArabicAcceptanceRenderTest` renders its
  own hand-written FO and touches neither the assembler nor the writer.
- **BL-146 blocks a local end-to-end print.** `StubUqudoClient` stores the ASCII string
  `fake-image-bytes:<id>` under a declared `image/jpeg`; the renderer correctly refuses. The
  integration suite only prints because its profiles have no committed image artifacts.

---

## Commit 1 — the watermark was measured, not read

Risk-first, and it earned it. Nothing in `backend/` had ever written a `background-image` or an
absolutely-positioned block, and a URI the custom resolver refuses does not degrade: the resolver
throws, FOP swallows it into an ERROR event, and `PrintedFormRenderer` turns any ERROR into a thrown
`IOException`. A wrong brand URI fails the whole render.

Three spellings rendered and measured out of the PDF's own content stream:

| spelling | drawn | on every page |
|---|---|---|
| `background-image` alone | **93×66mm** — the asset's intrinsic size at its 330dpi JFIF density | yes |
| `background-image` + `fox:background-image-width/height` | **148×105mm**, the artboard's | yes |
| positioned `block-container` + scaled `external-graphic` | 148×105mm | yes, **and rejected** |

XSL-FO 1.1 gives `background-image` no sizing property at all, so the first is unfixable in standard
FO. The third reaches the right size and is still wrong: `az-watermark.jpg` is an opaque white-ground
JPEG and a fixed container in `static-content` paints *after* the body, so it would draw the
watermark on top of the form. **Only a region background is behind the content.** Commit 5 must use
the `fox:` extension and declare the `fox` namespace on `fo:root`.

**Review finding that mattered:** the test's per-page assertions could not fail. FOP emits one
`/Resources` dictionary for the whole document and points every page at it, so asking a page which
images it *has* returns the document's union. Proved by negative control — the test passed on a
document where the watermark appeared once. Placement is now read from each page's **content
stream**.

---

## Commit 2 — the model, and a defect no test would have caught

`PrintedSection` gained a badge, a heading level and `breakBefore`; `PrintedImage` gained `onPage`;
`PrintedField.manuallyEntered/enteredBy` became `edited/editedBy` with the marker «معدَّل».

**The index is the image's key.** `imageCell` builds the render-scoped URI as `"image-" + index`
against `document.images()`. A filtered grid renumbering from zero would draw four tiles, every one
holding a real photograph, every caption wrong — the liveness selfie under «التوقيع». Nothing would
fail.

**Revert-restore, because the assertion is indirect:** changing `index` to `slot` fails
`theLivenessFrameLeavesTheGridWithoutLeavingTheBundle`; restoring it passes. The fixture suppresses
index 3 of 5 deliberately — suppressing the *last* image leaves the surviving originals identical to
a renumbering, and the test could not see the bug at all.

**Review finding:** the assembler's liveness ruling had no test whatsoever. Deleting the one ternary
that implements it left all 1298 tests green and put five tiles on every printed form.

---

## Commit 3 — the two reads, and a marker that was silently wrong

`PrintedFormRepository.editedFieldNumbers` reads `app.profile_field_edit`; `ReferenceCatalog.
findByAlpha3` resolves the MRZ's alpha-3 through V0022's seeded `extra`. Both added to existing
ports, so no new wiring and no migration.

**Rows 13 and 14 share one column and the edit is recorded against 13.** Row 13 carries the value
for a married *woman* (her husband's name), row 14 for a married *man*. So a marker keyed on the
stored number marks the woman's row and **silently misses the man's**. `isEdited` treats an edit of
13 as an edit of both; only one row ever has a value.

I had that direction backwards — in the code, the test and the written reasoning, all agreeing with
each other — until the test failed. Recorded because three consistent wrong statements are exactly
what review is least likely to catch.

**Review finding:** the two SQL statements were proved against real Postgres, but nothing proved
their results were *used*. Replacing both arguments at the construction site with empty values left
all 1303 tests green while the form printed no markers and raw codes forever. The integration test
now prints and reads the page back.

**That assertion cost four attempts, and the failures were the useful part.** Field 38 is absent on
this suite's profiles and an absent field is correctly never marked. Field 10 *looked* populated
only because «الجنس» is a substring of «الجنسية». And **«معدَّل» cannot be asserted as a string at
all**: measured, it extracts as «ل» … «الجنس» `U+E002` «معد» — the shadda-plus-fatha renders through
a glyph with no reverse cmap entry, so it returns as a private-use codepoint, and the final «ل» is
reordered to the head of the line. The test asserts the letters that survive, pinned to one
occurrence, and records the measurement.

---

## Commit 4 — nine sections to three

Fields are **built by source and placed by layout**. Every field expression stays in the method that
owns its data; `sections()` states only where each number appears. A leftover check throws if
anything is built and never placed, and `place()` throws if the layout names a field nothing builds.

**The two-column sections interleave.** The artboard is column-major and the writer pairs adjacent
fields two to a row, so the order that renders the artboard's grid is `5,8,6,9,7,21`. It looks like a
mistake. It is not, and the rendered page confirms it.

**Review found a blocker I had introduced.** The image grid was written after every section —
invisible while no section started a page. Section 3 now does, so the four tiles followed it onto
page 2, and the approved page 1 *ends* with them. The grid is flushed before the first page-breaking
section now, with a test on the order. The form was complete, valid, and on the wrong page.

Three more: an unverified customer got a bare «قنوات الاتصال» heading; the gendered marital status
had no test, so swapping the two maps passed the suite; and fields 34/41 are free text with no
numeric keyboard, so declaring them Latin would reorder «مربع ١٢» — they render Latin only when the
value really is digits.

---

## The visual proof, and what it showed

BL-146 means a locally-run stack cannot print. `PrintedFormAssemblerTest.rendersTheApprovedFormForEyesOn`
assembles the **real** document with decodable swatches and writes
`backend/target/printed-form/approved-form-page-*.png`.

Read against the artboards it confirms the structure, the interleave, the tile order, and all six
display rulings on paper — «معدَّل», «السودان», «متزوجة», «76,000 ج.س», «راتب / أجر — أساسي», and
verified channels only. **The product owner reviewed those pages and approved the structure.**

**It also shows THREE pages where the design has two.** Section 3 overflows by two rows — «الشارع»
and «المربع» fall onto a page carrying nothing but its own chrome. Not a commit-4 defect: the space
is commit 5's to reclaim. Filed as BL-162 and asserted as `2..3` with the reason, so commit 5 cannot
leave it silently.

---

## Gate, verbatim

```
[INFO] Tests run: 1310, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 507 files clean - 0 needs changes to be clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
MAVEN_EXIT=0
```

AD-014's acceptance render runs inside that suite and passes. It is named here with its limit
restated: it renders its own hand-written FO and exercises neither the assembler nor the writer, so
it proves FOP's Arabic handling and **not** this layout.

---

## Filed

- **BL-161** — the back office prints «متزوج» for a married woman where the form now prints «متزوجة».
  The approved *screen* artboard shows the feminine form too, so the back office is the stale tier.
- **BL-162** — three pages where the design has two.

## Not built, and in no commit's scope until commit 5

Page 1's two verification chips («التحقق الحي — ناجح», «تحقق MRZ — صحيح») are artboard content with
no assembler output at all. They need a data source confirmed before they are built.

---

## Two things worth carrying forward

- **`./mvnw test-compile` gives false greens.** It reported success with three fixtures genuinely
  broken; only `clean test-compile` surfaced them.
- **The integration suite has a documented account-allocation registry** in
  `AbstractPostgresIntegrationTest`. Taking `0000000631` from an apparently free gap produced an
  unexplained 409 — it belongs to another suite's block starting `0000000630`. Claimed `0000000626`
  and recorded the trap.
