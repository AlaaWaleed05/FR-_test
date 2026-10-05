# AWS app tier — Phase 2 deployment, session 2

Sprint 7. Continues `2026-09-06-aws-provisioning.md`, whose stack was left running and was
built on, never re-provisioned.

**Outcome in one line: the backend runs on Fargate against the real RDS instance and is
healthy; the acceptance walk did NOT run and is owed as its own session.**

---

## 1. The session had two halves, and the first was not in the brief

The brief opened with `@agent-reviewer` against session 1's committed work. That review found
no blocker and no leaked secret, but five real defects in `infra/aws/`, all in the same class:
**a gate that reports success when the thing it checks has failed.** Since those scripts run
once, against a live account, at creation time, they were fixed before anything was built on
top of them.

| Finding | Disposition |
|---|---|
| **`04-db-gates.sh:65-73` — M-3c printed `PASS` when its own ICU query errored.** Both `psql` exit codes were discarded; if only the `ar-x-icu` query failed — the exact condition the gate exists to detect — `ICU_ORDER` was empty, `C_ORDER` was not, the two "differed", and the gate passed | **Fixed.** Both orders must now be non-empty before they are compared. Proven by execution, not review — see §3 |
| **`04-db-gates.sh` — three verdicts were computed in SQL and never read.** M-3a (`:43`), the `datlocprovider` check (`:56`) and **M-4's own trigger-existence confirmation** (`:120`) printed a literal `PASS`/`FAIL` as a query *result* that the shell never inspected. Worse than "prints FAIL and continues": a `psql` error there produces neither word, so the STOP gate could report nothing and still exit 0 | **Fixed.** A `check_sql` helper reads the verdict, treats an absent verdict as failure, and M-4 exits 1 |
| **`04-db-gates.sh` — every other FAIL branch exited 0.** The script runs `set -uo pipefail` without `-e`. Under SSM automation a run where M-3 and M-5 both failed was reported as a *successful invocation* | **Fixed.** `RC` accumulates and the script exits non-zero |
| **`04-db-gates.sh:89-91` — `create-secret` unchecked.** `put-secret-value` was guarded; the first-creation path was not, so a failed store fell through to `CREATE ROLE` and produced a live privileged role whose password was recorded nowhere | **Fixed** with the same guard. **The reviewer's suggested reorder was rejected**: moving the write after role creation relocates the hole rather than closing it. Store-then-check-then-create is correct |
| **`04-db-gates.sh` rotated a live credential silently on re-run**, while `01`/`02` refuse to run twice and say so | **Fixed** in the header, which now also states plainly that the script is a *provisioning step* that creates the database and the `fru_migrator` role — not the test its name implies |
| `02-database.sh:24` referenced a nonexistent `04-gates.sql`; `05-layer3.sh:63-66` had the same exit-0-on-FAIL | **Both fixed** |

**A correction the previous session made to the review, which was right and is recorded
because it changes the README:** `04-db-gates.sh` is a prerequisite of `flyway:migrate`, not a
test of it, and `06-secrets.sh` never ran before the migration — it did not exist yet, and
those two secrets were created ad hoc. So the numeric order `01..06` does not reproduce the
stack. `infra/aws/README.md` now records the dependency order
(`01 → 02 → 03 → 04 → 06 → flyway:migrate → 05`) and says explicitly that it is the
dependency order and not a transcript of what happened.

**`infra/aws/00-discover-ids.sh` was written rather than the gap being backlogged.** Both
`*-ids.env` files existed only on one machine; `02`/`03` refuse to run without them and `01`
self-refuses when the VPC exists, so a fresh checkout had no route back to a running stack.
Every resource `01` and `03` create is tagged, so the file is rebuildable from the account.
The script is read-only (`describe-*` only), refuses to write anything partial or ambiguous,
and keeps a `.bak`. Both files turned out recoverable, not just the network one.

---

## 2. The gate script now has tests, and they caught a real regression

`infra/aws/test/run-gate-tests.sh` — stub `psql`/`aws`/`jq` earlier on `PATH`, no network, no
credentials, no account. `jq`, `psql` and `aws` are all absent from this machine, so the stubs
are the only ones that resolve and a missing stub fails loudly instead of reaching a live tool.

15 scenarios, 36 assertions, all passing. The one that matters is **test 15**, which runs the
*pre-fix* script (`95f5b4d`) against the same stubs:

```
=== 15. REGRESSION: the same scenario 5 against the pre-fix script (95f5b4d) ===
    pre-fix exit code : 0
    pre-fix verdict   : PASS  ICU collation demonstrably changes ordering (differs from codepoint order)
  ok    the pre-fix script printed PASS and exited 0 on a failed ICU query
        -- the fixed script fails the identical scenario in test 5
```

That is the false PASS caught in the act. Under CLAUDE.md's revert-restore rule this did not
strictly need proving — the assertion is a direct one on the absence of a `PASS` string, and
could not pass against the bug — but the pre-fix script is one command away and the evidence
is stronger than the argument.

**Two defects the harness found in the work that built it**, recorded because they are the
same class the session was fixing: the stub matched the wrong branch because M-4's verdict SQL
contains the literal words `CREATE EVENT TRIGGER` inside its own `PASS` string; and the first
secrets scan used `if grep … | head`, which tests `head` and therefore always succeeds. The
second would have reported a clean bill of health over anything.

---

## 3. Part 1 — the app tier

### S7-07 backend on ECR + Fargate — ✅

`backend/Dockerfile`, two stages. **This row had assumed `spring-boot-maven-plugin`'s
`build-image` and "no Dockerfile"**; a Dockerfile was written instead so the shipped jar is
the exact artifact the gate ran against rather than a second build of the same source. The
first stage fetches Amazon's **global** RDS trust store (global, not `eu-central-1`, so
relocating the database is not also a certificate change); the runtime stage runs as non-root.

Proven in the image before it was pushed:

```
=== RDS CA bundle present, and is it a real bundle? ===
-rw-r--r-- 1 root root 165408 Sep  6 18:49 /opt/rds/global-bundle.pem
108
=== runs as non-root? ===
uid=100(fru) gid=101(fru) groups=101(fru)
```

`infra/aws/07-ecr-and-params.sh` creates the ECR repository — **IMMUTABLE tags** (a mutable
tag lets "the image that was reviewed" and "the image that is running" differ while answering
to one name), scan-on-push, 14-day untagged expiry — and the Parameter Store entries. It also
*verifies* the Uqudo secret's shape without reading it, and refuses to proceed if absent.

`infra/aws/08-backend-service.sh` creates the log group (30-day retention), execution role,
cluster, target group, ALB, task definition and service. Re-running it reported `exists` or
`already absent` for every step, which is how its idempotency was proven rather than asserted.

**The ALB is internal, and that is a change from what session 1 anticipated.** ACM will not
issue a certificate for an `*.elb.amazonaws.com` name, so a public ALB could only have served
plaintext until a domain exists — and the walk carries a real person's national record.
CloudFront reaches an internal ALB through a VPC origin instead. `SG_ALB`'s internet-facing
443/80 ingress from S7-02 was therefore revoked: on an internal load balancer it exposed
nothing, but a rule saying "the internet may reach this" when the design says the opposite is
drift a later reader trusts.

**`sslmode` raised from `require` to `verify-full`** — the hardening session 1 deferred to the
image build. `require` encrypts but authenticates nothing. Proven live from the running task,
not asserted:

```
2026-09-06T19:29:33.678Z  INFO 1 --- [backend] [main] BackendApplication : The following 1 profile is active: "aws"
2026-09-06T19:29:45.875Z  INFO 1 --- [backend] [main] BackendApplication : Started BackendApplication in 14.506 seconds
2026-09-06T19:30:05.295Z  INFO 1 --- [backend] [nio-8080-exec-1] HikariPool : HikariPool-1 - Added connection org.postgresql.jdbc.PgConnection@7537129f
```

A `verify-full` connection to RDS from Fargate, with the bundle baked into the image.

`spring-boot-starter-actuator` was added because the codebase had **no endpoint that could
answer a health check** — every route is a POST or needs a session, and a target group
accepting a 405 would prove only that a servlet container is listening. Scope: `health` only,
`show-details=never` (with details on, an unauthenticated endpoint publishes the database
vendor and every contributor's state). It sits outside `/api/v1/**`, so it matches neither
`SecurityFilterChain` and **no security change was made**; and it is unreachable publicly
regardless, since the ALB is internal and CloudFront forwards only `/api/*`.

**Two defects this session caught in its own work**, both fixed rather than worked around:
`mktemp` handed the Windows `aws.exe` an MSYS path it cannot open (the same mangling that cost
session 1 its first AMI lookup), and the first image was built from a jar predating the
`fru.civil-registry.http.endpoint` property added minutes earlier. The second was rebuilt
through the full gate rather than patched, so the Dockerfile's claim about provenance stays
true. **Time was lost diagnosing the second as a config fault** when every failing task was in
fact still on revision 1 — recorded because the lesson is to check *which revision* a failing
task ran before reading its logs as evidence about the current one.

### S7-11 — ✅, closed on Fargate

`infra/aws/10-s711-selector-probe.sh`. It builds its task definition **from the live one**
minus exactly one variable, so it exercises the real ECS injection path rather than a
hand-written approximation, and runs a one-off task in a separate family so the service is
never touched.

```
== waiting for it to STOP (a start would itself be the failure) ==
  lastStatus=PROVISIONING
  lastStatus=RUNNING
  lastStatus=DEPROVISIONING
  lastStatus=STOPPED
  container exit code: 1
== the reason, from the task's own log stream ==
  PASS  refused with: Could not resolve placeholder 'FRU_CORE_BANKING_CLIENT'
== S7-11 probe PASSED: an unset selector refuses to start on Fargate ==
```

**The assertion is on the message, not on the exit code.** A task can die for a dozen reasons
that have nothing to do with the selector, and "it exited 1" would accept all of them — the
correction session 1's review made to the Layer 3 proof, applied here. This retires the hazard
the row names: Spring's relaxed binding did not quietly satisfy `fru.core-banking.client` from
the environment.

---

## 4. R-001's residual — discharged, and the premise it rested on was wrong

The brief required confirming, before any real PII, that the AWS Uqudo secret held the
project's own tenant rather than a session-1 leftover labelled "FIB tenant".

**There was no Uqudo secret in AWS at all.** `list-secrets` returned `rds!db-*` and
`fru/staging/db/{migrator,app,sealer}` and nothing else. The "FIB tenant" label was wording in
session 1's *report*, not a stored value — session 1's §7 had listed the Uqudo secrets as
PO-supplied and they had never been supplied.

So the discharge is **by provenance, not inspection**: the product owner created
`fru/staging/uqudo` fresh, with the project's own credentials, minutes before it was used.
There is no leftover to be wrong about. This session verified structure only — both keys
present, non-empty, no surrounding whitespace or newline — and never read a value, per
CLAUDE.md's rule that these appear in no log or report even redacted. It is stated as
provenance deliberately: an inspection would require knowing the tenant's real client id to
compare against, which this session must not hold.

**One process note worth keeping.** The first attempt stored the *placeholder prose from the
instructions* rather than the credentials, and the structural check passed it as "non-empty,
clean" because it only flagged values like `changeme`. The check was tightened to reject
whitespace, prose and the exact placeholder text, and the corrected version passed. A
verification that cannot fail is not a verification.

**R-001 stays amber, not retired**: it also covers real customer data through a real tenant,
and no walk with real PII has run.

---

## 5. Research before building — CloudFront VPC origins

`@agent-researcher` was run before the CloudFront work, per CLAUDE.md's rule against
integrating from documentation alone. It changed the design twice, and both would have been
defects.

**`CustomErrorResponses` is distribution-wide.** `CacheBehavior` has no equivalent field, and
CloudFront resolves the mapping from the distribution regardless of which behaviour served the
failing request. The conventional SPA history fallback — 403/404 → `/index.html` with status
200 — would therefore have rewritten **genuine API 403s and 404s from the ALB** into an HTML
page with status 200, silently breaking the back office's session-expiry handling. The
fallback is done instead with a CloudFront Function on the default behaviour only, since
`FunctionAssociations` *is* per-behaviour and a URI rewrite provably does not change which
origin serves the request.

**The origin's access boundary is a security group, not a prefix list.** CloudFront creates
`CloudFront-VPCOrigins-Service-SG` when the VPC origin is created; referencing it restricts
traffic to *our* distributions. The `origin-facing` managed prefix list is documented as valid
but admits any CloudFront distribution in any AWS account — not a property a bank's identity
API should have. The group exists only after the origin deploys, which is why the narrowing is
the last step of `09` rather than part of `08`.

Also carried forward: the VPC must keep its internet gateway (a required flag, routing
nothing), and outbound NACL rules must permit ephemeral ports while inbound rules are not
evaluated at all — so a later NACL tightening could break the origin with no inbound-side
symptom. Recorded in `docs/components/cloudfront-vpc-origin.md`.

---

## 6. BL-066 — found live, then fixed

The researcher's prediction was confirmed by measurement, not argument. **Through CloudFront,
over HTTPS, on the running stack:**

```
HTTP/1.1 401 
Set-Cookie: XSRF-TOKEN=20e70cb7-...; Path=/
Set-Cookie: JSESSIONID=0EDB1A54...; Path=/; HttpOnly
```

Neither cookie carried `Secure`. A bank operator's session cookie and CSRF token were both
marked as safe to send over plaintext — the exact defect BL-066 exists to prevent, and the
exact reason its own text says the assertion matters more than the property.

**Why the property was necessary and not sufficient.** `server.forward-headers-strategy=framework`
makes Boot honour `X-Forwarded-Proto`, and ELB documents that header as carrying *"the protocol
used between the client and the load balancer"*. CloudFront is the load balancer's client and
connects over HTTP, so the ALB truthfully reports `http`; Boot concludes the request was
plaintext and omits `Secure`. TLS terminates two hops earlier and the ALB cannot know. The
inference is not broken — it is accurate about the wrong hop, and always will be under this
topology.

**The fix states the attribute rather than deriving it.** `server.servlet.session.cookie.secure=true`
covers `JSESSIONID`. `XSRF-TOKEN` has no property equivalent: it comes from
`.csrf(CsrfConfigurer::spa)`, so `SecurityConfiguration` now supplies the repository
explicitly. **`spa()`'s behaviour was verified against spring-security-config 7.1.0 bytecode
rather than assumed** — it sets `CookieCsrfTokenRepository.withHttpOnlyFalse()` *and* a
`SpaCsrfTokenRequestHandler`, so calling `csrfTokenRepository` after `spa()` replaces only the
repository and leaves Spring's SPA handling intact. That check was the go/no-go: had the two
been inseparable, the fix would have meant reimplementing Spring Security's SPA support inside
a bank's login path, and the item would have been filed instead.

**Both switches default to OFF and are enabled only in the `aws` profile.** A `Secure` cookie
is silently discarded by the browser over plain HTTP, so enabling this globally would break
local development in the least debuggable way available: sign-in succeeds, the cookie never
arrives, the next request is anonymous.

`SecurityConfigurationCsrfCookieTest` asserts the **actual `Set-Cookie` header** in both
states, plus that `HttpOnly` stays off (the SPA must read the cookie from JavaScript). Reading
the header is the point: this defect was invisible to all 1,016 existing tests precisely
because none of them looked at one, and a test asserting "the property is set" would have
passed against the broken build. **No revert-restore proof is included, and the reason is the
rule's own distinction:** the assertion is a *direct* one on the produced value
(`contains("Secure")`), so it cannot pass against the defective version — a revert is required
for indirect assertions, not this shape.

**Verified live on the running stack, same command, same URL.** The deploy was allowed to
finish first: revision 2 and revision 3 both served traffic during the rolling update, so the
check waited until `running revisions=3, deployments=1` — a result taken while both were live
would not have distinguished the fix from luck about which task answered.

```
HTTP/1.1 401 
Set-Cookie: XSRF-TOKEN=4859633f-...; Path=/; Secure
Set-Cookie: JSESSIONID=07FF64D2...; Path=/; Secure; HttpOnly
```

Both carry `Secure`. `HttpOnly` remains absent from `XSRF-TOKEN` (the SPA reads it) and present
on `JSESSIONID` — the fix added one attribute and changed nothing else. The four routing checks
were re-run afterwards and are unchanged, so nothing regressed. **BL-066 is closed on both
halves: the property and the assertion.**

## 7. Gates

Backend tier touched (`Dockerfile`, `pom.xml`, `application-aws.properties`,
`SecurityConfiguration.java`), so `./mvnw verify -Pdb-integration-test` — the real coverage
gate per CLAUDE.md — was run in full. It ran three times: before the Civil Registry property
was added, after it, and again after the BL-066 fix. Only the final run is pasted; the earlier
two are recorded as one line each — `Tests run: 1016, Failures: 0`, BUILD SUCCESS at 20:46 and
again at 21:15 — because they predate what is being committed and prove nothing about it.

**Final run, verbatim:**

```
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 444 files clean - 0 needs changes to be clean, 0 were already clean, 444 were skipped because caching determined they were already clean
[INFO] 
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 324 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  01:57 min
[INFO] Finished at: 2026-09-06T22:00:56+02:00
[INFO] ------------------------------------------------------------------------
```

The surefire summary line from the same run: `Tests run: 1019, Failures: 0, Errors: 0, Skipped: 0`
— 1016 plus the three added for BL-066. (Surefire prints it above the plugin output pasted
here, not inside it.)

The property that caused the rebuild was confirmed present **inside the shipped jar**, not
just in the working tree:

```
fru.uqudo.http.client-id=${UQUDO_CLIENT_ID:}
fru.civil-registry.http.endpoint=${FRU_CIVIL_REGISTRY_ENDPOINT:}
spring.datasource.url=jdbc:postgresql://${DB_HOST}:${DB_PORT:5432}/${DB_NAME}?sslmode=verify-full&sslrootcert=${DB_CA_BUNDLE:/opt/rds/global-bundle.pem}
```

Back office: `npm run build` clean, 1,511 modules, `dist/` 1.3 MB. Mobile untouched — no gate
applies and none was run.

Infrastructure: `bash infra/aws/test/run-gate-tests.sh` — **36 passed, 0 failed** (re-run
after the review fixes, which added scenario 14 for a probe trigger that fails to drop).

---

## 8. What did NOT get done

| Item | State | Why |
|---|---|---|
| **S7-12 the guided acceptance walk** | ⬜ | **Not started, and deliberately not started.** The brief scoped it to "only if Part 1 leaves genuine headroom" and said a rushed walk is worse than a clean stop. Part 1 consumed the session |
| **BL-066** | ✅ | **Done, not deferred** — the assertion ran through CloudFront, FAILED, and the defect it found was fixed and re-verified. See §6. One honest caveat kept: the evidence is a `Set-Cookie` on a 401, not on a successful operator sign-in, because no operator credentials were held by this session. The cookies are issued by the same filter chain either way, but the walk should re-confirm after a real sign-in |
| **AD-002d back-office origin posture** | — | Same-origin was built as the brief instructed and as S7-08's row says closes it. Recorded as a deliberate closure with reasoning, not settled in passing |
| **M-21 / the production domain** | ⬜ | Filed as **BL-074**: bank-owned domain, ACM certificate, and an APK rebuild, because `app_config.dart` bakes the URL at build time |
| **R-026 / R-051** | 🔴 | Untouched. Staging on AWS is not production |

---

## 9. Review findings and dispositions

`@agent-reviewer` against the full working-tree diff. **One blocker, eight should-fixes, six
nits. All accepted; all fixed.** The three that mattered:

| Finding | Disposition |
|---|---|
| **BLOCKER — `00-discover-ids.sh`'s "refuses to write a partial file" guard was inert.** Every `resolve()` call is a command substitution, so `MISSING=1` was set in a SUBSHELL and never reached the parent; the check always saw 0 and the script would have written empty values and exited 0 | **Fixed** with a marker file, which crosses the subshell boundary. Proven: `M=$(mktemp); rm -f $M; r(){ : > $M; }; V=$(r); [ -f $M ]` → fires. **This is the same report-success-when-the-check-failed class the session's first half exists to remove, shipped in the same change set** — and it was asserted as fact in both the README and this report |
| **`08` re-opened on every redeploy what `09` closes.** `08` unconditionally authorised `tcp/80 from 10.0.0.0/16` on `SG_ALB`; `09` revokes exactly that rule once CloudFront's service SG exists. Re-running `08` is the documented way to deploy a new image, and `09` is not re-run for one | **Fixed** — the placeholder is now added only when no source-group rule is present. **Confirmed live, not theoretically:** the security group held BOTH rules after the BL-066 redeploy. The VPC-CIDR rule was revoked on the running stack; it now allows only the CloudFront service group |
| **`07`'s Uqudo check was weaker than every claim made about it.** It checked key *names* only, while R-001, S7-05 and §4 of this report all describe a check for emptiness, whitespace and placeholder prose. The check that actually caught the placeholder-prose mistake was ad hoc and lived in a session transcript — the exact gap session 1's review raised about `06-secrets.sh` | **Fixed** by putting the strengthened check in the script and re-running it green. Values are read but never printed; the header, which claimed the script "never reads their values", was corrected too |

Also fixed: `05-layer3.sh`'s row-count verdict was still computed in SQL and never read (the
same defect class, in a file this session had already edited); `10`'s failure branch leaked a
running Fargate task — the branch that fires when a backend *starts* without a selector, so it
would have added a second fault to the first; `S7-05` and `S7-11` rows were malformed into five
cells, leaving both reading 🔵 while claiming completion; `PROJECT_PLAN.md` still listed the
back-office origin posture as open; four full `Set-Cookie` values (two complete `JSESSIONID`s)
were pasted into this report and are now truncated; and the "what did NOT get done" table
contradicted §6 on BL-066.

Nits fixed: the README documented only `01`–`06`; `04`'s "probe objects removed" was announced
unconditionally after two unchecked `DROP`s (now confirmed, with a new test scenario);
`09`'s timestamped `CallerReference` defeated CloudFront's own duplicate protection.

**The gate harness caught the fix to the last of those**: adding the drop confirmation broke
four scenarios, because the new query fell through to the stub's M-4 branch and received a
verdict string where it wanted a count. Stub dispatch corrected and a scenario added for the
undropped-trigger case — 36 passing, up from 33.

---

## 10. Filed this session

- **BL-074** — the production DNS name as a bank dependency (BL-072's class)
- `infra/aws/00-discover-ids.sh`, `07-ecr-and-params.sh`, `08-backend-service.sh`,
  `09-backoffice-cloudfront.sh`, `10-s711-selector-probe.sh`, `README.md`, `test/`
- `backend/Dockerfile`, `backend/.dockerignore`
- `docs/components/cloudfront-vpc-origin.md`
