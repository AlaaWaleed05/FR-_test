# CLAUDE.md

**Fr_user_update** — a customer data-update solution for a bank: a mobile app for
retail customers to re-verify contact/social/address/identity details (OTP, Uqudo
document scan + liveness, Civil Registry lookup), a backend orchestrator, and a
back-office web app for bank operators.

## Constraints

- Arabic-first UI, full RTL layout.
- Sudan market.
- Core banking integration is a secure HTTPS/JSON call to the bank's middleware
  (`CheckAccount`: account number only → `Response_Code` (JSON number or string) +
  `Response_Message`; the full code list is OQ-025). Read-only. This replaces the earlier Oracle stored-procedure
  (`ProcessOmniCheckAct`) assumption — corrected 2026-09-04, see PROJECT_PLAN.md AD-007.
- Uqudo eKYC: token issuance, document scan/validation, document detail retrieval
  (image + extracted portrait), liveness/biometric verification and detail retrieval.
- Civil Registry lookup by national number.
- Outbound SMS, WhatsApp and email throughout the journey, not only on completion: three
  independent OTP codes at channel verification (one per channel — a single code sent to
  several channels proves none of them), a submission notification, and a message on every
  subsequent status transition. Sizing and provider selection must assume several messages
  per customer, not one.
- PII and identity-document images stored at rest.

## Architecture

- `mobile/` (Flutter, Android only — iOS dropped, AD-003) — customer journey UI.
- `backend/` (Spring Boot, Java 21) — owns every external integration (Uqudo, the core
  banking `CheckAccount` middleware call, Civil Registry, SMS/WhatsApp/email) and persistence. The only
  tier holding Uqudo client credentials.
- `backoffice/` (React + TypeScript + Vite) — operator web UI. Reads backend-held profile
  data, and performs the system's write actions: manual per-field data entry on
  CUSTOMER-ENTERED fields only (AD-015 as narrowed 2026-09-14 — Civil Registry and Uqudo
  fields are permanently read-only, which is what restores R-054's bound; not yet built,
  BL-135), approve/reject a submitted profile with a coded reason, and print the update form.
  Manual completion was removed by AD-022 (2026-09-16); only mobile submission creates a profile.
- `backend/` package layout, settled at S3-01 and binding on every later slice:
  **features are the top-level cut, and two package-name suffixes are reserved for business
  logic — `domain` and `service`.** A package with either suffix contains only code whose
  behaviour we chose and that a plain JUnit test can exercise with no Spring context, no
  database, no network and no clock — Spring stereotype annotations (`@Service`, and the
  like) are still allowed, since it's the test dependency that's forbidden, not the
  annotation. Everything else — `web`, `config`, `stub`, `jdbc`, and future adapter
  packages — is plumbing. A port (`CoreBankingClient`, `AuditEventWriter`) is an interface
  and belongs in a `domain`/`service` package — **never in its adapter's package**. Shared
  outbound integrations (`corebanking`, `audit`) sit beside the features, one package per
  external system, so each is its own quarantine boundary. Do not scaffold packages for
  features that do not exist yet.
- That naming rule is what S1-08's 90% business-logic tier is scoped to. A JaCoCo
  `<element>PACKAGE</element>` include must be **dot-separated**
  (`com.sfbank.bayanati.*.domain`); the slash form matches nothing and fails silently —
  proven live in S3-01 (`JavaNames.getPackageName` replaces `/` with `.` before matching). Two
  properties of the matcher to keep in mind when writing one: `*` becomes regex `.*`, so it spans
  package separators and the pattern also matches deeper nestings; and the `.` characters are
  regex "any character", not literals.
- `backoffice/` and `mobile/` now DO have feature code — `mobile/lib/features/` (6 features) plus
  `mobile/lib/core/` (17 modules; 102 Dart files across `mobile/lib`), and `backoffice/src/`
  (6 top-level directories, 51 TypeScript files).
  S1-08 is therefore no longer blocked for want of a structure to scope a rule to; what those two
  tiers still lack is a *reserved logic-package convention* like the backend's `domain`/`service`.
  Corrected 2026-09-14 — the "no feature code yet" claim had been stale since S5-05/S6-01.

## Hard rules

- Read files from local disk. Never the GitHub API, never web fetch for repo contents.
- The reference implementation is at `../FIB`, read-only. Never edit it, never commit
  any part of it into this repository.
- No secrets in code, prompts, logs, or session reports.
- Session start: read PROJECT_PLAN.md, EXECUTION_PLAN.md, BACKLOG.md, RISKS.md.
  Customer-facing or operator-facing work reads the journey stages it touches plus the
  resume rules (customer.md stage 13), not the whole document.
- Session end: update plan files → write docs/sessions/YYYY-MM-DD-<slug>.md → commit →
  push. This means straight to `main`, not a feature branch — branch-per-session is not
  this project's norm. A session an environment forces onto a branch must say so explicitly
  and get it fast-forwarded into `main` before the session ends, so the branch/tracking
  check every session-start reads stays meaningful.
- Architecture decisions listed as open in PROJECT_PLAN.md are NOT to be settled in
  passing. If work requires one, stop and say so.
- Third-party SDK or API integration: run `@agent-researcher` first. Never integrate
  from documentation alone. This applies to Uqudo, the core banking middleware, and the Civil
  Registry without exception.
- No real customer data, no live account numbers, and no identity-document images in
  the repo, in fixtures, in prompts, or in session reports.
- Before marking a task done: `@agent-reviewer` against the diff and the EXECUTION_PLAN.md task.
- iOS is DROPPED (AD-003, cancelled 2026-09-10; confirmed 2026-09-14). Do NOT check a Flutter
  package's iOS support and do not report one. Reviving iOS re-opens AD-003 first.
- Uqudo credentials are supplied at session time and live only in a local gitignored
  config. Never in the repo, a prompt, a log, or a session report — not even redacted.
  The Uqudo tenant identifier and all endpoints are configuration, never hardcoded and
  never baked into a build, because the tenant changes before production.
- NEVER hand-edit generated files. Edit the source, then re-run the tool. Paths:
  - mobile — `.dart_tool/`, `.fvm/`, `build/`, `android/app/build/`,
    `ios/Flutter/Generated.xcconfig`, `ios/Runner/GeneratedPluginRegistrant.h`,
    `ios/Runner/GeneratedPluginRegistrant.m`, `pubspec.lock`
  - backend — `target/`, `.mvn/wrapper/maven-wrapper.jar`
  - backoffice — `dist/`, `node_modules/`, `coverage/`, `package-lock.json`
- Uqudo results are a JWS compact string. Signature verification and parsing happen
  SERVER-SIDE ONLY. The mobile app forwards the raw JWS untouched and never decodes
  it. The FIB reference at `../FIB` does the opposite — do not copy it.
- The Uqudo JWS parser is quarantined in a single class. No other code may depend on its
  internal shape or on any field the component card marks [UNVERIFIED]. It is written against
  the stub, and rewritten against a real logged JWS when S1-02 runs. See R-034.
- Run the test and analyze gates for every tier you touched before every commit, and paste the
  final gate output verbatim — never a summary. An intermediate run after a review pass gets one
  line (command, count, pass/fail); a run that failed gets its failure output pasted, since that
  is evidence, but a passing re-run that only repeats an earlier passing run does not.
- Live proof versus a passing test. Paste live output only if a green test could coexist with a
  broken guard (a rejected write, blocked mutation, lost evidence) — not when a named integration
  test already exercises it against the real database, where naming that test and what it asserts
  is the proof instead. If unsure, ask whether the test could pass against the broken version.
- Revert-restore is conditional: prove a defect-guarding test by reverting the fix and confirming
  it fails, but only for indirect assertions or identical-happy-path ordering defects — a direct
  wrong-value assertion needs no revert, since it cannot pass against the bug. Say which, and why.
- Multi-file or unfamiliar changes: plan mode first, approval, then implement.
- When a feature's backend half ships and the mobile half is deferred, the deferring session
  must update the mobile-side comment in that SAME commit to say the endpoint exists and the
  client does not call it yet — never that no endpoint exists. A comment freezing an old
  truth is self-confirming: it stops the next session checking. Four findings so far —
  BL-101, BL-065, BL-021, BL-105.
- Commit proof is captured AFTER the push, never before. A report that pastes a `git status`
  reading "ahead of 'origin/main'" while claiming the work was pushed has not proved the push —
  it has proved the opposite and been contradicted by its own evidence (S8-12). The final
  status must read up to date with `origin/main`.
- **This file is capped at 250 lines.** At the cap, earn a new rule's place by cutting one whose
  removal would not cause a mistake, and say which in the session report.
- Reference lists are never hardcoded. Occupations, branches, administrative divisions,
  income sources and rejection reason codes are server-supplied, cached, and
  version-checked. The list version used for a submission is recorded on the profile.
- When compacting, preserve: modified file list, test commands, unresolved failures,
  open BL-###, R-### and AD-nnn items.
- Session reports carry proof, not narrative. Target 150–250 lines; verbatim gate output
  and a single revert-restore proof do not count toward it, since the rules require them —
  a report is over length only when its PROSE is. What earns its place: gate output, review
  findings with dispositions, design decisions with reasoning, live proofs where a passing
  test would not suffice, what failed, what was skipped, commit proof. What does not: prose
  restating a table, a decision explained twice, the same capture pasted in two sections,
  line-number corrections narrated as findings when they are already applied above, or
  re-narration of steps the task already specified. A report is re-read on every planning
  turn — length is a recurring cost.
- Name sections, not whole documents. `docs/journeys/customer.md` is over a thousand lines; a
  backend slice needs two stages of it. Name a whole document only when it is genuinely short.
- Phone numbers are E.164 everywhere — wire, storage, comparison and display masking. Reject
  non-ASCII digits at the boundary.
- Scan/liveness attempt budget (BL-039, 2026-09-06): the pending scan session is
  SINGLE-USE — consumed at the moment an attempt is spent (in the failure/wrong-number
  paths), never on a successful scan (consuming on success regresses BL-034's upload
  retry). Per-type scan limit is 5, total is derived as 2× per-type (10) — never write the
  total as a literal. A separate per-profile lifetime cap of 20 scan-token mints and 20
  face-token mints (never reset, and deliberately NOT cleared by AD-008's device-less
  reset) reuses the existing 24-hour block when crossed — no new error code or screen.
  The budget counts spent attempts, never token issuance for the retry limit; camera-
  denied, ARTIFACT_EXPIRED and IMAGES_UNAVAILABLE are exempt and must stay free. Any block
  applied by the cap must gate on status == in_progress (applyScanBlock/applyLivenessBlock
  hard-code that from-status; applying from another status is the BL-043 defect).

## Commands

The requirement is what's enforced; a machine's paths are just how it's satisfied there.
State the requirement first, resolve the local path second, so a session on a machine not
listed below still knows what to look for.

- **Java 21**, gated by the Maven enforcer plugin — any Java 21 satisfies it.
- **Flutter 3.47.0**, the version pinned in `mobile/.fvmrc`. The pin is what matters, not
  the launcher that resolves it.
- **Node ≥24.19.0 <25**, enforced by `engines` plus `engine-strict` in
  `backoffice/package.json` — a global Node outside that range is refused, not silently
  used.

- **Windows (DELL):** PATH is not preconfigured in a fresh shell — resolve these first,
  every session. `fvm` is not on PATH: `C:\Users\DELL\AppData\Local\Pub\Cache\bin\fvm.bat`.
  `JAVA_HOME` must be set to `C:\Program Files\Android\Android Studio\jbr` (JBR OpenJDK
  21.0.8, the only JDK on this machine) before any `mvnw` invocation. backoffice needs the
  isolated Node install at `C:\Users\DELL\.local-tools\node-v24.19.0-win-x64` (global Node
  is 22.22.2 and is refused by `engine-strict`).
- **Linux (S3-01's remote session):** none of the paths above exist. `mvnw` is mode 644 in
  the repo, so it is not directly executable — run it as `sh ./mvnw ...`. `JAVA_HOME` was
  OpenJDK 21.0.10. `fvm` was not installed; Flutter 3.47.0 — the exact pinned version — was
  cloned to `/opt/flutter-3.47.0` and run directly as
  `/opt/flutter-3.47.0/bin/flutter`, same SDK, different launcher. Node 24.20.0 was
  installed with `nvm install 24` at `/opt/nvm` (the global Node there, 22.22.2, was
  refused by `engine-strict` exactly as this section predicts). Check first on a fresh
  Linux box: the Docker daemon was not running and needed `dockerd` started manually; and
  Docker Hub image blobs were blocked by the network policy (`docker pull postgres:18` and
  `testcontainers/ryuk:0.14.0` both `403 Forbidden`), worked around by pulling the same
  images through `mirror.gcr.io` and retagging them to the names Testcontainers expects —
  same digests, so the Testcontainers runs were against the real `postgres:18`.

The agents referenced throughout this file (`@agent-researcher`, `@agent-reviewer`) live in
`.claude/agents/` in this repository, not a home directory — a project-level agent
overrides a user-level one of the same name, so a machine that also carries a
home-directory copy is unaffected either way.

### mobile/ (Flutter, run from `mobile/`)
- Test: `fvm flutter test`
- Test + coverage, enforced at 80%: `fvm dart run tool/check_coverage.dart`
- Lint/analyze: `fvm flutter analyze`
- Build (Android debug): `fvm flutter build apk --debug`

### backend/ (Spring Boot / Maven, run from `backend/`)
- Test: `./mvnw test`
- Test + coverage + lint, all enforced: `./mvnw verify` (JaCoCo 80% line-ratio check +
  Spotless format check, both bound to `verify`)
- Lint/format only: `./mvnw spotless:check`
- Build: `./mvnw package -DskipTests`
- DB connectivity integration test (requires Docker running; skipped by the three commands
  above): `./mvnw test -Pdb-integration-test` — boots the full Spring context, including
  `DataSourceAutoConfiguration`/`FlywayAutoConfiguration`, against a real, ephemeral
  Testcontainers PostgreSQL 18 and asserts the migrated schema state. See
  `AppSchemaConnectivityIntegrationTest`.
- Running the packaged backend locally against `docker-compose.yml`'s Postgres (e.g. for a
  live cross-tier proof) does NOT migrate the schema on its own: `spring-boot-flyway` is
  `test`-scope only by deliberate design (pom.xml comment, S2-02) so the shipped jar connects
  only as `fru_app`, which holds zero DDL rights. Migrate explicitly first, as `fru_migrator`:
  `./mvnw flyway:migrate` (reads `DB_PORT`/`DB_NAME`/`DB_MIGRATOR_PASSWORD`/`FRU_APP_PASSWORD`/
  `FRU_SEALER_PASSWORD` from the environment — source `.env` first). Found live at S5-01: the
  packaged jar started and ran happily two versions behind, and every endpoint touching the
  missing migrations' objects failed with `permission denied` / `bad SQL grammar`, not a
  startup error.

### backoffice/ (React + TypeScript + Vite, run from `backoffice/`)
- Test: `npm run test`
- Test + coverage, enforced at 80% (lines/statements/branches/functions):
  `npm run test:coverage`
- Lint: `npm run lint`
- Build: `npm run build`

## Coverage

- 80% overall line coverage is enforced in all three tiers: backend via JaCoCo
  `check` bound to `verify`; backoffice via Vitest v8 thresholds in `vite.config.ts`;
  mobile via `mobile/tool/check_coverage.dart` (Flutter has no built-in threshold
  enforcement, so this script parses `lcov.info` and fails under 80%).
- **Backend correction (found live at S4-05):** plain `./mvnw verify` alone does NOT
  clear 80% — measured at 60.52% line, standard tests only. A large share of this
  codebase's classes (JDBC repositories, several `web` controllers, wiring-heavy `config`
  classes) are exercised only by the `@Tag("integration")` suite, which `./mvnw verify`
  excludes by design (`excluded.test.groups`). The bundle-wide JaCoCo `check` has
  therefore always practically depended on `-Pdb-integration-test` also running to reach
  80% (confirmed live: 91.93% with it, 60.52% without). Treat
  `./mvnw verify -Pdb-integration-test` as the real backend coverage gate; `./mvnw verify`
  alone proves Spotless and the non-integration tests only.
- The 90% business-logic tier is NOT enforced for any tier — see Architecture for which tiers
  have logic packages to scope a rule to. Tracked as S1-08.
- Caveat: neither the Flutter nor the Vitest coverage tooling counts a source file
  that no test imports at all. A wholly untested directory leaves the percentage
  unmoved, not lowered — the gate protects exercised code from regressing, not against
  an untested feature shipping. S1-08 must also close this gap (see R-009).
