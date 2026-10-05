# Back-office redesign — the build specification

The single source for implementing AD-021 and AD-022. Three sessions, specified below in the
order they must run. Written 2026-09-16, after the design was approved.

**Read this with:** `PROJECT_PLAN.md` (AD-013, AD-015, AD-018, AD-021, AD-022),
`docs/journeys/field-provenance.md` (the field numbers used throughout), `docs/journeys/operator.md`
(the document most in need of reconciliation).

---

## 1. What was approved, and where it lives

The approved design is committed as four artboards. **Read them from disk. They are the build
target; this document describes them, it does not replace them.**

| File | What it is |
|---|---|
| `Design_3/backoffice/approved/login.dc.html` | Sign-in |
| `Design_3/backoffice/approved/profile-screen.dc.html` | The profile screen, all three sections |
| `Design_3/backoffice/approved/printed-form-p1.dc.html` | Printed form, page 1 |
| `Design_3/backoffice/approved/printed-form-p2.dc.html` | Printed form, page 2 |

They are `.dc.html` — the same format as the existing mocks under `Design_3/backoffice/`. Open
them in a browser or read the markup; the inline styles carry every value.

### Brand assets, all committed under `Design_3/assets/`

| File | Use |
|---|---|
| `az-login-panel.jpg` | The sign-in's left panel, whole. **Already corrected** — see §2.5 |
| `az-lockup.png` | Bilingual AZ mark, transparent ground, dark artwork. Screen header ONLY since S9-06 — AD-022 (h) took it off the print header |
| `az-lockup-white.png` | Same mark reversed to white, for dark grounds |
| `az-watermark.jpg` | Behind the printed pages until S9-06; AD-022 (j) removed it. Unreferenced, deliberately kept |
| `sfb-logo-circle.png` | The bank's roundel. The print header's mark since S9-06, AD-022 (h) |
| `sfb-wordmark-ar-navy.png` | The bank's name in its logo's calligraphy, cropped to the Arabic line by `backend/tool/prepare_brand_assets.py`. Print header, S9-06 |
| `az-header.jpg` | The full letterhead band. Not used by the approved design; kept as the source of truth |
| `az-lockup.jpg` | White-ground version of the lockup. Superseded by the `.png`; kept for reference |

The backend ships brand assets from `backend/src/main/resources/brand/` — the printed form is
rendered server-side by Apache FOP and cannot read `Design_3/`. S9-03 added the AZ pair there;
S9-06 added `sfb-wordmark-ar-navy.png` beside the roundel that was already present, and stopped the
form referencing either AZ asset. Both are kept: removing a committed brand asset is a decision
about what the bank's resources hold, not a tidy-up.

### The palette, sampled and approved

| Token | Value | Role |
|---|---|---|
| Purple | `#4B237E` | Primary action, section accents, reference number |
| Purple dark | `#351A63` | The sign-in panel's gradient |
| Blue | `#234B8F` | The sign-in panel's gradient |
| Text | `#20212A` | Body ink, and every rule and label derived from it |
| Border | `#CFD1D8` | Control borders on the sign-in |
| Ground | `#EEF0F4` | The page behind the cards |
| Red | `#E4312A` | Reject, and the accent in the two-colour rule |
| Success | `#2F7D5D` | The liveness chip only |

**The grey `#ABADAC` is removed** — the rule is red and purple, the purple taking the grey's
former width. **The red has no counterpart in the AZ login palette**; it comes from the
letterhead and stays until the product owner supplies a replacement.

**AZ branding is back-office only.** The mobile app is the bank's and keeps the SFB identity.

---

## 2. The four rulings of AD-022, and what each one touches

### 2.1 Manual completion is removed, not deferred

**The only journey that produces a profile is a mobile submission.** An operator may edit the
editable fields, then approve, reject and/or print. Nothing else.

Delete, do not deprecate:

| Tier | Surface |
|---|---|
| backend | `operator/web/ManualCompletionController.java`, `ManualCompletionRequest`, `ManualCompletionResponse` |
| backend | `operator/service/ManualCompletionService.java` |
| backend | `operator/domain/ManualCompletion{Outcome,Repository,State}.java`, `ManualCompletionJustificationRequiredException.java` |
| backend | `operator/jdbc/JdbcManualCompletionRepository.java` |
| backoffice | `profiles/ManualCompleteModal.tsx` + test, the `manualCompleteProfile` client, its type, the button on `ProfileDetailPage.tsx` |

**Do NOT delete** `app.profile_status_history.is_manual_completion` (V0009, V0067). Profiles
completed manually before this ruling keep their history, and the column is how they stay
distinguishable. It becomes write-never, read-still.

**Provenance stays meaningful and must not be hardcoded to digital.** AD-015 makes provenance
derived: `manual` the moment any single field was keyed by an operator, which per-field editing
(S9-02) still produces. What changes is that provenance can no longer mean "no identity evidence
at all".

> **CORRECTED AT S9-01, 2026-09-16.** The paragraph above describes the INTENDED design, not the
> running code. Provenance was never derived: `app.profile.provenance` is a stored column whose
> ONLY writer anywhere in the system was `JdbcManualCompletionRepository`, the file this ruling
> deletes. Deleting it therefore leaves provenance a constant `digital` until BL-135 ships
> per-field editing. The product owner accepted that interim state rather than landing S9-01 and
> S9-02 together; it is tracked as BL-155, and the dead list filter, the uninformative export
> column and the unreachable manual-provenance Alert are deliberately left standing for S9-02 to
> close.
>
> **BL-155 CLOSED 2026-09-16 at S9-02, and the interim state is over.** Not by restoring a writer:
> V0073's `app.derived_provenance()` resolves provenance as "the stored column already said
> manual, OR an operator has keyed at least one field", so `app.profile.provenance` is write-never
> and the filter, the export column and the screen banner are all live again. The `p_stored =
> 'manual'` disjunct is load-bearing — three profiles completed manually before AD-022 carry it in
> the column with no rows in the new table, and dropping it would silently re-label them
> `digital`.

Knock-ons to reconcile, not to leave standing:

- **R-018 retires.** Its whole subject — an operator marking a profile complete with no scan, no
  face match, no registry — becomes impossible.
- **R-054 narrows.** One operator can still edit fields and approve, but can no longer create a
  completed profile.
- **BL-004 is superseded.** A customer who never opened the app has no route into the database,
  now permanently rather than until V2. Record it as accepted.
- **`operator.md`'s "Manual completion" section, the provenance table, and "What operators
  cannot do"** all describe the removed behaviour.
- `PrintedFormAssembler` and `ProfileNotPrintableException` reference manual completion; check
  what they actually do before touching them.

### 2.2 The printed form loses its variant choice

The approved form **always names the operator who printed it**, in the page footer
(`طبع بواسطة الموظف: <username>`). The product owner judges that sufficient, so the
ATTRIBUTED / UNATTRIBUTED choice is removed.

> **CORRECTED AT S9-01, 2026-09-16.** That footer does not exist yet — the string appears in the
> approved artboards and nowhere in `backend/`. What the code renders today is
> `المشغّل الطابع:` in the page-1 identity band, on BOTH variants, and building the real footer
> is S9-03's job (§4). What the ATTRIBUTED variant actually added was a navy banner plus the
> operator's name beside each hand-keyed field — that name was the PROFILE-level manual
> completer, not a per-field editor, and it did render. S9-01 removed the choice and the banner
> and kept the identity-band line, so no print discloses less than the default one an operator
> already got; the product owner accepted the gap until S9-03.

- `printedform/domain/PrintedFormVariant.java` — collapses to one form, or the enum goes
- The two artifact kinds in V0070 (`printed_form`, `printed_form_attributed`) — **a new migration**;
  do not edit V0070, it is applied (see BL-144 for why that matters)
- `backoffice/src/profiles/PrintFormModal.tsx` — the two-way choice goes; the attachments
  question **stays**, still asked every time and still defaulting to no
- `printProfileForm`'s `variant` field, and `PrintedFormController`'s handling of it

**The attachments option remains exactly as it is.**

### 2.3 Face-match results are not displayed

On screen and on the form, the only verification line is the existing
«التحقق الحي — ناجح», plus the MRZ line. The face-match block, its confidence figure and its
`Alert` go.

**This is a display ruling and nothing else.** The face match is still performed, still stored,
still audited. `LivenessService`, `ProfileDetail.faceResult`, `ProfileDetailResponse.faceResult`
and the underlying columns stay. Do not remove the capture path.

**R-016 must be amended rather than retired.** Its mitigation currently reads that both results
are "recorded separately and surfaced to operators". Surfacing ends; recording does not. Write
the narrowing down as the accepted cost, with the product owner's name on it.

> **CORRECTED AT S9-01, 2026-09-16.** That sentence is not in `RISKS.md`. R-016 there has been
> **retired since 2026-09-04** and is about the CUSTOMER being unable to tell the two failures
> apart. The mitigation quoted above lives in `docs/journeys/journey-open-items.md`, and that is
> where the narrowing was applied. A loose end the ruling does not settle is filed as BL-154:
> REJ-03 is still a rejection reason whose on-screen evidence has now been removed.

### 2.4 Contact channels: read-only, verified only

- Not editable in the back office — no edit affordance on fields 25 and 26. **Already true at
  S9-01: the screen has no edit affordance on ANY field yet (BL-135). The force of this is as a
  constraint on S9-02, which must not add one here.**
- **Only VERIFIED channels are rendered**, ~~on screen and on the printed form~~ **on screen —
  S9-01**. Declined and unverified channels are not shown at all — not greyed, not tagged, absent
- ~~The printed form's `contact()` in `PrintedFormAssembler` currently prints phone and email
  unconditionally and carries no channel state; it needs the state and the filter~~ **— THIS IS
  S9-03, not S9-01 (product-owner ruling, 2026-09-16), which is what §5 already said. Doing it in
  S9-01 is work S9-03 deletes: S9-03 rewrites `sections()` from nine sections to three and the
  «الاتصال» section holding these two fields does not survive it. `ProfileDetail.channels` is
  already on `PrintedFormSources`, so S9-03 needs no new query.**
- `ProfileDetail` already carries `List<ChannelStateView> channels`, so the data is on hand

This settles BL-152's phone/email question. ~~BL-152's remaining question — whether the digits-only fields (16 number of children,
19 monthly expenses) count as free text — is still open and must go to the product owner in
S9-02.~~ **ANSWERED 2026-09-16 by product-owner ruling: fields 16 and 19 are NOT editable.
BL-152 closed.**

### 2.5 Already done, do not redo

- The garbled Arabic wordmark. `az-login-panel.jpg` and `az-lockup*.png` carry the correct
  «شركة ايه زيد تكنولوجي المحدودة». The supplied `az-logo.png` in the asset pack does **not** —
  never use it. See BL-151's neighbour note in the 2026-09-15 session report.
- ~~**BL-151 is still open**~~ **BL-151 CLOSED 2026-09-16 at S9-02**:
  `backoffice/src/profiles/detailLabels.ts` said «بطاقة وطنية» where every other tier says
  «البطاقة القومية». Two strings, three assertions and one comment; `PrintedFormVocabulary`'s
  stale divergence note went with it.

---

## 3. The screen: three sections, and the field spec

Section order, headings and badges are in `profile-screen.dc.html`. The field numbers below are
`field-provenance.md`'s.

### Section 1 — بيانات الهوية · from the Civil Registry, wholly read-only

Fields **5, 6, 7, 8, 9, 21**. Taken from the registry as they come, with **no comparison against
the scanned document** (AD-021). The registry's own address is *not* shown here.

### Section 2 — التحقق من الهوية · read-only

~~Seven artifact tiles (registry portrait, document portrait, document front, document back,
liveness image, signature, salary certificate)~~ **SIX — product-owner ruling 2026-09-16,
BL-159. `doc_back` is refused server-side by `OperatorImagePolicy.viewableKinds()` (wayfinder
ticket 02, "the set is the set") and the image endpoint 404s it, so a seventh tile renders a
broken image. The set is: registry portrait, document portrait, document front, liveness image,
signature, salary certificate** — the two verification lines, then document fields
**43, 44, 45, 46, 47, 48**.

**Built at S9-02 with two additions this sentence did not specify.** Each tile carries a SECOND
caption line naming its source («السجل المدني» / «البطاقة القومية» / «التحقق الحي» / «وقّعه العميل»
/ «أرفقها العميل»), because the artboard captions both portraits «الصورة الشخصية» and nothing else
tells them apart. For the two document-derived tiles that line follows the document ACTUALLY
scanned, so a passport customer reads «جواز سفر» — the artboard's fixture is a national-ID
customer, and hardcoding its string would have mislabelled every passport profile.

### Section 3 — البيانات المُقدَّمة من العميل · six sub-sections

Mirrors the mobile app's own segmentation. **14 fields are editable — exactly those the customer
typed as free text.** Everything else is list-picked and must not be editable, because editing a
coded value breaks the codes filtering and export are built on.

| Sub-section | Fields | Editable |
|---|---|---|
| الحساب والفرع | 3, 2 | none |
| قنوات الاتصال | 25, 26 | none — §2.4 |
| البيانات الشخصية والاجتماعية | 4, 10, 11, 12, 13, 15, 16, 17, 22, 24, 23 | **10, 13, 23** |
| المهنة والدخل | 18, 19, 20 | **20** |
| عنوان السكن | 35, 36, 37, 38, 39, 40, 41, 42 | **38, 39, 40, 41, 42** |
| جهة العمل عنوانه | 27, 28, 29, 30, 31, 32, 33, 34 | **27, 31, 32, 33, 34** |

> **CORRECTED AT S9-02, 2026-09-16, and the table above is INCOMPLETE as written.** Five things
> the build found: (1) **field 20 cannot be editable as drawn** — income source is a coded
> multi-select and this section's own rule forbids editing a list-picked value, so the product
> owner ruled the chip reaches only its «أخرى» free text, and only when `OTHER` is selected;
> (2) **field 23 is NOT unconditionally editable** — BL-135 lists it as an open edge and names
> birth city among the never-editable Uqudo fields, and the ruling is that it is editable only
> where the scan supplied no `placeOfBirth`; (3) **the derived rule applies to fields 24, 29 and
> 30 as well as 36/37** — identical column structure, identical journey rule; (4) **field 14
> «اسم الزوجة» is missing from the table entirely**, sharing `spouse_name` with field 13;
> (5) ~~**field 4 cannot render as drawn** — its only source is the MRZ alpha-3 code and no
> alpha-3-to-alpha-2 mapping exists (BL-157)~~ **— WRONG, AND REVERSED AT S9-02's SECOND HALF.
> The mapping does exist and is server-supplied: `V0022__seed_country.sql` seeds `extra` as
> `{"alpha3":"SDN"}` for all 249 country rows, and the reference document carries `extra` to the
> browser untouched. Fields 4 AND 48 now resolve to «السودان» through that list, hardcoding
> nothing, falling back to the raw code for an ICAO value with no ISO row (`XXA`, `GBD`, `RKS`).
> BL-157 closed.** The authoritative editable set now lives in
> `docs/journeys/field-provenance.md`, "Which fields the BACK OFFICE may edit".

**Editability is derived per profile, not hardcoded.** `customer.md` stage 5: for an address
outside Sudan, state and locality fall back to **free text** — so fields 36 and 37 are editable on
such a profile and not on a Sudan one. A static list is wrong for every non-Sudan address, and
R-042's own note records that customers abroad are not rare. Derive it from what was stored.

Editing stops at `approved` and is never permitted after (AD-015).

### The action bar

اعتماد and رفض, 54px, centred as a pair with the notification line beneath them; طباعة smaller at
the end of the same row. **No «إكمال يدوي».**

---

## 4. The printed form

Two pages, `printed-form-p1.dc.html` and `printed-form-p2.dc.html`.

> **BUILT 2026-09-16 at S9-03**, across six commits on `main` (`e7b3728`, `c99fcea`, `7ffeba5`,
> `b907e37`, `661e08c`, `20798fe`). Everything below is implemented unless a note says otherwise.
> **A rendered PDF has NOT been matched against the artboards by eye — BL-146 blocks that
> locally** (`StubUqudoClient` stores the ASCII string `fake-image-bytes:<id>` under a declared
> `image/jpeg` and the renderer correctly refuses it), so the confirmation §5 asks for is a
> STAGING check. What was read instead is the real assembler's output with decodable swatches,
> written to `backend/target/printed-form/approved-form-page-*.png` by
> `PrintedFormAssemblerTest.rendersTheApprovedFormForEyesOn`; the product owner reviewed those
> pages and approved them.

- **Page 1** — header (the bank's roundel, form title, the bank's wordmark and «بياناتي»,
  reference, date), section 1, section 2's document fields, the two verification lines, four
  artifact tiles.
  **CORRECTED 2026-09-16:** this line listed the PRODUCT NAME in the header. The artboards put it
  in the FOOTER — «AZ Omni eKYC · استمارة تحديث البيانات — SFB-…» — and the header carries the
  mark, title, bank line, reference and date only. Built to the artboards, not to this
  description.
  **CORRECTED 2026-09-18 (AD-022 (h), (i)):** the header's mark was the AZ lockup and its bank line
  was «البنك السوداني الفرنسي — للاستخدام الداخلي» in muted text. The paper now carries the
  bank's identity: the roundel, and the bank's name as an IMAGE in its own logo calligraphy
  followed by «بياناتي» in Amiri. The internal-use notice is not printed. **CORRECTED AGAIN
  2026-09-18 (AD-022 (o), S9-07): the artboards no longer draw the old header.** They were
  redrawn to match the code, so the departure is recorded in AD-022 alone and not visible as a
  disagreement between the drawing and the build. The as-approved drawing is recoverable at
  `git show 62f28fa:Design_3/backoffice/approved/printed-form-p1.dc.html`
- **Page 2** — section 3 in one column with its six sub-headings; edited values carry «معدَّل»
- **Footer, both pages** — three parts: product and reference · `طبع بواسطة الموظف: <username>` ·
  page number. The middle part is §2.2's whole justification
- **No watermark.** It was behind both pages until S9-06; AD-022 (j) removed it, along with the
  `fox:` extension that sized it. **CORRECTED 2026-09-18 (AD-022 (o), S9-07): the artboards no
  longer draw it either** — they were redrawn, so this departure too lives in AD-022 rather than
  in a gap between drawing and build
- **No declaration block and no signature lines** — the customer signs in the app, and the
  signature is on page 1
- Attachments still append, still asked every time, still defaulting to no

~~**The open question S9-03 must put to the product owner before building:**~~ **ANSWERED
2026-09-16 by the product owner — S9-03 IS NO LONGER BLOCKED ON THIS.** The form today follows
the bank's paper form in nine sections; the approved design has three, so a branch officer
comparing print to paper form finds a different running order. **The recently approved design
OVER-RULES the old paper order.** Build the three-section form; the paper form's running order is
not a constraint on it.

---

## 5. The three sessions

**Every session, in this order, without exception:**

1. **Research and review before editing.** Run `@agent-researcher` where a third-party contract is
   involved, and `@agent-reviewer` against this brief and the real code. This document is written
   from a reading of the code but is not a substitute for checking it.
2. **Take anything wrong back to the product owner.** If the brief contradicts the code, or a
   ruling cannot be implemented as written, stop and say so. Do not settle it in passing.
3. **Reconcile the plan and journey documents** — `PROJECT_PLAN.md`, `BACKLOG.md`, `RISKS.md`,
   `EXECUTION_PLAN.md`, and the journey documents each session's scope touches.
4. **Implement with tests.**
5. **Run the gates for every tier touched and paste the output verbatim**, then commit and push.

### S9-01 — Remove what the design removes

**Why first: it deletes surface that S9-02 and S9-03 would otherwise rebuild.**

Scope: §2.1 manual completion, §2.2 the printed-form variant choice, §2.3 the face-match display,
§2.4 the channel filter — the *removals* only, in both tiers, plus every document that describes
them.

Gates: `./mvnw verify -Pdb-integration-test` · `npm run test` · `npm run lint`

Done when: no manual-completion surface remains outside the history column; one printed-form kind;
no face-match display; only verified channels rendered ON SCREEN (the form is S9-03);
`operator.md`, `customer.md`, R-016, R-018, R-054, BL-004 all reconciled; gates pasted.
**DONE 2026-09-16** — see `docs/sessions/2026-09-16-s9-01-ad-022-removals.md`.

### S9-02 — Per-field editing, and the back office rebuilt to the design

**The biggest of the three. It is AD-015's actual build (BL-135), not just a restyle.**

Scope: the per-field edit endpoint, its audit events and its provenance effect; the three-section
screen; the approved palette; the sign-in; BL-151's wording fix; and the derived-editability rule
in §3.

~~Ask the product owner: BL-152's digits-only question.~~ **Asked and answered 2026-09-16 — not editable.**

Gates: `./mvnw verify -Pdb-integration-test` · `npm run test:coverage` · `npm run lint` · `npm run build`

Done when: the 14 free-text fields are editable and audited, list-picked fields are not, editability
is derived per profile, the screen matches `profile-screen.dc.html`, the sign-in matches
`login.dc.html`, BL-151 is closed, gates pasted.

**DONE 2026-09-16**, in two sessions — see `docs/sessions/2026-09-16-s9-02-per-field-editing.md`
(the backend half) and `docs/sessions/2026-09-16-s9-02-backoffice-rebuild.md` (the rebuild).
Deviations from this specification, each ruled and recorded: six artifact tiles not seven
(BL-159), no queue position (BL-158), the status history kept though the artboards omit it, and
fields 25/26 gated on channel verification. BL-157 was closed by REVERSING its premise — see §3's
correction block. Filed on the way out: BL-160.

### S9-03 — The printed form restructured and branded

Scope: `PrintedFormAssembler` from nine sections to three; the header, footer and watermark; the AZ
assets into `backend/src/main/resources/brand/`; the verified-channels filter on the form; the
attachments path unchanged.

Ask the product owner **first**: §4's three-sections-versus-nine question.

Gates: `./mvnw verify -Pdb-integration-test`, plus AD-014's acceptance render.

Done when: a rendered PDF matches the two approved artboards, the footer names the printing
operator, the watermark is present, attachments still append, gates pasted.
**DONE at S9-03, and superseded on two points since:** the watermark criterion by AD-022 (j) and
the header criterion by AD-022 (h)/(i), both S9-06 — the form deliberately no longer matches the
artboards' branding. The rest of the ticket stands as written.

### Order

**S9-01 → S9-02 → S9-03.** S9-01 clears the ground. S9-02 and S9-03 are independent of each other
once it is done and could run in either order, but S9-02 first is better: it settles the editable
set, and the printed form has to mark edited fields «معدَّل».

Deployment follows S9-03, by `docs/road-to-production.md`.
