# S4-05 — Operator authentication (closes AD-002e)

Every real HTTP call to `/api/v1/operator/**` previously 401'd: `OperatorIdentityArgumentResolver`
read `OperatorIdentity` from request attribute `fru.operatorIdentity`, which nothing set.
S4-01/S4-02 built the whole operator feature set (list, view, approve/reject, manual-complete,
export) against that explicit parameter *by design*, deliberately deferring the authenticating
filter to AD-002e. This session builds it: real Spring Security session auth, a user/role schema,
forced first-password-change, and full audit integration — closing AD-002e.

Research (`docs/sessions/2026-09-02-research-ad-002e-auth.md`) had already settled the framework
specifics against the real Spring Boot 4.1.0/Spring Security 7.1.0 BOM. Plan validated against a
Plan-agent critique before implementation (confirmed the migration-guard reasoning; caught two
real gaps — the dual `ROLE_OPERATOR`/`ROLE_VIEWER` authority grant and explicit JSON login
handlers, both folded in; validated the test-breakage strategy; pushed back usefully on
admin-account provisioning, resolved with a CLI runner instead of an HTTP surface or SQL-only
creation).

## 1. Four product-owner decisions — implemented exactly as given

1. **`OperatorIdentity.operatorId` = `app.operator_user.user_id` (UUID as text), never a
   username.** The four-eyes rule (V0009) matches `actor_id` in an append-only table with no
   correction path — a rename must never break it. Proven live: see §6.
2. **A failed sign-in records `accountExists` + `user_id`, never the submitted username.**
   `AuthAuditRecorder.signInFailed(boolean, UUID)`'s own signature makes passing a username a
   compile error, not a runtime discipline. `AuthFailureAuditListener` holds the attempted
   username only as a local variable, used solely to call `OperatorUserRepository.findByUsername`
   — never passed further.
3. **`server.servlet.session.timeout` left UNSET.** The product owner overrode the earlier "no
   timeout" plan and accepted Boot's 30-minute Tomcat default — free, and the original plan's
   Tomcat-vs-Spring-Session `0`-value hazard (§3.5(a) of the research report) no longer needs
   solving. Recorded in `SecurityConfiguration`'s Javadoc and `docs/components/backoffice-auth.md`
   so nobody reintroduces it if Spring Session is ever adopted.
4. **`app.operator_user` is never deleted, only disabled.** V0057 grants `fru_app` `SELECT,
   INSERT, UPDATE` — no DELETE.

## 2. What is stored about an operator

Login name, real name, role, active flag, password hash, must-change flag. Nothing else — no
email, no lockout columns, matching the task's explicit list.

## 3. Built

- **Migrations** V0057 (`app.operator_role` three-row lookup, `app.operator_user`) and V0058
  (DML-only, seeds one `audit.audit_chain` row `('system','auth',NULL)` for failed sign-ins,
  mirroring `V0034__audit_system_account_check_chain.sql`'s exact reasoning — no `SET LOCAL
  fru.migration_in_progress` needed for either migration, confirmed by reading V0034/V0049/V0051).
- **New `auth` package** (domain/service/jdbc/web/config, mirroring `operator`'s split):
  - `auth.config.SecurityConfiguration` — two `SecurityFilterChain` beans. Chain 1 (`@Order(1)`):
    `/api/v1/operator/**`, `/api/v1/auth/**`, `/api/v1/admin/**`, `formLogin` with explicit JSON
    success/failure handlers (raw `formLogin()` defaults to a 302 redirect — caught before
    writing code, not after), `csrf(csrf -> csrf.spa())`, role-based `authorizeHttpRequests`.
    Chain 2 (`@Order(2)`): `/api/v1/**` catch-all, `permitAll`, CSRF disabled, `STATELESS` — keeps
    the nine customer prefixes unauthenticated.
  - `auth.web.OperatorIdentityFilter` — re-reads `app.operator_user` per request via
    `findActiveById`; this, not the `account_disabled` audit event, is what makes disabling an
    account or changing its role take effect on a live session.
  - `auth.service.OperatorUserDetails` — the authority mapping every authorization rule depends
    on: `must_change_password=true` → `ROLE_PASSWORD_CHANGE_REQUIRED` only; `viewer` →
    `ROLE_VIEWER`; `operator` → **both** `ROLE_OPERATOR` and `ROLE_VIEWER` (the coarse gate on
    `/api/v1/operator/**` is `hasRole("VIEWER")`, so a single-role grant would 403 an operator
    before any controller ran — caught by Plan-agent critique before implementation); `admin` →
    `ROLE_ADMIN` only, never `ROLE_VIEWER` (what makes the prefix 403, not 401, for an admin).
  - `auth.web.PasswordChangeController` — after a successful change, rebuilds `Authentication`
    with full authorities and explicitly `securityContextRepository.saveContext(...)` +
    `request.changeSessionId()` (Spring Security 6+'s `requireExplicitSave` default means a
    mutated context is never auto-persisted).
  - `auth.service.AuthAuditRecorder` — the one class every auth audit event goes through: known
    identity → the operator's own chain (`audit.ensure_operator_chain`, V0047); failed sign-in →
    the seeded `system`/`auth` chain.
  - `auth.config.CreateOperatorAccountRunner` — resolves the scope question the Plan agent pushed
    back on. The task's OUT-OF-SCOPE line excludes back-office UI, and building an admin
    HTTP-CRUD endpoint before this session's own authorization layer existed to protect it would
    have been circular. One `@Profile`-gated `ApplicationRunner`, mirroring
    `ReferenceDocumentPublicationRunner`'s exact precedent, both bootstraps the first admin and
    creates every later operator/viewer account — a real mechanism behind "admin-created
    accounts," not SQL-only creation. Full runbook: `db/post-migrate/03-create-operator-account.md`.
    Missing: listing/disabling/role-changing an existing account — filed as BL-023.

## 4. Existing-test strategy (~549 tests at risk)

- **15 customer-facing `@SpringBootTest` integration classes**: no changes. They hit paths chain
  2 claims; confirmed by grep that no `@RequestMapping` in this codebase falls outside
  `/api/v1/**`.
- **5 `@WebMvcTest` classes**: one line each, `@AutoConfigureMockMvc(addFilters = false)` — the
  security starter makes these slices require auth by default since they don't load the real
  two-chain config; real unauthenticated access is proven by the 15 classes above.
- **5 pre-existing operator `@SpringBootTest` classes** (`ManualCompletionIntegrationTest`,
  `OperatorProfileListIntegrationTest`, `OperatorProfileViewIntegrationTest`,
  `OperatorReviewIntegrationTest`, `ProfileExportIntegrationTest`): same one-line annotation, no
  body changes — their existing `.requestAttr(OperatorIdentity)` direct-injection pattern and
  `...NoOperatorIdentityAre401` assertions both keep working unmodified (that 401 comes from the
  argument resolver's own fallback, independent of any security filter). They stay scoped to
  business logic; real auth is proven in the one new class below.
- **New `auth/OperatorAuthenticationIntegrationTest`** (filters left at their default — the class
  that actually exercises the real chain): full login→operator-endpoint→logout flow (also settles
  task item 5.1: does `formLogin`'s built-in filter save the SecurityContext under
  `requireExplicitSave`? — reusing the same session for a second request could only reach 200 if
  it did); a `must_change_password` account reaching only `/api/v1/auth/password`; four-eyes with
  two REAL seeded UUIDs, including a **rename mid-test** that must not break the match (the R1
  proof); disabling an account mid-session via direct SQL and observing the live-session 401; an
  authenticated admin getting 403; a failed sign-in for an unknown username with the audit
  payload asserted to omit the username entirely (also settles item 5.2 — this assertion can only
  pass if `DefaultAuthenticationEventPublisher` survived the custom `SecurityFilterChain` +
  `AuthenticationProvider` beans); the effective session timeout; and (added under review) a
  direct exercise of `JdbcOperatorUserRepository.create()` against the real database.

## 5. The four things research could not settle

1. **Does `formLogin`'s filter save the SecurityContext under `requireExplicitSave`?** Yes —
   `AbstractAuthenticationProcessingFilter.successfulAuthentication()` calls
   `securityContextRepository.saveContext(...)` explicitly as part of its own success path,
   independent of the default. Confirmed live by reusing one session across two requests in
   `signInReachAnOperatorEndpointAndSignOut`.
2. **Does `DefaultAuthenticationEventPublisher` survive alongside a custom `SecurityFilterChain` +
   `AuthenticationProvider`?** Yes — confirmed live: `failedSignInForAnUnknownUsernameRecordsAcc
   ountExistsFalseAndNeverTheUsername` can only pass if the failure event actually fired and
   `AuthFailureAuditListener` actually ran.
3. **What does `@WebMvcTest` do once `spring-boot-starter-security-test` is on the classpath?**
   Observed directly during implementation: it starts requiring authentication by default (long-
   standing `spring-boot-test-autoconfigure` behavior, not a 7.1-specific wrinkle — a first-pass
   citation to a `spring-framework` GitHub issue was wrong and dropped). `addFilters = false`
   sidesteps it entirely, per §4 above.
4. **What is the effective session timeout?** `MockHttpSession.getMaxInactiveInterval()` was
   tried first and rejected — it is a Spring test double never wired to the real embedded
   Tomcat's session config, and it returned `0`, not a measurement of anything the property
   affects. The real chain, found by decompiling the actual 4.1.0 jars (no `ServerProperties`
   class exists in any 4.1.0 jar under the old pre-4.0 package — Boot 4.1 moved it to
   `org.springframework.boot.web.server.autoconfigure.ServerProperties`): `getServlet().getSess
   ion().getTimeout()` defaults to `Duration.ofMinutes(30)` (decompiled from
   `org.springframework.boot.web.server.servlet.Session`, `spring-boot-web-server-4.1.0.jar`).
   Asserted live with the property genuinely absent from `Environment`.

## 6. Live proofs

- **Sign in, reach an operator endpoint, sign out**:
  `signInReachAnOperatorEndpointAndSignOut` — real `formLogin`+CSRF, session reused across
  requests, logout invalidates it (subsequent call with the same cookie 401s).
- **New account reaches only the change-password endpoint, by authorization not a controller
  check**: `newAccountReachesOnlyThePasswordChangeEndpointByAuthorizationNotAControllerCheck` —
  `/api/v1/auth/me` and `/api/v1/operator/profiles` both 403 pre-change; the same session reaches
  both immediately after the change, with no re-login.
- **Four-eyes with real identities, rename-safe**:
  `fourEyesWithRealIdentitiesSurvivesARenameBecauseActorIdIsTheUuid` — operator A manually
  completes a real profile (built via the existing contact-channels+OTP fixture), A's real,
  authenticated approve is refused; A's `username` is renamed mid-test via direct SQL; A signs in
  again under the NEW username and is STILL refused; B, a distinct identity, succeeds. A passing
  test here is sufficient proof per CLAUDE.md's own rule — V0009's `WHERE NOT EXISTS` is a raw SQL
  predicate a mock cannot fake.
- **Disabling an account takes effect on a live session**:
  `disablingAnAccountTakesEffectOnALiveSessionNotOnlyAtNextLogin` — same session, before/after a
  direct `UPDATE app.operator_user SET is_enabled = false`.
- **Authenticated admin gets 403, not 401**: `authenticatedAdminGets403OnOperatorEndpoint`.
- **Failed sign-in for an unknown username**:
  `failedSignInForAnUnknownUsernameRecordsAccountExistsFalseAndNeverTheUsername` — reads
  `payload_json` back from the real database, asserts `accountExists:false` and that the
  submitted username string is entirely absent from the row.
- **Regression — nine unauthenticated prefixes / full customer journey**: the existing 15
  full-context customer integration classes plus every `@WebMvcTest` slice pass unmodified (named
  per CLAUDE.md's rule, not re-derived); `unauthenticatedCustomerPrefixIsStillReachable` adds one
  direct check of its own, since this is the class that owns the new security config.

## 7. Gates

Flyway from scratch (Testcontainers, V0001→V0058, clean) and the idempotent re-run (explicit
`flyway:migrate` against the local docker-compose Postgres, twice — second run: `Schema "public"
is up to date. No migration necessary.`).

`./mvnw test -Pdb-integration-test`: **601/601**, 0 failures, 0 errors (up from S5-06's 549 — 44
new tests: 10 new integration-class methods on `OperatorAuthenticationIntegrationTest`, 34 new
no-DB unit tests across `auth.domain/service/web/config`). Existing tests needed changing: **10
annotation-only edits** (5 `@WebMvcTest` + 5 operator `@SpringBootTest` classes, each gaining one
`@AutoConfigureMockMvc(addFilters = false)` line and an explanatory comment) plus one Javadoc
range extension in `AbstractPostgresIntegrationTest`. **Zero test-body logic changed in any
pre-existing test.**

**Real finding, not this session's regression**: plain `./mvnw verify` (no `-Pdb-integration-
test`) measures 60.52% line coverage — well under the 80% gate — because a large share of
pre-existing classes across many features (JDBC repositories, several web controllers,
wiring-heavy config classes), not only this session's `auth` package, are exercised only by the
`@Tag("integration")` suite. `-Pdb-integration-test` has therefore always practically been
required to clear 80%; a session that ran only `./mvnw verify` and trusted a "checks met" belief
without actually invoking JaCoCo would have shipped an untested illusion of a passing gate. This
session's own `auth` package measures 88% standard-only (in line with the codebase's existing
JDBC-relies-on-integration-tests convention — `JdbcOperatorUserRepository` is the one class left
under-covered without a database). Filed as RISKS.md R-050; CLAUDE.md's Coverage section
corrected to name the real gate command.

Full gate, final: `./mvnw verify -Pdb-integration-test`:

```
601/601 tests, 0 failures, 0 errors
JaCoCo: 91.93% line (bundle)
Spotless: clean
BUILD SUCCESS (exit 0)
```

## 8. Review

`@agent-reviewer` ran twice against the diff and this task.

**First pass** — 4 verified findings, all fixed same session:
- SHOULD FIX: `PasswordChangeService.changePassword` wrote the password update and its audit
  event as two separate autocommit statements — an audit failure would leave the password
  changed with no record. Fixed: wrapped both in `TransactionTemplate.executeWithoutResult`,
  matching `ManualCompletionService`'s own pattern.
- SHOULD FIX: `JdbcOperatorUserRepository.create()` — the only account-creation mechanism in the
  system — was exercised by no test against a real database (only mocked in
  `CreateOperatorAccountRunnerTest`). Fixed: added a direct integration-test method.
- SHOULD FIX: `docs/components/backoffice-auth.md` claimed a live proof
  (`session.getMaxInactiveInterval() == 1800`) the test does not make — the actual assertion is
  on `ServerProperties`, and the doc's own text even names the `MockHttpSession` approach as
  rejected. Fixed: reworded to the real assertion.
- NOTE: `OperatorIdentityArgumentResolver`'s Javadoc and its 401 message text were stale — both
  described the filter as not existing, when it now does. Fixed: reworded to reference
  `auth.web.OperatorIdentityFilter` and the real reachable 401 causes.

Plus two documentation-only NOTEs (no behavioral change): a `SignInSuccessHandler` comment
overclaimed that writing the audit in the success handler makes the write failure-proof —
reworded to state honestly that a throw there surfaces as a 500 to an already-authenticated
caller with no record; and a stale coverage percentage in CLAUDE.md, corrected.

**Second pass**, against the fixed diff — found a real process gap, this project's twelfth
consecutive session where the re-review catches something in the first pass's own fixes:

- **BLOCKER**: all six first-pass fixes existed only in the working tree — `git status` showed
  every touched file as unstaged (`MM`/`M`) after the earlier `git add -A` had run BEFORE the
  fixes were made, not after. A commit at that point would have shipped the version the reviewer
  had just rejected, with plan files describing the fixed one. Fixed: re-staged immediately.
- **SHOULD FIX**: `PasswordChangeServiceTest`'s proof of the transaction wrap used a mocked
  `PlatformTransactionManager`, which makes the guarded callback run identically whether or not
  `TransactionTemplate` actually wraps it — the exact "a green test could coexist with a broken
  guard" case CLAUDE.md's live-proof rule names, and this codebase had already settled the same
  question once before (`ContactChannelsIntegrationTest`'s own comment: "A mocked
  PlatformTransactionManager ... cannot exercise real rollback"). Fixed: added
  `aFailingAuditWriteRollsBackThePasswordChangeTogetherNotPartially` to the real integration test
  — a `@MockitoSpyBean AuthAuditRecorder` made to throw, asserting the password row is unchanged
  after the request fails. **Revert-tested per CLAUDE.md's rule** (an indirect assertion): with
  the `TransactionTemplate` wrapper removed, this test fails on a genuine assertion (the hash DID
  change even though the audit write threw) — not merely errors out (the first version of the
  test errored identically with or without the fix, since `MockMvc` re-throws an unhandled
  Servlet exception rather than resolving it to a response, and had to be rewritten around
  `assertThrows` to actually reach the database assertion). Restored the wrapper; the test passes.
- **NOTE**: the newly-disclosed audit-failure residual in `SignInSuccessHandler` lived only in a
  source comment, and the comment's own "same class as R-048" citation was wrong (R-048 is about
  unbounded row growth, not a state change outliving a failed audit write) — this project flagged
  comment-only disclosure as a defect once already (S5-04). Fixed: added a bullet to
  `backoffice-auth.md`'s "Known gaps" section and dropped the loose citation.

Full gate re-run after this pass: 601/601 (`-Pdb-integration-test`), JaCoCo 91.93% line, Spotless
clean.

## 9. Docs

- `docs/components/backoffice-auth.md` — new card (built, not "researched, not built").
- `docs/components/persistence.md` — "Operator accounts (AD-002e, S4-05)" section, plus the
  `actor_kind` open-verification-item note.
- `docs/journeys/operator.md` — the `[OPEN: ...]` provisioning marker replaced.
- `PROJECT_PLAN.md` — AD-002e moved from "Open architecture decisions" to the closed Decisions
  log; OQ-013 marked answered.
- `RISKS.md` — R-048 (no lockout/rate-limit + one serialised failed-sign-in chain), R-049
  (failed-sign-in payload deliberately excludes the username, by construction), R-050 (the
  coverage-gate finding, §7 above).
- `BACKLOG.md` — BL-023 (the still-missing admin account list/disable/role-change HTTP surface).
- `CLAUDE.md` — Coverage section corrected (§7 above).
- `db/post-migrate/03-create-operator-account.md` (+ `README.md` pointer) — the CLI runbook.

## 10. Commit/push proof

```
$ git log --oneline -1
fa42d42 feat: S4-05 -- close AD-002e, operator authentication

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```
