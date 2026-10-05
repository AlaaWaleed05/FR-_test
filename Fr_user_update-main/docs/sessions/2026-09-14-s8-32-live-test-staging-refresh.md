# S8-32 — staging refreshed for the live test round

Date: 2026-09-14. Tiers: **backend (config only), infra script, plus a build of mobile and back
office with no source change in either.**
Task row: EXECUTION_PLAN.md S8-32. Asked for by the product owner: redeploy the latest backend,
build the latest APK, seed 100 stub accounts, keep everything else real, and leave the service
running independently of the laptop.

## What changed on the running stack

| | Before | After |
|---|---|---|
| Schema version | `0065` | **`0069`** |
| Task definition | `fru-staging-backend:11` | **`fru-staging-backend:12`** |
| Image | `0.0.1-20260910t2054` | **`0.0.1-20260914t0145`** |
| Image digest | — | `sha256:6deb40a91880b425e135822510e6b947ace1c929ad5985f91def76955f6c17fc` |
| Back-office bundle | `index-C4jkaBcS.js` | **`index-DhknbztP.js`** |
| Seeded stub accounts | 32 | **132** (100 of them new and unused) |
| `FRU_MESSAGING_WHATSAPP_ENABLED` | set by hand at S8-08 (rev 10), still present on rev 11, absent from the script | **set by the script itself** |

Every selector is unchanged and is what the request asked for: `FRU_CORE_BANKING_CLIENT=stub`,
`FRU_CIVIL_REGISTRY_CLIENT=http`, `FRU_UQUDO_CLIENT=http`, `FRU_MESSAGING_SMS_PROVIDER=http`.
WhatsApp and email stay `stub` because no real adapter exists for either and
`MessageSenderConfiguration` refuses `http` for them at startup — SMS-only release, PO decision
2026-09-07. Core banking stays stubbed by the ruling of 2026-09-12 (BL-089).

## Repository changes — three files

**`backend/src/main/resources/application.properties`** — 100 accounts, `0000002001`-`0000002100`.

The request said "seed the database"; there is no table to seed. The stub binds from
`fru.core-banking.stub.accounts[...]` into `StubCoreBankingProperties` and is baked into the image,
so a hundred accounts is a properties change plus a rebuild. Stated here because the next person to
want more accounts will otherwise go looking for a migration.

A **fresh** range rather than an extension of `0000001001`-`0000001030`, which is partly spent (the
audit below). Disjointness was checked by grepping the test sources rather than trusting
`AbstractPostgresIntegrationTest`'s inventory, which that class itself warns can lag: every fixture
account is `<= 0000000902`, plus `0000009999`, which stays unseeded as
`AccountCheckIntegrationTest`'s UNKNOWN_ACCOUNT — and it stays unseeded here.

The base file is **not** inert for tests: every `@SpringBootTest` loads it, which is exactly why
seeding `0000009999` would break the not-found proof, as that file's own S8-04 comment says. What
makes this block safe is narrower and worth stating precisely, because the loose version of the
sentence is what stops the next session running the grep: **no test asserts anything about an
account in `0000002001`-`0000002100`**, and tests that need their own stub accounts register them
through `@DynamicPropertySource` on top.

**`infra/aws/08-backend-service.sh`** and **`application-aws.properties`** — both halves of BL-097.
See below; this is the one defect closed by building rather than by investigating.

## The migration, and why it was not optional

Staging sat at `0065` while the image carried `V0066`-`V0069`, all of which landed 2026-09-13. That
is CLAUDE.md's S5-01 finding exactly: the shipped jar connects as `fru_app`, which holds no DDL
rights, so it starts perfectly happily two versions behind and fails per-endpoint with
`permission denied` or `bad SQL grammar`. Had this session deployed first and migrated later, the
symptom would have been a healthy service with broken endpoints.

Migrated as `fru_migrator` over an SSM port-forward, `DB_PORT=55432`. The three passwords came
from Secrets Manager into environment variables and were never written to a file, a log or this
report.

### A checksum mismatch blocked it — filed as BL-144

```
[ERROR] Migration checksum mismatch for migration version 0035
[ERROR] -> Applied to database : -1774721721
[ERROR] -> Resolved locally    : 1892487755
```

`flyway repair` is the documented fix and would have worked immediately. It was **not** run first.
`V0035__app_notification_outbox.sql` was applied to staging at S7-06 on 2026-09-06; commit `506e7fe`
(S8-06, the `.gov` → `com.sfbank.bayanati` rename) modified it on 2026-09-08. Reading that commit's
diff for the file shows one changed line, and it is a **SQL comment**:

```
-  -- Below: one-to-one with sd.gov.bank.fruserupdate.messaging.domain.MessageDispatchResult's
+  -- Below: one-to-one with com.sfbank.bayanati.messaging.domain.MessageDispatchResult's
```

Zero DDL difference, so the database objects and the file still agree and `repair` was correct.
`git log` confirms the rename touched exactly one migration file, and no other applied migration
(`V0001`-`V0065`) has been modified since staging ran them.

**The near miss is the point, not the incident.** A mechanical rename across 456 source files swept
a migration along with them. Had it rewritten a line of DDL instead of a comment, the signal would
have been the same one-line checksum error, and `repair` would have silently recorded agreement
between a file and a database that no longer matched. Nothing in the repository says an applied
migration is immutable, and nothing checks. BL-144 asks for the rule and a gate.

## BL-093 is refuted, and that matters more than anything else here

BL-093 recorded that an interrupted journey *may* permanently burn a customer's account, on one
observation, undiagnosed, with the worst case written down rather than asserted. Its own text named
the first step: read the status and the history for `0000001002`. That needed database access the
filing session did not have. This session had a tunnel open anyway, so it cost two read-only queries.

```
+----------------+-----------+-------------------------------+-------------------------------+-------------------------------+------------+
| account_number |  status   |          created_at           |       last_activity_at        |         submitted_at          | provenance |
+----------------+-----------+-------------------------------+-------------------------------+-------------------------------+------------+
| 0000001002     | submitted | 2026-09-07 18:08:07.333964+00 | 2026-09-07 18:45:46.971644+00 | 2026-09-07 18:46:00.554419+00 | digital    |
+----------------+-----------+-------------------------------+-------------------------------+-------------------------------+------------+

+-----+-------------+-------------+-------------------------------+------------+----------------------+
| seq | from_status |  to_status  |          occurred_at          | actor_kind | is_manual_completion |
+-----+-------------+-------------+-------------------------------+------------+----------------------+
|   1 |             | in_progress | 2026-09-07 18:08:09.477512+00 | customer   | f                    |
|   2 | in_progress | submitted   | 2026-09-07 18:46:00.56888+00  | customer   | f                    |
+-----+-------------+-------------+-------------------------------+------------+----------------------+
```

A complete journey was walked on that account, by a customer actor, 38 minutes after it was created.
`submitted` is one of V0005's four terminal statuses, so the `TERMINAL` answer that the walk read as
a lockout **was correct behaviour**. The account was burned by being used, which is the designed
single-use property, not by the power cut.

What was wrong was the walk's premise, not the code: that session believed the journey had ended at
the Stage 2 OTP screen, and the record says it did not. No customer-facing defect is evidenced by
anything in this row. Nothing was changed to close it — the queries were read-only.

This removes the scariest unknown standing in front of a live test: a tester whose phone dies
mid-journey is not, on this evidence, locked out of their one permitted update.

## The account audit

Every profile that exists on staging, with whether its status is terminal:

```
| 0000000001 | approved    | t | 2026-09-06 |
| 0000000009 | in_progress | f | 2026-09-07 |
| 0000000010 | in_progress | f | 2026-09-07 |
| 0000001001 | in_progress | f | 2026-09-10 |
| 0000001002 | submitted   | t | 2026-09-07 |
| 0000001003 | submitted   | t | 2026-09-07 |
| 0000001004 | submitted   | t | 2026-09-07 |
| 0000001005 | submitted   | t | 2026-09-08 |
| 0000001006 | submitted   | t | 2026-09-08 |
| 0000001007 | submitted   | t | 2026-09-08 |
| 0000001008 | submitted   | t | 2026-09-08 |
| 0000001009 | in_progress | f | 2026-09-10 |
| 0000001010 | submitted   | t | 2026-09-10 |
| 0000001011 | submitted   | t | 2026-09-11 |
| 0000001013 | in_progress | f | 2026-09-11 |
| 0000001018 | in_progress | f | 2026-09-10 |
| 0000001021 | in_progress | f | 2026-09-10 |
| 0000001025 | submitted   | t | 2026-09-11 |
| 0000001026 | in_progress | f | 2026-09-13 |
| 0000001030 | submitted   | t | 2026-09-08 |
```

Of the old staff range `0000001001`-`0000001030`: **11 burned** (terminal), **6 resumable**
(`in_progress` — they can be re-entered, they are not spent), **13 never touched**. That is why the
new hundred is a separate range: extending the old one would have handed a tester a block with
eleven dead numbers scattered through it and nothing on screen to distinguish them.

The new range is clean, and stayed clean after the three probe checks below — an account check
creates no profile, it only answers:

```
 profiles_in_new_range
-----------------------
                     0
```

## BL-097 closed, and proven through the documented path

The flag that disables WhatsApp was set by hand at S8-08 (on revision 10, registered from revision 9's
own JSON), was still present on revision 11 when this session read it live, and appeared in no
repository file.
`08-backend-service.sh` — which its own README calls "the script you re-run to deploy a new image" —
did not carry it, so this very deployment would have silently dropped it and staging would have gone
back to minting WhatsApp challenges that the `stub` provider can never deliver.

Both halves built: the heredoc now carries
`{ "name": "FRU_MESSAGING_WHATSAPP_ENABLED", "value": "false" }`, and `application-aws.properties`
now binds `fru.messaging.whatsapp.enabled=${FRU_MESSAGING_WHATSAPP_ENABLED:true}` explicitly. The
second half is not belt-and-braces: that file's own §9 convention says a key must not depend on
relaxed environment-variable binding, precisely because a value that binds invisibly is a value
nobody can grep for — which is how this defect happened.

**Proof, and it is the live kind rather than a passing test:** the deployment below ran `08` exactly
as prescribed, and revision 12's registered environment reads `FRU_MESSAGING_WHATSAPP_ENABLED = false`.

## Gates

### backend — `./mvnw verify -Pdb-integration-test`

```
[INFO] Results:
[INFO]
[INFO] Tests run: 1155, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 471 files clean - 0 needs changes to be clean, 0 were already clean, 471 were skipped because caching determined they were already clean
[INFO]
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 340 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  07:08 min
```

Re-run after the review pass, because the review changed a comment in `application.properties`:
`./mvnw verify -Pdb-integration-test` — 1155 tests, 0 failures, Spotless clean, coverage met,
BUILD SUCCESS. Not pasted in full: it repeats the passing run above.

### backoffice — `npm run lint`, `npm run test`, `npm run build`

```
lint exit code: 0

 Test Files  21 passed (21)
      Tests  175 passed (175)
   Duration  243.40s

dist/index.html                     0.53 kB │ gzip:   0.37 kB
dist/assets/index-MI8JscGq.css      0.30 kB │ gzip:   0.20 kB
dist/assets/index-DhknbztP.js   1,257.01 kB │ gzip: 397.36 kB
✓ built in 20.43s
```

Lint emits warnings only and exits 0; all of them pre-date this session, which changed no
back-office source.

### mobile — `fvm flutter analyze`, `fvm flutter test`

```
Analyzing mobile...
No issues found! (ran in 237.7s)

03:00 +654: All tests passed!
```

### mobile — `fvm flutter build apk --release --dart-define=REFERENCE_API_BASE_URL=https://d12k860j1xg6zy.cloudfront.net`

```
Running Gradle task 'assembleRelease'...                          880.3s
√ Built buildpp\outputslutter-apkpp-release.apk (108.9MB)
```

Release, not debug: a debug build skips the release-only gates, and this APK is what testers
install. It is signed with the **debug key** (BL-082 blocker 2, unchanged) — fine for
sideloading, still a Play submission blocker.

Identity, read off the built artifact rather than from the build config:

```
package: name='com.sfbank.bayanati' versionCode='1' versionName='1.0.0' compileSdkVersion='37'
minSdkVersion:'24'
targetSdkVersion:'36'
sha1: 14fda3d1a5d96529807251184ff3736cc29f6599
```

**The `--dart-define` is proved in the shipped binary, not assumed from the command line.** The base
URL is a compile-time `String.fromEnvironment` with a `http://localhost:8080` default, so a build
that silently dropped the define would still produce a working-looking APK that talks to nothing.
Grepping the `.apk` as a zip would have given a false negative (the entries are compressed), so the
AOT artifact was extracted and searched directly:

```
$ unzip -o app-release.apk lib/arm64-v8a/libapp.so
https://d12k860j1xg6zy.cloudfront.net -> 1 occurrence(s)
http://localhost:8080                 -> 0 occurrence(s)
```

The default is absent, so the override is what shipped.


## Live proofs

Migration, against the real staging database:

```
[INFO] Successfully validated 69 migrations (execution time 00:00.565s)
[INFO] Current version of schema "public": 0065
[INFO] Migrating schema "public" to version "0066 - app artifact ref storage key dead r046 closed"
[INFO] Migrating schema "public" to version "0067 - app profile status history four eyes removed"
[INFO] Migrating schema "public" to version "0068 - app operator role drop is back office"
[INFO] Migrating schema "public" to version "0069 - app salary certificate claimed"
[INFO] Successfully applied 4 migrations to schema "public", now at version v0069
```

Rollout:

```
{
    "taskDef": "arn:aws:ecs:eu-central-1:<account>:task-definition/fru-staging-backend:12",
    "desired": 1,
    "running": 1,
    "rollout": "COMPLETED"
}
```

Startup, from the new task's own log stream — no error, and the pool reaching RDS is what proves the
schema and the code agree:

```
2026-09-13T23:50:03.928Z  INFO 1 --- [backend] [main] com.sfbank.bayanati.BackendApplication : Started BackendApplication in 16.649 seconds (process running for 18.796)
2026-09-13T23:50:30.127Z  INFO 1 --- [backend] [nio-8080-exec-2] com.zaxxer.hikari.HikariDataSource : HikariPool-1 - Start completed.
```

**The one proof that the hundred accounts exist in the deployed image rather than only in the
repository** — through CloudFront, not from inside the VPC. A passing test could not have shown
this: the accounts are configuration baked into an image, so only the running image can answer.

```
$ curl -X POST https://d12k860j1xg6zy.cloudfront.net/api/v1/account-check -d '{"branch":"001","accountNumber":"0000002001"}'
{"outcome":"ACTIVE","continuation":"PROCEED","requestId":"fc2ed0db-...","blockedUntil":null}

0000002050 -> ACTIVE PROCEED
0000002100 -> ACTIVE PROCEED

$ curl ... '{"branch":"001","accountNumber":"0000009999"}'
{"outcome":"INVALID","continuation":"RETRY","requestId":"d6a71324-...","blockedUntil":null}
```

First, middle and last of the range all bind, so the block is whole rather than truncated, and the
unseeded number still answers not-found, so the stub's fall-through is intact.

Back office, serving the bundle built this session:

```
$ curl -s https://d12k860j1xg6zy.cloudfront.net/ | grep -o 'assets/index-[A-Za-z0-9]*\.js'
assets/index-DhknbztP.js
```

## Independent of the laptop

Nothing in the running system is local. The backend is one Fargate task under an ECS service with
`desiredCount: 1`, so ECS replaces it if it dies; the database is RDS; the back office is S3 behind
CloudFront; and the APK's base URL is compiled to the CloudFront hostname, not a LAN address. The
SSM tunnel that carried the migration and the read-only queries is closed, and nothing depends on it.

The honest limit on that claim: **one** task, and no monitoring of any kind (BL-127 — no alarms, no
ALB access logs, no container health check). The service survives the laptop being closed; it will
not tell anybody if it stops working.

## What was deliberately not done

Flagged before this round and still open, unchanged by it: **R-037** — `audit.seal_create()` and
`app.purge_abandoned_artifacts()` are still unscheduled, so the seal chain does not advance and
abandoned identity images are not deleted. **BL-127** — no monitoring. **R-041** — the SMS sender ID
is unregistered and only Sudani is proven. **BL-094** — delivery to a non-Sudanese number is still
unproven. These are the standing conditions of the test, not new findings.

## Commit proof

Captured AFTER the push, as CLAUDE.md requires — a status reading "ahead of" would disprove the
claim rather than support it.

```
$ git push origin main
To https://github.com/Osmantou/Fr_user_update
   7697c2d..10c67cc  main -> main

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean

$ git log --oneline -2
10c67cc S8-32: staging refreshed for the live test round
7697c2d S8-31: commit proof in the session report
```

Seven files, staged by name rather than with `git add -A` (R-053).

