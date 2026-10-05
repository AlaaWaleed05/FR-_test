# Bayanati — hosting specification for a bank-operated deployment

**For:** Sudanese French Bank, IT infrastructure and information security
**Subject:** what the bank must provide to host the customer data-update solution ("Bayanati") entirely on its own infrastructure
**Date:** 2026-09-13
**Status:** specification for agreement. Nothing here is provisioned by the bank yet.

**Where this fits (product-owner ruling, 2026-09-14).** The solution runs on **our AWS account**
today and V1 ships from there. This document exists so the bank can decide, in its own time,
whether it wants to host the solution on its own infrastructure instead — **it is a set of
requirements, not a migration plan and not a commitment to a date.** If the bank never acts on
it, nothing in the delivery changes. It is a separate matter from the AWS account handover in
`docs/road-to-production.md` §3.6, which transfers the account we already run.

---

## 1. What is being hosted

Three deployable parts, plus one database:

| Part | Technology | Shape |
|---|---|---|
| **Backend** | Java 21, Spring Boot 4.1.0, one executable jar (shipped as an OCI container image) | One long-running process, HTTP on port 8080 |
| **Back office** | React + TypeScript, compiled to a static bundle (`dist/`) | Static files behind a web server; no server-side runtime |
| **Database** | PostgreSQL 18 | One instance, three schemas: `app`, `audit`, `ref` |
| **Mobile app** | Flutter (**Android only** — iOS is not part of the product) | Distributed through Google Play; not hosted by the bank |

The backend is the only tier the bank hosts that talks to external services. The back office talks
only to the backend. Customer handsets additionally reach Uqudo directly during document scan and
liveness — the scan SDK runs on the device — so that traffic leaves the customer's mobile network,
not the bank's.

**Identity document images are stored inside the database**, not in object storage. That is a
deliberate design decision, and it drives the storage, backup and encryption requirements in
sections 4 and 6.

---

## 2. Compute

### 2.1 Application host

| | Minimum | Recommended | Notes |
|---|---|---|---|
| vCPU | 2 | 4 | |
| RAM | 4 GB | 8 GB | The JVM is configured to take 75% of the container or host memory limit |
| Disk | 50 GB | 100 GB | OS, container images, logs. No application data |
| OS | Any current Linux with a container runtime | | |

**One instance only.** The back-office operator session is an in-memory HTTP session — there is no
Spring Session and no Redis. Running two backend instances without sticky sessions logs operators
out at random. If the bank wants more than one instance, the load balancer must be configured with
session affinity on the back-office path.

**The process is never idle.** An internal dispatcher polls the outbound message queue every 30
seconds for the life of the process. Any scale-to-zero or consumption-billed compute model is ruled
out by this alone.

**Autoscaling is not required and is not requested.** Average load is about three customer
submissions per hour. The only load spike is caused by the bank itself sending an SMS campaign
blast, which is a scheduled event — pre-scale by hand the day before rather than react to it.

### 2.2 Runtime

Java 21 exactly. Not 17, not 25 — the build enforces the range `[21,22)` and refuses to compile
outside it. The bank does not need to install a JDK: the runtime ships inside the container image
(`eclipse-temurin:21-jre-alpine`), which runs as a non-root user and exposes port 8080.

If the bank prefers not to run containers, the plain jar runs under `systemd` with a Java 21 JRE on
the host — but note that the enforcer constrains the *build*, not the runtime, so a jar will start
happily on a wrong JRE and the host's Java version becomes something the bank has to hold still.
Two further settings are properties of the shipped image rather than of the application (see 5.4),
so a non-container deployment must supply them explicitly. The container is the recommended shape
for both reasons.

### 2.3 Health check

`GET /actuator/health` returns a single word and is the endpoint a load balancer or monitoring
system should poll. It reports DOWN when the database is unreachable, which is intentional — an
instance that cannot reach the database should not be receiving traffic.

---

## 3. Database

### 3.1 Non-negotiable platform requirements

These three are not preferences. The system does not work correctly without them.

| # | Requirement | Consequence if not met |
|---|---|---|
| **D-1** | **PostgreSQL 18**, self-managed or managed | Porting to SQL Server is estimated at 1–2 weeks of work; to Oracle, 2–3 weeks. If the bank has a mandated database standard other than PostgreSQL, we need to know now — it reopens a settled design decision |
| **D-2** | **A database account that can execute `CREATE EVENT TRIGGER`** at deployment time | On self-managed PostgreSQL this means an actual superuser: PostgreSQL enforces it with a hard-coded engine check and there is no `GRANT` that delegates it. On a managed platform a delegated admin role is sufficient *if* it can execute the statement (AWS RDS's main user can, despite not holding the `SUPERUSER` attribute). This installs Layer 3 of the audit tamper-protection. Without it, migrations still succeed, the application still starts, nothing errors — and DDL against the audit schema is unguarded |
| **D-3** | **An ICU-enabled PostgreSQL build**, UTF8 encoding, with the `ar-x-icu` collation present | Arabic sort order. Without ICU, reference lists and customer names sort wrongly and nothing fails |

**D-2 disqualifies one common platform.** *Azure Database for PostgreSQL Flexible Server cannot host
this system.* Its admin account belongs to `azure_pg_admin`, which is not superuser; the superuser
attribute is held by a managed role customers cannot access. Event triggers cannot be installed
there. If the bank's cloud standard is Azure, the database must be PostgreSQL on a VM rather than
the managed service. AWS RDS for PostgreSQL and self-managed PostgreSQL both satisfy D-2.

The database must be created explicitly rather than using a platform's default database:

```sql
CREATE DATABASE fru TEMPLATE template0 ENCODING UTF8 LOCALE_PROVIDER icu ICU_LOCALE 'ar';
```

Verification query to run before accepting the instance:

```sql
SELECT collname FROM pg_collation WHERE collname = 'ar-x-icu';   -- expect 1 row
```

### 3.2 Sizing

| | Value | Basis |
|---|---|---|
| Campaign size | ~100,000 customer accounts, one submission each, over 12–18 months | Agreed scope |
| Average write load | ~3 submissions/hour | Derived from the above |
| Data per completed profile | ~4.8 MB (identity document images are in the database) | Estimated |
| **Live data at full campaign** | **350–500 GB** | |
| **Storage to provision, including backups** | **1–1.5 TB** | |
| Audit rows | 6–10 million | |
| Partitioning | Not required anywhere | |

Provision the full 1–1.5 TB on day one rather than growing into it. The workload is not IOPS-bound
— three writes an hour, with multi-megabyte image bodies as the real I/O — so general-purpose SSD is
sufficient. Spinning disk is not.

### 3.3 Database host sizing

| | Minimum | Recommended |
|---|---|---|
| vCPU | 2 | 4 |
| RAM | 8 GB | 16 GB |
| Data volume | 1.5 TB SSD, encrypted | |

Avoid burstable or credit-based instance classes. The workload sits far below its average for months
and then bursts once, during a campaign blast — which is exactly when a CPU-credit cliff would hit,
on a system with nobody watching credit balances.

### 3.4 Database roles

Four separate database accounts. The separation is a security control rather than a convention: the
shipped application holds **zero DDL rights**, so a compromised application process cannot alter the
schema or the audit trail.

| Role | Holds | Used by |
|---|---|---|
| Bootstrap superuser | Creates `fru_migrator`, transfers database ownership, installs the audit event trigger | Deployment only. Never the application |
| `fru_migrator` | Owns the database and every schema | The schema migration step only |
| `fru_app` | Owns nothing and holds no DDL rights. Ordinary read/write on the `app` schema, mostly read-only on `ref`, and in the `audit` schema `INSERT` and `SELECT` only — it can never amend or delete an audit row | **The running application. The only database credential in the application's configuration** |
| `fru_sealer` | The audit seal tables only | The scheduled seal job only. Never the application |

Schema migration must run as a **separate step with a separate identity**, not by the application at
startup. The application is built to refuse DDL, and a jar running two migrations behind starts
happily and then fails per-request with `permission denied`. Keeping migration a distinct, visible
step is what keeps that failure loud.

---

## 4. Storage and encryption

| # | Requirement |
|---|---|
| **E-1** | **Encryption at rest at the disk or volume level**, covering the entire database volume. LUKS/dm-crypt, hypervisor volume encryption, SAN encryption, or a managed platform's storage encryption are all acceptable. Column-level encryption is explicitly *not* what is being asked for |
| **E-2** | **Backups must be encrypted too.** Because the identity document images live inside the database, every backup is a complete copy of every scanned passport and national ID. An encrypted volume with plaintext dumps beside it defeats E-1 entirely |
| **E-3** | On a managed platform, storage encryption is usually **settable only at instance creation**. There is no cheap retrofit on a 500 GB database full of identity documents. This one has to be right at provisioning |
| **E-4** | The encryption key should be one the bank controls, can audit and can revoke — not a platform-default key |

---

## 5. Network

### 5.1 Outbound — the part most likely to be missed

The backend must reach four external services. **Two of them are on non-standard ports.** A
default-deny egress policy blocks those silently, and the resulting failure looks like the bank's
own service being down.

| Destination | Port | Purpose |
|---|---|---|
| Uqudo eKYC (`auth.uqudo.io`, `id.uqudo.io`) | **443** | Document scan token issuance, result retrieval, signature verification keys |
| Core banking middleware (bank-hosted) | **9494** | `CheckAccount` eligibility lookup |
| Civil Registry (`GetCRSData`) | **5353** | National number lookup |
| SMS gateway (`www.airtel.sd`) | **443** | Customer OTP and status messages |
| PostgreSQL | 5432 | Internal only — see 5.3 |

**A question for the bank.** The core banking middleware and the Civil Registry are today reachable
from outside the bank's network. If the bank intends to close those endpoints — and both are
unauthenticated, so there is a good argument that it should — then the backend has to sit *inside*
the bank's network, and the hosting location is decided on those grounds rather than on preference.

Message volume over the campaign: the journey is designed to send up to three independent OTP codes
at channel verification, one submission notification, and one message on every subsequent status
change — about five messages per completed customer, so an upper bound of **500,000+ outbound
messages** across the campaign. At first release SMS is the only channel that is actually delivered,
so the real figure is lower; size the SMS contract against the upper bound rather than the average.
The bandwidth is trivial; the route simply has to stay available.

### 5.2 Inbound

| Surface | Requirement |
|---|---|
| **Mobile API** | A **permanent, bank-owned public DNS name** with a publicly trusted TLS certificate, reachable from Sudanese mobile networks. On-premise hosting means a DMZ placement, a published certificate and an inbound firewall path |
| **Back office** | Does not need to be reachable from the public internet, but it **must be served from the same hostname as the API** — see 5.3, which is a requirement rather than a preference |

**The DNS name must be fixed before the first Google Play release.** The mobile app compiles its API
address in at build time. Changing it afterwards is a new app store submission and a forced update
for every customer who already installed the app — not a configuration change.

Required alongside the domain, for Google Play: a **published privacy policy at a public URL** on
that domain.

### 5.3 Internal

- The database must **not be reachable from the public internet**. Private network segment, no
  public endpoint, and a firewall rule permitting 5432 only from the application host.
- **Required topology: one reverse proxy, one hostname.** `/` serves the back-office static bundle,
  `/api` proxies to the backend on 8080. This is not a preference. The back-office bundle calls the
  relative path `/api/v1/...` and has no configurable API base address, and **the backend ships no
  CORS configuration at all** — so serving the bundle and the API from different hostnames breaks
  the back office outright, with no setting that fixes it. Keeping them same-origin is also what
  avoids having to relax cookie policy on an operator's session cookie.
- The static bundle needs **SPA history fallback**: a 403 or 404 for an unknown path returns
  `/index.html` with HTTP 200.

### 5.4 TLS

- TLS on every public surface.
- If TLS terminates at a proxy or load balancer ahead of the application — the normal case — that
  proxy must set `X-Forwarded-Proto` and `X-Forwarded-Host` correctly, and the deployment must
  enable the matching application setting. Without it the operator session cookie and the CSRF token
  are issued without the `Secure` attribute, and nothing warns you.
- **With more than one terminating hop in front of the application, the forwarded-header mechanism
  is not enough.** We found this live: with a CDN in front of a load balancer, the load balancer
  truthfully reports its own hop as plain HTTP, the application believes the request was
  unencrypted, and both cookies come back without `Secure` anyway. The fix is to state the cookie
  attributes outright in the deployment configuration rather than let them be inferred. Any bank
  edge with two hops hits the identical defect, so verify the `Set-Cookie` headers after a real
  sign-in through the finished edge, not in a test environment with one hop.

**Application-to-database TLS.** The connection must run with `sslmode=verify-full`, which encrypts
*and* validates the certificate chain and hostname. Two things the bank needs to know:

- `verify-full` and the fail-closed CA bundle are set in the **deployment profile of the shipped
  container image**, not in the application's base configuration. A deployment that runs the plain
  jar without that profile connects with the JDBC driver's default (`prefer`) and will silently fall
  back to plaintext. A non-container deployment must set the SSL mode explicitly.
- The CA bundle baked into the image is **Amazon's RDS trust store**, which will not validate a
  bank-operated PostgreSQL certificate. The bank must supply a server certificate for its own
  PostgreSQL instance and its CA bundle, mount that bundle into the container, and point the
  `DB_CA_BUNDLE` setting at it. That override exists precisely so this needs no rebuild.

---

## 6. Secrets

Eight credentials must be held **outside the source repository and outside the container image** —
in the bank's secret store (HashiCorp Vault, a cloud secret manager, or at minimum a root-owned file
outside the deployment artifact) — and injected at the point of use:

| Secret | Injected into |
|---|---|
| Database bootstrap / master password | Deployment and the post-migrate steps only. Never the application. The most privileged credential in the system |
| `fru_migrator` password | The migration step only |
| `fru_app` password | The application |
| `fru_sealer` password | The scheduled seal job only |
| Uqudo client id | The application |
| Uqudo client secret | The application |
| SMS gateway username | The application |
| SMS gateway password | The application |

Endpoint URLs, the Uqudo tenant identifier and the live-versus-stub selectors are **configuration,
not secrets**, and are also injected at runtime rather than baked into the image. The same image runs
in staging and in production; only the configuration differs. None of the above may appear in a
build artifact, a log, or a configuration file that is committed anywhere.

---

## 7. Scheduled jobs — required, and nothing in the application runs them

The bank must provide a scheduled-task facility (cron, a systemd timer, or the platform's scheduler)
for two database jobs plus an export. **None of them is invoked by anything in the application.**
All of them fail silently, by simply never running.

| Job | Runs as | Suggested cadence | If it never runs |
|---|---|---|---|
| `SELECT audit.seal_create();` | `fru_sealer` | Daily | The audit tamper-evidence chain never advances. The protection the design promises is never actually produced |
| `SELECT app.purge_abandoned_artifacts();` | `fru_migrator` | Daily | Abandoned customers' passport images are never deleted. A data-retention breach that grows every day |
| Seal export to off-box storage | See section 8 | Daily, after the seal | The seal exists only inside the database it is meant to police |

Overlapping runs are safe by design, but the schedule should not deliberately overlap them.

**One limitation to record honestly, because a data-protection reviewer will otherwise read the
purge job as more than it is.** `app.purge_abandoned_artifacts()` nulls the stored **image bodies**
of profiles that have been marked abandoned for more than 90 days. It does two things less than the
retention rule needs: it does not null the rest of the profile's personal data, and — more
importantly — **nothing in the system currently marks a profile as abandoned**. There is no
abandonment sweep yet. Scheduling this job today is correct and costs nothing, but it will purge
zero rows until that sweep exists. It is on our side to build, not the bank's, and it should not be
recorded as discharging the 90-day retention commitment on its own.

---

## 8. The audit seal destination — a separate administrative domain

The system keeps a cryptographic hash chain over its audit trail and periodically produces a *seal*
over that chain. The seal exists to detect someone rewriting the audit trail.

**The seal must be written to storage that is not under the same administrative control as the
database.** A seal copied to a share protected by the same credentials that protect the database is
protected by the credential it exists to police — whoever holds one holds both.

Acceptable shapes:

- Object storage with an **immutability or WORM lock in a compliance mode**, meaning one where not
  even the platform's root account can shorten a retention period, in a **separate account or
  tenancy** from the one running the application.
- An on-premise WORM appliance or backup target operated by a **different administrative team** from
  the one running the application database.

A governance-mode lock, where a sufficiently privileged administrator can override retention, does
not satisfy this.

One note for cloud platforms: object lock is usually settable **only at bucket creation**, and it
requires versioning that can then never be suspended. Like storage encryption in E-3, it has no
cheap retrofit.

---

## 9. Deployment procedure — three steps that fail silently if skipped

Every fresh environment needs these, in order, after the schema migration. All three succeed by
omission: nothing errors if you forget them.

1. **Install the audit event trigger** (`db/post-migrate/01-audit-event-trigger.sql`), **as the
   superuser**. Then verify:

   ```sql
   SELECT evtname, evtevent, evtenabled FROM pg_event_trigger WHERE evtname LIKE '%audit%';
   ```

   **Expect exactly 2 rows.** Zero rows means audit Layer 3 is absent. This check belongs in the
   deployment runbook as a gate, not an afterthought.

2. **Publish the reference documents** — this populates the occupation, branch,
   administrative-division and income-source lists that the mobile app downloads. A profile-gated
   runner inside the application jar.

3. **Create the first administrator account** — a profile-gated CLI runner in the same jar. Every
   later operator account is created the same way until the admin screens are built.

---

## 10. Environments

At least two, running the **same container image** and differing only by configuration:

| Environment | Purpose | Sizing |
|---|---|---|
| Staging | Integration testing against the bank's real middleware and registry | About half of production; 100 GB storage is enough |
| Production | | Sections 2 and 3 |

The application refuses to start if any of its live-versus-stub selectors is unset or misspelt. That
is deliberate — a backend must never run a real customer's account check against a stub because
somebody mistyped a configuration key. A startup failure naming the missing property is the system
working as designed, not a defect.

---

## 11. Operational duties the bank inherits

This is the complete list. Each is something that degrades silently if it is forgotten.

| Duty | Frequency | Why it matters |
|---|---|---|
| OS and PostgreSQL patching | Ongoing | Standard |
| Container base-image rebuild for CVEs | Quarterly, or on advisory | Container hosting removes the *host* OS from your list. It does not remove the OS inside the image |
| Database backups, point-in-time recovery, storage growth | Continuous | The duty most often skipped silently without a DBA |
| **Restore testing** | Quarterly | See below |
| **Reinstalling the audit event trigger around every major PostgreSQL upgrade** | Every major upgrade | Managed PostgreSQL platforms generally require event triggers to be **dropped before** a major version upgrade, and they do not come back afterwards. Audit Layer 3 then disappears silently. The runbook step is: drop before, reinstall after, then run the two-row verification query as the gate. Confirm whether your platform requires the drop; on self-managed PostgreSQL it may not, but verify the two rows after any upgrade regardless |
| The three scheduled jobs (section 7) | Daily | Each accrues damage every day it does not run |
| The three post-migrate steps (section 9), on every new environment | Per environment | All three fail by omission |
| TLS certificate renewal | Annual, or automatic | |
| Secret rotation | Per bank policy | |
| Disk-space and connection monitoring | Continuous | |

**On restore testing specifically:** a restore must reproduce the *guards*, not merely the rows. A
snapshot restore that loses the audit event trigger reports success while having quietly lost an
enforcement layer. The drill has to re-run the schema-state and role checks against the restored
instance, not only against the original.

---

## 12. What the bank must supply or decide

| # | Item | What it blocks |
|---|---|---|
| 1 | A **bank-owned domain** with a publicly trusted TLS certificate | The first Google Play release. Permanent once the app ships |
| 2 | A **published privacy policy URL** on that domain | Google Play submission |
| 3 | Confirmation of the **regulatory and data-residency position** — whether customer PII and identity documents may reside where the bank intends to host them | Any real customer data reaching the platform. A legal question we are not positioned to answer |
| 4 | Whether the **database platform standard** permits PostgreSQL 18 | See D-1. A different mandate is 1–3 weeks of porting work |
| 5 | Whether the core banking middleware and the Civil Registry will **remain reachable** from where the backend will sit | Section 5.1. May decide the hosting location on technical grounds |
| 6 | The **separate administrative domain** for the audit seal | Section 8 |
| 7 | A genuine **database superuser** at deployment time | Section 3.1, D-2 |
| 8 | A **PostgreSQL server certificate and CA bundle** | Section 5.4 |
| 9 | Inbound firewall path and DMZ placement, for on-premise hosting | Section 5.2. Usually the longest lead-time item on this list |
| 10 | The expected **RPO and RTO** — how much data the bank is willing to lose in a failure, and how quickly the service must be back | Section 11. We have no basis to assume either, and an assumed figure would silently size the backup and recovery strategy for you |

---

## 13. What hosting does not solve

Stated plainly so that no perimeter control gets recorded as closing an application-level gap.

The platform can and should contribute TLS, network isolation, rate limiting and a web application
firewall. **None of that replaces the customer-session authentication on the customer-facing API**,
which is a tracked pre-production item on our side rather than the bank's. A rate limit raises an
attacker's cost; it cannot distinguish a customer fetching their own document from someone fetching
another person's, because the two requests are identical on the wire.

We are not asking the bank to solve that. We are asking that it not be recorded as solved by a
firewall rule.

---

## 14. Reference: the current deployment

For comparison, the solution has been proven end to end on a cloud deployment of the shape below. It
is offered as a reference topology, not as a recommendation of any particular vendor.

| Component | As deployed |
|---|---|
| Backend | Container, 1 vCPU / 2 GB, one instance, behind an internal load balancer |
| Back office | Static bundle behind a CDN, on the same hostname as the API |
| Database | Managed PostgreSQL 18.6, 20 GB growing to 100 GB at staging sizing, encrypted under a customer-managed key, private subnet, no public endpoint, 7-day backup retention |
| Secrets | Managed secret store, referenced by identifier from the task definition and resolved at process start |
| Configuration | Parameter store, one path per environment |

A full customer journey — account check, OTP, document scan, liveness, Civil Registry lookup,
submission, then operator review and approval — was completed on this stack from a real handset,
against the bank's real middleware and the real Civil Registry.
