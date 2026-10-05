# Component: Back-office authentication (AD-002e)

Status: built · Last verified: 2026-09-02
Sources: docs/sessions/2026-09-02-research-ad-002e-auth.md;
docs/sessions/2026-09-02-s4-05-operator-auth.md

## Decision
Decided by the product owner, not by research: three roles (viewer, operator, admin);
admin-created accounts, no self-registration; system-generated initial password shown once,
forced change on first sign-in; no self-service reset; server-side sessions; **accept the
framework's 30-minute idle timeout** (not "no timeout" — see below); no lockout in v1; every
authentication event audited; admin mutually exclusive with viewer/operator on one account.

## Versions (managed by spring-boot-dependencies 4.1.0)
| Component | Version | Marker |
|---|---|---|
| Spring Security | 7.1.0 | [OBSERVED spring-boot-dependencies-4.1.0.pom] |
| Spring Framework | 7.0.8 | [OBSERVED same] |
| Tomcat | 11.0.22 | [OBSERVED same] |

Dependencies added: `spring-boot-starter-security`, `spring-boot-starter-security-test` (test).

## Two chains, not one
Chain 1 `@Order(1)`: `/api/v1/operator/**`, `/api/v1/auth/**`, `/api/v1/admin/**` — `formLogin`
(`loginProcessingUrl=/api/v1/auth/login`), `csrf(csrf -> csrf.spa())`, role-based
`authorizeHttpRequests`, `HttpStatusEntryPoint(401)`. Chain 2 `@Order(2)`: `/api/v1/**` catch-all
— `permitAll`, CSRF disabled, `STATELESS`. Covers every unauthenticated customer prefix under
`/api/v1/**` not claimed by chain 1 — account-check, contact-channels, otp, data-entry,
identity-scan, liveness, signature, submission, reference, and (S4-06) salary-certificate.
Spring Security dispatches to the first chain whose `securityMatcher`
matches; a future endpoint added outside `/api/v1/**` would fall through both chains and run
with NO security filters at all (silently unprotected, not 401'd) — keep every new controller
under `/api/v1/**`. `com.sfbank.bayanati.auth.config.SecurityConfiguration`.

## Reaching `fru.operatorIdentity`
`auth.web.OperatorIdentityFilter extends OncePerRequestFilter`, added
`addFilterAfter(..., AnonymousAuthenticationFilter.class)`. It re-reads `app.operator_user` per
request via `OperatorUserRepository.findActiveById` — that re-read, not the audit event, is what
makes "account disabled" take effect on a live session. It sets nothing for a disabled account. Since AD-013/BL-139 it DOES set an identity for
`admin`, at the `OPERATOR` access level -- the `role() != ADMIN` filter that used to drop them
is gone. `operator.web.OperatorIdentityArgumentResolver` and
`operator.config.OperatorWebConfiguration` are unchanged from S4-01.

**`OperatorIdentity.operatorId` is `app.operator_user.user_id` (UUID) as text, never a
username.** The original justification was V0009's four-eyes predicate, which matched
`h.actor_id = :operator_id`; **AD-013 removed that rule on 2026-09-13**. The conclusion is
unchanged and now rests on the audit trail instead: `actor_id` is written into append-only
`app.profile_status_history` and `audit.audit_event` rows, so a renameable identifier would
silently re-attribute past actions with no correction path. Proven live:
`auth.OperatorAuthenticationIntegrationTest#actorIdIsTheUuidSoARenameDoesNotRewriteHistory`
renames operator A's `username` mid-test and asserts the history row and the audit event
still carry A's UUID.

## Authority mapping — the single most important correctness point
A `must_change_password = true` account is granted ONLY `ROLE_PASSWORD_CHANGE_REQUIRED`.
Otherwise: `viewer` → `ROLE_VIEWER`; `operator` → **both** `ROLE_OPERATOR` and `ROLE_VIEWER` (the
coarse gate on `/api/v1/operator/**` is `hasRole("VIEWER")`, so an operator granted only
`ROLE_OPERATOR` would 403 before reaching a controller); `admin` → `ROLE_ADMIN` AND `ROLE_OPERATOR` AND `ROLE_VIEWER`, the ladder AD-013 settled
(built at S8-28, BL-139). Until then admin was granted `ROLE_ADMIN` only, never `ROLE_VIEWER`,
and THAT is what made `/api/v1/operator/**` 403 rather than 401 for an authenticated admin.
Note which gate that was: the 403 came from `AuthorizationFilter`, not from the identity filter
or the argument resolver, so granting the two extra authorities is half the fix and unfiltering
the identity is the other half. Neither alone is sufficient. `auth.service.OperatorUserDetails#getAuthorities`, unit-tested directly (no Spring
context).

## Password storage
BCrypt inside `DelegatingPasswordEncoder`
(`PasswordEncoderFactories.createDelegatingPasswordEncoder()`); stored as `{bcrypt}$2a$…`,
default strength 10 (accepted, not re-measured this session — record if a later session
re-tunes it). Argon2 needs BouncyCastle; PBKDF2 only if OQ-001 ever mandates FIPS — that is the
flip condition, and it costs nothing to defer given the `{id}` prefix. Password input capped at
72 bytes (bcrypt's limit; Arabic is 2 bytes/char) at `auth.service.PasswordChangeService` and the
account-creation runner — never on the login path itself, which Spring Security's own filter
owns. `CompromisedPasswordChecker`/HaveIBeenPwned is NOT wired (an outbound internet call with an
unknown Sudan egress story).

## Forced first change
`must_change_password = true` restricts authorities as above, so `AuthorizationFilter` denies
every rule but `POST /api/v1/auth/password` before any controller or argument resolver runs — no
controller-side check exists. After a successful change,
`auth.web.PasswordChangeController` reloads a fresh `OperatorUserDetails`, builds a new
`Authentication` with the full authority set, and explicitly
`securityContextRepository.saveContext(...)` plus `request.changeSessionId()` — required because
`SecurityContextHolderFilter` only READS the context since Spring Security 6's
`requireExplicitSave` default. Proven live: the same session reaches `/api/v1/auth/me` and
`/api/v1/operator/profiles` immediately after the change, with no re-login.

## Session idle timeout — the task's decision, not research's original recommendation
**`server.servlet.session.timeout` is deliberately left UNSET.** The product owner accepted
Boot's Tomcat default (30 minutes) instead of the earlier "no timeout" plan. **Recorded so nobody
reintroduces the original hazard**: `server.servlet.session.timeout=0` means "never expire" under
plain Tomcat but "expire immediately" under Spring Session (`MapSession.isExpired()`) — this
project uses plain Tomcat sessions (no Spring Session), so the hazard does not apply today, but
would resurface instantly if Spring Session JDBC were ever adopted without re-verifying this from
scratch. Proven live: `auth.OperatorAuthenticationIntegrationTest
#effectiveSessionTimeoutIsThirtyMinutesWithThePropertyLeftUnset` asserts
`serverProperties.getServlet().getSession().getTimeout().equals(Duration.ofMinutes(30))` with
`server.servlet.session.timeout` genuinely absent from `Environment` — not
`MockHttpSession.getMaxInactiveInterval()`, which the test tried first and rejected: it is a
Spring test double never wired to the real embedded Tomcat's session configuration and returned
`0`, not a measurement of anything the property actually affects.

**Sessions are in-memory Tomcat, not Spring Session** — every deployment restart signs every
operator out. Accepted: Spring Session JDBC would need new tables `fru_app` cannot create
(`spring.session.jdbc.initialize-schema=never`, needing explicit grants including `DELETE`,
which `fru_app` holds on almost nothing) plus re-verifying the timeout hazard above.
`server.servlet.session.persistent` is NOT used — it would write live session data (the operator
principal) to a file on disk, a new at-rest PII surface outside AD-004/AD-002d's encryption
story.

## Audit integration
- Sign-in success / sign-out / password change → the operator's own chain via
  `audit.ensure_operator_chain(user_id)` (V0047), `actor_kind='operator'`, `actor_id=user_id`.
  Written by `auth.service.AuthAuditRecorder`, the one class every auth-related audit write goes
  through.
- Failed sign-in → the ONE pre-seeded `system`/`auth` chain (V0058, DML only — mirrors
  `V0034__audit_system_account_check_chain.sql` exactly, no `SET LOCAL
  fru.migration_in_progress` needed since an INSERT triggers neither of
  `audit.block_audit_ddl()`'s two events). Payload `{"accountExists": bool, "userId":
  string|null}` — **the submitted username never reaches `AuthAuditRecorder` and is never
  written**, even for a real, existing account whose password was simply wrong.
  `auth.web.AuthFailureAuditListener` listens on `AbstractAuthenticationFailureEvent` (the
  abstract type — `UsernameNotFoundException` and `BadCredentialsException` both publish the same
  concrete event, so the type can never distinguish them) and does its OWN
  `OperatorUserRepository.findByUsername` lookup, independent of whatever
  `DaoAuthenticationProvider` decided internally. Proven live:
  `#failedSignInForAnUnknownUsernameRecordsAccountExistsFalseAndNeverTheUsername` reads
  `payload_json` back from the real database and asserts the submitted username string is absent
  from it entirely; the same assertion can only pass if
  `DefaultAuthenticationEventPublisher` actually survived alongside the custom
  `SecurityFilterChain`/`AuthenticationProvider` beans (task's own open determination, settled by
  this test).
- `payload_json` is flat RFC 8785 canonical JSON via `audit.domain.CanonicalJson` — never the
  password, old or new, hashed or not.
- Written in `SignInSuccessHandler`/`PasswordChangeService`/`SignOutAuditLogoutHandler`, not
  `@EventListener`s, for the success paths — this codebase's standing rule is "an unaudited
  action is worse than a failed one," which means controlling ordering and failure directly
  rather than relying on an async/best-effort event.
- Account created / role changed / account disabled have no producing code path this session —
  no admin HTTP endpoint exists yet (see "Account provisioning" below). Recorded as an open item,
  not invented speculatively.

## Account provisioning
No HTTP admin CRUD surface this session — the task's OUT-OF-SCOPE line excludes back-office
UI/admin screens, and building an account-management endpoint before the authorization layer
protecting it existed would have been circular. Instead: `auth.config.CreateOperatorAccountRunner`,
a `@Profile("create-operator-account")`-gated `ApplicationRunner` mirroring
`reference.config.ReferenceDocumentPublicationRunner`'s exact precedent — generates a
`SecureRandom` one-time password, prints it to stdout exactly once (never logged, never audited,
never written to any file), creates the row with `must_change_password=true`, exits. Both
bootstraps the first admin and creates every later operator/viewer account. Full runbook:
`db/post-migrate/03-create-operator-account.md`. **Still missing**: listing, disabling or
changing an existing account's role — filed to BACKLOG.md.

## Known gaps in the decided design, stated rather than papered over
- Sign-out is audited only when explicitly called. A closed browser produces no event, and the
  session persists (unaudited) until it idles out at 30 minutes or the JVM restarts.
- No lockout + no rate limiting (BL-007) + one serialised `system`/`auth` audit chain (R-038's
  shape) = unbounded audit growth from failed sign-ins, with the chain-row lock as the only
  throttle. Both deferrals are deliberate; see RISKS.md for the recorded consequence.
- Admin can create an account and use it; the design's actual safeguard is that this shows up as
  a separate identity in the audit trail, not that it is prevented.
- A sign-in success's audit write is not failure-proof: `AbstractAuthenticationProcessingFilter`
  saves the `SecurityContext` to the session before invoking `SignInSuccessHandler`, so if
  `AuthAuditRecorder.signInSucceeded` itself throws, the caller ends up with a live, authenticated
  session and a 500 response, with no `sign_in_succeeded` record. Password-change audit writes are
  the one write in this feature actually protected against this (wrapped in a
  `TransactionTemplate` with the state change, S4-05 review finding) — sign-in success and sign-out
  are not, and are not planned to be: the state they guard (an HTTP session) is not a database row a
  transaction can roll back.

## Open
- [ ] Cookie `SameSite`/`Secure` and CORS — blocked on AD-002d (same-origin or not).
- [ ] BCrypt work factor (10, the library default) not re-measured against a real deployment
      target — cheap to raise later via the `{bcrypt}` prefix's re-encode-on-next-login path.
- [ ] Admin account list/disable/role-change HTTP surface — filed to BACKLOG.md.
- [x] `docs/journeys/operator.md`'s access model section named `admin` as a third,
      non-back-office role. CLOSED at S8-28 (BL-139): AD-013 reversed that ruling, so the
      section now carries a three-row hierarchy table. `OperatorAccessLevel` is indeed still
      unaffected and still two-valued — admin maps onto `OPERATOR`. [CLOSED 2026-09-13]
