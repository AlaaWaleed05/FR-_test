# AWS hosting requirements — AD-002d provider decision and service mapping

**Date:** 2026-09-06 · **Type:** decision + requirements/infrastructure mapping · **No feature code changed**

---

## 1. The decision, recorded

**AD-002d's provider question is CLOSED: AWS, by product-owner decision, on technical fit.**

Recorded in PROJECT_PLAN.md's decisions log as row `AD-002d (provider)`, mirroring the
`AD-002c (port)` precedent for closing one half of a decision while its remaining half stays open.

**Stated honestly, as instructed:** the provider is fixed on technical fit alone. The
**legal and regulatory viability of a Sudan-based bank using a US cloud provider — sanctions
exposure and data residency — is the bank's legal team's call and is NOT part of this technical
decision.** It is recorded as a **dependency the bank must confirm before any real customer data
reaches an AWS account**, and this session does not evaluate it, does not estimate it, and does not
treat OQ-001/OQ-002 as answered by it. AD-002d listed those two open questions as its blockers; the
product owner's decision resolves *which host*, not *whether that host is permitted*.

**What closes, and what does not.** Closed: the provider question. Still open and still owned by
AD-002d: **R-051** (customer-session auth — see §6), **R-026** (encryption at rest — the requirement
is now mapped, §5, but not built), **R-037** (seal export destination — mapped with a caveat, §5),
**R-046** (back-office image URL addressing — untouched, a back-office design decision this session
deliberately does not make). Each remains 🔴 Live in RISKS.md.

## 2. Scope, and what this session deliberately did not do

Did not: provision anything, write Terraform/CloudFormation, choose instance sizes as commitments
rather than starting points, touch feature code, price anything, or evaluate the legal question.
The runbook and the actual provisioning are a later session, once these requirements are agreed.

**Inputs.** The requirement IDs **M-1 … M-22** below are not invented here — they come from
`docs/sessions/2026-09-06-research-ad-002d-hosting-technical.md` §2, where each is derived from an
observed repository fact. This session supplies the third and fourth columns: the AWS service and
why. **Every M-ID is traceable back to source evidence in that report; I re-verified the ones this
mapping leans hardest on** (M-1, M-4, M-6, M-7, M-11, M-13, M-15, M-20, M-22) directly against the
files, and one research statement was found **stale**: §6.6 says `fru.uqudo.client=http` "has no real
adapter yet". It does — `uqudo/http/HttpUqudoClient.java` exists and
`UqudoClientConfiguration:86` wires it. **Three of the four stub→real swaps are live today; messaging
is the exception** — `messaging/` has `config`/`domain`/`service`/`stub` and no `http` package, and
`MessageSenderConfiguration:82-84` accepts `stub` and throws on everything else, by design until the
bank answers OQ-016 (§9).

---

## 3. Requirements → AWS service

### Compute and platform

| # | Requirement | AWS service | Why this one |
|---|---|---|---|
| M-1 | Java 21 runtime, one Spring Boot 4.1.0 fat jar | **ECS on Fargate**, image in **ECR** | The runtime ships *inside* the image, so the Java-21 constraint (`pom.xml:30`, enforcer `[21,22)`) stops being a host property the bank can drift. `spring-boot-maven-plugin` is already declared (`pom.xml:171`) and its `build-image` goal produces an OCI image with **no Dockerfile to maintain** |
| M-8 | Static hosting for `backoffice/dist` with SPA history fallback | **S3 (private) + CloudFront with OAC** | The jar carries no static resources — `backend/src/main/resources/static/` exists on disk but is empty and holds no tracked files (verified; the research report's "does not exist" is imprecise). History fallback is a CloudFront custom error response: 403/404 → `/index.html`, 200 |
| M-16 | A decision on back-office origin: same-origin, or cross-origin + CORS + `SameSite` | **One CloudFront distribution, two behaviours** — default → S3, `/api/*` → ALB. **Recommended mapping, to be confirmed at provisioning — NOT recorded as closed** | `backoffice/vite.config.ts:9-11` explicitly defers this to AD-002d, and the provider decision alone does not settle it. Routing both through one distribution makes the back office same-origin in production exactly as the Vite dev proxy makes it same-origin in dev — so `JSESSIONID`/`XSRF-TOKEN` need no cross-site cookie relaxation and **no CORS configuration is written at all**. Choosing a separate `api.` hostname instead would force `SameSite=None` on a bank operator's session cookie for no gain |
| M-14 | TLS on every public surface | **ACM certificate on CloudFront + ALB** | ACM issues and auto-renews at no cost; renewal is a class of outage the bank does not inherit |
| M-15 | `X-Forwarded-*` handling once TLS terminates ahead of the jar | *(not an AWS feature — a backend config property)* | **Verified live this session: `server.forward-headers-strategy` appears nowhere in `application.properties`.** Behind an ALB, Boot sees `http` and issues `JSESSIONID`/`XSRF-TOKEN` **without `Secure`**. Filed as **BL-066**; it is a one-property deployment-config change, not an AWS setting |
| M-21 | A stable permanent public DNS name, fixed before the first store release | **Route 53 + CloudFront alias** | `mobile/lib/core/config/app_config.dart:6-9` bakes the base URL at build time via `--dart-define`; changing it post-release is an app-store submission, not a config change |
| M-17 | Egress to Uqudo (443), the middleware on **9494**, the registry on **5353**, and the messaging gateway | **NAT Gateway + explicit security-group egress rules** | Non-standard ports are the trap: a default-deny egress policy blocks 9494/5353 silently. The failure direction is safe (connection refused, not wrong data), but it must be an explicit provisioning line item |

### Database

| # | Requirement | AWS service | Why this one |
|---|---|---|---|
| M-2 | PostgreSQL 18, one instance, three schemas | **RDS for PostgreSQL 18** — *not* Aurora, *not* self-managed on EC2 | See §4 |
| M-3 | An ICU-enabled build; `ar-x-icu` resolvable | RDS PG18 — **[UNVERIFIED], a provisioning gate** | See §4 |
| M-4 | Genuine superuser (or delegated equivalent) for `CREATE EVENT TRIGGER` | RDS **main user account** — **the single most load-bearing AWS fact in this mapping** | See §4 |
| M-5 | Create login roles; transfer database ownership to `fru_migrator` | RDS main user — **[UNVERIFIED], a provisioning gate** | See §4 |
| M-6 | Migration on a separate privileged connection; the shipped jar holds zero DDL rights | **Three separate Secrets Manager secrets**, only one injected into the ECS service task | See §4 |
| M-7 | Somewhere to run three post-migrate steps, one as the superuser | **A one-shot ECS task in the same private subnets** | M-11 removes the public DB endpoint, so this must originate inside the VPC. A task definition is reproducible for the bank; a bastion someone SSHes into is not |
| M-18/19 | ~350–500 GB data, 1–1.5 TB provisioned incl. backups; ~3 submissions/hour; no partitioning | **gp3 storage, provisioned for the end state** | See §7 |

### Data security

| # | Requirement | AWS service | Why this one |
|---|---|---|---|
| M-9 (R-026) | Disk/volume-level encryption over the whole database | **RDS storage encryption under a customer-managed KMS key** | AD-004 put identity-document bytes in `app.artifact_ref.body`, and persistence.md settled that the answer is volume-level, not per-column. **Hard caveat [DOC]: RDS can only be encrypted at creation, never after.** Getting this wrong means a snapshot-copy-and-restore migration on a ~500 GB database holding passport scans |
| M-10 | Encrypted backups, not just an encrypted live volume | **RDS automated backups + PITR**, inheriting the same KMS key | Because AD-004 put the images *inside* the database, **every backup is a copy of every passport scan.** [DOC] RDS encrypts backups, snapshots and logs under the instance key automatically — which is precisely why M-9's at-creation caveat is load-bearing for M-10 too |
| M-11 | The database unreachable from the public internet | **Private subnets, `PubliclyAccessible=false`, SG allowing 5432 only from the ECS task SG** | `docker-compose.yml:16-17` publishes 5432 to the host — correct for local dev, must not survive into production. A CIDR-based rule is weaker than an SG-to-SG rule and should not be used |
| M-13 | Secrets outside the repo and outside the image | **Secrets Manager** (5 credentials) + **SSM Parameter Store** (non-secret config) | See §9 |
| M-12 (R-037) | An off-box destination for the audit seal, and something that invokes `seal_create()` | **S3 with Object Lock (Compliance) in a SEPARATE, bank-owned AWS account** + **EventBridge Scheduler → one-shot ECS task as `fru_sealer`** | See §5 — the separate-account part is the whole requirement, not a detail |
| M-22 | A scheduled-task facility for three things nothing in the codebase invokes | **EventBridge Scheduler → ECS scheduled tasks** | Fargate has no host cron. Two schedules: `SELECT audit.seal_create();` as `fru_sealer`, and `app.purge_abandoned_artifacts()`. Declarative and visible in the console beats a crontab on a box nobody documented |
| M-20 | One backend instance, or sticky sessions | **ECS desired count 1; ALB target-group stickiness if ever raised** | See §7 |

---

## 4. PostgreSQL: RDS, and the four things that must be verified before committing

**RDS for PostgreSQL 18. Not Aurora, not self-managed on EC2.**

**Aurora rejected** on three grounds, none of them preference. Its version support trails community
PostgreSQL, so PG18 availability is an extra dependency for no gain. Its I/O-priced model is the
wrong shape for a workload that is ~3 transactions/hour but writes 4.8 MB per profile. And its
clustered architecture buys HA that this system has already established is **not** a must-have — the
journey tolerates an outage (sessions pause per R-011, completion is terminal, the window is 12–18
months). Paying an architecture premium for an availability property we do not need, on a component
whose event-trigger behaviour I would then have to re-verify separately, is a bad trade.

**Self-managed PostgreSQL on EC2 rejected** because it reintroduces exactly the burden the managed
choice exists to remove — patching, backup discipline, storage growth, PITR — and R-010 already
records that this project's gates are local and voluntary. The research's own risk section named the
failure mode: not the deploy, but "six months later: no restore was ever tested, the disk filled, a
minor version went unpatched."

### The role model, mapped to RDS

The compose stack's bootstrap superuser has no RDS equivalent; the **RDS main user account**
(member of `rds_superuser`, not a true `SUPERUSER`) takes its place. `db/init/01-create-migrator.sh`
is therefore **dev-only**, and its two statements become a documented bootstrap step run as the main
user. The three-role split is unchanged, and maps cleanly:

| Role | Holds | AWS mapping |
|---|---|---|
| RDS main user | Bootstrap only: creates `fru_migrator`, transfers DB ownership, installs the Layer-3 event trigger | **RDS-managed master secret.** Never injected into the ECS service task |
| `fru_migrator` | Owns the database and every schema; Flyway connects as this | Customer-managed Secrets Manager secret, injected **only** into the one-shot migration task |
| `fru_app` | Owns nothing; INSERT+SELECT on `audit` only; zero DDL | Customer-managed secret, **the only DB secret in the service task definition** |
| `fru_sealer` | Seal tables only; the application never connects as this | Customer-managed secret, injected **only** into the scheduled seal task |

That separation is the point, and AWS expresses it better than a `.env` file does: the "shipped app
holds no DDL rights" model becomes a property of *which task definition references which secret ARN*,
enforced by IAM, rather than a property of a file nobody re-reads. S5-01 found live that a jar two
migrations behind starts happily and then fails per-endpoint with `permission denied` — so the
migration step being a **separate task with a separate identity** is the shape that keeps that
failure loud rather than making it disappear.

### Four provisioning gates — verify before contracting, not after

Carried forward as [UNVERIFIED] from the research and **not laundered into confidence here**. The
provider being fixed converts these from a comparison exercise into a checklist with named tests.

1. **M-4 — event triggers.** [DOC] AWS states the main user account can create, modify and delete
   event triggers on all available RDS PostgreSQL versions. **What is not stated** is that *our*
   `SECURITY DEFINER` function in schema `audit` installs unmodified. **Test:** run
   `db/post-migrate/01-audit-event-trigger.sql` as the main user and confirm the event-trigger query
   returns exactly 2 rows. **If this fails, Layer 3 of the audit append-only enforcement is silently
   absent** — `flyway:migrate` succeeds, the app starts, nothing errors, and DDL against the audit
   schema is unguarded. This is R-028's silent-skip shape, made permanent.
2. **M-4, second caveat — [DOC] RDS requires event triggers to be dropped before a major version
   upgrade.** This means **every future major upgrade silently removes Layer 3 unless the runbook
   reinstalls it.** That is a handover item the bank inherits, not a one-time step, and it must be
   written into the upgrade runbook explicitly.
3. **M-5 — `ALTER DATABASE ... OWNER TO fru_migrator`** under the main user. Plausible (it creates
   the role and is a member of it) but unconfirmed. One statement settles it.
4. **M-3 — Arabic collation.** Two separable things, and conflating them is the trap. The seeds use
   **explicit** `COLLATE "ar-x-icu"` clauses (e.g. `V0014:188`), which need only that the collation
   exists in `pg_collation` — an ICU-enabled build with a UTF8 encoding — **not** that the database's
   *default* provider is ICU. But `docker-compose.yml` sets `--locale-provider=icu --icu-locale=ar`
   at initdb, and **RDS gives no way to pass initdb arguments**. **Recommended mapping:** do not use
   the RDS-created default database; create ours explicitly —
   `CREATE DATABASE fru TEMPLATE template0 ENCODING UTF8 LOCALE_PROVIDER icu ICU_LOCALE 'ar'` — so
   dev and production agree on the *default* collation too. A silent disagreement here changes sort
   order in Arabic without failing anything. **Test:** `SELECT collname FROM pg_collation WHERE
   collname = 'ar-x-icu';` plus a `datlocprovider` check on the created database.

---

## 5. Data security, and the two places AWS relocates a risk rather than closing it

**Encryption at rest (R-026)** is **mapped to** RDS storage encryption under a **customer-managed**
KMS key — mapped, not discharged: nothing is provisioned and R-026 stays 🔴 Live — customer-managed rather than the AWS-managed default because the bank should be able to
revoke it, audit its use in CloudTrail, and hold it under its own key policy. The at-creation
constraint makes this a provisioning-time decision with no cheap do-over.

**The audit hash chain needs almost nothing from AWS.** The chain and its four enforcement
mechanisms are entirely in-database — role grants, BEFORE triggers, an event trigger, the SHA-256
chain — and they work identically on RDS provided M-4 passes. What the storage layer must provide is
narrower and easier to overlook: **a restore must reproduce the guards, not merely the rows.** An
RDS snapshot restore that loses the Layer-3 event trigger is a restore that lost an enforcement
layer while reporting success. The smoke test must therefore re-run the schema-state and role checks
**against the restored instance**, not only against the original.

**The seal export (R-037) is where AWS can quietly fail to help.** The threat the seal exists for is
someone who can rewrite the audit trail; `seal_verify()` was proven live against exactly that attack.
But a seal copied to an S3 bucket **in the same AWS account as the database** is protected by the
same credential that protects the thing it polices — an attacker with account access owns both. That
is R-037's failure mode moved up a layer, not discharged. **The requirement is therefore: S3 with
Object Lock in Compliance mode, in a separate AWS account under the bank's control, written
cross-account.** Object Lock in Compliance mode is the right primitive because [DOC] not even the
root user can shorten a retention period — which is what "append-only in practice" has to mean for an
anchor. **Governance mode would not satisfy this**, since a privileged principal can override it.

**A second irreversible provisioning constraint, in the same class as M-9's:** [DOC] Object Lock can
only be enabled **at bucket creation**, and it requires versioning that can then never be suspended.
So the seal bucket, like the RDS instance, has no cheap do-over — **both must be right at creation**,
and both belong on §4's gate list rather than being discovered at provisioning time.

**Secrets.** Five credentials in Secrets Manager (`DB_MIGRATOR_PASSWORD`, `FRU_APP_PASSWORD`,
`FRU_SEALER_PASSWORD`, `UQUDO_CLIENT_ID`, `UQUDO_CLIENT_SECRET`), referenced by ARN from task
definitions and resolved at task start, so they exist in the image never and in the task definition
only as an ARN. No secret appears in this report, in the repo, or in any log line — the standing rule
is unchanged by the host.

---

## 6. R-051 — what AWS can and cannot do

**The edge features are real and worth having: CloudFront + AWS WAF in front of `/api/*`, a WAF
rate-based rule, private subnets keeping the database off the internet, TLS everywhere.**

**None of them fixes R-051, and this requirement must not be recorded as if they did.**

`GET /api/v1/identity-scan/image/{kind}` serves passport and ID scans, the Uqudo portrait and the
Civil Registry photograph to anyone holding a profile UUID, because the customer chain is
`permitAll` — verified again this session at `SecurityConfiguration:129` (`anyRequest().permitAll()`,
`SessionCreationPolicy.STATELESS`). A WAF **cannot** distinguish a legitimate customer fetching their
own image from an attacker fetching someone else's, **because the two requests are byte-identical**.
A rate limit raises the cost of enumeration; it does not stop a caller who already holds one UUID
from retrieving that person's identity document. R-051's own text says the fix is a customer-session
credential across the whole `/api/v1/**` surface.

So the honest statement of the requirement is: **AWS supplies mitigation layers underneath the fix,
and the fix is application-level authentication that does not exist yet.** R-051 stays a hard
Phase 2 entry gate, exactly as PROJECT_PLAN.md records it, and moving to AWS changes nothing about
its status. The same applies to R-051's two extensions — the `POST /api/v1/submission/current`
disclosure (S5-13) and BL-041's device-less supersede, which lets an unauthenticated caller force a
rescan repeatedly.

---

## 7. Sizing — and what "bursty, not steady" actually changes

The campaign is ~100,000 accounts, one submission per account, over 12–18 months: an **average** of
about three submissions per hour. Sizing to the average would be wrong, and sizing to a fear of
unbounded load would be wronger. Four things follow from bursty-not-steady specifically:

**1. The burst is scheduled, which is the strongest argument against autoscaling.** The load spike is
caused by the bank sending an SMS campaign blast — the bank chooses when. A predictable burst is
pre-scaled by hand (raise the ECS desired count the day before), not reacted to by a scaling policy
that adds latency, cost and a failure mode nobody will exercise. **Autoscaling is overkill here and
is deliberately not requested.**

**2. Horizontal scale is available for the customer surface but not free for the operator surface.**
A nuance worth stating precisely: the *customer* chain is `STATELESS` (verified,
`SecurityConfiguration:134`), so customer endpoints scale horizontally with no session concern. It
is the *back-office* chain that authenticates against an in-memory Tomcat session with no Spring
Session and no Redis (`IF_REQUIRED`, `:97`). So **raising the task count above 1 requires ALB
target-group stickiness, or operators get logged out at random.** Recommended: **desired count 1**,
with headroom taken **vertically** (task CPU/memory) first; raising the count is a two-setting change
(count + stickiness) available for the campaign window. Rolling-deploy downtime of ~1 minute is
acceptable for a system whose journey already tolerates an outage.

**3. Storage is provisioned for the end state on day one, not grown into it.** 1–1.5 TB gp3 from the
start. [DOC] RDS storage can grow but **never shrink**, and at ≥400 GiB gp3's baseline IOPS clears
this workload's needs comfortably — 3 writes/hour, with 4.8 MB artifact bodies as the real I/O, is not an
IOPS-bound workload. Storage autoscaling is a safety net, not the plan.

**4. The campaign ends, and the stack should be sized to be decommissioned.** This is the thing
bursty-not-steady most changes and the thing most likely to be forgotten: after 12–18 months the
submission traffic goes to approximately zero while the *data* — including every identity document —
must be retained. **The steady state after the campaign is a read-mostly archive, not a running
campaign**, and the handover should name the post-campaign downsizing and retention posture rather
than leaving a campaign-sized bill and a campaign-sized attack surface running indefinitely.

**Starting points, offered as starting points and not derived numbers:** ECS task 1 vCPU / 2 GB;
RDS **db.m7g.large**. A burstable `t`-class would serve the average easily and is nonetheless
**not** recommended — a CPU-credit cliff during the one scheduled burst, on a bank system with no
one watching credit balances, is a failure mode removed for a few dollars. Validate both under a
load test before the campaign, not after.

**One thing that rules out a whole class of compute:** `OutboxDispatchScheduler` polls every 30s
forever (`fixedDelayString = "${fru.notification.outbox.poll-interval:30s}"`, verified). The backend
is never idle. **Scale-to-zero compute — Lambda, App Runner's idle mode — is ruled out by this
alone**, independent of anything else.

---

## 8. Operational reality — what the bank's ops team inherits

Solo developer, no ops team, delivered to a bank that operates it afterwards. Managed services are
weighted heavily for exactly one reason: **every operational duty we do not hand over is one the
bank cannot forget to do.**

| Inherited | Who runs it | Note |
|---|---|---|
| Host/VM OS patching | **Nobody — removed by Fargate** | The largest single reduction. No SSH into a host holding identity images |
| **Base-image CVE patching — rebuild and redeploy** | **The bank** | Fargate removes the *host* OS, not the buildpack base OS inside the image. This is a real inherited duty and belongs on this list by §8's own premise: an image rebuild cadence, not a no-op |
| DB patching, backups, PITR, storage growth | **AWS** | The duty most likely to be silently skipped by a team without a DBA |
| TLS certificate renewal | **AWS (ACM)** | Auto-renewing; a class of outage removed |
| **Reinstalling the Layer-3 event trigger after every major PG upgrade** | **The bank** | §4 gate 2. Silent if skipped — must be in the upgrade runbook |
| **Three post-migrate steps on every fresh environment** | **The bank** | All three fail silently if skipped (`db/post-migrate/README.md`); R-033 already tracks one |
| **Two scheduled DB jobs** (seal creation, artifact purge) | **The bank**, via EventBridge | Nothing in the codebase invokes either |
| **Verifying a restore reproduces the guards, not just the rows** | **The bank** | §5. A restore that loses the event trigger reports success |
| **Cross-account seal export account** | **The bank** | It only works if the bank, not us, controls the second account |
| Secret rotation | The bank | RDS-managed rotation is available for the master secret; the three app roles are manual |

**The honest cost of this choice:** the handover story is no longer "restore this dump and run" — it
is "reproduce this account's IAM, KMS, VPC, task definitions and schedules." That is a genuine loss
against a single-VM shape, and it is accepted because the recurring duties above are worth more than
the one-time reproduction cost. Infrastructure-as-code in the later provisioning session is what
keeps that cost one-time; a hand-clicked console build would forfeit the trade.

---

## 9. Where the stub→real swap lives in AWS

Four selector properties, each with **no default and each failing startup if unset or misspelt** —
deliberate, so a backend cannot silently run a real customer's check against a stub. **Three of the
four have real adapters wired** — core banking, Civil Registry and Uqudo (the research report's claim
that Uqudo's was missing is stale, corrected in §2). **Messaging has none and is `stub`-only by
design**, so it is a selector with nothing to select yet, not a swap awaiting configuration.

| Property | Values | AWS home |
|---|---|---|
| `fru.core-banking.client` (+ `.http.endpoint`, port 9494) | `stub` \| `http` | **Parameter Store** — not secret |
| `fru.civil-registry.client` (+ `.http.endpoint`, port 5353) | `stub` \| `http` | **Parameter Store** — not secret |
| `fru.uqudo.client` + `auth-url`/`api-base`/`jwks-url`/`issuer` | `stub` \| `http` | **Parameter Store** — endpoints are config, never baked into a build (R-007) |
| `fru.uqudo.http.client-id` / `client-secret` | — | **Secrets Manager** |
| `fru.messaging.<channel>.provider` | `stub` only until OQ-016 | **Parameter Store** |

**Mechanism — deliberately the one the repo already proves.** Keep an `application-aws.properties`
in the image whose values are `${UQUDO_CLIENT_ID}`-style placeholders — the pattern
`application.properties` already documents at `:113-114` and uses live at `:6, 12, 19, 22`; note that
`.env.example` supplies the variable *names*, not this pattern — and have the ECS task definition
inject those environment-variable names
from Secrets Manager (`secrets`) and Parameter Store. This avoids relying on Spring's relaxed
environment-variable binding rules for hyphenated keys like `fru.core-banking.client`, where a
silently-unbound property would land in the one failure mode this design exists to prevent.

**The swap itself is then a parameter-path change, not a rebuild:** `/fru/staging/*` holds `stub`,
`/fru/prod/*` holds `http`, and the same image runs in both. **The startup-failure safety property
survives onto AWS and must be proven there** — a smoke test that boots the task with a selector
deliberately unset and confirms it refuses to start, rather than assuming it would.

**Two production-config hazards to carry into the runbook:** `application.properties:51-52, 74-76,
138-139` carry synthetic stub seed values (fabricated account numbers, phone numbers, registry
outcomes) — inert once the selectors are `http`, but a production parameter path should not carry
them at all. And `fru.messaging.*.provider` has no real value to swap to yet: it stays `stub` until
the bank answers OQ-016, which is AD-002c's open half, not this decision's.

---

## 10. Filed this session

- **BL-066** — `server.forward-headers-strategy` is unset, so behind an ALB the operator session
  cookie and CSRF cookie are issued without `Secure`. Deployment-config change, verified absent.
- **PROJECT_PLAN.md** — `AD-002d (provider)` row added to the decisions log; the AD-002d bullet under
  "Open architecture decisions" updated to record the provider closed and the four sub-items open.

**Not filed, deliberately:** nothing was added to RISKS.md. R-026, R-037, R-046 and R-051 are all
still 🔴 Live and correctly described where they are; this session mapped requirements onto services
but built none of them, and marking a risk as changed on the strength of a mapping would be exactly
the misreading §6 warns against.

## 11. Review findings and dispositions

`@agent-reviewer` against the three-file diff. It confirmed every cited file:line against source,
found no secrets, and found no scope breach (no open AD settled beyond the provider question, no
evaluation of the legal question). **Eight findings, all accepted and applied** — the two that
mattered are first:

| Finding | Disposition |
|---|---|
| **"All four stub→real swaps are live today" is wrong.** `messaging/` has no `http` package and `MessageSenderConfiguration:82-84` accepts `stub` and throws on everything else | **Fixed** in §2 and §9. **Verified independently before accepting** rather than taken on the agent's word. My error: I checked the Uqudo adapter, found the research stale on it, and generalised from one selector to four without checking the other three |
| **The inherited-duty table understates Fargate.** "OS patching — nobody" ignores the buildpack base OS inside the image, whose CVE patching is a rebuild-and-redeploy the bank inherits — omitted from the one table whose purpose is to enumerate exactly that | **Fixed** — split into two rows. The finding is right on its own terms: §8's stated premise is that an unlisted duty is a forgotten one |
| **`PROJECT_PLAN.md` recorded M-16 (back-office origin/CORS) as *closed*** while the same row calls structurally identical mappings "not built". The product owner decided the provider, not the origin posture | **Fixed** in both plan-file edits and §3 — downgraded to a recommendation, and the origin posture added to AD-002d's still-open list |
| §5 said R-026 "is **discharged** by" RDS encryption — the exact word the rest of the diff avoids | **Fixed** → "mapped to", with "nothing is provisioned, R-026 stays 🔴 Live" made explicit |
| S3 Object Lock's at-creation constraint was missing, though the parallel RDS one is flagged a hard caveat | **Fixed** — added; it is the same class of irreversible provisioning decision |
| Two load-bearing AWS assertions unhedged: gp3/RDS storage growth (carries the day-one sizing) and Object Lock Compliance (carries the whole R-037 mitigation) | **Fixed** — both marked `[DOC]`, matching the discipline applied to the four RDS gates |
| "`backend/src/main/resources/static/` does not exist" — it exists, empty and untracked. Inherited verbatim from the research report | **Fixed** — the substantive claim (the jar carries no static resources) holds; the wording did not |
| `${UQUDO_CLIENT_ID}` placeholder pattern attributed to `.env.example`, which supplies variable *names* | **Fixed** — cites `application.properties:113-114` and `:6, 12, 19, 22` |

## 12. Gates

**No code changed** — documentation and plan files only. No test or analyze gate applies, and none
was run; claiming one would be false proof.

**Working-tree note:** unrelated backend work from the prior BL-039 session (identity-scan and
liveness sources plus `V0065`) was already staged when this session began. It is **not** part of this
session and was deliberately **excluded** from the commit, which names its three paths explicitly and
leaves that work staged and uncommitted for the session that owns it.
