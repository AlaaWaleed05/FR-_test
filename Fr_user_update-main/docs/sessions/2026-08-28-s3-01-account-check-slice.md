# S3-01 — Backend module structure and the stage 1a account-check slice

Date: 2026-08-28
Branch: `claude/s3-01-account-check-slice-38anff`
Scope: backend only. `mobile/` and `backoffice/` were not touched; their gates were run as
regression.

---

## 0. Step-0 findings

`git log --oneline -3` on a clean tree, before any work:

```
2e8a339 docs: record S2-04 commit/push proof in session report
9aa799c feat: S2-04 audit seal export — external anchor for the hash chain
0bfcf40 docs: record S2-10 commit/push proof in session report
```

`git status`: **`nothing to commit, working tree clean`**.

Two differences from what the task expected, reported rather than acted on:

1. **S2-04 is at `9aa799c`, not `41e8a8b`.** The shape matches what the task described — the
   S2-04 commit with a proof-recording commit (`2e8a339`) on top — but the hashes differ. No
   commit with the hash `41e8a8b` exists in this repository's history.
2. **`.claude/settings.json` is NOT modified.** The task expected it to show as modified and
   said to report it and leave it alone. It is clean, so there was nothing to leave alone. It
   was not touched during this session either, and is still clean at the end (see §9).

---

## 1. Environment — the gate commands CLAUDE.md documents do not exist on this machine

This session ran on Linux, not the Windows machine CLAUDE.md's Commands section was written
for. None of the three documented toolchain paths exists here. What was substituted, so the
gate output below can be read for what it is:

| Tier | CLAUDE.md says | Actually run here |
|---|---|---|
| backend | `./mvnw verify`, `JAVA_HOME` = Android Studio JBR | `sh ./mvnw verify` on OpenJDK 21.0.10. `mvnw` is mode 644 in the repo, so it is not directly executable — hence `sh`. Maven 3.9.16 via the wrapper, as pinned. |
| backoffice | isolated Node 24.19.0 install at a Windows path | Node **24.20.0**, installed with `nvm install 24` at `/opt/nvm`. The global node here is 22.22.2 and `engine-strict=true` refuses it, exactly as CLAUDE.md predicts. |
| mobile | `fvm flutter …` | `fvm` is not installed. Flutter **3.47.0** — the exact version pinned in `mobile/.fvmrc` — cloned to `/opt/flutter-3.47.0` and run directly as `/opt/flutter-3.47.0/bin/flutter`. Same SDK version, different launcher. |

Two more environment facts, both of which cost time and are worth recording for the next
session that runs here:

- **The Docker daemon was not running.** `docker info` reported a working client and
  `failed to connect to the docker API at unix:///var/run/docker.sock`. Started with `dockerd`.
- **Docker Hub image blobs are blocked by the network policy.** The proxy answered `403` to
  `CONNECT production.cloudfront.docker.com:443`, so `docker pull postgres:18` and
  `testcontainers/ryuk:0.14.0` both failed with `Forbidden` after resolving the manifest.
  Worked around by pulling the same images through `mirror.gcr.io` and retagging them to the
  names Testcontainers asks for. **The images are byte-identical** (same digests, `mirror.gcr.io`
  is a pull-through cache of Docker Hub), so the integration runs below are against the real
  `postgres:18`. Nothing in the repository was changed for this; it is local daemon state.

There is no `reviewer` agent definition in this environment — `.claude/agents/` does not exist,
and the available agent types are `claude`, `Explore`, `general-purpose`, `Plan`,
`claude-code-guide`, `statusline-setup`. §8 was run with a `general-purpose` agent given an
explicit reviewer brief. That substitution is stated there.

---

## 2. Findings from S2-04 recorded as risks (task step 1)

`RISKS.md` gains **R-035**, **R-036** and **R-037** verbatim as the task supplied them: the
integration fixture never installs Layer 3 so a whole class of migration defect is invisible to
tests; `audit.verify_chain()` is the accidental-corruption check and must never be mistaken for
the integrity check (`audit.seal_verify()` is that); and seals that are never exported protect
nothing.

`PROJECT_PLAN.md`'s AD-002d bullet gains the sentence assigning it the seal export destination
and schedule (R-037) and the profile database's encryption at rest (R-026).

### R-038, found while designing this slice

> Every account check serialises on one audit chain row.

`fru_app` holds `SELECT` only on `audit.audit_chain` (V0004) and cannot create a chain, so an
account check that creates no profile has to append to a pre-seeded singleton chain. That is
correct and deliberate, but it means `audit.chain_append()`'s `SELECT … FOR UPDATE` on that one
row serialises every concurrent account check in the system — and Stage 1a is the one stage
every customer reaches, including the ones who fail. Filed 🟡 Watching: at the stated scale
(~100,000 accounts over 12–18 months, roughly three submissions an hour) it is not a problem,
and a hash chain is serial by construction, so this is a fact to hold rather than a defect to
fix now. The two escape routes, if it ever bites, are recorded in the risk row.

---

## 3. The module structure — the decision S1-08 was blocked on

### The rule

> **Features are the top-level cut. Two package-name suffixes are reserved for business logic:
> `domain` and `service`.** A package with either suffix contains only code whose behaviour we
> chose and that a plain JUnit test can exercise with no Spring context, no database, no
> network and no clock. Everything else — `web`, `config`, `stub`, `jdbc`, and future adapter
> packages — is plumbing.

Two corollaries, both load-bearing:

- **A port lives in the logic package that calls it; its adapter does not.**
  `CoreBankingClient` and `AuditEventWriter` are interfaces in `…/domain`; `StubCoreBankingClient`
  and `JdbcAuditEventWriter` are in `…/stub` and `…/jdbc`. This is what lets S3-02 drop in a
  JDBC `CoreBankingClient` without the account-check logic changing at all, and it makes the
  Uqudo-parser quarantine R-034 requires the default shape rather than a special case.
- **Shared outbound integrations sit beside the features, one package per external system.**
  `corebanking` and `audit` are not inside `accountcheck` because they are not its property.

### What was actually created

```
sd.gov.bank.fruserupdate
├─ BackendApplication.java                       (unchanged)
├─ accountcheck
│  ├─ domain/   AccountCheckOutcome, AccountCheckContinuation          [90% tier]
│  ├─ service/  AccountCheckService, AccountCheckResult                [90% tier]
│  └─ web/      AccountCheckController, AccountCheckRequest, AccountCheckResponse
├─ corebanking
│  ├─ domain/   CoreBankingClient                       (port)         [90% tier]
│  ├─ config/   CoreBankingClientConfiguration
│  └─ stub/     StubCoreBankingClient, StubCoreBankingProperties
└─ audit
   ├─ domain/   AuditEvent, AuditEventWriter (port), CanonicalJson     [90% tier]
   └─ jdbc/     JdbcAuditEventWriter
```

No empty packages were scaffolded for stages that do not exist.

### Why this and not the alternatives

- **Layer-first** (`controller/`, `service/`, `repository/` at the root) scopes for coverage
  just as well, and was rejected on how it ages: a 13-stage journey would end with Stage 1a's
  controller sitting next to Stage 10's liveness controller, sharing a package with nothing in
  common. The natural unit of change here is a journey stage.
- **Feature-first with no internal split** (`accountcheck/` flat) reads best of the three and
  was rejected because it makes the 90% rule unscopable, which is the single thing this task
  exists to fix.
- **Feature-first with a reserved logic suffix** — chosen. Features carry the directory
  structure; the reserved suffix carries the coverage tier. A new slice inherits both tiers by
  naming, with no per-feature list to maintain and nothing for a later session to forget.

### One honest wrinkle: is `AccountCheckService` really "logic"?

It carries `@Service` and `@Transactional`, which are framework annotations, so the "no Spring"
claim needs qualifying. The property that actually matters — and the one the rule is written
against — is that **a plain JUnit test can exercise it**, which `AccountCheckServiceTest` does:
it constructs the class with `new`, passes two hand-written fakes, and asserts behaviour with no
context, no container and no database. The annotations are declarative metadata the class does
not read. The rule is stated in CLAUDE.md in those terms rather than as "framework-free", so it
is testable rather than aspirational.

### The JaCoCo pattern form, proven rather than assumed

S1-08 has to write a `<element>PACKAGE</element>` rule, and the include-pattern form is not
documented unambiguously. Rather than leave that to be discovered, it was tested here with a
throwaway `pom.xml` edit (**reverted; not committed** — `git diff --stat pom.xml` is empty).

Method: set the per-package minimum to an impossible `1.01` and see whether the build fails. A
matching pattern produces a violation naming each package; a non-matching pattern produces
silence.

**Slash form** — `sd/gov/bank/fruserupdate/*/domain`:

```
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

Silence. The pattern matched nothing, and a rule that matches nothing passes. This is the
dangerous outcome: a per-package rule written this way would look enforced and enforce nothing.

**Dot form** — `sd.gov.bank.fruserupdate.*.domain`:

```
[WARNING] Rule violated for package sd.gov.bank.fruserupdate.audit.domain: given minimum ratio is 1.01, but must be between 0.0 and 1.0
[WARNING] Rule violated for package sd.gov.bank.fruserupdate.accountcheck.domain: given minimum ratio is 1.01, but must be between 0.0 and 1.0
[WARNING] Rule violated for package sd.gov.bank.fruserupdate.accountcheck.service: given minimum ratio is 1.01, but must be between 0.0 and 1.0
[WARNING] Rule violated for package sd.gov.bank.fruserupdate.corebanking.domain: given minimum ratio is 1.01, but must be between 0.0 and 1.0
[INFO] BUILD FAILURE
```

All four logic packages matched. Re-run at a realistic `0.90`:

```
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

**S1-08 must use the dot-separated form.** Recorded in CLAUDE.md and in S1-08's note so it
cannot be lost.

---

## 4. The slice

### `CoreBankingClient` — the port

```java
int processOmniCheckAct(String branchCode, String accountNumber);
```

It returns the **raw `int`** on purpose. That is the stored procedure's actual contract, and
S3-02's `SimpleJdbcCall` implementation has to satisfy this signature without inventing a
translation layer. Journey meaning is applied exactly one layer up. The javadoc records that the
procedure name spelling is still unverified against the real database (S1-04, blocked on
OQ-006).

### The stub, selected by configuration

`CoreBankingClientConfiguration` has one `@Bean` factory that switches on
`fru.core-banking.client`; S3-02 adds one `case "oracle" ->` beside it. **There is no `if (mock)`
anywhere in the call path** — `AccountCheckService` holds a `CoreBankingClient` and cannot tell
which one it holds, and neither implementation knows the other exists. Selection has to happen
somewhere, and one startup factory keeps it in a single readable place.

**There is deliberately no default.** `application.properties` does not set the property. A
backend that silently answered a real customer's account check from a stub would be a serious
defect and an invisible one, so anything not recognised — missing, empty, or a typo like `stubb`
— fails at startup naming the property, what was found, and what is accepted:

```
fru.core-banking.client is 'stubb'; accepted values are [stub]. There is no default: a backend
running a real account check against a stub must fail loudly, not quietly. Set it to 'stub' for
local development and testing, or to the real implementation's name in any environment that must
reach the bank's core banking system.
```

The first version of this guard was a `@ConditionalOnProperty` sentinel bean that caught only the
*missing* case, not a typo — the likelier mistake. Review caught it; see §8 SF5. The guard fired
for real during this session either way — see §7.

**How a caller chooses which accounts return which outcome.** The stub is a lookup table
supplied entirely by configuration, keyed `<branch>-<accountNumber>` and valued with the raw
result code. Add an entry valued `1` for an active account, `2` for an inactive one. **Anything
absent from the table returns `-1`**, which is what a real core banking system does with an
account it has never heard of — so the default is the truthful one, and the third outcome is
always reachable without seeding anything.

```properties
fru.core-banking.stub.accounts[16-0000000001]=1
fru.core-banking.stub.accounts[16-0000000002]=2
```

Both account numbers are synthetic. Branch `16` is a real branch **code** from the S2-03 seeded
reference list (الخرطوم) — reference data, not customer data.

### The endpoint

`POST /api/v1/account-check`, body `{ "branch": …, "accountNumber": … }`, response
`{ "outcome": "ACTIVE|INACTIVE|INVALID", "continuation": "PROCEED|TERMINAL|RETRY", "requestId": … }`.

- **Always `200` when the check ran.** Invalid and inactive are business outcomes of the
  journey, not transport failures. A `404` for an unknown account would make the app infer from
  a status code what the body already states, and would collide with a genuinely missing route.
- **The raw result code is never returned.** It goes to the audit trail, where an investigation
  reads it, and nowhere else.
- **`continuation` is journey semantics, not a screen.** The task's constraint — the endpoint
  reports the outcome and does not decide the next screen — is honoured by naming the three
  permissions the journey defines (retryable / terminal / proceed) and leaving the mapping to a
  screen entirely on the app.
- **`requestId` is echoed** so support can find the exact audit row from what the customer was
  shown.
- **`400` for input that never reaches the core, and that attempt is not audited.** The journey
  requires *account-check attempts* recorded; nothing was attempted against the core banking
  system, so there is no attempt to record. Asserted both ways in the tests. The rejected set is
  blank/missing, anything over 64 characters, and anything containing a control character — the
  last two added on review (§8 SF2/SF3/SF4): this endpoint is unauthenticated and writes verbatim
  into a table that is never deleted from, on one serialised chain.
- **No session, no profile, nothing written to schema `app`.**

### The audit write

`event_type = 'account_check_attempted'`, `actor_kind = 'customer'`, `actor_id`, `profile_id` and
`session_id` all `NULL`, `request_id` set, and a canonical-JSON payload carrying exactly the four
values the journey's audit section names:

```json
{"accountNumber":"0000000002","branch":"16","outcome":"INACTIVE","resultCode":2}
```

**Every attempt is recorded, including the ones that fail.** A result code outside the 1/2/-1
contract is audited with the raw code and `"outcome":"UNMAPPED"` before the failure propagates; a
call that throws is audited with `"resultCode":null` and `"outcome":"CALL_FAILED"`. Neither is
mapped to one of the three — the attempt is on the record, but we do not guess what it meant. The
first draft of this class did neither, and had a test asserting that as correct; see §8 SF1.

`profile_id IS NULL` is not a compromise — V0002's own column comment says
`-- NULL for account-check attempts that create no profile; no FK`. The schema anticipated this
case two sprints ago.

Two constraints in the existing schema shaped the implementation:

1. **`fru_app` cannot create an audit chain.** V0004 grants it `SELECT` only on `audit_chain`,
   so the chain has to pre-exist. **V0034** seeds one `('system', 'account_check')` chain. Its
   `subject_id` is deliberately non-NULL: `UNIQUE (chain_kind, subject_id)` does not deduplicate
   NULLs in PostgreSQL, so a NULL-subject system chain could be seeded twice by accident and the
   application would then append to an arbitrary one of them. `head_hash` is passed as an
   explicit `NULL` and filled by the `audit_chain_genesis` BEFORE trigger, which runs ahead of
   the NOT NULL check.

   **On R-035:** V0034 touches schema `audit` but issues **DML, not DDL**.
   `audit.block_audit_ddl()` is registered on `ddl_command_end` and `sql_drop`, neither of which
   an `INSERT` fires, so `SET LOCAL fru.migration_in_progress = 'on'` is not required here — and
   adding it anyway would have taught the next reader the wrong rule. The migration says so in a
   comment rather than leaving it to be re-derived.

2. **Five columns are NOT NULL with no defaults and are owned by `audit.chain_append()`**
   (`seq`, `occurred_at`, `prev_hash`, `content_hash`, `row_hash`). The writer passes
   placeholders the trigger overwrites. Adding column defaults in a migration was considered and
   **rejected**: that would be DDL on schema `audit` — precisely R-035's trap — bought purely for
   the convenience of one class that is already the single writer. The placeholders live in
   `JdbcAuditEventWriter` and nowhere else, with the reason in a comment.

   The chain is resolved inside the statement (`INSERT … SELECT … FROM audit.audit_chain WHERE
   chain_kind = ? AND subject_id = ?`) rather than by a prior lookup, so a missing chain inserts
   zero rows — and an update count other than 1 **throws**. Combined with `@Transactional` on the
   service, an account check that cannot be audited is rolled back rather than answered. There is
   no path that returns an outcome without a recorded event.

`CanonicalJson` implements the RFC 8785 subset that `payload_json`'s column comment requires:
flat objects, keys sorted by UTF-16 code unit, no insignificant whitespace, JSON string escaping,
`String`/integral/`Boolean`/`null` values only. It exists rather than a general JSON serializer
because canonical form is what the hash chain covers — two runs recording the same facts must
produce byte-identical text, and a serializer whose key order follows map iteration order gives
no such guarantee. Floating-point values and nested structures are **rejected** rather than
serialized approximately: RFC 8785's number rule is ECMAScript `Number::toString`, which is not
worth implementing before an audit payload needs it. Arabic is emitted unescaped, because RFC 8785
output is UTF-8 and escaping it would be valid JSON but a different byte sequence, and therefore
a different hash.

### Deliberately not built, stated rather than dropped

- **The ACTIVE outcome's profile-existence branch.** Stage 1a continues past `1`: no profile →
  1b; a complete profile → terminal "already completed"; an incomplete profile → 1b plus full
  OTP and the device-less review flow. That needs profile persistence and the resume flow,
  neither of which exists. Filed as **BL-006**, deliberately not stubbed — the campaign allows
  exactly one update per account, so a half-implemented terminal state is worse than an absent
  one, because a wrong answer there lets an account be updated twice.
- **The `omni_check_request` / `omni_check_response` audit artifacts.** Those kinds exist in
  V0002 and the journey asks for the raw request and response preserved. Against a stub the "raw
  response" is an `int` we invented; preserving it proves nothing. Assigned to S3-02 in its row.
- **Any Oracle integration.** CLAUDE.md's `@agent-researcher`-before-integration rule is *not*
  triggered by this task: there is no Oracle driver, no `SimpleJdbcCall`, no protocol — only an
  interface stating a contract the plan files already record. S3-02 is where that rule bites, and
  S3-02 is additionally gated on S1-04/OQ-006, since the `ojdbc` artifact cannot even be chosen
  until the bank's Oracle server version is known. Both facts are in the S3-02 row.

---

## 5. Tests

Nine test classes, 85 tests (76 under `verify`, plus the two Testcontainers classes). No gate was weakened and no threshold was touched; `pom.xml` is
byte-identical to its committed state.

| Class | What it holds down |
|---|---|
| `AccountCheckOutcomeTest` (10) | All three codes → outcome and continuation; six unrecognised codes each rejected with the code named; a round-trip guard that would catch a future outcome added with a colliding code |
| `AccountCheckServiceTest` (13) | Both fakes hand-written, no context. All three outcomes; both fields forwarded unchanged; the full audited payload; nulls on profile/session/actor for an invalid attempt; the target chain; that the `requestId` returned is the one audited; that an unrecognised code **is** audited as `UNMAPPED` before it throws; that a thrown call is audited as `CALL_FAILED`; that an audit failure propagates rather than returning an unaudited outcome |
| `AccountCheckControllerTest` (11) | `@WebMvcTest`. The wire shape for all three outcomes; that inactive is `200` and not an error status; that invalid is `200` and not `404`; that `resultCode` is absent from the body; that blank, missing, control-character and over-length input are `400` **and never reach the service**; that a field exactly at the cap is accepted; whitespace trimming |
| `StubCoreBankingClientTest` (6) | Seeded `1`, seeded `2`, unseeded `-1`; that the key is `(branch, accountNumber)` and not the account number alone; empty and null seed maps |
| `CoreBankingClientConfigurationTest` (11) | Selecting `stub` builds the stub from its seed; whitespace in the selector is tolerated; **eight rejection inputs** — null, empty, whitespace, `stubb`, `orcale`, `oracle`, `STUB`, `true` — each failing with a message naming the property, quoting what it found, and listing what is accepted; the property name matches what `application.properties` documents |
| `CanonicalJsonTest` (19) | Byte-for-byte output. Sorting independent of insertion order; UTF-16 code-unit (not locale) ordering; empty object; quote/backslash escapes; the five shorthand control escapes; `\u00xx` for other control characters; keys escaped as well as values; Arabic unescaped; a well-formed surrogate pair; every supported scalar type; `null`; and the rejections — a `Double`, a nested `Map`, a `List`, a null key, U+0000, unpaired surrogates at both ends of a string, and an integral value beyond ±2^53 (with the boundary itself accepted) |
| `JdbcAuditEventWriterTest` (5) | Mocked `JdbcTemplate`. Each of the nine bound parameters by position; that uuids bind as text against explicit `::uuid` casts; that the trigger-owned columns are literals in the SQL and never parameters; **the whole statement pinned verbatim**, so a column reordering cannot pass silently (§8 N6); that an update count of 0 throws naming the event, the chain and V0034 |
| `AccountCheckIntegrationTest` (4) — `@Tag("integration")` | Real endpoint → real service → configuration-selected stub → real JDBC writer → real PostgreSQL 18 with all 34 migrations. All three outcomes with their audit rows read back as `fru_app`; **that `audit.verify_chain()` reports `ok = true` afterwards**; that a blank account number is rejected and writes nothing; that the seeded system chain exists exactly once |
| `AppSchemaConnectivityIntegrationTest` (5) — pre-existing | Unchanged except for one added property (§7) |

Two choices worth defending:

**`JdbcAuditEventWriter` is unit-tested against a mocked `JdbcTemplate`, not only through the
integration test.** The integration test is `@Tag("integration")` and excluded from
`./mvnw verify`, so without the unit test the writer's lines would sit uncovered in the coverage
bundle and the 80% gate would be measuring less than it appears to. This is the R-009 failure
mode in miniature, inside the one tier where R-009 does not apply.

**The integration test asserts `audit.verify_chain()`, not just a row count.** Counting rows
would pass even if the application had supplied its own hashes and `chain_append()` had never
run. Recomputing the chain proves the events are genuinely inside it. The test comments record
what this does *not* prove: `verify_chain()` is the accidental-corruption check, not the
integrity check against a determined owner — that is `seal_verify()`, per R-036.

---

## 6. Gates, verbatim

### 6.1 `sh ./mvnw verify` — the standard backend gate

```
[INFO] Running sd.gov.bank.fruserupdate.BackendApplicationTests
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.567 s -- in sd.gov.bank.fruserupdate.BackendApplicationTests
[INFO] Running sd.gov.bank.fruserupdate.corebanking.config.CoreBankingClientConfigurationTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.098 s -- in sd.gov.bank.fruserupdate.corebanking.config.CoreBankingClientConfigurationTest
[INFO] Running sd.gov.bank.fruserupdate.corebanking.stub.StubCoreBankingClientTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.018 s -- in sd.gov.bank.fruserupdate.corebanking.stub.StubCoreBankingClientTest
[INFO] Running sd.gov.bank.fruserupdate.audit.jdbc.JdbcAuditEventWriterTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.040 s -- in sd.gov.bank.fruserupdate.audit.jdbc.JdbcAuditEventWriterTest
[INFO] Running sd.gov.bank.fruserupdate.audit.domain.CanonicalJsonTest
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.040 s -- in sd.gov.bank.fruserupdate.audit.domain.CanonicalJsonTest
[INFO] Running sd.gov.bank.fruserupdate.accountcheck.web.AccountCheckControllerTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.719 s -- in sd.gov.bank.fruserupdate.accountcheck.web.AccountCheckControllerTest
[INFO] Running sd.gov.bank.fruserupdate.accountcheck.service.AccountCheckServiceTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.020 s -- in sd.gov.bank.fruserupdate.accountcheck.service.AccountCheckServiceTest
[INFO] Running sd.gov.bank.fruserupdate.accountcheck.domain.AccountCheckOutcomeTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.018 s -- in sd.gov.bank.fruserupdate.accountcheck.domain.AccountCheckOutcomeTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 76, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO]
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file /home/user/Fr_user_update/backend/target/jacoco.exec
[INFO] Analyzed bundle 'backend' with 13 classes
[INFO]
[INFO] --- jar:3.5.0:jar (default-jar) @ backend ---
[INFO] Building jar: /home/user/Fr_user_update/backend/target/backend-0.0.1-SNAPSHOT.jar
[INFO]
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ backend ---
[INFO] Replacing main artifact /home/user/Fr_user_update/backend/target/backend-0.0.1-SNAPSHOT.jar with repackaged archive, adding nested dependencies in BOOT-INF/.
[INFO] The original artifact has been renamed to /home/user/Fr_user_update/backend/target/backend-0.0.1-SNAPSHOT.jar.original
[INFO]
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 26 files clean - 0 needs changes to be clean, 0 were already clean, 26 were skipped because caching determined they were already clean
[INFO]
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file /home/user/Fr_user_update/backend/target/jacoco.exec
[INFO] Analyzed bundle 'backend' with 13 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  7.726 s
[INFO] Finished at: 2026-08-28T08:04:20Z
[INFO] ------------------------------------------------------------------------
```

### 6.2 The JaCoCo bundle — the number this session exists to change

**`Analyzed bundle 'backend' with 13 classes`.** Every session before this one reported
`with 0 classes` (confirmed on the clean tree at the start of this session), which is why S1-07
had to prove the gate could fail at all — it had never measured anything.

15 new classes were written; JaCoCo reports 13 because `CoreBankingClient` and `AuditEventWriter`
are interfaces with no executable lines, and `BackendApplication` is excluded by the existing
`pom.xml` configuration.

Coverage, computed from `target/site/jacoco/jacoco.csv`:

```
LINE   covered=156 missed=0 ratio=1.0000
BRANCH covered=72  missed=0
METHOD covered=33  missed=0
```

| Package | LINE |
|---|---|
| `sd.gov.bank.fruserupdate.accountcheck.domain` | 100.00% (18/18) |
| `sd.gov.bank.fruserupdate.accountcheck.service` | 100.00% (26/26) |
| `sd.gov.bank.fruserupdate.accountcheck.web` | 100.00% (19/19) |
| `sd.gov.bank.fruserupdate.audit.domain` | 100.00% (55/55) |
| `sd.gov.bank.fruserupdate.audit.jdbc` | 100.00% (22/22) |
| `sd.gov.bank.fruserupdate.corebanking.config` | 100.00% (9/9) |
| `sd.gov.bank.fruserupdate.corebanking.stub` | 100.00% (7/7) |

**100% line, branch and method coverage against an 80% gate**, after the review fixes as well as
before them. Stated plainly: this is a small, new, deliberately-testable body of code, and 100%
here is not evidence that the gate is well-calibrated for what comes next. It does mean the 90%
business-logic tier would pass today with no work, and that nothing was added that tests do not
reach.

Two branches in `CanonicalJson` were briefly uncovered after the review fixes — a high surrogate
at the very end of a string and a low surrogate at index 0, the two positions a look-ahead and a
look-behind walk off. A test was added rather than the gap left, which is the whole point of
having the branch counter visible.

### 6.3 `sh ./mvnw test -Pdb-integration-test`

Every pre-existing test still passes alongside the new ones:

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.936 s -- in sd.gov.bank.fruserupdate.BackendApplicationTests
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.111 s -- in sd.gov.bank.fruserupdate.corebanking.config.CoreBankingClientConfigurationTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.018 s -- in sd.gov.bank.fruserupdate.corebanking.stub.StubCoreBankingClientTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.055 s -- in sd.gov.bank.fruserupdate.audit.jdbc.JdbcAuditEventWriterTest
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.051 s -- in sd.gov.bank.fruserupdate.audit.domain.CanonicalJsonTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.066 s -- in sd.gov.bank.fruserupdate.AppSchemaConnectivityIntegrationTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.561 s -- in sd.gov.bank.fruserupdate.accountcheck.web.AccountCheckControllerTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.346 s -- in sd.gov.bank.fruserupdate.accountcheck.AccountCheckIntegrationTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.017 s -- in sd.gov.bank.fruserupdate.accountcheck.service.AccountCheckServiceTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.013 s -- in sd.gov.bank.fruserupdate.accountcheck.domain.AccountCheckOutcomeTest
[INFO] Tests run: 85, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

The five `AppSchemaConnectivityIntegrationTest` assertions — the app/audit schema check, the
`ref` seed row counts, the provenance-matrix versions, the status-transition guard including its
live rejection of an illegal `in_progress → approved` UPDATE, and the audit-seal wiring — all
still pass against a database that now also carries V0034.

### 6.4 backoffice — `npm run test`, `test:coverage`, `lint`, `build` (Node 24.20.0)

```
> backoffice@0.0.0 test
> vitest run

 RUN  v4.1.11 /home/user/Fr_user_update/backoffice

 Test Files  1 passed (1)
      Tests  1 passed (1)

 % Coverage report from v8
----------|---------|----------|---------|---------|-------------------
File      | % Stmts | % Branch | % Funcs | % Lines | Uncovered Line #s
----------|---------|----------|---------|---------|-------------------
----------|---------|----------|---------|---------|-------------------

=============================== Coverage summary ===============================
Statements   : 100% ( 1/1 )
Branches     : 100% ( 0/0 )
Functions    : 100% ( 1/1 )
Lines        : 100% ( 1/1 )
================================================================================

> backoffice@0.0.0 lint
> oxlint

> backoffice@0.0.0 build
> tsc -b && vite build

✓ 1486 modules transformed.
dist/index.html                   0.46 kB │ gzip:  0.29 kB
dist/assets/index-DGNrK5qb.css    1.78 kB │ gzip:  0.81 kB
dist/assets/index-Bx39NJdG.js   289.43 kB │ gzip: 97.60 kB
✓ built in 536ms
```

Unchanged by this session. `1/1` statements is R-009 in plain sight: the tier has no feature
code, so "100%" measures one line.

### 6.5 mobile — `flutter test`, `dart run tool/check_coverage.dart`, `flutter analyze` (Flutter 3.47.0)

```
00:00 +0: loading /home/user/Fr_user_update/mobile/test/widget_test.dart
00:00 +0: Counter increments smoke test
00:00 +1: All tests passed!

Line coverage: 92.31% (24/26 lines), threshold 80%
PASSED: coverage meets the 80% threshold.

Analyzing mobile...
No issues found! (ran in 6.7s)
```

Unchanged by this session. Run as regression only. `git status` after the mobile run confirms no
generated file (`pubspec.lock`, `.dart_tool/`) was modified.

---

## 7. What failed along the way

**1. `BackendApplicationTests` stopped loading its context.** The smoke test excludes
`DataSourceAutoConfiguration` on purpose, so that `./mvnw test` never needs Docker — which means
no `JdbcTemplate` exists, which means `JdbcAuditEventWriter` cannot be constructed. Fixed by
giving the test a nested `@TestConfiguration` supplying a mocked `JdbcTemplate`. That keeps the
test's whole point intact (a wiring check that must not need Docker) while making it prove the
new beans wire, and leaves what the writer actually *does* to the integration test, where it
belongs.

**2. `AppSchemaConnectivityIntegrationTest` stopped loading its context** — the deliberate
no-default guard firing correctly, on a pre-existing test that boots the full application and
had no reason to know about core banking:

```
Error creating bean with name 'coreBankingClientSelectionRequired' … Factory method
'coreBankingClientSelectionRequired' threw exception with message: fru.core-banking.client is
not set. …
```

Fixed by that test declaring its choice (`fru.core-banking.client=stub`), which is the rule
working as designed rather than an exception to it: **every context states its choice or fails
at startup.** This is also the strongest available evidence that the guard is not decorative —
it caught a real context within minutes of existing.

**3. Jackson 2 imports did not compile.** Spring Boot 4.1 ships **Jackson 3**
(`tools.jackson.databind`, 3.1.4); `com.fasterxml.jackson.databind` is not on the classpath
except for `jackson-annotations` 2.21. The integration test uses
`JsonMapper.builder().build()`.

**4. Spotless rejected two files twice**, both times for line length inside comments I had
rewritten by hand. `spotless:apply` fixed the first round; the second round it re-wrapped a
comment into something less readable, so that comment was reworded rather than left as the
formatter's output.

**5. Docker was not running, and then could not pull.** Both covered in §1. Neither is a defect
in this repository, but both would stop the next session here dead, so they are recorded rather
than quietly worked around.

Nothing else failed. No test was skipped, disabled or weakened; no threshold was changed.

---

## 7b. Live proof of the three schema assumptions V0034 and the writer rest on

The integration test proves the slice works. These `psql` runs prove *why* it is built the way
it is, against a database in the realistic deployment state — all 34 migrations applied **and
Layer 3 installed** from `db/post-migrate/01-audit-event-trigger.sql`, which is the state R-035
warns tests never reproduce.

**1. V0034's chain exists once, with a genesis hash the trigger computed** — the query below
recomputes the AD-005 genesis formula independently and gets the same value:

```
 chain_kind |  subject_id   | head_seq |                            head_hash                             |                        recomputed_genesis
------------+---------------+----------+------------------------------------------------------------------+------------------------------------------------------------------
 system     | account_check |        0 | ce1ad45d531cda4b83c850661c5a7a7b4845f9bfba78ce3afba4547184131bf0 | ce1ad45d531cda4b83c850661c5a7a7b4845f9bfba78ce3afba4547184131bf0
```

**2. Layer 3 really is active in this database** — an unguarded `CREATE TABLE audit.r035_probe`
as `fru_migrator`:

```
ERROR:  DDL against schema audit requires a migration session
CONTEXT:  PL/pgSQL function audit.block_audit_ddl() line 17 at RAISE
```

**3. …and V0034-style DML on the same schema needs no flag**, which is the claim the migration's
comment makes. Same session, same active Layer 3:

```
INSERT 0 1
 subject_id | head_seq
------------+----------
 r038_probe |        0
```

This is the R-035 check performed rather than asserted: V0034 is safe without
`SET LOCAL fru.migration_in_progress = 'on'` because it issues no DDL, and that has now been
tested against a Layer-3-active database rather than a virgin one.

**4. `fru_app` cannot create a chain**, which is *why* V0034 has to seed one:

```
ERROR:  permission denied for table audit_chain
```

**5. The writer's exact statement, run as `fru_app`** — note what the trigger did with the
placeholders: `seq` went in as `0` and came out as `1`, and `row_hash` went in as an empty
`bytea` and came out as 32 bytes.

```
INSERT 0 1
 seq |       event_type        | profile_id | session_id | outcome | row_hash_bytes
-----+-------------------------+------------+------------+---------+----------------
   1 | account_check_attempted |            |            | INVALID |             32
```

**6. The event is inside the hash chain**, not merely an inserted row:

```
 ok | checked | first_bad_seq | reason
----+---------+---------------+--------
 t  |       1 |               | ok
```

**7. And it is immutable to the role that wrote it:**

```
ERROR:  permission denied for table audit_event
```

(Denied at Layer 1 — `fru_app` holds INSERT+SELECT only — before the Layer-2 trigger is reached.
Both layers are checked in the S2-01 report; this confirms the first still holds for the first
application-written event.)

---

## 8. `@agent-reviewer` — findings and dispositions

**Substitution, stated plainly:** there is no `reviewer` agent definition in this environment.
`.claude/agents/` does not exist here, and the available agent types are `claude`, `Explore`,
`general-purpose`, `Plan`, `claude-code-guide` and `statusline-setup`. The review was run as a
`general-purpose` agent given an explicit reviewer brief: the full staged diff, the task statement
including its OUT OF SCOPE list, a required reading list (CLAUDE.md, the journey's Stage 1 and
audit sections, the persistence card, V0002–V0004, and R-028/R-035/R-036/R-038), and seven
prioritised areas to attack. It was told to report only, not to edit.

**Verdict: no BLOCKERs.** Six SHOULD FIX and ten NOTEs. Every one is listed below with what was
done about it. All fixes were re-verified — the gate output in §6 was re-run after them and is the
post-fix state.

### SHOULD FIX

**SF1 — an attempt that reached the core but returned an unexpected code was never audited.
FIXED.** `fromResultCode` threw before any event was constructed, and a test *asserted that as
correct*. The journey requires account-check **attempts** recorded, and an anomalous result is
exactly what an investigation reads. Unreachable with the shipped stub, live the moment S3-02
lands — and by then a green test would have been defending the gap.

The fix has a wrinkle the reviewer identified: `@Transactional` would roll back the very event
being written before the throw. Rather than reach for `REQUIRES_NEW` (which self-invocation would
have silently bypassed anyway), **`@Transactional` was removed**. It bought nothing here — the
audit write is a single INSERT, so autocommit already gives the only atomicity available: it
commits or it throws, and a caller never gets an unaudited answer either way. `AccountCheckService`
now audits every attempt: `outcome: "UNMAPPED"` with the raw code for an out-of-contract result,
and `outcome: "CALL_FAILED"` with a null result code when the call itself throws (whether it
reached the core is unknowable from here, which is itself worth recording). The old test is
inverted, and two more added. Removing `@Transactional` also dissolves N2's tension.

**SF2 — `required()` validated before trimming, so control-character input became an empty account
number. FIXED.** `String.isBlank()` is false for U+0000–U+0008 and U+000E–U+001B; `trim()` strips
everything at or below U+0020. So an account number of a single U+0001 returned 200 and audited an
empty account number. Now trims first, then checks, with the reasoning in the javadoc so the order
is not "tidied" back later. Test added.

**SF3 — an interior U+0000 would 500 with nothing recorded. FIXED, in two places.**
`audit.audit_event.payload` is a generated `payload_json::jsonb` column and PostgreSQL rejects a
U+0000 escape in that cast (SQLSTATE 22P05). Trimming does not help — the NUL is interior. The
controller now rejects control characters outright (a plain 400 instead of an obscure 500), and
`CanonicalJson` rejects U+0000 independently, because "this string can never be audited" is a
property of the canonicaliser, not of one endpoint. Tests at both levels.

**SF4 — no input constraints, and the settled rate-limit policy has no field to key on.
SPLIT.** The bounds are in: `MAX_FIELD_LENGTH` of 64 and the control-character rejection above,
so an unauthenticated request cannot write an arbitrarily large value into an append-only table on
a single serialised chain (R-038). The reviewer's sharper point — that customer.md:1201 settles
"10 per install per hour" while the wire contract carries no install identifier, making the
settled policy *unimplementable* — is **not** fixed here. Adding a field to a wire contract the
mobile tier will consume is a contract decision, not a detail of the first slice to touch it.
Filed as **BL-007** with the reviewer's reasoning intact, as they asked.

**SF5 — the startup guard covered a missing property, not a wrong one. FIXED, by deleting the
clever bit.** The sentinel-`havingValue` guard bean caught an absent property but not `stubb`,
`orcale` or an empty value — the likelier mistake — which fell through to "no qualifying bean of
type CoreBankingClient", the exact error the guard's own javadoc mocked. Replaced with a single
`@Bean` factory whose package-private `select(String, StubCoreBankingProperties)` switches on the
value and throws for anything unrecognised, naming the property, what it found, and the accepted
set. Missing, empty, whitespace and typos now all produce one legible message.

This is still not the forbidden `if (mock)`: that rule is about the call path, and nothing serving
a request can tell which implementation it holds. Selection has to happen somewhere; one startup
factory is a more honest place for it than a pair of conditional annotations. Test coverage went
from 3 cases to 11, including every rejection input.

**SF6 — `docs/components/persistence.md` was stale in ways that matter. FIXED.** Its header said
"researched, not implemented"; line ~86 said "this codebase has no service layer". Both false as
of this commit. More importantly the card is the reference the *next* audit writer will read, and
it documented none of this. It now carries a **"How application code writes an audit event"**
section: the two trigger-owned/grant-driven constraints, a table of chains seeded for application
use, why the writer resolves its chain inside the statement, what `CanonicalJson` is for and what
it rejects, and the DML-vs-DDL rule for schema `audit` with V0034 as the worked example.

The reviewer also caught that `docs/journeys/customer.md` Stage 1a still read "Nothing is
persisted at this stage", which the audit event contradicts. Corrected to distinguish the profile
database (nothing) from the audit trail (the attempt, precisely because no profile is created) —
reconciling an internal contradiction in the spec rather than changing the journey.

### NOTES

**N1 — CLAUDE.md stated a rule the code does not follow. FIXED.** "Ports live in the logic package
that calls them" is wrong: both ports live in their own integration package, and the caller is
`accountcheck.service`. A later session following it literally would have moved both interfaces.
Reworded to "a port is an interface and belongs in a `domain`/`service` package — never in its
adapter's package".

**N2 — `@Service`/`@Transactional` versus the "no Spring context" claim. FIXED both ways.**
`@Transactional` is gone (SF1), so the class no longer makes any claim about a database. CLAUDE.md
now says explicitly that Spring stereotype annotations are permitted and that the real property is
"needs no container, database, socket or real clock to test".

**N3 — two more properties of the JaCoCo matcher. ADDED.** The reviewer independently confirmed
the dot-form finding by reading `BundleChecker`/`JavaNames` bytecode, and added that `*` becomes
regex `.*` (so it spans package separators and matches deeper nestings) and that `.` in the
pattern is regex "any character". Both are now in CLAUDE.md's note, where S1-08 will read them.

**N4 — dangling session-report link. RESOLVED** — this file.

**N5 — `CanonicalJson` correct for its subset, with three edges. ALL THREE NOW REJECTED.** The
reviewer verified the sorting and escaping line by line against RFC 8785, and checked that the
`%04x` formatting is locale-independent under `ar-SA-u-nu-arab`, `hi-IN-u-nu-deva`,
`fa-IR-u-nu-arabext` and `tr` (a comment now says so, so nobody "fixes" it into a locale-aware
formatter). The three edges — U+0000, unpaired surrogates, and integral values beyond ±2^53 where
RFC 8785's ECMAScript number rule and an exact decimal diverge — now throw with reasons, because
each would otherwise leave a permanent, unfixable record in an append-only chain. The unpaired
surrogate is the worst of the three: appended raw it becomes `?` at UTF-8 encoding, so the chain
stays self-consistent while the record silently differs from what was received. Nested `Map`/`List`
rejection is now tested too. `CanonicalJsonTest` went from 12 tests to 19.

**N6 — the writer's unit test asserted SQL *text*, not SQL *behaviour*. FIXED as far as it can
be.** `contains(...)` fragments would survive a column reordering that no longer lines up with the
argument array, and the only thing proving that correspondence against the real schema is the
`@Tag("integration")` test excluded from `verify` — so the default gate could go green over a
writer binding to the wrong columns. The whole statement is now pinned verbatim in a golden
assertion, so any reordering fails loudly and must be a deliberate edit to both sides. The
underlying point stands and is not fully fixable here: it is R-035's shape one layer up, and it
closes properly only when the tagged tests run somewhere (R-010, no CI exists).

**N7 — vacuous `assertNotNull` after three `contains` on the same message. REMOVED.**

**N8 — `PROCEED` is a published contract that is knowingly incomplete. DOCUMENTED.** Every ACTIVE
account is answered `PROCEED` today, but BL-006's profile-existence branch has a terminal arm, so
this enum will gain a value or `PROCEED` will narrow — a wire change. Now stated on
`AccountCheckResponse` so mobile work built against it in the meantime knows.

**N9 — no component card for core banking. CREATED.** `docs/components/core-banking.md`, marked
**"stub only — nothing here is verified"**, with a table separating assumed from known: the
procedure name spelling, the 1/2/-1 contract, parameter directions, null behaviour and the Oracle
version (OQ-006) are all [UNVERIFIED]. It records the reviewer's sharpest catch here — **an Oracle
`NUMBER` OUT parameter is nullable and the port's `int` return cannot express that**, cheap to
change while there is one caller — and states that CLAUDE.md's researcher rule bites at S3-02, not
at S3-01.

**N10 — stub seed lives in the production `application.properties`. NOT CHANGED, deliberately.**
The reviewer is right that a deployed artifact carrying stub fixtures is untidy. It is inert —
`StubCoreBankingClient` is never constructed unless the selector explicitly says `stub` — and if
someone selected the stub in production, the seed is not the problem. Against that, moving it to
an `application-local.properties` makes the one thing a new developer needs (working example
accounts) less discoverable, and the block is heavily commented where it is. Recorded as a
disagreement rather than silently ignored.

### What the reviewer checked and found correct

Worth recording, because these are the places this task could most easily have been silently
wrong: V0034 genuinely does not need the R-035 flag; the NULL-subject uniqueness trap is real and
correctly avoided; `head_hash NULL` works because BEFORE ROW triggers precede constraint
evaluation; the writer's INSERT is well-formed against the real schema and matches `fru_app`'s
grants exactly and no more; `INSERT … SELECT` is the right shape for the missing-chain guard, with
no `SELECT`-then-`INSERT` race; nothing is persisted outside the audit trail; the raw result code
does not leave the backend; the 1/2/-1 mapping fails closed; there is no `if (mock)` anywhere in
the call path; the `domain` packages are genuinely free of Spring, JDBC, I/O and clocks; and no
secrets, real account numbers, hardcoded reference lists or hand-edited generated files were
introduced. The reviewer also ran the gates independently and reproduced them.

### One correction made before the review returned

`AccountCheckService.check`'s javadoc originally claimed the core banking call "is not part of the
transaction", which was loosely worded — it was not *covered* by the transaction but did run while
one was open. That is now moot, since `@Transactional` is gone (SF1), and the javadoc states the
current position: the call cannot be rolled back, which is safe because Stage 1a changes nothing
and the core is read-only to this solution.

---

## 9. Is S1-08 unblocked?

**Partially. The backend half is unblocked; the other two tiers are not, and R-009 is untouched.**

S1-08 has three deliverables. Where each now stands:

| S1-08 deliverable | Status after S3-01 |
|---|---|
| 90% per-package JaCoCo rule (backend) | **Unblocked, and de-risked.** The `domain`/`service` suffix rule gives it a scope, and the include-pattern form is proven live (§3): dot-separated, not slash. Adding the rule is now a `pom.xml` edit with a known-good pattern and a body of code that already passes at 100% |
| 90% per-glob Vitest threshold (backoffice) | **Still blocked.** `backoffice/src` has no feature code and no business-logic directory — its coverage report is literally `1/1` statements (§6.4). There is nothing to scope a glob to |
| Mobile equivalent | **Still blocked.** Same reason: `mobile/lib` is still the Flutter counter template |
| Make all three tiers count source files no test imports (R-009) | **Untouched, in all three tiers.** Nothing in this session addressed it. It is currently harmless in the backend — JaCoCo instruments compiled classes, so it does not have the Flutter/Vitest blind spot — but it is exactly as live for backoffice and mobile as it was |

So S1-08 stays **⚠️ Blocked**, with its note rewritten to record the partial unblocking and the
proven pattern form. Doing the backend third of it now and leaving the row open would make the
row lie about what is enforced. The honest sequencing is: S1-08 runs when the backoffice or
mobile tier gets its first feature code, and does all three tiers plus R-009 at once — or, if the
schedule pushes back, it is split into an explicit backend-only task rather than being
half-done under the existing ID.

One thing worth flagging for whoever runs S1-08: **the backend passing at 100% today is not
evidence the 90% rule is well-calibrated.** This is 122 lines of deliberately-testable new code
with no I/O in the logic packages. The rule's real test is the first slice with an awkward
external dependency, which is S3-02.

---

## 10. What changed, file by file

**New — backend main (15 classes):**

```
accountcheck/domain/AccountCheckOutcome.java, AccountCheckContinuation.java
accountcheck/service/AccountCheckService.java, AccountCheckResult.java
accountcheck/web/AccountCheckController.java, AccountCheckRequest.java, AccountCheckResponse.java
corebanking/domain/CoreBankingClient.java
corebanking/config/CoreBankingClientConfiguration.java
corebanking/stub/StubCoreBankingClient.java, StubCoreBankingProperties.java
audit/domain/AuditEvent.java, AuditEventWriter.java, CanonicalJson.java
audit/jdbc/JdbcAuditEventWriter.java
```

**New — migration:** `V0034__audit_system_account_check_chain.sql`

**New — tests (7 unit classes + 1 integration):** `AccountCheckOutcomeTest`,
`AccountCheckServiceTest`, `AccountCheckControllerTest`, `StubCoreBankingClientTest`,
`CoreBankingClientConfigurationTest`, `CanonicalJsonTest`, `JdbcAuditEventWriterTest`,
`AccountCheckIntegrationTest`

**Modified — backend:**
- `application.properties` — the stub seed, and a commented block explaining why
  `fru.core-banking.client` is deliberately *not* set there
- `BackendApplicationTests` — the client property, and a mock `JdbcTemplate` (§7)
- `AppSchemaConnectivityIntegrationTest` — the client property only (§7)

**New — component card:** `docs/components/core-banking.md` (§8 N9)

**Modified — plan files:**
- `RISKS.md` — R-035, R-036, R-037 (as supplied), R-038 (new)
- `PROJECT_PLAN.md` — AD-002d now owns the seal export destination/schedule and the profile
  database's encryption at rest
- `EXECUTION_PLAN.md` — new Sprint 3 section with S3-01 and S3-02; S1-08's note rewritten
- `BACKLOG.md` — BL-006, Stage 1a's profile-existence branch
- `CLAUDE.md` — the Architecture bullet claiming no feature or business-logic subdirectories
  exist was false as of this commit; replaced with the package-layout rule and the proven JaCoCo
  pattern form (including the two matcher properties from §8 N3). The Coverage section's identical
  claim corrected the same way

**Modified — specification and component docs (both found stale by review, §8 SF6):**
- `docs/components/persistence.md` — header status, the "no service layer" line, and a new
  "How application code writes an audit event" section
- `docs/journeys/customer.md` — Stage 1a's "nothing is persisted" now distinguishes the profile
  database from the audit trail

**Not touched:** `pom.xml` (byte-identical — the per-package experiment was reverted; no gate or
threshold was changed at any point),
`mobile/`, `backoffice/`, `.claude/settings.json`, `../FIB`, and every file under the
generated-file paths CLAUDE.md lists.

---

## 11. Commit and push proof

This section is appended after the work was committed and pushed, so the hashes below are the
ones the previous section's file list describes. It is the same shape S2-04 and S2-10 used.

The session landed in **two commits**, deliberately:

| Commit | What |
|---|---|
| `5bd2036` | `feat: S3-01 backend module structure and the stage 1a account-check slice` — the module structure, the slice, V0034, the tests, and the plan-file updates |
| `37efa72` | `fix: S3-01 review round — audit every attempt, harden input and canonical JSON` — everything §8 records, plus this report's §8 rewritten from placeholder to the real findings |

They are separate because the review had not returned when the first was made, and a commit
claiming review findings it did not have would have been a lie in the history. The split also
makes the review's effect on the code readable as a diff, which is the more useful record.

`git log --oneline -1`:

```
37efa72 fix: S3-01 review round — audit every attempt, harden input and canonical JSON
```

`git status`:

```
On branch claude/s3-01-account-check-slice-38anff
Your branch is up to date with 'origin/claude/s3-01-account-check-slice-38anff'.

nothing to commit, working tree clean
```

Branch tracks `origin/claude/s3-01-account-check-slice-38anff`; tree clean.

**On `.claude/settings.json`:** the task expected it to show as modified and said to report it and
leave it alone. It was **never modified** — the tree was clean at session start (§0), the file was
not touched during the session, and it does not appear in either commit or in the final `git
status` above. There was nothing to leave alone.

---

## Reconciliation, added by S3-03

This section is appended by S3-03 (2026-08-28), which re-reviewed this slice with the real
`reviewer` agent and reconciled the commit proof above against what `main` actually shows. Nothing
in the sections above this one was edited — they are an accurate record of what this session
believed and did on its own machine.

**The commit hashes above do not match `main`.** This report's §11 table names `5bd2036` for the
first commit ("feat: S3-01 backend module structure and the stage 1a account-check slice"). **That
hash does not exist anywhere in this repository.** The commit actually reachable from `main` with
that message is **`eda48fe`**. The second commit, `37efa72` ("fix: S3-01 review round — audit every
attempt, harden input and canonical JSON"), matches exactly. This is the same class of mismatch
this session already flagged for S2-04 in its own §0 — a hash quoted in a report does not survive
whatever process moved the work from the branch it was written on into `main`. Treat the hash in a
session report's own prose as provisional; `git log`/`git show` against `main` is authoritative.

**`main` was fast-forwarded by hand, not pushed to directly.** This session's branch,
`claude/s3-01-account-check-slice-38anff`, still exists as `origin/claude/s3-01-account-check-slice-38anff`.
Its tip is `4baacad` — byte-identical to `main`'s tip at the time S3-03 ran, zero commits of
divergence in either direction. It is fully merged and was left in place rather than deleted (S3-03
did not delete it). The branch existed only because this session ran in a remote/cloud environment
that required one; S3-03 records that this is not a project convention — see CLAUDE.md's session-end
hard rule, restated at S3-03 to say so explicitly.

**Independent re-review outcome.** S3-01's own §8 review was run by a `general-purpose` agent given
a reviewer brief, because no `reviewer` agent definition existed in that session's environment —
disclosed honestly there, but closer to a self-review than the read-only independent review this
project depends on. S3-03 installed the real `.claude/agents/reviewer.md` (identical to the one used
on every other session in this project) and reviewed the combined diff of `eda48fe` and `37efa72`
against this same EXECUTION_PLAN.md task. Result: **no BLOCKERs, three new SHOULD FIX findings the
substitute review missed entirely**, all fixed and re-verified in that session — a real correctness
bug where an audit-write failure on the CALL_FAILED/UNMAPPED paths silently discarded the original
core-banking failure with no `addSuppressed`, plus two javadoc/comment claims left over from the
`@Transactional` removal that promised rollback semantics the code no longer has. Full findings,
fixes, and re-verified gate output are at
docs/sessions/2026-08-28-s3-03-rereview-and-portability.md.

**Is this slice now independently reviewed?** Yes, as of S3-03.
