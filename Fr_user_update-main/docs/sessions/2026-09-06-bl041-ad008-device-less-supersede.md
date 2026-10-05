# 2026-09-06 — BL-041 / AD-008: a device-less re-entry must not inherit the identity artifacts

Backend only. No mobile change was needed or made. The fraud fix at the top of the
pre-production queue; AD-008 was already closed as a decision, so this is pure build.

## What was wrong

`ContactChannelsService`'s Stage 1b re-entry transaction refreshed contact details, channel
states, branch and OTP challenges, and superseded nothing. A person entering **another**
customer's account number on a new device therefore inherited that customer's accepted
identity cycle — document scan, extracted document data, Civil Registry record, face match —
and their signature, and could submit a profile carrying all of it while editing the data and
receiving every OTP on their own handset.

## The crux: how a device-less re-entry is detected

The task named this as the thing to verify rather than assume, and the first answer was that
it **cannot** be detected. That is true of *device* identity: `ContactChannelsRequest` is six
fields, `ContactChannelsController.submit` reads no header, `reentry` is the single bit
`findExisting(accountNumber).isPresent()`, and there is no idempotency key in either tier. A
pre-build audit returned CANNOT and recommended stopping.

It is detectable by **journey position**. The trigger is a Stage 1b re-POST for a profile that
already holds an `active` identity cycle, and that is expressed as the `WHERE` clause of the
supersede's own `UPDATE` rather than as a separate read — so it cannot be evaluated against a
state that changes before the write lands. No legitimate same-device route into Stage 1b can
hold an active cycle:

- the Stage 2 "wrong phone number?" correction lives on the channel-verification screen, which
  precedes the Stage 8 scan;
- every other in-journey route back to account entry calls `abandon()`/`_clearSession()` first
  (`channel_verification_screen.dart`, `contact_channels_screen.dart`,
  `session_pending_screen.dart`), so local state is already gone — which makes it a device-less
  re-entry by definition rather than an exception to one.

This matters more than a flag would: `POST /api/v1/contact-channels` is unauthenticated by
design (R-051), where anything the client asserts is attacker-controlled. AD-008's "the
same-device resume is unchanged" clause is honoured structurally.

**My first version of this argument was wrong and is corrected in the code comments.** I wrote
that `ResumeContactChannels` is reached "only on an unrecognised `resumeStage`". It is not —
`'contactChannels'` is a real value written by `checkAccount()` and it does fall through to the
switch's default arm. The conclusion survives, but on the `_clearSession()` reasoning above.
Found by `@agent-reviewer` checking the mobile switch rather than taking the claim.

## The complete supersede set

Enumerated exhaustively because a partial fix that looks complete is this task's stated failure
mode.

**Superseded by the cycle write** (every customer-facing read joins `ic.state = 'active'`, so
superseding the cycle is what revokes them): `app.scan_result` and all its extracted document
columns; `app.registry_result`; `app.face_result`; and the `artifact_ref` kinds `doc_front`,
`doc_back`, `doc_front_frame`, `doc_back_frame`, `portrait_uqudo`, `portrait_registry`,
`face_audit_trail`.

**Superseded separately, because no cycle write can reach it:** the profile-keyed `signature`.

**Reset:** `scan_attempts_national_id`, `scan_attempts_passport`, and the stale
`pending_scan_session_id` / `pending_scan_nonce` / `pending_face_session_id` handles.

**Deliberately not touched, each with a reason:** `scan_attempts_total` and `scan_blocked_until`
(below); `liveness_attempts` / `liveness_blocked_until` (AD-008 silent; same family of
per-profile abuse bound); customer-entered data (AD-008 requires it kept); `app.profile.status`
except the one case below; artifact bodies; and `salary_certificate` — the *other* profile-keyed
artifact, customer-supplied rather than identity-derived and so outside AD-008's scope, named
here so it is dispositioned rather than missed.

## The signature is the whole fix, not a corner of it

`JdbcSubmissionRepository`'s gate read `EXISTS (... WHERE ar.profile_id = p.profile_id AND
ar.kind = 'signature')` — profile-keyed, no cycle linkage, no state filter. V0026 gave the
signature `profile_id` with `cycle_id` NULL deliberately ("a signature belongs to the whole
profile"), so a cycle supersede cannot reach it.

The consequence is the one a cycle-only fix misses. AD-008 *requires* the impostor to rescan.
Once they do, a fresh accepted cycle and a fresh passed `face_result` exist, every cycle-keyed
gate is satisfied again — and `has_signature` is still true from the victim's row. The pointer
skips SIGNATURE, answers SUBMIT, and the submitted profile carries a signature drawn by someone
else, Stage 11 never shown.

Closing it needed schema work: `app.artifact_ref.state` was `CHECK (state IN
('staged','committed','purged'))` and `fru_app` holds no DELETE. **V0063** widens that CHECK to
include `superseded`; `has_signature` gains `AND ar.state = 'committed'` (chosen over `<>
'superseded'` because it also excludes `staged`/`purged`, matching the operator view's existing
filter). No grant was owed — V0010 already gives `fru_app` UPDATE. No migration was owed for the
new `identity_superseded` audit event — `audit.audit_event.event_type` is a vocabulary held in
code with no CHECK (V0002). A redraw returns the row to `committed` with no extra code, because
`JdbcSignatureRepository`'s upsert already carries `state = EXCLUDED.state` for the identical
`purged` case; V0055's purge predicate is `<> 'purged'`, so a superseded row stays purgeable.

## The budget reset, and why not the obvious statement

`RESUME_FROM_SCAN_BLOCK` zeroes all three counters and clears `scan_blocked_until`. Reusing it
was the obvious move and would have been a security defect: AD-008's own promise is that "the
total-based 24-hour block still keys on cumulative attempts across sessions, so abuse stays
bounded despite the reset", and on an unauthenticated endpoint that statement would let anyone
holding an account number clear the block on demand, one re-entry at a time. A new statement
resets the two per-type counters only.

## The resume-point cap is derived — BL-018 closed

`app.profile.resume_stage` is written and read by no code; BL-018 was accurate. Writing it now
would produce dead state with no reader, which is why that row stayed open since 2026-08-30. The
cap is enforced by the supersede plus the three gates that already require
`state='active' AND accepted_at IS NOT NULL` — liveness (Stage 10), signature (Stage 11),
submission (Stage 12). Once the cycle is superseded, the customer's only forward move is a fresh
scan, which is exactly "capped at identity-type selection". Server-enforced, not left to the
client.

This is also what makes the S5-08 pointer safe as designed. It was deliberately built as
placement-not-authorization *so* this fix could land; after supersession `has_accepted_cycle` is
false and `face_passed` is NULL, so it answers `STATE_CONFLICT`, not SIGNATURE or SUBMIT.

## Two corrections to the approved plan, both forced by evidence

**1. The predicate is `state = 'active'`, not `active AND accepted_at IS NOT NULL`.** The
approved plan restricted the supersede to accepted cycles and filed the unaccepted case as an
acceptable residual. A revert-restore proof that *failed to fail* exposed why that was wrong:
`INSERT_ACTIVE_CYCLE` inserts with `accepted_at` NULL, and only Stage 9's Accept stamps it, so
`active`+unaccepted is the ordinary mid-review state rather than an edge. Restricting to
accepted cycles would have left a re-entering impostor the previous customer's extracted
document data, Civil Registry record and document images on the Stage 9 review screen, and let
them stamp `accepted_at` on that cycle themselves. The submission gates would still have refused,
so this was PII disclosure rather than submission forgery — but it was a live path, not the
stale-display risk I had described when filing it. One clause, no same-device cost, so it was
closed rather than deferred.

**2. The test account range moved to 0000000550–0000000560.** The first full gate failed on
`IdentityScanIntegrationTest` — `expected: <1> but was: <3>` on a portrait count that spans a
profile's cycles. Cause: that class uses 0000000531–**0000000536** while its allocation entry in
`AbstractPostgresIntegrationTest` stops at 534, and I had claimed 535+ on the strength of the
doc. My range collided; my re-entry-plus-rescan tests added cycles to its fixture. Both entries
are corrected, and the stale one now says to grep the sources rather than trust the list.

## Reviewer findings and dispositions

`@agent-reviewer` ran against the diff and BL-041, asked specifically to check supersede-set
completeness and that the same-device path is untouched. Verdict: set complete; same-device path
untouched, but with a status the argument never considered.

| # | Finding | Disposition |
|---|---|---|
| BLOCKER | A re-entry on an `awaiting_registry` profile superseded the cycle without moving the status, permanently bricking it | **Fixed** — see below |
| SHOULD FIX | V0063's retention justification contradicted by V0046's un-state-scoped unique index | **Comments corrected; gap filed as BL-062** |
| SHOULD FIX | The two "nothing is written" unit tests did not name the new destructive writer | **Fixed** — `verifyNoInteractions` added to both |
| SHOULD FIX | BL-041's resume-point-cap deliverable neither built nor dispositioned | **Fixed in docs** — BL-041 and BL-018 rows now record "derived", naming the three gates |
| NOTE | The same-device argument was stated inaccurately in three comments | **Fixed** — comments now carry the `_clearSession()` reasoning and say why the earlier claim was wrong |
| NOTE | `salary_certificate` is the other profile-keyed artifact and was in neither half of the inventory | **Dispositioned** in the BL-041 row; no code change (outside AD-008's scope) |
| NOTE | The supersede is now an unauthenticated destructive write | **RISKS R-051 extended** |

### The blocker, in full — a defect this change introduced

A registry lookup that fails leaves the profile at `awaiting_registry` with an `active`,
un-accepted cycle. Superseding that cycle without moving the status closed every exit:
`issueToken` and `submitScan` refuse with `RegistryReviewPendingException` while the status says
`awaiting_registry`; the three Stage 9 actions and the review read all enter through
`requireActiveReview`, which needs the cycle just superseded; and V0020's only
`awaiting_registry -> in_progress` arc is written by `retryRegistryLookup`, which needs it too.
No sweeper exists, so the profile never self-heals — the only escape was an operator manual
completion.

The supersede now also clears the pause (a legal V0020 transition) with its own
`app.profile_status_history` row, actor `system`. The port returns a `registryPauseToClear` flag
and the caller performs the transition *after* appending the `identity_superseded` event, because
the history row references that event's id. The test asserts the column **and** that the customer
can actually issue a scan token afterwards, which is the thing that matters.

Note what caught this: my own reasoning had gone one level deep ("the cycle must go, because the
scan stage is atomic") without asking what the profile *status* was when it did.

### The retention limit, stated rather than assumed

V0063's header claimed superseded artifacts are retained per customer.md l.174-175. True for
every cycle-keyed artifact — a rescan opens a new cycle, so the old rows persist. **Not** true
for the signature: V0046's index is `UNIQUE (profile_id) WHERE kind = 'signature'`, not scoped by
state, and the upsert targets it, so the next signature drawn overwrites the superseded bytes.
The supersession itself survives as the audit event; the image does not survive a redraw. The
comments now say exactly that, and closing it — scoping the index and the conflict target to
`state = 'committed'` — is filed as **BL-062** rather than done here, because it changes purge and
upsert semantics for a table this security fix had no reason to touch.

## Design decisions worth recording

- **The port lives in `identityscan/domain`, the write happens in `contactchannels`.** What
  counts as an inheritable artifact is identity-scan's knowledge; a narrow two-method port keeps
  `ContactChannelsService` from acquiring a surface that lets it insert cycles or spend attempts.
- **The trigger and the write are one statement.** The "does an inheritable identity exist" test
  is the `WHERE` clause, so there is no read-then-write window.
- **The call sits after `lockAndCheckStillEligibleForReentry`**, inside the existing transaction
  and under its `FOR UPDATE OF p`, so it never runs on a profile that turned terminal mid-send.
- **Artifact bodies are retained, not deleted.** Non-inheritance comes from the readers, not from
  destroying evidence. `LivenessIntegrationTest`'s
  `portraitArtifactSurvivesWhenTheIdentityCycleIsSuperseded` was **left unchanged** — the session
  brief asked for it to be inverted, but it exercises Stage 9's "wrong number" path, not AD-008's,
  and its assertion is correct for both. A sibling test asserts the AD-008 truth instead.

## Revert-restore proofs

Three, each against the bug shape a happy-path test would not catch.

**1. The signature filter** — remove `AND ar.state = 'committed'` from `JdbcSubmissionRepository`:

```
[ERROR]   DeviceLessReentrySupersessionIntegrationTest.theInheritedSignatureCannotCarryAnImpostorPastStageEleven:174
  the victim's signature must not carry the impostor past Stage 11 ==> expected: <SIGNATURE> but was: <SUBMIT>
[ERROR] Tests run: 9, Failures: 1, Errors: 0, Skipped: 0
```

**2. The trigger guard** — remove the `cyclesSuperseded == 0` early return, so the budget reset
fires on every re-entry:

```
[ERROR]   DeviceLessReentrySupersessionIntegrationTest.aReentryBeforeTheScanSupersedesNothingAndLeavesTheSameDevicePathUntouched:222
  a same-device re-entry resets no counter and clears no pending handle ==>
  expected: <{scan_attempts_national_id=0, scan_attempts_passport=1, scan_attempts_total=1, ...
             pending_scan_session_id=d2f3c1fe-..., pending_scan_nonce=6ee3459b-..., ...}>
   but was: <{scan_attempts_national_id=0, scan_attempts_passport=0, scan_attempts_total=1, ...
             pending_scan_session_id=null, pending_scan_nonce=null, ...}>
```

**3. The supersede call itself** — neutralised in `ContactChannelsService`:

```
[ERROR] Tests run: 9, Failures: 8, Errors: 0, Skipped: 0
[ERROR]   ...aDeviceLessReentryMidRegistryReviewSupersedesTheUnacceptedCycleToo:273 expected: <superseded> but was: <active>
[ERROR]   ...aDeviceLessReentrySupersedesEveryInheritedIdentityArtifactAndBlocksSubmission:104 expected: <superseded> but was: <active>
[ERROR]   ...aFreshSignatureAfterSupersessionReturnsTheRowToCommitted:440 expected: <superseded> but was: <committed>
[ERROR]   ...theInheritedSignatureCannotCarryAnImpostorPastStageEleven:164 expected: <superseded> but was: <committed>
[ERROR]   ...theScanBudgetResetsPerTypeButTheTotalThatBoundsAbuseSurvives:332 expected: <0> but was: <1>
[ERROR]   ...theSupersessionWritesOneAuditEventNamingWhatItRevoked:413 expected: <1> but was: <0>
```

The one survivor is the same-device test, which correctly asserts nothing is superseded.

The blocker fix has its own proof: the test failed `expected: <in_progress> but was:
<awaiting_registry>` before it, written before the fix rather than after.

Every other assertion is a **direct wrong-value assertion** needing no revert — `state =
'superseded'`, the counters, `scan_attempts_total` unchanged, bodies non-null — because the
pre-fix code writes none of those states at all.

## Files

**New:** `V0063__app_artifact_ref_superseded_state.sql`;
`identityscan/domain/DeviceLessReentrySuperseder.java`;
`identityscan/domain/SupersededIdentity.java`;
`identityscan/jdbc/JdbcDeviceLessReentrySuperseder.java`;
`contactchannels/DeviceLessReentrySupersessionIntegrationTest.java` (10 tests,
`@Tag("integration")`).

**Modified:** `contactchannels/service/ContactChannelsService.java` (one dependency, one guarded
call, one audit event, one conditional status clear);
`submission/jdbc/JdbcSubmissionRepository.java` (the `has_signature` filter);
`contactchannels/service/ContactChannelsServiceTest.java` (3 new tests + 2 assertions);
`AbstractPostgresIntegrationTest.java` (range allocation, incl. the stale IdentityScan entry).

**Not modified, deliberately:** anything under `mobile/`; the same-device resume path;
`LivenessIntegrationTest`; `RESUME_FROM_SCAN_BLOCK`; operator SQL.

## Gate

`./mvnw verify -Pdb-integration-test`, final run, verbatim:

```
[INFO] Results:
[INFO] Tests run: 987, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] --- jar:3.5.0:jar (default-jar) @ backend ---
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 443 files clean - 0 needs changes to be clean, 0 were already clean, 443 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  01:41 min
```

## Plan files

BACKLOG — BL-041 closed; BL-018 closed as "derived"; BL-045 gains the AD-008 dimension; BL-062
filed. EXECUTION_PLAN — S5-14 added. PROJECT_PLAN — AD-008 marked BUILT. RISKS — R-051 extended.
