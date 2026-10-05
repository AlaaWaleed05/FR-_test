# Bayanati — hosting requirements for a bank-operated deployment

| | |
|---|---|
| **Document** | AZT-BYN-HOST-02 |
| **Revision** | 0.1, draft for internal review. Not issued |
| **Date** | 18 September 2026 |
| **For** | Sudanese French Bank, IT infrastructure and information security |
| **Issued by** | Az Technology, solution provider |
| **Subject** | The servers, network provisions and operational duties required to host the Bayanati customer data-update solution on bank infrastructure |

**Scope of this document.** It describes a production deployment on bank infrastructure. It is a
statement of requirements, not a migration plan and not a commitment to a date. The staging
environment remains on Az Technology's cloud account and is not covered here. Supersedes
AZT-BYN-HOST-01.

---

## 1. What is being hosted

| Component | Technology | Deployed shape | Hosted by the bank |
|---|---|---|---|
| Backend | Java 21, Spring Boot 4.1.0 | One container image, one long-running process, HTTP on port 8080 | Yes |
| Back office | React, TypeScript, compiled to static files | A folder of files served by a web server. No server-side runtime | Yes |
| Database | PostgreSQL 18 | One instance, three schemas: `app`, `audit`, `ref` | Yes |
| Mobile app | Flutter, Android only | Distributed through Google Play | No |

Three points that shape everything below.

**SC-1.** The backend is the only component that connects to external services. The back office
connects only to the backend.

**SC-2.** Identity document images are stored inside the database, not in object storage. Every
storage, backup and encryption provision in this document applies to those images.

**SC-3.** Customer handsets connect directly to Uqudo during document scan and liveness. That
traffic does not traverse the bank network.

---

## 2. Conceptual diagram

```
                                  PUBLIC INTERNET
                                         │
                       customer handsets │ HTTPS 443
                                         │
                              ┌──────────▼───────────┐
                              │    Bank firewall     │   publishes 443 to SRV-APP only
                              └──────────┬───────────┘
                                         │
 ══════════════════════════════════ BANK NETWORK ══════════════════════════════════
                                         │
   ┌─────────────────┐                   │
   │  Operator PCs   │                   │
   │  (bank LAN)     │                   │
   └────────┬────────┘                   │
            │ HTTPS 443                  │
            │ (internal hostname)        │
            │                            │
   ┌────────▼─────────────────┐          │
   │  SRV-WEB                 │          │
   │  Back-office server      │          │
   │  nginx + static files    │          │
   │  NOT internet-reachable  │          │
   └────────┬─────────────────┘          │
            │                            │
            │  HTTP 8080                 │
            │  (operator API paths)      │
            │                            │
            │      ┌─────────────────────▼──────────────────┐
            └─────▶│  SRV-APP                               │
                   │  Application server                    │        outbound only
                   │  nginx front door + backend container  ├──────▶ core banking   :9494
                   │  + scheduled jobs                      ├──────▶ Civil Registry :5353
                   └──────────────────┬─────────────────────┘──────▶ Uqudo          :443
                                      │                     └──────▶ SMS gateway    :443
                                      │ TCP 5432
                                      │
                   ┌──────────────────▼─────────────────────┐
                   │  SRV-DB                                │
                   │  PostgreSQL 18                         │
                   │  reachable only from SRV-APP           │
                   └────────────────────────────────────────┘
```

Reading the diagram:

- Only SRV-APP is published to the internet, and only on port 443.
- SRV-WEB is reachable from the bank LAN only. It serves the operator interface.
- SRV-DB accepts connections from SRV-APP and from nothing else.
- Every arrow leaving SRV-APP to an external service is outbound only. None of those services
  initiates a connection into the bank.

---

## 3. Server inventory

Three servers. Physical or virtual, the requirements are the same.

| Ref | Server | Zone | Purpose |
|---|---|---|---|
| SRV-APP | Application server | Bank network, published to the internet through the firewall on 443 | Serves the customer mobile API. Runs the backend and the scheduled jobs |
| SRV-WEB | Back-office server | Bank network, internal only | Serves the operator interface to bank staff |
| SRV-DB | Database server | Bank network, restricted segment | PostgreSQL |

### 3.1 Sizing basis and safety factor

Every figure in sections 4, 5 and 6 is stated twice: a minimum, below which the system should not be
deployed, and a provisioning figure, which is what Az Technology asks the bank to allocate.

The provisioning figures deliberately exceed the calculated requirement, in most cases by a factor
of two to four. That is not padding for its own sake. Three reasons:

1. The campaign runs for 12 to 18 months and the data grows monotonically. The system is sized for
   its end state, not its opening week.
2. Load is not evenly distributed. The bank will send SMS campaign invitations in batches, and each
   batch produces a burst of customers arriving together. Average figures do not describe that day.
3. Resizing a server after deployment is a change request, an outage window and an approval cycle in
   a bank. Storage encryption in particular is configured when a volume is created and is not
   practically retrofitted to a volume holding hundreds of gigabytes of identity documents.

Where a measured figure exists from the proven deployment, it is stated alongside the requirement so
that the bank can see the margin it is being asked to provide rather than having to infer it.

---

## 4. SRV-APP, the application server

### 4.1 Hardware

| Resource | Minimum | **Provision this** | Basis |
|---|---|---|---|
| vCPU | 4 | **8** | x86-64 architecture. The container image is built and tested for x86-64. ARM is not tested |
| RAM | 8 GB | **16 GB** | The JVM takes 75% of the host or container memory limit. The printed customer form is rendered in memory with embedded document images |
| Disk | 100 GB | **200 GB** | Operating system, container images, application logs. No customer data is stored here |
| Disk type | SSD | SSD | |

Measured need at the load in 6.2 is approximately 1 vCPU and 2 GB, which is what the proven
deployment runs. The figures above carry a deliberate safety factor over that. See section 3.1.

### 4.2 Operating system and software

| Item | Requirement |
|---|---|
| Operating system | A current, supported 64-bit Linux distribution. Red Hat Enterprise Linux 9, Rocky Linux 9, Oracle Linux 9 and Ubuntu 22.04 LTS or later are all suitable. The bank's standard build is acceptable |
| Container runtime | Docker Engine 24 or later, or Podman 4 or later. **AP-1** |
| Web server | nginx 1.24 or later. **AP-2** |
| PostgreSQL client | `postgresql-client` version 18, providing `psql`. Required by the scheduled jobs in section 9 |
| Scheduler | `cron`, or a systemd timer facility |
| Certificate store | The distribution's `ca-certificates` package, kept current |
| Java | **Not required on the host.** A Java 21 runtime ships inside the container image (`eclipse-temurin:21-jre-alpine`), which runs as a non-root user and exposes port 8080 |

**AP-1.** The application is delivered as a container image. If the bank cannot run containers, the
plain jar runs under systemd with a Java 21 runtime installed on the host, but two settings that are
properties of the shipped image rather than of the application must then be supplied explicitly:
the database TLS mode and the database CA bundle path (see NW-9). The container is the supported
shape. The bank must also state how the image will reach it: a registry it can pull from, or an
offline image archive loaded by hand.

**AP-2.** nginx sits in front of the application on this server and does four jobs: it terminates
HTTPS, it forwards `/api` to the application on port 8080, it rejects malformed requests before they
reach the application, and it refuses the operator paths `/api/v1/auth`, `/api/v1/operator` and
`/api/v1/admin` on the public hostname. Az Technology supplies the configuration file.

### 4.3 Runtime characteristics the bank should know

**AP-3. One instance only.** The operator session is held in the application's own memory. There is
no external session store. Running two instances without session affinity signs operators out at
random.

**AP-4. The process is never idle.** An internal dispatcher polls the outbound message queue every
30 seconds for the life of the process. Any platform that suspends an idle process is unsuitable.

**AP-5. Autoscaling is not required.** Expected load is about three customer submissions per hour.
The only anticipated spike follows an SMS campaign sent by the bank, which is a scheduled event.

**AP-6. Health check.** `GET /actuator/health` returns the service state and is the endpoint a load
balancer or monitoring system should poll. It reports DOWN when the database is unreachable, which
is intended.

---

## 5. SRV-WEB, the back-office server

### 5.1 Hardware

| Resource | Minimum | **Provision this** | Basis |
|---|---|---|---|
| vCPU | 2 | **4** | |
| RAM | 4 GB | **8 GB** | |
| Disk | 40 GB | **100 GB** | The operator interface is 1.7 MB of static files. The rest is operating system and logs |

This server holds no data and runs no application. The figures above are deliberately well above
what the work requires, so that the bank can add logging, monitoring agents, endpoint protection
and future operator tooling to it without revisiting the specification.

### 5.2 Operating system and software

| Item | Requirement |
|---|---|
| Operating system | As SRV-APP. The bank's standard Linux build |
| Web server | nginx 1.24 or later |
| Node.js | **Not required.** The operator interface is compiled to plain files before delivery. No JavaScript runtime is installed on this server |

### 5.3 What it must do

**WB-1.** Serve the static files at `/` on an internal hostname, over HTTPS.

**WB-2.** Forward `/api` from that same hostname to SRV-APP on port 8080. This is a requirement, not
a deployment preference. The operator interface requests the relative path `/api/v1/...` and has no
configurable backend address, and the backend publishes no cross-origin policy. Serving the files
from one hostname and the API from another stops the back office working, and no setting corrects
it.

**WB-3.** Return `/index.html` with HTTP 200 for unknown paths under `/`, so that an operator
refreshing a deep link does not receive an error. This rewrite must apply to the static paths only
and must never apply to `/api`, where a genuine 404 from the backend has to reach the browser
unchanged.

**WB-4.** Not be reachable from the public internet, by any address.

Az Technology supplies the nginx configuration satisfying WB-1 to WB-3.

---

## 6. SRV-DB, the database server

### 6.1 Hardware

| Resource | Minimum | **Provision this** |
|---|---|---|
| vCPU | 4 | **8** |
| RAM | 16 GB | **32 GB** |
| Data volume | 2 TB SSD, encrypted | **3 TB SSD, encrypted** |

**DB-1.** General-purpose SSD. Magnetic storage is not suitable.

**DB-2.** Provision the full data volume at the outset rather than growing into it. The workload is
not I/O intensive: about three writes an hour, each carrying several megabytes of document images.

**DB-3.** If the bank's virtualisation platform offers burstable or CPU-credit instance classes, do
not use one. The system runs far below its average for months and then peaks during a campaign,
which is exactly when a credit limit would be reached.

### 6.2 Capacity basis

| Parameter | Value |
|---|---|
| Campaign size | About 100,000 customer accounts, one submission each, over 12 to 18 months |
| Average write load | About 3 submissions per hour |
| Data per completed profile | About 4.8 MB, including identity document images |
| Live data at full campaign | 350 to 500 GB |
| Working storage required, including local backup copies | 1 to 1.5 TB |
| **Storage to provision** | **2 to 3 TB**, carrying roughly double the calculated requirement |
| Audit rows | 6 to 10 million |
| Table partitioning | Not required |

### 6.3 Platform requirements

These three are not preferences. The system does not work correctly without them.

| Ref | Requirement | Consequence if not met |
|---|---|---|
| **DB-4** | **PostgreSQL 18.** | Porting to SQL Server is estimated at one to two weeks of work, to Oracle two to three weeks. If the bank has a mandated database standard other than PostgreSQL, Az Technology needs to know before this document is accepted |
| **DB-5** | **A database account that can execute `CREATE EVENT TRIGGER`** at deployment time. On self-managed PostgreSQL this means a genuine superuser. PostgreSQL enforces it in the engine and no `GRANT` delegates it | This installs the third layer of the audit tamper-protection. Without it the migrations still succeed, the application still starts, nothing reports an error, and changes to the audit schema are unguarded |
| **DB-6** | **An ICU-enabled PostgreSQL build**, UTF8 encoding, with the `ar-x-icu` collation present | Arabic sort order. Without ICU, reference lists and customer names sort incorrectly and nothing fails visibly |

The database is created explicitly rather than using the platform's default database:

```sql
CREATE DATABASE fru TEMPLATE template0 ENCODING UTF8
  LOCALE_PROVIDER icu ICU_LOCALE 'ar';
```

Acceptance check before the instance is handed over. Expected result: one row.

```sql
SELECT collname FROM pg_collation WHERE collname = 'ar-x-icu';
```

### 6.4 Database accounts

Four separate accounts. The separation is a security control: the running application holds no
schema-change rights at all, so a compromised application process cannot alter the schema or the
audit trail.

| Account | Rights | Used by |
|---|---|---|
| Bootstrap superuser | Creates `fru_migrator`, transfers database ownership, installs the audit event trigger | Deployment only. Never the application |
| `fru_migrator` | Owns the database and every schema | The schema migration step only |
| `fru_app` | Owns nothing, holds no schema-change rights. Read and write on `app`, mostly read-only on `ref`, and on `audit` insert and select only, so it can never amend or delete an audit record | The running application. The only database credential in the application's configuration |
| `fru_sealer` | The audit seal tables only | The scheduled seal job only. Never the application |

**DB-7.** Schema migration runs as a separate deployment step under a separate identity. The
application does not migrate at startup and refuses schema changes by design.

### 6.5 Storage encryption

| Ref | Requirement |
|---|---|
| **EN-1** | Encryption at rest at disk or volume level, covering the entire database volume. LUKS or dm-crypt, hypervisor volume encryption, or SAN encryption are all acceptable. Column-level encryption is not what is being asked for |
| **EN-2** | Backups are encrypted to the same standard. Because document images are held in the database, every backup is a complete copy of every scanned passport and national ID. An encrypted volume with unencrypted dumps beside it defeats EN-1 |
| **EN-3** | Volume encryption is configured when the volume is created. There is no inexpensive retrofit on a 500 GB database full of identity documents |
| **EN-4** | The encryption key is one the bank controls, can audit and can revoke |

---

## 7. Network

### 7.1 Firewall rules

| Ref | Direction | Source | Destination | Port | Purpose |
|---|---|---|---|---|---|
| FW-1 | Inbound | Public internet | SRV-APP | 443 | Customer mobile app |
| FW-2 | Inbound | Bank LAN, operator subnets | SRV-WEB | 443 | Operator interface |
| FW-3 | Internal | SRV-WEB | SRV-APP | 8080 | Operator API traffic |
| FW-4 | Internal | SRV-APP | SRV-DB | 5432 | Database. **From SRV-APP only** |
| FW-5 | Outbound | SRV-APP | Core banking middleware, one host | 9494 | `CheckAccount` eligibility lookup |
| FW-6 | Outbound | SRV-APP | Civil Registry, one host | 5353 | `GetCRSData` national number lookup |
| FW-7 | Outbound | SRV-APP | `auth.uqudo.io`, `id.uqudo.io` | 443 | Scan token issuance, result retrieval, signature verification keys |
| FW-8 | Outbound | SRV-APP | SMS gateway, `www.airtel.sd` | 443 | Customer one-time codes and status messages |

**NW-1. Deny everything else outbound from SRV-APP**, including the rest of the bank's internal
network. This single rule matters more than the arrangement of the servers. SRV-APP accepts traffic
from the internet and also holds a route to core banking. That combination is unavoidable in any
mobile banking application, because something has to be on both sides. What limits the consequence
is that the route leads to exactly one endpoint on one port and nowhere else.

**NW-2. Two destinations are on non-standard ports.** A default-deny egress policy blocks 9494 and
5353 with no error raised at the application, and the resulting failure looks like the bank's own
service being unavailable rather than a firewall rule.

**NW-3. SRV-DB must not be reachable from the public internet**, and must not accept connections
from SRV-WEB or from operator workstations.

**NW-4. Outbound internet access must be direct.** The application connects to Uqudo and the SMS
gateway without an intermediate proxy and does not read proxy settings from its environment. If the
bank requires outbound traffic to pass through a forward proxy, Az Technology must be told before
deployment: it is a change to the application, not a configuration setting. See BR-4.

### 7.2 Hostnames and certificates

| Ref | Requirement |
|---|---|
| **NW-5** | A permanent, bank-owned public DNS name for SRV-APP, with a publicly trusted TLS certificate, reachable from Sudanese mobile networks |
| **NW-6** | **The public DNS name must be fixed before the first Google Play release.** The mobile app compiles its API address at build time. Changing it afterwards is a new app store submission and a forced update for every customer who has already installed the app. It is not a configuration change |
| **NW-7** | A published privacy policy at a public URL on that domain, required for Google Play submission |
| **NW-8** | An internal DNS name for SRV-WEB, with a certificate that the bank's managed workstations trust. The bank's own certificate authority is sufficient here, because only bank staff reach this name |

### 7.3 Transport security

**NW-9. Application to database.** The connection runs with `sslmode=verify-full`, which encrypts
the connection and validates the certificate chain and hostname. The certificate bundle inside the
shipped image is a cloud provider's and will not validate a bank-operated PostgreSQL certificate.
The bank supplies a server certificate for SRV-DB and its issuing CA bundle, mounts that bundle into
the container, and sets `DB_CA_BUNDLE` to its path. No rebuild is required.

**NW-10. Application to core banking and the Civil Registry.** Both endpoints are HTTPS. The
application validates their certificates against the standard public trust store. If those
certificates are issued by the bank's own certificate authority, the bank must supply that CA bundle
as well, or the connections will fail at handshake. This is a separate bundle from NW-9. See BR-5.

**NW-11. Forwarded headers.** Where HTTPS terminates at nginx in front of the application, nginx
must set `X-Forwarded-Proto` and `X-Forwarded-Host`, and the deployment must enable the
corresponding application setting. Without it the operator session cookie and the request forgery
token are issued without the `Secure` attribute, and nothing warns you. If the bank places a further
terminating device in front of nginx, the cookie attributes must be stated outright in the
deployment configuration rather than inferred from headers. Verify the `Set-Cookie` headers after a
real sign-in through the completed path.

---

## 8. Secrets

Eight credentials are held outside the source repository and outside the container image, in the
bank's secret store (HashiCorp Vault, an equivalent product, or at minimum a root-owned file outside
the deployment artifact), and injected at the point of use.

| Ref | Secret | Injected into |
|---|---|---|
| SE-1 | Database bootstrap and master password | Deployment and post-migration steps only. Never the application. The most privileged credential in the system |
| SE-2 | `fru_migrator` password | The migration step only |
| SE-3 | `fru_app` password | The application |
| SE-4 | `fru_sealer` password | The scheduled seal job only |
| SE-5 | Uqudo client id | The application |
| SE-6 | Uqudo client secret | The application |
| SE-7 | SMS gateway username | The application |
| SE-8 | SMS gateway password | The application |

**SE-9.** Endpoint addresses, the Uqudo tenant identifier and the live-versus-test selectors are
configuration rather than secrets. They are injected at runtime and never built into the image. The
same image runs in every environment and only the configuration differs.

**SE-10.** No item above may appear in a build artifact, a log file, or a configuration file
committed to any repository.

---

## 9. Scheduled jobs

The bank provides a scheduled-task facility on SRV-APP. **None of these jobs is started by the
application.** Each fails by simply never running, with no error anywhere.

| Ref | Job | Runs as | Cadence | If it never runs |
|---|---|---|---|---|
| JB-1 | `SELECT audit.seal_create();` | `fru_sealer` | Daily | The audit tamper-evidence chain never advances. The protection the design promises is never produced |
| JB-2 | `SELECT app.purge_abandoned_artifacts();` | `fru_migrator` | Daily | Abandoned customers' document images are never deleted. A retention breach that grows daily |
| JB-3 | Export the audit seal to the storage in section 10 | See section 10 | Daily, after JB-1 | The seal exists only inside the database it is meant to police |

**JB-4.** Overlapping runs are safe, but the schedule should not deliberately overlap them.

**JB-5. A scope limit on JB-2, recorded so that it is not entered in the bank's data protection
register as more than it is.** `app.purge_abandoned_artifacts()` clears the stored image bodies of
profiles marked abandoned for more than 90 days. It does not clear the remainder of the personal
data, and at present no component marks a profile as abandoned. The job will purge zero rows until
Az Technology delivers that step. Scheduling it from the start is correct and costs nothing, but on
its own it does not discharge the 90-day retention commitment.

---

## 10. Audit seal storage

The system maintains a cryptographic hash chain over its audit trail and periodically produces a
seal over that chain. The seal exists to detect someone rewriting the audit trail.

**AS-1.** The seal must be written to storage that is not under the same administrative control as
the database. A seal copied to a share protected by the same credentials that protect the database
is protected by the credential it exists to police.

**AS-2.** Acceptable destinations:

- A write-once storage appliance or backup target operated by a different administrative team from
  the team operating SRV-DB.
- Object storage with an immutability lock in a mode where no administrator, including the
  platform's most privileged account, can shorten a retention period, held in a separate account
  from the one running the application.

A lock that a sufficiently privileged administrator can override does not satisfy AS-1.

---

## 11. Deployment steps, per environment

Required in order, after the schema migration, on every environment. All three complete silently if
omitted.

**DP-1. Install the audit event trigger** (`db/post-migrate/01-audit-event-trigger.sql`) as the
superuser, then verify. **Expected result: exactly two rows.** Zero rows means the third audit layer
is absent. This check belongs in the deployment runbook as a gate.

```sql
SELECT evtname, evtevent, evtenabled
  FROM pg_event_trigger
 WHERE evtname LIKE '%audit%';
```

**DP-2. Publish the reference documents.** This populates the occupation, branch, administrative
division and income source lists that the mobile app downloads. A runner inside the application jar.

**DP-3. Create the first administrator account.** A command-line runner in the same jar. Every later
operator account is created the same way until the administration screens are delivered.

**DP-4.** The application refuses to start if any live-versus-test selector is unset or misspelt.
That is deliberate: the backend must never check a real customer's account against a test service
because a configuration key was mistyped. A startup failure naming the missing property is the
system working as designed.

---

## 12. Backup and recovery

**BK-1.** Daily backups of SRV-DB, encrypted to EN-2, with point-in-time recovery enabled.

**BK-2. Restore testing, quarterly.** A restore must reproduce the protections, not only the rows. A
restore that loses the audit event trigger reports success while having silently lost an enforcement
layer. The drill re-runs the DP-1 verification and the account checks of 6.4 against the restored
instance, not only against the original.

**BK-3.** SRV-APP and SRV-WEB hold no customer data and do not require data backup. Both are rebuilt
from the container image, the static files and their configuration.

**BK-4.** The target recovery point and recovery time are a bank decision and are not assumed here.
See BR-2. Until they are stated, this document specifies a single database instance with tested
backups. A standby instance, if the bank's recovery target requires one, is a fourth server and a
replication design.

---

## 13. Ongoing operational duties

| Ref | Duty | Frequency |
|---|---|---|
| OP-1 | Operating system and PostgreSQL patching on all three servers | Ongoing |
| OP-2 | Rebuilding the container base image for published vulnerabilities. Container hosting removes the host operating system from this list. It does not remove the operating system inside the image | Quarterly, or on advisory |
| OP-3 | Database backups, point-in-time recovery, storage growth | Continuous |
| OP-4 | Restore testing per BK-2 | Quarterly |
| OP-5 | Reinstalling the audit event trigger around every major PostgreSQL upgrade, then running the DP-1 verification as the gate. Major version upgrades commonly require event triggers to be dropped first, and they do not return afterwards | Every major upgrade |
| OP-6 | The three scheduled jobs of section 9 | Daily |
| OP-7 | The deployment steps of section 11, on every new environment | Per environment |
| OP-8 | TLS certificate renewal on both hostnames | Annual, or automatic |
| OP-9 | Secret rotation | Per bank policy |
| OP-10 | Disk space and database connection monitoring | Continuous |

---

## 14. What the bank supplies or decides

| Ref | Item | Type | Blocks |
|---|---|---|---|
| BR-1 | A bank-owned public domain name with a publicly trusted TLS certificate, and an internal name for the back office | Supply | NW-5, NW-6, NW-8. The public name is permanent once the mobile app ships |
| BR-2 | The target recovery point and recovery time | Decide | Section 12. Decides whether a standby database server is required |
| BR-3 | Confirmation of the regulatory and data residency position on holding customer personal data and identity document images at the intended location | Decide | Any real customer data reaching the platform. A legal question Az Technology is not positioned to answer |
| BR-4 | Whether outbound internet traffic must pass through a forward proxy | Decide | NW-4. If it must, this is a change to the application and must be known before deployment |
| BR-5 | Whether the core banking middleware and Civil Registry certificates are issued by the bank's own certificate authority, and if so their CA bundle | Supply | NW-10 |
| BR-6 | A PostgreSQL server certificate for SRV-DB and its CA bundle | Supply | NW-9 |
| BR-7 | A database superuser at deployment time | Supply | DB-5 |
| BR-8 | Confirmation that the database platform standard permits PostgreSQL 18 | Decide | DB-4. A different mandate is one to three weeks of porting work |
| BR-9 | The separate administrative domain for the audit seal | Supply | Section 10 |
| BR-10 | Inbound firewall path and published address for SRV-APP | Supply | FW-1. Usually the longest lead time on this list |
| BR-11 | A published privacy policy URL | Supply | NW-7. Google Play submission |
| BR-12 | How the container image reaches the bank: a registry to pull from, or an offline archive | Decide | AP-1 |

---

## 15. Scope boundary

Stated plainly so that no perimeter control is recorded as closing an application-level gap.

The hosting platform contributes transport security, network isolation, rate limiting and, if the
bank wishes, a web application firewall. Those controls raise an attacker's cost. They do not
authenticate individual customers on the customer-facing API, because a request from a customer
retrieving their own record and a request from someone retrieving another person's record are
identical on the wire. Access to a stored document image is currently protected by the fact that the
profile identifier is a long random value that is not published. Az Technology records this here as
a known property of the current release rather than something the bank's infrastructure is being
asked to solve.
