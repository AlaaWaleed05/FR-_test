# Can an operator enter or correct a single field by hand, and how is that recorded?

> **SUPERSEDED IN PART BY AD-022 (2026-09-16).** This ticket remains BL-135's live spec and S9-02's input, so it is stamped rather than rewritten — but TWO of its statements are now false and one of them originates here. **Decision 7 ("TWO printed forms, and the operator chooses which to produce") is REVERSED**: there is one form, the variant choice is gone and V0072 withdrew the second artifact kind. Tickets 04 and 08 cite this decision and are superseded with it. **The write-endpoint list is also out of date**: `manual-complete` was deleted at S9-01, leaving `approve`, `reject` and `print`. Everything about per-field manual entry still stands.




Type: grilling
Status: resolved
Blocked by: —

## Question

Product-owner requirement, 2026-09-13, raised while settling ticket 04 and deliberately split out
of it: an operator should be able to complete **any** field manually. The value is saved to the
database exactly as a mobile-supplied one is — same table, same validation, same downstream
behaviour — with two differences: the **source is flagged as manual entry**, and the **operator who
entered it is recorded**. The profile is labelled `digital` only if every field is digital; if any
single field was entered by hand, the whole profile is `manual`.

**Verified absent before filing**, against source rather than recollection:

- `app.profile.provenance` is `CHECK (provenance IN ('digital','manual'))` — ONE flag for the whole
  profile. No per-field granularity exists.
- No `source`, `entered_by` or equivalent column on any customer-data field anywhere in schema `app`.
- The "provenance matrix" (`V0027`/`V0029`) is **not** per-field provenance despite the name. It
  versions which edition of `docs/journeys/field-provenance.md` resolved a profile. Its own comment:
  "Written once by whatever backend code later resolves a profile's fields (no such code exists yet)".
- The back office has exactly three write endpoints **against profile data** — `manual-complete`,
  `approve`, `reject`. (It also POSTs `/auth/login`, `/auth/logout` and `/auth/password`, which
  write session and credential state, not profile data.) There is no field-edit path.

So this is new build, not a gap in wiring.

## Why this is an architecture decision, not a feature

CLAUDE.md describes `backoffice/` as reading backend-held profile data and performing "the system's
only two write actions". Per-field manual entry turns the back office into a **data-entry tier**.
That is a decision to take deliberately, which is why it was not settled inside ticket 04.

## What needs deciding

1. **Which fields are editable, and which never are.** A registry-supplied national number, the
   account number, the scan-derived document number — is any of these hand-editable, and if so does
   editing it invalidate the face match or the registry lookup that used it?
2. **When.** Before submission only? While `submitted`? After `approved` — and if so, does the
   approval survive?
3. **Four-eyes.** An operator who fills fields and then approves is the same conflict the existing
   rule guards against ("an operator may not approve a profile they themselves manually completed").
   Does entering a single field carry the same bar as manual completion?
4. **Does the customer find out?** The journey notifies on every status transition. An operator
   correcting a customer's address is not a status transition, but it is a change to their record.
5. **Reference-coded fields.** Occupation, admin division, country, income source and education are
   server-supplied lists. Manual entry must pick from the same list and record the same list version,
   or the printed form's version footer (ticket 04) becomes a lie.
6. **Validation parity.** The mobile app enforces E.164 phone shape, ASCII-digit rejection, and the
   reference-list constraints. A back-office edit path that does not enforce the identical rules
   admits values the mobile app could never produce.
7. **What the printed form shows.** Ticket 04 settled that the form carries per-field source labels.
   Does it name the editing operator per field, or only mark the field manual?
8. **Existing `manual` profiles.** Three exist today with `provenance = 'manual'` and no field-level
   record of what was entered. Do they stay as they are, or get backfilled?

## Context

- `BL-135` — the backlog row, with the scope estimate
- Ticket 04 (`04-form-field-set.md`) — resolved without this; the print renders whatever provenance
  the profile carries, so it needs no layout change when this lands
- `docs/journeys/operator.md` — the manual-completion section, the four-eyes rule, and the
  "only two write actions" boundary
- `docs/journeys/field-provenance.md` — the 54 fields and their source precedence
- `backend/…/operator/web/` — `ManualCompletionController`, `ReviewController`, the only write paths
- `V0027`/`V0029` — the matrix-version tables, and what they are NOT

---

## Decision

Product-owner, 2026-09-13, by interview.

1. **Every field is editable.** The product owner's reason, which is the point of the whole
   feature: the branch runs the WHOLE process during a customer visit, so an operator must be able
   to fill anything the customer would have filled on the phone. This overrides the narrower
   recommendation (identity fields locked) deliberately, and decision 3 is what makes it safe.

2. **Editing is allowed at any status before `approved`, and never after.** With four-eyes gone
   (ticket 06), allowing edits after approval would let one person approve a profile and then
   change what they approved, with nothing but the audit log noticing. `approved` therefore keeps
   meaning "someone approved THIS data".

3. **An identity edit does NOT void the verification.** The registry and face results are facts
   about a moment that genuinely happened, and they are stored independently of the editable value
   — `registry_result.identity_number_returned` holds what the registry actually returned, and
   `scan_result` / `face_result` hold theirs. So **divergence is computable, not guessed**: any
   field whose current value differs from the verified one is marked **overridden** on screen and
   on the printed form.

   **Two states, not one — clarified at review.** "Divergence is computable" holds only where a
   verified value EXISTS to diverge from, and on the branch-visit profile this feature is built for
   it often does not: a manually-completed profile has no registry result at all (S8-19 verified
   this live — 3 manual profiles, zero registry results, zero artifacts), and
   `registry_result.identity_number_returned` is itself NULL "when no parseable record came back or
   the lookup was stubbed" (`V0062:38-43`). So a field renders as **overridden** when a verified
   value exists and differs, and as **manually entered** when none exists. Collapsing the two would
   let "overridden" imply a verification that never happened. Rejected: voiding the face match (deletes a real result because a later
   typist disagreed) and forcing a re-scan (defeats the branch visit entirely).

4. **The customer is notified only when a CONTACT CHANNEL is edited** — phone or email. Changing
   those silently redirects every future message about the customer's own record. Notifying on a
   corrected street name is noise that trains people to ignore the channel.

5. **The three existing `manual` profiles are left exactly as they are.** They are a controlled
   test group (product owner). There is also no honest way to backfill them: nobody knows which
   fields were entered, so a backfill would invent provenance.

6. **Parity, not a new dialect.** Reference-coded fields pick from the same server-supplied lists
   and record the same list version — otherwise ticket 04's version footer becomes a lie — and
   validation matches the mobile app's rules field for field (E.164 shape, ASCII-digit rejection,
   reference-list constraints). Stated as the only defensible answer and not disputed.

7. **TWO printed forms, and the operator chooses which to produce.** Product-owner decision,
   2026-09-13, replacing an earlier narrower one:

   - **Unattributed** — every manually entered or overridden field carries a marker, but no staff
     names. The compact form.
   - **Attributed** — the same form, plus **the name of the operator who entered each such field**,
     beside the field.

   **Why this replaced "marker only".** The original decision declined per-field names because they
   would clutter a customer's filed document and "leak internal identities onto paper that leaves
   the building". The product owner then corrected the premise: **the print is INTERNAL and is never
   handed to the customer** (ticket 05 decision 7). The privacy half of that argument evaporated,
   leaving only clutter — a weak objection for an internal record whose purpose is to BE the
   branch's account of what happened, especially now that the audit trail is the only remaining
   control (R-054). Rather than swap one form for the other, both ship: the compact one for routine
   filing, the attributed one when someone needs to see who typed what without opening an audit
   query.

   Who edited what, and when, still lives in the audit trail regardless — ticket 09 decision 5,
   which records the old AND new values. The attributed form is a convenience over that record, not
   a replacement for it.

   **Three consequences that follow mechanically, settled here so the builder does not have to
   guess:**

   a. **The two forms are two `kind` values on `app.artifact_ref`**, not one kind with a flag.
      `artifact_ref` has no generic metadata column, so the alternative would need a new one; the
      `kind` check constraint is already the enumeration of what an artifact is, and ticket 05
      decision 2 already allows many prints per profile. A stored row therefore says which form it
      is without anyone opening the PDF.
   b. **The print audit event records which variant was produced** (ticket 09 decision 2). Two forms
      of the same profile have different content and different disclosure, so an event that cannot
      tell them apart cannot answer what was printed.
   c. **Both variants are available to everyone who may print** — operator and admin, never viewer
      (ticket 06's ladder, ticket 05 decision 6). Nothing about attribution changes who may print.

8. **Four-eyes does not arise.** Ticket 06 removed it. Question 3 of the Question section above is
   therefore answered by a decision taken elsewhere, not by this ticket.

9. **Viewers cannot edit.** Ticket 06's ladder is explicit: viewer is "view only. No print, no
   edit." Editing is operator and admin.

## What this costs, recorded rather than discovered later

With four-eyes already gone, "every field editable" means **one person can now enter every field
on a profile and then approve it.** No separation of duties remains anywhere in the product. The
audit trail is the entire compensating control. Ticket 06 already said this needs an `R-` row
before production; that row is now written — **R-055** — because this decision is what makes it
concrete rather than theoretical.
