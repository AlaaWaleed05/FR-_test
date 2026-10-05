# AD-005 — Persistence technology and data model for the profile store, the audit trail, and the reference-data registry

**Status:** research, for review. Not a decision. This report absorbs **AD-002g** (audit store) — no separate audit-store recommendation is produced.
**Date:** 2026-08-22
**Prepared against:** PROJECT_PLAN.md, EXECUTION_PLAN.md, RISKS.md, BACKLOG.md, docs/journeys/customer.md, docs/journeys/operator.md, docs/components/uqudo-sdk.md, docs/components/uqudo-api-findings.md, `../FIB/backend server fib/utility/{pom.xml,src/main/resources/application.yml}`

---

## Reading of the question

The defensible reading I pursued: **one relational database, owned by us, holding three logically separate stores** (mutable profile, append-only audit, versioned reference data) in three schemas of one instance, with the mobile app holding a *subset* mirror shaped for offline entry and resume — not a replica.

Readings I did **not** pursue, and why:

- *Two physically separate database instances (profile and audit).* The journey requires a status transition and its audit record to be atomic (customer.md "Every status transition — the governing rule"). Across two instances that needs two-phase commit or a saga. "Structurally separate" is satisfied by separate schemas, separate roles and separate privileges; it does not require separate servers. Flagged in Risks as the thing to revisit if a regulator demands physical separation.
- *Polyglot persistence (relational profile + append-only log store such as an immutable object-store ledger).* Rejected as a reading because it doubles the operational surface for a solo developer and breaks the same atomicity.
- *Event sourcing the profile.* Rejected as a reading: the journey specifies a mutable current profile plus a separate append-only trail, which is a deliberate split (customer.md "The audit trail — separate from the profile"), not an invitation to derive the profile from events.

---

## 1. Answer

**Use PostgreSQL 18 for all three stores, in one instance, three schemas (`app`, `audit`, `ref`), two database roles (`fru_migrator` owns everything; `fru_app` owns nothing and holds only INSERT/SELECT on `audit`). Flyway 12 for migrations. Use `drift` + `sqlite3` 3.x for the mobile local store.**

The reasoning that actually decides it, under *our* stated constraints rather than in general:

1. **Transactional DDL.** PostgreSQL runs every Flyway migration inside a transaction and rolls a failed one back automatically; MySQL, MariaDB and Oracle cannot, and SQL Server only partially. [DOC Redgate] For a solo developer with no CI (R-010) and no DBA, a half-applied migration at 23:00 is the failure mode that costs a day. This is the single largest operational-burden difference between the options and it is not a matter of taste.
2. **Licence and portability.** PostgreSQL Licence, permissive, no per-core cost imposed on the bank, and it runs on the Windows dev box, on any cloud we pick, and on-premise on whatever the bank runs. SQL Server would make the bank buy licences we chose for them. Oracle Free caps at 12 GB user data / 2 GB RAM / 2 cores [DOC], which a national campaign exceeds, so "Oracle" means "Oracle SE2/EE licences", i.e. the same imposition.
3. **The three requirements that are awkward everywhere are least awkward here.** Exactly-one-primary income source → partial unique index. Byte-identical raw artifact storage → `bytea` with transparent TOAST compression. Multi-instance outbox polling without a broker → `FOR UPDATE SKIP LOCKED`. Server-side SHA-256 without an extension → built-in `sha256(bytea)` [DOC].
4. **Where PostgreSQL genuinely loses:** it has no engine-level immutable table. SQL Server 2022 append-only ledger tables block UPDATE and DELETE *at the API level* and compute a SHA-256 Merkle chain and database digests in the engine [DOC Microsoft Learn], across all editions including Express. That is a real, material advantage for requirement 4, and it is roughly 300 lines of trigger and verification code that we will have to write and test ourselves. I still do not recommend it, for the licence and platform reasons above, and because Microsoft's own documentation concedes that ledger "can't prevent such attacks but guarantees that any tampering will be detected when the ledger data is verified" *against digests stored externally* [DOC] — the external-anchoring operational burden exists either way.

---

## 2. Options

Scale assumption used throughout, stated because OQ-004 is unanswered: **up to 3 million accounts, campaign spread over 12–18 months, average ~2 submissions/second, peak ~20/second, 60–100 audit events per profile → up to ~250 million audit rows and ~150 GB of audit artifact bodies** (raw JWS ≈ 5–30 KB, raw Civil Registry response ≈ 2–10 KB, `ProcessOmniCheckAct` request/response ≈ 1 KB). Images are excluded (AD-004). **[UNVERIFIED — every figure here is my assumption, not a supplied number.]** Where this breaks is stated per option.

### Option 1 — PostgreSQL 18 — **RECOMMENDED**

| Dimension | Assessment |
|---|---|
| Local dev on Windows 10 Pro | EDB Interactive Installer covers PG 18 (listed for Windows Server 2025/2022) [DOC postgresql.org/download/windows]. **[UNVERIFIED whether the PG18 installer is supported on Windows 10 desktop — the EDB table lists server SKUs only.]** Mitigation, and my preference regardless: run PostgreSQL in Docker Desktop/WSL2, which also matches the cloud and bank targets. Zip binaries are the no-Docker fallback. |
| Testing on a cloud we pick | Managed PostgreSQL on AWS RDS/Aurora, GCP Cloud SQL/AlloyDB, Azure Flexible Server, DigitalOcean, Hetzner, Neon, Supabase. No lock-in: the schema is plain SQL plus two extensions (`pg_trgm`, optionally `pgcrypto` — avoidable, see §4). |
| Deployment into an unknown bank environment | Runs on RHEL, Ubuntu, SLES, Windows Server, containers, and every managed offering. On-premise install is a package and an `initdb`. Only real dependency: ICU must be compiled in — "Apt, Rpm and the EDB installer have opted to include ICU support" [DOC collation.html]. |
| Exit cost if wrong | Low-to-moderate. The schema uses four PostgreSQL-specific constructs: partial unique indexes, `jsonb`, `SKIP LOCKED`, and PL/pgSQL triggers. Each has a documented equivalent or workaround in MySQL 8.4 and SQL Server. Estimated port: **1–2 weeks**, dominated by rewriting the audit trigger and the fold function. |
| Append-only enforcement | No engine feature. Built from role separation + REVOKE + BEFORE UPDATE/DELETE/TRUNCATE triggers + an event trigger on DDL. **We write and test this.** Costed at ~300 lines plus tests. |
| Transactional DDL | Yes, full. Flyway rolls a failed migration back automatically. [DOC Redgate: "With PostgreSQL, all migrations are run in transactions so it will automatically roll back if it encounters an error."] |
| Arabic | See §9. Best of the three for expression indexes and for a custom immutable fold. |
| Licence | PostgreSQL Licence (permissive, BSD-like). No cost to us or to the bank. |
| Where it breaks | Not at our assumed scale. A single primary on 8 vCPU / 32 GB handles 20 writes/sec and 250M audit rows comfortably. It would break at sustained >5,000 writes/second on one primary, or if audit artifact bodies turn out to be megabytes rather than kilobytes — in which case artifact bodies move to object storage under AD-004 and only their SHA-256 stays in the DB. The schema already supports that move (§4). |

### Option 2 — MariaDB 11 LTS / MySQL 8.4 LTS

| Dimension | Assessment |
|---|---|
| Local dev on Windows | Excellent. Native installers, tiny footprint. |
| Testing on a cloud | Ubiquitous and cheap on every provider. |
| Deployment into a bank | Widely accepted; MariaDB avoids the Oracle-ownership question that MySQL raises. |
| Exit cost | Moderate. Migrating *away* is harder than from PostgreSQL because you will have coded around the missing features (no partial indexes, weaker JSON, no `SKIP LOCKED` in MariaDB before 10.6 / present in MySQL 8). |
| Append-only | Table-level `REVOKE UPDATE, DELETE` works, and triggers can `SIGNAL SQLSTATE`. Comparable to PostgreSQL. No engine ledger. |
| **Transactional DDL** | **No.** "DDL statements, atomic or otherwise, implicitly end any transaction that is active in the current session… DDL statements cannot be performed within another transaction" [DOC MySQL 8.4 manual, atomic-ddl.html]. Redgate: "MySQL/MariaDB can roll back DML transactions, but all the important DDL operations are immediately committed and can't be rolled back" [DOC]. Every failed migration is a manual repair. **This is the disqualifier.** |
| Arabic | `utf8mb4_0900_ai_ci` (MySQL) / `uca1400_ai_ci` (MariaDB) collations exist. Accent-insensitivity collapses أ/ا but **not** ة/ه or ى/ي [UNVERIFIED for these specific pairs — needs measurement], so the same explicit fold is required. Functional indexes: MySQL 8.0.13+ yes; MariaDB requires a virtual generated column plus index. Substring search on Arabic: MySQL's built-in FTS `ngram` parser is CJK-oriented, so you fall back to unindexed `LIKE '%…%'`. **Weakest of the three for operator search.** |
| Licence | MySQL GPLv2 + commercial (Oracle-owned); MariaDB GPLv2. GPL server, but we ship no derivative work of the server — we connect over a wire protocol. Connector licensing differs (Connector/J is GPL+FOSS exception; MariaDB Connector/J is LGPL). A bank's procurement will ask about it; PostgreSQL's licence generates no such question. |
| Where it breaks | Same scale ceiling as PostgreSQL. Breaks operationally on the first failed migration. |

### Option 3 — Microsoft SQL Server 2022/2025

| Dimension | Assessment |
|---|---|
| Local dev on Windows | Best of the three. Developer Edition is free, full-featured, and native. |
| Testing on a cloud | Available managed everywhere (RDS, Cloud SQL, Azure SQL MI), but priced with licence included — materially more expensive than PostgreSQL for the same test footprint. |
| Deployment into a bank | Runs on Windows and Linux. **The bank must hold or buy licences.** Express caps at 10 GB per database, which our ~150 GB audit store blows past, so it is Standard at minimum — per-core, real money, chosen by us on their behalf. |
| Exit cost | **Highest of the three, precisely because of the feature that makes it attractive.** Append-only ledger tables have no PostgreSQL/MySQL equivalent; leaving means re-implementing the hash chain *and* re-proving tamper-evidence to whoever accepted the ledger attestation. |
| **Append-only** | **Best in class, and it is not close.** Append-only ledger tables "block updates and deletions at the API level"; rows are SHA-256 hashed into a Merkle tree per transaction, transactions into blocks, blocks chained by hashing the previous block's root — and database digests can be published to WORM storage and verified later [DOC Microsoft Learn, ledger-overview, SQL Server 2022 16.x+]. Supported in **all editions including Express** [DOC/secondary: Microsoft editions-and-components page]. This removes ~300 lines of our code and replaces it with a vendor-attested mechanism. |
| Transactional DDL | Partial — "to a certain extent SQL Server" supports DDL in transactions [DOC Redgate]. Better than MySQL, worse than PostgreSQL. |
| Arabic | `Arabic_100_CI_AI_SC_UTF8` collation; full-text search has an Arabic word breaker. Accent-insensitivity again does not cover ة/ه or ى/ي, so the explicit fold is still needed, implemented as a computed persisted column plus index. Strongest Arabic FTS of the three; the fold requirement is identical. |
| Licence | Commercial. Developer Edition is dev-only and may not be used in production. |
| Where it breaks | Not technically. It breaks commercially: we would be handing the bank a licence bill we invented. |

### Option 4 — Oracle Database (named, rejected)

Rejected on three independent grounds, any one sufficient. (a) The delivery brief is explicit that the bank's Oracle is not our database and is touched read-only once via `ProcessOmniCheckAct`; FIB did the opposite and put its application data in the bank's Oracle [OBSERVED `../FIB/.../application.yml`: `spring.datasource` pointing at an Oracle instance with `OracleDialect` and `ddl-auto: none`]. (b) Oracle Database Free is hard-capped at 12 GB user data, 2 GB RAM and 2 CPUs [DOC/secondary] — below our assumed audit footprint by an order of magnitude. (c) No transactional DDL [DOC Redgate: "Oracle doesn't support transactional DDL so any changes are committed immediately"]. There is one genuine argument for it — the bank's DBAs already know Oracle — and it is not enough.

### Option 5 — MongoDB / document store (named, rejected)

Rejected on two grounds. (a) The data is unambiguously relational: nine cross-referencing entities, a version registry with foreign keys that must never dangle, a status history with strict ordering, and a four-eyes rule that is naturally a constraint over history rows. (b) MongoDB Community is SSPL, which is not OSI-approved and is a known procurement obstacle in banks. FerretDB/DocumentDB variants add a third moving part. No offsetting benefit.

---

## 3. Recommendation, and the conditions under which it flips

**PostgreSQL 18, single instance, three schemas, Flyway 12, no Redis.**

The "no Redis" is deliberate and in scope: FIB carried `spring-boot-starter-data-redis` and a Redis cache config [OBSERVED `../FIB/.../pom.xml` lines 37–40 and `application.yml`]. Our OTP challenge state, idempotency records and notification outbox all fit in PostgreSQL, and every one of them wants to be transactional with a business write. Adding Redis adds a second thing a solo developer must operate, back up, and secure, to solve a problem we do not have at 20 writes/second.

**It flips to SQL Server 2022 if, and only if, both of these become true:** the bank confirms it already licenses SQL Server Standard or above and will host on it, **and** OQ-001's regulatory answer requires vendor-attested tamper-evidence rather than an application-implemented hash chain (i.e. an auditor will not accept "we hash it ourselves"). Either alone is insufficient: the first without the second buys a licence we do not need, and the second without the first makes us impose one.

**It flips to MariaDB if** the bank's only permitted database platforms are Oracle and MySQL-family, and Oracle is off the table for cost. In that case, accept the manual-migration-repair burden and budget for it explicitly.

**It does not flip** for: managed-service convenience (all clouds offer PostgreSQL), Arabic search results being disappointing (the fix is a `tsvector` over folded text, still PostgreSQL — see §9), scale (see §2), or the bank running Oracle (that estate is not ours).

**If I am wrong about the flip conditions**, the tell will be an early conversation in which the bank's infrastructure team names a platform standard. Ask it now — it is a cheap question and it is the only input that changes this answer.

---

## 4. Schema

PostgreSQL DDL, condensed to shapes. Three schemas: `app`, `audit`, `ref`. All timestamps `timestamptz`, server-set. All text UTF-8.

### 4.1 `app` — profile, mutable current state

```sql
-- Closed code sets that need labels get a lookup table (not a PG ENUM: ALTER TYPE ADD VALUE
-- interacts badly with Flyway's all-migrations-in-a-transaction behaviour on PostgreSQL).
CREATE TABLE app.status_code (
  code        text PRIMARY KEY,        -- in_progress | awaiting_registry | blocked_scan
                                       -- | blocked_liveness | abandoned | submitted
                                       -- | approved | rejected | terminated_registry_mismatch
  label_ar    text NOT NULL,
  label_en    text NOT NULL,
  is_terminal boolean NOT NULL
);

CREATE TABLE app.profile (
  profile_id        uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  reference_number  text        UNIQUE,               -- NULL until submitted (stage 11)
  branch_code       text        NOT NULL,
  account_number    text        NOT NULL,
  status            text        NOT NULL REFERENCES app.status_code(code),
  status_changed_at timestamptz NOT NULL,
  provenance        text        NOT NULL DEFAULT 'digital'
                                CHECK (provenance IN ('digital','manual')),
  resume_stage      smallint    NOT NULL DEFAULT 1,   -- last completed journey stage
  created_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  last_activity_at  timestamptz NOT NULL,             -- drives 30d abandoned, 90d purge
  submitted_at      timestamptz,
  pii_purged_at     timestamptz,                      -- 90-day retention rule; row survives
  row_version       bigint      NOT NULL DEFAULT 1,
  CONSTRAINT profile_one_per_account UNIQUE (branch_code, account_number)
);
```

`UNIQUE (branch_code, account_number)` is the database-level expression of the campaign rule "one update per account" (customer.md stage 1a) and of operator.md's reasoning that a duplicate submission cannot occur. The profile row is **never deleted** — the 90-day retention rule nulls PII and sets `pii_purged_at`, so the reference number and the audit linkage survive.

```sql
CREATE TABLE app.profile_customer_data (      -- DEVICE-AUTHORITATIVE on reconcile
  profile_id            uuid PRIMARY KEY REFERENCES app.profile ON DELETE RESTRICT,
  phone_number          text,
  email_address         text,
  sex_declared          text CHECK (sex_declared IN ('m','f')),  -- interface only; NOT stored truth
  marital_status        text CHECK (marital_status IN ('single','married','divorced','widowed')),
  spouse_name           text,
  has_children          boolean,
  children_count        smallint CHECK (children_count IS NULL OR children_count >= 1),
  education_level       smallint CHECK (education_level BETWEEN 1 AND 7),

  occupation_code       text,
  occupation_version    int,
  occupation_list       text GENERATED ALWAYS AS ('occupation') STORED,

  monthly_expenses_sdg  numeric(14,2) CHECK (monthly_expenses_sdg >= 0),
  identity_type         text CHECK (identity_type IN ('passport','national_id')),

  home_country_code     text, home_state_code text, home_locality_code text,
  home_state_text       text, home_locality_text text,          -- non-Sudan fallback
  home_city text, home_area text, home_street text, home_block text, home_house_no text,

  employer_name         text,
  work_country_code     text, work_state_code text, work_locality_code text,
  work_state_text       text, work_locality_text text,
  work_city text, work_area text, work_street text, work_block text,   -- no house number

  admin_div_version     int,
  updated_at            timestamptz NOT NULL,
  device_claimed_at     timestamptz,     -- UNVERIFIED device clock, recorded as claimed

  CONSTRAINT occupation_fk FOREIGN KEY (occupation_list, occupation_version, occupation_code)
    REFERENCES ref.reference_item (list_code, version, item_code),
  CONSTRAINT sudan_uses_codes CHECK (
    home_country_code IS DISTINCT FROM 'SD' OR home_locality_code IS NOT NULL),
  CONSTRAINT children_consistent CHECK (
    has_children IS NOT TRUE OR children_count IS NOT NULL)
);
```

The generated constant column `occupation_list` is what makes a real foreign key from a profile field to a *versioned* reference item possible. It is worth the ugliness for occupation and administrative divisions specifically, because those are the two lists where a wrong code is silently-wrong data the bank will filter on (R-014, R-020, OQ-012). Income source gets no FK, because `أخرى` is free text by design.

```sql
CREATE TABLE app.profile_income_source (
  profile_id  uuid NOT NULL REFERENCES app.profile_customer_data(profile_id) ON DELETE CASCADE,
  source_code text NOT NULL,
  is_primary  boolean NOT NULL DEFAULT false,
  other_text  text,
  PRIMARY KEY (profile_id, source_code),
  CONSTRAINT other_needs_text CHECK (source_code <> 'OTHER' OR other_text IS NOT NULL)
);
-- "One selection must be marked primary" (customer.md stage 4), enforced by the database:
CREATE UNIQUE INDEX profile_income_one_primary
  ON app.profile_income_source (profile_id) WHERE is_primary;
```

```sql
CREATE TABLE app.profile_channel (            -- BACKEND-AUTHORITATIVE
  profile_id  uuid NOT NULL REFERENCES app.profile ON DELETE RESTRICT,
  channel     text NOT NULL CHECK (channel IN ('sms','whatsapp','email')),
  state       text NOT NULL CHECK (state IN ('verified','declined','unverified')),
  verified_at timestamptz,
  locked_at   timestamptz,
  wrong_code_attempts smallint NOT NULL DEFAULT 0 CHECK (wrong_code_attempts <= 5),
  resend_count        smallint NOT NULL DEFAULT 0 CHECK (resend_count <= 3),
  PRIMARY KEY (profile_id, channel)
);
```

**Note the deliberate split:** the phone number and email address live on `profile_customer_data` (device-authoritative), the per-channel *state* lives here (backend-authoritative). Putting the destination on the channel row would have created a table with two owners and made the stage 12 reconcile rule ambiguous per column.

```sql
CREATE TABLE app.otp_challenge (
  challenge_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  profile_id   uuid NOT NULL REFERENCES app.profile,
  channel      text NOT NULL,
  code_hash    bytea NOT NULL,      -- sha256(salt || code); the code is NEVER stored
  salt         bytea NOT NULL,
  issued_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at   timestamptz NOT NULL,          -- issued_at + 5 minutes
  resend_index smallint NOT NULL,             -- 0,1,2 → 30s/60s/120s unlock
  consumed_at  timestamptz
);
```

### 4.2 `app` — system-derived identity data, with supersede

```sql
-- The unit that gets superseded when a device-less resume forces a rescan
-- (customer.md "System-derived identity artifacts are not inherited").
CREATE TABLE app.identity_cycle (
  cycle_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  profile_id   uuid NOT NULL REFERENCES app.profile ON DELETE RESTRICT,
  seq          int  NOT NULL,
  state        text NOT NULL CHECK (state IN ('pending','active','superseded','abandoned')),
  opened_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  accepted_at  timestamptz,
  superseded_at timestamptz,
  UNIQUE (profile_id, seq)
);
CREATE UNIQUE INDEX identity_one_active
  ON app.identity_cycle (profile_id) WHERE state = 'active';

CREATE TABLE app.scan_result (
  cycle_id        uuid PRIMARY KEY REFERENCES app.identity_cycle ON DELETE RESTRICT,
  uqudo_jti       text NOT NULL UNIQUE,       -- global replay guard (uqudo-sdk.md)
  document_type   text NOT NULL,              -- SDN_ID | PASSPORT, as requested and echoed
  card_variant    text,                       -- SDN_ID previous | latest
  identity_number text NOT NULL,              -- ⚠ the Civil Registry lookup key
  document_number text,                       -- ⚠ MRZ value — NOT the registry key
  mrz_verified    boolean,                    -- integrity signal
  nationality     text,
  sex_on_document text,                       -- THIS is the stored sex, not sex_declared
  date_of_birth   date,
  date_of_issue   date, date_of_expiry date,  -- expiry gates nothing (stage 9, RESOLVED)
  place_of_issue  text, issuing_country text,
  name_ar_on_document text, name_en_on_document text, blood_type text,
  id_print_score smallint, id_screen_score smallint, id_photo_tampering_score smallint,
  received_at     timestamptz NOT NULL
);

CREATE TABLE app.face_result (
  cycle_id           uuid PRIMARY KEY REFERENCES app.identity_cycle ON DELETE RESTRICT,
  uqudo_jti          text NOT NULL UNIQUE,
  face_session_id    text NOT NULL,           -- minted by us, validated back out of the JWS
  match              boolean NOT NULL,
  match_level        smallint NOT NULL CHECK (match_level BETWEEN 1 AND 5),
  threshold_applied  smallint NOT NULL,       -- server-side, starts at 3; recorded per row
  passed             boolean  GENERATED ALWAYS AS (match AND match_level >= threshold_applied) STORED,
  received_at        timestamptz NOT NULL
);
-- NOTE: there is deliberately NO liveness score column. Uqudo returns none; a liveness
-- failure produces no JWS at all and is recorded in audit as a terminated attempt.
-- See "Contradiction found" below — customer.md still says otherwise.

CREATE TABLE app.registry_result (
  cycle_id        uuid PRIMARY KEY REFERENCES app.identity_cycle ON DELETE RESTRICT,
  state           text NOT NULL CHECK (state IN ('pending','ok','not_found','unreachable')),
  queried_at      timestamptz,
  attempts        smallint NOT NULL DEFAULT 0,
  full_name_ar text, full_name_en text, mother_name text, citizenship text,
  birth_country text, birth_state text, birth_city text
);

CREATE TABLE app.omni_check (            -- ProcessOmniCheckAct outcome; also for no-profile attempts
  omni_check_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  profile_id    uuid REFERENCES app.profile,     -- NULL for -1 and 2 outcomes
  branch_code   text NOT NULL,
  account_hash  bytea NOT NULL,                  -- sha256; the raw number lives in audit only
  result_code   smallint NOT NULL CHECK (result_code IN (1, 2, -1)),
  called_at     timestamptz NOT NULL
);

-- References only. Bytes live wherever AD-004 decides.
CREATE TABLE app.artifact_ref (
  artifact_ref_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  cycle_id      uuid REFERENCES app.identity_cycle ON DELETE RESTRICT,
  profile_id    uuid REFERENCES app.profile,     -- for the salary certificate, which has no cycle
  kind          text NOT NULL CHECK (kind IN ('doc_front','doc_back','doc_front_frame',
                    'doc_back_frame','portrait','face_audit_trail','salary_certificate')),
  uqudo_image_id text,
  uqudo_checksum text,                           -- "sha256:<digest>" exactly as supplied
  storage_key   text NOT NULL,                   -- opaque; AD-004 owns its meaning
  content_type  text NOT NULL,
  byte_size     bigint NOT NULL,
  sha256        bytea  NOT NULL,
  state         text NOT NULL CHECK (state IN ('staged','committed','purged')),
  created_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (cycle_id, kind)
);
```

The `staged`/`committed` state on `artifact_ref` is the reconciliation hook for the stage 8 chain (§5): rows are allocated before the download so the storage key is known, and only promoted when the accept transaction commits. A sweeper reclaims anything left `staged`.

### 4.3 `app` — status history

```sql
CREATE TABLE app.profile_status_history (
  history_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  profile_id     uuid NOT NULL REFERENCES app.profile ON DELETE RESTRICT,
  seq            int  NOT NULL,
  from_status    text REFERENCES app.status_code(code),     -- NULL for the first
  to_status      text NOT NULL REFERENCES app.status_code(code),
  occurred_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  actor_kind     text NOT NULL CHECK (actor_kind IN ('customer','operator','system')),
  actor_id       text,                                      -- operator identity; NULL otherwise
  reason_code    text,                                      -- REJ-01..07, versioned in ref
  reason_version int,
  reason_list    text GENERATED ALWAYS AS ('rejection_reason') STORED,
  internal_note  text,                                      -- never sent to the customer
  is_manual_completion boolean NOT NULL DEFAULT false,      -- drives the four-eyes rule
  audit_event_id bigint NOT NULL REFERENCES audit.audit_event(audit_event_id),
  UNIQUE (profile_id, seq),
  CONSTRAINT operator_named CHECK (actor_kind <> 'operator' OR actor_id IS NOT NULL),
  CONSTRAINT reject_needs_code CHECK (to_status <> 'rejected' OR reason_code IS NOT NULL),
  CONSTRAINT rej07_needs_detail CHECK (reason_code <> 'REJ-07' OR internal_note IS NOT NULL),
  FOREIGN KEY (reason_list, reason_version, reason_code)
    REFERENCES ref.reference_item (list_code, version, item_code)
);
```

This table is deliberately a **second copy** of information that also lives in the audit trail. The reason is a requirement, not laziness: operator.md requires the back office to search and filter on status history and the dashboard to aggregate rejection codes, while customer.md requires that reading the audit trail is itself an audited event with its own access control. Making operators query the audit store for routine work would generate an audit event per page view of the profile list. The `audit_event_id` FK binds the operational copy to the evidential one; a mismatch between the two is itself a detectable tampering signal.

**Four-eyes rule, enforced in the database rather than in the UI** — the approve statement is a single conditional UPDATE:

```sql
UPDATE app.profile p
   SET status = 'approved', status_changed_at = clock_timestamp(), row_version = row_version + 1
 WHERE p.profile_id = :pid
   AND p.status IN ('submitted','rejected')            -- legal prior states only
   AND NOT EXISTS (                                     -- four-eyes
         SELECT 1 FROM app.profile_status_history h
          WHERE h.profile_id = p.profile_id
            AND h.is_manual_completion
            AND h.actor_id = :operator_id);
```

Zero rows affected means either a lost race or a four-eyes violation; the service distinguishes them with a follow-up read. This makes the control impossible to bypass by calling the API directly.

### 4.4 `ref` — reference-data version registry

```sql
CREATE TABLE ref.reference_list (
  list_code text PRIMARY KEY,   -- occupation | branch | admin_division | income_source
                                -- | rejection_reason | education_level
  name_ar text NOT NULL, name_en text NOT NULL,
  is_hierarchical boolean NOT NULL DEFAULT false
);

CREATE TABLE ref.reference_list_version (
  list_code    text NOT NULL REFERENCES ref.reference_list,
  version      int  NOT NULL CHECK (version > 0),
  published_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  content_hash bytea NOT NULL,     -- sha256 over the canonical serialisation of all items
  item_count   int   NOT NULL,
  source_note  text,               -- provenance of the data, e.g. the supplied workbook
  is_current   boolean NOT NULL DEFAULT false,
  PRIMARY KEY (list_code, version)
);
CREATE UNIQUE INDEX ref_one_current ON ref.reference_list_version (list_code) WHERE is_current;

CREATE TABLE ref.reference_item (
  list_code    text NOT NULL,
  version      int  NOT NULL,
  item_code    text NOT NULL,             -- '86', '2'..'26', locality code, 'REJ-03'
  parent_code  text,                      -- admin hierarchy: country→state→locality
  label_ar     text NOT NULL,
  label_en     text,
  search_ar    text NOT NULL,             -- ar_fold(label_ar), generated — see §9
  search_en    text,
  sort_ordinal int  NOT NULL,             -- server-computed under ar-x-icu; see §9
  is_active    boolean NOT NULL DEFAULT true,   -- false hides 133/139/135 duplicates
  extra        jsonb,                     -- customer-facing message for REJ codes, etc.
  PRIMARY KEY (list_code, version, item_code),
  FOREIGN KEY (list_code, version) REFERENCES ref.reference_list_version (list_code, version)
);
CREATE INDEX ON ref.reference_item (list_code, version, parent_code);

-- Which version each submission actually used.
CREATE TABLE ref.profile_reference_version (
  profile_id uuid NOT NULL REFERENCES app.profile ON DELETE RESTRICT,
  list_code  text NOT NULL,
  version    int  NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (profile_id, list_code),
  FOREIGN KEY (list_code, version) REFERENCES ref.reference_list_version (list_code, version)
);
```

`reference_list_version` rows are **never deleted** — the FK from `profile_reference_version` and from the profile fields guarantees it. That is what makes "locality code 4103 under the interim dataset may not mean the same thing once the corrected dataset lands" (customer.md) recoverable rather than a data-loss event.

### 4.5 `audit` — append-only, hash-chained

```sql
CREATE TABLE audit.audit_chain (
  chain_id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  chain_kind text NOT NULL CHECK (chain_kind IN ('profile','operator','system')),
  subject_id text,                       -- profile_id, operator_id, or NULL for 'system'
  head_seq   bigint NOT NULL DEFAULT 0,
  head_hash  bytea  NOT NULL,            -- genesis = sha256(chain_id || chain_kind || subject)
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  closed_at  timestamptz,
  UNIQUE (chain_kind, subject_id)
);

CREATE TABLE audit.audit_artifact (
  artifact_id  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  chain_id     uuid NOT NULL REFERENCES audit.audit_chain,
  kind         text NOT NULL CHECK (kind IN ('uqudo_scan_jws','uqudo_face_jws',
                 'civil_registry_response','omni_check_request','omni_check_response')),
  media_type   text  NOT NULL,           -- application/jose, application/json, text/xml…
  body         bytea,                    -- BYTE-IDENTICAL, never re-encoded; NULLable, see below
  byte_size    bigint NOT NULL,
  sha256       bytea  NOT NULL,          -- of the ORIGINAL body; survives a lawful purge
  received_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  body_purged_at timestamptz             -- set when the 90-day PII rule erases the bytes
);

CREATE TABLE audit.audit_event (
  audit_event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  chain_id       uuid   NOT NULL REFERENCES audit.audit_chain,
  seq            bigint NOT NULL,                  -- monotonic within the chain; trigger-set
  occurred_at    timestamptz NOT NULL,             -- SERVER time; trigger-set, client ignored
  device_claimed_at timestamptz,                   -- unverified, explicitly marked as claimed
  event_type     text NOT NULL,                    -- controlled vocabulary, see below
  actor_kind     text NOT NULL CHECK (actor_kind IN ('customer','operator','system')),
  actor_id       text,
  profile_id     uuid,      -- NULL for account-check attempts that create no profile
  session_id     uuid,
  request_id     uuid,
  idempotency_key text,
  payload_json   text  NOT NULL,                   -- RFC 8785 canonical JSON, written once
  payload        jsonb GENERATED ALWAYS AS (payload_json::jsonb) STORED,   -- for querying only
  artifact_id    bigint REFERENCES audit.audit_artifact,
  prev_hash      bytea NOT NULL,
  content_hash   bytea NOT NULL,
  row_hash       bytea NOT NULL,
  UNIQUE (chain_id, seq)
);
CREATE INDEX ON audit.audit_event USING brin (occurred_at);
CREATE INDEX ON audit.audit_event (profile_id, occurred_at);
CREATE INDEX ON audit.audit_event (event_type, occurred_at);

CREATE TABLE audit.audit_seal (            -- the external anchor; SQL Server calls this a digest
  seal_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  sealed_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  chain_count int   NOT NULL,
  seal_hash   bytea NOT NULL,     -- sha256 over (chain_id, head_seq, head_hash) for all chains,
                                  -- ordered by chain_id
  prev_seal_hash bytea NOT NULL,
  exported_at timestamptz,
  export_note text                -- where it was written: WORM bucket, printed register, email
);
```

**Two design points that carry weight and are easy to get wrong:**

1. **`payload_json text` is what gets hashed; `payload jsonb` is a generated column for querying.** Hashing a `jsonb` column's text rendering would tie verification to PostgreSQL's `jsonb` output formatting, which is not contractually stable across major versions. Storing the canonical JSON the backend produced (RFC 8785 JSON Canonicalization Scheme) makes a 2033 verification of a 2026 row independent of the server version, and independent of PostgreSQL entirely. **[UNVERIFIED: whether `text::jsonb` is accepted in a `GENERATED ALWAYS AS … STORED` expression — the cast's volatility must be confirmed with `SELECT provolatile FROM pg_proc WHERE proname = 'jsonb_in';` before relying on it. Fallback: a plain `jsonb` column populated by the same BEFORE INSERT trigger.]**

2. **`audit_artifact.body` is NULLable and the chain hashes `sha256`, not `body`.** The 90-day abandoned-profile retention rule erases personal data, but the raw Uqudo JWS contains that same personal data in its payload and the audit trail is retained for 7 years. Those two policies collide. The schema resolves it: the body bytes can be erased while the chain still verifies, because the hash covers the digest, not the bytes. The erasure is itself an audit event. Whether erasure is permitted at all is OQ-001's call, not ours — but the schema must not make the lawful answer impossible, and this one does not.

### Controlled event vocabulary (from customer.md and operator.md)

`account_check_attempted` · `session_created` · `otp_issued` · `otp_delivery_result` · `otp_attempted` · `otp_verified` · `otp_resent` · `channel_locked` · `stage_submitted` · `identity_type_selected` · `uqudo_token_issued` · `scan_started` · `scan_cancelled` · `scan_failed` · `scan_jws_received` · `jws_verification_result` · `image_retrieved` · `image_checksum_mismatch` · `scan_accepted` · `identity_number_extracted` · `registry_queried` · `registry_response` · `registry_review_action` · `face_session_created` · `face_jws_received` · `face_match_evaluated` · `liveness_attempt_terminated` · `block_applied` · `block_expired` · `profile_submitted` · `local_state_cleared` · `abandoned` · `resume_reconciled` · `artifacts_superseded` · `notification_dispatched` · `uqudo_session_purged` — and operator side: `operator_signin` · `operator_signin_failed` · `operator_signout` · `profile_viewed` · `image_viewed` · `search_executed` · `export_performed` · `manual_completion` · `status_transition` · `access_level_changed` · `audit_read` · `artifact_body_purged` · `chain_verified`.

---

## 5. Append-only and tamper-evidence, enforced at the database level

Four layers. Each is named as a specific PostgreSQL mechanism.

### Layer 1 — Role separation and REVOKE (the primary mechanism)

PostgreSQL's `REVOKE` cannot meaningfully be used against a table's owner: "the owner is always treated as holding all grant options" [DOC postgresql.org/docs/current/sql-revoke.html, Notes]. **Therefore the application must not own the audit tables.** That is the whole design.

```sql
CREATE ROLE fru_migrator LOGIN;   -- owns every object; used by Flyway only
CREATE ROLE fru_app      LOGIN;   -- the runtime connection; owns nothing
CREATE ROLE fru_purge    LOGIN;   -- the scheduled retention job only
CREATE ROLE fru_auditor  LOGIN;   -- read-only on audit, for verification and investigation

REVOKE ALL ON SCHEMA audit FROM PUBLIC;
GRANT  USAGE ON SCHEMA audit TO fru_app, fru_auditor, fru_purge;

GRANT INSERT, SELECT ON audit.audit_event    TO fru_app;
GRANT INSERT, SELECT ON audit.audit_artifact TO fru_app;
GRANT SELECT           ON audit.audit_chain  TO fru_app;
-- fru_app is granted NO UPDATE, NO DELETE, NO TRUNCATE anywhere in audit.

GRANT UPDATE (body, body_purged_at) ON audit.audit_artifact TO fru_purge;  -- column-level only
GRANT SELECT ON ALL TABLES IN SCHEMA audit TO fru_auditor;

ALTER DEFAULT PRIVILEGES FOR ROLE fru_migrator IN SCHEMA audit
  REVOKE ALL ON TABLES FROM PUBLIC;
```

An `UPDATE audit.audit_event …` issued by the application is rejected by the executor with `ERROR: permission denied for table audit_event`. Not a convention, not a code review, not an ORM setting — a privilege check.

The chain-maintaining trigger needs to update `audit_chain.head_*`, which `fru_app` cannot do. Make the trigger function `SECURITY DEFINER`, owned by `fru_migrator`, so the write happens with the owner's rights inside the trigger and nowhere else.

### Layer 2 — Immutability triggers (catches the owner and any mis-grant)

```sql
CREATE FUNCTION audit.reject_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'audit.% is append-only; % is not permitted', TG_TABLE_NAME, TG_OP
    USING ERRCODE = '42501';
END $$;

CREATE TRIGGER audit_event_immutable
  BEFORE UPDATE OR DELETE ON audit.audit_event
  FOR EACH ROW EXECUTE FUNCTION audit.reject_mutation();

CREATE TRIGGER audit_event_no_truncate
  BEFORE TRUNCATE ON audit.audit_event
  FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_mutation();
```

`audit_artifact` gets a narrower trigger, because the lawful purge must pass:

```sql
CREATE FUNCTION audit.artifact_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP <> 'UPDATE' THEN RAISE EXCEPTION 'audit_artifact is append-only'; END IF;
  IF NEW.artifact_id  IS DISTINCT FROM OLD.artifact_id
     OR NEW.chain_id  IS DISTINCT FROM OLD.chain_id
     OR NEW.kind      IS DISTINCT FROM OLD.kind
     OR NEW.media_type IS DISTINCT FROM OLD.media_type
     OR NEW.byte_size IS DISTINCT FROM OLD.byte_size
     OR NEW.sha256    IS DISTINCT FROM OLD.sha256
     OR NEW.received_at IS DISTINCT FROM OLD.received_at
     OR OLD.body IS NULL                       -- already purged
     OR NEW.body IS NOT NULL                   -- purge means NULL, nothing else
     OR NEW.body_purged_at IS NULL
  THEN RAISE EXCEPTION 'the only permitted update is a body purge'; END IF;
  RETURN NEW;
END $$;
```

The same `reject_mutation` trigger is applied to `app.profile_status_history`, which is operationally insert-only for the same reasons.

### Layer 3 — An event trigger against DDL on the audit schema

```sql
CREATE FUNCTION audit.block_audit_ddl() RETURNS event_trigger LANGUAGE plpgsql AS $$
DECLARE r record;
BEGIN
  IF current_setting('fru.migration_in_progress', true) = 'on' THEN RETURN; END IF;
  FOR r IN SELECT * FROM pg_event_trigger_ddl_commands() LOOP
    IF r.schema_name = 'audit' THEN
      RAISE EXCEPTION 'DDL against schema audit requires a migration session';
    END IF;
  END LOOP;
END $$;
CREATE EVENT TRIGGER fru_audit_ddl_guard ON ddl_command_end EXECUTE FUNCTION audit.block_audit_ddl();
```

Flyway sets `SET LOCAL fru.migration_in_progress = 'on'` in a `beforeMigrate` callback. This stops an accidental `DROP TABLE audit.audit_event` in a stray migration or a psql session.

### Layer 4 — The hash chain (what makes it evidence rather than a log)

Written by a `BEFORE INSERT` trigger, `SECURITY DEFINER`, so the application supplies **none** of `seq`, `occurred_at`, `prev_hash`, `content_hash` or `row_hash`. Any value it supplies is overwritten.

```sql
CREATE FUNCTION audit.chain_append() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = audit, pg_catalog AS $$
DECLARE h audit.audit_chain%ROWTYPE; a_hash bytea; canon text;
BEGIN
  SELECT * INTO h FROM audit.audit_chain
   WHERE chain_id = NEW.chain_id FOR UPDATE;                -- serialises appends per chain
  IF NOT FOUND THEN RAISE EXCEPTION 'unknown audit chain %', NEW.chain_id; END IF;

  NEW.seq         := h.head_seq + 1;
  NEW.occurred_at := clock_timestamp();                     -- server-authoritative time
  NEW.prev_hash   := h.head_hash;

  SELECT sha256 INTO a_hash FROM audit.audit_artifact WHERE artifact_id = NEW.artifact_id;

  canon := concat_ws(E'\x1e',
             NEW.chain_id::text,
             NEW.seq::text,
             to_char(NEW.occurred_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
             NEW.event_type,
             NEW.actor_kind,
             coalesce(NEW.actor_id,''),
             coalesce(NEW.profile_id::text,''),
             coalesce(NEW.session_id::text,''),
             coalesce(NEW.request_id::text,''),
             coalesce(NEW.idempotency_key,''),
             NEW.payload_json,                              -- canonical JSON, written once
             coalesce(encode(a_hash,'hex'),''));            -- digest, NOT the body

  NEW.content_hash := sha256(convert_to(canon,'UTF8'));
  NEW.row_hash     := sha256(h.head_hash || NEW.content_hash);

  UPDATE audit.audit_chain
     SET head_seq = NEW.seq, head_hash = NEW.row_hash
   WHERE chain_id = NEW.chain_id;
  RETURN NEW;
END $$;
```

`sha256(bytea)` is built into PostgreSQL — no `pgcrypto` extension required [DOC postgresql.org/docs/current/functions-binarystring.html]. That matters for deployment into an unknown bank environment where extension installation may need a change request.

**Concurrency:** `SELECT … FOR UPDATE` on the chain head row is the serialisation mechanism. Appends to different chains never block each other; appends to the same chain (one customer's journey, or one operator's session) serialise, which is exactly the required semantics and is essentially uncontended. `clock_timestamp()` rather than `now()` so several events in one transaction get distinct times — but ordering is guaranteed by `seq`, not by the timestamp.

**One consequence to design around:** if the enclosing transaction rolls back, the audit row and the chain head update roll back together — the chain stays consistent, but the event is *lost*. Customer.md requires "Account check attempted … recorded even when no profile is created". Therefore audit writes for failed operations are issued in a **separate transaction** (`Propagation.REQUIRES_NEW` on a second `DataSource`-backed template) so they survive the business rollback.

**Verification:**

```sql
CREATE FUNCTION audit.verify_chain(p_chain uuid)
RETURNS TABLE (ok boolean, checked bigint, first_bad_seq bigint, reason text) …
```
Walks `seq = 1..head_seq` in order, recomputes `content_hash` from the stored columns and `row_hash` from the recomputed previous, checks contiguity of `seq`, and asserts that the final `row_hash` equals `audit_chain.head_hash`. Then compares `head_hash` against the most recent exported `audit_seal`. Runs nightly across all chains, on demand for one profile from the back office, and as a Testcontainers integration test that deliberately corrupts a row (as `fru_migrator`) and asserts detection.

**What this does not do, stated plainly:** it does not stop a PostgreSQL superuser, or anyone with write access to the data directory or the backups. Nothing in any of the four options does — Microsoft says the same about ledger: "an attacker or system administrator who has control of the machine can bypass all system checks and directly tamper with the data. Ledger can't prevent such attacks but guarantees that any tampering will be detected" [DOC]. The requirement in customer.md is *tamper-evident*, not tamper-proof, and the seal export is what closes the loop: a seal written outside the database at time T fixes the state of every chain at T, so any later edit to a row written before T is detectable even by someone who can rewrite the whole table.

---

## 6. Transaction boundaries

### A. Status transition + audit record + notification dispatch

**Atomic, one transaction, one database:**

1. `UPDATE app.profile SET status = :new, status_changed_at, last_activity_at, row_version = row_version + 1 WHERE profile_id = :pid AND status = :expected_prior [AND four-eyes predicate]` — a compare-and-set. Zero rows means the transition is illegal or lost a race; the transaction aborts. This is what makes concurrent approve/reject by two operators safe without table locks or SERIALIZABLE isolation.
2. `INSERT INTO app.profile_status_history …`
3. `INSERT INTO audit.audit_event …` (the trigger writes the chain), returning `audit_event_id`, which is written onto the history row.
4. `INSERT INTO app.notification_outbox …` — **one row per channel where `app.profile_channel.state = 'verified'`**, selected inside the same transaction so the channel set cannot shift underneath.
5. `UPDATE app.idempotency_key SET state = 'completed', response_status, response_body`.

**Explicitly NOT in the transaction:** the SMS, WhatsApp or email send. It is external I/O of unbounded latency, and customer.md is explicit that "Notification dispatch is fire-and-forget relative to the profile: a failed SMS does not change a status." A dispatcher polls the outbox with

```sql
SELECT * FROM app.notification_outbox
 WHERE state = 'pending' AND next_attempt_at <= clock_timestamp()
 ORDER BY next_attempt_at
 FOR UPDATE SKIP LOCKED LIMIT 50;
```

`FOR UPDATE SKIP LOCKED` is the PostgreSQL mechanism that makes a multi-instance dispatcher correct with no message broker. Each dispatch attempt commits its own short transaction updating the outbox row **and** writing a `notification_dispatched` audit event carrying the per-channel delivery result, as customer.md requires.

Isolation level: `READ COMMITTED` throughout. Nothing here needs more, because every contended write is a compare-and-set or protected by a unique constraint.

### B. Stage 8 — scan accepted only after images download successfully

This cannot be one transaction: it contains a JWKS fetch, up to five authenticated image downloads, a Civil Registry call and a Uqudo `DELETE`. Holding a transaction open across those would pin a connection for tens of seconds and, worse, would roll back the raw-JWS audit record on any failure — losing the evidence precisely when something went wrong.

**T1 — short, committed immediately.** Claim the idempotency key (`in_flight`, see §7). Insert `audit.audit_artifact` with the **raw JWS, byte-identical**, and `audit.audit_event` `scan_jws_received`. Create or reuse `app.identity_cycle` in state `pending`. Allocate `app.artifact_ref` rows in state `staged` with their storage keys. **COMMIT.** From this point the JWS is preserved whatever happens next.

**External phase — no transaction held.** Each step writes its own audit event in its own short transaction, so a crash leaves a truthful partial trail:
- verify signature against JWKS; validate `iss`, `aud`, `exp`, `iat`, `jti` == our session id, `data.nonce` == our nonce, `documentType`, and `jti` not previously accepted → `jws_verification_result`
- download each image by id; verify against its `…Checksum`; write bytes to the AD-004 store under the pre-allocated key → `image_retrieved` per image
- extract `identityNumber` (**not** `documentNumber`) → `identity_number_extracted`
- call the Civil Registry; store the raw response as an `audit_artifact` → `registry_queried`, `registry_response`

**T2 — short, committed. This is the accept decision.** Reached only if every image downloaded and every checksum matched.
`UPDATE app.artifact_ref SET state = 'committed'`; `INSERT app.scan_result`; `INSERT app.registry_result`; supersede any prior active cycle and `UPDATE app.identity_cycle SET state = 'active', accepted_at`; transition the profile status (to `awaiting_registry` if the registry did not answer, otherwise stay `in_progress`) through boundary A; audit `scan_accepted`; complete the idempotency key. **COMMIT.**

**Then, outside any transaction:** `DELETE /api/v1/info/{jti}` to purge Uqudo's cached session data. Failure is logged and audited, never fatal.

**The ordering constraint from AD-002a is structural, not procedural:** acceptance lives in T2, and T2 is unreachable unless the downloads succeeded. A JWS that verifies perfectly but whose images have expired produces `IMAGES_UNAVAILABLE`, a `pending` cycle, `staged` artifact rows a sweeper reclaims, and no retry-budget charge (R-012).

### C. Stage 11 — submission

One transaction: assign the reference number (protected by `UNIQUE`), transition to `submitted`, history, audit, outbox rows for every verified channel, complete the idempotency key. The strict ordering in customer.md — the app clears local storage only *after* the backend's acknowledgement, and the notification goes out *after* that — is a wire-contract and dispatcher-ordering property, not a database one; the database's job is to make step 2 atomic so that "submitted" is never half-true.

---

## 7. Idempotency

**Where the key is stored:** `app.idempotency_key`, in the same database and, for short mutations, the same transaction as the effect it protects.

```sql
CREATE TABLE app.idempotency_key (
  scope           text  NOT NULL,      -- endpoint identity, e.g. 'POST /sessions/{id}/scan'
  key             text  NOT NULL,      -- client-supplied
  profile_id      uuid,
  request_hash    bytea NOT NULL,      -- sha256 of the canonical request body
  state           text  NOT NULL CHECK (state IN ('in_flight','completed')),
  response_status smallint,
  response_body   jsonb,
  created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  claim_expires_at timestamptz,        -- only for in_flight claims
  completed_at    timestamptz,
  expires_at      timestamptz NOT NULL,   -- created_at + 30 days
  PRIMARY KEY (scope, key)
);
CREATE INDEX ON app.idempotency_key (expires_at);
```

**Two tiers, because the endpoints differ in duration.**

*Tier 1 — short mutations* (stage submissions, OTP verification, channel selection, operator approve/reject/manual-complete). Everything happens in one transaction:

```sql
INSERT INTO app.idempotency_key (scope, key, profile_id, request_hash, state, response_status,
                                 response_body, completed_at, expires_at)
VALUES (…, 'completed', …)
ON CONFLICT (scope, key) DO NOTHING
RETURNING *;
```

If a row comes back, this is the first execution and the effect commits with it. If zero rows come back, a record already exists: read it, and if `request_hash` matches, replay the stored response verbatim with no side effect; if it differs, return `422 IDEMPOTENCY_KEY_REUSED`. **No `in_flight` state and no reaper are needed here**, because PostgreSQL's speculative-insertion behaviour makes a concurrent duplicate *block* on the unique index until the first transaction ends, then correctly see the conflict. The unique index is the concurrency control.

*Tier 2 — long chains* (`POST scan`, `POST face-session-result`, and any handler with external I/O). The claim is committed **before** the external work, in T1, as `state = 'in_flight'` with `claim_expires_at = clock_timestamp() + interval '3 minutes'`. A duplicate arriving during the external phase gets `409 REQUEST_IN_PROGRESS` and a `Retry-After`. T2 flips it to `completed` with the response. A reaper deletes `in_flight` rows past `claim_expires_at`, releasing the key so a genuine retry can proceed after a crash.

**What makes the retry harmless, per resource:**
- Profile creation — `UNIQUE (branch_code, account_number)`; a second create resolves to the same row.
- Scan/face submission — `scan_result.uqudo_jti UNIQUE` and `face_result.uqudo_jti UNIQUE` are a **second, independent** guard: the same JWS can never be accepted twice even if the idempotency key is lost. This also serves as the global replay guard uqudo-sdk.md requires.
- Status transitions — the compare-and-set on the prior status makes a replayed approve a zero-row update, which the handler maps to the stored response.
- Outbox — a `UNIQUE (status_history_id, channel)` prevents a duplicate notification for one transition.

The idempotency key is also copied onto `audit_event.idempotency_key`, so a duplicate-delivery investigation can be reconstructed from the trail alone.

**[Note on the wire contract:** the `409 REQUEST_IN_PROGRESS` and `422 IDEMPOTENCY_KEY_REUSED` responses are additions to the surface AD-002a settled, not changes to it. See the closing paragraph.]

---

## 8. The mobile local store

### Package choice: `drift` + `sqlite3` (3.x), with `flutter_secure_storage` for the key

**iOS support verified at the moment of proposal, per the CLAUDE.md hard rule:**

| Package | Version | iOS support | Evidence |
|---|---|---|---|
| `drift` | 2.34.3, published ~25 days ago, MIT, publisher simonbinder.eu | **Yes** — iOS listed among Android, iOS, Linux, macOS, web, Windows. Pure Dart with no platform channels; inherits platform support from `sqlite3`. | [OBSERVED pub.dev/packages/drift, fetched 2026-08-22] |
| `sqlite3` | 3.5.2, published ~2 days ago, MIT, publisher simonbinder.eu | **Yes** — the page states prebuilt iOS binaries for "arm64 (devices), arm64 (simulator), x64 (simulator)". Bundled via Dart build hooks, so no CocoaPods entry and no external dependency. | [OBSERVED pub.dev/packages/sqlite3, fetched 2026-08-22] |
| Dart build hooks | Stable | Build hooks and code assets are **stable in Dart 3.10 / Flutter 3.38** and enabled by default. Our pins are Flutter **3.47.0** and Dart **^3.10.7**, comfortably above the floor. | [DOC dart.dev/tools/hooks + Flutter 3.38 release announcement; OBSERVED `mobile/.fvmrc`, `mobile/pubspec.yaml`] |
| `flutter_secure_storage` | 11.0.0, published ~16 days ago, BSD-3-Clause | **Yes** — iOS uses Keychain; requires `keychain-access-groups` in `DebugProfile.entitlements` and `Release.entitlements`; `IOSOptions.accessibility` defaults to `unlocked`. | [OBSERVED pub.dev/packages/flutter_secure_storage, fetched 2026-08-22] |

**Do not add `drift_flutter`, `sqlite3_flutter_libs` or `sqlcipher_flutter_libs`.** Both `*_flutter_libs` packages are published as `0.6.0+eol` / `0.7.0+eol` and "no longer do anything" after the move to `sqlite3` 3.x [OBSERVED pub.dev; DOC simolus3/sqlite3.dart UPGRADING_TO_V3.md]. `drift_flutter` 0.3.1 still lists both as dependencies [OBSERVED pub.dev/packages/drift_flutter], so taking it drags two dead packages in. Depend on `drift` and `sqlite3` directly and open the database with `NativeDatabase.createInBackground`.

**Encryption at rest**, via the hooks configuration rather than a package:

```yaml
hooks:
  user_defines:
    sqlite3:
      source: sqlite3mc      # SQLite3 Multiple Ciphers
```
then `PRAGMA key = '<key from flutter_secure_storage>'` in the `setup:` callback [DOC drift.simonbinder.eu/platforms/encryption].

**⚠️ The one real iOS risk, and it is the R-003 class of problem.** Uqudo's iOS pod requires `OpenSSL-Universal` pinned to **exactly** 3.3.3001, and any other version crashes the SDK [OBSERVED docs/components/uqudo-sdk.md, from the podspec]. SQLCipher links OpenSSL in some build configurations. I chose `sqlite3mc` over `sqlcipher` specifically because SQLite3 Multiple Ciphers embeds its own crypto implementations rather than linking OpenSSL — but **[UNVERIFIED: that `sqlite3mc` as bundled by `sqlite3` 3.x introduces no OpenSSL symbol into the iOS binary. This must be checked at S1-03, not assumed.]** If it does conflict, the fallback is an unencrypted local database with field-level encryption of the handful of PII fields using a key from the Keychain — more code, no linker risk.

### Alternatives considered

- **`sembast`** — pure Dart, no native code at all, therefore **zero** iOS link risk. This is the fallback if the OpenSSL check above fails and field-level encryption is judged too fiddly. Cost: it is a document store with an in-memory index and an append-only log file; the four-stage draft, the outbox and the reference cache all become hand-rolled query code, and there is no migration framework.
- **`objectbox` 5.3.2** — iOS listed, Apache 2.0, requires iOS deployment target 15.0 (we are already at 15.0, so no change), Sync is a paid feature we would not use [OBSERVED pub.dev]. Fast, but its object model does not mirror the server's relational one, and it adds a second native library alongside Uqudo's.
- **`isar`** — stable release **3.1.0+1, published roughly three years ago**, with only `4.0.0-dev.*` prereleases since [OBSERVED pub.dev/packages/isar, fetched 2026-08-22]. Whatever its technical merits, a three-year-old stable in a bank deliverable with a mandatory iOS target is an unacceptable maintenance risk. Rejected.
- **`hive` / `hive_ce`** — key-value, no query model, no migration story. Rejected for a four-stage draft with a reconcile rule.
- **raw `sqflite`** — uses the iOS system SQLite, so the smallest binary and no build hooks. Loses drift's generated type-safe schema, its migration framework, and encryption. A reasonable degraded choice, not the first one.

### Two databases, not one

| | `session.db` | `reference.db` |
|---|---|---|
| Holds | draft, progress, outbox | cached reference lists and their versions |
| Encrypted | yes | not required (no PII) |
| Cleared on completion | **yes** | **no** |
| Cleared on abandonment | **yes** | **no** |

Clearing the reference cache with the session would leave the next customer on a shared device offline with empty pickers. This separation is not an optimisation; it is required by the interaction of "local state is cleared on completion and on abandonment" with "stages 3–6 must work with no connection".

### What `session.db` holds

- `local_draft` — every customer-entered field from stages 1b and 3–6 plus the stage 7 identity type, written as the customer types, with a per-field `updated_at` so the stage 12 device-wins reconcile is field-level rather than row-level.
- `local_progress` — `profile_id`, `session_id`, `resume_stage`, and the *last-known* backend answer with an `is_stale` flag.
- `local_outbox` — queued mutations with their idempotency keys, and **transiently, the raw Uqudo JWS awaiting upload**, stored as an opaque string. This is the one system-derived artifact the device holds, and it is held as a pending transmission, never as state: the app does not decode it, does not parse it, does not display anything from it, and deletes it on acknowledgement or when the backend reports `ARTIFACT_EXPIRED` / `IMAGES_UNAVAILABLE`. That is consistent with the CLAUDE.md hard rule and with the stage 12 retry design.
- `local_attachment_queue` — the salary certificate's local file path, size and upload state. It gates nothing.

### What it **never** holds

Any parsed Uqudo field; the national number; the Civil Registry data; the extracted portrait or any document image; `face.match`, `matchLevel`, `mrzVerified`; the account status; the profile status as truth; the verified-channel states as truth; OTP codes; Uqudo access tokens (minted at point of use, dropped after the call); anything operator-side. **Stage 9's screen renders directly from the backend's single display payload and is not persisted** — a resume re-fetches it, which is also what makes "the backend alone decides whether the session is still open" true in practice.

### Device migrations

drift's versioned `MigrationStrategy`, with `drift_dev`'s generated schema snapshots and migration tests (`schema dump` → `schema generate` → `schema steps`) so an upgrade path is covered by the mobile coverage gate rather than discovered on a customer's phone. **The device schema version is numbered independently of the server's and the two must never share a numbering scheme** — they are not migrated together and a coincidental match would invite exactly the wrong assumption.

---

## 9. Reference-data versioning

**How a version is identified.** `(list_code, version int)` is the addressable identity; `content_hash` — SHA-256 over the canonical serialisation of the ordered item list — is the *true* identity. The integer is monotonic per list, assigned at publication, and never reused. Two publications with the same content produce the same hash, which lets the app skip a download it does not need.

**How the app checks it.** `GET /reference/manifest` returns `{ lists: [{ listCode, version, contentHash, itemCount }], manifestEtag }`. The app sends `If-None-Match: <manifestEtag>`; an unchanged manifest costs a 304 and no body. For each list whose `contentHash` differs from the cached one, `GET /reference/lists/{listCode}?version=N` returns the full item set; the app recomputes the content hash itself before accepting it and swaps the cache in one local transaction. The check is opportunistic and **never blocking** — offline, the cache is served and the journey proceeds, because customer.md requires stages 3–6 to work with no connection.

**How a submission records which version it used.** At stage 11 the app sends the versions it actually rendered from. The backend writes `ref.profile_reference_version` rows and **rejects the submission if it claims a version the server never published** — the FK enforces this at the database level. Two consequences worth stating: an offline customer can legitimately submit against a superseded version, which is exactly why the version is recorded; and `reference_list_version` rows can therefore never be deleted.

**Which lists.** occupation, admin_division, branch, income_source recorded on the profile; rejection_reason recorded on the status-history row that used it, since it is an operator-side choice made later. education_level is versioned like the rest even though customer.md calls it exhaustive — the cost is one row and the alternative is a hardcoded list, which CLAUDE.md forbids.

**The duplicate occupation codes** (مبرمج 86/133, ممرض 43/139, موظف حكومة 9/135) are carried as `is_active = false` rows rather than omitted, so a profile that somehow references one still resolves to a label.

---

## 10. Arabic collation and search

**The finding that drives everything in this section:** ordering and matching are two different problems, and Arabic orthographic equivalence is **not** a collation problem.

- Unicode canonical decomposition relates أ (U+0623) to ا + U+0654, so a sufficiently weak collation strength *may* collapse them. It does **nothing** for ة/ه (U+0629 / U+0647) or ى/ي (U+0649 / U+064A) — those have no canonical relationship and are distinct primary weights in any Unicode collation. No `ks` level, no accent-insensitive collation, and no NFC/NFD normalisation makes them equal.
- The direct evidence: Lucene ships an explicit `ArabicNormalizer` whose documented job is exactly "normalization of hamza with alef seat to a bare alef; normalization of teh marbuta to heh; normalization of dotless yeh (alef maksura) to yeh; removal of Arabic diacritics (the harakat) and removal of tatweel" [DOC Lucene 9.12.1 API, `org.apache.lucene.analysis.ar.ArabicNormalizer`]. That set is character-for-character the set customer.md declares mandatory for the occupation picker. It exists as a separate filter precisely because collation does not provide it.
- PostgreSQL's `unaccent` module does not help: its shipped rules file is "directly useful for most European languages" [DOC postgresql.org/docs/current/unaccent.html] and would require a custom rules file installed into `$SHAREDIR/tsearch_data/` on the bank's server — a filesystem dependency I would rather not take into an environment we do not control.

**Conclusion: an explicit fold function, defined once, implemented twice (SQL and Dart), pinned by a shared golden-vector fixture.**

### PostgreSQL — the concrete configuration

```sql
-- Cluster/database: UTF8, ICU provider so ordering is identical on Windows dev and Linux prod.
CREATE DATABASE fru
  ENCODING 'UTF8' LOCALE_PROVIDER icu ICU_LOCALE 'ar' TEMPLATE template0;
```
Predefined ICU collations are named in BCP-47 form with `-x-icu` appended, e.g. `ar-x-icu`, `und-x-icu` [DOC postgresql.org/docs/current/collation.html]. ICU is compiled in by "Apt, Rpm and the EDB installer" [DOC same page]. **[UNVERIFIED for the specific PG18 EDB Windows build on Windows 10 — check with `SELECT collname FROM pg_collation WHERE collname = 'ar-x-icu';` on first install.]**

```sql
CREATE FUNCTION ref.ar_fold(t text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT AS $$
  SELECT lower(
    regexp_replace(
      translate($1,
        --  أ  إ  آ  ٱ  ة  ى     Arabic-Indic digits
        'أإآٱةى' || '٠١٢٣٤٥٦٧٨٩' || '۰۱۲۳۴۵۶۷۸۹',
        'ااااهي' || '0123456789'   || '0123456789'),
      -- tatweel, harakat, superscript alef, extended marks, bidi/zero-width controls
      '[ـً-ْٰۖ-ۭ​-‏‪-‮]', '', 'g')
  );
$$;
```
The `from` and `to` arguments of `translate` must match character-for-character — the pairing above is `أ→ا إ→ا آ→ا ٱ→ا ة→ه ى→ي`, matching Lucene's set plus alef wasla. `\uwxyz` hex escapes are valid inside bracket expressions in PostgreSQL's ARE, and for a UTF-8 database "escape values are equivalent to Unicode code points" [DOC postgresql.org/docs/current/functions-matching.html, Table 9.20].

Digit folding is separated deliberately: fold Arabic-Indic digits in *names and free text*, but validate the account number and the national number at input, never by search folding.

```sql
ALTER TABLE ref.reference_item
  ALTER COLUMN search_ar SET DEFAULT NULL;   -- populated as a generated column:
-- CREATE TABLE … search_ar text GENERATED ALWAYS AS (ref.ar_fold(label_ar)) STORED
CREATE INDEX reference_item_search_trgm ON ref.reference_item USING gin (search_ar gin_trgm_ops);
CREATE INDEX profile_name_search_trgm   ON app.registry_result
  USING gin (ref.ar_fold(full_name_ar) gin_trgm_ops);
```

**Three verification items I could not close from documentation:**

1. **`ref.ar_fold` must genuinely be immutable** for the generated column and the expression index to be safe. `translate`, `regexp_replace` and `lower` are believed immutable but the docs do not state volatility. **[UNVERIFIED — confirm with `SELECT proname, provolatile FROM pg_proc WHERE proname IN ('translate','regexp_replace','lower','normalize');` and require `i` for each. If `lower` returns `s` — it is collation-dependent — replace it with `translate` over the ASCII alphabet, since the Arabic path does not need case folding at all.]**
2. **pg_trgm on Arabic is not documented.** The manual says only that trigrams work "in many natural languages" and that "pg_trgm ignores non-word characters" [DOC postgresql.org/docs/current/pgtrgm.html]; it says nothing about multibyte. The source does contain explicit multibyte handling, and secondary sources report it working for non-Latin scripts provided the database is not in the `C` locale. **[UNVERIFIED — spike: load the real 138-item occupation list and ~200 synthetic Arabic names, then measure recall for the queries customer.md names ("هندس" must find مهندس) with and without the index.]** If it disappoints, the fallback is a `tsvector` over the folded text with the `simple` configuration (not `arabic_stem` — stemming an occupation label is wrong) plus a GIN index; that is encoding-safe by construction and still PostgreSQL.
3. **Never index a user-text column under a non-C collation.** "A change in collation definitions can lead to corrupt indexes" and the remedy is `REINDEX` plus `ALTER COLLATION … REFRESH VERSION` [DOC postgresql.org/docs/current/sql-altercollation.html]. Since the bank chooses their own ICU version, every index we create is on `ar_fold(...)` — a pure codepoint transformation, indexed under the default/C collation — so an ICU upgrade on their server can only change *display ordering*, never index validity. This is the single most valuable consequence of separating fold from collation and it should not be traded away.

### Ordering — and the non-obvious device consequence

customer.md requires that an empty occupation search shows the full list "in Arabic alphabetical order". That list is rendered **on the device, offline**. Dart's `String.compareTo` is UTF-16 code-unit order, which is not Arabic alphabetical order, and Flutter has no bundled ICU collator we can rely on.

**Therefore the server computes the ordering and ships it.** `ref.reference_item.sort_ordinal` is populated at publication time with `row_number() OVER (ORDER BY label_ar COLLATE "ar-x-icu")`, and the device simply sorts by that integer. The device never attempts Arabic collation. This is a requirement on the reference-data payload (AD-002f territory, not AD-002a) and it is easy to miss until the picker looks wrong on a phone.

### Per-option Arabic configuration

| | PostgreSQL 18 | MariaDB 11 / MySQL 8.4 | SQL Server 2022 |
|---|---|---|---|
| Encoding | `UTF8` | `utf8mb4` | `UTF8` collation suffix |
| Display ordering | `COLLATE "ar-x-icu"` (ICU, platform-consistent) | `utf8mb4_0900_ai_ci` / `uca1400_ai_ci` | `Arabic_100_CI_AI_SC_UTF8` |
| Orthographic fold | `IMMUTABLE` SQL function + generated column + expression index | Deterministic SQL function + **virtual generated column** (MariaDB) or **functional index** (MySQL 8.0.13+) | `PERSISTED` computed column + index |
| Substring search | `pg_trgm` GIN, or `tsvector` with `simple` config | Unindexed `LIKE '%…%'`; the `ngram` FTS parser is CJK-oriented. **Weakest.** | Full-text search with the Arabic word breaker (LCID 1025). **Strongest for phrase search**, still needs the fold for equivalence. |
| Deployment risk | ICU must be compiled in — it is, in Apt/Rpm/EDB builds [DOC] | none notable | none notable |

### The Dart side

~15 lines implementing the same four transformations, applied to the **query only** (list items arrive pre-folded in `search_ar`). The two implementations are pinned together by a single JSON fixture of input→expected pairs, committed once and read by both `mobile/test` and the backend's tests. Without that fixture the two folds will drift, and the symptom — a customer typing the natural spelling and being told their occupation does not exist — is exactly the failure customer.md warns about.

---

## 11. Migration tooling

**Flyway 12.4.0**, the version Spring Boot 4.1.x manages [DOC docs.spring.io dependency-versions/coordinates, Spring Boot 4.1.1: `org.flywaydb:flyway-core` 12.4.0]. `flyway-core` is Apache 2.0 [DOC github.com/flyway/flyway]. Since Flyway 10, database support is modular, so `org.flywaydb:flyway-database-postgresql` must be declared explicitly alongside it.

**Why not Liquibase, which Spring Boot also manages (5.0.3):** Liquibase Community 5.x changed its licence from Apache 2.0 to **FSL-1.1-ALv2** — the Functional Source License, source-available, converting to Apache 2.0 "on the second anniversary of the date we make the Software available" [DOC github.com/liquibase/liquibase LICENSE.txt]. Our use is permitted (we are not building a competing migration tool), but it is a non-OSI licence in a deliverable heading into a bank's procurement review, and Liquibase 5 additionally unbundles drivers and extensions from the free distribution [DOC/secondary liquibase.com]. Flyway's plain-SQL, Apache-2.0 story is the lower-friction one for a solo developer handing a product to a third party.

**Conventions:**

- `backend/src/main/resources/db/migration/V0001__baseline.sql` onwards; `R__` repeatables for views and functions only.
- `spring.jpa.hibernate.ddl-auto=validate`, never `update`. FIB ran `ddl-auto: none` with **no migration tool at all** [OBSERVED `../FIB/.../application.yml` and `pom.xml` — no Flyway, no Liquibase]; the schema evidently lived outside version control. We do not repeat that.
- **Roles and grants are migrations.** `V0002__roles_and_grants.sql` creates `fru_app`, `fru_purge`, `fru_auditor` and applies every REVOKE in §5. The append-only guarantee is then reproducible from the repository rather than being something someone once typed into psql.
- Flyway runs as `fru_migrator`, which owns everything. The application connects as `fru_app` and has no DDL rights at all, so an ORM cannot alter the schema even by accident.
- A `beforeMigrate` callback sets `fru.migration_in_progress = 'on'` so the DDL event trigger (§5, layer 3) stands down for legitimate migrations.
- **Reference-data publication is not a schema migration.** New list versions are data, inserted through an authenticated admin endpoint that computes the content hash and the `sort_ordinal` server-side. Only the initial seed is a repeatable migration.
- On PostgreSQL every migration runs inside a transaction and a failure rolls back automatically [DOC Redgate migration transaction handling] — so a broken migration leaves no half-applied schema and no manual repair.
- **Gate:** Flyway runs against a Testcontainers PostgreSQL inside `./mvnw verify`, so a migration that does not apply cleanly fails the build. Testcontainers needs Docker Desktop on the Windows box; the no-Docker fallback is Zonky embedded-postgres, whose Windows amd64 binaries exist and are published up to PostgreSQL 17.x [OBSERVED Maven Central `io.zonky.test.postgres:embedded-postgres-binaries-windows-amd64`] — but **[UNVERIFIED whether those binaries include ICU, which our collation needs. Check before adopting the fallback.]**
- Device migrations are drift's `MigrationStrategy`, versioned independently (§8).

---

## What I could not determine

1. **Actual scale.** OQ-004 is unanswered. Every figure in §2 is my assumption, stated so you can reject it. The recommendation does not depend on the exact number, but the sizing and the partitioning decision do.
2. **Whether `pg_trgm` gives usable recall on Arabic.** The documentation is silent on multibyte [DOC pgtrgm.html]. The fallback exists and is still PostgreSQL, so this is a tuning risk, not a technology risk — but I did not verify it.
3. **Whether `ref.ar_fold`'s constituent functions are truly `IMMUTABLE`.** Function volatility is not in the reference documentation. The exact `pg_proc` query to settle it is in §9.
4. **Whether `text::jsonb` is permitted in a `GENERATED ALWAYS AS … STORED` expression.** Needs one statement against a live server. Fallback stated.
5. **Whether the PG18 EDB Windows installer supports Windows 10 desktop.** The published table lists Windows Server 2025/2022 only [DOC postgresql.org/download/windows]. Docker sidesteps this.
6. **Whether `sqlite3mc` introduces an OpenSSL symbol into the iOS binary**, which would collide with Uqudo's exactly-pinned OpenSSL-Universal 3.3.3001. This is the single most consequential unknown in the mobile half and it cannot be settled without an iOS build (S1-03, AD-003).
7. **Whether MySQL/MariaDB/SQL Server accent-insensitive collations collapse ة/ه and ى/ي.** I asserted they do not, on the general Unicode reasoning plus the existence of Lucene's normalizer; I did not measure it on those engines. It does not change the recommendation, since all three need the explicit fold either way.
8. **The Civil Registry response format** (AD-002b). `audit_artifact.body bytea` + `media_type` is deliberately format-agnostic for that reason, and `registry_result`'s columns come from the field-provenance table in customer.md, not from a real response.
9. **Whether SQL Server ledger tables are truly available in every edition.** Microsoft's edition-comparison page was reported as listing it across editions [DOC/secondary], but I read that through a search summary rather than the table itself. If the SQL Server flip condition ever fires, verify it directly.

---

## Risks if the recommendation is wrong

| # | Risk | Cost to reverse |
|---|---|---|
| 1 | The bank mandates SQL Server or Oracle after we have built on PostgreSQL | **1–2 weeks** for SQL Server (rewrite the audit trigger, the fold, the partial unique indexes, `SKIP LOCKED`), **2–3 weeks** for Oracle (add no transactional DDL, so also rewrite the migration discipline). The schema is deliberately plain SQL to keep this bounded. Mitigate by asking the platform question now. |
| 2 | An auditor rejects an application-implemented hash chain in favour of a vendor-attested one | Moderate. The chain stays; the seal export becomes the negotiating artifact. Worst case is the SQL Server flip, priced at #1. Mitigate by writing the seal export from day one and getting the format in front of whoever will audit it. |
| 3 | `pg_trgm` recall on Arabic is poor and the operator search is unusable | Small — swap the index for a `tsvector` over folded text, ~1 day. Contained, because the *offline occupation picker* does its own matching on the device and does not depend on the database at all. |
| 4 | `sqlite3mc` collides with Uqudo's pinned OpenSSL on iOS | Moderate and late-discovered, which is what makes it dangerous. Fallback is field-level encryption or `sembast`, ~3–5 days. This is R-003 materialising in a new place; record it there. |
| 5 | Audit artifact bodies turn out far larger than assumed | Small. Move `audit_artifact.body` to object storage under AD-004 and keep the `sha256` in the row. The chain is already designed to hash the digest, so nothing about tamper-evidence changes. |
| 6 | The 90-day PII purge and the 7-year audit retention are found to be legally incompatible | Not a technology risk — OQ-001 owns it. The schema already permits both readings via `audit_artifact.body_purged_at` without breaking verification. |
| 7 | Single-instance PostgreSQL becomes a single point of failure the bank will not accept | None to the schema. Streaming replication and any managed offering solve it; this is AD-002d's problem, not AD-005's. |
| 8 | Two separate physical databases are demanded for audit | **High.** It breaks the atomicity in §5A and forces a saga or an outbox-per-store. Named here so it is a decision rather than a surprise. |

---

## Contradiction found — must be reconciled before implementation

**`docs/journeys/customer.md`, the audit events list, still says:** "Liveness attempt: **liveness result and score, face-match result and score, recorded separately**."

**AD-002a and `docs/components/uqudo-sdk.md` establish that Uqudo returns no liveness field and no score of any kind, and that a liveness failure produces no signed artifact at all** — only a thrown `PlatformException` terminating as `SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS`. PROJECT_PLAN and R-016 already record the correction; customer.md's Stage 10 body was corrected but this line in the audit-events list was not. My `app.face_result` table therefore has no liveness-score column and cannot have one. The two failure modes are distinguished by *channel* — a signed JWS with `match:false` versus a thrown error — and recorded as two different audit event types (`face_match_evaluated` and `liveness_attempt_terminated`). This is a stale line in the journey doc, not a design gap, but it needs correcting before someone implements against it.

---

## Note on the AD-002a contract and the journey specification

Nothing here redesigns either, but the persistence design places three **additive** requirements on the wire contract, all outside AD-002a's settled Uqudo shape and inside AD-002f's still-open reference-data delivery: (a) two idempotency responses, `409 REQUEST_IN_PROGRESS` for a duplicate arriving during a long chain and `422 IDEMPOTENCY_KEY_REUSED` for a key replayed with a different body; (b) a `sort_ordinal` integer on every reference item, because the device cannot compute Arabic alphabetical order and must not try; and (c) a per-list `contentHash` in the reference manifest so the app can verify a downloaded list before replacing its cache. None of these changes a settled decision; if any of them is judged to be a contract change rather than an addition, that judgement should be made before AD-005 closes, not during implementation.

---

## Card updates

No `docs/components/` card exists for persistence. Two actions.

### (a) Correct `docs/journeys/customer.md`

In "Events recorded — customer side", replace:

```
- Liveness attempt: **liveness result and score, face-match result and score, recorded
  separately**.
```

with:

```
- Face session: recorded as TWO distinct event types, because the two failure modes reach
  the backend through different channels.
  - `face_match_evaluated` — a signed JWS was returned. Records `face.match` (bool),
    `face.matchLevel` (1–5), and the server-side threshold applied. [DOC AD-002a]
  - `liveness_attempt_terminated` — no JWS was produced. Records the SDK error code
    (`SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS`) and the attempt number.
  **There is no liveness score. Uqudo does not return one.** [DOC docs/components/uqudo-sdk.md]
```

### (b) Draft card — `docs/components/persistence.md`

```markdown
# Component: Persistence

Status: researched, not implemented · Last verified: 2026-08-22
Sources: docs/sessions/2026-08-22-research-ad-005-persistence.md

## Decision (proposed, AD-005 — NOT YET SETTLED)
PostgreSQL 18, one instance, three schemas: `app` (profile), `audit` (append-only),
`ref` (reference-data version registry). Flyway 12 for migrations. No Redis.
Mobile: `drift` + `sqlite3` 3.x, two local databases (session, cleared; reference, kept).

## Versions and licences
| Component | Version | Licence | Marker |
|---|---|---|---|
| PostgreSQL | 18 (EOL 2030-11-14) | PostgreSQL Licence | [DOC postgresql.org/support/versioning] |
| Flyway core | 12.4.0 (managed by Spring Boot 4.1.x) | Apache 2.0 | [DOC spring.io coordinates; github.com/flyway/flyway] |
| flyway-database-postgresql | required separately since Flyway 10 | Apache 2.0 | [DOC/secondary] |
| Liquibase (rejected) | 5.0.3 | **FSL-1.1-ALv2**, converts to Apache 2.0 after 2 yrs | [DOC liquibase LICENSE.txt] |
| postgresql JDBC | 42.7.13 (managed) | BSD-2 | [DOC spring.io coordinates] |
| Hibernate ORM | 7.4.5.Final (managed) | Apache 2.0 | [DOC spring.io coordinates] |
| drift | 2.34.3, iOS supported | MIT | [OBSERVED pub.dev 2026-08-22] |
| sqlite3 (Dart) | 3.5.2, iOS arm64 device + sim prebuilt | MIT | [OBSERVED pub.dev 2026-08-22] |
| flutter_secure_storage | 11.0.0, iOS Keychain | BSD-3 | [OBSERVED pub.dev 2026-08-22] |

## Do NOT use
- `sqlite3_flutter_libs` (0.6.0+eol) and `sqlcipher_flutter_libs` (0.7.0+eol) — both are
  no-ops after `sqlite3` 3.x. `drift_flutter` 0.3.1 still depends on both. [OBSERVED pub.dev]
- `isar` — stable 3.1.0+1 last published ~3 years ago, only dev prereleases since. [OBSERVED]
- PostgreSQL `ENUM` types for status codes — `ALTER TYPE ADD VALUE` interacts badly with
  Flyway's transactional migrations on PostgreSQL. Use `text` + FK to a lookup table.
- `spring.jpa.hibernate.ddl-auto=update`. Ever. `validate` only.
- Redis. FIB carried it [OBSERVED ../FIB/.../pom.xml]; we do not need it at this scale and
  every candidate use (OTP state, idempotency, outbox) wants to be transactional.
- Indexing any user-text column under a non-C collation. Index `ref.ar_fold(col)` instead —
  a collation-version change corrupts collation-dependent indexes.
  [DOC postgresql.org/docs/current/sql-altercollation.html]

## Arabic — the rule
Ordering is a collation problem; orthographic equivalence is NOT. No collation strength
collapses ة/ه or ى/ي. Use `COLLATE "ar-x-icu"` for display ordering only; use the
`ref.ar_fold()` function (أ إ آ ٱ→ا · ة→ه · ى→ي · strip tatweel + harakat) for every
equality and substring comparison, on both tiers, pinned by a shared golden-vector fixture.
Lucene ships `ArabicNormalizer` doing exactly this set, which is the evidence that
collation does not. [DOC Lucene 9.12.1 ArabicNormalizer]
`sort_ordinal` is computed server-side under `ar-x-icu` and shipped to the device — Dart
cannot collate Arabic.

## Append-only — the four mechanisms
1. `fru_app` does NOT own the audit tables (an owner always holds all privileges
   [DOC sql-revoke]); it holds INSERT+SELECT only.
2. BEFORE UPDATE/DELETE/TRUNCATE triggers raising `ERRCODE 42501`.
3. An event trigger blocking DDL on schema `audit` outside a migration session.
4. A SHA-256 hash chain written by a SECURITY DEFINER BEFORE INSERT trigger, with a
   nightly `audit_seal` exported outside the database.
Built-in `sha256(bytea)` — no pgcrypto needed. [DOC functions-binarystring.html]
Hash covers `audit_artifact.sha256`, never the body, so a lawful PII purge of the body
leaves the chain verifiable.

## Open verification items
- [ ] `SELECT proname, provolatile FROM pg_proc WHERE proname IN ('translate','regexp_replace','lower','normalize');` — all must be `i` for `ar_fold` to be safely IMMUTABLE
- [ ] Is `text::jsonb` accepted in `GENERATED ALWAYS AS … STORED`?
- [ ] `SELECT collname FROM pg_collation WHERE collname='ar-x-icu';` on the Windows dev install
- [ ] pg_trgm recall on the real 138-item occupation list and ~200 Arabic names
- [ ] S1-03: does `sqlite3mc` link OpenSSL on iOS? Collides with Uqudo's pinned
      OpenSSL-Universal 3.3.3001 if so (R-003)
- [ ] Does Zonky embedded-postgres (Windows amd64) include ICU?
- [ ] Ask the bank: platform standard for a delivered application database
```

---

## Noticed in passing

1. **`../FIB/backend server fib/utility/src/main/resources/application.yml` contains live credentials in plaintext, committed to the repository** — a database password, third-party client secrets, a partner secret, and a keystore password, for at least three different systems. No values are reproduced here and none should be. Worth raising with whoever owns that repository; it is also the reason our own CLAUDE.md rule about gitignored local config exists.
2. **FIB used the bank's Oracle instance as its own application database** [OBSERVED that file: `spring.datasource` → an Oracle host, `OracleDialect`, `ddl-auto: none`], with a Hikari pool configured for `maximum-pool-size: 2323` against `minimum-idle: 40`. Precedent, and a direct contradiction of our constraint that the bank's Oracle is read-only through one stored procedure.
3. **FIB had no schema migration tooling at all** — no Flyway, no Liquibase in `pom.xml`, and `ddl-auto: none`. Its schema lived outside version control.
4. `backend/pom.xml` is on **Spring Boot 4.1.0**; the dependency-version page I could reach documents **4.1.1**. The managed versions cited in this report are 4.1.1's and may differ by a patch. Worth a bump to 4.1.1 regardless.
5. **`ojdbc` is still deliberately absent from `backend/pom.xml`** pending OQ-006 [OBSERVED, with an explanatory comment in the file]. Nothing in this report changes that: the Oracle driver is for `ProcessOmniCheckAct` only and is a second, read-only `DataSource`, entirely separate from the PostgreSQL one.

---

## Commit/push proof

```
$ git log --oneline -1
8833983 docs: file AD-005 persistence research, absorb AD-002g

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```
