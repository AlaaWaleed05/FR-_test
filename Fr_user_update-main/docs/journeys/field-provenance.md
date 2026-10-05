# Field provenance

Every field on the bank's paper customer-update form, which is the profile we are
digitalising. This document is the authority for where each field's value comes from.

Decisions taken by the product owner, 2026-08-23. Complete — no field is unassigned.

Version: 2 · Effective 2026-08-27

## Sources, in precedence order

- **S1 — Civil Registry.** One observed response from `POST /CRSAPI/Services/GetCRSData`.
  See `docs/components/civil-registry.md`.
- **S2 — Uqudo.** SDN national ID (two card versions) or SDN passport, per document scanned.
  See `docs/components/uqudo-sdk.md`.
- **S3 — Customer entry.**

**Default rule:** take from S1 if it supplies the field, else S2, else S3. The product owner
has overridden this default on specific fields where the mechanical answer was wrong; each
override is marked in bold with its reason.

## Everything is retained regardless

The full Uqudo JWS, the full raw Civil Registry response and the full core-banking exchange
are stored byte-identical in the audit schema. **The profile is a derived view over that
source data; both persist.** A field taken from S2 does not discard the S1 value — that value
lives in the audit artifact and simply does not populate the profile.

## Convention in this document

**Arabic text is confined to its own table column and never shares a line with Latin text**,
because mixed-script lines render unreadably. In prose, fields are referenced by number and
English name.

Legend: `Y` = the source supplies this field. Blank = it does not.

---

## Header

| # | Field | Arabic | S1 | S2 | S3 | Source | Notes |
|---|---|---|---|---|---|---|---|
| 1 | Form date | التاريخ | | | Y | System | Server-generated submission timestamp, never typed |
| 2 | Bank branch | الفرع | | | Y | S3 | Stage 1a, from the 25-branch list. Descriptive data only: the profile's identity is the account number alone (BL-032, V0061), and the branch is refreshed to the latest selection on a Stage 1b re-entry |

## Personal data

| # | Field | Arabic | S1 | S2 | S3 | Source | Notes |
|---|---|---|---|---|---|---|---|
| 3 | Customer number | رقم الحساب البنكي | | | Y | S3 | Same as the account number, stage 1a |
| 4 | Nationality | الجنسية | | Y | Y | S2 | MRZ `nationality`. The registry has no nationality field |
| 5 | Full name, Arabic | الاسم الكامل بالعربي | Y | Y | | S1 | The registry returns FOUR parts — given, father, grandfather, great-grandfather. Uqudo returns one string |
| 6 | Full name, English | الاسم الكامل بالانجليزي | Y | Y | | S1 | Registry `FIRST_NAMES` and `LAST_NAME`. Absent from the older national-ID card version, so the registry covers a real gap |
| 7 | National number | الرقم الوطني | Y | Y | | S1 | The registry echoes the value we sent as Uqudo's `identityNumber` — **answered 2026-09-04 by the product owner (AD-002b):** `IDENTITY_NUMBER` is read from the found record, not copied from the request; in a successful lookup it therefore equals the number sent, and the adapter treats any difference as `not_found`. **Built 2026-09-04 (S3-14, BL-030 closed):** `HttpCivilRegistryClient` enforces the equality guard and `app.registry_result.identity_number_returned` (V0062) stores the returned value — on `ok` and on a mismatch `not_found` alike. |
| 8 | Mother's name | اسم الأم | Y | | | **S1, all four parts** | The registry returns the maternal chain — mother, her father, her grandfather, her great-grandfather. All four become profile fields, matching the paternal chain |
| 9 | Sex | النوع | Y | Y | | S1 | **The bank uses this label for sex.** Registry `GENDER`, a lowercase single character |
| 10 | Ethnicity | الجنس | | | Y | **S3, free text** | **The bank uses this label for ethnicity.** No source supplies it. New field, mandatory |
| 11 | Country of residence | المواطنة | | | Y | **S3, ISO 3166 list** | The bank means country of residence. Mandatory. Requires the country reference list |

## Social status

| # | Field | Arabic | S1 | S2 | S3 | Source | Notes |
|---|---|---|---|---|---|---|---|
| 12 | Marital status | الحالة الاجتماعية | | | Y | S3 | Added by us to drive the spouse and children branching |
| 13 | Husband's name | اسم الزوج | | | Y | S3 | Shown only when married |
| 14 | Wife's name | اسم الزوجة | | | Y | S3 | Shown only when married |
| 15 | Has children | له أطفال | | | Y | S3 | Yes/no gate, so a customer without children answers once |
| 16 | Number of children | عدد الأطفال | | | Y | S3 | Only when the gate is yes. **NOT editable in the back office** (BL-152, product-owner ruling 2026-09-16: typed by the customer but digits, not free text) |
| 17 | Education level | مستوي التعليم | | | Y | S3 | Seven-value ordinal list |
| 18 | Occupation | المهنة | | Y | Y | **S3** | Uqudo's national-ID card carries occupation as free text printed on a card that may be years old. We need a code from the bank's 138-item list, and refreshing this is the point of the campaign |
| 19 | Monthly expenses | النفقات الشهرية | | | Y | **S3, digits only** | Constrained to digits, in SDG. The field carries a visible hint saying so. **NOT editable in the back office** (BL-152, same ruling as field 16) |
| 20 | Income source | مصدر الدخل | | | Y | S3 | Multi-select, exactly one marked primary. **Back office (S9-02): the codes and the primary flag are READ-ONLY — AD-021 forbids editing a list-picked value. Only the «أخرى» free text is editable, and only when `OTHER` is among the selected codes** |

## Birth data

| # | Field | Arabic | S1 | S2 | S3 | Source | Notes |
|---|---|---|---|---|---|---|---|
| 21 | Date of birth | تاريخ الميلاد | Y | Y | | S1 | The registry returns `DD/MM/YYYY`, **not ISO 8601**. Parse explicitly, never with a locale default |
| 22 | Birth country | البلد | | | Y | **S3, ISO 3166 list** | Corrected 2026-08-27. Neither source supplies a true birth country: Uqudo's `placeOfBirth` is the city, and MRZ `issuer` is the document's issuing country, which reads `SDN` for every customer including those born abroad. Customer entry, same list and alphabet as fields 11, 28 and 35. Defaults to Sudan |
| 23 | Birth city | المدينة | | Y | Y | **S2 `placeOfBirth`, else free text** | Confirmed: `placeOfBirth` is the city. **Back office (S9-02, product-owner ruling): editable ONLY where the scan supplied no `placeOfBirth`** — where it did, that value is what the field means, and editing `birth_city_text` would write a column nothing displays. Closes the third of BL-135's four edges |
| 24 | Birth state | الولاية | | | Y | **S3, from list** | Sudan state list when the birth country is Sudan; free text otherwise, matching the address pattern. **Back office (S9-02): editable ONLY on the free-text side, i.e. when the birth country is not Sudan** — the same derived rule as fields 29/30 and 36/37 |

## Contact

| # | Field | Arabic | S1 | S2 | S3 | Source | Notes |
|---|---|---|---|---|---|---|---|
| 25 | Phone — read-only in the back office; rendered ON SCREEN only when the channel is VERIFIED (AD-022); the printed form APPLIES THE SAME FILTER since S9-03 (2026-09-16) — an unverified or declined channel omits its row entirely, AD-022 ruling (b) | التلفون | | | Y | S3 | Verified per channel — SMS and WhatsApp separately — at stage 2 |
| 26 | Email — read-only in the back office; rendered ON SCREEN only when the channel is VERIFIED (AD-022); the printed form APPLIES THE SAME FILTER since S9-03 (2026-09-16) — an unverified or declined channel omits its row entirely, AD-022 ruling (b) | البريد الالكتروني | | | Y | S3 | Optional, verified at stage 2 |

## Work address

| # | Field | Arabic | S1 | S2 | S3 | Source | Notes |
|---|---|---|---|---|---|---|---|
| 27 | Employer | جهة العمل | | | Y | S3 | Free text |
| 28 | Country | البلد | | | Y | S3 | Cascading list |
| 29 | State | الولاية | | | Y | S3 | Cascading list, populated by country. **Back office (S9-02): editable only when the work country is not Sudan, where it falls back to free text** |
| 30 | Province | المحافظة | | | Y | S3 | Same tier as locality in our dataset. **Back office (S9-02): editable only when the work country is not Sudan** |
| 31 | Area | المنطقة | | | Y | S3 | Free text |
| 32 | City | المدينة | | | Y | S3 | Free text |
| 33 | Street | الشارع | | | Y | S3 | Free text |
| 34 | Block | المربع | | | Y | S3 | Free text |

No house number on the work address — the form carries the employer in its place.

## Home address

| # | Field | Arabic | S1 | S2 | S3 | Source | Notes |
|---|---|---|---|---|---|---|---|
| 35 | Country | البلد | Y | Y | Y | **S3** | |
| 36 | State | الولاية | Y | Y | Y | **S3** | |
| 37 | Province | المحافظة | Y | Y | Y | **S3** | |
| 38 | Area | المنطقة | Y | Y | Y | **S3** | |
| 39 | City | المدينة | Y | Y | Y | **S3** | |
| 40 | Street | الشارع | Y | Y | Y | **S3** | |
| 41 | Block | المربع | Y | Y | Y | **S3** | |
| 42 | House number | رقم المنزل | | | Y | S3 | Not in the registry string, not on the card |

**Why the whole block is customer entry, against the default rule.** The registry's address
arrives as ONE comma-separated free-text Arabic string, not decomposed into these seven
levels, and it records where the registry believes the customer lives rather than where they
live now. Refreshing the address is the purpose of the campaign. The registry string is stored
alongside for comparison and never populates the profile.

## Identity document

| # | Field | Arabic | S1 | S2 | S3 | Source | Notes |
|---|---|---|---|---|---|---|---|
| 43 | Document type | نوع الهوية | | Y | Y | **S3 chooses, S2 confirms** | The customer picks before the scan, since the SDK must know what to scan. Uqudo's MRZ `documentCode` confirms afterwards. A mismatch is an operator signal |
| 44 | Document number | رقم الهوية | | Y | | S2 | MRZ `documentNumber`. **NOT the national number** — a different field with a different meaning |
| 45 | Issue date | تاريخ الإصدار | | Y | | S2 | |
| 46 | Place of issue | مكان الإصدار | | Y | | S2 | |
| 47 | Expiry date | تاريخ الصلاحية | | Y | | S2 | Recorded but never checked — expired documents are accepted |
| 48 | Issuing country | بلد الإصدار | | Y | | S2 | MRZ `issuer` |

## Signature and attachments

| # | Field | Arabic | S1 | S2 | S3 | Source | Notes |
|---|---|---|---|---|---|---|---|
| 49 | Signature | التوقيع | | | Y | **S3, MANDATORY** | Drawn on screen or uploaded as an image; the customer chooses. Stored as a file on the profile |
| 50 | Salary certificate | شهادة مرتب | | | Y | S3, optional | Gates nothing — no status, no completion, no operator action depends on it |
| 51 | Identity documents | مستندات الهوية | | Y | | **Not collected** | No separate upload of national ID or passport. The Uqudo scan satisfies them |

## Images

| # | Item | S1 | S2 | S3 | Source | Notes |
|---|---|---|---|---|---|---|
| 52 | Portrait | Y | Y | | **Store BOTH, display BOTH** | Each labelled with its origin. **Labels corrected 2026-09-14 (S8-33) by product-owner ruling: the form names the DOCUMENT, never the tool.** They are «السجل المدني», «جواز سفر» and «بطاقة قومية», following the document actually scanned — not "Uqudo — passport" / "Uqudo — national ID", which this row prescribed until the first form was rendered and read. Uqudo is a tool, not a source: a branch officer needs to know which document to ask the customer for again, and the scanning vendor's name tells them nothing. Uqudo's is the image the face match ran against, which the registry's is not |
| 53 | Document images | | Y | | S2 | Front, back and both frames |
| 54 | Liveness audit image | | Y | | S2 | |

---

## Corrected 2026-09-14 (S8-33) — two labels, and which source the PRINTED FORM shows

**Labels.** Field 2 is «الفرع», not «المصرف». Field 3 is «رقم الحساب البنكي», not «رقم العميل».
Product-owner corrections on reading the printed form. Both are the vocabulary the rest of the
system already uses — the mobile account-entry screen and the back office's profile page both say
«الفرع» — so this document and the form were the outliers.

**Which source the form DISPLAYS, where more than one exists.** Sixteen numbered rows have more
than one source column marked `Y`. The printed form used to show both, tagged by origin; the
product owner read that and ruled one source each:

- **Civil Registry only** — 5, 6, 7, 9, 21.
- **Customer entry only** — 4, 18, 23, 43, and 35-41.

**This changes DISPLAY, not STORAGE.** The Source column above still governs what the profile
stores, and the audit schema still keeps every source byte-identical. It does mean fields 4, 23 and
43 are shown from customer entry although their Source column resolves to S2.

**Scoped to a profile submitted through the mobile app** (product owner, same day). ~~A manually
completed profile has no registry result at all, so "fields 5/6/7/9/21 come from the registry" has
no answer there — an open question for whoever builds the assembler, not a rule to invent.~~

**That open question CLOSED on 2026-09-16 (AD-022): manual completion is removed, so a profile
submitted through the mobile app is the ONLY kind there is.** The scoping caveat is now
unconditional and the assembler has no second case to handle — a simplification S9-03 depends on.

## Two form labels the bank uses differently than a literal reading suggests

Confirmed by the product owner, 2026-08-23:

- **Field 9** is the bank's label for **sex**. Source: the registry.
- **Field 10** is the bank's label for **ethnicity**. Free text, supplied by no source.

The customer still answers sex at stage 3, before any scan, because Arabic gendering and the
marital-status branching both need it. The registry's value is what the profile stores; the
customer's answer drives the interface only.

## Consequences for the rest of the specification

**A seventh reference list is required: ISO 3166 countries.** Needed by field 11, and by the
country level of both address hierarchies. The administrative-divisions dataset seeded in
S2-03 holds exactly one country row. Arabic-first, with `sort_ordinal` computed under the
Arabic collation like every other list. Tracked as S2-07.

**Ethnicity is a new field with no place in the journey.** Stage 3 must gain it, after sex and
before country of residence, following the paper form's own order.

**The signature becomes a required journey step.** It was resolved as not required on
2026-08-22 and reversed on 2026-08-23. It affirms the completed submission and the form places
it last, so it sits after liveness and before submission. It adds a second mandatory file
artifact per profile, growing AD-004's scope, and means a customer who can neither draw nor
upload cannot complete the journey. That is a deliberate consequence of the requirement.

**The maternal chain is four fields, not one**, matching the paternal chain.

**The schema now matches this document in full, Version 2 included.** S2-08 amended the `app`
schema to match Version 1 — four-part name chains, ethnicity, country of residence, birth data,
the signature and second-portrait artifact kinds, and the country foreign keys. S2-09 closed
the one remaining gap: field 22 (birth country) now has its own customer-entered
code/version/list + FK on `app.profile_customer_data`, matching fields 11/28/35, and the S2-08
generated column that mirrored the MRZ issuer is gone. R-032 remains Watching.

---

## Which fields the BACK OFFICE may edit (S9-02, 2026-09-16)

AD-015 as narrowed twice: first to the fields whose Source is S3 (2026-09-14), then by AD-021 to
the **free-text subset** of those. A list-picked value is never editable, because editing a coded
value breaks the codes filtering and export are built on. Together those bounds are what R-054
relies on: no edit can invent a scan, a face match or a registry record.

**Editability is DERIVED PER PROFILE, never a fixed list.** `operator.domain.EditableFieldPolicy`
is the authority, and the server applies it twice — once to tell the browser which fields carry an
edit affordance, and again on the write path, because a missing affordance is presentation and
presentation is never the control.

| Editable | Condition |
|---|---|
| 10, 27, 31, 32, 33, 34, 38, 39, 40, 41, 42 | Always — no source supplies them and no list constrains them |
| 13 / 14 (one column, `spouse_name`) | Only when marital status is `married` |
| 23 | Only when the Uqudo scan supplied no `placeOfBirth` |
| 20 (its «أخرى» text only) | Only when `OTHER` is among the selected income codes |
| 24 | Only when the birth country is not Sudan |
| 29, 30 | Only when the work country is not Sudan |
| 36, 37 | Only when the home country is not Sudan |

**Everything else is permanently read-only to everyone** — the 6 Civil Registry fields, the 7
Uqudo fields, the system-generated form date, both contact channels (AD-022 ruling 4), and every
list-picked customer value including fields 16 and 19.

**The often-quoted "14 fields" is an outcome, not a specification.** It is the count for one shape
of profile: Sudan-resident, Sudan-born, Sudan-employed, married, with an «أخرى» income source and
no scanned birth city. A customer living abroad has more; an unmarried one has fewer. R-042's own
note records that customers abroad are not a rare case, which is why a static list would be wrong
rather than merely imprecise.

**The editable window is `submitted` and `rejected` only**, not AD-015's literal "any status
before `approved`" — `app.profile_customer_data` is device-authoritative and every customer stage
write is a full-row replace, so an edit to a live profile would be silently destroyed by that
customer's next submission.

Rows 49-54 are ARTIFACTS rather than fields and are out of scope for field editing.
