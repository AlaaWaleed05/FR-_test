# What does the audit record for an image view, a print, and an account change?

Type: grilling
Status: resolved — EXCEPT one item added later. The print event's payload was settled here; whether a salary certificate printed alongside the form gets its own event or a flag on the existing one was added by ticket 05 decision 9 (2026-09-13, S8-25) and is NOT answered. See decision 2 below.
Blocked by: 01, 05, 07

## Question

The brief's item 4 — "add back-office user activity to the audit" — turned out to be already
built for every surface that exists. Eleven operator event types already reach the append-only,
hash-chained `audit` schema with its own operator chain (`V0047`, `V0058`):

`sign_in_succeeded` · `sign_in_failed` · `sign_out` · `password_changed` ·
`profile_list_searched` · `profile_viewed` · `profile_exported` · `profile_approved` ·
`profile_approve_refused` · `profile_rejected` · `profile_manually_completed`

So this ticket is not a track. It is the clause the other three tracks each owe, and it is
deliberately settled **after** them, because the payload depends on what they chose.

1. **Image view.** `R-046` insists this is its own event. What is in the payload — artifact id,
   kind, profile, cycle? Is a re-render from the operator's own browser cache a missed event, and
   is that acceptable? (Ticket 01 decides the caching answer; this ticket decides whether the
   audit consequence is tolerable.)
2. **Print.** One event at render, or two (rendered, downloaded)? Payload: which profile, which
   field set, the stored artifact's id, the list versions used. Is a re-download of a stored print
   a separate event from the original print?
3. **Account administration.** Created, disabled, re-enabled, role changed, password reset — one
   event type with a verb, or one type each? Payload must carry the acting admin and the target
   account, never a password or a hash.
4. **Does any of this need a new `audit_artifact` kind?** `V0062` extended
   `audit.audit_artifact`'s kind check once already; a printed form may want a row there rather
   than only in `app.artifact_ref`.
5. Confirm every new event flows through `AuditEventWriter` and the existing chain rather than a
   parallel path, and that nothing in a payload can carry PII that the audit schema is not already
   permitted to hold.

## Context

- `backend/…/audit/domain/{AuditEvent,AuditEventWriter,AuditArtifact,CanonicalJson}.java`
- `V0002`–`V0004`, `V0030`–`V0034`, `V0036`, `V0047`, `V0058`, `V0062`
- `BACKLOG.md` BL-121 — a precedent for an audit payload that recorded the wrong reason; the
  lesson is that the payload's meaning is part of the design, not an afterthought.
- `CLAUDE.md`: no secrets, no real customer data, in logs or audit payloads.

---

## Decision

Product-owner, 2026-09-13, by interview. Taken last, as this ticket intended, because the payloads
depend on what tickets 01, 05, 07 and 10 chose.

**The governing fact, which shapes every payload below:** `AuditEvent` already carries
`chainKind`, `chainSubject`, `eventType`, `actorKind`, `actorId`, `profileId`, `sessionId` and
`requestId` as first-class fields. The payload is only for what those do not cover — which is why
`profile_viewed`'s payload is literally `CanonicalJson.object(Map.of())`. Nothing below duplicates
a column.

1. **Image view — payload is `kind` + `artifactId`.** Kind alone cannot tell two artifacts of the
   same kind apart across cycles (a superseded scan and its replacement are both `doc_front`), and
   the artifact id is what BL-075's per-artifact authorization keys on anyway. Cycle and checksum
   are derivable from that row, so storing them duplicates state that can drift.

   **The caching consequence ticket 01 handed over, answered:** `Cache-Control: no-store, private`
   means a revisit genuinely re-fetches, so **one event per origin fetch** is a faithful record.
   It counts FETCHES, not eyeballs — scrolling an already-rendered image back into view fires
   nothing. That limit is accepted openly rather than papered over.

2. **Print — ONE event, not two.** Ticket 05 decided store-first-then-stream in a single
   transaction, so "rendered" and "delivered" are the same instant and two events would record a
   distinction that does not exist. Payload: the stored artifact's id, the provenance-matrix
   version, and the reference-list versions from ticket 04's footer, so a reprint years later can
   be checked against what the original claimed. **The payload also names WHICH VARIANT was
   produced** — the print ships in an unattributed and an attributed form (ticket 10 decision 7),
   which differ in content and in disclosure, so an event that cannot tell them apart cannot answer
   what was printed.

   **OPEN since 2026-09-13 (S8-25), and this ticket has not answered it:** the operator may now
   also print the salary certificate as a separate document alongside the form (ticket 05 decision
   9). Whether that is the same single event carrying "certificate included" plus its artifact id,
   or an event of its own, is not settled here. The recommendation on ticket 05 is one event with
   the flag, on the grounds that decision 6 exists to answer how many copies of a document exist —
   but it is a recommendation, not this ticket's ruling. **Note for whoever builds it:**
   `app.profile_provenance_matrix_version` (V0027/V0029) has **no writer** — no Java code inserts
   into it, and V0027's own comment says so. Ticket 04's footer and this payload both depend on a
   value nothing currently records; populating it is part of the print work, not an assumption. Re-download is a separate event type (ticket 05
   decision 6).

3. **Account administration — one event type PER ACTION**: `operator_account_created`,
   `operator_account_disabled`, `operator_account_reenabled`, `operator_account_role_changed`,
   `operator_account_password_reset`. All eleven existing types already work this way; a single
   type with a verb inside the payload would make every query parse JSON to answer "who was
   disabled last month". **On the `operator` chain, with `chainSubject` = the TARGET account** and
   the acting admin as `actorId`, so one account's whole life replays as a single hash chain.
   Payload carries the target account id and, for a role change, the old and new role. **Never a
   password, never a hash.**

4. **No new `audit_artifact` kind.** Every existing kind is an external-system exchange
   (`uqudo_scan_jws`, `uqudo_face_jws`, `civil_registry_request`/`response`,
   `omni_check_request`/`response`); a PDF this system generated is not one. Ticket 05 already
   puts the bytes in `app.artifact_ref` with a checksum and `app.artifact_read()`, which is the
   stronger guarantee. Recording it in both places creates two records that can disagree.

5. **The manual-edit event records the field name and a classification in the payload; the old
   and new VALUES go in a purgeable `audit_artifact` body.** Added at this session: this ticket was
   written hours before ticket 10 existed, so it had no question for the edit event. The
   requirement stands — an edit log that cannot say what a field was changed TO answers nothing,
   and with four-eyes gone this trail is the sole compensating control.

   **CORRECTED AT REVIEW.** This decision originally put the values in `payload_json`, justified by
   "the audit schema already stores the full Civil Registry response, so holding PII is its job".
   That conflated two stores with OPPOSITE retention properties, and the codebase states the
   convention in terms:

   - `audit.audit_artifact.body` is "BYTE-IDENTICAL, never re-encoded; **NULLable after a lawful
     purge**" with `body_purged_at` "set when the 90-day PII rule erases the bytes" (`V0002:28,32`).
     That is where the Civil Registry response and the Uqudo JWS live, and it is **erasable**.
   - `audit.audit_event.payload_json` is "written once, **what gets hashed**" (`V0002:52`) — part of
     the permanent hash chain and therefore **never erasable**.
   - `IdentityScanService` says it outright at three separate places, e.g. l.548: "into
     payload_json: payload_json is permanently hash-chained and never erasable".

   Putting a customer's name or national number in `payload_json` would create PII that can never
   be erased by any retention rule, and would fail this ticket's OWN question 5 ("nothing in a
   payload can carry PII that the audit schema is not already permitted to hold"). So: the payload
   carries the field name and whether the value changed; the before/after values go in an artifact
   body, which the existing purge can reach. Hashes of the values remain rejected — a hash proves a
   value you already have and reveals nothing about one you do not.

   If the product owner would rather reverse the convention and accept permanently unerasable edit
   values, that is a deliberate decision to take explicitly, not a side effect of this ticket.

6. **Everything flows through `AuditEventWriter` and the existing hash-chained schema.** No
   parallel path. Confirmed as the requirement, not a new decision.
