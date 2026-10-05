# Operator journey — Fr_user_update

Specification of what bank operators experience in the back-office web application.
**All policy values are settled.** Companion to `customer.md`.

**Status: agreed 2026-08-19.**

---

## Access model

**Three roles in a hierarchy** (AD-013, 2026-09-13; built at S8-27 and S8-28). This reverses
the "flat by design — there is no seniority hierarchy" model that stood until then.

| Level | Can |
|---|---|
| **Viewer** | Sign in · search and filter profiles · open a profile · view all profile data **including document images and both portraits** · view status history |
| **Operator** | Everything a viewer can, plus: **export** · **print** the update form · **approve or reject** a submitted profile · **edit customer-entered free-text fields** (BL-135, shipped 2026-09-16 at S9-02) |
| **Admin** | Everything an operator can, plus: **create and manage back-office users** — the one power that is not inherited downward |

### ~~The four-eyes rule — the one constraint~~ REMOVED 2026-09-13 (AD-013)

**There is no four-eyes rule**, and since AD-022 there is no manual completion either, so the
action it governed no longer exists. AD-013 removed the rule on 2026-09-13 (built at S8-27,
BL-131); AD-022 removed manual completion on 2026-09-16 (built at S9-01). Nothing replaces
either: no separation of duties remains over the business action, and the audit trail is the
sole compensating control. **See RISKS.md R-054**, which carries the accepted cost, is
narrowed rather than retired by AD-022, and is explicitly not to be retired until a
replacement control is decided.

**The cheap-reversal claim that stood here is no longer true, and that matters.** It read:
`is_manual_completion` "is still written … so the rule can be switched back on by restoring
one SQL conjunct, with no migration". AD-022 deleted the only writer of that column, so
restoring four-eyes would now need a write path rebuilt as well as a predicate restored. The
column is KEPT (V0067, V0071) for a different and still-valid reason: it is how profiles
completed before the ruling stay distinguishable on the status-history timeline. It is
write-never, read-still. V0009's own comment still describes the rule as enforced; V0067
records that as historical, and V0071 records the column as write-never.

Original text, kept readable as what was true until 2026-09-13:

> **An operator may not approve a profile they themselves manually completed.** Another
> operator must.
>
> This is segregation of duties, not seniority. Manual completion bypasses Uqudo, face match
> and the Civil Registry entirely; approval is what makes a profile final. Without the
> constraint, a single operator could take a profile from nothing to approved with no second
> pair of eyes anywhere in the chain — the strongest control bypass the system permits.
>
> The rule is enforced by the system, not by policy. The approve action is unavailable to the
> operator who performed the manual completion.

**Provisioning and authentication: CLOSED at S4-05 (AD-002e).** See
`docs/components/backoffice-auth.md`. Accounts are created via a CLI tool
(`auth.config.CreateOperatorAccountRunner`), never self-registered; sign-in is real
Spring Security session auth with a forced first-password-change.

**Superseded 2026-09-13 by AD-013, built at S8-28 (BL-139).** This paragraph read: "A third
role, **admin**, exists purely to create/manage accounts — it is **not** a back-office access
level: an admin signs in but reaches no `/api/v1/operator/**` endpoint." That was the ruling
AD-013 reverses, and AD-002e's own decision row points here rather than restating it, so this
is the sentence that had to change. An admin now reaches every operator endpoint.

What did NOT change: `OperatorAccessLevel` is still two-valued (viewer/operator). Admin maps
onto `OPERATOR` rather than becoming a third level — "everything an operator may" is precisely
what that level already means, and a third constant would force every `!= OPERATOR` gate in the
service tier to be rewritten for no gain. Admin's extra authority is the `/api/v1/admin/**`
surface, which is a Spring Security rule keyed on `ROLE_ADMIN`. The hierarchy lives in the
authority mapping (`OperatorUserDetails` grants an admin `ROLE_OPERATOR` and `ROLE_VIEWER` as
well), so no authorization rule mentions admin at all.

One consequence worth carrying: because admin and operator arrive at the same access level,
audit events record the caller's role explicitly as `actorRole` in the payload. Without it an
admin's approve would be byte-identical to an operator's in the chain — see RISKS.md R-054,
for which that trail is the sole remaining compensating control.

---

## Profile statuses

Derived from the customer journey's states. Operators filter on these.

### In flight

| Status | Meaning |
|---|---|
| `in_progress` | Customer is partway through. The recorded step says where. |
| `awaiting_registry` | Civil Registry unreachable or returned nothing. Not a failure — the backend retries on resume. |
| `blocked_scan` | Document-scan retry budget exhausted. Temporary. |
| `blocked_liveness` | Liveness retry budget exhausted. Temporary. |
| `abandoned` | **30 days** without activity. Remains visible to operators — the customer may still walk into a branch. |

### Terminal for the customer

| Status | Meaning |
|---|---|
| `submitted` | Journey complete. **Awaiting operator review.** This is the operator's work queue. |
| `approved` | Reviewed and accepted. Final. |
| `rejected` | Reviewed and refused, with a recorded reason. Resolution is at a branch. |
| `terminated_registry_mismatch` | Customer confirmed the national number was right but registry details wrong. Directed to a branch. |

Invalid and inactive account attempts create **no profile** and therefore no status. They
appear in the audit trail only.

### Status history, not just current status

The back office shows the **full status history** of a profile, not only where it stands.
A profile that went `submitted → rejected → approved` was resolved by a human at a branch;
one that went `submitted → approved` was accepted on its digital evidence. Those are
materially different and cannot be distinguished from the current status alone.

---

## Provenance — permanent, and never merged

Every profile carries how it was completed. This must remain distinguishable for the life
of the record, or the dashboard reports identity-verified profiles that were never verified.

| Provenance | Meaning |
|---|---|
| **Digital** | No field was keyed by an operator. |
| **Manual** | An operator keyed at least one customer-entered field. |

**Both meanings changed on 2026-09-16 (AD-022).** `Manual` used to mean "marked complete by an
operator — no scan, no face match, no liveness", which is what made a manual profile a
materially weaker artifact. That journey is gone: every profile is now a mobile submission and
therefore carries the full identity evidence. A `manual` profile is no longer weaker in
identity terms at all — it is a fully verified profile some of whose typed answers were
entered by staff rather than by the customer.

The non-merge rule STANDS, with its reason restated: the distinction must stay visible in
counts, exports and dashboard figures not because manual profiles lack identity evidence, but
because "who typed this" is a different question from "is this person who they say they are",
and only the first is what provenance now answers.

**One interim state, recorded rather than left to be discovered (S9-01).** AD-022 deleted the
only code that ever wrote `manual`, and per-field editing (BL-135) had not shipped yet.
**BOTH ENDS OF THIS GAP ARE NOW CLOSED: BL-135 shipped 2026-09-16 at S9-02, hours after AD-022.**
For that window `app.profile.provenance` was a constant `digital` for every profile created after
2026-09-16: the list filter on provenance returns no profile created after
2026-09-16 (the handful completed manually before it still match), and the export column is
uninformative for everything newer. Accepted by the product owner as the cost of sequencing S9-01 before S9-02.
Profiles completed manually BEFORE the ruling keep their stored `manual` value and still read
correctly. Tracked as BL-155, **CLOSED 2026-09-16**: V0073's `app.derived_provenance()` resolves provenance
as "the stored column already said manual, OR an operator has keyed at least one field", so the
column is write-never and both the filter and the export column are live again.

**AD-015 (2026-09-13, narrowed 2026-09-14) makes this flag DERIVED, not set**, and after AD-022
that is the ONLY way it can ever be set — there is no longer a "mark this profile complete"
action anywhere in the system. Since per-field manual entry shipped (BL-135, S9-02), a profile is
`digital` only if every field is digital, and `manual` the moment any single field was keyed by
an operator. The two values above keep their meanings;
what changes is that one hand-keyed field is enough to make a whole profile `manual`. The
identity evidence itself is never hand-keyed — Civil Registry and Uqudo fields are read-only —
so `manual` here means "some data was typed", never "the identity was asserted by staff".

---

## The profile list

The primary working surface.

- **Search across any field or combination of fields**, including status and status
  history. Account number is expected to be the most common entry point, since a customer
  standing at a branch is the usual trigger for a lookup.
- **Filter** by status, provenance, date range, branch, and rejection reason code.
- **Incomplete profiles are visible.** An operator taking over from a customer who
  abandoned at the scan step needs to see where they stopped. This was chosen deliberately
  over restricting the list to completed profiles.

**Default sort:** most recently submitted first.
**Default columns:** account number · name · branch · status · provenance · submitted date.

---

## The single profile view

Shows everything held about one customer:

- Submitted data — contact, social status, addresses, occupation
- **Verified contact channels only** (AD-022, 2026-09-16). The three states `verified`,
  `declined` and `unverified` are all still RECORDED — the customer tier writes them and
  Stage 12's notifications depend on them — but only `verified` channels are RENDERED. A
  declined or unverified channel is absent from the screen entirely: not greyed, not tagged,
  absent. A profile with no verified channel at all says so in words rather than showing an
  empty table. Contact channels are also not editable in the back office, and do not become
  editable when BL-135 shipped (S9-02, 2026-09-16), and are not editable now.
- Uqudo results. **Liveness and face-match results are still recorded separately with their
  confidence figures, and the face-match result is NOT DISPLAYED** (AD-022, 2026-09-16). The
  operator sees one verification line — the liveness chip «التحقق الحي — ناجح» in section 2 —
  plus the MRZ line. **Reworded at S9-02's rebuild (2026-09-16):** the old alert
  «اكتمل التحقق الحي بنجاح.» under a heading «نتائج Uqudo — التحقق الحي» went with the nine-block
  layout; the approved three-section screen draws both lines as chips inside section 2. Both are
  now TRI-STATE rather than present-or-absent — «ناجح» / «فشل» on `blocked_liveness` / «لم يتم بعد»
  when the profile never reached the stage, and «صحيح» / «غير صحيح» / «لم يتم بعد» for MRZ — because
  drawing «ناجح» flat, as the artboard does, asserts a pass that did not happen on a profile with
  no face result at all. The liveness state is still derived from the mere PRESENCE of a face
  result and never from its values. The match
  is still run, still stored, still audited and still queryable; what ended is surfacing it.
  **This is a real narrowing and it is the accepted cost, with the product owner's name on it**
  — a face-match failure remains the strongest fraud signal the journey produces, and an
  operator can no longer see it. R-016's mitigation narrows from "surfaced to operators" to
  "retained and queryable"; that sentence lives in `journey-open-items.md`, not in RISKS.md,
  where R-016 has been retired since 2026-09-04. **BL-154 CLOSED 2026-09-16 (S9-02):** REJ-03
  «فشل أو عدم وضوح مطابقة الوجه» was a rejection reason whose on-screen evidence this ruling
  removed, and it is now withdrawn — see "REJ-03 is withdrawn", below.
- Civil Registry data
- Document images and both portraits — one from the Civil Registry, one extracted by Uqudo —
  each labelled with its origin: "Civil Registry", "Uqudo — passport" or "Uqudo — national ID"
- The signature captured at stage 11
- The optional salary certificate, where supplied
- Full status history with timestamps and actors
- Reference number

**Images are served as downscaled derivatives in list and preview contexts.** Full
resolution is fetched only on explicit request. Originals are never re-encoded — an altered
image is worthless as audit evidence. See AD-004.

**Opening a profile is an audit event. Viewing a document image is a separate audit event.**
Identity-data access is the point of those records.

---

## Review — approve or reject

The operator's core workflow. Applies to profiles in `submitted`.

### What the review is

**The solution produces a verified identity claim. The operator judges it and decides.**

The journey establishes that a live person matched the portrait in a genuine document, and
that the document matches the Civil Registry. It does not establish that the document
belongs to the account holder — the core-banking `CheckAccount` call returns only a
found / not-found / error code (AD-007, OQ-025; formerly `ProcessOmniCheckAct` 1/2/-1), and this
system holds no prior identity data to compare against.

That is the design, not a gap: the system's responsibility ends at presenting a verified
claim with its evidence. **How an operator reaches a decision is outside this
specification** — they may consult the bank's core systems, branch records, or anything
else. The specification's job is to give them the evidence and the codes.

**Approve** → status becomes `approved`. Final.

**Reject** → status becomes `rejected`, and requires:
- **An internal reason code** from a fixed list — this is what the dashboard aggregates and
  what makes rejection patterns visible
- **A customer-facing message** derived from that code
- Optional internal detail the customer never sees

Free text alone is not accepted as a rejection reason: it cannot be aggregated, varies
between operators, and would be sent verbatim to a customer over SMS.

### Rejection reason codes

| Code | Internal reason | Customer-facing message |
|---|---|---|
| REJ-01 | Document images illegible or poor quality | Your document images were not clear enough. Please visit any branch with your original document. |
| REJ-02 | Document details conflict with Civil Registry | Your details could not be confirmed against the Civil Registry. Please visit any branch with your original document. |
| ~~REJ-03~~ | ~~Face match failed or inconclusive~~ | **WITHDRAWN 2026-09-16 (BL-154, V0074).** Not offered and not accepted. See below. |
| REJ-04 | Suspected document tampering or forgery | *(neutral)* Your update could not be completed. Please visit any branch with your original document. |
| REJ-05 | Document does not belong to the account holder | *(neutral — identical to REJ-04)* |
| REJ-06 | Entered data not usable — nonsense or contradictory free-text values | Some of the details you provided could not be accepted. Please visit any branch to complete your update. |
| REJ-07 | Other — internal detail mandatory | *(neutral — identical to REJ-04)* |

**REJ-04, 05 and 07 share one message deliberately.** Naming which control fired tells
a fraudster exactly what caught them and what to change next time. The operator sees the
real code, the dashboard aggregates on it, and the customer sees nothing that distinguishes
them. REJ-03 shared that message too, until it was withdrawn.

### REJ-03 is withdrawn (BL-154, 2026-09-16)

AD-022 ruling 3 stopped displaying face-match results. That left REJ-03 a reason an operator
could cite but could no longer see the evidence for — and the dashboard would have aggregated
it as though it had been observed. The product owner withdrew it rather than admit a narrow
exception.

**It is withdrawn by `is_active = false` (V0074), not by deletion and not by a new list
version**, and the distinction is what makes history survive:

- **Deletion is impossible.** `app.profile_status_history` carries a composite foreign key onto
  `ref.reference_item` (V0013), so any profile already rejected under REJ-03 pins the row.
- **A new list version would break the filter.** The back office resolves rejection labels and
  the reason filter against the CURRENT version only, so publishing a version 2 without REJ-03
  would make every profile already rejected under it unfilterable, and show a bare code where a
  label used to be — a worse outcome than the problem being fixed.
- **The flag withdraws it exactly where needed.** `ReferenceCatalog.exists()` filters
  `is_active`, so the server refuses REJ-03 with a 400. `ReferenceCatalog.find()` and both
  status-history label joins do NOT filter it, so every historical REJ-03 rejection keeps its
  label and its place in the filter.

The reject picker filters inactive items client-side as well, because the published reference
document deliberately still carries them — a client needs the row to resolve a label for a
historical value. Without that filter an operator would see REJ-03 in the dropdown and get a
400 on submit.

A face-match failure remains the strongest fraud signal the journey produces, and an operator
can now neither see it nor cite it. That is the accepted cost of AD-022 ruling 3, recorded here
rather than left to be discovered.

REJ-06 exists because mandatory fields prevent blanks, not nonsense: the free-text parts of
the address can hold a single character or a meaningless string.

Deliberately **not** included:
- *Contact channel could not be attributed to the account holder* — nothing in the journey
  validates contact details against any source of truth, so there is no basis for it.
- *Duplicate submission* — stage 1a blocks a completed profile before a session can start,
  and an incomplete one routes to resume. A duplicate cannot reach submission.

### Re-approving a rejected profile

A rejected customer resolves the matter at a branch. The operator can then move the profile
`rejected → approved`. This is a legitimate transition and is recorded as such in the
status history.

### Every status transition

Without exception:

1. Written to the profile database as the new current state
2. Recorded in the audit trail with **actor**, **timestamp**, **prior and new state**, and
   **reason** where one applies
3. **Communicated to the customer on all verified channels**

There are no silent state changes.

---

## Manual completion — REMOVED 2026-09-16 (AD-022)

**There is no manual completion.** The only journey that produces a profile is a mobile
submission; an operator may edit the editable fields and then approve, reject and/or print, and
nothing else. The action, its endpoint, service, repository and modal were deleted at S9-01.

Two consequences, recorded here because this is where anyone would look for them:

- **A customer who never opened the app has no route into the database — permanently.** This
  was BL-004, deferred to a later version; AD-022 supersedes it, and the product owner has
  accepted it as the standing position rather than a gap to close. There is no branch-counter
  path.
- **R-018 retires.** Its whole subject — an operator marking a profile complete with no scan,
  no face match and no registry record — cannot occur.

`app.profile_status_history.is_manual_completion` is deliberately KEPT (V0009, V0067, V0071) and
is still shown on the status-history timeline, so profiles completed this way before the ruling
stay distinguishable. It is write-never, read-still.

Original text, kept readable as what was true until 2026-09-16:

> For a customer who began in the app and then completed the process at a branch.
>
> The operator marks the profile complete. Provenance is recorded as **manual** — no scan, no
> face match, no Civil Registry lookup.
>
> It is an explicit, audited action recording who, when, and the justification — never a quiet
> flag.

**Operator actions that WRITE to the profile database are now TWO**: approve/reject, and print
(which stores an artifact and appends an audit event in one transaction). Per-field manual entry
(AD-015) became the third when BL-135 shipped at S9-02 (2026-09-16), so there are now THREE.
Everything else is read and export.

### What printing produces (S9-03, 2026-09-16; rebranded S9-06, 2026-09-18)

**Two pages, to `Design_3/backoffice/approved/printed-form-p*.dc.html`, with a BANK-branded
header.** AD-022 (k) rules the split deliberate and permanent, not a migration half-done: the
paper's letterhead carries the bank's identity and the SCREEN keeps the vendor's. **How far that
reaches is ruled precisely, by AD-022 (l): the header mark and the muted line, and no further.**
The footer still opens «AZ Omni eKYC» on every page and the palette is still the AZ one, both
deliberately — do not read (k) as licence to finish a migration that was never started. One form,
not two: AD-022 ruling 2 removed
the ATTRIBUTED/UNATTRIBUTED choice, and the attachments question remains — asked every time,
defaulting to no.

- **Every page carries the same header** — the red/purple rule, the bank's circular logo, the form
  title, the bank's name drawn in its own logo calligraphy followed by «بياناتي» in Amiri, the
  reference and the date. There is no separate continuation header; a page showing the whole
  letterhead and «صفحة 2 من 2» already says it continues. The AZ lockup and the muted internal-use
  line were both here until AD-022 (h)/(i) replaced them, and there is no internal-use notice on
  the form at all — AD-022 (k), which also records that it was never a requirement.
- **Every page carries the same three-part footer** — «AZ Omni eKYC · استمارة تحديث البيانات —
  \<reference\>» · «طبع بواسطة الموظف: \<username\>» · «صفحة N من M». The product name is the
  vendor's and stays, by AD-022 (l); it is the one place the paper still names AZ. The middle part
  is the whole of AD-022 ruling 2's justification and did not exist before S9-03: the operator who
  PRINTED the sheet is named on it, which is not the same claim as who keyed a field, and no name
  is printed beside a field. It is the USERNAME, not the display name and not the user id —
  AD-022 (n), which is also what closed BL-163.
- **Page 1** is section 1, section 2, the two verification lines and four artifact tiles. **Page
  2** is section 3 alone, in one column under six sub-headings.
- **The two verification lines are the only ones AD-022 ruling 3 permits**, and both are derived
  from PRESENCE, never from a score — the same derivation the screen uses, and for the same
  reason. A profile that never reached stage 10 prints «التحقق الحي — لم يتم بعد» rather than the
  artboards' flat «ناجح», which would assert a pass that did not happen.
- **Only VERIFIED channels appear** (AD-022 ruling 4 as read literally by ruling (b)): an
  unverified or declined channel omits its row entirely rather than printing «غير متاح».
- **An operator-edited value carries «معدَّل»**, and never the name of who edited it.
- **A one-line identity strip** under the header — customer, submission time, print time (ruling
  (a)). The reference is not on it; the header and footer each carry it independently, so a filed
  sheet can always be matched back to its profile.

**The bundle is not only the form.** When the operator asks for attachments, each present image
prints again full size on its own sheet, and a PDF salary certificate is appended by PDFBox behind
them — so the form's «من M» counts sheets FOP never laid out, and the bank's pages carry a note
saying which sheet the payslip is.

---

## Export

Produces a list of profiles matching the current filter.

- **Field data only. No document images.** A file of passport scans leaving the system as an
  email attachment is the highest-risk artifact this product could produce. Operators view
  images in the profile when they need them.
- **Every export is an audit event**, recording the operator, the filters applied, the row
  count, and the fields included. This is bulk PII leaving the system and is the
  highest-value record the operator side produces.

**Format: XLSX and CSV both** — XLSX for operators, CSV for anything downstream.
**Limit: 10,000 rows per export. No second approval** — operators already view document
images at viewer level, so requiring sign-off on a field-only export would be inconsistent.

---

## Dashboard

Aggregate figures over the campaign.

- Submissions over time
- Current status distribution, **with digital and manual provenance shown separately**
- Rejection counts by reason code — the reason the codes are a fixed list
- Completion funnel: where customers stop, by stage. Scan and liveness block counts are the
  operationally useful figures here.
- ~~Face-match failure counts, as a fraud indicator~~ — **unreconciled against AD-022 ruling 3.
  An AGGREGATE count is not a per-profile display and may well survive, but nobody has taken that
  decision. The dashboard is deferred (BL-001); settle it when the dashboard is built, not in
  passing.**

`[OPEN: the full metric list — refine when BL-001 is scheduled]`

**The statistics dashboard is BL-001 — deferred to protect the Sprint 1–2 schedule.** Core
profile navigation and review ship first.

---

## What operators cannot do

- ~~**Edit customer-entered data.** The customer owns their own answers; an operator
  correcting them silently would break the provenance model.~~ **NO LONGER TRUE — AD-015
  (2026-09-13, narrowed 2026-09-14) makes the back office a data-entry tier: an operator MAY
  enter or correct any customer-entered field.** The provenance model is preserved rather than
  broken, because the edit is not silent: the field is flagged manual, the entering operator and
  timestamp are recorded, the audit event carries the old and new values, and a profile is
  `digital` only if every field is digital. Editing stops at `approved` and is never permitted
  after. **BUILT IN FULL 2026-09-16 (S9-02) — backend and editing UI.** This bullet described
  the intended design rather than the running system until then; it now describes both. Two
  details the build settled: the editable set is DERIVED PER PROFILE rather than fixed, and the
  window is `submitted` and `rejected` only, not AD-015's literal "any status before `approved`"
  — `app.profile_customer_data` is device-authoritative and every customer stage write is a
  full-row replace, so an edit to a live profile would be silently destroyed by that customer's
  next submission.
- **Edit or override Uqudo or Civil Registry data.** It is authoritative by design.
  **This one STANDS, and 2026-09-14 is when it stopped being accidental.** AD-015 as first
  written said "ANY field", which would have removed it; the product owner narrowed AD-015 the
  next day so that the 6 Civil Registry fields and the 7 Uqudo fields are permanently
  read-only — see `docs/journeys/field-provenance.md` for exactly which, by source column.
  An operator can never manufacture an identity: no edit can invent a scan, a face match or a
  registry record. This is the bound R-054 relies on, and AD-022 STRENGTHENS it — with manual
  completion gone, an operator cannot produce a profile at all, only edit one the customer
  submitted.
- **Delete a profile or any audit record.** The audit trail is append-only.
- ~~**Approve a profile they manually completed.**~~ **The action no longer exists.** AD-013
  removed the four-eyes rule on 2026-09-13; AD-022 removed manual completion itself on
  2026-09-16. An operator cannot complete a profile at all, so there is nothing of their own to
  approve. What R-054 now carries is narrower and still uncontrolled: one operator may edit a
  submitted profile's editable fields and then approve it unaided.
- **Mark a profile complete.** AD-022. A profile becomes `submitted` only when the customer
  submits it from the app.
- **Edit contact channels.** AD-022 ruling 4. Fields 25 and 26 are read-only in the back office,
  and stayed read-only when BL-135 shipped (S9-02, 2026-09-16).
