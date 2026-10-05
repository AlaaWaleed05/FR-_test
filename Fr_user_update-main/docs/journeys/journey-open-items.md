# Journey work — items for the repo plan files

Everything the journey specification raises that must land in `PROJECT_PLAN.md`,
`RISKS.md`, `BACKLOG.md` or `EXECUTION_PLAN.md`. Produced alongside `customer.md` and the
operator journey. Nothing here lives only in conversation.

Source: `customer.md`, `branches.md`, and the planning discussion of 2026-08-19.

---

## New architecture decisions

**AD-004 — Image and artifact storage.**
Up to six Uqudo images per profile (document front, document back, both frames, extracted
portrait, liveness audit-trail image), plus a second portrait supplied by the Civil Registry,
a mandatory signature file, and one optional salary certificate — nine artifact kinds per
profile in total. Several megabytes per profile, across a national campaign, retained
permanently because completion is terminal and audit requires it.

Direction established, not yet settled: object storage with database references, checksums
and metadata — not BLOBs in the relational database, where they would bloat backups, slow
restores, and burden every ordinary profile query. Encryption at rest is mandatory for
identity documents. The operator UI must read **downscaled derivatives**, since a list view
pulling full-resolution passports is unusable. **Originals are never re-encoded** — an
altered image is worthless as audit evidence.

Blocked on hosting, which is blocked on OQ-001 and OQ-002. Recorded now so it is not
settled by whoever writes the first upload handler.

**AD-002 scope addition — reference data delivery.**
No list is hardcoded: occupations, branches, administrative divisions, income sources and
rejection reason codes are all server-supplied, fetched and cached by the app, version-
checked on each connection, and served from cache offline. The list version used for a
submission is recorded on the profile, because a locality code under the interim dataset may
not mean the same thing under the corrected one. This touches the same persistence layer as
the resume model and must be designed in rather than retrofitted.

**AD-002 scope addition — the audit trail is a new architectural component.**
Append-only, hash-chained, tamper-evident storage, structurally separate from the profile
database, with its own access control and its own retention. This was not in AD-001's scope
and is not in the module map. It is a distinct persistence concern and belongs in AD-002
alongside the other integration decisions.

---

## New open questions

| Ref | Question |
|---|---|
| Admin-divisions dataset | The interim file is missing West Kordofan, Central Darfur and East Darfur; its confidence column is absent though the README describes it; and its States/Regions sheets still contain South Sudan. See `customer.md` stage 4. |
| Operator authentication | How operator accounts are provisioned and authenticated — part of AD-002 back-office auth. |
| Export format | CSV, XLSX, or both. |
| Dashboard metrics | The full metric list (dashboard itself is BL-001, deferred). |
| Default list columns | Columns and sort order for the profile list. |

## Resolved during the journey work — record, do not re-raise

- **رقم العميل — **relabelled «رقم الحساب البنكي» on the printed form, 2026-09-14; see field-provenance.md field 3** is the account number.** Same identifier. Profile is keyed on branch +
  account number.
- **Identity fields are never typed.** The paper form assumed a clerk transcribing from a
  document; Uqudo supplies those fields from the scan.
- **Document expiry is not checked.** A genuine but expired document is accepted; only
  forgery matters, and that is Uqudo's job.
- **Both document types carry the national number.** No customer reaches stage 8 without a
  number to look up.
- **Exactly one optional attachment** — the income or salary certificate. Stored on the
  profile, validated by nothing, gates nothing.
- **Branch list is definitive** — 25 branches, numbered 2 to 26, no branch 1.
- ~~**No signature.**~~ **Superseded 2026-08-23 (S2-06).** The signature is now mandatory —
  Stage 11 of `customer.md`. See `docs/journeys/field-provenance.md` field 49.
- **Education level** — 7-value ordinal list, proposed and approved: أمي · يقرأ ويكتب ·
  أساس · ثانوي · دبلوم/معهد فني · جامعي · دراسات عليا.
- **Occupation** — bank-supplied coded list of 138, names used exactly as supplied,
  duplicates resolved to the smaller code, code 33 اخرى is the catch-all, **no free text**.
- **Income source** — short list, **multi-select with one marked primary**, أخرى opens free
  text.
- **All fields mandatory for every occupation in v1**, including work address for students
  and retirees. The collapse logic is deferred to v2 pending a cleaner occupation list.
- ~~**Nationality, citizenship and birth date are Uqudo-derived; birth place is
  Civil-Registry-derived. مستوي التعليم is the only new customer-entered field.**~~
  **Superseded 2026-08-23 (S2-06)** by `docs/journeys/field-provenance.md`, the authoritative
  source. Citizenship/المواطنة (field 11) is now customer-entered country of residence, and
  ethnicity (field 10) is a second new customer-entered field alongside education level.
- **Completion is terminal.** One update per account; the campaign is a one-time
  government-mandated re-verification.
- **Android-first launch accepted** (already OQ-009).

---

## Stages 3–6 rebuilt — no longer parked

The data-collection stages have been rebuilt against the bank's paper form. The field
provenance mapping is complete and is filed at `docs/journeys/field-provenance.md` (S2-06).
Downstream stages: identity type is 7, scan 8, Civil Registry 9, liveness 10, signature 11
(added 2026-08-23), completion 12, resume 13.

New segmentation: **3** personal and social (sex → marital status → dependents → education),
**4** occupation and income, **5** home address, **6** work address, employer and the optional
attachment.

**Superseded 2026-08-27 (S2-10):** stage 3 renamed "Personal, social and birth data" and its
field list now also carries birth country, birth state and birth city (fields 22, 24, 23) after
education level — see `docs/journeys/field-provenance.md` fields 22–24.

Occupation moved from the work-address stage to sit with income, since the collapse logic
that justified pairing it with the employer is deferred to v2.

**Sex is asked at stage 3 for interface reasons only** — Arabic gendering and marital-status
branching both need it before any scan. **Superseded 2026-08-23 (S2-06):** the Civil Registry's
value is what gets stored, not Uqudo's — see `docs/journeys/field-provenance.md` field 9.

---

## New concepts added after the first pass

**Submission and review.** Completion of the customer journey no longer completes the
profile. The journey ends at `submitted`; an operator then approves or rejects. A rejected
profile is resolved at a branch and can then be moved to `approved`. This introduced the
status model, the review workflow, and the rule that **every status transition is written
to the database, recorded in audit with actor and reason, and communicated to all verified
channels**.

**Status history is first-class.** `submitted → rejected → approved` and
`submitted → approved` are materially different — one was resolved by a human at a branch —
and cannot be distinguished from the current status alone. History is retained, shown in the
back office, and recorded in audit.

**~~Four-eyes rule.~~ REMOVED 2026-09-13 (AD-013).** Operator and supervisor collapsed into a
single **operator** level, and the segregation-of-duties concern was handled by one constraint
instead of a role hierarchy: an operator could not approve a profile they themselves manually
completed. **That constraint no longer exists** — AD-013 removed it, so the role collapse is
now unaccompanied by any compensating constraint. See RISKS.md R-054.

**Face matching is part of stage 9.** Uqudo's face session performs liveness *and* matches
against the portrait extracted from the scanned document. The second is what binds the
person to the document — without it, a genuine document held by someone else passes.
Liveness failure and face-match failure are recorded separately with confidence figures. Recorded, not displayed — AD-022 (2026-09-16) removed the operator-facing display.

---

## Design principles established

**The solution produces a verified identity claim; the operator judges it.** The journey
establishes that a live person matched the portrait in a genuine document, and that the
document matches the Civil Registry. It does not establish that the document belongs to the
account holder — the core banking call returns only 1/2/-1 and this system holds no prior
identity data. This is the intended division of responsibility, not a gap. How an operator
reaches a decision is outside the specification.

**One active session per account.** Stage 1a routing gives this: a completed profile blocks
a new session, an incomplete one routes to resume.

**Address structure is fixed; only its contents change.** Country, state and locality are
cascading pick-lists; المحافظة maps onto locality; city, area, street, block and house
number are free text. Replacing the dataset changes what is in the three lists, never the
shape.

**Rejection codes: REJ-01 to REJ-07, settled.** Fraud-related codes share one neutral
customer-facing message so that naming a control does not tell a fraudster what to change.

---

## New risks

| ID | Risk | Impact | Mitigation / resolution path | Status |
|---|---|---|---|---|
| R-013 | Identity-document images accumulate without bound and are never deleted | Several MB per profile across a national campaign, retained permanently for audit. Stored wrongly, this makes backups unusable and every profile query expensive. | AD-004: object storage with DB references, encryption at rest, downscaled derivatives for the operator UI. | 🔴 Live |
| R-014 | The interim administrative-divisions dataset is missing three current states | Customers from West Kordofan, Central Darfur or East Darfur have no correct selection, producing wrong address data at scale. | Corrected dataset required before production. Interim version is explicitly not final. | 🔴 Live |
| R-015 | 34 localities in the interim dataset are uncertain transcriptions and cannot be identified | The workbook README flags 31 Medium and 3 Low confidence rows and points at a column that is absent from the file. | Request the dataset with its confidence column intact. | 🟡 Watching |
| R-016 | Face-match failure is indistinguishable from liveness failure to the customer, and could be lost in the record | Face-match failure means the person holding the phone is not the person in the document — the strongest fraud signal the journey produces. If absorbed into a generic retry it is invisible to the bank. | Uqudo returns both results with confidence figures. Both are recorded separately and retained, and both stay queryable. **NARROWED 2026-09-16 by AD-022 ruling 3: the face-match result is NO LONGER SURFACED to operators.** The match is still run, still stored and still audited; the operator sees only the liveness line. This is the accepted cost, taken by the product owner with the approved design in hand, and it is the one place this mitigation sentence actually lives — RISKS.md R-016 is a different, already-retired row about the customer’s experience. | 🟡 Watching |
| R-017 | The optional salary-certificate upload is the heaviest payload in the journey, on poor connectivity | A multi-megabyte upload could block or appear to fail at the last step of an otherwise complete journey. | It gates nothing: the profile completes without it and the attachment lands whenever it can. | 🟡 Watching |
| R-018 | **RETIRED 2026-09-16 (AD-022): manual completion is removed, so this cannot occur.** Manual completion bypasses Uqudo, face match and the Civil Registry entirely | An operator can mark a profile complete with none of the identity evidence the journey exists to produce. Pooled with digital profiles, this would misreport identity-verified counts. | Provenance recorded permanently and never merged; ~~four-eyes rule prevents one operator completing and approving the same profile~~ — **REMOVED 2026-09-13 by AD-013; the control no longer exists. See RISKS.md R-054**; every manual completion is an audited action with a recorded justification. | ❌ Retired 2026-09-16 (AD-022) |
| R-020 | Forcing a work address on customers with no employer | طالب، ربة بيت، متقاعد must select a work state and locality from structured pick-lists. Under a mandatory field they will select arbitrary values, corrupting fields the bank filters and aggregates on. Free-text employer junk is harmless; a wrong locality code is data that looks real. | Accepted for v1 by product-owner decision. Revisited with the cleaner occupation list in v2. | 🟡 Watching |
| R-019 | The reference number is the customer's only artifact and is shown once, immediately before local storage is cleared | A customer who closes the app without noting it has no record of their submission and must be looked up by an operator. | The reference number is included in the submission notification to all verified channels, giving every customer a durable copy. | 🟡 Watching |

---

## Policy values — SETTLED

All sixteen are set and recorded in `customer.md` → *Policy values*, with the reasoning for
each. Summary:

OTP code valid **5 min** · resend **30/60/120s** progressive · **5** wrong attempts per
channel · **3** resends per channel · channel lock **15 min escalating to 1 hour** · scan
**3** per document type and **6** per session · liveness **5** · block **24 hours** ·
face-match failure uses the liveness budget with **no** differentiated customer experience ·
**all** fields mandatory · attachment **10 MB**, JPEG/PNG/PDF, downscaled on-device ·
abandonment **30 days** · abandoned-profile retention **90 days** (provisional, pending
OQ-001) · audit retention **7 years** · account check **10 per install per hour**, no global
ceiling · export **10,000 rows**, XLSX and CSV, no second approval.

### Superseded — the old marker list

| Value | Governs |
|---|---|
| Code validity period | How long an OTP remains usable |
| Resend delay | How long before the resend control unlocks |
| Resend cap | Resends permitted per channel per session |
| OTP attempt limit | Wrong entries before a channel locks |
| Retry-after period | Wait after all phone channels lock |
| Attempts per document type | Scan attempts before switching document is required |
| Total scan attempts per session | Overall bound regardless of switching |
| Liveness attempts | Should be more generous than the scan's — failures are environmental |
| Block duration | Wait after a scan or liveness budget is exhausted |
| Face-match failure handling | Whether repeated face-match failure blocks differently from liveness failure |
| Mandatory vs optional fields | Per data-collection stage, after the rebuild |
| Abandoned-profile retention | How long a half-finished profile holding a scanned document survives |
| Audit retention | Distinct from, and longer than, profile retention |
| Account-check rate limits | Per-install and global, on the stage 1a endpoint |
| Abandonment threshold | Inactivity after which an in-flight profile is marked `abandoned` |
| Export row limit | Maximum rows per export, and whether large exports need second approval |

---

## Still open — the complete list

**Re-assessed against the code on 2026-09-14, not against recollection.** Two items remain; the
third was closed twelve days before this list was last edited and nobody noticed.

1. **Corrected administrative-divisions dataset** — **STILL OPEN, verified at source.**
   `backend/src/main/resources/db/migration/V0016__seed_admin_division.sql` contains no
   غرب كردفان (West Kordofan), وسط دارفور (Central Darfur) or شرق دارفور (East Darfur). South
   Sudan appears only in `V0022__seed_country.sql`, where it belongs — it is a country, not a
   state, so that half of the original complaint is already right. The *structure* is fixed, so
   this is a data swap, not a design dependency. Tracked as OQ-012 / R-014.
2. ~~**Operator authentication** — how operator accounts are provisioned and authenticated.
   Part of AD-002 back-office auth.~~ **CLOSED — and it was closed on 2026-09-02.** Settled by
   AD-002e, built at S4-05, recorded as OQ-013 ANSWERED. Verified in the tree 2026-09-14:
   `backend/.../auth/` carries the full `config`/`domain`/`jdbc`/`service`/`web` set, and
   `backoffice/src/auth/` carries `AuthContext`, `LoginPage`, `ChangePasswordPage`, `RequireAuth`
   and `capabilities`. What remains is admin *account management* (BL-023), which is a different
   thing and is tracked on road-to-production §3.3 — not authentication.
3. **Dashboard metric list** — **STILL OPEN.** Refine when BL-001 is scheduled. Verified 2026-09-14:
   no dashboard exists in either tier. Tracked as OQ-014.

Everything else in both journeys is settled.

---

## Reference data supplied

- `branches.md` — definitive, 25 branches.
- `NSudan_Admin_Hierarchy.xlsx` — interim, gaps recorded above and in `customer.md`
  stage 4.
- `operator.md` — the operator journey specification.
- `customer.md` — the customer journey specification.
- إدارة_الإلتزام.pdf — the bank's paper form. The app must collect the same data,
  segmented to fit this journey. Drives the stage 3–5 rebuild.
