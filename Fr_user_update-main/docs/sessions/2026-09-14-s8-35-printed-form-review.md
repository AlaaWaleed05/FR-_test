# 2026-09-14 — S8-35: the printed form reviewed end to end, and eight defects closed

Review, testing and proofs for BL-132's round two (`ced97b0`), which shipped without a session
report. Eight defects found, all fixed with tests. Both open decisions went to the product owner and were answered in-session, and one of them changed the footer.

| | |
|---|---|
| Reviewed | `59d1118..ced97b0 -- backend/` — the four S8-33 commits |
| Gate | `./mvnw verify -Pdb-integration-test` — BUILD SUCCESS before (1172 tests) and after (1182) |
| Reviewer | one independent pass: one BLOCKER, three should-fix, three notes |
| Tiers touched | backend only |

## What round two shipped, and what it did not

The six corrections are all on the form and correct, confirmed on the rendered page: the two
relabelled fields, one value per field with no origin tags, the Civil Registry's address string off
the form, five images including the document scan, no image labelled by origin, no version numbers
in the footer, and the attachment bundle as one opt-in in a single PDF.

**The feature is not usable.** Nothing references `com.sfbank.bayanati.printedform` outside its own
package — no endpoint, no migration adding `printed_form`/`printed_form_attributed` to
`artifact_ref_kind_check`, no back-office action. That is BL-132's declared remainder, not a
regression, but "the printing of the back office" does not exist yet: what exists is a renderer with
no caller.

Three process gaps, now closed: `ced97b0` wrote no session report, left EXECUTION_PLAN's S8-33 row
describing only round one, and left BACKLOG's BL-132 asserting the certificate is "never merged into
the form's PDF", "two separate documents", and that separate documents "avoid needing PDFBox" — the
three things round two reversed. The next session reads that file at start-up.

## The blocker: a customer's PDF contributed more than its pages

`PDFMergerUtility.appendDocument` does not copy pages. Reproduced live through `render()` with a
certificate built to carry an open-action:

```
BUNDLE /OpenAction  = COSDictionary{COSName{S}:COSName{JavaScript};COSName{JS}:COSString{app.alert('customer script ran');};}
BUNDLE /Names       = COSDictionary{COSName{JavaScript}:...}
BUNDLE /Info Author = CUSTOMER SUPPLIED AUTHOR
BUNDLE /Info Title  = CUSTOMER SUPPLIED TITLE
```

It copies the source's `/OpenAction` whenever the destination has none — and FOP output never has
one — clones the whole `/Names` dictionary, which is where `/JavaScript` and `/EmbeddedFiles` live,
merges `/Info`, and merges AcroForm fields, which is where XFA lives.

A customer uploads a payslip carrying document-level JavaScript. An operator prints with
attachments. The bytes stored as a `printed_form` artifact and streamed to the operator's browser now
carry that action, and execute it in any viewer with JavaScript enabled, under the name of a
bank-generated document. The certificate is the renderer's only untrusted input and this was the only
place it was opened.

Fixed by stripping the source catalog — `/OpenAction`, `/Names`, `/AcroForm`, `/AA` — emptying
`/Info`, and dropping page-level `/AA` and annotations, before the merge; the destination catalog is
re-stripped afterwards. What survives is the page content, which is all "print it in a separate
paper" asked for.

## Two defects that made a broken print look finished

**FOP reports a missing or undecodable resource as an ERROR EVENT, not an exception.** The resolver
throws an `IOException`, FOP swallows it, logs `Image not found` and lays the page out without the
image. Live, with portrait bytes reading `"not an image at all"`:

```
ERROR org.apache.fop.apps.FOUserAgent -- Image not found. URI: render-image:c73c0954-.../image-0.
INFO  org.apache.fop.apps.FOUserAgent -- Rendered page #1.
PROBE-1 corrupt image: render RETURNED 39557 bytes (no exception)
```

A truncated portrait therefore printed an EMPTY bordered box rather than «غير متاح» — the model
believes the image is present, so the form does not say it is missing — and a certificate stored with
`content_type` `image/png` over PDF bytes printed a blank sheet under «شهادة المرتب». Those bytes
become the print of record. `render()` now collects ERROR and FATAL events and refuses. The event ID
only is kept: a formatted FOP message can carry document content and this string reaches a log.

**Customer image bytes outlived the render in the singleton factory.** Clearing the `ThreadLocal` was
described as what stops one customer's portrait being reachable while the next form renders. Measured
after `render()` returned and `clear()` ran:

```
PROBE-2 after render, ImageCache.imageInfos holds 2 entries: [classpath:brand/sfb-logo-circle.png, render-image:d2b2af62-.../image-0]
PROBE-2 after render, ImageCache.images     holds 2 entries: [render-image:d2b2af62-.../image-0 (image/png;Raw), classpath:brand/sfb-logo-circle.png (BufferedImage)]
PROBE-3 after a SECOND render, ImageCache.imageInfos holds 3 entries
```

Both maps are `SoftMapCache` on the process-wide `FopFactory`, so a decoded face stays softly
reachable until the JVM needs the memory — outside the artifact store AD-004 exists to keep identity
images in, and reachable in any heap or core dump taken after a print. The per-render nonce that
closed the cross-customer leak is what makes it unbounded too: unique keys never overwrite. Now
cleared in the same `finally` as the ThreadLocal. Font metrics live elsewhere on the factory and are
not re-parsed; the cost is re-decoding the 10 KB logo per render.

## Two defects found by measuring the rendered page

**The absent-image box was a different height from its row-mates.** `height` on an `fo:block` is not
honoured by FOP — a block's extent is content-driven — so a present image made a box about 33mm tall
and the «غير متاح» placeholder one about 20mm, side by side in one row. Caption baselines on page 2
of the unattributed form:

```
absent cell «السجل المدني»   y=262.2
«صورة الوثيقة»               y=302.1
«وثيقة الهوية»               y=302.1
```

A 39.9pt step. Now an `fo:block-container` with a declared height and `display-align="center"`, one
constant shared with the graphic inside it. Re-measured on the same page after the fix — all three on
one baseline, with the placeholder centred in its box:

```
«غير متاح» (placeholder)     y=244.0
absent cell «السجل المدني»   y=298.0
«صورة الوثيقة»               y=298.0
«وثيقة الهوية»               y=298.0
```

**The metadata separators were not space.** The identity band wrote four literal spaces between its
four items and the continuation header three between name and reference. XSL-FO's
`white-space-collapse` defaults to true, so each collapsed to one and the four items printed as a
run-on line. PDFBox returned the whole line as a single run with single spaces at the separators.
Now `fo:leader` with a fixed `leader-length`, which collapsing does not touch.

## Tests that could not fail, and one path with no test at all

- `anImageSalaryCertificateGetsAnAttachmentPageOfItsOwn` proved its positive half with
  `assertThat(render(...)).isNotEmpty()`. Deleting the branch in `imagesOf` that BINDS the
  certificate's bytes left every test green while the operator got a blank sheet under the right
  caption — the same silent failure one layer down. It now asserts the certificate's own pixels are
  embedded, found by size.
- No fixture could produce "the profile HAS a certificate and the operator said no":
  `document(variant, includeAttachments)` tied the two together. That is the privacy-relevant
  direction of the guard. `withCertificate(certificate, false)` now exists and both certificate kinds
  are asserted to add no page.
- `PrintedFormRenderFailedException` measured **0% line coverage** while the package measured 95%, so
  the guarantee that a broken certificate fails the whole print rather than yielding a bundle
  quietly missing it was asserted by nothing. A truncated PDF now exercises it, and the same test
  asserts a FAILED render still unbinds the thread.

## Three comments freezing a pre-reversal truth

`SyntheticForm` still said the certificate "is never merged into this PDF"; `keyFor`'s note said the
image set "happens not to collide", which stopped being true when five images arrived — the document
scan, the portrait off it and the liveness frame share one origin on a passport profile, so
origin-keying would now print one image three times; and the padding-cell comment called itself
defensive when five images in a three-column grid exercise it on every form.

## Revert-restore proof

Required for the cache guard, whose assertion is indirect — it names the logo, the only image whose
URI a test can spell, since a portrait's carries the render's private nonce. Taken for all four new
guards at once, because the risk in the other three is a fixture that is not actually hostile rather
than an indirect assertion. All four reverted together:

```
[ERROR] Tests run: 23, Failures: 4, Errors: 0, Skipped: 0
[ERROR]   PrintedFormRendererTest.aCustomersPdfContributesItsPagesAndNothingElseToTheBanksForm:618 [no open-action: the customer's script does not run when the bank opens its own form]
[ERROR]   PrintedFormRendererTest.anImageThatWillNotDecodeFailsThePrintRatherThanPrintingAnEmptyBox:669
[ERROR]   PrintedFormRendererTest.everyImageBoxInARowIsTheSameHeightWhetherItsImageExistsOrNot:775 [the three captions of one image row sit on one baseline (was a 39.9pt step when the absent box was shorter than its neighbours)]
[ERROR]   PrintedFormRendererTest.noCustomerImageIsLeftInTheSharedFactorysCacheAfterARender:699 [nothing decoded during the render is still held as BufferedImage]
```

Exactly four failures and no others: each guard is proved and none of the existing 19 tests depends
on the defects.

## Checked and cleared, so the next reader does not re-check

- **The submitted-at timestamp is NOT reversed.** PDFBox extracts `06/09/2026 11:01` as
  `11:01 06/09/2026`, which looks like a bidi defect. Cropped from the render at 6x and read: the
  page is correct. It is PDFBox re-applying bidi to already-visually-ordered glyphs — the same class
  of extraction artifact this suite already records for «الفرع» and the internal-use notice.
- **Zero FOP WARN or ERROR events** across the whole gate run: no missing glyph, no font
  substitution, no area overflow.
- **`FoDocumentWriter.outputFactory` as a singleton bean is safe.** The JDK provider returns its
  cached writer only when `fReuseInstance` is set, and the default is false. Would need re-checking
  only if Woodstox reached the classpath.
- **No render output is committed.** `backend/target/` is gitignored; the only binaries in the tree
  are the logo and the two fonts.

## One finding recorded and not acted on

`PrintedFormRenderer` sits in `printedform.service` — a package CLAUDE.md reserves for business
logic — while its own javadoc says "Plumbing, not logic", and it imports `printedform.config`, the
dependency direction `PrintedFormImageUris` says it was placed in `service` to avoid. It passes the
rule's literal test (a plain JUnit test exercises it with no Spring, database, network or clock), and
every other outbound integration in this backend keeps its driver in an adapter package. Left alone
deliberately: moving it is an S1-08 scoping decision about what the 90% business-logic gate covers,
and CLAUDE.md forbids settling an open architecture decision in passing.

## Gate output

Final run, verbatim:

```
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 1182, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO]
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 356 classes
[INFO]
[INFO] --- jar:3.5.0:jar (default-jar) @ backend ---
[INFO] Building jar: C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar
[INFO]
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ backend ---
[INFO] Replacing main artifact C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar with repackaged archive, adding nested dependencies in BOOT-INF/.
[INFO] The original artifact has been renamed to C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar.original
[INFO]
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 486 files clean - 0 needs changes to be clean, 0 were already clean, 486 were skipped because caching determined they were already clean
[INFO]
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 356 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  06:50 min
[INFO] Finished at: 2026-09-14T23:14:09+02:00
[INFO] ------------------------------------------------------------------------
```

Zero FOP WARN or ERROR events in the whole run.

## The two decisions, put to the product owner and answered

**1. The appended certificate sheet. RULED AND BUILT.** Measured on the seven-sheet bundle: pages
1–6 each said «صفحة N من 6» and **page 7 returned zero text runs** — no caption, no header, no
reference, no number. An operator separating the sheets held a payslip with nothing tying it to a
form. Three options went up: a captioned lead page, a stamp on the sheet itself, or leave it.

The ruling: **leave the sheet bare, and make the bank's pages carry the count and say which sheet
the payslip is.** The certificate is the customer's own document and the bank does not write on it.
So the footer now reads «صفحة 1 من 7» with «الصفحة 7 شهادة المرتب» at the other end of the same line.

That costs a second FOP pass, and the reason is structural rather than incidental: «من M» was
`fo:page-number-citation-last`, which FOP resolves over the document FOP laid out, and the
certificate is PDFBox's. Neither the total nor the certificate's first sheet exists until the form
has been paginated once and the certificate opened, so the FO is written again with both filled in.
A guard fails the print if the second pass repaginates, rather than printing a total that is wrong.

**The count is derived, never fixed**, because the certificate and the whole attachment bundle are
optional. `theFooterTotalIsWhateverTheBundleActuallyRunsTo` writes its own measurements to
`target/printed-form/footer-shapes.txt` as it runs, so the five shapes are read rather than reasoned
about:

```
form alone                                  2 sheets   صفحة 1 من 2   (no note)
attachments, no certificate at all          6 sheets   صفحة 1 من 6   (no note)
a certificate the operator declined         2 sheets   صفحة 1 من 2   (no note)
a photographed certificate, laid out by FOP 7 sheets   صفحة 1 من 7   (no note)
a PDF certificate, stapled on afterwards    7 sheets   صفحة 1 من 7   الصفحة 7 شهادة المرتب
```

It renders five shapes — form alone,
attachments with no certificate, a certificate the operator declined, a photographed certificate FOP
lays out itself, a PDF one stapled on — and asserts in each that the number in the footer is the
number of sheets in the file. Only the last takes the second pass; the rest still use FOP's citation,
which is already right there.

Measuring the footer also caught something that had never worked. The leader meant to push the page
number to the far end took its 12pt default instead, because a space leader only stretches on a
JUSTIFIED line and this one is start-aligned. The page number sat at x=506.7 on a 595pt page, hard
against the start edge with the line's whole width unused beside it. With `text-align-last="justify"`
it now sits at x=36.8, and the note occupies the other end.

**2. Which source fills fields 5, 6, 7, 9 and 21 on a hand-completed profile. ANSWERED, AND THE
QUESTION DISSOLVES.** The product owner's answer: a profile cannot reach `submitted` without a Civil
Registry record at all, so the case never arises on a printable profile — and where there is no
registry result there is no Uqudo document either, so there would have been nothing to fall back to.
The manual path is a later piece of work in its own right. Nothing to build now.

## Files changed

```
backend/src/main/java/com/sfbank/bayanati/printedform/service/{FoDocumentWriter,PrintedFormRenderer}.java
backend/src/test/java/com/sfbank/bayanati/printedform/{PrintedFormRendererTest,SyntheticForm}.java
BACKLOG.md · EXECUTION_PLAN.md · .scratch/backoffice-remaining/issues/04-form-field-set.md
```

No real customer data, no live account numbers and no identity images. The hostile-certificate
fixture is a blank page carrying a JavaScript action and two metadata strings; every "portrait" is a
generated rectangle.
