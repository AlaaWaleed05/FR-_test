# Research: AD-002d hosting — technical and data-security requirements

**Date:** 2026-09-06 · **Type:** research, no implementation · **Scope:** technical half of AD-002d only

---

## 0. Reading taken, and readings not pursued

The task is ambiguous in one way, and I state the reading I took at the top as required.

**Reading taken:** "What must a production host provide, and which hosting *shape* best satisfies it, judged only on technical fit, data-security posture against our own must-haves, and what one developer can run safely." Legality, sanctions, residency law and regulatory approval are treated as a **dependency owned by the bank**, named where a shape obviously depends on one, never evaluated.

**Readings not pursued:**
- "Which specific cloud vendor and SKU should we buy" — vendor SKU/pricing selection is downstream of the bank's answers in §4, and pricing for Sudan is a payment/de-risking question (the OQ-020 pattern), not a technical one.
- "Design the customer-session authentication scheme" — R-051's real fix. Named here, deliberately not designed here; it is application work, not hosting.
- "Choose the seal export destination" — AD-002d owns it (R-037). I enumerate what the destination must *provide*; choosing it needs the bank's answer to §4 Q14.

**Constraint I am honouring:** PROJECT_PLAN.md lists AD-002d as open and blocked on OQ-001/OQ-002. Nothing below decides it. AD-005 (PostgreSQL 18), AD-004 (bytes in the database, no object store) and AD-002e (session-cookie operator auth) are **settled** and I treat them as fixed inputs. Where a finding pressures a settled decision I flag it rather than relitigate it — see §7 and §9.

---

## 1. Answer, up front

**Fr_user_update needs one Linux host with a Java 21 runtime, one PostgreSQL 18 instance with genuine superuser access, ~1–1.5 TB of encrypted storage, a TLS-terminating reverse proxy, and an off-box destination for the audit seal. That is the whole production footprint.** There is no second service, no object store, no cache, no queue broker, and — at ~3 submissions/hour average over 12–18 months — no case for autoscaling.

**On technical merit I recommend the single self-managed Linux host shape (§2b/§2c), deployed on infrastructure the bank controls**, with an identical deployment artifact whether that host physically sits in the bank's datacentre or on a bank-owned VM elsewhere. It is the only shape that satisfies every database-privilege requirement unconditionally, it is the shape AD-004 was explicitly decided *for* ("one PostgreSQL instance, restore it and run"), and the system's statefulness (in-memory HTTP sessions, one serialised audit chain) means the elasticity a managed platform sells has nothing here to act on.

**It flips to managed cloud (AWS RDS specifically, not Azure) if the bank has no datacentre and no ops team and legal clears offshore hosting.** It flips to *mandatory* on-premise if the bank closes the `CheckAccount` / `GetCRSData` endpoints to the public internet, which would make an offshore backend technically impossible, not merely legally awkward. Full flip conditions in §3.

**The single most important finding for the bank conversation:** our audit trail's Layer 3 requires `CREATE EVENT TRIGGER`, which requires actual PostgreSQL superuser. Managed Postgres is **not uniformly capable of this**. Azure Database for PostgreSQL Flexible Server cannot do it at all. That is a hard host filter, not a preference, and it must be checked before any provider is chosen.

---

## 2. REQUIREMENTS

Each derived from something concrete. `MUST` = production is impossible or unsafe without it.

### 2.1 Runtime and platform — MUST

| # | Requirement | Evidence |
|---|---|---|
| M-1 | A **Java 21** runtime (not 17, not 25) for one Spring Boot **4.1.0** fat jar | [OBSERVED] `backend/pom.xml:7-8` (`spring-boot-starter-parent` 4.1.0), `:30` (`<java.version>21</java.version>`), `:177-187` (maven-enforcer pinning `[21,22)`) |
| M-2 | **PostgreSQL 18**, one instance, three schemas (`app`, `audit`, `ref`) | [OBSERVED] `docker-compose.yml:3` (`image: postgres:18`); AD-005 row, PROJECT_PLAN.md |
| M-3 | A PostgreSQL build **with ICU support**, and a database encoding ICU supports (UTF8) | [OBSERVED] every reference seed orders under an explicit `COLLATE "ar-x-icu"` — e.g. `V0014__seed_occupation.sql:188`, `V0016:189`, `V0022:311`. [DOC] ICU collations are populated into `pg_collation` by `initdb` **if ICU support is configured**, and are ignored when the database encoding is one ICU does not support — postgresql.org/docs/18/collation.html |
| M-4 | **Genuine superuser (or a delegated equivalent) on the database** to run `CREATE EVENT TRIGGER` | [OBSERVED] `db/post-migrate/01-audit-event-trigger.sql:5-9` — "PostgreSQL requires actual superuser to run CREATE EVENT TRIGGER — a hard-coded engine check with no GRANT-based delegation, regardless of ownership or CREATEROLE." This is audit append-only **Layer 3** (persistence.md §"Append-only — the four mechanisms"), one of four layers "none sufficient alone" |
| M-5 | Ability to **create login roles and transfer database ownership** at bootstrap: `fru_migrator` (LOGIN CREATEROLE, owns the database), `fru_app`, `fru_sealer` | [OBSERVED] `db/init/01-create-migrator.sh:9-10` (`CREATE ROLE fru_migrator LOGIN CREATEROLE`; `ALTER DATABASE ... OWNER TO fru_migrator`); V0001, V0030 |
| M-6 | Migration runs as a **separate connection** (`fru_migrator`) from the application's (`fru_app`); the shipped jar holds **zero DDL rights** | [OBSERVED] `backend/src/main/resources/application.properties:4-12`; CLAUDE.md Commands — `spring-boot-flyway` is `test`-scope by design, and S5-01 found live that the jar starts happily two migrations behind and then fails per-endpoint with `permission denied` |
| M-7 | A place to run **three post-migrate steps outside Flyway**, at least one as the bootstrap superuser | [OBSERVED] `db/post-migrate/README.md` — `01-audit-event-trigger.sql` (superuser, R-028), `02-publish-reference-documents.md` (Java, profile-gated runner, R-033), `03-create-operator-account.md` (CLI runner, AD-002e). **All three fail silently if skipped** |
| M-8 | Static hosting for the back-office bundle with **SPA history fallback** | [OBSERVED] `backoffice/package.json:8` (`tsc -b && vite build` → `dist/`), `:21` (`react-router-dom`). The jar carries no static resources — [OBSERVED] `backend/src/main/resources/static/` does not exist |

### 2.2 Data security — MUST

| # | Requirement | Evidence |
|---|---|---|
| M-9 | **Encryption at rest at the disk/volume level**, covering the whole database volume | [OBSERVED] RISKS.md R-026 (🔴 Live, explicitly assigned to AD-002d); persistence.md:507-510 — "Encryption at rest stays disk/volume-level, not per column… Encrypting `body` specifically would break ordinary operator access and gains nothing against the threat that actually matters here — someone removing a disk". [DOC] postgresql.org/docs/18/encryption-options.html: data-partition encryption "prevents unencrypted data from being read from the drives if the drives or the entire computer is stolen… does not protect against attacks while the file system is mounted" |
| M-10 | **Encrypted backups**, not just an encrypted live volume | Derived: AD-004 put identity-document bytes *inside* the database ([OBSERVED] persistence.md §"Artifact storage", `app.artifact_ref.body`), so every backup is now a copy of every passport scan. An encrypted volume with plaintext dumps beside it defeats M-9 entirely |
| M-11 | The database **not reachable from the public internet** | Derived from the data class (PII + identity images at rest, CLAUDE.md Constraints) and from `fru_app` holding real grants. No repo artifact enforces this today — `docker-compose.yml:16-17` publishes `5432` to the host, which is correct for local dev and must not survive into production |
| M-12 | An **off-box, append-only-in-practice destination for the audit seal**, plus something that periodically invokes `SELECT audit.seal_create();` as `fru_sealer` | [OBSERVED] RISKS.md R-037 (🔴 Live, "assign it explicitly to AD-002d"); persistence.md:160-168 — "a seal that is created but never copied out of this database provides no protection against the threat it exists for"; `audit.seal_render()` / `audit.seal_mark_exported()` exist, **nothing calls them** |
| M-13 | **Secrets held outside the repo and outside the image**: `DB_MIGRATOR_PASSWORD`, `FRU_APP_PASSWORD`, `FRU_SEALER_PASSWORD`, `UQUDO_CLIENT_ID`, `UQUDO_CLIENT_SECRET`, plus every endpoint URL | [OBSERVED] `.env.example:8-49`; `application.properties:26-33, 99-131` — endpoints and the client selector are deliberately un-defaulted and uncommitted (R-007). CLAUDE.md: Uqudo credentials live only in a local gitignored config, "not even redacted" in a report |
| M-14 | **TLS on every public surface**, terminated somewhere the app can trust | Derived: the mobile app posts PII and forwards the Uqudo JWS; the back office authenticates with a session cookie ([OBSERVED] `SecurityConfiguration.java:93-113`) |
| M-15 | **`X-Forwarded-*` handling configured** if TLS is terminated ahead of the jar | [OBSERVED] `server.forward-headers-strategy` appears **nowhere** in `application.properties`. Without it Boot sees `http`, and the `JSESSIONID` / `XSRF-TOKEN` cookies are issued without `Secure`. This is a hosting-shaped defect waiting to happen, not a theoretical one |
| M-16 | A decision on **back-office origin**: same-origin (reverse-proxy path routing, or the bundle served by the jar) or cross-origin plus explicit CORS + `SameSite` | [OBSERVED] `backoffice/vite.config.ts:9-11` — "the backend's own CORS/SameSite story is deliberately left to AD-002d (hosting), out of scope here." This requirement is *assigned to this decision by name* |
| M-17 | **Egress** to `auth.uqudo.io` / `id.uqudo.io` (443), the middleware host on **port 9494**, the Civil Registry host on **port 5353**, and the eventual messaging gateway | [OBSERVED] `docs/components/core-banking.md:15` (port 9494), PROJECT_PLAN OQ-023 / `application.properties:129` (port 5353), `application.properties:109-114`. Non-standard ports matter: a default-deny egress policy blocks these |

### 2.3 Sizing and availability — MUST

| # | Requirement | Evidence |
|---|---|---|
| M-18 | **~350–500 GB** of profile data at full campaign; **1–1.5 TB provisioned including backups** | [OBSERVED] PROJECT_PLAN.md AD-004 row — "~4.8 MB/completed profile, ~350–500 GB at full campaign scale; 1–1.5 TB provisioned including backups" |
| M-19 | Sized for **~3 submissions/hour average**, at most 100,000 profiles, 6–10 M audit rows, ~4 GB of audit artifact bodies, over 12–18 months. **No partitioning required anywhere** | [OBSERVED] PROJECT_PLAN.md Constraints (answers OQ-004) |
| M-20 | **One backend instance, or sticky sessions.** The back office authenticates against an **in-memory Tomcat HTTP session**; there is no Spring Session and no Redis | [OBSERVED] `SecurityConfiguration.java:40-45` ("Do NOT set `server.servlet.session.timeout`, and do not adopt Spring Session without re-verifying that hazard"); `SessionCreationPolicy.IF_REQUIRED` at `:97`; persistence.md "Do NOT use… Redis". **Naive horizontal scaling logs operators out at random** |
| M-21 | A **stable, permanent public DNS name for the backend, fixed before the first store release** | [OBSERVED] `mobile/lib/core/config/app_config.dart:6-9` — the base URL is a compile-time `String.fromEnvironment('REFERENCE_API_BASE_URL')`. Changing it after release is an app-store submission, not a config change |
| M-22 | A **scheduled-task facility** on the host (cron / systemd timer / Task Scheduler) for three things nothing in the codebase invokes: `audit.seal_create()`, `app.purge_abandoned_artifacts()`, and seal export | [OBSERVED] persistence.md:105-109 and :464-470 — both are stated as "an operational requirement, not yet met by anything in this repository" |

### 2.4 NICE-TO-HAVE (explicitly not must)

- **Autoscaling / elasticity.** At ~3 submissions/hour, and with M-20 forbidding transparent horizontal scale, this buys nothing. Even a 100× burst is one modest host's work. **Overkill.**
- **Multi-AZ / hot standby.** The journey tolerates an outage: sessions pause (R-011), completion is terminal, one submission per account, a 12–18 month window. RPO/RTO is a bank question (§4 Q13), not a technical necessity we can assert.
- **Read replicas.** No read-heavy workload exists; note that [DOC] RDS cannot create event triggers on a read replica anyway.
- **Object storage / CDN.** Deliberately excluded by AD-004 [OBSERVED PROJECT_PLAN AD-004 row].
- **Kubernetes.** One stateful replica plus one database. A container orchestrator adds an operator burden with no workload to justify it — and would immediately need a health endpoint we do not have (see §7).
- **Container image build.** [DOC] `spring-boot-maven-plugin` provides a `build-image` goal, and the plugin is already declared ([OBSERVED] `backend/pom.xml:171`), so an OCI image is available if a target wants one. Not required by any shape below.

### 2.5 The R-051 boundary — stated so hosting is not mistaken for the fix

`GET /api/v1/identity-scan/image/{kind}` serves passport/ID scans, the Uqudo portrait and the Civil Registry photograph to **anyone holding a profile UUID**, because chain 2 of the security config is `permitAll` across the whole customer surface [OBSERVED `SecurityConfiguration.java:127-136`; RISKS.md R-051, 🔴 Live; PROJECT_PLAN "Phase 2 entry gates"].

A host or edge layer **can** contribute: TLS (M-14), per-IP rate limiting, a WAF, and keeping the database off the internet (M-11). BL-007 / R-048 already name a reverse-proxy throttle as "the cheapest fix that needs no schema change" for a *different* abuse surface.

**None of that fixes R-051.** A rate limit slows an attacker who has a valid UUID; it does not stop them. A WAF cannot distinguish a legitimate customer's fetch of their own image from an attacker's fetch of someone else's, because *the request is byte-identical*. R-051's own text already says so: "the real fix is a customer-session credential across the whole `/api/v1/**` surface." **Do not let a hosting feature be recorded as closing R-051.** Hosting is a mitigation layer under the fix, not a substitute for it. R-051 is a hard Phase 2 gate and stays one regardless of where this runs.

### 2.6 Messaging volume — a sizing input, not a hosting choice

CLAUDE.md requires **three independent OTPs** at channel verification, **a submission notification**, and **one message per subsequent status transition**. Floor of ~5 messages per completed customer, more for anyone who resends or hits a rejection. Over ~100k accounts that is **≥500,000 outbound messages** across the campaign.

Hosting consequences only: (a) sustained outbound HTTPS egress to the gateway, trivial in bandwidth; (b) `app.notification_outbox` rows and their audit events counted inside M-18/M-19's existing estimate; (c) the outbox dispatcher polls every 30s forever ([OBSERVED] `OutboxDispatchScheduler.java:70-72`), so the backend is never idle-scalable-to-zero. **Serverless/scale-to-zero compute is ruled out by this alone.** Provider selection is AD-002c and out of scope.

---

## 3. Three hosting shapes, evaluated against §2

### (a) Major managed cloud — managed Postgres + container/app hosting

*Concretely: AWS RDS for PostgreSQL 18 + ECS/App Runner/EC2, an ALB, S3 for the seal, Secrets Manager.*

**Technical fit against the must-haves**

- M-2 ✅ [DOC] RDS supports major version 18 (from 18.1, Nov 2025); 18.6 shipped Aug 2026 — aws.amazon.com/about-aws/whats-new/2026/08/…
- **M-4 — the deciding filter, and it is provider-specific:**
  - **AWS RDS ✅** [DOC] "All current PostgreSQL versions support event triggers, and so do all available versions of RDS for PostgreSQL. You can use the main user account (default, `postgres`) to create, modify, rename, and delete event triggers." Two caveats that touch us: cannot be created on a read replica, and **must be deleted before a major version upgrade** — docs.aws.amazon.com/AmazonRDS/latest/UserGuide/PostgreSQL.Concepts.General.FeatureSupport.EventTriggers.html
  - **Azure Database for PostgreSQL Flexible Server ❌** [DOC] "The admin user… belongs to the role **azure_pg_admin**. This role doesn't have full superuser permissions. The PostgreSQL superuser attribute is assigned to **azure_superuser**, which belongs to the managed service. You don't have access to this role." — learn.microsoft.com/en-us/azure/postgresql/configure-maintain/concepts-servers. **Layer 3 cannot be installed. This platform is disqualified for this system as built.**
  - **Google Cloud SQL ✅ (qualified)** [DOC] the `cloudsqlsuperuser` role is documented as permitting "Creating event triggers", while not holding the full `SUPERUSER` attribute — docs.cloud.google.com/sql/docs/postgres/users. [UNVERIFIED] I did not confirm this against a live Cloud SQL PostgreSQL 18 instance; the Cloud SQL PG18 version matrix was not verified.
- M-5 [UNVERIFIED] `ALTER DATABASE ... OWNER TO fru_migrator` under a non-superuser managed admin role — plausible (the admin role can typically create roles and own databases) but **not confirmed against any provider**. Must be tested before commitment, not assumed.
- M-3 [UNVERIFIED] per provider. All three almost certainly ship ICU-enabled builds, but I did not confirm `ar-x-icu` present in `pg_collation` on any of them. One `SELECT collname FROM pg_collation WHERE collname='ar-x-icu';` settles it — the same open item persistence.md already carries for the Windows dev install.
- M-9 / M-10 ✅ **strongest of the three shapes.** [DOC] RDS encrypts "the underlying storage for DB instances, its logs, automated backups, read replicas, and snapshots" with AES-256 under a KMS key, customer-managed key optional. **Load-bearing caveat: "You can only encrypt an Amazon RDS DB instance when you create it, not after."** Getting this wrong at provisioning means a snapshot-copy-and-restore migration later — docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Overview.Encryption.html
- M-11 ✅ trivially (private subnet, no public endpoint).
- M-13 ✅ a real secret manager rather than a root-owned `.env`.
- M-17 ⚠️ egress on 9494 and 5353 needs explicit security-group rules; the default-deny direction is the safe failure.
- M-20 ⚠️ the elasticity is unusable. One task, or sticky sessions on the load balancer. Nothing here is wrong, it is simply paid for and unused.
- M-22 ⚠️ no host cron. Needs a scheduled Lambda / EventBridge / ECS scheduled task connecting as `fru_sealer` — **one more moving part than a cron line**, and a second credential path into the audit schema.
- M-7 ⚠️ the superuser post-migrate step must be run from something inside the VPC, since M-11 removed the public database endpoint. A bastion or a one-shot task. Not hard; not free either.

**Data-security posture:** best-in-class on encryption at rest and backup encryption, and a genuine secrets story. Two structural weaknesses: the seal export destination lives in the *same account* as the database it exists to police (an attacker with the account owns both — R-037's exact failure mode, moved up a layer), and every byte of Sudanese customer PII and every identity-document image sits under a foreign provider's control. The second is a legal question the bank owns; I flag it as a dependency and do not weigh it.

**Solo-developer burden:** **lowest.** Patching, backups, PITR, storage growth and monitoring are the provider's. Realistically the largest single reduction in ongoing risk available.

**Costs to the project, technically:** provider lock-in on the one component we most want portable; a per-provider M-4/M-5/M-3 verification that has no analogue in the other shapes; an IAM/VPC/KMS/security-group surface that is genuinely learnable but is a fourth toolchain for a developer already carrying three (R-005); and a handover story that is no longer "restore this dump and run" but "reproduce this account's IAM, KMS, VPC and scheduled tasks."

---

### (b) Self-managed VM / VPS — one Linux host, provider-agnostic

*Concretely: one VM, LUKS-encrypted data volume, Postgres 18 from the PGDG repo or the `postgres:18` image, the jar under systemd, nginx terminating TLS.*

**Technical fit:** every must-have in §2 is satisfiable **unconditionally, with no per-provider verification**.

- M-4 ✅ we own the superuser. This is the only shape where Layer 3 is not a question.
- M-5, M-6, M-7 ✅ exactly as they run locally today — `db/init/01-create-migrator.sh` and `db/post-migrate/*` were written against precisely this shape.
- M-3 ✅ standard PGDG and the official `postgres:18` image are ICU-enabled; `docker-compose.yml:13-15` already exercises the ICU path locally.
- M-9 [DOC] dm-crypt + LUKS is the option PostgreSQL itself names for data-partition encryption — postgresql.org/docs/18/encryption-options.html. Note its documented limit: it protects a *stolen disk*, not a mounted filesystem. That is exactly the threat persistence.md scoped it to, so the fit is honest rather than accidental.
- M-10 ⚠️ **entirely on us.** `pg_dump`/`pg_basebackup` on a timer, encrypted (age/gpg), shipped off-box, and — the part that actually gets skipped — **restore-tested**. This is the single largest operational burden of this shape, made larger because AD-004 put ~350–500 GB of image bytes inside the backup.
- M-11 ✅ bind Postgres to localhost or a private interface. Note `docker-compose.yml:16-17`'s host port publish must not be copied forward.
- M-15/M-16 ✅ nginx in front gives same-origin path routing (`/` → the `dist/` bundle with SPA fallback, `/api` → `:8080`), which makes the CORS question in M-16 **disappear rather than be answered** — the cheapest available resolution of a requirement this decision was handed by name.
- M-20 ✅ one process. The constraint is satisfied by construction.
- M-22 ✅ a cron line or systemd timer, invoked as `fru_sealer`. The simplest possible discharge of M-12.
- M-17 ✅ default-allow egress; the two non-standard ports are a non-issue.

**Data-security posture:** as good as the operator makes it and no better. LUKS gives M-9; M-10 is a script that either exists or does not; unattended-upgrades or a monthly patch window is a discipline, not a platform guarantee. The one place it is *structurally better* than (a): the seal can be exported to a destination under different control entirely (a different provider, an offline copy, a printed register), which is what R-037 actually asks for.

**Solo-developer burden:** **highest, and honestly so.** OS patching, Postgres minor upgrades, backup verification, disk-full monitoring, TLS renewal, log rotation. Bounded — this is one host and one database, not a fleet — and every task is scriptable. But there is no safety net, and R-010 already records that this project has no CI and every gate is voluntary.

**Cost to the project, technically:** near zero learning cost (identical to the local dev shape), and near-zero lock-in — the entire artifact is a jar, a `dist/` directory, a dump and a `.env`.

---

### (c) On-premise / bank datacentre — first-class option

*Technically identical software to (b). The differences are ownership of the tin, the network position, and who operates it.*

**Technical fit:** **identical to (b) on every must-have**, because the software shape is identical. What changes:

- **M-17 becomes the pivotal question.** Today the two bank services are reachable from the public internet — [OBSERVED] `docs/components/core-banking.md:25`: "Round-trip 1.21–1.22 s, three calls, **from outside the bank's network**", DigiCert-issued certificate, no authentication; the Civil Registry answers the same way on port 5353 (PROJECT_PLAN OQ-023). So (a) and (b) are *currently* viable network-wise. If the bank closes those endpoints — which they arguably should, given both are unauthenticated — **only a backend inside the bank's network keeps working**, and the choice is made for us on technical grounds, not legal ones.
- **M-21 gets harder, not easier.** The mobile app must reach the backend from public mobile networks. On-prem means a DMZ, a public DNS name, a published TLS certificate and an inbound path through the bank's perimeter — a firewall-change conversation, on the bank's change-control clock, with the app-store release schedule (M-21) sitting behind it.
- M-9/M-10 ⚠️ depend entirely on what the bank's existing platform provides. A bank with a hypervisor and a backup product probably already satisfies both to a higher standard than we would build. A bank that hands over a bare VM and no backup product leaves us in shape (b) with worse network access.
- M-4/M-5/M-7 ✅ almost certainly — the bank owns the database host.
- M-22 ✅, and the seal export can genuinely leave the bank's control boundary if they want it to.
- **M-2 is the live risk:** [OBSERVED] RISKS.md R-027 (🟡) and OQ-015 — the bank may mandate a database platform standard. Cost recorded there: "1–2 weeks to port to SQL Server, 2–3 weeks to Oracle." This is the only input that reopens AD-005, and it costs nothing to ask (§4 Q6).

**Data-security posture:** potentially the strongest of the three, and the strongest *fit* to what this system stores. PII and identity-document images never leave the bank's control boundary. Encryption at rest, backup encryption, network isolation and physical security become the bank's existing controls rather than ours to invent. The audit seal can be exported to a destination the bank already runs for exactly this purpose.

**Solo-developer burden:** **structurally lowest post-handover, highest during delivery.** Every deployment is mediated by someone else's change process; debugging a production issue may require a ticket and an escort; there is no `ssh` at 2am. Against that: the bank's ops team runs it afterwards, which is the correct end state for a bank system and the one AD-004 was explicitly designed to make simple ("a materially simpler handover to a bank that will host this").

**What it costs the project:** schedule, in units nobody controls — firewall change windows, DMZ placement, certificate issuance, their security review (§4 Q11). This is the R-008 pattern: procurement and process, not code, on the critical path.

---

## 4. RECOMMENDATION

**Deploy to a single self-managed Linux host on infrastructure the bank controls — shape (b)'s software on shape (c)'s ownership.** One host, one PostgreSQL 18 instance, the jar under systemd or as a container, nginx terminating TLS and serving the back-office bundle same-origin. Build the deployment so the artifact is identical whether that host is a VM in the bank's datacentre or a bank-owned VM elsewhere; that keeps the choice of *where* a late, reversible decision instead of an early, expensive one.

**Why, against our constraints specifically and not in general:**

1. **It is the only shape where M-4 is not a question.** Layer 3 of a four-layer audit guarantee is not something to make contingent on a provider's role model, especially when one major provider ([DOC] Azure) cannot do it at all and a second requires deleting the triggers to perform a major version upgrade.
2. **AD-004 already decided for this shape.** "One PostgreSQL instance, restore it and run" is the stated reason images live in `bytea` instead of an object store. Choosing managed cloud now would keep AD-004's cost (large backups) while discarding the benefit it was bought for.
3. **The system is stateful and singular.** M-20 (in-memory sessions) and R-038 (every account check serialises on one audit chain row) both mean one instance. A platform whose value proposition is elasticity has nothing here to act on.
4. **M-16 resolves for free.** A reverse proxy serving both origins makes the CORS/`SameSite` question this decision was handed by name (`backoffice/vite.config.ts:9-11`) not arise.
5. **The handover target is a bank ops team, not us.** A dump, a jar, a `dist/` and a documented runbook is a handover. An AWS account with IAM roles, KMS keys, a VPC and scheduled tasks is a migration.

**Conditions under which this flips — each concrete and checkable:**

| Flip | Trigger | Consequence |
|---|---|---|
| **F1 → managed cloud (AWS RDS, not Azure)** | The bank has no datacentre, no hypervisor and no ops team, **and** legal clears offshore hosting | Take RDS specifically — the only one of three checked where event-trigger support is documented plainly. **Enable storage encryption at creation** [DOC — it cannot be added later]. Budget one day to verify M-3 and M-5 live before committing |
| **F2 → on-premise becomes mandatory, not preferred** | The bank closes `CheckAccount` (9494) and/or `GetCRSData` (5353) to the public internet, or fronts them with a VPN/allowlist | An offshore backend stops working. This is a **technical** disqualification of (a) and off-bank (b), independent of any legal view |
| **F3 → AD-005 reopens** | The bank answers OQ-015 with a mandated database platform other than PostgreSQL | R-027's cost applies: 1–2 weeks to SQL Server, 2–3 weeks to Oracle. Note that SQL Server ledger tables would *improve* the audit story and Layer 3 disappears as a problem — but AD-005 rejected it on licensing, and that is settled |
| **F4 → containers/orchestrator** | The bank mandates Kubernetes or an internal PaaS | Still one replica, still sticky. But **a health endpoint becomes blocking** — we have none (§7). Add `spring-boot-starter-actuator` first |
| **F5 → revisit sizing** | Scale turns out ≫ OQ-004's answer (say 1M accounts, or the campaign compressed to weeks) | One host still holds at 10×. At 100×, R-038's single audit chain is the first thing to break, not the host — and its two named remedies are schema work, not hosting |
| **F6 → no flip, but a hard stop** | R-051 is still open at go-live | Not a hosting decision. Named here so nobody reads "hosting settled" as "ready for customers" |

**Subject to:** the bank's residency, sanctions and regulatory call (OQ-001, OQ-002), which their legal function owns and which this report does not address.

---

## 5. QUESTIONS THE BANK MUST ANSWER TO CLOSE AD-002d

Self-contained; the product owner can take this list as-is.

**Where it runs**
1. **Does the bank mandate that this system runs in its own datacentre?** If yes, everything below is scoped to that; if no, does the bank have a preferred provider?
2. **Does the bank have a datacentre or private cloud we would deploy into, and what does it actually provide?** Specifically: hypervisor (VMware/Hyper-V/KVM/other), Kubernetes or a container platform, a DBA team, a standard Linux image and version.
3. **Are there residency constraints on customer PII and identity-document images?** (Bank policy and regulator position — OQ-001, OQ-002. Legal owns this; we need the answer as an input.)
4. **Who operates this after handover** — the bank's IT team, an external supplier, or the developer under a support contract? And from what date?

**Database**
5. **Can we be granted PostgreSQL superuser on the production database, or can someone with superuser run one 25-line SQL script for us at deployment and after every full restore?** *Why it matters:* one of four audit append-only layers requires `CREATE EVENT TRIGGER`, which PostgreSQL restricts to superuser with no GRANT-based delegation. Without it, DDL against the audit schema is unguarded and nothing fails loudly.
6. **Does the bank have a mandated database platform standard for delivered applications?** *(OQ-015 / R-027 — the only input that would change AD-005. Costs nothing to ask; costs 1–3 weeks to discover late.)*
7. **Can we create login roles and set database ownership at provisioning time?** We need three roles with deliberately unequal rights: an owner/migrator, an application role that owns nothing, and a seal role that can write the audit seal but not the audit trail.

**Storage, backup, recovery**
8. **Is encryption at rest available, and does it cover backups and snapshots as well as the live volume?** Identity-document images are stored inside the database, so an unencrypted backup exposes exactly what the encrypted volume protects.
9. **Can you provision ~1.5 TB, growing to that over 12–18 months?** (~500 GB live data plus backups.)
10. **What are the expected RPO and RTO?** Concretely: how much data may we lose in a disaster, and how long may the service be down? We have no basis to assume either.
11. **Who takes, verifies and restore-tests backups — and where are they stored?**

**Security and network**
12. **What security review, penetration test or approval must this pass before go-live, and how long does that take?** *(Schedule item, not a technical one — ask early.)*
13. **What network path must the backend take to `CheckAccount` (port 9494) and `GetCRSData` (port 5353)?** Both are currently reachable from the public internet with no authentication. **Is that intended to remain true in production?** If either moves behind the bank's perimeter, the backend must live inside that perimeter — this single answer can decide the hosting shape on its own.
14. **Where should the audit seal be exported to, and how often?** It must be a destination outside this database and ideally outside the same administrative control — a WORM bucket, an offline archive, a printed register, or an existing evidence store the bank already runs. *(R-037. A seal that never leaves the database it protects protects nothing.)*
15. **Who holds production secrets** — database passwords, the Uqudo tenant credentials, messaging gateway credentials — **and in what system?** Is there a bank-standard secret store, or is a root-owned file on the host acceptable?
16. **Who provisions and renews the TLS certificate for the public hostname, and what is the renewal process?**
17. **What is the production hostname for the backend?** This must be **final before the first mobile store release** — it is compiled into the app, so changing it later is a new app-store submission, not a config change.
18. **Will the back office be reachable from the public internet, or from the bank's internal network only?** This changes the edge design and the exposure of the one authenticated surface.

**Mobile distribution**
19. **In whose name are the Apple Developer and Google Play accounts held, and do they exist?** *(Related to OQ-007. Account setup and review are multi-week, procurement-shaped items — the R-008 pattern.)*

**Operations**
20. **Is there a scheduled-task facility on the host** (cron, systemd timers, an enterprise scheduler)? Three maintenance jobs must run periodically and none is invoked by the application: the audit seal, the 90-day abandoned-artifact purge, and the seal export.
21. **Who is on call, and what is the escalation path** for a production incident after handover?
22. **What monitoring, log aggregation and alerting exists** that we should ship into rather than invent?

---

## 6. CONDITIONAL DEPLOYMENT-SHAPE OUTLINE

**Not a runbook.** AD-002d is open and the concrete steps differ by target. This is the *sequence*, structured so a step-by-step runbook can be filled in once §5 Q1–Q3 are answered. Steps marked **[C]** are common to all three shapes; **[T]** varies by target.

### 6.1 Phase 0 — prerequisites (all shapes)

1. **[C]** §5 answered, at minimum Q1, Q2, Q5, Q6, Q13, Q17.
2. **[C]** R-051 has a decided fix and it is built, or an explicit, recorded product-owner acceptance exists. *This gates real customers, not the staging deploy.*
3. **[C]** The production hostname (Q17) is fixed. Nothing downstream can start without it.
4. **[T]** Target provisioned: bank VM / cloud account+VPC / physical host.

### 6.2 Phase 1 — database bring-up

| Step | Shape (b)/(c) — self-managed | Shape (a) — managed |
|---|---|---|
| 1 | Provision the host; create an encrypted data volume (LUKS or bank equivalent) **before** any data | Create the instance **with storage encryption enabled at creation** [DOC — RDS cannot add it later] |
| 2 | Install PostgreSQL 18; confirm ICU: `SELECT collname FROM pg_collation WHERE collname='ar-x-icu';` | Same query, same expectation. **Verify before committing to the provider** |
| 3 | Create `fru_migrator` as a login role with `CREATEROLE`, then `ALTER DATABASE ... OWNER TO fru_migrator` (the `db/init/01-create-migrator.sh` shape) | Same, using the provider's admin role. [UNVERIFIED] confirm the admin role can transfer database ownership |
| 4 | Bind Postgres to a private interface; no public listener | Private subnet, no public endpoint |
| 5 | Set up encrypted backups **and prove a restore** | Enable automated backups + PITR; **still prove a restore** |

### 6.3 Phase 2 — schema (identical in every shape; this is the point of AD-005)

1. Run `./mvnw flyway:migrate` as `fru_migrator`, with `DB_PORT`/`DB_NAME`/`DB_MIGRATOR_PASSWORD`/`FRU_APP_PASSWORD`/`FRU_SEALER_PASSWORD` in the environment.
   *Why explicitly:* `spring-boot-flyway` is `test`-scope by design; the shipped jar holds **no DDL rights** and will start happily against a schema two versions behind, failing per-endpoint with `permission denied` instead of at startup (CLAUDE.md, found live at S5-01).
2. **Run `db/post-migrate/01-audit-event-trigger.sql` as the bootstrap superuser.** Then run the verification query and **assert exactly 2 rows** — this is a gate, not an afterthought (R-028).
3. Run `db/post-migrate/02-publish-reference-documents.md` (the profile-gated Java publisher). Skipping it leaves the old, narrower 4-of-9-column `content_hash` in place with **nothing failing loudly** (R-033).
4. Run `db/post-migrate/03-create-operator-account.md` to bootstrap the first admin account.
5. Record which of the four ran, by whom, when. **All three post-migrate steps fail silently if skipped** — the deployment record is the only evidence they did not.

### 6.4 Phase 3 — application

1. **[C]** Build: `./mvnw package -DskipTests` → jar; `npm run build` in `backoffice/` → `dist/`.
2. **[C]** Write the environment's configuration. **This is the stub-vs-real swap point** — see §6.6.
3. **[T]** Run the jar: systemd unit / container / bank standard. **One instance** (M-20).
4. **[T]** Reverse proxy: TLS termination, `/` → `dist/` with SPA history fallback, `/api` → `:8080`. **Set `server.forward-headers-strategy`** or the session and CSRF cookies ship without `Secure` (M-15).
5. **[C]** Verify egress on 443, 9494 and 5353 reaches the four external systems (M-17).
6. **[C]** Install the scheduled jobs (M-22): `SELECT audit.seal_create();` as `fru_sealer`, the seal export to the Q14 destination, and `app.purge_abandoned_artifacts()`.
7. **[C]** Build the mobile release with `--dart-define=REFERENCE_API_BASE_URL=https://<production host>` (M-21).

### 6.5 Phase 4 — "run the tests with the client on the hosted environment"

Requirements, in general terms:

- **A reachable staging deploy** on the same shape as production, with its own database and its own credentials. Not a developer laptop with a port forward: the whole point is to exercise TLS, the reverse proxy, forwarded headers, real DNS and real network latency from a real handset on a real mobile network.
- **A handset build pointed at staging.** Because the base URL is compile-time (`app_config.dart:6-9`), staging needs its own build with its own `--dart-define`. Plan two builds, not one.
- **Physical devices.** [OBSERVED] AD-001: Uqudo supports armeabi-v7a/arm64-v8a only and does not function on an x86_64 emulator; R-008 found no cloud-device service can substitute (live camera passthrough on arm64 does not exist outside a virtual device Uqudo excludes by name). At least one physical arm64 Android handset, ideally **API 36+** so R-006 can be retired at UAT.
- **A two-person session** for the face-match case R-016's retirement left as an accepted residual.

### 6.6 The stub-vs-real config swap point — exact locations

Four external systems, four selector properties, **each with no default and each failing at startup if unset or misspelt** — a deliberate design so a backend cannot silently run a real customer's check against a stub. [OBSERVED `backend/src/main/resources/application.properties:24-139`; `CoreBankingClientConfiguration.java:50-83, 114-124`.]

| Property | Values | Real value also needs | Config class |
|---|---|---|---|
| `fru.core-banking.client` | `stub` \| `http` | `fru.core-banking.http.endpoint` (+ optional timeouts) | `corebanking/config/CoreBankingClientConfiguration.java` |
| `fru.civil-registry.client` | `stub` \| `http` | `fru.civil-registry.http.endpoint` | `civilregistry/config/CivilRegistryClientConfiguration.java` |
| `fru.uqudo.client` | `stub` \| `http` | the whole `fru.uqudo.http.*` block — `auth-url`, `api-base`, `jwks-url`, `issuer`, `client-id`, `client-secret`. Startup names **all** missing keys at once | `uqudo/config/UqudoClientConfiguration.java` |
| `fru.messaging.<channel>.provider` | `stub` only, until the bank supplies a gateway spec (OQ-016) | — (separate `fru.messaging.<channel>.enabled` axis, default true) | `messaging/config/MessageSenderConfiguration.java` |

Stub implementations live in `backend/src/main/java/sd/gov/bank/fruserupdate/{corebanking,civilregistry,uqudo,messaging}/stub/` [OBSERVED]. **Two production-config hazards worth naming in the runbook:**

- `application.properties:51-52, 74-76, 138-139` carry synthetic stub seed values (account numbers, phone numbers, registry outcomes). They are inert once the selectors are `http`, but a production config file should not carry them.
- [OBSERVED] `fru.uqudo.client=http` has **no real adapter yet** — R-034's retirement note records that "the stub is the only `UqudoClient`". Selecting `http` today fails. This is Phase 2 work, not a deployment step.

### 6.7 Smoke-test checklist — shape

A concrete checklist gets filled in once the target is known; its *shape* is fixed:

1. **Schema state** — the four post-migrate steps ran; the event-trigger query returns exactly 2 rows.
2. **Roles** — `fru_app` cannot write `audit.audit_seal`; `fru_app` cannot DDL the `audit` schema; `fru_sealer` cannot insert an `audit_chain` row. (Each already has a regression test; production is checking the *deployment*, not the code.)
3. **Reference data** — `GET /api/v1/reference/manifest` returns a manifest whose `contentHash` matches a document hashed independently.
4. **Stage 1a end-to-end** — a fabricated account number through the real middleware returns `0`/"Account not Found"; the audit chain gains request and response artifacts. **Fabricated values only** (CLAUDE.md hard rule).
5. **Civil Registry reachability** — connect and TLS only. **No probes.** OQ-023 records that a fabricated all-zeros value returned a real person's record with a photograph, and that further probing was stopped pending the bank's say-so.
6. **Uqudo** — token issuance against the correct tenant, JWKS fetched and cached.
7. **Back office** — sign in over TLS; confirm `JSESSIONID` and `XSRF-TOKEN` carry `Secure`; approve/reject a synthetic profile; confirm the four-eyes refusal fires.
8. **Mobile** — a full journey on a physical handset over a mobile network, against staging, ending in a submission and its notification.
9. **Seal** — `audit.seal_create()` runs on schedule as `fru_sealer`; `audit.seal_verify()` reports `matches`; the exported copy exists at the Q14 destination and `exported_at` is set.
10. **Backup** — take one, restore it to a scratch host, **re-run steps 1 and 2 against the restore.** A restore that loses the event triggers is a restore that lost Layer 3.

---

## 7. WHAT I COULD NOT DETERMINE

Stated explicitly; none of these is filled with a guess.

1. **[UNVERIFIED] Whether any managed provider permits `ALTER DATABASE ... OWNER TO <role>`** under a non-superuser admin role. M-5 depends on it. Needs one live test per candidate provider.
2. **[UNVERIFIED] Whether `ar-x-icu` is present in `pg_collation`** on RDS, Cloud SQL, or any specific distribution build. One `SELECT` settles it. persistence.md already carries the equivalent open item for the Windows dev install.
3. **[UNVERIFIED] Cloud SQL's event-trigger support on PostgreSQL 18 specifically.** The `cloudsqlsuperuser` documentation lists event-trigger creation [DOC], but I confirmed neither PG18 availability on Cloud SQL nor the behaviour live.
4. **[UNVERIFIED] Whether `db/post-migrate/01-audit-event-trigger.sql` runs unmodified** under RDS's main user or Cloud SQL's `cloudsqlsuperuser`. The documentation says event triggers are creatable; it does not say our specific `SECURITY DEFINER` function in schema `audit` is. Must be tested, not assumed.
5. **The bank's actual infrastructure.** Everything in §2.3 is derived from our system, not from their environment. §5 exists because I could not determine any of it.
6. **RPO/RTO.** No basis in the repo. Deliberately not assumed — an assumed RPO would silently size the backup strategy.
7. **Whether the middleware and registry endpoints stay internet-reachable.** [OBSERVED] they are today. Whether that survives a security review is unknown and is §5 Q13 — the single answer most likely to decide the shape.
8. **What the bank's security review will require.** §5 Q12. Could add weeks; could add technical requirements not in §2.
9. **Cost.** Not researched — provider pricing for a Sudanese bank is a payment/de-risking question (the OQ-020 pattern), not a technical one, and any figure I produced would be a guess.

---

## 8. RISKS OF THIS RECOMMENDATION

**If the recommendation is wrong (self-managed chosen, managed would have been better):**
- *What breaks:* backup discipline. Not the deploy — the deploy is easy. The failure mode is six months later: no restore was ever tested, the disk filled, a Postgres minor version went unpatched. R-010 already establishes that this project's gates are voluntary and unenforced; the same weakness applies to operations.
- *Cost to reverse:* **low.** `pg_dump` → restore into managed Postgres → repoint the jar. The mobile app never learns the difference if the hostname holds (M-21). AD-004's no-object-store decision is what keeps this cheap: there is one thing to move.
- *Mitigation:* make the backup-with-restore-test a named deliverable of the deployment task, not an assumed practice.

**If managed cloud is chosen instead and M-4 was not verified first:**
- *What breaks:* Layer 3 is silently absent. `flyway:migrate` succeeds, the app starts, nothing fails, and DDL against the audit schema is unguarded — R-028's exact silent-skip shape, now permanent rather than fixable by running a script.
- *Cost to reverse:* **high, and possibly unrecoverable in place.** On Azure it is not reversible at all without migrating the database elsewhere.
- *Mitigation:* §6.2 step 2 and §6.7 step 1 make it a gate. Verify before contracting, not after.

**If storage encryption is not enabled at instance creation on a managed provider:**
- [DOC] RDS: "You can only encrypt an Amazon RDS DB instance when you create it, not after." Recovery is snapshot-copy-with-encryption plus restore — a full downtime window on a ~500 GB database holding identity images.

**If the seal export destination sits in the same administrative control as the database:**
- R-037 is not discharged, only relocated. An attacker who can rewrite the audit trail can rewrite the seals beside it. §5 Q14 must be answered with a destination under *different* control, not merely a different bucket.

**If hosting is recorded as addressing R-051:**
- The most consequential misreading available here. A rate limit and a WAF change the cost of the attack, not its possibility. R-051 is a hard Phase 2 gate and remains one on every shape in §3.

**Standing risks this touches but does not close:** R-026 (🔴, encryption at rest — discharged into deployment, still open until AD-002d settles), R-037 (🔴, seal export — still ownerless), R-046 (🔴, image URL addressing — a back-office decision this report does not make), R-027 (🟡, mandated database platform — §5 Q6), R-051 (🔴).

---

## 9. CARD UPDATES

### 9.1 New card — draft `docs/components/deployment.md`

No hosting or deployment card exists ([OBSERVED] `docs/components/` holds ten cards, none for hosting). Draft below; it should not be filed as settled while AD-002d is open.

```markdown
# Component: Deployment and hosting

Status: **NOT DECIDED — AD-002d is open, blocked on OQ-001/OQ-002.** This card records
what production REQUIRES, not where it runs.
Last verified: 2026-09-06
Source: docs/sessions/2026-09-06-research-ad-002d-hosting-technical.md

## What must be hosted
- `backend/` — one Spring Boot 4.1.0 jar on Java 21. **One instance** (see "Stateful", below).
- PostgreSQL 18 — one instance, three schemas.
- `backoffice/` — a static `dist/` bundle, SPA history fallback required.
- `mobile/` — **DISTRIBUTED, not hosted.** Play Store / App Store. [OBSERVED]

## Hard host requirements
- Java 21 exactly. [OBSERVED backend/pom.xml:30, enforcer [21,22)]
- PostgreSQL 18 with an **ICU-enabled build**; every reference-list ordering uses an
  explicit `COLLATE "ar-x-icu"`. [OBSERVED V0014:188, V0016:189, V0022:311]
  [DOC postgresql.org/docs/18/collation.html — ICU collations are populated by initdb when
  ICU support is configured, and ignored when the database encoding does not support ICU]
- **Genuine superuser on the database.** `db/post-migrate/01-audit-event-trigger.sql` needs
  `CREATE EVENT TRIGGER`, superuser-only with no GRANT delegation. This is audit Layer 3 of
  four. [OBSERVED that file, lines 5-9]
  - AWS RDS: supported via the main user account. [DOC docs.aws.amazon.com/AmazonRDS/latest/
    UserGuide/PostgreSQL.Concepts.General.FeatureSupport.EventTriggers.html] Caveats: not on
    a read replica; **event triggers must be deleted before a major version upgrade.**
  - **Azure Database for PostgreSQL Flexible Server: NOT possible.** `azure_pg_admin` does not
    hold the superuser attribute; `azure_superuser` belongs to the managed service.
    [DOC learn.microsoft.com/en-us/azure/postgresql/configure-maintain/concepts-servers]
    **Disqualified for this system as built.**
  - Google Cloud SQL: `cloudsqlsuperuser` is documented as permitting event-trigger creation.
    [DOC docs.cloud.google.com/sql/docs/postgres/users] [UNVERIFIED live, and PG18
    availability on Cloud SQL unverified]
- Three DB login roles, unequal by design: `fru_migrator` (LOGIN CREATEROLE, owns the
  database), `fru_app`, `fru_sealer`. Requires `ALTER DATABASE ... OWNER TO`.
  [OBSERVED db/init/01-create-migrator.sh:9-10] [UNVERIFIED on any managed provider]
- Egress: 443 (Uqudo), **9494** (CheckAccount), **5353** (Civil Registry), messaging TBD.
  Non-standard ports — a default-deny egress policy blocks two of the four. [OBSERVED
  docs/components/core-banking.md:15; application.properties:129]

## Stateful — do not autoscale
The back office authenticates against an in-memory Tomcat HTTP session. No Spring Session,
no Redis. **A second instance logs operators out at random.** One instance, or sticky
sessions. [OBSERVED auth/config/SecurityConfiguration.java:40-45, :97; persistence.md
"Do NOT use ... Redis"]
Every account check also serialises on one audit chain row (R-038), so extra instances do
not help the one stage every customer hits.

## Data security — must-haves
- Disk/volume encryption at rest covering the DB volume. [R-026, 🔴 Live]
  [DOC postgresql.org/docs/18/encryption-options.html — protects a stolen disk, not a
  mounted filesystem; that is exactly the threat persistence.md scoped it to]
- **Encrypted backups.** Identity-document bytes live in `app.artifact_ref.body`, so a
  backup is a copy of every passport scan. [AD-004]
- Database not publicly reachable. `docker-compose.yml:16-17` publishes 5432 for local dev
  only — must not survive into production.
- Audit seal exported to a destination **outside this database, ideally outside the same
  administrative control**. [R-037, 🔴 Live] Nothing invokes `audit.seal_create()` today.
- Secrets outside the repo and the image: DB_MIGRATOR_PASSWORD, FRU_APP_PASSWORD,
  FRU_SEALER_PASSWORD, UQUDO_CLIENT_ID, UQUDO_CLIENT_SECRET, all endpoints.
  [OBSERVED .env.example; application.properties:26-33, 99-131]

## Edge requirements
- TLS on every public surface.
- **`server.forward-headers-strategy` must be set** when TLS terminates ahead of the jar.
  It is set nowhere today; without it `JSESSIONID`/`XSRF-TOKEN` ship without `Secure`.
  [OBSERVED — absent from application.properties]
- Back-office origin: same-origin via reverse-proxy path routing is the cheapest answer, and
  makes the CORS/`SameSite` question disappear rather than need answering.
  [OBSERVED backoffice/vite.config.ts:9-11 assigns this question to AD-002d by name]
- **No health endpoint exists** — `spring-boot-starter-actuator` is not a dependency.
  [OBSERVED] Any LB or orchestrator probe has nothing to call. Add it before adopting one.

## Sizing
~100k accounts, ≤100k profiles, 6-10M audit rows, ~4 GB audit artifact bodies, ~3
submissions/hour over 12-18 months. ~4.8 MB/completed profile → 350-500 GB live,
**1-1.5 TB provisioned including backups.** No partitioning anywhere.
[OBSERVED PROJECT_PLAN.md Constraints; AD-004 row]
≥5 outbound messages per completed customer (3 OTPs + submission + per-transition) →
≥500k messages campaign-wide. The outbox dispatcher polls every 30s forever, so
**scale-to-zero compute is ruled out.** [OBSERVED OutboxDispatchScheduler.java:70-72]

## Operational requirements nothing in this repo meets
- `SELECT audit.seal_create();` as `fru_sealer`, periodically. [persistence.md:105-109]
- Seal export + `audit.seal_mark_exported()`. [R-037]
- `app.purge_abandoned_artifacts()`, periodically. [persistence.md:464-470]
- Three post-migrate steps, each failing SILENTLY if skipped. [db/post-migrate/README.md]

## What hosting does NOT fix
**R-051.** The identity-scan image endpoint is unauthenticated; a UUID is the only thing in
front of a passport scan. TLS, WAF and rate limiting reduce the cost of the attack and do
not prevent it — a legitimate and an illegitimate request are byte-identical. The fix is a
customer-session credential across `/api/v1/**`. Do not record any hosting feature as
closing R-051. [RISKS.md R-051, 🔴 Live; PROJECT_PLAN "Phase 2 entry gates"]
```

### 9.2 Correction to `docker-compose.yml`'s comment — [OBSERVED]

`docker-compose.yml:13-15` says: *"ar-x-icu collation depends on ICU; locale provider can only be set at database creation time, so it is fixed here."* This conflates two things and would, if read as a hosting requirement, over-constrain the choice of host.

**Correct statement:** `COLLATE "ar-x-icu"` requires only that the server build has **ICU support** and the database **encoding** is one ICU supports. [DOC postgresql.org/docs/18/collation.html — "if support for ICU is configured, then when a database cluster is initialized, `initdb` populates the system catalog `pg_collation`…"; "ICU collations are independent of the encoding"; entries are ignored only for encodings ICU does not support.] `--locale-provider=icu --icu-locale=ar` sets the *default* collation, which **nothing in the schema relies on** — [OBSERVED] every ordering site in `V0011`, `V0014`–`V0022`, `ReferenceDocumentPublisher.java:53` uses an explicit `COLLATE "ar-x-icu"` or `COLLATE "C"`.

*Consequence:* a managed Postgres that does not let us pass `initdb` arguments is **not** disqualified on this ground. It may still be disqualified on M-4. Suggested comment text, unchanged in intent, correct in mechanism:

```yaml
# ar-x-icu collation requires an ICU-enabled Postgres build and a UTF8 encoding; every
# schema use is an explicit COLLATE "ar-x-icu", so the database's DEFAULT locale provider
# is not load-bearing. Set here anyway to keep dev close to a plain ICU install.
```

### 9.3 Line to add to `docs/components/persistence.md` — "Open verification items"

Replace the existing unchecked *"Encryption at rest for the profile database itself"* item with, or add beneath it:

```markdown
- [ ] **Superuser availability on the production database.** `db/post-migrate/
      01-audit-event-trigger.sql` needs `CREATE EVENT TRIGGER` (superuser-only, no GRANT
      delegation). Azure Database for PostgreSQL Flexible Server CANNOT do this at all
      [DOC learn.microsoft.com/en-us/azure/postgresql/configure-maintain/concepts-servers];
      AWS RDS can via the main user account [DOC docs.aws.amazon.com/AmazonRDS/latest/
      UserGuide/PostgreSQL.Concepts.General.FeatureSupport.EventTriggers.html]; Cloud SQL's
      `cloudsqlsuperuser` is documented as permitting it [DOC docs.cloud.google.com/sql/
      docs/postgres/users], unverified live. **A hard host filter for AD-002d, not a
      preference.** [2026-09-06 research]
- [ ] **`ALTER DATABASE ... OWNER TO fru_migrator` under a managed provider's admin role.**
      Required by db/init/01-create-migrator.sh:10. [UNVERIFIED on every provider]
- [ ] **`SELECT collname FROM pg_collation WHERE collname='ar-x-icu';` on the chosen
      production host.** Same open item this file already carries for the Windows dev
      install; the answer is per-build, not per-provider-family.
```

---

## 10. Noticed in passing

Not expanded, not acted on. Two are genuinely relevant to AD-002d; three are adjacent.

1. **No `spring-boot-starter-actuator`, therefore no health endpoint.** [OBSERVED — grep for `actuator|health` across `backend/` matches only a test file.] Any load balancer, container orchestrator or uptime monitor has nothing to call. Adopting shape (a) or F4 makes this blocking. Small, but sequence it before the platform, not after.
2. **No certificate pinning in the mobile app.** [OBSERVED — no match for `pinn`/`badCertificate`/`SecurityContext` in `mobile/lib/`.] AD-001's decisions log cites certificate pinning as one of three client-side security capabilities that overrode flip F2 (browser/PWA). It is not built. This matters to hosting in one direction only: **if pinning is later added, TLS certificate rotation becomes an app-store release**, which changes the answer to §5 Q16 materially. Worth filing.
3. **`docker-compose.yml` publishes Postgres on the host port.** Correct for dev, must not be copied into a production compose file. Worth one line in whatever runbook §6 becomes.
4. **The `CheckAccount` and `GetCRSData` endpoints are unauthenticated and internet-reachable.** [OBSERVED core-banking.md:22-25; PROJECT_PLAN OQ-023.] AD-002b already records this as accepted — "the bank's own arrangement… any hardening is a later improvement, not ours." Noted only because it is the fact that currently keeps shapes (a) and (b) viable, and because it is the fact most likely to change under §5 Q12's security review.
5. **`.env` exists in the repo root alongside `.env.example`.** [OBSERVED via glob — filename only; I did not open it, and no secret from it appears in this report.] Confirm it is gitignored before any deployment work touches production credentials.

---

**Sources:**
- [Event triggers for RDS for PostgreSQL — AWS](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/PostgreSQL.Concepts.General.FeatureSupport.EventTriggers.html)
- [Encrypting Amazon RDS resources — AWS](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Overview.Encryption.html)
- [Amazon RDS for PostgreSQL supports minor versions 18.6, 17.11, … — AWS (Aug 2026)](https://aws.amazon.com/about-aws/whats-new/2026/08/amazon-rds-postgresql-18-6-17-11-16-15-15-19-14-24/)
- [Amazon RDS for PostgreSQL now supports major version 18 — AWS (Nov 2025)](https://aws.amazon.com/about-aws/whats-new/2025/11/amazon-rds-postgresql-major-version-18)
- [Server Concepts for Azure Database for PostgreSQL Flexible Server — Microsoft Learn (updated 2026-07-10)](https://learn.microsoft.com/en-us/azure/postgresql/configure-maintain/concepts-servers)
- [About PostgreSQL users and roles — Cloud SQL for PostgreSQL, Google Cloud](https://docs.cloud.google.com/sql/docs/postgres/users)
- [PostgreSQL 18 documentation — Collation Support](https://www.postgresql.org/docs/18/collation.html)
- [PostgreSQL 18 documentation — Encryption Options](https://www.postgresql.org/docs/18/encryption-options.html)
