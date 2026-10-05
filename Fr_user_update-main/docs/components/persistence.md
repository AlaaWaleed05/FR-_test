# Component: Persistence

Status: implemented (schema complete; first application writer landed at S3-01) · Last verified: 2026-09-01
Sources: docs/sessions/2026-08-22-research-ad-005-persistence.md; docs/sessions/2026-09-01-s5-03-local-encryption.md

## Decision (proposed, AD-005 — NOT YET SETTLED)
PostgreSQL 18, one instance, three schemas: `app` (profile), `audit` (append-only),
`ref` (reference-data version registry). Flyway 12 for migrations. No Redis.
Mobile: `drift` + `sqlite3` 3.x, two local databases (session, cleared; reference, kept).
`session.sqlite` is `sqlite3mc`-encrypted since S5-03 — see "Mobile local store — encryption"
below; `reference.sqlite` stays plain, no PII.

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

- The fold has **three** implementations, not two: `ref.ar_fold` (SQL, V0011), `arFold`
  (Dart, mobile picker query string), and `arFold` (TypeScript, back-office `Table`
  `filterSearch`). One shared golden-vector fixture must pin all three — filed as BL-014,
  not yet built. [2026-08-30 research, S3-10]
- **The device folds only the query string, not the labels.** `ref.reference_item.search_ar`
  is `GENERATED ALWAYS AS (ref.ar_fold(label_ar)) STORED` [OBSERVED V0011:64], and since
  S3-10 `search_en` is `GENERATED ALWAYS AS (ref.ar_fold(label_en)) STORED` too (V0038) —
  so folded labels in both languages arrive from the server. This narrows the Dart/TS
  obligation; it does not remove it, and the golden vectors still apply. [OBSERVED]
- **`ar_fold` does not fold decomposed hamza.** `translate()` maps precomposed أ إ آ ٱ ة ى;
  the regexp strips U+0640, U+064B–U+0652, U+0670, U+06D6–U+06ED and bidi controls
  [OBSERVED V0011:44-49]. **U+0653–U+0655 (maddah above, hamza above, hamza below) are in
  neither set**, so an NFD-decomposed أ does not fold to ا. Low probability from a standard
  Arabic soft keyboard; decide it once — extend the range, prepend `normalize(t, NFC)`, or
  record it out of scope — and put a decomposed case in BL-014's golden vectors before the
  second implementation is written. [OBSERVED, 2026-08-30]

## Append-only — the four mechanisms
1. `fru_app` does NOT own the audit tables (an owner always holds all privileges
   [DOC sql-revoke]); it holds INSERT+SELECT only.
2. BEFORE UPDATE/DELETE/TRUNCATE triggers raising `ERRCODE 42501`.
3. An event trigger blocking DDL on schema `audit` outside a migration session.
4. A SHA-256 hash chain written by a SECURITY DEFINER BEFORE INSERT trigger, plus the
   audit seal (below) as the external anchor.
Built-in `sha256(bytea)` — no pgcrypto needed. [DOC functions-binarystring.html]
Hash covers `audit_artifact.sha256`, never the body, so a lawful PII purge of the body
leaves the chain verifiable.

## The audit seal (S2-04) — status: **built and proven**, not yet operated

The hash chain alone is only self-consistent: anyone who can rewrite the whole
`audit.audit_event` table (a compromised `fru_migrator`, a restored backup, direct
data-file access) can also recompute every `content_hash`/`row_hash` forward and update
`audit.audit_chain.head_hash` to match — `audit.verify_chain()` would then report
`ok = true` over data that was tampered with. The seal closes that gap: a periodically
created, immutable, externally-exported snapshot of each chain's head, so a later
comparison against that snapshot exposes an edit made before the snapshot was taken,
even if the chain was made internally consistent again afterward.

**Two tables.** `audit.audit_seal` (V0002) holds one aggregate row per seal run:
`sealed_at` (server time), `chain_count`, `seal_hash` (sha256 over every chain's
`(chain_id, head_seq, head_hash)`, ordered by `chain_id`), `prev_seal_hash` (chains
seals to each other, so the seal history's own order/completeness is itself
tamper-evident), `exported_at`, `export_note`. `audit.audit_seal_chain` (V0031, new)
holds the per-chain breakdown the aggregate alone cannot provide: one row per
`(seal_id, chain_id)` recording `head_seq`, `head_hash`, and an independently-scanned
`row_count`.

**Both tables are themselves append-only.** `audit.audit_seal_chain` accepts no
UPDATE/DELETE at all. `audit.audit_seal` accepts exactly one narrow update per row — a
one-time `NULL → value` set of `exported_at`/`export_note` — enforced by
`audit.seal_guard()` (V0032/V0033). Without this, the seal would be the weak link: a
rewriter could tamper the chain and patch the seal to match.

**Production.** `audit.seal_create()`, a SQL function with no application code and no
scheduler. A service layer now exists (S3-01) but no scheduled-job infrastructure does,
and nothing calls this. **Operational requirement, not yet met by anything in this repository: something
outside this codebase (cron, Windows Task Scheduler, a future backend scheduler) must
invoke `SELECT audit.seal_create();` periodically.** Nothing currently does. This
mirrors Layer 3's `db/post-migrate/` precedent: a documented, externally-invoked SQL
step, not a migration.

**Who writes it.** A new role, `fru_sealer` (V0030), distinct from `fru_app` and
`fru_migrator`, with `SELECT` on `audit_chain`/`audit_event`/`audit_artifact`/
`audit_seal`/`audit_seal_chain`, `INSERT` on the two seal tables, and a column-limited
`UPDATE` on `audit_seal` for `exported_at`/`export_note` only. The `audit_artifact`
grant is required — `seal_verify()` reads `audit_artifact.sha256` for every
artifact-linked event, i.e. essentially every real profile chain (Uqudo JWS, Civil
Registry, `ProcessOmniCheckAct`); its absence was found live in review, where it broke
verification for `fru_sealer` itself. `fru_app` gains no grant on either seal table —
the application does not seal its own audit trail; if it could, a single compromised
`fru_app` credential could tamper the chain and reseal over its own tampering. Proven
live (§4 below): `fru_app` cannot read or write `audit.audit_seal`/
`audit.audit_seal_chain` at all, and cannot call `seal_create()`/`seal_verify()` either
(SECURITY INVOKER — they fail with the same permission errors `fru_app` would get
querying the tables directly).

**Two correctness properties `seal_create()` must hold, found live in review:**
`chain_count`, `seal_hash` and the `audit_seal_chain` rows it writes must all describe
the same instant — PostgreSQL's default READ COMMITTED takes a fresh snapshot per
statement, not per transaction, so three separate reads of `audit_chain`/`audit_event`
could otherwise see a `chain_append()` land in between and produce a seal whose
aggregate hash does not match its own per-chain rows, permanently and unrepairably
(both tables are append-only). Fixed by capturing everything in one `INSERT ... SELECT`
into a temp table before deriving anything from it. Separately, two overlapping
`seal_create()` calls could both read the same `prev_seal_hash` and both insert,
forking the seal-of-seals chain; fixed with a `pg_advisory_xact_lock` serializing
callers, the same role `chain_append()`'s `SELECT ... FOR UPDATE` plays per chain.

**Verification — `audit.seal_verify(p_seal_id)`.** For each chain the seal covers,
recomputes the hash chain from raw `audit_event` content alone (never from
`audit_event.content_hash`/`row_hash` or `audit_chain.head_hash`, all of which a
rewriter could also overwrite to look self-consistent) up to the sealed `head_seq`, and
compares the result only against the immutable `audit_seal_chain.head_hash`. Reports
`matches` (chain unchanged since sealing), `grew_consistently` (sealed prefix intact,
more events appended since — expected), or `diverged` (the sealed prefix does not
reproduce the sealed hash). **Proven live against the recompute attack** — tamper a row
inside the sealed prefix, then recompute every hash column forward plus
`audit_chain.head_hash` so `audit.verify_chain()` reports `ok = true`: `seal_verify()`
still reports `diverged`, because it never trusted the columns the attack rewrote. See
docs/sessions/2026-08-27-s2-04-audit-seal.md for the full transcript.

**Verification of the seals themselves — `audit.seal_verify_history()`.**
`seal_verify()` checks a chain's data against its seal; this checks the seal-of-seals
chain — recomputes each seal's `seal_hash` from its own `audit_seal_chain` rows and
confirms `prev_seal_hash` correctly points at the prior seal's (recomputed, not stored)
hash, or the genesis constant for the first. Added in review because nothing otherwise
verified those two columns, so a corrupted or forked seal-of-seals chain would go
undetected; proven live by forging `seal_hash` directly (with the same disable-trigger
bypass technique used elsewhere) and confirming detection, then reverting to clean.

**What "exported" means, stated plainly: a seal that is created but never copied out of
this database provides no protection against the threat it exists for** — it is just
another row a rewriter who already controls the database controls too.
`audit.seal_render(p_seal_id)` produces a stable, canonical text rendering of one seal
suitable for `psql -t -A -c "SELECT audit.seal_render(N)" > seal_0000000N.txt`;
`audit.seal_mark_exported(p_seal_id, p_note)` records that the copy happened. **The
actual export destination (WORM bucket, printed register, email, etc.) is
[UNVERIFIED] / not chosen** — explicitly out of scope for S2-04, which states this
requirement rather than building an uploader.

Regression proofs (the status-transition guard, the no-history-row-fails
guard, and the original S2-01 append-only checks against `audit_event`/`audit_artifact`
for both `fru_app` and `fru_migrator`) were re-run unchanged after this work and all
still pass — see the S2-04 session report §5.

**A deployment-ordering fact confirmed live by this task, relevant to any future
migration that touches schema `audit`:** V0003's own comment already stated the
contract — a migration issuing DDL in schema `audit` must
`SET LOCAL fru.migration_in_progress = 'on';` first, because Layer 3 blocks unguarded
DDL there. V0031/V0032 (this task) are the first migrations after S2-01 to actually
touch schema `audit`, and the first proof run omitted this, which passed only because
it ran on a virgin database with Layer 3 not yet installed — Testcontainers-based tests
never run `db/post-migrate/`, so this ordering bug went unnoticed until reviewed and
re-tested against a database with Layer 3 already active (the realistic case for every
deployment past S2-01). Fixed by adding the `SET LOCAL` to V0031-V0033.

## How application code writes an audit event (S3-01)

The first application writer landed with the Stage 1a account check. Read this before
writing the second one — the constraints below are not obvious from the table definitions.

**One class writes to `audit.audit_event`: `audit.jdbc.JdbcAuditEventWriter`,** behind the
`audit.domain.AuditEventWriter` port. Keep it that way. Two facts have to be reproduced
exactly by any writer, and reproducing them in several places is how one of them gets it
wrong:

1. **Five NOT NULL columns are owned by the trigger, not by the application** — `seq`,
   `occurred_at`, `prev_hash`, `content_hash`, `row_hash`. They have no defaults, so the
   INSERT must supply placeholder values, and `audit.chain_append()` overwrites every one.
   The application asserts no sequence number and no hash. (Column defaults were considered
   at S3-01 and rejected: adding them is DDL on schema `audit`, i.e. the R-035 trap, bought
   purely for one class's convenience.)
2. **`fru_app` cannot create a chain directly.** V0004 grants it `SELECT` only on
   `audit.audit_chain`, deliberately — chain creation is a schema-shaped act. For a *fixed,
   known* subject, the chain is seeded by a migration ahead of time. For a **profile** chain
   this doesn't work — `profile_id` doesn't exist until the application creates it at runtime
   (S3-06 is the first slice that does) — so S3-06 adds `audit.ensure_profile_chain(p_profile_id
   uuid)` (V0036): a `SECURITY DEFINER` function, same pattern as `audit.chain_append()`,
   `INSERT`ing `(chain_kind='profile', subject_id=p_profile_id::text)` with `ON CONFLICT
   (chain_kind, subject_id) DO NOTHING` (idempotent — safe to call before every audit write on a
   profile chain, not only the first). `EXECUTE` is granted to `fru_app` only — **and explicitly
   `REVOKE`d from `PUBLIC` first**, because PostgreSQL grants `EXECUTE` on a new function to
   `PUBLIC` by default; without the `REVOKE`, `fru_sealer` (held to `SELECT`-only on
   `audit_chain` by V0033, precisely so it cannot write what it later seals) could call this
   function and insert chain rows. Found by `@agent-reviewer` at S3-06 and fixed before commit;
   proven live that `fru_sealer` gets `permission denied` and `fru_app` succeeds.

**Chains seeded or creatable for application use:**

| chain_kind | subject_id | How | Used by |
|---|---|---|---|
| `system` | `account_check` | Pre-seeded, V0034 | Journey Stage 1a. The attempt is recorded even though no profile is created, which is why a `profile` chain is not an option |
| `profile` | the profile's own `profile_id` | Created at runtime via `audit.ensure_profile_chain()`, V0036 | Every profile-scoped event from Stage 1b onward (`session_created`, `otp_issued`, `notification_dispatched`, …) |

`subject_id` is non-NULL on the seeded `system` row on purpose: `UNIQUE (chain_kind,
subject_id)` does not deduplicate NULLs in PostgreSQL, so a NULL-subject system chain could be
seeded twice and the application's lookup would then pick an arbitrary one. The same
`UNIQUE` constraint is what makes `ensure_profile_chain()`'s `ON CONFLICT` target valid. See
**R-038** for the serialisation a shared chain implies — `chain_append()` takes `SELECT … FOR
UPDATE` on the chain row; a `profile` chain does not share this problem since each profile gets
its own row.

**`AuditEventWriter.append` returns the generated `audit_event_id` (`long`), not `void`** —
changed at S3-06 because `app.profile_status_history.audit_event_id` is a `NOT NULL` foreign
key onto it, and the caller writing a profile's first status-history row needs the id of the
`session_created` event it just wrote. `JdbcAuditEventWriter`'s INSERT now ends `RETURNING
audit_event_id`, read back via `JdbcTemplate.query(sql, args, argTypes, RowMapper)` — note the
**argument order matters**: this is a distinct overload from the variadic
`query(String, RowMapper, Object...)`, and swapping the two silently mis-binds parameters
instead of failing to compile. Every pre-existing caller (`AccountCheckService`,
`OutboxDispatcher`) simply discards the return value; nothing else changed for them.

**The writer resolves the chain inside the statement** (`INSERT … SELECT … FROM
audit.audit_chain WHERE chain_kind = ? AND subject_id = ?`) rather than with a prior lookup, so
a missing chain inserts zero rows and the writer throws. Fail closed: an action that should
have been audited and was not is worse than one that failed.

**`payload_json` is RFC 8785 canonical JSON, built by `audit.domain.CanonicalJson`, never by
string concatenation or a general-purpose serializer.** Canonical form is what the hash chain
covers, so two runs recording the same facts must produce byte-identical text — a serializer
whose key order follows map iteration order gives no such guarantee. It supports flat objects
with `String`/integral/`Boolean`/`null` values and rejects everything else, including U+0000
(the generated `payload_json::jsonb` column would refuse it), unpaired surrogates, and integral
values beyond ±2^53 where RFC 8785's ECMAScript number rule and an exact decimal disagree.

**A migration touching schema `audit` with DML only does not need the R-035 flag.**
`audit.block_audit_ddl()` fires on `ddl_command_end` and `sql_drop`; an INSERT fires neither.
V0034 is the worked example and V0031–V0033 are the contrasting DDL case. Verified live against
a database with Layer 3 actually installed — see docs/sessions/2026-08-28-s3-01-account-check-slice.md §7b.

## Stage 1a/1b profile existence and re-entry (S3-07)

The read that decides Stage 1a's branch and Stage 1b's insert-vs-update choice is one query,
`ProfileRepository.findExisting(accountNumber)` — account number alone since V0061 (BL-032,
2026-09-04): `profile_one_per_account` is `UNIQUE (account_number)` under its V0005 name, and the
branch the customer selected is descriptive data that never narrows this lookup:

```sql
SELECT p.profile_id, p.status, sc.is_terminal
  FROM app.profile p JOIN app.status_code sc ON sc.code = p.status
 WHERE p.account_number = ?
```

`is_terminal` is read from `app.status_code`, not re-derived in Java — it was seeded exactly for
`submitted`/`approved`/`rejected`/`terminated_registry_mismatch` at V0005, matching operator.md's
"Terminal for the customer" table. This keeps the terminal-status set in one place.

**Re-entry to Stage 1b never deletes.** Neither `app.otp_challenge` nor `app.profile_channel`
grants `fru_app` `DELETE` (V0010), and a re-entry — customer.md Stage 2 "Phone number wrong → Back
to 1b", or the device-less resume's incomplete-profile branch — is expected to leave both tables
non-empty afterward, just with different rows in play:

- **`app.profile_channel`** is upserted (`INSERT ... ON CONFLICT (profile_id, channel) DO UPDATE`,
  its own PRIMARY KEY): a channel in this submission's selection gets a fresh state (`verified_at`/
  `locked_at`/both attempt counters reset); a channel that had a row before but isn't in this
  submission (only ever email, since SMS/WhatsApp decisions always exist) is separately declined —
  never removed, just marked `declined` like an ordinary deselection.
- **`app.otp_challenge`** is invalidated by **expiring, not deleting**: `UPDATE ... SET expires_at =
  LEAST(expires_at, now)`. The row survives as evidence that a code was issued; it simply can no
  longer verify, because it is already past its (now earlier) expiry. Do not add an
  `invalidated_at` column for this — `expires_at` already means exactly this, and a second column
  would just be two ways to say the same thing with no rule for which one Stage 2's future
  verification check reads.
- **`app.profile`** itself is touched two ways depending on the existing status: any status
  transitions `last_activity_at` forward; only `abandoned` also transitions to `in_progress`
  (`app.status_transition`, V0020, whose own comment cites this exact case) with the matching
  `app.profile_status_history` row the deferred `profile_status_requires_history` trigger requires.
  Every other in-flight status (`in_progress`/`awaiting_registry`/`blocked_scan`/`blocked_liveness`)
  is left alone — those blocks are about scan/liveness/registry progress, not contact-channel
  identity, and Stage 1b re-verifying channels does not resolve them. Since V0061 (BL-032) a third,
  status-independent write also happens on every re-entry: `branch_code` is refreshed to the branch
  selected this time (`ProfileRepository.updateBranchCode`, `UPDATE app.profile SET branch_code = ?
  WHERE profile_id = ?`) — no `row_version` bump, the same posture as the `last_activity_at` touch,
  because the branch is descriptive data and the previous value is already on the
  `session_created`/`session_reentered` events.

Re-entry is its own audited event, `session_reentered`, structurally parallel to `session_created`
but additionally carrying the previous phone/email and previous per-channel states, so the
permanent record shows a second Stage 1b submission happened for this account, not just its end
state. A terminal profile submitted to 1b (whether reached via Stage 1a or called directly) is
rejected with `contact_channels_rejected` and nothing else — no OTP, no send, no `app.*` write —
checked before the send loop runs, so the check is enforced server-side rather than trusted to the
app's own Stage 1a gate.

## Lock ordering across every writer, not only operator-side ones (S4-01/S4-02)

**Every writer anywhere in this codebase** that takes both the `app.profile` row lock (`SELECT
... FOR UPDATE`) and the audit-chain lock (`AuditEventWriter.append`'s own `SELECT ... FOR
UPDATE` inside `audit.chain_append()`) must take the profile row lock FIRST — found live at
S4-01 as a deadlock (`40P01`) between concurrent operator-side `approve()`/`reject()` calls that
had been taking the two locks in opposite orders. `operator.domain.ReviewRepository
#lockAndReadStatus`'s Javadoc states the invariant in full; `operator.domain
.ManualCompletionRepository`'s equivalent lock method (S4-02, the third *operator-side* writer)
follows it too.

**Not only operator-side, in practice**: reviewing S4-02's diff for this same invariant found two
pre-existing customer-side writers violating it — `identityscan.service.IdentityScanService
#reportWrongDetails` and `#acceptRegistryReview`, both of which called a precondition helper that
took (and, outside any transaction, immediately released) the profile lock, then opened their own
transaction with the audit-chain write as its first statement. Latent since introduction (no other
writer took both locks against an `in_progress` profile at the time), made live and reachable by
S4-02: manual completion is a second legal writer against `in_progress` profiles (**AD-022
removed that writer on 2026-09-16, so `SubmissionService` is the only one again — the invariant
is NOT relaxed, because any future second writer re-creates the same race**). Both fixed by
re-locking as each transaction's own first statement, matching every other sibling method in that
class (`reportWrongNumber`, `retryRegistryLookup`). A future writer anywhere — customer- or
operator-side — that takes both locks must do the same.

## Mobile local store — encryption (S5-03, closes R-025)

`session.sqlite` is now genuinely encrypted, not just OpenSSL-collision-free. §8's original
`sqlite3mc` choice is reinstated via `mobile/pubspec.yaml`'s `hooks: user_defines: sqlite3:
source: sqlite3mc` — this selects the binary for the whole `sqlite3` package (there is no
per-database selector); `reference.db` shares the same binary but is never given a `PRAGMA key`,
so SQLite3 Multiple Ciphers leaves it a plain, unencrypted file, matching §8's "not required (no
PII)" call.

**Key**: 32 `Random.secure()` bytes, hex-encoded (`generateSessionEncryptionKey()`,
`mobile/lib/core/security/session_key_store.dart`) — never hardcoded, never derived from anything
shipped, never written into the database it protects. Stored via `flutter_secure_storage` 11.0.0
(`SecureSessionKeyStore`, `session_key_store_native.dart`): Android Keystore-backed RSA-OAEP +
AES-GCM (11.0.0's default `AndroidOptions()` — the `encryptedSharedPreferences` option the AD-005
research cited was removed outright in this major, replaced by this stronger default), Keychain
(`kSecClassGenericPassword`) on iOS. `flutter_secure_storage_darwin`'s iOS podspec depends only on
`Flutter` — no OpenSSL, no third-party pod.

**Wiring** (`mobile/lib/core/database/session_encryption.dart`): `PRAGMA key = '<key>'` runs via
`NativeDatabase.createInBackground(file, setup: ...)` — drift's own documented mechanism, firing
"just after the database is opened, before drift is fully ready," i.e. before drift's own
`PRAGMA user_version` migration check ever touches the file. First run: no key in
`SessionKeyStore` → generate, store, then key the (new) file. Every later run: read the stored key
back and key the existing file with it.

**Discard, not migrate, a pre-S5-03 plaintext file.** Having no *usable* key is the signal that a
`session.sqlite` on disk predates this change, because this code always writes a key before/at
every successful open of a database it created. "Usable" is load-bearing, not just "present":
found under review, `flutter_secure_storage`'s Android backend resets and returns the literal
string `"Data has been reset"` from `read()` on a Keystore decryption failure (`resetOnError`, its
own default) — a value that must never be treated as a real key. `openEncryptedSessionExecutor`
therefore also rejects any stored value that is not a 64-character lowercase-hex string (i.e. not
something `generateSessionEncryptionKey()` could have produced) and treats a throwing read the
same way — both collapse to "no key stored" rather than a usable key or a reason to leave a
plaintext file in place. On that combination the file (and any `-wal`/`-shm`/`-journal` sidecars)
is deleted outright and a fresh key generated — the customer lands on a fresh Stage 1a via
`LaunchScreen`'s existing no-`LocalProgress` path, not a crash. Chosen over migrating in place
because the only data at risk is one in-flight session the customer can re-enter in under a
minute, and migrating would mean reading plaintext PII just to re-encrypt it.

**Split for testability, mirroring the existing `reference_database.dart`/
`reference_database_native.dart` pattern**: `session_key_store.dart` (the `SessionKeyStore`
interface + the pure key generator) and `session_encryption.dart` (`openEncryptedSessionExecutor`,
the discard/key/`PRAGMA` logic) import neither Flutter nor `path_provider`/`flutter_secure_storage`
— both are exercised directly against real temp files in tests and in
`bin/live_session_encryption_proof.dart` (plain `dart run`) with a fake `SessionKeyStore`.
`session_key_store_native.dart` (the real `flutter_secure_storage`-backed store) and
`session_database_native.dart` (real file path via `path_provider`) carry the Flutter-dependent
halves, wired together only in `openSessionDatabase()`.

**Live-proven** (docs/sessions/2026-09-01-s5-03-local-encryption.md): a raw `sqlite3` handle
without the key fails `SQLITE_NOTADB` (extended result code 26) on its first `SELECT`; with the
key it lists the real tables (`pinned_reference_versions`, `local_draft`, `local_progress`); a raw
byte scan of the file finds neither a written phone number nor account number.

## Artifact storage — closes AD-004 (S5-06)

**Decision**: artifact bytes live in PostgreSQL, in the profile database — no object store.
`app.artifact_ref` (V0008) is extended with a `body bytea` column (`STORAGE EXTERNAL`, V0053)
rather than duplicated into a new table: it already sits in its own purge-lifecycle boundary,
separate from `app.profile` and from `audit.audit_artifact` (7-year, append-only — a different
lifecycle entirely). `STORAGE EXTERNAL` disables PostgreSQL's default compression attempt — JPEGs
are already compressed, so paying the CPU cost for zero space saved is pure waste; `EXTERNAL`
still allows out-of-line TOAST storage, just skips the compression pass `EXTENDED` (the type
default) would otherwise attempt.

**Why not object storage.** The delivery model decides it: "one PostgreSQL instance, restore it
and run" is a materially simpler handover to a bank that will host this than PostgreSQL plus an
object store plus bucket policies, credentials and lifecycle rules their infrastructure team must
accept and can misconfigure unseen. The usual objection against BLOBs only half-applies — they do
enlarge backups, but do **not** slow ordinary queries: PostgreSQL moves anything over ~2KB
out-of-line into TOAST automatically, so a query against `app.profile` or `app.scan_result` never
reads image bytes unless it explicitly selects `body`.

**Live-proven, out-of-line storage** (a raw `psql` session, not a JUnit assertion — physical
storage layout is not something a passing test can meaningfully verify): `attstorage = 'e'` on
`app.artifact_ref.body` confirms the setting took effect; inserting one real row with a 60,000-byte
body leaves `app.artifact_ref`'s own main relation at exactly one page (`pg_relation_size` = 8192
bytes) regardless, while the *toast* relation grows to 64 kB — the bytes are genuinely elsewhere,
not inflating the row a profile query pays for. `pg_column_size(body) = 60000`, byte-for-byte,
confirming `EXTERNAL` applies no compression.

**What is stored, and what is deliberately not.** Per profile: document front, document back
(national ID only), document front/back capture frames, Uqudo's extracted portrait
(`portrait_uqudo`), the Civil Registry's own portrait (`portrait_registry`), the liveness
audit-trail image, the signature, and — once BL-022's upload endpoint exists — the optional salary
certificate. **Raw capture frames Uqudo returns alongside the cropped document are NOT stored** —
they would roughly double the volume for no evidentiary gain, since the cropped document is what
the JWS attests to; a deliberate exclusion, not an oversight. Originals are stored byte-identical,
never re-encoded — the same rule and reasoning as the raw Uqudo JWS in the audit trail: a
re-encoded image cannot be proven to be what Uqudo (or the registry) actually returned.

**Derivatives** (downscaled images for the operator's list/preview contexts, per operator.md):
`app.artifact_ref.derived_from_artifact_ref_id` (V0053), a nullable self-referencing FK — a
derivative row has this set, pointing at the original it was generated from; `kind` mirrors the
parent. **Schema only, not populated by any code this session** — same precedent as V0048's
`ref.reference_list_document` sitting empty until its publication step landed later. The generator
belongs with whoever builds the backoffice image-viewing endpoint. **That endpoint shipped at
BL-075 and derivatives were NOT part of it** — it serves originals, and whether the operator's
contact sheet ever needs downscaled copies is an open question rather than an owed deliverable.
R-046 is closed (the addressing scheme is a same-origin cookie-authenticated `GET`, not a signed
URL), so this column no longer waits on anything.

**Checksum-verified reads — `app.artifact_read(p_artifact_ref_id uuid) RETURNS bytea`** (V0054). An
invariant pushed into the database rather than trusted to every future Java caller, matching this
schema's existing discipline (`ref.ar_fold()`, `audit.chain_append()`, `audit.seal_create()`):
verifies `sha256(body) = sha256` using PostgreSQL's built-in `sha256()`. A checksum mismatch on an
existing body raises — refused, never returned silently — and so does an entirely missing
`artifact_ref` row. **Absence is different in kind and is not an error**: a row whose `body` is
NULL (never stored, or purged by `app.purge_abandoned_artifacts()`) returns SQL `NULL`, a
legitimate outcome the caller checks for rather than a raised exception — this is what lets a
reactivated abandoned profile fail with a defined "go rescan" outcome instead of a raw 500 (found
under review; see `LivenessService.issueFaceSessionToken` below). `REVOKE EXECUTE FROM
PUBLIC` then `GRANT ... TO fru_app` — the exact gap `@agent-reviewer` caught at S3-06 for
`audit.ensure_profile_chain()`, since PostgreSQL grants `EXECUTE` on a new function to `PUBLIC` by
default. This is not a speculative mechanism: `LivenessRepository
.currentAcceptedCycleReferenceImage` is its one real production caller (see below), and
`ArtifactStorageIntegrationTest` proves both the good-row and tampered-row paths live against a
real database.

**The 90-day artifact purge — `app.purge_abandoned_artifacts(p_as_of timestamptz DEFAULT
clock_timestamp()) RETURNS int`** (V0055). Nulls `body` and sets `state = 'purged'` for
`app.artifact_ref` rows belonging to a profile with `status = 'abandoned'` and `last_activity_at <
p_as_of - interval '90 days'` — the artifact half of customer.md's "Abandoned-profile retention:
90 days from last activity, then identity images and personal data deleted" rule. **Scope note:**
this closes only the artifact half. `app.profile.pii_purged_at` (V0005) remains unwritten by any
code, exactly as before this session — full profile PII nulling (name, address, national number)
is the separate, broader, still-provisional decision customer.md itself marks "Provisional...
OQ-001 is unanswered," not part of AD-004. Mirrors `audit.seal_create()`'s own precedent exactly: a
plain SQL function, no scheduler, structurally incapable of touching `audit.audit_artifact` (a
different schema, never referenced anywhere in this function's body) — **nothing in this
codebase invokes this periodically; that is an operational requirement, not yet met.** Two more
pieces this function's precondition depends on are equally unmet today, found live under review:
no production code ever transitions a profile *into* `abandoned` in the first place — every
occurrence in this codebase is a transition *out* of it (`JdbcProfileRepository`'s reactivation,
`JdbcManualCompletionRepository`) — and customer.md's own 30-day abandonment sweep does not exist
either. `ArtifactStorageIntegrationTest` has to hand-write the `in_progress -> abandoned`
transition in raw SQL to exercise this function at all; nothing reaches that state on its own.
`REVOKE
EXECUTE FROM PUBLIC`, deliberately **no** grant to `fru_app`. This is narrower than the audit
seal's grant withholding, and stated honestly as such: `fru_app` already holds `UPDATE` on
`app.artifact_ref` (V0010) for its ordinary writes, so it could still null one row's `body`
directly — unlike the seal tables, where the missing grant makes `fru_app` structurally incapable
of sealing at all. What withholding `EXECUTE` here actually enforces is that the **bulk,
time-based sweep** across every abandoned profile stays an admin-only, deliberately-invoked
operation — the same operational-only posture `audit.seal_create()` has via `fru_sealer` — not
something the application's own request path can trigger.
`ArtifactStorageIntegrationTest` proves it live: an abandoned profile backdated past 90 days has
its artifact bodies nulled and `state='purged'`; one backdated only 10 days is untouched;
`audit.audit_artifact`'s row count is unchanged before and after.

**Behaviour change: the backend now persists what it used to discard.** Stage 8's scan images and
the Civil Registry portrait (`IdentityScanService`), stage 10's liveness audit-trail image
(`LivenessService`), and stage 11's signature (`SignatureService`) were all already downloaded and
checksum-verified — only the bytes themselves were thrown away, pending AD-004. Each now passes its
already-in-hand `byte[]` through to `insertArtifactRef`/`upsertFaceAuditTrailArtifact`/
`insertSignatureArtifact`. No new endpoints, no new external calls — the same chains, storing one
more thing they already had.

**`app.scan_result.face_reference_image` (V0041/V0042, S3-13) is REPLACED, not left alongside** —
see RISKS.md R-047 (retired) for the full three-path reasoning. `app.artifact_ref` (S3-12) already
inserted a `kind='portrait_uqudo'` row in the same transaction as `scan_result`, but before this
session that row held only a checksum and metadata, never bytes (AD-004 was unresolved) — so
V0042's triggers were, historically, clearing the only durable byte-copy of the portrait this
backend held. V0053 (this session) gives that same row a `body` column, so going forward the
insert that used to populate the bridge column also durably stores the bytes — that is what makes
the bridge redundant now, not a claim that a governed copy always already existed. `LivenessRepository
.currentAcceptedCycleReferenceImage` now reads `SELECT app.artifact_read(artifact_ref_id) FROM
app.artifact_ref WHERE cycle_id = ? AND kind = 'portrait_uqudo'` instead of the dropped column;
`LivenessRepository.clearFaceReferenceImage` is removed outright, since AD-004 retains the portrait
permanently as one of its nine sanctioned kinds — there is nothing left to clear on a face-match
pass. V0056 drops the column and both V0042 triggers/functions, in that order (a trigger function
is not statically re-validated against a column dropped elsewhere).

**Encryption at rest stays disk/volume-level, not per column** (discharges R-026 into deployment,
per AD-002d — unchanged, still open). Encrypting `body` specifically would break ordinary operator
access and gains nothing against the threat that actually matters here — someone removing a disk —
so it is not part of this schema.

**Operator LISTING reads stay metadata-only.** `operator.jdbc.JdbcProfileViewRepository`'s
`ARTIFACTS` query still never selects `body` — listing what exists must not drag every image out of
TOAST. Since BL-075 it also selects `artifact_ref_id`, because the bytes are now fetched
one-at-a-time by id from `operator.jdbc.JdbcOperatorImageRepository`, which reads through
`app.artifact_read()` and applies the same ownership predicate. R-046 is closed: a same-origin
cookie-authenticated `GET`, neither a signed URL nor a blob fetch. Its existing
`ar.state = 'committed'` filter, previously dead code (nothing ever wrote `'purged'`), is now
load-bearing: a purged row's stale `storage_key`/`sha256` no longer surfaces to an operator whose
bytes are gone.

## Salary certificate upload and email correction (S4-06)

**Salary certificate (BL-022).** Mirrors the signature's own upsert precedent exactly: V0059 adds
`artifact_ref_one_salary_certificate_per_profile`, a partial unique index `ON app.artifact_ref
(profile_id) WHERE kind = 'salary_certificate'` (V0008's plain `UNIQUE (cycle_id, kind)` does not
dedupe NULL `cycle_id`, and this kind always has `cycle_id NULL`) — the same shape V0046 already
established for `kind='signature'`. `JdbcSalaryCertificateRepository`'s `ON CONFLICT ... DO UPDATE`
now also sets `state = EXCLUDED.state` on every upsert, not just the byte columns — found under
review as an omission this shares with the (now also fixed) signature upsert: without it, a row
purged by `app.purge_abandoned_artifacts()` (state set to `'purged'`, body nulled) and later
re-uploaded to would restore `body` while `state` stayed `'purged'` forever, and the purge
function's own `WHERE ar.state <> 'purged'` would then permanently exclude that row from ever being
swept again even though it once again holds a live, ungoverned body.

**Corrected email on resend (BL-012).** `OtpVerificationService.resend`'s `reserveResend` applies an
email correction — when supplied, and only for the `email` channel — inside the same
row-locked transaction as the resend reservation decision, before that decision is made. This is
what lets the correction land even when the reservation itself is then refused (`TOO_SOON`/
`CAP_EXHAUSTED`/`CHANNEL_LOCKED`): editing the row and tapping resend are two logically separate
actions per customer.md ("editable in place on its row, **then** resend"). The correction also
invalidates the OLD address's still-live `otp_challenge` row itself (`invalidateChallengesForChannel`,
same call `resend`'s Phase 2 already makes on success) — found under review to be load-bearing, not
redundant: Phase 2 only runs when the reservation succeeds, so without this the old challenge stayed
live on every refused-resend-with-correction path, and entering its code would mark the email
channel `verified` against an address that was never itself challenged. New
`ProfileRepository.updateEmailAddress` writes only `app.profile_customer_data.email_address` — a
narrower sibling of `updateContactDetails`, which requires (and would silently overwrite) the phone
number too. Every applied correction is audited as `email_address_corrected` (previous + new address
in full, not masked — same precedent as `session_reentered`'s `previousPhoneNumber`/
`previousEmailAddress`, S3-07); a correction identical to the stored value writes and audits nothing.

## Operator accounts (AD-002e, S4-05)

`app.operator_role` (three rows: viewer/operator/admin) and `app.operator_user` (V0057). One
`role` column, not a role set — that is what makes holding `admin` AND `viewer`/`operator` at
once *inexpressible* rather than merely rejected (still true, and since AD-013 it no longer
implies anything about capability: admin now subsumes the operator powers rather than excluding
them); a role-set table would need a
trigger, since PostgreSQL does not support a CHECK referencing other rows
[DOC postgresql.org/docs/18/ddl-constraints.html], the same finding V0049 already relies on.

`fru_app` gets `SELECT` on `operator_role` and `SELECT, INSERT, UPDATE` — **no DELETE** — on
`operator_user`. An account is disabled, never deleted: `app.profile_status_history.actor_id`
and `audit.audit_event.actor_id` reference `user_id` as plain text forever and both tables are
append-only, so a deletion would orphan every audit attribution. Same
reasoning as `app.profile`.

**`actor_id` is `app.operator_user.user_id` (UUID text), never a username.** The original
reason was V0009's four-eyes `UPDATE`, which matched `h.actor_id = :operator_id`
[OBSERVED V0009:42-46]; **AD-013 removed that rule on 2026-09-13**. The requirement is unchanged
and now rests on attribution alone: `actor_id` is written into append-only
`app.profile_status_history` and `audit.audit_event` rows, so a renameable identifier would
silently re-attribute past actions in tables that cannot be corrected. Proven live: see
docs/components/backoffice-auth.md's rename-safety test.

`username` is ASCII-lowercase with a shape CHECK and a plain UNIQUE — deliberately NOT a
generated `lower()`/`ar_fold()` column, per this document's own rule against indexing a
user-text column under a non-C collation.

A second, DML-only migration (V0058) seeds one `audit.audit_chain` row, `('system', 'auth',
NULL)`, mirroring V0034's exact reasoning — a failed sign-in's actor may not exist at all, so it
cannot be given its own lazily-created chain the way `audit.ensure_operator_chain` (V0047) does
for a known operator.

## Open verification items
- [ ] `SELECT proname, provolatile FROM pg_proc WHERE proname IN ('translate','regexp_replace','lower','normalize');` — all must be `i` for `ar_fold` to be safely IMMUTABLE
- [ ] Is `text::jsonb` accepted in `GENERATED ALWAYS AS … STORED`?
- [ ] `SELECT collname FROM pg_collation WHERE collname='ar-x-icu';` on the Windows dev install
- [ ] pg_trgm recall on the real 138-item occupation list and ~200 Arabic names
- [x] Does `sqlite3mc` link OpenSSL on iOS? **CLOSED at S5-03, with evidence, not deferred to
      S1-03.** The installed `sqlite3` 3.5.2 package carries no podspec, no `ios/`, no `darwin/`
      directory for ANY `source:` flavor (`sqlite3`/`sqlite3mc`/`sqlcipher`) — all three are
      pre-compiled binaries fetched by the same Dart native-assets build hook
      (`hook/build.dart`), never CocoaPods. The package's own `doc/hook.md` attributes the
      OpenSSL dependency to the `sqlcipher` flavor only, on Windows/Linux/Android — never to
      `sqlite3mc` (SQLite3 Multiple Ciphers embeds its own crypto). No collision with Uqudo's
      pinned `OpenSSL-Universal 3.3.3001` (R-003). See
      docs/sessions/2026-09-01-s5-03-local-encryption.md.
- [ ] Does Zonky embedded-postgres (Windows amd64) include ICU?
- [ ] Ask the bank: platform standard for a delivered application database
- [ ] Whether `pg_trgm` gives usable recall on Arabic — the manual documents only that
      trigrams work "in many natural languages" and says nothing about multibyte
      [DOC postgresql.org/docs/current/pgtrgm.html]
- [ ] Whether `ref.ar_fold`'s constituent functions (`translate`, `regexp_replace`,
      `lower`) are truly `IMMUTABLE` — function volatility is not stated in the reference
      documentation
- [ ] Whether `text::jsonb` is permitted in a `GENERATED ALWAYS AS … STORED` expression —
      needs one statement against a live server; fallback is a plain `jsonb` column
      populated by the same BEFORE INSERT trigger
- [ ] Whether the PG18 EDB Windows installer supports Windows 10 desktop — the published
      table lists Windows Server 2025/2022 only [DOC postgresql.org/download/windows];
      Docker sidesteps this
- [ ] Encryption at rest for the profile database itself. AD-005 covers the mobile local
      store and is silent on the server. Falls between AD-005 (schema) and AD-002d
      (hosting) — assigned to AD-002d explicitly (R-026)
- [x] `app.notification_outbox` — built at S3-05 (V0035), per the AD-005 §6A design. The
      `MessageSender` port and stub (AD-002c/S3-04) are also built; see
      docs/components/messaging.md and docs/sessions/2026-08-29-s3-05-messaging-port-and-outbox.md.
- [x] `ref.reference_item.search_en` was declared (V0011:65) and populated by no seed
      migration — stage 4's SETTLED English substring search had nothing to match against.
      Fixed at S3-10 (V0038): `search_en` is now `GENERATED ALWAYS AS (ref.ar_fold(label_en))
      STORED`, matching `search_ar`, plus a matching trigram index. No `reference_list_version`
      bump needed — every seed migration's `content_hash` canonicalises
      `item_code|parent_code|label_ar|label_en`, never `search_ar`/`search_en`.
- [ ] `content_hash` covered FOUR of the nine served columns (R-033). `extra` was documented
      as deliberately excluded (V0022:29-32), and `sort_ordinal`, `is_active`, `search_ar` and
      `search_en` were excluded too — none appeared in any seed migration's `canon` CTE.
      `sort_ordinal` was the sharpest case: the device cannot recompute or detect a wrong
      value, because it cannot collate Arabic. **Definition redefined by AD-002f (V0048,
      S4-03):** `content_hash` is now SHA-256 over the exact bytes of the document generated
      by `reference.domain.ReferenceDocumentGenerator` and stored in
      `ref.reference_list_document` — the envelope is the whole document by construction
      once a version is published. Proven live: an integration test recomputes the document
      from `ref.reference_item` independently for all seven current versions and asserts the
      recomputed hash equals both `reference_list_document.sha256` and
      `reference_list_version.content_hash`; the publisher was also run twice by hand
      against a real database with identical results. **Still open**: population is a
      by-hand step (`db/post-migrate/02-publish-reference-documents.md`) that can be
      silently skipped, the same R-028 shape as Layer 3's own script — a deployment that
      migrates through V0050 and never runs the step keeps the old, narrower hash with
      nothing failing loudly about it. Left open rather than closed; see R-033.
      [OBSERVED 2026-08-31; definition fixed 2026-08-31, operationally still open]
- [ ] `ref.profile_reference_version` is written by no application code. The standing
      constraint "the list version used for a submission is recorded on the profile" is
      therefore still NOT met. `fru_app` already holds SELECT, INSERT (V0012:20) — only a
      writer in `SubmissionService`'s transaction is missing. **Still open after S4-03** —
      explicitly out of scope this session (AD-002f §6.2), next session's work alongside
      wire-contract version pinning. [OBSERVED 2026-08-31]
- [x] Nothing enforced that `admin_division`'s root `item_code` equals the `country` list's
      alpha-2 code for Sudan (R-045). **Settled by AD-002f (2026-08-31, V0049, S4-03):** the
      root becomes declared data — `ref.reference_list_version.root_item_code` +
      `root_country_version` + `root_country_list GENERATED ALWAYS AS ('country') STORED`,
      with a composite FK onto `ref.reference_item (list_code, version, item_code)` (the
      V0006 `occupation_list` pattern) — plus one `DEFERRABLE INITIALLY DEFERRED` constraint
      trigger (`ref.assert_list_root`/`ref.trg_assert_list_root`) asserting the list's single
      parentless row carries that exact `item_code`. A plain `CHECK` is not an option:
      PostgreSQL does not support `CHECK` constraints referencing other rows
      [DOC postgresql.org/docs/18/ddl-constraints.html]. A trigger is restore-safe here
      because `pg_dump` emits triggers in the post-data section
      [DOC postgresql.org/docs/18/app-pgdump.html]. `DataEntryService.SUDAN_CODE` is gone,
      replaced by `ReferenceCatalog.currentRootItemCode`. `app.profile_customer_data
      .sudan_uses_codes` (V0006) is left as a now-stale literal, explicitly commented
      (V0050) rather than rewritten — cross-row `CHECK`s remain forbidden.
- [x] `'SD'` was asserted in FOUR independent places, not two: `admin_division`'s root row
      (V0016:35), the `country` list's Sudan row (V0022), `app.profile_customer_data`'s
      `sudan_uses_codes` CHECK (V0006:42-43) and `DataEntryService.SUDAN_CODE`
      (DataEntryService.java:84). AD-002f (S4-03) removed the last one (lookup via
      `ReferenceCatalog.currentRootItemCode`) and left V0006's CHECK as a now-meaningless
      literal, commented rather than rewritten. [OBSERVED 2026-08-30; closed 2026-08-31]
- [x] `audit.audit_event.actor_kind` has no `'admin'` value (V0002:46). AD-002e (S4-05) records
      admin actions as `actor_kind='operator'` with `"actorRole":"admin"` in the payload rather
      than altering a CHECK on an append-only table (DDL in schema audit — R-035).
      **IMPLEMENTED at S8-28 (BL-139)** — `operator.domain.OperatorAuditPayload` stamps the field
      at the operator-chain sites (approve, reject, approve-refused, export,
      profile view, list search). It became necessary rather than optional the moment AD-013 let
      an admin reach those endpoints: admin and operator arrive at the same access level, so
      without it the two are indistinguishable in the chain, and R-054 names that chain as the
      sole compensating control. No CHECK was altered and no migration was needed; only new
      events carry the field and no existing row is re-hashed.
      **One site deliberately excluded:** `profile_image_viewed`. Wayfinder ticket 09 decision 1
      fixes that payload at "kind + artifactId and nothing else" and an integration test asserts
      the field count, so an admin's image view is still indistinguishable from an operator's.
      That is a live gap awaiting a ruling, not an oversight — see BACKLOG.md.
      [OBSERVED 2026-09-02; implemented 2026-09-13]
