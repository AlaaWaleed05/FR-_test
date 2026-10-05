# Map: The back office's remaining half

Label: `wayfinder:map`
Charted: 2026-09-13
Visual: https://claude.ai/code/artifact/785b4cfe-afe3-414c-966d-ccc4ce081dc6

## Destination

A settled spec for everything the back office still owes: identity-image viewing, the printed
and persisted update form, admin account management via the UI, and the UI/print design pass —
with every architecture decision they depend on closed, so a build session can start without
settling anything in passing.

## Notes

- Domain: `docs/journeys/operator.md` (roles, review, export), `docs/components/persistence.md`,
  `PROJECT_PLAN.md` (AD rows), `RISKS.md` (R-046), `BACKLOG.md` (BL-023, BL-026, BL-075, BL-084).
- Skills every session should consult: `grilling` + `domain-modeling`. Use `research` for the
  AFK tickets, `prototype` for the design tickets.
- `CLAUDE.md` binds: open AD rows are never settled in passing; `@agent-researcher` before any
  third-party SDK/API integration; `@agent-reviewer` before marking a task done; no real customer
  data or identity images anywhere.
- Three of the brief's six items were checked against source before charting. Item 6 (decision
  messages) is already built and is ruled out of scope below. Item 4 (operator activity audit) is
  already built for every surface that exists, and survives here only as a clause on the three new
  surfaces — ticket 09, not a track of its own.

## Decisions so far

<!-- one line per closed ticket -->

- [How does the back office address an identity image?](issues/01-image-addressing.md): a
  **cookie-authenticated `GET` straight into an `<img>`** — no signed URLs, no blob plumbing, no new
  key material. `R-046`'s premise (an `<img>` cannot carry an Authorization header) never applied:
  the back office is same-origin with the API since S7-08, and auth is a session cookie. **Closes
  `R-046`, unblocks `BL-075`**, and leaves `app.artifact_ref.storage_key` dead but not yet dropped.
- [Does admin become a superuser?](issues/06-does-admin-become-a-superuser.md): **Yes** — admin
  gains the full operator capability set on top of being the only role that creates back-office
  users, superseding `AD-002e` with a new AD row. And **four-eyes is removed entirely**, keeping
  `is_manual_completion` on status history so it can be switched back on without a migration. **CORRECTED 2026-09-16 (AD-022/S9-01): this reversal is no longer cheap.** The column has no writer since manual completion was deleted, so restoring the predicate would need a write path rebuilt too — and the action the rule governed no longer exists. The column is kept (V0067, V0071) so pre-ruling profiles stay distinguishable, which is a different reason. **CORRECTED 2026-09-16 (AD-022/S9-01): this reversal is no longer cheap.** The column has no writer since manual completion was deleted, so restoring the predicate would need a write path rebuilt too — and the action the rule governed no longer exists. The column is kept (V0067, V0071) so pre-ruling profiles stay distinguishable, which is a different reason. No
  separation of duties remains; the audit trail is now the only compensating control.
  **BUILD STATUS, 2026-09-13 (S8-27): the two halves have diverged.** Four-eyes removal is BUILT
  and BL-131 is closed. **The admin-superuser half is NOT built** — `OperatorIdentityFilter` still
  filters ADMIN out, so an admin reaches no operator endpoint today. It had no backlog row of its
  own; now filed as **BL-139**. This ticket is not fully discharged until that ships.
- [Which artifacts does an operator see, and in what arrangement?](issues/02-which-images.md):
  **Six kinds (five at first — see below), flat contact sheet, captioned with the image NAME** — `ID Document`, `Document
  Photo`, `CR`, `Liveness`, `Signature` — not metadata, and nothing else shown. Chosen by flipping
  three structurally different variants in a browser against real artifacts. `doc_front_frame` is
  excluded by name: it declares `image/jpeg` at 1.7 MB with `body IS NULL`. ~~Caveat left open: the
  captions are ENGLISH in an Arabic-first RTL UI, which nobody has decided.~~ **Decided at S8-23,
  when the tiles were built: the captions are ARABIC** — «وثيقة الهوية», «صورة الوثيقة»,
  «السجل المدني», «إثبات الحياة», «التوقيع». Built and CLOSED at S8-23 (BL-075). **A second correction, S8-24:** this ticket's opening premise counted six byte-carrying kinds when there are seven, so `salary_certificate` was excluded by omission rather than decision. Product owner asked and answered — it is viewable, and is the sixth tile (BL-136, closed).
- [What exactly is on استمارة تحديث البيانات?](issues/04-form-field-set.md): **all 54 fields of
  `field-provenance.md`**, both values tagged by origin on the 16 dual-source rows, one footer line
  of list versions, **signature and both portraits but no document scans**, no redaction, «غير متاح»
  never a blank, provenance per field. **Unblocks ticket 08.**

  > **SUPERSEDED 2026-09-14 (S8-33).** One source per field, so no row is dual-source and no origin tag prints; no list-version footer; and the images are FIVE, document scan included. See ticket 04's round-two amendment.
- [What is the life of a printed form once it is stored?](issues/05-printed-artifact-lifecycle.md):
  **Decision 9, added 2026-09-13 (S8-25): the salary certificate is a SEPARATE document, never
  part of the form.** The operator chooses the form alone, or the form plus the certificate as two
  documents — **asked at print time, every time, defaulting to no, and not offered at all on a
  profile with no certificate.** Closes the omission ticket 04 decision 4 had, which is the same omission ticket 02
  made about VIEWING the certificate (BL-136). Two consequences left open on that decision for
  whoever builds BL-132: whether a printed certificate is stored as a new artifact, and what the
  audit event records.

  bytes to `app.artifact_ref` (profile-keyed, `cycle_id` NULL, no unique index) with a checksum and
  `app.artifact_read()`; **every print its own artifact, nothing superseded**; store first then
  stream in one transaction, so a failed store means no file; re-download by operator/admin only and
  its own audit event; **only `submitted` and `approved` may be printed**. Retention is a rule that
  must be BUILT — no profile PII-nulling rule exists today for those statuses.
- [What can the admin screen do?](issues/07-admin-screen-capabilities.md): **all five capabilities**
  (list · create · disable/re-enable · change role · reset password), create covering all three
  roles; **first admin from the existing CLI only**, nothing seeded; last-admin protection over self
  AND last-enabled-admin, needing row locks or SERIALIZABLE rather than a plain count; passwords
  generated and shown once; no customer data on the screen. **Reverses the 2026-09-04 cut of
  `BL-023`.** Build requirement found at review: the admin endpoints must re-read the account
  per request, or a disabled admin keeps a live session.
- [Can an operator enter or correct a single field by hand?](issues/10-manual-field-entry.md):
  **yes — every field**, because the branch runs the whole process during a customer visit. Editing
  stops at `approved`; an identity edit does NOT void the scan or registry result, so a field reads
  `overridden` where a verified value differs and `manually entered` where none exists; customer
  notified only on a contact-channel change; viewers cannot edit. Recorded as **AD-015**; removes
  the bound `R-054` relied on.
- [What does the audit record for the new surfaces?](issues/09-audit-events-for-the-new-surfaces.md):
  image view = kind + artifact id, **one event per origin fetch** (counts fetches, not eyeballs);
  print = ONE event; account admin = **one type per action** on the `operator` chain with the TARGET
  account as subject; no new `audit_artifact` kind. Manual-edit values go in a **purgeable artifact
  body, never `payload_json`**, which is permanently hash-chained.
- [What is wrong with the back office today, and what should the print look like?](issues/08-ui-and-print-design-pass.md):
  walked it first and measured the failures — **no brand on login** (default Ant blue, no logo), **no
  Arabic face bundled** (`BL-134`), **a transparent `body` with no page ground**, a **3,556 px
  detail page**, and a list using a third of the screen with placeholder-only filter labels. RTL
  itself was fine. The operator's day is **low volume, minutes each**, so the **detail page is home
  base** and the list is a queue. Brand inherited from `Design_3/tokens/` — navy `#0b1c47`, **radius
  0**, **no shadows** — with the **circular pearl** as the mark. Print is **A4**, header = logo +
  bank name + title, compact repeated header and «صفحة N من M». Artboards: `Design_3/backoffice/`.
  **This closes the map**, and discharges the three fog items that were waiting on it: `BL-026`'s
  export button is answered (decision 4, it is on the queue design); the viewer role survives with a
  reduced detail screen (no print, no edit — ticket 06's ladder, drawn as a departure note on 08);
  and the **operator dashboard is NOT designed and was not asked for** — it stays unbuilt, and should
  be raised as its own item if it is still wanted.
- [Which JVM toolchain renders a right-to-left Arabic PDF correctly?](issues/03-arabic-pdf-toolchain.md):
  **Apache FOP 2.11**, XSL-FO to PDF, embedding the repo's existing IBM Plex Sans Arabic `.ttf` —
  the only Apache-2.0 candidate that applies the font's OpenType GSUB/GPOS rather than pre-shaping
  to compatibility forms. iText ruled out on AGPL. Report:
  `docs/sessions/2026-09-13-research-arabic-pdf-toolchain.md`. **The acceptance render has not
  happened** and must run before any form layout is written.

## Product-owner answers carried into this map (2026-09-13)

These came in before charting and are treated as settled inputs, not open tickets:

- Hosting stays on AWS as it is today. The bank-subdomain / bank-hosting question is deliberately
  later, so whatever is chosen for images must be secure **today on AWS** and must not have to be
  rebuilt when hosting moves.
- The admin does everything through the UI, not a command line.
- Operator and admin may print. A viewer may not print and may not edit.
- The admin is the only role that may create back-office users.
- The back-office UI and the print layout both get a design improvement pass.

## Not yet specified

- **What, if anything, stands in for four-eyes before production?** **SHARPENED 2026-09-13, and
  now WORSE than when this was written.** Ticket 09 settled what the audit captures (old and new
  values of every edited field, in a purgeable artifact body), and ticket 10 made every field
  editable — so the exposure grew at the same time the trail improved. The `R-` row this item asked
  for was **not** filed as a new row: `R-054` already existed and already said this, so it was
  AMENDED instead, because its stated bound ("it cannot invent a Uqudo scan or a Civil Registry
  result") is exactly what ticket 10 removes. What remains genuinely undecided, and is the real
  question: **does anyone ever READ the trail, on what schedule, and who?** A log nobody reviews
  detects nothing. Three options are named on `R-054` and none has been weighed. `R-037` (seals are
  produced and never exported; nothing calls `audit.seal_create()`) is now recorded as a
  prerequisite of that mitigation rather than an unrelated row.
- **Does the stored print need PDF/A conformance?** Raised by ticket 03 and deliberately not
  investigated there. It is worth asking the bank now rather than retrofitting: PDF/A changes
  font-embedding rules (full embedding, no subsetting) and FOP supports it, so deciding early is
  cheap and deciding late is not. Not sharp enough to ticket until someone asks the bank.
- **BL-084 — the outbox retries a transient failure every 30 s forever**, because nothing reads
  `attempt_count`. It sits directly on the delivery path the (already built) decision messages
  ride, so it belongs to this effort's surface area, but the question — backoff curve? dead-letter
  state? operator visibility? — is not sharp enough to ticket yet.
- **BL-026 — no export button in the back office.** The endpoint exists; the UI does not. Likely
  folds into ticket 08's design pass rather than becoming its own ticket, but that depends on what
  08 finds.
- **Does the viewer role survive?** If admin gains operator powers (ticket 06), the role ladder is
  worth re-reading as a whole rather than patched one rung at a time.
- **The operator dashboard** (`operator.md` "Dashboard") is specified and unbuilt. In scope for the
  back office generally; not obviously part of *this* destination. Revisit once 08 reports.

## Out of scope

- **Decision messages to the customer on a back-office decision** — the brief's item 6. Already
  built: `OperatorReviewService.approve`/`.reject` and `ManualCompletionService` each enqueue into
  `app.notification_outbox` for every verified channel inside the same transaction as the status
  write; `ReviewMessageRenderer` renders per-channel Arabic copy; `OutboxDispatchScheduler` drains
  it. Nothing to decide. (Its one real defect, BL-084, is fog above, not this.)
- **Moving hosting to the bank** — product-owner decision that it comes later. Ticket 01 must not
  be *blocked* on it, but must not *foreclose* it either.
