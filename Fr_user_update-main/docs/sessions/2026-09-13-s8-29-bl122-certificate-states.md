# S8-29 — BL-122: telling a declined salary certificate from a failed upload

**2026-09-13.** Three tiers. Closes BL-122; files BL-142 and BL-143.

A profile with no `app.artifact_ref` row of `kind='salary_certificate'` was ambiguous between a
customer who declined (it is optional and gates nothing) and one who attached a file that never
arrived. Both were byte-identical at rest. The mobile app already knew the difference —
`DataEntryDraft.salaryCertificatePath` beside `salaryCertificateUploadedAt` — and threw it away.

## The design decision, and why the asymmetry

**Only the CLAIM ("a file is attached") is asserted. The decline is its absence.**

- There is no decline affordance to assert from. The Stage 6 field offers capture, selection and
  retry — no remove, no skip — so declining is done by doing nothing. Asserting it would mean
  adding a customer-facing control to solve a back-office problem.
- It fails in the safe direction. A claim lost in transit reads as `DECLINED`, which is exactly the
  behaviour that shipped before this change, so no regression is reachable. A lost *decline*
  assertion would read as `ATTACH_FAILED` and send an operator chasing a customer who said no.
- A positive decline would not have removed any derivation anyway — see correction 2 below.

The counter-precedent was real and was weighed: `ChannelState.DECLINED` positively models a declined
optional input one package away. A declined channel is a choice made by acting; a declined
certificate is a choice made by doing nothing. That is what decides it.

**The signal is a claim, not an upload result**, and that reframing is what makes `Stage6Request`
the right carrier despite `submitStage6` being sent *before* the Next-time upload retry: picking the
file is what makes the claim true, and no later retry outcome can change it.

**Policy: settled, not deferred.** BL-122 said the answer "affects what an operator should do when a
certificate is missing, which is a review-policy question". Put to the product owner mid-session and
ruled: an operator does nothing differently. The certificate stays optional, gates nothing, and
`ATTACH_FAILED` is not a rejection reason. Hence no warning colour, no icon, no call to action.

## Where the brief was wrong

Verified before building, per the session's own instruction.

| Brief said | Source says |
|---|---|
| "Eight repository-level test cases" | **Seven** (`data_entry_repository_test.dart:317-411`). The same wrong count was live in `BACKLOG.md:109` and was copied from there — the exact rot the brief warned about, in the row it quoted. Corrected. |
| Stage 6 is mandatory, so a submitted profile has passed it | **False at the backend.** Submission gates on liveness and signature alone (`SubmissionService:163-169`), `DataEntryService` enforces no stage ordering, and Stage 6's POST is offline-queued. `NOT_REACHED` is a reachable state, not a theoretical one. |
| S8-25 keeps the certificate off printing | **Narrower than quoted.** It is never part of the *form*, but may print as a second, separate document. Its addendum's "not offered on a profile with no certificate" predicate reasoned explicitly from BL-122's limit — so this change invalidated the *reason* for a rule S8-25 already wrote. Rule unchanged, reason amended in place (two spots, one of which the first amendment missed). |

Everything else in the brief held: AD-016 verbatim, the «لم يُرفق (اختياري)» wording, the
`JdbcProfileViewRepository:375` label, V0059, migration head V0068, and `customer.md:526-528` still
stating the ambiguity.

Two structural facts the brief did not have: `artifact_ref` **cannot** hold a "failed" row
(`storage_key`/`content_type`/`byte_size`/`sha256`/`state` are all NOT NULL, V0008:68-83), and a new
column needs a migration, which the brief did not mention.

## What `@agent-reviewer` found in my own diff

Run against the brief before coding and against the diff before done. Three real defects, all fixed.

1. **`provenance='manual'` does not mean what I assumed.** I branched on it so a manual profile
   would never read as `DECLINED`, reasoning that its `employer_name` was an operator's typing under
   AD-015. But its only writer is `JdbcManualCompletionRepository`'s manual-completion UPDATE, which
   marks a **customer-started** profile an operator finished over the counter. The branch flipped a
   genuine `DECLINED` to `NOT_REACHED` the moment a profile was manually completed — putting "has
   not reached this stage yet" on a *submitted* profile, the precise thing `EmptyTile`'s own comment
   says must never be shown, on exactly the profiles an operator reviews most. **Fixed:** `resolve`
   no longer takes provenance at all. Pinned by
   `SalaryCertificateStateTest.manualCompletionDoesNotTurnAGenuineDeclineIntoNotReached`.
2. **The monotonic COALESCE was wrong, and its stated reason was also wrong.** I made the column
   never clear, justified by an out-of-order `flushPending` replay erasing a later claim. That
   mechanism does not exist: the replay re-runs `submitStage6`, which re-reads the draft, so no
   stale claim is ever replayed. Worse, monotonicity made this **the one Stage 6 column a
   device-less re-entrant's submission could never correct** — every other column is a full replace,
   so a previous person's claim would be reported about the new customer for ever. **Fixed:** the
   write is now `CASE ?::boolean WHEN true THEN COALESCE(existing, now) WHEN false THEN NULL ELSE
   existing END` — `true` records, an explicit `false` corrects, and an omitted field (a client
   built before this existed) leaves it alone. The `NULL` arm is the case that actually needed care,
   and is the same reason `Stage6Request` boxes the field.
3. **No test produced `PRESENT` through the real SQL.** `SalaryCertificateStateTest` takes
   `hasCommittedArtifact` as a parameter, and both integration tests asserted *zero* certificate
   artifacts — so a wrong `kind`, `state` or join key in the new `EXISTS` would have passed the
   entire change. **Fixed:** `aCommittedCertificateIsPresentAndAPurgedOneIsNotFoundByTheQuery`.

It also confirmed clean: the informative-only scope rule (no warning styling, gate or CTA
anywhere), tile/state consistency, the mobile read's interaction with the offline queue and the
per-stage mutex, that `UPDATE_STAGE6` is the column's only writer, and the `domain` package rule.

## Revert-restore proof

The write semantics are an ordering defect whose happy path is identical either way, so per
CLAUDE.md it needs the revert. With the guard replaced by a plain assignment:

```
[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0
org.opentest4j.AssertionFailedError: expected: <ATTACH_FAILED> but was: <DECLINED>
[ERROR]   OperatorProfileViewIntegrationTest.aLaterStage6SayingNothingIsAttachedCannotEraseAnEarlierClaim:206
```

A replayed straggler silently converted a failed upload back into a decline — BL-122 rebuilt out of
the fix for it. The sibling three-state test **passed** against the broken version, which is exactly
why the guard needed its own test. (That test was later rewritten for the corrected semantics above
and is now `anExplicitFalseClearsTheClaimButAnAbsentFieldDoesNot`, covering both arms.)

## Live cross-tier proof

The integration suite runs against Testcontainers; this runs the **packaged jar** against the
compose Postgres, which is the pairing CLAUDE.md records as having silently run two migrations
behind at S5-01. Real form-login operator session (CSRF token, `/api/v1/auth/login`), real
`/api/v1/operator/profiles/{id}` reads.

```
STATE          stage6   account    profileId
----------------------------------------------------------------
DECLINED       false    0000000901 41b64ab7-28ef-4e6c-8244-a405ea3f2207
ATTACH_FAILED  true     0000000902 a8b8175e-21f4-481c-8def-43179713b26b
NOT_REACHED    (none)   0000000903 4a6c46f8-6d32-4bd2-a9b1-02679700a5e6

--- the same profile corrected: true -> omitted -> false ---
after true    : ATTACH_FAILED
after omitted : ATTACH_FAILED  (a pre-BL-122 client must not clear it)
after false   : DECLINED  (an explicit correction must land)
```

V0069 applied cleanly to the live database ahead of this (`flyway_schema_history` head `0069|t`,
column present as `timestamp with time zone`).

## What I did NOT do

- **The emulator run the brief asked for.** An AVD is available, but driving a six-stage Arabic form
  journey blind through `adb input` is not something I could verify honestly, and reporting it as
  done on that basis would be worse than not doing it. The residual risk this leaves is narrow and
  specific: whether the real Dio client serialises the new key. I closed **that** directly instead,
  with `dio_data_entry_api_test.dart` driving the real `DioDataEntryApi` through an interceptor and
  asserting the key on the wire — the repository and screen tests both stop at the API interface, so
  nothing else covered it. What remains unproven is only the physical device path itself.
- **A screen-level test of the failed-upload case.** `stage6_screen_test.dart` covers the claim for a
  picked certificate but stamps the draft as uploaded, because the Next-time retry does real file
  I/O (`File.exists`, `readAsBytes`) that never completes inside `pumpAndSettle`'s fake-async zone —
  it hung the test until I found the cause. The failed-upload case is proven at the repository
  level, under plain `test()`, where the real failure runs end to end.
- **Settle a fifth operator-facing state** for a purged certificate, or decide whether a re-entrant
  inherits the previous customer's pay document. Both are product/AD-008 questions; filed as BL-142
  and BL-143.

**One unforced error, local only:** the live proof needed an operator password and I overwrote
`s828.admin`'s `password_hash` on the local compose database without saving the old value, so that
account's previous dev password is gone on this machine. Synthetic account, local database, nothing
in the repo and no production impact — but it was avoidable and is recorded rather than quietly left.

## Test debt found and paid

Both screen-level guards protecting the race BL-122 touches were untested — `stage6_screen_test.dart`
had exactly one certificate test (the picker actions). Since this change edits the Stage 6 payload
path, it adds screen-level coverage rather than editing that code with no net.

## Gates

Backend, `./mvnw clean verify -Pdb-integration-test`:

```
[INFO] Tests run: 1155, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 471 files clean - 0 needs changes to be clean, 471 were already clean, 0 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Analyzed bundle 'backend' with 340 classes
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

Mobile, `fvm flutter analyze` then `fvm dart run tool/check_coverage.dart`:

```
Analyzing mobile...
No issues found! (ran in 139.7s)

04:22 +654: All tests passed!
Line coverage: 85.93% (4356/5069 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

Back office, `npm run lint` (exit 0, pre-existing warnings only), `npm run test:coverage`,
`npm run build`:

```
 Test Files  21 passed (21)
      Tests  175 passed (175)
Statements   : 97.24% ( 529/544 )
Branches     : 86.77% ( 328/378 )
Functions    : 97.64% ( 166/170 )
Lines        : 98.96% ( 480/485 )
✓ built in 19.26s
```

No Flutter package added, so no iOS support check applies.

**A Maven trap worth recording:** plain `./mvnw test-compile` reported BUILD SUCCESS against test
sources that could not compile — 13 args to a 14-arg method — because incremental compilation
skipped unchanged test files. Only `clean test-compile` surfaced it. Any gate run after a signature
change needs the `clean`.

## Commit proof

Captured after the push, per CLAUDE.md.

```
$ git push origin main
To https://github.com/Osmantou/Fr_user_update
   469be42..91b3693  main -> main

$ git status -sb
## main...origin/main
?? docs/bank-hosting-specification.md

$ git log --oneline -2
91b3693 S8-29: an operator can tell a declined certificate from a failed upload (BL-122)
469be42 AD-016: the salary certificate is a profile artifact (decision recorded)
```

`main`, not a branch. The status line reads up to date with `origin/main` — no "ahead of". The one
untracked file predates this session (it was present in the starting `git status`) and is not mine
to commit.
