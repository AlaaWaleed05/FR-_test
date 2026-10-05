# What exactly is on استمارة تحديث البيانات?

> **SUPERSEDED IN PART BY AD-022 (2026-09-16).** MORE THAN ONE POINT is superseded. The printed form does NOT come in an unattributed and
> an attributed variant — there is one form and the variant choice is removed (decision 7, and
> the closing "Two premises" note, both rest on it). Anything resting on the MANUAL-COMPLETION
> journey is also void: that journey was deleted at S9-01. The field set itself still stands. Everything not named above still stands and is still the reason the current design is
> what it is. This is a
> dated decision record, not a live spec — it is stamped rather than rewritten or deleted.

Type: grilling
Status: resolved
Blocked by: —

## Question

The brief fixes some of it: title استمارة تحديث البيانات, a header, a numbered footer, the name
of the operator who printed it, the print date and time, the customer's name and reference number
prominently, the date the customer submitted from the mobile app, and "the full data of the
profile".

"Full data" is the part that needs settling, field by field:

1. **Which fields.** The profile carries contact channels, address (birth / home / work), identity
   details from the scan and from the Civil Registry, occupation, income source, education level,
   and a captured signature. Is the printed form all of it, or the subset a branch actually files?
2. **Which value, where two exist.** Several fields have both a customer-entered value and a
   registry-returned value, tracked by the provenance matrix (`V0027`, `V0029`). The form must
   show one, or both labelled by origin. Which?
3. **Reference-coded fields** (occupation, admin division, country, income source, education) are
   server-supplied lists. The printed form must carry the Arabic label, and the **list version
   recorded on the profile** — not today's list, or a reprint in a year says something different.
4. **Images on the form?** Does the printed استمارة carry the document image, the portrait, and
   the signature, or is it field data only? Note `operator.md`'s existing rule for the *export*:
   "Field data only. No document images. A file of passport scans leaving the system as an email
   attachment is the highest-risk artifact this product could produce." A printed PDF is the same
   class of artifact, so this is a deliberate decision either way.
5. **Masking.** The back office masks some values on screen. Does the print unmask them?
6. **Signature block.** Does the form carry a place for the customer or the branch to sign on
   paper, given the captured digital signature already exists?

## Context

- `docs/journeys/operator.md` — Export section's images rule, and the single-profile view
- `docs/journeys/field-provenance.md` — which fields have two origins
- `backend/…/operator/domain/ProfileDetail.java`, `CustomerDataView`, `RegistryResultView`,
  `ScanResultView` — what the back office can already read
- `ExportRow.COLUMNS` — the field set already chosen once, for a different artifact (BL-020)

---

## Decision

Product-owner, 2026-09-13, by interview. Nine decisions; the two corrected premises are recorded
because a later reader will otherwise re-derive them.

1. **Field set — all 54 fields of `field-provenance.md`, in its own section order** (Header,
   Personal data, Social status, Birth data, Contact, Work address, Home address, Identity
   document, Signature and attachments, Images). That document opens "Every field on the bank's paper
   customer-update form, which is the profile we are digitalising" and is product-owner decided,
   v2, "Complete — no field is unassigned" — so the field set already existed and this ticket only
   had to confirm using it whole. **Not** `ExportRow.COLUMNS`: those 17 columns were chosen for a
   spreadsheet, not a filing document.

2. **Where two sources exist, print BOTH, tagged by origin — inline, per field.** Exactly **16 of
   the 51 numbered FIELD rows** have more than one source (nationality, both full names, national
   number, sex, occupation, date of birth, birth city, document type, and the seven home-address
   rows); the other 35 have one and print as a single untagged value. A uniform two-column grid
   across all of them was rejected: 35 half-empty rows on a filed form.

   **Item 52 (Portrait) is dual-source too** — `field-provenance.md:147`, S1 and S2 — but it is an
   image rather than a text field, and decision 4 covers it by printing both, each labelled by
   origin. Counting it as a seventeenth two-source row is also correct; it is separated here only
   because it needs a different treatment on the page.

3. **List versions — one footer line**, not per-field version tags. The requirement is that a
   reprint in a year says the same thing; a footer satisfies it without putting version noise
   beside five coded values on every form.

4. **Images — signature AND portraits; no document scans.** Both portraits, each labelled by
   origin ("Civil Registry" / "Uqudo — passport" / "Uqudo — national ID"), per field-provenance
   item 52's "Store BOTH, display BOTH" — printing one would silently pick a winner the product
   owner declined to pick. Document scans stay off, per `operator.md`'s export rule ("a file of
   passport scans ... the highest-risk artifact this product could produce"). **Consequence to
   carry into ticket 05:** the filed form now bears a face, so it is a PII artifact in its own right.

   **AMENDED 2026-09-13 (S8-25), and it closes a gap this decision had rather than changing it:**
   the salary certificate is **not on the form** and never will be. This decision listed what the
   form carries without ever weighing the certificate — the same omission ticket 02 made about
   viewing it, which BL-136 then had to correct. The product owner has now ruled deliberately: the
   certificate is a separate document with its own print, chosen by the operator at print time.
   See ticket 05 decision 9. Nothing about the form's own image set changes.

5. **No redaction — full account number and national number.** It is an internal branch filing
   document; a redacted form cannot serve as one.

6. **Signature block — the customer's captured signature only. No branch-officer line.**
   The signature may be **drawn or uploaded** (`SignatureService.ALLOWED_CAPTURE_METHODS` =
   `drawn` | `uploaded`; PNG/JPEG, 5 MB cap) and is stored **byte-identical with no image
   processing today**, so the renderer must: trim to the ink bounding box on a near-white
   threshold, honour EXIF rotation, then scale to fit a fixed signature box preserving aspect
   ratio, never upscaling more than ~2x. Thresholding uploaded photos to pure black-on-white was
   rejected — it destroys a faint pencil or ballpoint signature. `captureMethod` is stored, so the
   two paths can diverge later without a schema change.

7. **Absent data prints «غير متاح», never a blank.** A blank cell beside a customer value reads as
   "the registry agreed", which is the one misreading this form must not invite. Same rule one
   level up for a manually-completed profile, which by design has no registry values, no portraits
   and no signature at all.

8. **The form states provenance PER FIELD**, and the profile-level label is derived: `digital`
   only if every field is digital; if any single field was entered by hand, the whole profile is
   `manual`.

9. **Per-field manual entry is NOT built here.** Decision 8 describes a capability that does not
   exist: `app.profile.provenance` is one flag for the whole profile, nothing in `app` records a
   per-field source or editor, and the back office has no field-edit endpoint. Split out by
   product-owner agreement as **wayfinder ticket 10** / **BL-135**, because it turns the back
   office into a data-entry tier and that is an architecture decision, not a print decision.
   **This ticket does not wait on it:** the print renders whatever provenance the profile carries,
   so today the per-field label degenerates to the single existing flag, and the layout does not
   change when ticket 10 lands.

## Added 2026-09-13 — there are TWO forms, not one

Product-owner decision taken while settling ticket 10: the print comes in an **unattributed** and an
**attributed** variant, differing only in whether the operator who entered each manual or overridden
field is named beside it. Everything decided above — the 54 fields, the dual-source rows, the
portraits and signature, the absence rule, the version footer — is identical in both. See ticket 10
decision 7 for the reasoning and for the three consequences (two artifact kinds, the variant on the
print audit event, and both variants available to operator and admin).

## Amended 2026-09-14 (S8-33), ROUND TWO — six more, taken against the second render

The product owner read the corrected render and gave six further comments. Two supersede decisions
above, one reverses another, and one is a straight reversal of a label. All are BINDING.

**Everything below is scoped to a profile submitted through the mobile app** — the product owner
said so explicitly when the manual case was raised. See "What this leaves open" at the end.

1. **«رقم الحساب البنكي», not «رقم العميل»** (field 3), and **«الفرع», not «المصرف»** (field 2).
   Both are the vocabulary the rest of the system already uses — the mobile account-entry screen
   and the back office's profile page both say «الفرع» — so the printed form was the outlier.
   `field-provenance.md` is amended, since the assembler will read it rather than this ticket.

2. **DECISION 2 IS SUPERSEDED. No field prints two values.** Decision 2 bound 16 rows to print
   BOTH values tagged by origin. Read on a real render that put «السودان» on the page twice and
   the customer's name twice under two tags, and the product owner ruled, given the list of all 16:
   *table 1 comes from CR only, table 2 comes from customer data entry only.* Concretely:

   - **Civil Registry only** — 5 (Arabic name), 6 (English name), 7 (national number), 9 (sex),
     21 (date of birth).
   - **Customer entry only** — 4 (nationality), 18 (occupation), 23 (birth city), 43 (document
     type), and 35-41 (the whole home address).

   Two consequences worth naming. **Origin tags disappear from the body** — a tag existed to tell
   two values apart, and there are no longer two. Provenance becomes a property of the FIELD, fixed
   by `field-provenance.md`. And **the Civil Registry's address string is no longer on the form at
   all**: round one kept its one undecomposed string on field 35 so it appeared somewhere, and the
   product owner confirmed, when the consequence was put to them, that it should go. The registry's
   address is where it believes the customer lived; refreshing it is the campaign's purpose.

   This is a DISPLAY ruling. Storage is untouched — the profile still holds the customer entry, the
   scan result and the registry result separately, and the audit schema keeps every source
   byte-identical.

3. **DECISION 4 IS REVERSED: the document scan IS on the form.** The images are now FIVE — the
   identity document's front page, the portrait off that document, the Civil Registry photograph,
   the liveness frame, and the signature. Decision 4 excluded document scans by citing
   `operator.md`'s "highest-risk artifact" rule; that rule sits under `operator.md`'s **Export**
   heading and governs the bulk XLSX/CSV list, many customers' scans in one emailed file. Decision
   4 applied it to a single filed form by analogy and the product owner has reversed the analogy.
   It does not contradict `operator.md`. It does put a document scan on the filed form.

   Captions are the back office's own (`backoffice/src/profiles/artifactTiles.ts`) so an operator
   reads the same words on screen and on paper: وثيقة الهوية · صورة الوثيقة · السجل المدني ·
   إثبات الحياة · التوقيع.

   **No image carries an origin label any more.** With five distinct captions the label stopped
   distinguishing and started repeating or misattributing — the registry portrait's caption IS the
   registry's origin tag, so it printed «السجل المدني — السجل المدني», and the liveness frame would
   have printed «إثبات الحياة — جواز سفر», attributing a selfie to the passport. Item 52's reason
   for labelling (both portraits sharing one caption) no longer holds.

4. **Attachments are one opt-in bundle.** See ticket 05 decision 9, widened.

5. **DECISION 3 IS SUPERSEDED: no reference-list versions in the footer.** Page N of M stays.
   Nothing is lost from the RECORD — ticket 09 decision 2 puts the same versions in the print audit
   event payload, which is where a reprint would actually be checked. The
   `app.profile_provenance_matrix_version` writer is therefore **still required** by the endpoint
   work, even though the value no longer appears on paper.

### What this leaves open, for the assembler rather than for the renderer

- ~~**A profile with no Civil Registry result.**~~ **CLOSED 2026-09-14 (S8-35), and the question
  dissolved rather than being answered.** Put to the product owner as a choice between falling back
  to the scanned document and printing «غير متاح». Their answer: **a profile cannot reach
  `submitted` without a Civil Registry record**, so a printable profile always has one and the case
  never arises. And where there is no registry result there is no Uqudo document either, so the
  fallback that was being offered had nothing to fall back to. The manual-completion path is its own
  later piece of work; nothing is owed here.
- **Field 43's mismatch signal.** `field-provenance.md` notes "S3 chooses, S2 confirms. A mismatch
  is an operator signal." Printing the customer's choice alone removes the only place that signal
  was visible on a filed form.

## Amended 2026-09-14 (S8-33) — four product-owner corrections, taken against the first render

The renderer was built and its output read. Four things the decisions above got wrong or left
open were corrected by the product owner on sight of the rendered page. All four are BINDING and
narrow the decisions they touch.

1. **A source that cannot supply a field contributes NOTHING — not even «غير متاح».** Decision 7
   said absence must print «غير متاح» rather than a blank, and decision 2 bound 16 rows to print
   both values tagged. Together they produced «غير متاح — السجل المدني» on each of the seven
   home-address rows. The Civil Registry returns the address as ONE undecomposed comma-separated
   string, so it never had a per-level answer to withhold: that row **invented a denial the
   registry never made**. The registry's one string appears once, against field 35; fields 36-41
   are single-source rows. The same reasoning removes the scan's row from field 18 when the
   document scanned was a passport, which carries no occupation at all.

2. **UQUDO IS NEVER NAMED ON THE FORM. Uqudo is a tool, not a source.** Decision 4 and
   field-provenance item 52 both spell the portrait labels "Uqudo — passport" / "Uqudo — national
   ID", and the first renderer printed exactly that. What a branch officer needs from a filed form
   is which DOCUMENT a value was read from, because that is what they can ask the customer to
   produce again; the vendor whose SDK did the scanning tells them nothing and puts a supplier's
   brand on the bank's own document. The tags are **«جواز سفر»** and **«بطاقة قومية»**, with
   «وثيقة الهوية» when the scanned type is not recorded — naming the wrong document being worse
   than naming none.

3. **An absent value is never attributed to anything.** «غير متاح» prints alone, always, even on a
   row whose other value is tagged. A value cannot be simultaneously unavailable and read from a
   passport, from the registry, or from the customer's own data entry. This is enforced in the
   type rather than in the renderer — `PrintedValue` refuses to construct a tagged absence — so it
   cannot be reintroduced by a later caller.

4. **Field 51 («مستندات الهوية») is NOT on the form.** field-provenance.md already marks it "Not
   collected": there is no separate upload of a national ID or passport, because the scan satisfies
   them. The row could therefore only ever have reported the absence of something nobody asks for.
   **This narrows decision 1's "all 54 fields" to the 53 that have something to say.**

**Where correction 1 STOPS, recorded because the first implementation overshot it.** "A source
that never supplies this field" is not the same as "a source that had no value for this customer".
The registry genuinely cannot decompose an address, so rows 36-41 lose its row entirely — but the
passport MRZ *does* carry a Latin name, so field 6 keeps its scan row and prints «غير متاح» there
when the scan returned none. Collapsing that row asserted the passport had nothing to say, which is
false and loses real information. The first build collapsed it and a review caught it. Same test for
field 18: a passport carries no occupation at all, so for a passport profile the scan contributes
nothing; for a national-ID profile it does.

**Carried with them, same principle, found in the same render:** an image's origin tag is printed
only where it distinguishes one image from another. Both portraits share the caption «الصورة
الشخصية» and are told apart by their tags, which is item 52's actual requirement; the signature
has no second image to be confused with, so tagging it read «توقيع العميل — إدخال العميل», saying
the same thing twice.

## Two premises in the Question above that did not survive checking

- **"The back office masks some values on screen"** (Q5) — it does not. There is no masking
  anywhere in `backoffice/src`. `DestinationMasker` masks an OTP destination on the *customer's*
  screen, and `ProfileExportWriter`'s "mask" mention is CSV formula-injection sanitising. Q5 was
  therefore re-framed as a redaction question; see decision 5.
- **"The Civil Registry can be absent, so the registry column can be empty"** — true, but not by
  the route assumed. A `submitted` + `digital` profile always has a registry result (verified live:
  2 of 2). The real route is **manual completion**, which `operator.md` states carries "no scan, no
  face match, no Civil Registry lookup" — confirmed live: 3 manual-provenance profiles, zero
  registry results and **zero artifacts of any kind** between them. That is what decision 7's second
  half covers, and it takes out the registry column, both portraits and the signature at once.
