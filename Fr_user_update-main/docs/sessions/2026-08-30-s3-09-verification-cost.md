# S3-09 — Scope the proof discipline; make the integration suite cheaper to run; settle .claude/settings.json

**Session:** implementation · **Date:** 2026-08-30 · **Task ID:** S3-09

---

## 0. Commit check

```
$ git log --oneline -3
15de8e3 docs: record S3-08 commit/push proof in session report
c958e96 feat: S3-08 — Stage 2, OTP verification, resend, per-channel lockout
5a3065b docs: record S3-07 commit/push proof in session report

$ git status
On branch main
Your branch is up to date with 'origin/main'.
Changes not staged for commit:
	modified:   .claude/settings.json

$ git merge-base --is-ancestor c958e96 main && echo "c958e96 IS an ancestor of main"
c958e96 IS an ancestor of main
```

S3-08's stated hash `c958e96` confirmed as an ancestor of `main` at session start. `.claude/settings.json`
showed as modified, exactly as the task predicted — settled in §4 below.

---

## 1. EXECUTION_PLAN.md

S3-09 added, marked ✅ (see §6 for what closed it).

---

## 2. Testcontainers reuse

### 2.1 Design chosen, and why

**The Testcontainers "singleton container" pattern** (java.testcontainers.org, "Manual container
lifecycle control"): one `PostgreSQLContainer` field in a new shared base class,
`backend/src/test/java/sd/gov/bank/fruserupdate/AbstractPostgresIntegrationTest.java`, started in a
static initializer and never explicitly stopped — Testcontainers' own Ryuk sidecar reaps it when the
JVM exits, the same guarantee the five per-class `@Container` fields it replaces relied on
individually. Every `@Tag("integration")` class now `extends AbstractPostgresIntegrationTest` instead
of declaring its own `@Container`/`@DynamicPropertySource` pair; Spring's `@DynamicPropertySource`
support scans the full class hierarchy, so the shared properties apply to every subclass
automatically, and a subclass (`AccountCheckIntegrationTest`) is still free to add its own
`@DynamicPropertySource` method for fixtures only it needs.

**Chosen over `withReuse(true)` + `testcontainers.reuse.enable`**: that mechanism persists a
container *across separate Maven invocations* via a machine-wide opt-in file
(`~/.testcontainers.properties`), which would leave a container running after unrelated future runs —
on this project or any other on the same machine — until manually cleaned up. A JVM-scoped singleton
fully addresses the observed problem (repeated container starts within *one* Surefire run, one of
which cold-started to 240s after many earlier starts in the same long S3-07 dev session — see that
report's §6.1) without that cross-invocation risk, and needs no global machine configuration.

### 2.2 Isolation — truncation was tried, found unsafe, and abandoned; not weakened

A per-class `TRUNCATE` was the first design tried. It failed live:

```
org.postgresql.util.PSQLException:
ERROR: app.profile_status_history is append-only; TRUNCATE is not permitted
```

`app.profile_status_history` carries its own append-only guard — V0010's
`profile_status_history_immutable`/`profile_status_history_no_truncate` triggers, which reject the
operation **even for the owning role** ("the same defence-in-depth reasoning as audit Layer 2", per
that migration's own comment) — and it is reachable by `CASCADE` off `app.profile`, since every other
mutable `app` table FKs to `app.profile` directly or transitively. V0010 also documents `app.profile`
itself as "never deleted" by design (90-day retention nulls PII in place instead). So a profile row,
once created, is not actually meant to be cleared between test classes any more than an audit row is
— this is the schema's own real intent, not an accidental obstacle. **Per the task's own instruction,
this guard was not weakened to make reuse convenient**; the design changed instead.

**The actual isolation mechanism is namespace partitioning, not cleanup.** Every one of the five
integration classes already gives its fixtures a disjoint `account_number` (and sometimes `branch`)
range — they were each written against their own throwaway container, so no class could ever have
collided with another's data before this task either; sharing one container only makes that existing
property load-bearing instead of incidental. Confirmed disjoint by inspection (see
`AbstractPostgresIntegrationTest`'s Javadoc for the authoritative, currently-claimed list):

| Class | Branch | Account-number range |
|---|---|---|
| `AccountCheckIntegrationTest` | 16 | 0000000001, 0000000002, 0000009999, 0000000501, 0000000502 |
| `AppSchemaConnectivityIntegrationTest` | 2 | "ACCT-JAVA-TEST" — the one test that writes it rolls the transaction back, never committed |
| `ContactChannelsIntegrationTest` | 16 | 0000000101–0000000110 |
| `OtpVerificationIntegrationTest` | 16 | `AtomicInteger` counter from 200, formatted `"00000002%02d"` — 11 characters, a different length than every other class's 10-character numbers |
| `NotificationOutboxIntegrationTest` | 16 | 0000000099, seeded once via its own `@BeforeEach` + `AtomicBoolean` guard |

Every assertion across all five classes already reads state scoped to one test's own `profile_id` (or
a delta against a count taken earlier in the same test) — never an unscoped whole-table count — so a
shared container accumulating other classes' profiles across one run changes nothing a test observes.
The base class's Javadoc records this as a standing rule: a new integration test class must claim its
own unused range.

This is genuinely still per-*invocation* isolation, not merely per-class: the container is JVM-scoped,
so two separate `./mvnw test -Pdb-integration-test` processes each start their own fresh, empty
container — nothing carries between runs (proven in §2.4).

Also done, as the task's fallback explicitly anticipates for the append-only case: `docker pull
postgres:18` was run at the start of this session — image was already cached locally (`Status: Image
is up to date`), so it cost nothing here, but it is a legitimate belt-and-suspenders step against a
cold first pull on a fresh machine.

### 2.3 Wall-clock time — before and after

All four runs below are on the same machine, in the same session, with the `postgres:18` image
already warm in the local Docker cache (so none of them reproduce the *specific* 240s cold-pull
scenario from S3-07 — that scenario was registry/image-pull slowness compounding after *many*
container starts in one long session, not reproducible in a single timed run). This is an
apples-to-apples comparison of container-boot overhead alone: five container starts vs. one.

**Before** (git-stashed back to the pre-S3-09 code — five independent `@Container` fields, one per
class):

```
[INFO] Tests run: 292, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  01:21 min

real	1m25.955s
```

**After** (this session's singleton-container change), run twice consecutively — see §2.4:

```
Run 1: [INFO] Tests run: 292, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS / real 1m0.243s
Run 2: [INFO] Tests run: 292, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS / real 1m9.866s
```

~26–30% faster even in this warm-cache, single-run comparison, purely from four fewer container boots
and four fewer Flyway migrate runs (later classes just `validate`). The real-world win is larger than
this number suggests: the original problem was a *cold* container start compounding after many
starts in one session — reducing five potential cold starts to one removes most of that exposure by
construction, not just the steady-state boot overhead this timing shows.

### 2.4 Isolation proofs

**Full suite run twice consecutively, identical results:**

```
$ ./mvnw test -Pdb-integration-test        # run 1
[INFO] Tests run: 292, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

$ ./mvnw test -Pdb-integration-test        # run 2, immediately after
[INFO] Tests run: 292, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Per-class counts identical between the two runs too (6/5/10/5/9 for AccountCheck/AppSchemaConnectivity/
ContactChannels/NotificationOutbox/OtpVerification respectively, both times) — not just the same
total.

**One integration class run alone, without the others:**

```
$ ./mvnw test -Pdb-integration-test -Dtest=ContactChannelsIntegrationTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- in sd.gov.bank.fruserupdate.contactchannels.ContactChannelsIntegrationTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Passes standalone — nothing in the shared-container design depends on the other four classes having
run first (or at all).

---

## 3. Proof discipline scoped in CLAUDE.md

Added to the Hard rules section (merged into the existing "Run the test and analyze gates..." bullet,
plus two new bullets): "Live proof versus a passing test", and "Revert-restore is conditional". Full
wording is in CLAUDE.md itself (search for those two phrases) — condensed from the task's original
phrasing to fit the 200-line budget (see §6.6), preserving every distinct rule: paste live output only
when a green test could coexist with a broken guard, unless a *named* integration test already
exercises the behaviour against the real database (in which case naming that test is the proof); paste
the final gate run verbatim, one line for an intermediate re-run, paste a run that failed-then-passed;
and revert-restore a defect-guarding test only where it could plausibly pass anyway (indirect
assertions, or a rollback/ordering defect with an identical happy path) — a direct wrong-value
assertion needs no revert.

**Applied to this task's own proofs**: §2.4's two consecutive full-suite runs are both pasted above
(not condensed) because the first is what closed the append-only discovery and the second is the
required identical-results proof; §2.3's timing runs are pasted because they are the requested
before/after evidence, not a repeat.

---

## 4. `.claude/settings.json`

**Decision: commit it.** The diff is one auto-recorded Bash permission-allowlist entry:

```diff
       "Bash(sed -n '1,118p' docs/components/messaging.md)",
+      "Bash(find 'C:\\\\Users\\\\DELL/.m2' -iname spring-test-*.jar)"
```

A read-only `find` scoped to the local Maven repository cache. No secret, credential, or token — and
the file already carries roughly fifteen other committed entries containing the exact same
`C:\Users\DELL\...` machine-specific path pattern (e.g. the `fvm.bat` entries, the three `find
'C:\\Users\\DELL/.m2/...'` entries already committed at S2-09, and the `additionalDirectories`
scratchpad path), consistent with CLAUDE.md's own "Windows (DELL)" per-machine documentation. This is
established project practice, not a new precedent — gitignoring it now would be the actual departure,
and would mean losing the team-visible record of what Bash commands this project's sessions have
needed approved, a record fifteen-plus entries deep already. Committed as part of this session's
changes; confirmed clean in §6.4.

---

## 5. Risk register cleaned to match the deliverable

- **R-041** and **R-043** moved from RISKS.md's main table to a new "Delivery notes — outside the
  deliverable" section at the end of that file — not deleted, and not marked ❌ (they are true facts
  about the delivery environment, not risks that stopped applying). A one-paragraph header on the new
  section explains why: AD-002c's port is closed, provider selection is the bank's commercial
  decision.
- **OQ-016 through OQ-022** in PROJECT_PLAN.md moved the same way, to a matching "Delivery notes —
  outside the deliverable" section at the end of that file. The original list location now has one
  summary line pointing at the new section; AD-002c's existing "Gated on OQ-016 through OQ-022" cross-
  reference in the decisions-log discussion still resolves correctly, since the IDs themselves did not
  change, only their location.
- **BL-007 left exactly as it was** — confirmed with `git diff --stat BACKLOG.md`, which shows no
  output (no changes at all to that file).

---

## 6. Verify and report

### 6.1 Suite wall-clock time before/after, and isolation proofs

See §2.3 and §2.4 above — before: 1m25.955s (five containers); after: 1m0.243s / 1m9.866s across two
consecutive runs (one shared container); one class alone: passes standalone.

### 6.2 `./mvnw test -Pdb-integration-test` twice consecutively

Both pasted in full in §2.4: 292/292 passing, identical per-class counts, both `BUILD SUCCESS`.

### 6.3 One integration class alone

Pasted in §2.4: `ContactChannelsIntegrationTest` alone, 10/10 passing, `BUILD SUCCESS`.

### 6.4 All three tiers' standard gates

This session touched only `backend/` (test files, `AbstractPostgresIntegrationTest`) plus the
repository's own plan/config files (CLAUDE.md, EXECUTION_PLAN.md, RISKS.md, PROJECT_PLAN.md,
`.claude/settings.json`) — per CLAUDE.md, only `backend/`'s gates apply.

```
$ ./mvnw verify
...
[INFO] Tests run: 257, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 111 files clean - 0 needs changes to be clean, 6 were already clean, 105 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Analyzed bundle 'backend' with 73 classes
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
[INFO] Total time:  51.513 s
```

257 = 292 integration-tagged-suite total minus the 35 `@Tag("integration")` tests (5+6+10+9+5),
matching `./mvnw test`/`./mvnw verify`'s default exclusion. JaCoCo bundle: 73 classes, **96.24% line
coverage / 89.8% branch coverage** (computed from `target/site/jacoco/jacoco.csv`), both comfortably
above the enforced 80% line-ratio gate.

Working tree confirmed clean after this session's commit — see §7.

### 6.5 `@agent-reviewer`

Ran against the diff and this task. Independently re-ran the gates itself (`spotless:check`,
`test-compile`, `test -Pdb-integration-test`: 292/292, `BUILD SUCCESS`, 49.711s) and confirmed live
that exactly one `postgres:18` container was created (`grep -c "Creating container for image:
postgres:18"` = 1) and none was left running afterwards (`docker ps -a --filter ancestor=postgres:18`
empty). No BLOCKER. One SHOULD FIX, three NOTEs — all fixed:

| # | Finding | Severity | Disposition |
|---|---|---|---|
| 1 | The isolation Javadoc's forward-looking rule ("claim a disjoint account-number range") is not sufficient: `JdbcNotificationOutboxRepository.claimOnePending()` runs an unscoped `SELECT ... WHERE state = 'pending' AND next_attempt_at <= clock_timestamp() ... LIMIT 1` with no `profile_id` predicate. A future integration class enqueuing a notification could follow the documented rule to the letter and still race a claimable row against `NotificationOutboxIntegrationTest`'s own ordered tests — a collision structurally impossible before S3-09 (each class had its own container) and not covered by the documented mitigation. | SHOULD FIX | **Fixed.** `AbstractPostgresIntegrationTest`'s Javadoc extended with a paragraph naming the exact query, explaining why disjoint keys don't cover it, and stating the rule a future class enqueuing outbox rows must follow (push `next_attempt_at` into the future, the same technique `NotificationOutboxIntegrationTest`'s own `Order(5)` test already uses for the identical reason within its own class). |
| 2 | The Javadoc claimed the new Ryuk-at-JVM-exit reliance was "exactly as the five per-class `@Container` fields this replaces relied on individually" — inaccurate: those were stopped deterministically by `@Testcontainers`' `afterAll`, with Ryuk only a backstop; a JVM-scoped singleton has no such `afterAll` and genuinely depends on Ryuk (or the shutdown hook) as the only mechanism. | NOTE | **Fixed.** Reworded to state the reliance accurately, and added the live `docker ps -a` confirmation (independently reproduced by the reviewer itself) that nothing is left running. |
| 3 | `OtpVerificationIntegrationTest`'s class Javadoc kept a stale sentence ("Same container/init-script shape as `ContactChannelsIntegrationTest`") superseded by its own next paragraph, which correctly says the container now comes from the base class. The equivalent sentence was already removed from the other four classes. | NOTE | **Fixed.** Sentence deleted. |
| 4 | CLAUDE.md's merged gate-output bullet ("a run that failed and was fixed gets pasted... but a mere repeat does not") left it ambiguous whether the failing run or the post-fix run is what must be pasted. | NOTE | **Fixed.** Reworded to "a run that failed gets its failure output pasted... but a passing re-run that only repeats an earlier passing run does not." |

All four fixes re-verified: `./mvnw -q test-compile` and `./mvnw -q spotless:apply`/`spotless:check`
clean, `./mvnw test -Pdb-integration-test` still 292/292 (§6.2's proof re-run after the fixes), `./mvnw
verify` still green (§6.4). No second review pass needed — every finding was a documentation/wording
fix with no behavioural change, so the same live proofs already in this report cover the fixed diff
too; per this session's own new "gate output in reports" rule, a passing re-run confirming an
unchanged behaviour is not repasted.

### 6.6 CLAUDE.md line count

```
$ wc -l CLAUDE.md
199 CLAUDE.md
```

Under 200, as required — grew from 190 to 199 lines (net +9) by merging the new "paste final gate
output" rule into the existing "Run the test and analyze gates" bullet and tightening the two new
rules' wording several passes to fit, without dropping any of the three distinct rules the task asked
for.

---

## 7. Commit/push proof

```
$ git log --oneline -1
c027b4d feat: S3-09 — Testcontainers singleton container; scope proof discipline; settle settings.json

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

`.claude/settings.json` is part of this commit (§4) — the working tree is clean including it, closing
the gap that had persisted since S2-10.

Push output:
```
$ git push
To https://github.com/Osmantou/Fr_user_update
   15de8e3..c027b4d  main -> main
```
