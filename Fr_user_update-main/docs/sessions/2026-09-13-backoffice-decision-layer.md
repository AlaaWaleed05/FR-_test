# S8-20 — The back office's decision layer, emptied

2026-09-13. Decisions only, no code in any tier. Four wayfinder tickets settled in one grilling
session; the map goes from five open to one.

---

## What changed

| Ticket | Was | Now |
|---|---|---|
| 05 printed-artifact lifecycle | open | ✅ resolved |
| 07 admin screen capabilities | open | ✅ resolved |
| 09 audit events for new surfaces | open (blocked by 01, 05, 07) | ✅ resolved |
| 10 per-field manual entry | open (filed this morning) | ✅ resolved |
| 08 UI and print design pass | open (blocked by 04) | **open, and READY** |

Nine of ten tickets are resolved. Ticket 08 is the only one left.

## Why these four, in this order

Ticket 09 declares itself "not a track — the clause the other three tracks each owe", and is
deliberately settled after them because its payloads depend on what they chose. That held: every
one of its five answers is shaped by a decision taken earlier in the same session.

Ticket 10 was done first for a reason that is not in the tracker. **It turns the profile detail
screen from a read-only record into a form** — every field editable — and ticket 08 is the ticket
that designs that screen. Designing it read-only and retrofitting editing is exactly the rework 08
exists to prevent, so 10 had to land first. Ticket 08's Question section could not know this: it
was written before ticket 10 existed. It has been annotated rather than left to mislead.

---

## The decisions

Full text lives on each ticket. What follows is what a later reader needs without opening four
files, plus the reasoning that is not obvious from the outcome.

### Ticket 10 — manual field entry

**Every field is editable**, overriding the narrower recommendation that identity fields stay
locked. The product owner's reason is the point of the feature: the branch runs the whole process
during a customer visit, so an operator must be able to fill anything the customer would have
filled on the phone.

What makes that safe is the third decision. **An identity edit does not void the verification.**
The registry and face results are facts about a moment that genuinely happened, and they are
stored independently of the editable value — `registry_result.identity_number_returned` holds what
the registry actually returned. So divergence is **computable, not guessed**: any field whose
current value differs from the verified one renders as `overridden`, on screen and on the print.
Voiding the face match was rejected because it deletes a real result on the say-so of a later
typist; forcing a re-scan was rejected because it defeats the branch visit entirely.

**Two states, not one — corrected at review.** "Computable" holds only where a verified value
exists to diverge from, and on the branch-visit profile this feature exists for it often does not:
a manually-completed profile has no registry result at all (S8-19 proved that live), and
`identity_number_returned` is itself NULL when the lookup was stubbed or unparseable. So a field
reads **overridden** when a verified value exists and differs, and **manually entered** when none
exists. Collapsing them would let "overridden" imply a verification that never happened.

Editing is allowed **at any status before `approved`, never after** — otherwise one person could
approve a profile and then change what they approved. The customer is notified **only when a
contact channel changes**, since that silently redirects every future message about their own
record; notifying on a corrected street name trains people to ignore the channel. The three
existing manual profiles are left alone (a controlled test group, and there is no honest way to
backfill provenance nobody recorded).

### Ticket 07 — admin screen

**All five capabilities ship**, create covering all three roles. The argument for the full set
rather than a subset is `BL-096`: a throwaway account sits on staging disabled and unremovable,
because partial administration leaves permanent mess.

**The first admin comes only from the existing CLI.** Seeding one in a migration was rejected
explicitly: the password or its hash would live in the repo forever, and git history keeps it even
after deletion — a bank system shipping with a live back door, which the ticket's own question
warned about.

**Last-admin protection covers both self and last-enabled-admin.** Accounts are never deleted and
there is no other way in, so a lockout here has no recovery path. I first wrote this as "a count
check inside the same transaction, not a read-then-write" — **which is wrong, and the review caught
it.** A `SELECT count(*)` inside a transaction *is* a read-then-write under READ COMMITTED: two
admins disabling two different admins each see two enabled admins and both commit, producing
exactly the zero-admin lockout the rule exists to prevent. It needs row locks over the
enabled-admin set (`SELECT … FOR UPDATE`) or SERIALIZABLE. The in-repo precedent,
`JdbcReviewRepository`'s lock-and-read plus a conditional `UPDATE … WHERE`, works because it
contends on one row; last-admin contends on a set.

One item was a **confirmation, not a decision, and was verified rather than asserted**:
`OperatorIdentityFilter` re-reads the account every request via `findActiveById` (filtered to
`is_enabled = true`), so a disable lands mid-session — tested by a case named for exactly that,
`OperatorIdentityFilterTest.setsNothingForADisabledAccountTheLiveSessionDisableEffect`.

**But "nothing to build" was too broad, also caught at review.** That re-read only protects
endpoints that resolve an `OperatorIdentity`. `/api/v1/admin/**` is gated on the cached session
principal, and the filter deliberately sets nothing for an admin — so a disabled admin's live
session would keep full access to every admin endpoint. The build must make the admin endpoints
depend on the same per-request re-read, or "disable takes effect immediately" holds for operators
and fails for the role that can do the most damage.

### Ticket 05 — printed-form lifecycle

Bytes to `app.artifact_ref` with a checksum and `app.artifact_read()`. That matters more here than
for any other artifact because, per ticket 03, **the render is not byte-reproducible** — FOP, iText
and Chromium all stamp a creation date and document id — so the stored checksum is the only proof
of what was printed. Nobody can recompute it from the profile later.

**Every print is its own artifact; nothing is superseded** — and the row is **profile-keyed with
`cycle_id` NULL**, the shape `signature` already uses. That detail is load-bearing and came out of
review: `app.artifact_ref` carries `UNIQUE (cycle_id, kind)`, so a cycle-keyed print would make the
*second* print of a profile a constraint violation, contradicting the decision itself.

**Store first, then stream, one transaction.** The reason changed after the product owner
corrected a premise — **the print is internal and is never handed to the customer** — so the
Question's stated failure mode ("a print the customer received that the bank has no record of")
cannot happen. The decision stands on the failure mode that survives: a copy of the densest PII
object in the system existing on someone's disk with no record that it was produced. With four-eyes
gone, the audit trail is the only thing that would ever notice.

**Retention matches the profile's PII exactly and no longer** — but I asserted this as though the
rule were live, and **it is not**. The review established that the only 90-day rule is
*abandoned-profile* retention, itself provisional pending OQ-001; `V0055`'s header says full profile
PII nulling "stays unwritten by any code"; and nothing writes `pii_purged_at`. For the only
printable statuses there is no PII-nulling rule at all today. So this is a rule the print must
follow *once one exists*, and whoever builds profile retention owns the print's retention in the
same change.

**Only `submitted` and `approved` profiles may be printed.** Manual completion sets
`status = 'submitted'`, so the branch case is covered. I wrote that this "stops the retention rule
and the purge rule contradicting each other"; more accurately, **it makes the retention rule
inoperative for now** — no purge or nulling event can reach a `submitted` or `approved` profile, so
that rule currently has no trigger and constrains nothing.

### Ticket 09 — audit

The governing fact is that `AuditEvent` already carries actor, profile, session and request as
columns, which is why `profile_viewed`'s payload is literally empty. Nothing decided here
duplicates a column.

Image view records **kind plus artifact id** — kind alone cannot separate a superseded scan from
its replacement, since both are `doc_front`. Print is **one event**, because store-then-stream in
one transaction makes "rendered" and "delivered" the same instant. Account administration gets
**one event type per action**, matching all eleven existing types, **on the `operator` chain with
the target account as subject**, so an account's whole life replays as one hash chain. No new
`audit_artifact` kind: every existing kind is an external-system exchange, and a PDF we generated
is not one.

The fifth answer was **added, not answered** — ticket 09 was written hours before ticket 10, so it
had no question for the manual-edit event. That event records the field name **plus the old and new
values**, because a log that cannot say what a field became answers nothing.

**Where those values live was my error, and it was the review's one blocker.** I put them in
`payload_json` and justified it with "this schema already stores the full Civil Registry response,
so holding PII is its job". That conflates two stores with opposite retention: `audit_artifact.body`
is "NULLable after a lawful purge" with `body_purged_at` for exactly the 90-day PII rule
(`V0002:28,32`), while `payload_json` is "written once, what gets hashed" (`V0002:52`) — part of the
permanent chain and **never erasable**. `IdentityScanService` states the convention outright in
three places. Putting a national number there would create PII no retention rule could ever reach,
and would fail ticket 09's *own* question 5. Corrected: the payload carries the field name and
whether the value changed; the before/after values go in a purgeable artifact body. Reversing the
convention instead remains available, but as an explicit decision rather than a side effect.

---

## Two premises checked rather than accepted

**Ticket 05 feared the 90-day purge would eat a printed record.** It cannot. `V0055` scopes
`app.purge_abandoned_artifacts()` to `p.status = 'abandoned' AND p.last_activity_at < cutoff`, and
decision 8 forbids printing anything but `submitted` and `approved` — and `V0020` gives neither
status an outgoing arc to `abandoned`, so no stored print can ever sit on a profile that later
abandons. The edge case dissolved rather than needing a rule.

**Ticket 10's four-eyes question was already answered elsewhere.** Ticket 06 removed the rule
outright on the same day. The question was not re-litigated; it was recorded as settled by another
ticket, which is a different thing from being assumed away.

---

## What this session cost, and why it became an amendment rather than a new risk row

Two decisions compound, and neither ticket owns the combination. Ticket 06 removed four-eyes.
Ticket 10 made every field editable. Together: **one person can create an account, enter every
field on a profile, and approve it**, with nobody else in the loop. The person who types a
customer's identity, income and address is the same person who attests it is correct.

I first filed this as a new row, **R-055. That was wrong, and the review caught it.** `R-054`
already existed — written the same day for ticket 06 — and already stated the same scenario and the
same "audit trail alone, for now" mitigation. Worse, R-054 *bounded* the exposure with a sentence
ticket 10 invalidates: "it cannot invent a Uqudo scan or a Civil Registry result." That bound is
precisely what every-field editability removes. So the correct act was to **amend R-054**, not to
file a second row saying the same thing while the first one quietly went stale. R-055 was withdrawn
before commit.

Three things were added to R-054 rather than asserted loosely:

- **The bound is struck through and explained**, so a reader sees what changed and when.
- **A precision the first draft overstated.** "No separation of duties remains anywhere" is not
  true — the `fru_migrator`/`fru_app`/`fru_sealer` database-role split still separates evidence
  custody from the application, and that is what keeps the audit trail credible as a compensating
  control at all. What is gone is separation over the **business action**: enter, then attest.
- **`R-037` is now named as a prerequisite of R-054's own mitigation.** The trail is offered as the
  compensating control, but its tamper-evidence anchor outside the database does not exist — seals
  are produced and never exported, and nothing in `backend/src/main/java` calls
  `audit.seal_create()`. An append-only log whose custodian can rewrite it is not a substitute for
  separation of duties.

**The open question underneath all of it, still undecided: does anyone ever read the trail, on what
schedule, and who?** A log nobody reviews detects nothing. R-054 names three options — restore
four-eyes for approval only, review profiles where the entering and approving actor match, or alert
on that condition — and none has been weighed. That is a decision for the product owner before
production.

## Gates

No tier was touched. This session changed `.scratch/` tickets, `BACKLOG.md`, `RISKS.md`,
`EXECUTION_PLAN.md` and this report — no `backend/`, `backoffice/` or `mobile/` source — so no tier
gate applies to the commit.

---

## Review

`@agent-reviewer` against the diff, per CLAUDE.md. **One blocker and eight should-fixes, all
applied.** No secret, credential or customer PII in any changed file. Every factual claim I made
about the codebase was checked against source; the list that held is on the tickets. What follows
is what did not hold, because the corrections are the substance.

### The blocker: I would have written unerasable PII into the audit chain

Ticket 09's manual-edit decision put the old and new field values in `payload_json`, justified by
"the audit schema already stores the full Civil Registry response, so holding PII is its job".
**That conflated two stores with opposite retention properties**, and this codebase states the
convention explicitly: `audit_artifact.body` is purgeable (`V0002:28,32`, with `body_purged_at`
sized for the 90-day rule), while `payload_json` is "written once, what gets hashed" (`V0002:52`)
and can never be erased. `IdentityScanService` says so at three separate places.

The consequence would have been a customer's national number sitting permanently in a hash chain,
beyond the reach of any retention rule — and it would have failed **ticket 09's own question 5**,
which asks precisely that nothing in a payload carry PII the schema is not permitted to hold. The
decision now splits: field name and change-flag in the payload, values in a purgeable artifact body.
The product owner's actual requirement — the log must say what a field became — is untouched.

### Seven more corrections

- **`app.profile`'s PII is not nulled at 90 days.** I stated it as live behaviour to justify the
  print's retention. The only 90-day rule is abandoned-profile retention, provisional pending
  OQ-001, and `V0055`'s own header says full profile PII nulling "stays unwritten by any code". For
  the only printable statuses there is no rule at all. Recorded as a rule to be built.
- **Restricting printing to `submitted`/`approved` does not reconcile the retention rule — it makes
  it inoperative.** No purge can reach those statuses, so the rule has no trigger today.
- **A plain count check does not prevent the last-admin lockout.** Under READ COMMITTED it is a
  read-then-write; two admins disabling two different admins both commit. Needs `FOR UPDATE` over
  the set, or SERIALIZABLE.
- **"Mid-session disable: nothing to build" was too broad.** It holds only for endpoints resolving
  an `OperatorIdentity`; `/api/v1/admin/**` runs off the cached principal, so a disabled admin keeps
  a live session. Added as a build requirement.
- **`UNIQUE (cycle_id, kind)` would have made the second print of a profile a constraint
  violation.** The print row must be profile-keyed with `cycle_id` NULL.
- **"Divergence is computable" fails exactly where the feature is aimed** — a branch-visit profile
  has no registry result to diverge from. Two states now: `overridden` and `manually entered`.
- **The print payload's provenance-matrix version has no writer.** `V0027`/`V0029` create the table;
  nothing inserts into it. Populating it is part of the print work, not an assumption.

### Two process findings, and one of them matters more than the rest

**I filed a duplicate risk row.** R-055 said what `R-054` already said — written the same day, for
ticket 06 — and my line "ticket 06 asked for this row" was wrong, because that ask was already
discharged by R-054. Worse, R-054 carried a bound that ticket 10 silently invalidates ("it cannot
invent a Uqudo scan or a Civil Registry result"), and filing a second row would have left the first
one quietly stale while looking like diligence. **R-055 was withdrawn before commit and R-054
amended instead**, including a precision I had overstated: the database-role split still separates
evidence custody from the application, so what is gone is separation over the *business* action, not
everywhere. `R-037` is now named as a prerequisite of R-054's own mitigation, since seals are
produced and never exported.

**Ticket 10 is an architecture decision and had no `AD` row.** EXECUTION_PLAN's own S8-19 note
called it "an architecture decision CLAUDE.md forbids settling in passing", and the two comparable
rulings of the same day each got one — yet this was recorded only in a `.scratch` ticket and a
backlog row. Now **AD-015**, and `CLAUDE.md`'s architecture section, which still described the back
office as performing "the system's only two write actions", is corrected: that sentence became false
the moment this decision was taken.

Also applied: `map.md` gained decision lines for all six tickets closed across S8-19 and S8-20 (02
and 04 had been missed by the previous session too), and its four-eyes fog item was rewritten —
sharpened rather than discharged, since the exposure grew while the trail improved.

### Left open deliberately

`docs/bank-hosting-specification.md` is still untracked and is not this session's work. `R-053` is
precisely about that content entering a commit by default, so it stays out until someone decides it
should go in.

**One gap I raised at review turned out to rest on a false premise, and the product owner
corrected it: the print is INTERNAL and is never handed to the customer.** I had framed the
printed-then-edited case as "the customer holds a document contradicting the bank's record", which
cannot happen. What remains is an internal file holding a point-in-time snapshot that a later edit
superseded — which is what ticket 05 decision 2 already describes deliberately, and what any filing
system does. The edit is on the audit trail and the snapshot carries its own date. Whether a branch
must reprint after an edit is a bank process question, not a system one.

The correction also voided the *reasoning* behind two decisions. Ticket 05 decision 5 survived on
a better reason (restated above). Ticket 10 decision 7 did not, and went back to the product owner:
it declined per-field operator names partly because they would "leak internal identities onto paper
that leaves the building" — paper that, it turns out, never leaves.

**The outcome was not a swap but a second form.** The print now ships in an **unattributed**
variant (manual and overridden fields marked, no staff names) and an **attributed** one (the same
form, naming the operator who entered each such field), with the operator choosing at print time.
The compact one is for routine filing; the attributed one lets a colleague see who typed what
without opening an audit query — which matters more now that the audit trail is the only remaining
control.

Three consequences were settled with it rather than left to the builder: the two forms are **two
`kind` values** on `app.artifact_ref` rather than one kind with a flag (the table has no generic
metadata column, and `kind` is already the enumeration of what an artifact is, so a stored row says
which form it is without anyone opening the PDF); the **print audit event records which variant**
was produced, since two forms of one profile differ in content and in disclosure; and **both
variants are available to operator and admin, never viewer**.

## Commit proof

Captured AFTER the push.

```
$ git push origin main
   4b8e5b2..5e41a32  main -> main

$ git status -sb
## main...origin/main
?? docs/bank-hosting-specification.md

$ git log --oneline -1 origin/main
5e41a32 S8-20: the back office's decision layer emptied — four tickets settled
```

`## main...origin/main` carries no "ahead" marker, which is the proof (S8-12).
`docs/bank-hosting-specification.md` is untracked and pre-existing — see "Left open deliberately".

This report's own proof commit follows it, the same shape S8-18 and S8-19 used.
