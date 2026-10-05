# S9-03 — the page furniture, and the form finished (part 2 of 2)

**2026-09-16.** Commits 5 and 6 of S9-03, plus two the plan did not contain: a product-owner
refinement taken on the rendered pages, and BL-161. All gated and pushed. S9-03 is complete and
BL-153 with it.

| Commit | | |
|---|---|---|
| `661e08c` | the page furniture, branded AZ, and the form back to two pages | ✅ |
| `20798fe` | the section frames and field rules the approved design draws | ✅ |
| `45a6d86` | BL-161 — the back office agrees with a married woman | ✅ |
| — | this report and the plan reconciliation | ✅ |

Part 1 is `2026-09-16-s9-03-printed-form-az.md`.

---

## The pre-build audit earned its place four times over

`@agent-reviewer` against the brief and the real code before an edit, as
`docs/backoffice-redesign.md` §5 requires. Four findings that would each have cost a gate cycle or
shipped a defect:

- **`FoDocumentWriter` could not write a `fox:` attribute at all.** It bound only the `fo` prefix,
  so `writeAttribute(FOX, …)` throws `Prefix cannot be null`, which `write` converts into an
  `IllegalStateException`. Not a small watermark — a dead render.
- **The reference number would have left the paper entirely.** It was printed in exactly two
  places, the identity band and the continuation header, and commit 5 deletes both.
- **A printable profile can have `faceResult == null`.** Every pre-AD-022 manually completed
  profile is `submitted` with no identity cycle, and `PRINTABLE_STATUSES` admits `submitted`.
- **Removing «الصور والتوقيع» would have silently retired the commit-4 regression guard**, that
  string being the only anchor proving the tiles stay on page 1.

It also corrected the brief's own arithmetic — see BL-162 below.

---

## Commit 5 — the furniture

**The verification chips were the one thing the brief said to stop for.** They have a source, and
it is not the obvious one. There is no liveness outcome column anywhere — V0008 says so in terms,
because a liveness failure produces no JWS at all (AD-002a) — so the PRESENCE of a face result IS
the liveness pass. `FaceResultView.passed` is the trap: it reads like the answer and is a GENERATED
column, `match AND match_level >= threshold_applied`, i.e. the face match whose display AD-022
ruling 3 removed. Printing it would breach the ruling while looking like it honoured it. S9-02 had
already solved this on the screen and the product owner had approved that screen, so the form
follows the same derivation.

**Two states, not the screen's three.** `blocked_liveness` is unreachable on a form that prints
only `submitted` and `approved`.

**The single master, and what it cost.** The artboards give page 2 the same full header as page 1,
so there is nothing left for a second master to differ in — one `static-content` per flow-name, and
`CONTINUED_FROM_PREVIOUS` loses its only caller. The reference had to be picked up by the header
and the footer independently, which is why `PrintedFormDocument.referenceNumber`'s javadoc now
records that the duplication is load-bearing.

**Review finding that mattered most: no test could tell the two chip derivations apart.** Every
fixture builds its face result as `scan == null ? null : FaceResultView(true, 5, 4, true, …)`, so
"a row exists" and "`passed` is true" are perfectly correlated across the whole suite — an
assembler reading `passed` passed every test, including the one named for the property.
`aFailedFaceMatchDoesNotChangeTheLivenessChip` gives a profile a face result that FAILED its match
and asserts the liveness chip still reads «ناجح». It is the only test that discriminates.

**Second review finding: the per-page header assertions were all satisfied by the footer.** The
footer writes PRODUCT · TITLE — REFERENCE, so asserting the title, reference, product or
printed-by line proved only that the footer existed — every one would pass with the header deleted
outright. Re-anchored on «البنك السوداني الفرنسي» and «التاريخ», which appear nowhere else, and the
reference is now asserted as occurring twice per page.

**A decision I took and then reversed on review.** I kept the longer internal-use notice over the
artboards' «للاستخدام الداخلي», reasoning that trimming it would delete half of ticket 05 decision
7. AD-022 states that rulings (e) and (f) are the only deliberate departures from the approved
artboards, so a third is not something a code comment gets to introduce. Shortened. Decision 7's
requirement — that the form say on its face that it is internal — still holds; what goes is the
instruction not to hand it over, and if the bank wants that back it is a ruling for AD-022's list.
Side benefit: the dropped clause carried three combining marks that PDF extraction returns out of
order, so the assertion could only ever check half the string. It is now exact.

**Revert-restore, because the re-anchored grid guard is an ordering assertion.** Setting the flush
condition to `false` — the pre-commit-4 defect — fails
`theImageGridStaysOnPageOneWhenASectionBreaksToPageTwo` with `to be less than`; restoring passes.

---

## BL-162 closed, and not the way anyone predicted

The brief said the identity band and the 34mm header would reclaim the two overflowing rows. **The
identity band contributes nothing**: it is on page 1, and section 3 carries `break-before="page"`,
so page 2 starts in the same place however much page 1 frees. Measured on the three-page render,
page 2 held 33 of 35 rows in 241mm with essentially no slack.

| | reclaimed on page 2 |
|---|---|
| one 17mm header (artboard-measured) replacing the 22mm continuation header | +5mm |
| the artboards' page margins (9/8/10.6mm) | +3mm |
| body margin-bottom 14mm → 12mm | +2mm |
| **furniture total** | **+10mm** |
| two rows cost | **~11.5mm** |

So the furniture alone lands short, and the rest came from the field VALUE size — 9.5pt against
7.5pt labels, a pairing that belongs to no artboard. The artboards set both at 10px of a 794px
page, which is 7.5pt. Moving toward that is a correction, not a compromise.

---

## Commit 6 — what the product owner saw that the tests could not

Read off the rendered pages: "the size of the frame is smaller than the width of the section title,
that defeats the view. Also horizontal lines are not showing between all fields inside the section,
its there between some fields only."

Both accurate, and neither had the cause I first guessed.

**The intermittent rules were sub-pixel.** The rule was 0.2pt — 0.36 of a pixel at the 130dpi the
eyes-on render uses — and FOP's rasteriser snaps thin fills to whole pixels rather than
anti-aliasing them, so each row was a coin flip. Every row rule comes back at full intensity or not
at all, which is exactly what "between some fields only" looks like. Strictly a preview artefact —
0.2pt would have printed at 600dpi — but the artboards draw 1px, which on a 794px page representing
210mm is 0.75pt, and every rule on the form now takes that.

**The frame was narrower for a reason I got wrong first.** I wrote that "XSL-FO is not CSS about
padding: padding is drawn outside the content rectangle". That is false — padding is outside the
content box in CSS too. The real rule is XSL 1.1 §5.3.2: `start-indent`/`end-indent` are inherited,
and border and padding are folded into them ONLY when a margin is specified. With no margin the
content rectangle stays put and the border is drawn outside it.

Measured out of FOP's area tree, which is how it was settled:

```
padding="1mm", no margin : ipd=538583  ipda=545751  space-start="-3584"
padding="1mm", margin="0": ipd=531415  ipda=538583  start-indent="3584"
```

**The wrong wording cost a second defect immediately.** My first fix rebuilt the band as a one-cell
table — five elements and a relocated page break — and then, reasoning from the same wrong model, I
added `padding-start`/`padding-end` to the sub-heading blocks. Review measured the result: the six
sub-heading rules overhung the frame containing them by 1mm each side. The reported defect, one
method later, on the same page. The whole thing is one attribute, `margin="0"`, now behind a named
helper with the measurement in its javadoc.

Also found by that review: the image tile's border was the one rule left at 0.3pt, inside the same
sub-pixel band; and a comment paragraph in `body()` had been written twice.

Measured after: band, frame and every full-width rule share x 54..1019 on page 2 — no overhang
anywhere. ~38mm of slack remains, so the thickening's ~6.8mm sat inside a comfortable margin rather
than a lucky one.

---

## BL-161 — bigger than the backlog said, and a test was pinning the bug

The entry estimated "two strings and one test". `maritalStatusLabel` took no sex at all, so
inflecting it needs a sex and a rule for choosing one — and two rules were already in play. The
form resolves sex registry-first falling back to declared (`effectiveSex`); the screen's
spouse-name row, three lines below the row it has to agree with, read `sexDeclared` alone. Left
alone, a customer whose registry and declared sex differ would see «اسم الزوج» over «متزوج» on one
screen, each row right by its own rule and the pair incoherent.

**`labels the spouse field by the customer sex` was asserting the divergence.** It overrode
`sexDeclared` to 'm' on a fixture whose `sexRegistry` is 'f' and expected «اسم الزوجة»; the form,
given that same profile, says «اسم الزوج».

All four states are asserted in the feminine, not only «متزوجة» — a map with one corrected entry
passes a married-only test and still says «أعزب» to a single woman.

---

## A failure I nearly misattributed

`setup.test.tsx`'s BL-156 guard failed twice under `test:coverage`. I first compared against a
clean tree using `npm run test` — a different command — and would have concluded "flaky". Run
properly, all four combinations:

| | no coverage | with coverage |
|---|---|---|
| clean tree | pass (263) | pass (263) |
| this branch | pass (271) | **fail** |

So it was real and it was mine: eight more tests plus v8 instrumentation push antd's asynchronous
toast mount past `waitFor`'s default 1000ms — the same slowdown `vite.config.ts` already documents
for `testTimeout`. The timeout is now explicit; the guard is untouched, its substance being the
second test, which asserts that no toast survives into a test that did not raise one.

---

## Gates, verbatim

Backend, `./mvnw verify -Pdb-integration-test`:

```
[INFO] Tests run: 1322, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 508 files clean - 0 needs changes to be clean, 508 were already clean, 0 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
MAVEN_EXIT=0
```

Back office, `npm run test:coverage` and `npm run lint`:

```
 Test Files  24 passed (24)
      Tests  271 passed (271)
All files          |   97.61 |    90.82 |    98.6 |   99.12 |
LINT_EXIT=0
```

AD-014's acceptance render runs inside the backend suite and passes, with its limit restated: it
renders its own hand-written FO and exercises neither the assembler nor the writer, so it proves
FOP's Arabic handling and **not** this layout.

---

## What is NOT proved, stated plainly

**A rendered PDF has not been matched against the two approved artboards by eye.** BL-146 blocks it
locally: `StubUqudoClient` stores the ASCII string `fake-image-bytes:<id>` under a declared
`image/jpeg` and the renderer correctly refuses it. What was reviewed — and approved by the product
owner — is the real assembler's output with decodable swatches, at
`backend/target/printed-form/approved-form-page-*.png`. The tiles there are placeholders; on
staging they become real photographs and page 1 will read denser. **That confirmation belongs to
staging**, and `docs/backoffice-redesign.md` §4 now says so.

## Carried forward

- **BL-158 and BL-159** remain open, both recorded deviations from the artboards rather than
  defects.
- **`backend/src/main/resources/brand/sfb-logo-circle.png` is now referenced by no code.** Not
  deleted: removing a committed brand asset is a decision about the bank's resources, not a
  tidy-up. The comment that wrongly claimed mobile reads it is corrected — mobile ships its own
  assets and cannot read a backend classpath resource.
- **A stale comment survived the session that was meant to remove it.** `PrintedFormVocabulary`
  still claimed a «بطاقة وطنية» divergence that BL-151 closed at S9-02, and
  `docs/backoffice-redesign.md` §2.5 asserted the note had gone with it. Corrected here — the
  general lesson being the one CLAUDE.md already states, that a comment freezing an old truth is
  self-confirming.
