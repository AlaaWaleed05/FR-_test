# Pre-production consolidation audit

Read-only. No code changed, no plan file edited, no gates run (none owed). One
reconciliation of PROJECT_PLAN.md, EXECUTION_PLAN.md, BACKLOG.md and RISKS.md — all four
read in full — against the committed tree at `dfcb325`.

Working tree at audit time: `main`, one uncommitted file (`CLAUDE.md` — see T-8).

---

## 1. Plan-file truth check

Corrections for the planner. Each is stated with the file/line and what it should say.
Nothing here was fixed in passing.

### T-1 — BL-056 and BL-057 are each filed twice (numbering collision)

| Line | ID | Item | Filed |
|---|---|---|---|
| BACKLOG.md:12 | BL-056 | "Device runs on the S1-02 Huawei should use adb over Wi-Fi" | `e21b845`, 2026-09-06 |
| BACKLOG.md:13 | BL-057 | "The Uqudo SDK logs three non-fatal `cv::error()` OpenCV failures" | `e21b845`, 2026-09-06 |
| BACKLOG.md:14 | BL-056 | "Stages 10-12 collapsed every outcome into a bare status" | `9c72db1`, 2026-09-05 |
| BACKLOG.md:15 | BL-057 | "No read answered 'where am I in stages 10-12'" | `9c72db1`, 2026-09-05 |

Established by `git log -S` on each row's opening phrase, not by reading dates off the
rows. The S5-13 pair (lines 14-15) was filed first and is the one already cross-referenced
elsewhere — BL-051 cites `[[BL-057]]`, and BL-058 says "BL-057's read supplies the channels
instead". **Renumber the S5-08 device-run pair (lines 12-13), not the S5-13 pair**, to
BL-069 and BL-070; BL-068 is the current high-water mark.

### T-2 — BL-061 is a false defect: the code has never had the bug

BL-061 (BACKLOG.md:67-75) says `mobile/tool/check_coverage.dart` "exits 0 (success) even
when the underlying `flutter test` run FAILS … and returns success anyway", claiming a red
suite passes the coverage gate.

The script does the opposite, at [check_coverage.dart:50-55](mobile/tool/check_coverage.dart#L50-L55):

```dart
if (testResult.exitCode != 0) {
  stderr.writeln('flutter test failed (exit ${testResult.exitCode}); coverage not checked.');
  exit(testResult.exitCode);
}
```

And it always has. `git log -S'exit(testResult.exitCode)' -- mobile/tool/check_coverage.dart`
returns exactly one commit — `47d69f6` (2026-08-19, S1-07), the file's first version — and
`git diff 47d69f6 HEAD -- mobile/tool/check_coverage.dart` changes no `exit` line at all
(10 insertions, none of them an exit path; the file's other three `exit(1)` calls are the
missing-lcov and below-threshold paths). The described mitigation, "read the last line,
not the status", was never needed.

**Should say:** closed as not reproducible against source, with the S1-07 provenance, and
explicitly *not* recorded as fixed — nothing was fixed. This is the only item in the four
files whose premise is contradicted by the code it names.

### T-3 — BL-061's row breaks the BACKLOG.md table

BACKLOG.md:67-75 is hard-wrapped across nine physical lines, and carries three columns
(ID | item | date) against the header's four (`| ID | Item | Why deferred | Date added |`).
Every other row in the file is one physical line with four columns. It renders as loose
text, not a row.

### T-4 — three blank lines split BACKLOG.md into four separate tables

BACKLOG.md:76, :79 and :82. Everything from BL-062 onward renders headerless. Removing the
three blank lines restores one table.

### T-5 — the BL-039 build shipped with no EXECUTION_PLAN row

Two production commits landed 2026-09-06 — `a041a19` (Slice A, one token one attempt) and
`959fa07` (Slice B, lifetime token cap) — carrying two migrations, changes across
`identityscan` and `liveness`, and ~1,800 lines of diff. No task ID covers them. Every
comparable backend slice has one: S5-12 for the guard-parity slice, S5-14 for BL-041/AD-008.

**Should say:** a new row (S5-15, or one per slice) in EXECUTION_PLAN.md's Sprint 5 table,
citing `docs/sessions/2026-09-06-bl039-scan-budget-fix.md`.

### T-6 — V0065 is named in no plan file

`V0065__app_profile_lifetime_token_cap.sql` exists on disk and is the lifetime-token-cap
migration. Counts across the four files: V0063 → PROJECT_PLAN 1, EXECUTION_PLAN 1, BACKLOG 2;
V0064 → BACKLOG 1 (inside BL-039); **V0065 → 0, everywhere**. Its rules survive only in
`CLAUDE.md`, which is itself uncommitted (T-8), and in the session report.

### T-7 — EXECUTION_PLAN.md's Sprint 5 section contains two Sprint 4 rows

`S4-05` (EXECUTION_PLAN.md:81) and `S4-06` (:82) sit under `## Sprint 5` (:71), between
S5-06 and S5-07. Sprint 4's own section (:62-69) holds only S4-04, S4-01, S4-02, S4-03.
Task IDs are permanent, so this is a filing error to correct by moving the rows, never by
renumbering them.

### T-8 — CLAUDE.md's BL-039 rule block was never committed

`git status` shows ` M CLAUDE.md`: an 11-line addition at CLAUDE.md:130-140 recording the
BL-039 budget rules (single-use pending session, per-type 5 / total derived as 2×, the
20-mint lifetime caps, the exempt failure classes, the `in_progress` gate). The BL-039
session ended without committing it, against CLAUDE.md's own session-end rule. Combined
with T-6 this is the *only* record of the lifetime-cap numbers outside a session report,
and it is one `git checkout` from being lost.

### T-9 — EXECUTION_PLAN.md now has zero open tasks

61 rows: 58 ✅, 2 ⚠️ (S1-03 iOS spike, S1-08 coverage completeness), 1 ❌ (S6-03, cut by
decision). No ⬜, no 🔵. Every remaining piece of work — the whole deployment phase — lives
as a risk, a backlog line or a clause inside AD-002d, with no task ID, no sequence and no
sprint. Not a contradiction with reality; a gap in it. The deployment phase needs its own
sprint section before it starts, or the truth of "what's next" stays spread across three
files.

### T-10 — R-002's follow-up task is named as unfiled and is still unfiled

RISKS.md R-002 says the ~176 Arabic override strings are "originally S5-07's scope, now a
separate post-demo task not yet filed". Grep across BACKLOG.md and EXECUTION_PLAN.md finds
no BL and no S-row for it. Real work (the SDK ships no `values-<lang>` folder at all,
confirmed at S1-02) with no home.

### What checked out clean

Stated because the value here is an accurate map, and these were all checkable:

- **R-### are unique and contiguous.** 52 rows, R-001…R-052, no duplicates, no gaps.
- **BL-### have no gaps.** BL-001…BL-068 all present; 70 rows because of T-1's two
  collisions and nothing else.
- **Every commit hash cited across the four files resolves**: `3e78cf2`, `eda48fe`,
  `dab0884`, `33f8ae8`, `a341ed3`. The one that does not — `5bd2036` — is deliberate:
  EXECUTION_PLAN S3-03 cites it as the *wrong* hash it reconciled against `eda48fe`.
- **Every `docs/sessions/*.md` path cited across the four files exists on disk.** Zero
  dangling references.
- **S1-08 ⚠️ is accurate.** `backend/pom.xml` carries only `<element>BUNDLE</element>` —
  no PACKAGE rule; `backoffice/vite.config.ts:29-34` carries global thresholds only — no
  per-glob. Neither tier has the 90% business-logic rule.
- **R-051 is accurate.** `auth/config/SecurityConfiguration.java:129` — chain 2 is
  `anyRequest().permitAll()`.
- **BL-066 is accurate.** Zero lines matching `^server\.` in `application.properties`;
  `forward-headers` appears nowhere under `backend/src/main/resources/`.
- **BL-018's "written by no code" is accurate.** The only `resume_stage` hit in
  `backend/src/main/java` is a Javadoc mention at `JdbcProfileRepository.java:35`.
- **BL-045 is accurate and still open.** `JdbcProfileViewRepository.java:69` and
  `JdbcProfileListRepository.java:74` both still `ORDER BY seq DESC LIMIT 1` with no
  `state` filter.
- **BL-052 is accurate.** No `mobile/ios/Podfile`; zero `NSCameraUsageDescription` in
  `Info.plist`.
- **BL-059 is accurate and still open.** The comment at `SignatureController.java` still
  justifies the code by copy that "want[s] different copy", while all four causes emit one
  `SIGNATURE_REJECTED`.
- **R-013's retention disclosure is accurate.** No production code moves a profile *into*
  `status='abandoned'`: `JdbcProfileRepository.java:180` writes the `abandoned → in_progress`
  reactivation history row, and `JdbcIdentityScanRepository.java:156` writes an
  `identity_cycle` state, not a profile status.
- **BL-041/AD-008 and BL-039 shipped as recorded.** V0063, V0064, V0065 all on disk;
  `ScanAttemptBudget.java:28` `PER_TYPE_LIMIT = 5` and `:34` `TOTAL_LIMIT = 2 * PER_TYPE_LIMIT`,
  derived exactly as the rule requires.

No other status line, cross-reference or claim in the four files was found to disagree with
the committed tree.

---

## 2. The real pre-production picture

Every open item across the four files, grouped by what it actually blocks.

### 2A. Hard production blockers — before any real customer data

| Item | What it is | Priority |
|---|---|---|
| **R-051** | The entire `/api/v1/**` customer surface is `permitAll`. A profile UUID alone fetches that customer's passport/ID images, national number, address, reference number and verified channels — and, since AD-008, forces them to rescan. The fix is customer-session auth across the whole surface, which does not exist. AD-002d's AWS edge features explicitly do **not** close it: a legitimate and an illegitimate image fetch are byte-identical. | **P0 — largest single piece of unbuilt work** |
| **R-026** | Encryption at rest, unprovisioned. Mapped to an RDS customer-managed KMS key, which **must be enabled at instance creation and cannot be added later**. | **P0 — irreversible if missed** |
| **R-037** | The audit seal is produced and never exported. Discharged only by S3 Object Lock (Compliance) in a **separate bank-owned account** — a same-account destination shares the credential that compromises both, which is R-037's own failure mode moved up a layer. Object Lock is a second at-creation-only setting. | **P0 — irreversible if missed** |
| **The four RDS provisioning gates** | All [UNVERIFIED], all gating the contract not the build: M-4 (can the RDS main user create our `SECURITY DEFINER` audit event trigger — if not, Layer 3 is *silently* absent, R-028's shape made permanent), M-5 (`ALTER DATABASE … OWNER TO fru_migrator`), M-3 (`ar-x-icu` plus an ICU-provider-created database, since RDS accepts no initdb arguments), and RDS's standing requirement that event triggers be **dropped before every major version upgrade** — a recurring handover duty, not a one-time step. | **P0 — verify before contracting** |
| **OQ-001 / OQ-002** | Regulatory regime and data residency: whether a Sudan-based bank may lawfully use a US cloud provider at all. AD-002d fixed the provider on **technical fit alone** and explicitly does not answer these. | **P0 — blocked on the bank's legal team** |
| **OQ-010 / OQ-011 / R-001** | **SUPERSEDED 2026-09-06, later the same day — see docs/sessions/2026-09-06-tenant-reconciliation.md: the project has its own Uqudo tenant and has been running on it since S5-07/S5-08, so OQ-010 is ANSWERED, OQ-011 is moot as posed, and R-001 is downgraded to 🟡 with only the per-environment credential confirmation left. This row's P0 rating does not survive that.** As written at the audit: everything today runs on FIB's borrowed Uqudo tenant. R-001 additionally records two properties of FIB's own backend, verified against `../FIB`: a `GET /token` with no observed authentication returning the tenant-wide bearer, and identity-document images logged at INFO. OQ-011 is whether real customer documents may flow through that tenant at all. | **P0 — blocked on bank/vendor** |
| **R-014 / OQ-012** | The administrative-divisions dataset is missing three current states (West Kordofan, Central Darfur, East Darfur). Customers from them cannot select a correct address. A data swap, not a design change. | **P0 — blocked on the bank** |
| **R-041 / OQ-017** | An unregistered alphanumeric sender ID: MTN Sudan and Sudani One reject numeric senders outright, so roughly half the market silently never receives an OTP. Registration takes ~3 weeks and cannot be compressed. | **P0 by lead time — start before the pilot** |
| **R-042** | Stage 1b offers WhatsApp by default, but a deployment with no WABA would send a code into a stub and leave the customer watching a timer forever. Needs a journey decision plus a field on the 1b contract and the manifest. | **P0 for launch correctness** |
| **BL-005** | The REJ-01..07 Arabic internal labels and customer-facing SMS copy were written by a session, not sourced from the bank. Customer-facing regulated copy. | **P1 — blocked on the bank** |

### 2B. Customer- and operator-facing, worth fixing before real users, not strictly blocking

| Item | What it is |
|---|---|
| **BL-045** | Both operator readers resolve the *latest* cycle by `seq`, ignoring state. After AD-008 a device-less re-entry produces superseded cycles routinely, so **an operator can be shown the previous person's scan and registry data as this profile's** — and a failed attempt after a good scan blanks every identity column for a profile that demonstrably holds verified data. Its own text says FIX BEFORE PRODUCTION. The most consequential open item in this group. |
| **BL-064** | The BL-039 Slice A regression. A rejected scan whose response is lost on the wire now loops the customer on the upload-retry screen, whose copy says their capture is saved, forever — every retry gets a bare 400 that maps straight back to the same screen. Wire + mobile change. |
| **BL-046** | customer.md Stage 8's "repeated verification failure must surface to operators" is unimplemented. A JWKS or tenant break would drive every customer in the campaign into a 24-hour block, and the bank would see only a rising count of `blocked_scan` with no cause attached. |
| **R-002** | The most visible screens in an Arabic-first bank journey render in English. ~176 override strings; the SDK ships no language folder at all. Task unfiled (T-10). |
| **BL-051** | A scan-blocked customer who relaunches gets `continuation=PROCEED`, no signal, and discovers the block only when they next tap to scan. customer.md:1139-1140 is not implementable for the scan block today. |
| **BL-021 / BL-022** | Both backends exist and are proven; both mobile halves are unwired — the `BLOCKED`/`blockedUntil` launch signal, and the salary-certificate upload. |
| **BL-019 / BL-020** | The customer's reference-number format and the 18-column export field set are both invented placeholders with no bank spec. Changing either later is customer-facing and operator-search-facing, not internal. |
| **BL-014** | No shared golden-vector fixture pins the three Arabic-fold implementations (SQL, Dart, TypeScript) together. Divergence is silent: an occupation "isn't in the list", or an operator's search returns nothing, with no error anywhere. |
| **BL-060** | A signature between ~5 MB and ~5.72 MB decoded gets a bare, uncoded 400. Unreachable from the app (Stage 11 downscales), real for any future client. |
| **BL-053 / BL-054** | The dead-spinner path. Two device runs (S5-07 scan, S5-08 face) both resolved a real cancel in ~2 s and 9.64 s respectively; neither entered its timeout arm. Severity is genuinely lower than filed — this belongs near the bottom of this group. |

### 2C. Owed verification

- **R-016 — the two-person face-mismatch test.** Retired by product-owner decision on
  FIB-derived reasoning, never executed. S5-08's device run was solo and proved only the
  matching path (`match:true`, `matchLevel 5`). The single most load-bearing untested
  assumption in the fraud model.
- **SDN_ID has never been scanned.** R-034's national-ID parser branch stays `[UNVERIFIED]`,
  and front/back side placement is unobserved. Deferred to UAT by decision.
- **R-006 — API 36 behaviour.** Both device runs were on API 28. Accepted Phase 1 residual;
  verified during the bank's UAT device coverage.
- **iOS has never been compiled** — S1-03 ⚠️, R-003, BL-052, AD-003. No Podfile, no
  `NSCameraUsageDescription`; the Uqudo iOS pod has only ever been *read*, never resolved.
  Stages 6, 8, 10 and 11 would fail at camera/photo access on iOS today.
- **The four RDS gates** (also in 2A — they are verification that gates provisioning).
- **R-035** — the integration suite never installs Layer 3, so a migration touching `audit`
  without the flag passes every test and fails on every correctly-deployed database.
- **R-028 / R-033** — two manual `db/post-migrate/` steps that can be silently skipped, one
  of which leaves the reference-list hashes at their old 4-of-9-column definition.
- **OQ-025 residual** — inactive/dormant/closed account, a non-numeric value, and a genuine
  middleware outage have never been exercised.
- **OQ-023 residual** — a well-formed Civil Registry not-found sample is still unobserved;
  accepted by decision, since everything non-conforming collapses to `not_found`.

### 2D. Genuine deferrable hardening

**Latent correctness, not currently reachable:** BL-047 (`insertHistory` hard-codes
`'in_progress'` as from-status), BL-048 (a registry-result proxy for a status held under the
lock), BL-049 (a null `scan_blocked_until` under `blocked_scan` falls through to silent
success in `issueToken`), BL-050 (three Stage 9 writers still trust a released-lock read).

**Narrow races:** BL-010 (two concurrent first submissions for one account), BL-011
(`checkPhoneLockNotActive` read outside the transaction).

**Evidence and retention:** BL-040 (a raced scan rejection loses the record that a JWS
failed verification at all), BL-062 (a superseded signature's bytes are overwritten on the
next redraw), R-013's three unmet operational pieces — nothing invokes the artifact purge,
nothing writes `status='abandoned'`, and the 30-day abandonment sweep does not exist, so
nothing ever reaches the purge-eligible state.

**BL-039 follow-ups:** BL-067 (reactivation zeroes no liveness counter, so a reactivated
customer meets a 24-hour block for a budget arguably owed them — a product question),
BL-068 (the mint counter increments before the Uqudo call it counts; fails safe, over-counts).

**Cost and abuse:** BL-036 (no idempotency key on any mutating call, against
PROJECT_PLAN:159-160 and customer.md stage 13 — a lost ack costs a real attempt from a
budget of five; arguably the strongest candidate in this group for promotion), BL-007 and
R-048 (rate limiting on the unauthenticated surface — same root cause as R-051, no install
identifier to key on), BL-063 (token reuse, the largest identified cost saving, blocked on
OQ-026 alone), R-038 (one audit chain serialises every account check; tolerable at the
stated ~3 submissions/hour).

**Version pinning:** BL-017, BL-025. **Comment/doc corrections:** BL-059, BL-065, and
BL-061 which should simply be closed (T-2). **Device-run procedure:** BL-055 and the two
mis-numbered device-run rows. **Deferred by decision:** BL-001, BL-002, BL-003, BL-004,
BL-006's device-less review flow, BL-023, BL-026, BL-029, BL-035. **Process:** S1-08 / R-009
(coverage completeness), R-010 (no CI), R-039 (machine-local config).

### 2E. Blocked on the bank vs blocked on us

**On the bank (nothing we can do but ask):** OQ-001, OQ-002 (legal viability of AWS),
OQ-010/OQ-011 (own tenant, consent to FIB's), OQ-012/R-014/R-015 (the corrected divisions
dataset), OQ-016–OQ-022 (every messaging commercial question, R-041/R-043 behind them),
OQ-023's outage sample, OQ-025's unexercised codes, OQ-026 (Uqudo's billed unit — the only
thing standing between BL-063 and the largest identified saving), OQ-007, OQ-008, OQ-015,
plus BL-005, BL-019 and BL-020, which are all "the bank has not told us the answer".

**On us:** R-051's auth layer, R-026/R-037's provisioning and the deployment runbook,
BL-045, BL-046, BL-064, BL-036, the whole iOS chain (behind AD-003's macOS route), R-002's
override strings, S1-08, and the plan-file corrections in §1.

---

## 3. What is actually done

Stated with its proof, not asserted.

**The customer journey, stages 0–12, is built end to end on both tiers and hardware-proven.**
Backend stages 1a–12 across S3-01…S3-13 and S5-09/S5-10/S5-11/S5-13; mobile stages 0–12
across S5-01…S5-08 — `stage3`…`stage12_screen.dart` all present on disk under
`mobile/lib/features/`. Two real-hardware runs on the S1-02 Huawei against the live FIB
tenant: **S5-07** (`03947ec`, 2026-09-05) proved the real Uqudo scan pipeline — RS256/JWKS
verification of a real enrolment JWS, real artifacts, Stage 9 rendered, plus the cancel,
camera-denied and BL-034 upload-retry paths; **S5-08** (`e21b845`, 2026-09-06) walked
stages 1→12 continuously in ~11 minutes to reference FRU-000000004, with liveness passing on
hardware (`match:true`, `matchLevel 5`) and the face-cancel path yielding its verbatim
`USER_CANCEL` code. The app's assumed SDK codes were confirmed, not corrected.

**All three external integrations are real adapters, not stubs, and each has been proven
against the live service.** `corebanking/http/HttpCoreBankingClient` (S3-02, live 3/3 on
port 9494), `civilregistry/http/HttpCivilRegistryClient` (S3-14, live 1/1 — the real service's
own 1,105-byte HTTP 400 page classified correctly), `uqudo/http/HttpUqudoClient` +
`UqudoJwksSignatureVerifier` (S5-09, exercised by both device runs). All three files are on
disk under their `http/` packages.

**The fraud fix is closed and proven.** AD-008 built as BL-041 at S5-14 (`c75dfb8`,
2026-09-06): a device-less Stage 1b re-entry supersedes the inherited cycle, every
cycle-keyed artifact and the profile-keyed signature (V0063 on disk), with three
revert-restore proofs and one reviewer BLOCKER — a bricked `awaiting_registry` profile —
found and fixed inside the same slice.

**The scan/liveness budget hole is closed.** BL-039 Slice A (`a041a19`) and Slice B
(`959fa07`), 2026-09-06: single-use pending session, `canAttempt` checked under the lock on
both spend paths, per-type 5 with `TOTAL_LIMIT = 2 * PER_TYPE_LIMIT` derived at
`ScanAttemptBudget.java:34`, and per-profile lifetime mint caps (V0064, V0065 on disk).

**The persistence and audit foundation is complete and tamper-evident.** 65 migrations
(V0001…V0065), four append-only layers, the hash chain, the seal chain with its recompute
attack proven live (S2-04), the database-level status-transition guard (S2-05), and
artifact bytes in `app.artifact_ref.body` with TOAST verified live (S5-06/AD-004).

**The back office is built and live-proven** — sign-in, forced password change, profile
list, single profile view, approve/reject with coded reason, manual completion with the
four-eyes rule refused server-side even when the client ignores `canApprove` (S6-01, S6-02),
on operator auth closed at S4-05 (AD-002e).

**Gate discipline held throughout.** The last full backend gate on record is
`./mvnw verify -Pdb-integration-test`, 987 tests, 0 failures, Spotless clean, JaCoCo met
(S5-14). Every slice in the plan was reviewer-passed, twice where CLAUDE.md requires it.

---

## 4. Anything genuinely untracked

**Nothing untracked surfaced.** Every open item found in the reconciliation already has a
home in one of the four plan files, in AD-002d, or in a committed session report.

Three items are *named but unhomed* — recorded somewhere, actionable nowhere. They are the
§1 findings T-5, T-9 and T-10, repeated here because they are the ones most likely to be
lost between phases: the BL-039 build has no task row, the deployment phase has no task rows
at all, and R-002's ~176 Arabic override strings are described by the risk that names them
as unfiled and remain unfiled. Each needs a row, not an investigation.

One item is *at risk of loss rather than untracked*: T-8, the uncommitted CLAUDE.md rule
block, which together with T-6 makes the lifetime-cap numbers dependent on a working-tree
file and one session report.

No new BL-### or R-### is proposed by this audit.
