# 2026-09-14 — S8-33: the printed update form (BL-132), part one

**PARTIAL BY DESIGN.** Three commits, each gated, reviewed and pushed. The brief said not to thin
the field set or skip the deployment to make the slice fit, and to stop at a clean commit boundary
instead. That is what this is. What remains is listed at the end.

| | |
|---|---|
| Commits | `2039eb0` acceptance render · `56d7d4c` renderer · `d0d7240` corrections |
| Gate | `./mvnw verify -Pdb-integration-test` — BUILD SUCCESS on all three |
| Reviewer | three passes, 21 findings, all dispositioned; one BLOCKER |
| Tiers touched | backend only. No mobile, no back office, no deployment |

## The brief's five claims, checked at source before building

All five true.

1. `app.artifact_ref` carries `UNIQUE (cycle_id, kind)` — `V0008__app_identity_artifacts.sql:82`.
2. The live kind list is V0026's nine values. V0062 amends `audit.audit_artifact`, a different table.
3. `app.profile_provenance_matrix_version` has **no writer**. Only four references exist in
   `backend/src`, all in `AppSchemaConnectivityIntegrationTest`, all reads.
4. `app.purge_abandoned_artifacts()` (V0055) is scoped to `p.status = 'abandoned'` on both branches.
5. The three named precedents exist and are the right shapes.

## Product-owner rulings taken

Four at the start, as the brief asked, rather than discovered mid-build:

| Question | Ruling |
|---|---|
| Is a printed salary certificate stored as a new artifact? | **No** — stream the existing immutable, checksummed one |
| Re-rendered or passed through? | **Passed through byte-identical** |
| Its own audit event? | **No** — one print event carrying the flag and the artifact id |
| PDF/A? | **Not now.** Plain PDF; put archival conformance to the bank separately |

Four more came later, on sight of the first rendered form, and are in "What the render changed"
below.

## AD-014's acceptance gate: FOP passed

The gate was non-negotiable — if FOP mis-shaped the face, the layout would be thrown away with the
library, because XSL-FO does not port to HTML. `FopArabicAcceptanceRenderTest` writes the PDF,
per-page PNGs, the extracted text and FOP's event log to `backend/target/acceptance-render/`. The
pages were read.

All four positional forms join, the lam-alef renders as one ligature, no word broke. The content
stream carries Arabic-block codepoints, not presentation forms — and that is a real observation
rather than a default: the font **does** map 140 U+FE70 entries (measured with `fontTools`), so the
legacy path was available and FOP declined it. Numbers hold logical order, `rl-tb` puts labels right,
page N of M resolves across three pages.

**AD-014 is no longer conditional, and its minimum-JDK `[UNVERIFIED]` is closed** —
`fop-parent-2.11.pom` sets `java.version=8`.

### Three things the render corrected that documents had not

Each produces a **finished-looking document**, which is why none would have been caught by a green
test:

1. **The research's §6 mixed-direction rule is wrong.** It said to give a Latin value its own block
   with `writing-mode="lr-tb"` and insert no control characters. Rendered, `+249912345678` prints as
   `249912345678+` — the exact defect ticket 08 predicted would "bite again in the PRINT renderer".
   Not a FOP bug: a leading `+` is bidi class ES, not between two numbers, so UAX#9 resolves it as a
   neutral and a neutral beside Arabic takes the paragraph direction. Five spellings were rendered
   side by side; the answer is `fo:bidi-override` with **`unicode-bidi="embed"`**, not
   `bidi-override` — override would render an Arabic value in a Latin-tagged field reversed letter
   by letter and still looking like text, where embed degrades to correct.
2. **`font-weight="bold"` resolves to numeric 700.** A face registered only at 600 is not a match,
   so FOP substituted Times — which has no Arabic — and every bold Arabic word became a box while
   the document still rendered and still paginated.
3. **FOP cannot resolve a `classpath:` URI**, and does not fail when a font will not load. It
   reports an event and carries on with Times. A file path would have worked here and failed inside
   the fat jar.

**FSI/PDI confirmed inert** (research R-5, predicted from source): no isolating effect at all, one
missing-glyph event each, and dropped rather than drawn — the worse of the two outcomes, because it
is silent.

## Two defects found by looking, not by testing

**Field order.** The first writer emitted every dual-source row before every single-valued one, so
field 8 (mother's name) printed after field 9 (sex). Every field was present and every value was
correct — nothing asserting what the form *says* could have caught it. Now a single in-order walk
that pairs only adjacent fields.

**A cross-customer PII leak — the reviewer's BLOCKER, reproduced live.** FOP's `ImageCache` lives on
the `FopFactory`, a long-lived singleton by design, and keys the decoded image on the URI **string**.
The image URIs were constant per slot, so the second render through one factory was a cache hit and
the resolver was never called:

```
customerA-red:    xobject Im3 200x200 centre=#FF0000
customerB-green:  xobject Im3 200x200 centre=#FF0000   <-- A's face on B's form
```

The thread-scoped registry could not have caught it — the bytes escaped one layer above the
`ThreadLocal`, which was being cleared correctly throughout. Fixed with a per-render nonce in every
image URI. The new test renders twice through one renderer with a red then a green portrait and
asserts the embedded image's colour directly, so it cannot pass against the bug.

## What the render changed — four product-owner corrections

Given on sight of the first rendered form. Recorded as an amendment on wayfinder ticket 04, and on
`field-provenance.md` item 52, rather than by quietly editing the originals.

1. **A source that cannot supply a field contributes nothing — not even «غير متاح».** Decisions 7
   and 2 together printed «غير متاح — السجل المدني» on each of the seven home-address rows. The
   registry returns the address as ONE undecomposed string, so it never had a per-level answer to
   withhold: the form was **inventing a denial the registry never made**.
2. **Uqudo is never named. It is a tool, not a source.** A branch officer needs to know which
   *document* a value came from, because that is what they can ask the customer to produce again.
   The tags are «جواز سفر» and «بطاقة قومية», with «وثيقة الهوية» when the type was not recorded —
   naming the wrong document being worse than naming none.
3. **An absence is never attributed.** Enforced in `PrintedValue`, which refuses to construct a
   tagged absence, so a later caller cannot reintroduce it.
4. **Field 51 («مستندات الهوية») is off the form.** Already "Not collected", so the row could only
   report the absence of something nobody asks for. Narrows decision 1's "all 54 fields" to 53.

**I over-applied correction 1** and the reviewer caught it: a passport MRZ *does* carry the Latin
name, so field 6 keeps its scan row. Collapsing it asserted the passport had nothing to say, which
is false. Ticket 04 now records where correction 1 stops.

## Review findings worth carrying forward

Three reviewer passes, 21 findings. Beyond the blocker and the four above, the ones that changed
code rather than prose:

- An assertion labelled "page N of M resolved" matched only the static word «صفحة» and would have
  passed with both page-number elements resolving to nothing.
- An assertion labelled "no redaction" matched a bare account-number string the body supplied
  anyway; a record component documented as proving that decision was read by nothing.
- The escaping test exercised two header fields and not the label and value paths its own rationale
  is about.
- Labelling images by caption collision made item 52's unconditional requirement depend on a
  fixture's choice of wording.
- The pom's exclusion comment claimed a `commons-logging` collision that measurement showed does not
  exist, and an `xml-apis-ext` fallback that does not exist either.

### Revert-restore proof

Required, because the correction-3 assertion is indirect. Reintroduced the tagged absence in
`FoDocumentWriter`:

```
Tests run: 1, Failures: 1 — anAbsentValueIsNeverPrintedWithASourceBesideIt
[and never with a source tag beside it]
```

Restoring the fix returns the class to green. **The first needle omitted the space `originTag` writes
before the element and passed against the bug** — that is what the proof caught, and it is why the
rule exists. The other defect-guarding test (the image leak) asserts a direct wrong value, so it
cannot pass against its bug and needs no revert.

## Gate output

Final run, verbatim:

```
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 486 files clean - 0 needs changes to be clean, 2 were already clean, 484 were skipped because caching determined they were already clean
[INFO]
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 355 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  07:15 min
[INFO] Finished at: 2026-09-14T12:22:39+02:00
```

## Where this stopped, and what remains

Stopped at a clean commit boundary with everything pushed. The remainder, in build order:

1. **The assembler** — `ProfileDetail` + reference labels → `PrintedFormDocument`, plus the
   version-pinned label lookup through the existing `ReferenceCatalog.find`. Carries BL-145.
2. **The migration** — `printed_form` and `printed_form_attributed` onto `artifact_ref_kind_check`,
   profile-keyed with `cycle_id` NULL.
3. **The provenance-matrix-version writer** — V0027/V0029 still have none, and both the footer and
   the audit payload need the value.
4. **The endpoint** — store-then-stream in one transaction, operator and admin only, viewer refused,
   non-`submitted`/`approved` refused.
5. **The audit events** — one print event naming the variant, the versions and the certificate flag;
   a separate re-download type.
6. **The back-office print action** — variant and certificate in ONE interaction.
7. **The deployment** — migrate as `fru_migrator` first, then image, then `08-backend-service.sh`,
   then the back office, then a live proof through CloudFront.

**No deferred-half comment is owed.** CLAUDE.md's rule applies when a backend half ships and a
client half is deferred; no endpoint exists yet, so no comment in any tier asserts otherwise.

## Files changed

```
backend/pom.xml
backend/src/main/java/com/sfbank/bayanati/printedform/config/{FopFactoryProvider,RenderScopedImages}.java
backend/src/main/java/com/sfbank/bayanati/printedform/domain/{FieldOrigin,PrintedField,PrintedFormDocument,PrintedFormVariant,PrintedImage,PrintedSection,PrintedValue}.java
backend/src/main/java/com/sfbank/bayanati/printedform/service/{FoDocumentWriter,PrintedFormImageUris,PrintedFormRenderer}.java
backend/src/main/resources/fop/fop.xconf
backend/src/main/resources/fonts/{IBMPlexSansArabic-Regular.ttf,IBMPlexSansArabic-SemiBold.ttf,OFL-IBMPlexSansArabic.txt}
backend/src/main/resources/brand/sfb-logo-circle.png
backend/src/test/java/com/sfbank/bayanati/printedform/{FopArabicAcceptanceRenderTest,PrintedFormRendererTest,SyntheticForm}.java
mobile/tool/fetch_and_subset_fonts.py
docs/components/pdf-rendering.md
docs/journeys/field-provenance.md
.scratch/backoffice-remaining/issues/04-form-field-set.md
PROJECT_PLAN.md · EXECUTION_PLAN.md · BACKLOG.md
```

No real customer data, no live account numbers and no identity images anywhere — the fixture's
"portraits" are generated rectangles, and the account number was deliberately moved off the seeded
stub range once the reviewer pointed out it collided with a staging account.
