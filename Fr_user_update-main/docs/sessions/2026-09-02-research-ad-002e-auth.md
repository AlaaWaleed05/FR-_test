# AD-002e — Back-office authentication on Spring Boot 4.1

**Scope note (ambiguity I resolved).** The task says "do NOT write any code, migration or configuration", and also "Give the concrete configuration, not a description of it" and "the user and role schema — enough to migrate". I read those as: do not create files in the repository; the report itself must carry the concrete Java and SQL. Everything below is report content for you to act on, not files. I did not pursue the reading where the report withholds code — it would not answer items 1 and 6.

---

## 1. Question

Confirm how to implement an already-decided back-office authentication design correctly on Spring Boot 4.1: the current Spring Security configuration shape, how the authenticated principal reaches the existing `fru.operatorIdentity` request attribute, password storage, the forced-first-password-change flow, the user/role schema, and anything in the decided design that does not survive contact with this framework and this codebase.

---

## 2. Answer

Add `spring-boot-starter-security` (+ `spring-boot-starter-security-test`); Spring Boot 4.1.0 manages **Spring Security 7.1.0**. Use **two `SecurityFilterChain` beans**: an ordered first chain matching `/api/v1/operator/**`, `/api/v1/auth/**`, `/api/v1/admin/**` with `formLogin` (form-encoded, `loginProcessingUrl=/api/v1/auth/login`), `csrf().spa()`, `authorizeHttpRequests` with `hasRole`, an `HttpStatusEntryPoint(401)`, and a second catch-all chain that `permitAll`s and disables CSRF for the nine unauthenticated customer/mobile prefixes. The principal reaches `fru.operatorIdentity` via a `OncePerRequestFilter` added with `addFilterAfter(filter, AnonymousAuthenticationFilter.class)` — it re-reads `app.operator_user` per request (this is what makes "account disabled" mean anything) and sets the attribute only for `viewer`/`operator`, never for `admin`. Password storage: **BCrypt inside `DelegatingPasswordEncoder`**, strength measured and pinned explicitly rather than defaulted. Forced first change: grant the account **only** `ROLE_PASSWORD_CHANGE_REQUIRED` until it clears, so `AuthorizationFilter` — not controller code — denies everything but the change endpoint. Schema: one `app.operator_role` lookup table and one `app.operator_user` table with a **single `role` column**, which makes `admin`'s mutual exclusion structural rather than a constraint.

**Eight things in the decided design do not survive contact as stated.** The two sharpest: (a) "no idle timeout" is *not* achieved by leaving it alone — Boot defaults to 30 minutes, and the property value that means "never" under Tomcat means "expire immediately" under Spring Session; (b) `OperatorIdentity.operatorId` must be an immutable UUID, not a username, or the already-shipped database four-eyes rule silently stops matching after a rename, in an append-only table with no correction path. Full list in §3.5.

---

## 3. Evidence

### 3.0 Versions actually on this stack

| Fact | Marker |
|---|---|
| `spring-boot-dependencies` 4.1.0 sets `<spring-security.version>7.1.0</spring-security.version>`, `<spring-framework.version>7.0.8</spring-framework.version>`, `<spring-session.version>4.1.0</spring-session.version>`, `<tomcat.version>11.0.22</tomcat.version>` | [OBSERVED `C:\Users\DELL\.m2\repository\org\springframework\boot\spring-boot-dependencies\4.1.0\spring-boot-dependencies-4.1.0.pom` lines 197, 206, 207, 216] |
| Spring Boot 4.1 release notes confirm Spring Security 7.1.0 and Spring Session 4.1.0 | [DOC github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes] |
| `spring-boot-starter-security` and `spring-boot-starter-security-test` both exist at 4.1.0 in the BOM (the `-test` variant follows the same naming as the project's existing `spring-boot-starter-webmvc-test`) | [OBSERVED same pom, lines 2722–2731] |
| `spring-boot-starter-webmvc` pulls `spring-boot-starter-tomcat`; description "Starter for using Spring MVC and Tomcat" | [OBSERVED `spring-boot-starter-webmvc-4.1.0.pom` lines 14, 56–61] |
| **The published reference documentation currently serves 7.1.1, not 7.1.0.** Every `[DOC]` below is 7.1.1 unless stated. The delta 7.1.0→7.1.1 is a patch release; I did not diff it. | [OBSERVED docs.spring.io/spring-security/reference/whats-new.html reports "Spring Security 7.1.1"] |
| This project currently carries **no** Spring Security at all — `backend/pom.xml`'s nimbus-jose-jwt comment states it explicitly ("this project carries no Spring Security") | [OBSERVED `backend/pom.xml` lines 71–82] |
| The only Spring Security jars in the local `.m2` are **6.3.4**, from the FIB reference implementation (Boot 3.3.x). They are not what Boot 4.1 manages and must not be used as an API reference. | [OBSERVED `C:\Users\DELL\.m2\repository\org\springframework\security\`] |

### 3.1 The current configuration shape (item 1)

**What changed since 3.x / Spring Security 6.**

| Removed / changed | Replacement | Marker |
|---|---|---|
| `WebSecurityConfigurerAdapter` (deprecated 5.7) | `SecurityFilterChain` `@Bean` | [UNVERIFIED against a 7.0 doc page — I could not load the 7.0 migration servlet pages beyond the authorization one; reported consistently by secondary sources] |
| `authorizeRequests()`, `antMatchers()` | `authorizeHttpRequests()`, `requestMatchers()` | [DOC docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html (7.1.1) shows only the new forms] + [UNVERIFIED that the old ones are *removed* rather than deprecated in 7.0 — secondary sources say removed] |
| `AccessDecisionManager` / `AccessDecisionVoter` / the Access API | moved to a separate legacy module `org.springframework.security:spring-security-access`; use the Authorization API | [DOC docs.spring.io/spring-security/reference/migration/servlet/authorization.html] |
| `SecurityJackson2Modules` (Jackson 2 `ObjectMapper`) | `SecurityJacksonModules` (Jackson 3 `JsonMapper.Builder`) | [DOC docs.spring.io/spring-security/reference/migration/] |
| `DaoAuthenticationProvider.setUserDetailsService(...)` | constructor `DaoAuthenticationProvider(UserDetailsService)` — **`setUserDetailsService` is no longer a public method in 7.1** | [DOC javadoc 7.1.1, `org.springframework.security.authentication.dao.DaoAuthenticationProvider`] |
| `SecurityContextPersistenceFilter` | `SecurityContextHolderFilter`; `requireExplicitSave` is the default — Spring Security **reads** but does not automatically **save** the `SecurityContext` | [DOC servlet/authentication/session-management.html (7.1.1)] |
| `SessionManagementFilter` | no longer used by default since 6; `sessionAuthenticationErrorUrl`, `sessionAuthenticationFailureHandler`, `sessionAuthenticationStrategy` on `sessionManagement()` throw | [DOC same page] |
| Manual SPA CSRF plumbing | **`csrf(csrf -> csrf.spa())`, new in 7.0** — cookie repository + a request handler that resolves the plain (non-XOR) token, and handles the post-login/post-logout token refresh | [DOC javadoc 7.1.1 `CsrfConfigurer.spa()` carries `@since 7.0`; DOC servlet/exploits/csrf.html] |
| Request-matcher construction | `securityMatcher(s)` and `requestMatcher(s)` build matchers "using a `PathPatternRequestMatcher.Builder` bean, if available" | [DOC authorize-http-requests.html, quoted verbatim] |

**Concrete configuration.** New package `sd.gov.bank.fruserupdate.auth.config` (`config` is plumbing under the S3-01 rule, so no JaCoCo business-logic obligation attaches to it).

```java
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

  /** Chain 1: everything that requires an operator or admin identity. */
  @Bean
  @Order(1)
  SecurityFilterChain backOfficeChain(
      HttpSecurity http,
      OperatorUserRepository users,                 // domain port, jdbc adapter
      AuthAuditRecorder audit) throws Exception {

    http
      .securityMatcher("/api/v1/operator/**", "/api/v1/auth/**", "/api/v1/admin/**")
      .authorizeHttpRequests(auth -> auth
          .requestMatchers("/api/v1/auth/login", "/api/v1/auth/logout").permitAll()
          // The ONLY endpoint a must-change principal may reach (see §3.4).
          .requestMatchers(HttpMethod.POST, "/api/v1/auth/password")
              .hasAnyRole("PASSWORD_CHANGE_REQUIRED", "VIEWER", "ADMIN")
          .requestMatchers("/api/v1/auth/me").hasAnyRole("VIEWER", "ADMIN")
          .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
          .requestMatchers(HttpMethod.POST,
              "/api/v1/operator/profiles/*/approve",
              "/api/v1/operator/profiles/*/reject",
              "/api/v1/operator/profiles/*/manual-complete").hasRole("OPERATOR")
          .requestMatchers(HttpMethod.GET, "/api/v1/operator/profiles/export").hasRole("OPERATOR")
          .requestMatchers("/api/v1/operator/**").hasRole("VIEWER")
          .anyRequest().denyAll())
      .csrf(csrf -> csrf.spa())
      .sessionManagement(session -> session
          .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
          .sessionFixation(fix -> fix.changeSessionId()))
      .exceptionHandling(ex -> ex
          .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
      .formLogin(form -> form
          .loginProcessingUrl("/api/v1/auth/login")
          .successHandler(new SignInSuccessHandler(audit))   // 200 + {mustChangePassword, role}
          .failureHandler(new SignInFailureHandler()))       // 401, no discriminating detail
      .logout(logout -> logout
          .logoutUrl("/api/v1/auth/logout")
          .addLogoutHandler(new SignOutAuditLogoutHandler(audit))
          .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
          .deleteCookies("JSESSIONID"))
      .addFilterAfter(new OperatorIdentityFilter(users), AnonymousAuthenticationFilter.class);

    return http.build();
  }

  /** Chain 2: the nine unauthenticated customer/mobile prefixes. */
  @Bean
  @Order(2)
  SecurityFilterChain customerApiChain(HttpSecurity http) throws Exception {
    http
      .securityMatcher("/api/v1/**")
      .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
      .csrf(CsrfConfigurer::disable)          // no ambient credentials exist on these endpoints
      .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .requestCache(RequestCacheConfigurer::disable);
    return http.build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {         // §3.3
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  DaoAuthenticationProvider authenticationProvider(
      OperatorUserDetailsService uds, PasswordEncoder encoder) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(uds); // constructor-only in 7.1
    provider.setPasswordEncoder(encoder);
    return provider;                          // presence of this bean backs off Boot's default user
  }
}
```

Class/method provenance for the above: `HttpStatusEntryPoint` — package `org.springframework.security.web.authentication`, `@since 4.0`, "Useful for JavaScript clients which cannot use Basic authentication since the browser intercepts the response" [DOC javadoc 7.1.1]. `HttpStatusReturningLogoutSuccessHandler` — package `...web.authentication.logout`, `@since 4.0.2`, two constructors, default `HttpStatus.OK` [DOC javadoc 7.1.1]. `formLogin(form -> form.loginPage("/api/login").loginProcessingUrl("/api/login"))` with a base path is the documented pattern [DOC servlet/authentication/passwords/form.html, verbatim snippet]. `sessionFixation`/`sessionCreationPolicy` shapes [DOC session-management.html]. `hasRole("X")` is a shortcut for `hasAuthority("ROLE_X")` [DOC authorize-http-requests.html].

**Backing off Boot's defaults** — both required, both cheap to get wrong:

> "To completely switch off the default web application security configuration, including Actuator security, add a bean of type `SecurityFilterChain`. Doing so does not disable the `UserDetailsService` configuration." … "To also switch off the `UserDetailsService` configuration, add a bean of type `UserDetailsService`, `AuthenticationProvider`, or `AuthenticationManager`." [DOC docs.spring.io/spring-boot/reference/web/spring-security.html, Spring Boot 4.1.1]

The auto-configured default is a single in-memory user `user` with a random password logged at WARN, and "Form-based login or HTTP Basic security (depending on the `Accept` header) **for the entire application**" [DOC same page]. Boot also auto-configures a `DefaultAuthenticationEventPublisher` [DOC same page]; the Spring Security docs state the publisher bean is required for events to fire [DOC servlet/authentication/events.html]. Boot's `SecurityAutoConfiguration` is what supplies it, and it does **not** back off merely because you defined a `SecurityFilterChain` — [UNVERIFIED that it survives this exact configuration; assert it with a one-line context test].

**Three options for how the SPA signs in, evaluated against this project's constraints:**

1. **`formLogin` + form-encoded POST from the SPA** (recommended). Zero custom authentication code; `UsernamePasswordAuthenticationFilter` handles session creation, session-fixation `changeSessionId`, and CSRF token rotation via `CsrfAuthenticationStrategy` [DOC csrf.html]. Cost: the React client must send `application/x-www-form-urlencoded`, not JSON, on one endpoint.
2. **Custom `@RestController` login accepting JSON**, using `AuthenticationManager` plus an *explicit* `securityContextRepository.saveContext(context, request, response)` — the documented pattern, verbatim, in [DOC session-management.html]. Cost: you now own session fixation, CSRF rotation, and the explicit save; every one of those is a documented footgun since 6.0's `requireExplicitSave` default. Choose only if the SPA genuinely cannot post form-encoded.
3. **HTTP Basic + session.** Rejected: the browser intercepts the `WWW-Authenticate` challenge and shows a native dialog, which is why `HttpStatusEntryPoint` exists; and it puts credentials on every request rather than one.

**Recommendation flips to (2)** if the back office is served cross-origin from the API and the CORS pre-flight/form-encoding combination proves worse than owning the save — decide once AD-002d settles deployment topology.

### 3.2 How the principal reaches `fru.operatorIdentity` (item 2)

The existing seam is unchanged: `OperatorIdentityArgumentResolver.REQUEST_ATTRIBUTE = "fru.operatorIdentity"`, read from the request attribute, 401 if absent, registered in `OperatorWebConfiguration.addArgumentResolvers` [OBSERVED `backend/src/main/java/sd/gov/bank/fruserupdate/operator/web/OperatorIdentityArgumentResolver.java:43,56-64`; `.../operator/config/OperatorWebConfiguration.java:17-19`]. Its Javadoc already names exactly what AD-002e must add: "add the authenticating filter that calls `request.setAttribute(REQUEST_ATTRIBUTE, identity)`" [OBSERVED same file, lines 27–28].

```java
public final class OperatorIdentityFilter extends OncePerRequestFilter {

  private final OperatorUserRepository users;   // domain port; jdbc adapter reads app.operator_user

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && authentication.getPrincipal() instanceof OperatorUserDetails principal) {

      // Re-read per request. This — not the audited event — is what makes "account disabled"
      // take effect on a live session (see §3.5(f)). One PK lookup per operator request.
      users.findActiveById(principal.userId())
          .filter(u -> u.role() != OperatorRole.ADMIN)     // admin never gets an OperatorIdentity
          .ifPresent(u -> request.setAttribute(
              OperatorIdentityArgumentResolver.REQUEST_ATTRIBUTE,
              new OperatorIdentity(u.userId().toString(), u.accessLevel())));
    }
    chain.doFilter(request, response);
  }
}
```

**Placement.** `addFilterAfter(filter, AnonymousAuthenticationFilter.class)`. The reference documentation's placement table says a filter that needs authentication already to have happened goes after `AnonymousAuthenticationFilter`, listing the events already complete at that point as "SecurityContext + Exploit protection + Authentication" [DOC servlet/architecture.html]. The default chain order is `DisableEncodeUrlFilter, WebAsyncManagerIntegrationFilter, SecurityContextHolderFilter, HeaderWriterFilter, CsrfFilter, LogoutFilter, UsernamePasswordAuthenticationFilter, DefaultLoginPageGeneratingFilter, DefaultLogoutPageGeneratingFilter, BasicAuthenticationFilter, RequestCacheAwareFilter, SecurityContextHolderAwareRequestFilter, AnonymousAuthenticationFilter, ExceptionTranslationFilter, AuthorizationFilter` [DOC same page]. So the identity filter runs *before* `AuthorizationFilter` and therefore before authorization decisions — harmless, because it only reads and sets an attribute, and `AuthorizationFilter` still denies. Placing it *after* `AuthorizationFilter` would also work logically but is [UNVERIFIED] as a supported placement; do not do it without checking.

**Do not declare the filter as a top-level `@Bean`** without `FilterRegistrationBean#setEnabled(false)`, or Boot registers it with the servlet container as well and it runs twice [DOC servlet/architecture.html, "If Filter Needs to Be a Spring Bean"]. Constructing it inline inside the `@Bean SecurityFilterChain` method, as above, avoids the question entirely.

**Identity is still never client-supplied.** A request attribute cannot be set by an HTTP client; the resolver's Javadoc already makes that argument [OBSERVED resolver Javadoc lines 20–24]. Nothing about this changes.

### 3.3 Password storage (item 3)

**BCrypt, wrapped in `DelegatingPasswordEncoder`.** The 7.1.1 documentation still leads with `DelegatingPasswordEncoder` and states that when built via the factory method, "**bcrypt is the default encoding algorithm**"; storage format is `{id}encodedPassword`, e.g. `{bcrypt}$2a$10$...` [DOC features/authentication/password-storage.html]. Reasons this beats the alternatives *under our constraints*:

- **Argon2** is documented as an option, not a preference, and `Argon2PasswordEncoder` "requires BouncyCastle dependency" [DOC same page]. That is a new third-party crypto library entering a bank deliverable with its own licence and review cost, for a login path used by a handful of operators.
- **PBKDF2** is documented as "recommended when **FIPS certification is required**" [DOC same page]. No FIPS requirement is recorded anywhere in this project; OQ-001 (regulatory regime) is unanswered [OBSERVED PROJECT_PLAN.md Open questions]. **This is the flip condition**: if OQ-001 comes back with a FIPS or Bank-of-Sudan cryptographic-module requirement, switch to `Pbkdf2PasswordEncoder` — and because of the `{id}` prefix, existing `{bcrypt}` hashes keep verifying, so the switch is a configuration change plus opportunistic upgrade, not a migration.
- Everything else in `PasswordEncoder` land is "deprecated to indicate that they are no longer considered secure" [DOC, quoted].

**Strength.** `BCryptPasswordEncoder`'s default is 10, range 4–31 [DOC javadoc 7.1.1]. The reference text says "We recommend that the 'work factor' be tuned to take about one second to verify a password on your system" [DOC]. 10 is well under a second on 2026 hardware. **Measure on the actual deployment target and pin the strength explicitly** rather than accepting a default nobody chose; the `{bcrypt}` prefix means raising it later costs nothing, and `DaoAuthenticationProvider.setUserDetailsPasswordService(...)` [DOC javadoc 7.1.1] gives free re-encoding on next successful sign-in.

**Two traps.**
- bcrypt's 72-byte input limit. The 7.1.1 `BCryptPasswordEncoder` javadoc page I read **does not mention it at all** [OBSERVED — absence, not presence]. Whether 7.1 truncates silently or throws is [UNVERIFIED]. Cap password length at 72 **bytes** at the API boundary and test the boundary. Note the Arabic-first context: UTF-8 Arabic is 2 bytes per character, so a 40-character Arabic passphrase already exceeds 72 bytes.
- `DaoAuthenticationProvider.setCompromisedPasswordChecker(...)` exists [DOC javadoc 7.1.1]. **Do not wire `HaveIBeenPwnedRestApiPasswordChecker`.** It is an outbound internet call from the bank's backend on every password set, it is not in the decided design, and egress from a Sudan deployment is unknown (AD-002d open).
- The generated initial password is returned in an HTTP response body exactly once. It must not reach a log, an audit `payload_json`, or a session report — CLAUDE.md's no-secrets rule applies, and `audit.audit_event` is append-only with a 7-year retention, so a mistake there is permanent.

### 3.4 The forced-first-change flow (item 4)

**Where the check belongs: the authorization layer, expressed as authorities — not a controller check, not a `@ControllerAdvice`, not the `OperatorIdentityFilter`.**

`OperatorUserDetailsService` grants, for a user with `must_change_password = true`, **only** `ROLE_PASSWORD_CHANGE_REQUIRED` — none of `ROLE_VIEWER` / `ROLE_OPERATOR` / `ROLE_ADMIN`. Every rule in chain 1 except the password-change endpoint is expressed with `hasRole(...)`, so `AuthorizationFilter` — the last filter in the chain [DOC servlet/architecture.html] — denies with 403 before any controller method body or argument resolver runs. Role expansion for the normal case: `operator` grants `{ROLE_OPERATOR, ROLE_VIEWER}` so read rules need only `hasRole("VIEWER")`.

The `formLogin` success handler returns `{"mustChangePassword": true, "role": "operator"}` so the SPA routes to the change screen rather than discovering the state by collecting 403s.

**The thing that is always forgotten:** after the password changes, the session's `Authentication` still carries the restricted authority. You must rebuild it and save it, or the user is locked out of the application they just unlocked:

```java
// inside the change-password handler, after the row update commits
OperatorUserDetails refreshed = userDetailsService.loadUserByUsername(username);
Authentication reauth = UsernamePasswordAuthenticationToken.authenticated(
    refreshed, null, refreshed.getAuthorities());
SecurityContext context = securityContextHolderStrategy.createEmptyContext();
context.setAuthentication(reauth);
securityContextHolderStrategy.setContext(context);
securityContextRepository.saveContext(context, request, response);  // explicit save is required
request.changeSessionId();                                          // fixation hygiene
```

The explicit-save requirement is documented: since 6, `SecurityContextHolderFilter` "**only reads**" and "**Users must explicitly save**" [DOC session-management.html, with this exact `saveContext` snippet].

**Explicitly do not use `UserDetails.isCredentialsNonExpired() == false` for this.** `AbstractUserDetailsAuthenticationProvider`'s post-authentication check would then throw `CredentialsExpiredException`, mapping to `AuthenticationFailureCredentialsExpiredEvent` [DOC servlet/authentication/events.html] — i.e. authentication *fails*, no session is created, and the user has no authenticated context in which to change anything. The docs confirm pre-checks run "before validation of the credentials takes place" and post-checks after [DOC javadoc 7.1.1 `AbstractUserDetailsAuthenticationProvider.setPreAuthenticationChecks`]; which side `credentialsNonExpired` falls on is [UNVERIFIED from the pages I could load], but either side produces a failed authentication, which is the disqualifying property. Same reasoning excludes `isEnabled()==false` as a "must change" signal.

### 3.5 What does not work as stated on this stack (item 5)

**(a) "No idle timeout" is not the default, and the property that means *never* under one implementation means *immediately* under another.**
Boot's Tomcat factory: `getSessionTimeoutInMinutes()` returns `0` when the configured `Duration` is null, negative or zero, and passes it to `context.setSessionTimeout((int) sessionTimeout)` [OBSERVED vendor source, `spring-boot v4.1.0`, `module/spring-boot-tomcat/.../TomcatServletWebServerFactory.java`]. Tomcat then converts: `this.sessionTimeout = (timeout == 0) ? -1 : timeout;` with the comment "SRV.13.4 …: If the timeout is 0 or less, the container ensures the default behaviour of sessions is never to time out" [OBSERVED vendor source, `apache/tomcat 11.0.x`, `StandardContext.setSessionTimeout(int)`]. **So `server.servlet.session.timeout=0` gives you exactly what the decision asks for — but only on plain Tomcat sessions.** Leaving the property unset gives the 30-minute default, i.e. an idle timeout the design says must not exist. In Spring Session, `MapSession.isExpired()` returns false only for a **negative** interval; **zero expires immediately** [OBSERVED vendor source, `spring-session 4.1.0`, `MapSession`]. The two are opposite at the same value.
→ **Use plain Tomcat `HttpSession` with `server.servlet.session.timeout=0` and no Spring Session.** If Spring Session JDBC is ever adopted, re-verify this from scratch.

**(b) "Server-side sessions" in Tomcat are per-JVM and die on restart.** Every deployment signs every operator out. This is a consequence of (a)'s recommendation, not an argument against it — the alternative, Spring Session JDBC, needs `SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES` tables created by Flyway (because `fru_app` has no DDL rights, so `spring.session.jdbc.initialize-schema=never` [DOC docs.spring.io/spring-session/reference/configuration/jdbc.html] plus explicit grants including `DELETE`, which `fru_app` currently holds on almost nothing [OBSERVED V0010]), plus the (a) hazard, plus a serialization decision. Boot's `server.servlet.session.persistent=true` (Tomcat `configurePersistSession` [OBSERVED source]) is the third option and is worse: it writes live session data — including the operator principal — to a file in the work directory, creating a new at-rest PII surface outside AD-004/AD-002d's encryption story. **Recommend: in-memory, accept sign-out on deploy, record it in the component card.**

**(c) Adding the starter secures the entire application, including nine unauthenticated customer prefixes and ~549 tests.**
Boot's default gives "Form-based login or HTTP Basic security … for the entire application" [DOC Boot 4.1.1]. The unauthenticated prefixes are `/api/v1/account-check`, `/api/v1/contact-channels`, `/api/v1/otp`, `/api/v1/data-entry`, `/api/v1/identity-scan`, `/api/v1/liveness`, `/api/v1/signature`, `/api/v1/submission`, `/api/v1/reference` [OBSERVED grep of `@RequestMapping` across `backend/src/main/java`]. Without chain 2 above, every one 401s, and CSRF alone would 403 every mobile POST. Test blast radius: 549 integration / 426 standard tests [OBSERVED EXECUTION_PLAN.md S4-04 row], with 6 `@WebMvcTest` slices and ~20 `@SpringBootTest @AutoConfigureMockMvc` classes [OBSERVED grep of `backend/src/test/java`]. There is also a reported Boot 4 wrinkle: `@WebMvcTest` behaves differently once `spring-boot-starter-security-test` is on the classpath (tests that passed start returning 401), raised against Spring Framework and closed as "not planned / for: external-project" [OBSERVED github.com/spring-projects/spring-framework/issues/36423]. Budget for touching the test suite; this is not a two-file change.

**(d) `OperatorIdentity.operatorId` must be an immutable id, not a username.** The four-eyes rule is `AND NOT EXISTS (SELECT 1 FROM app.profile_status_history h WHERE h.profile_id = p.profile_id AND h.is_manual_completion AND h.actor_id = :operator_id)` [OBSERVED `V0009__app_status_history.sql:42-46`], against a table with `actor_id text` and no UPDATE/DELETE grant plus an immutability trigger [OBSERVED V0009:11, V0010:47,61-67]. If `operatorId` is a username and an admin renames the account (the decided design gives admins role and account management), the operator who manually completed a profile can then approve it, and the historical rows cannot be corrected. `OperatorIdentity`'s own Javadoc already flags that its shape "becomes part of two persisted contracts once real values start flowing" [OBSERVED `OperatorIdentity.java:12-15`]. → **`operatorId` = `app.operator_user.user_id` (UUID) rendered as text. Decide before the first real row is written.** If usernames are chosen to be immutable instead, that is a schema constraint that must be written down and enforced, not an assumption.

**(e) An authenticated `admin` hitting an operator endpoint gets 401, not 403, unless authorization stops it first.** The resolver throws `ResponseStatusException(UNAUTHORIZED)` for *any* missing attribute [OBSERVED resolver:60-64], and the filter above deliberately does not set one for admins because `OperatorAccessLevel` has only `VIEWER` and `OPERATOR` [OBSERVED `OperatorAccessLevel.java`]. → the `authorizeHttpRequests` rules in §3.1 must deny `/api/v1/operator/**` to `ROLE_ADMIN`, so `AuthorizationFilter` returns 403 before the resolver runs. **Do not add an `ADMIN` constant to `OperatorAccessLevel`** — the design says admin "does nothing else", and a third constant would leak into `ProfileListFilter`, the export path and the four-eyes reasoning.

**(f) "Account disabled" is in the audited-event list but has no runtime effect unless you add one.** `SecurityContextHolderFilter` restores the principal from the session; it does not reload `UserDetails`. With no idle timeout (deliberate) and no lockout (deliberate), a disabled account's live session works indefinitely. The `OperatorIdentityFilter` in §3.2 re-reads `app.operator_user` per request precisely to close this. This is implementing the decided design, not amending it — but it is the difference between the event meaning something and being decoration. The same re-read closes the equivalent hole for a role change.

**(g) `audit.audit_event.actor_kind` has no `admin` value.** `CHECK (actor_kind IN ('customer', 'operator', 'system'))` [OBSERVED `V0002__audit_tables.sql:46`]; the same CHECK exists on `app.profile_status_history` [OBSERVED V0009:10]. Changing it means `ALTER TABLE` in schema `audit`, which is DDL and therefore needs the R-035 `SET LOCAL fru.migration_in_progress = 'on'` guard [OBSERVED persistence.md, "A deployment-ordering fact confirmed live"]. → **use `actor_kind = 'operator'` for admin actions and carry `"actorRole":"admin"` in `payload_json`.** Cheaper, reversible, and does not touch a constraint on an append-only table.

**(h) A failed sign-in for an unknown username has no operator id — and must not create an audit chain.** `audit.ensure_operator_chain(text)` is `SECURITY DEFINER` with `EXECUTE` granted to `fru_app` [OBSERVED `V0047__audit_operator_chain_creation.sql:31-41`], so calling it with an attacker-supplied username would insert unbounded rows into `audit.audit_chain` from unauthenticated input, in a schema that is append-only and therefore uncleanable, on an endpoint with no rate limiting (BL-007 has no per-install identifier to key on [OBSERVED BACKLOG.md:11]). → **Seed one `system` chain, `subject_id = 'auth'`, in a new migration.** Precedent and exact shape: `V0034__audit_system_account_check_chain.sql`, which is DML only and therefore needs no R-035 flag [OBSERVED V0034:21-24 and persistence.md].

**(i) The submitted username lands permanently in an append-only, hash-chained store.** A user who types their password into the username field writes it into `audit.audit_event.payload_json`, hashed into the chain, 7-year retention, no correction path (`app.reject_history_mutation`-equivalent triggers plus `fru_app` holding INSERT+SELECT only [OBSERVED persistence.md "Append-only — the four mechanisms"]). This is not hypothetical and it is not reversible. **Decide the payload shape before the first failed sign-in is recorded**; options are (i) record the username verbatim, (ii) record only `accountExists` plus the `user_id` when it matched, (iii) record a truncated/normalised form. I am not choosing for you — it is a data-protection call, adjacent to OQ-001.

**(j) No lockout + no rate limiting + one serialised audit chain.** `audit.chain_append()` takes `SELECT … FOR UPDATE` on the chain row (R-038) [OBSERVED persistence.md]. Every failed sign-in queues on the single `system`/`auth` row — which is simultaneously a natural throttle and a single point of contention, and means an attacker can drive unbounded row growth into `audit.audit_event` with no lockout to stop them. Both deferrals are deliberate and I am not reopening them; the *combination*'s consequence is new and belongs in RISKS.md.

**(k) Sign-out is auditable only when it is called.** A closed browser produces no event, and with no idle timeout the session survives until the JVM restarts. "Every authentication event is written to the audit trail" is therefore satisfiable for sign-in, failed sign-in, explicit sign-out, password change, account created, role changed and account disabled — but the audit trail will show sessions that begin and never end. Say so in the card rather than letting an auditor discover it.

**(l) Where to write the sign-in-success audit event.** The `AuthenticationSuccessEvent` listener runs synchronously inside `ProviderManager.authenticate(...)`; what a thrown exception from an `@EventListener` does there is [UNVERIFIED]. Since this codebase's standing rule is "an unaudited action is worse than a failed one" [OBSERVED `AuditEventWriter` Javadoc], write the **success** audit in the `AuthenticationSuccessHandler`, where you control ordering and failure, and use `@EventListener(AbstractAuthenticationFailureEvent.class)` only for failures. Note that `UsernameNotFoundException` and `BadCredentialsException` both map to `AuthenticationFailureBadCredentialsEvent` [DOC events.html], so the event type alone cannot tell you whether the account existed — which is correct anti-enumeration behaviour and is why (i) matters.

**(m) CSRF/`csrf().spa()` assumes cookie-based, same-site delivery.** If the back office is served from a different origin than the API, `SameSite`, `Secure` (requires TLS) and CORS pre-flight all engage, and none of that is settled — AD-002d is open. PROJECT_PLAN records AD-002e as depending on AD-002d [OBSERVED PROJECT_PLAN.md, Open architecture decisions]. **That dependency is narrower than stated**: only the cookie flags, the cross-origin question and encryption-at-rest depend on hosting. Everything else in this report can be built now.

### 3.6 The user and role schema (item 6)

Conventions this follows, all [OBSERVED]: closed code sets get a lookup table, never a PostgreSQL `ENUM` (V0005 header, persistence.md "Do NOT use"); `uuid PRIMARY KEY DEFAULT gen_random_uuid()` and `timestamptz NOT NULL DEFAULT clock_timestamp()` (V0005); per-table `GRANT` to `fru_app` with a comment justifying each verb and no `DELETE` unless earned (V0010); `REVOKE ALL ON SCHEMA app FROM PUBLIC` and the default-privileges revoke are already in place from V0010, so no repeat is needed.

Migration number: the working tree already holds uncommitted **V0053–V0056** (S5-06's concurrent artifact-storage work), so this lands at **V0057** or later, whenever S5-06 lands.

```sql
-- app schema. Lookup table, not a PG ENUM — same reasoning as app.status_code (V0005).
CREATE TABLE app.operator_role (
  code           text PRIMARY KEY,
  label_ar       text NOT NULL,
  label_en       text NOT NULL,
  -- true for the two roles that may reach /api/v1/operator/**; false for 'admin', which
  -- manages accounts and does nothing else (docs/journeys/operator.md, AD-002e).
  is_back_office boolean NOT NULL
);

INSERT INTO app.operator_role (code, label_ar, label_en, is_back_office) VALUES
  ('viewer',   'مطّلع',       'Viewer',        true),
  ('operator', 'مشغّل',       'Operator',      true),
  ('admin',    'مدير النظام', 'Administrator', false);

CREATE TABLE app.operator_user (
  user_id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  -- ASCII, lowercase, stored as typed. Deliberately NOT a generated lower()/ar_fold() column:
  -- docs/components/persistence.md forbids indexing a user-text column under a non-C collation,
  -- and a bank employee identifier has no need of Arabic folding.
  username             text NOT NULL,
  display_name         text NOT NULL,               -- Arabic display name, never a lookup key

  -- EXACTLY ONE role per account. This is what makes 'admin' mutually exclusive with
  -- 'viewer'/'operator' — the forbidden combination is not merely rejected, it is inexpressible.
  -- A role-SET table would need a trigger instead: PostgreSQL does not support a CHECK
  -- referencing other rows [DOC postgresql.org/docs/18/ddl-constraints.html] — the same finding
  -- AD-002f/V0049 already relied on.
  role                 text NOT NULL REFERENCES app.operator_role(code),

  -- DelegatingPasswordEncoder form, e.g. '{bcrypt}$2a$12$...'. Never a bare hash: the {id}
  -- prefix is what lets the algorithm or work factor change later without a data migration.
  password_hash        text NOT NULL,
  must_change_password boolean NOT NULL DEFAULT true,
  password_changed_at  timestamptz,

  is_enabled           boolean NOT NULL DEFAULT true,
  disabled_at          timestamptz,                 -- when it was LAST disabled; not a state flag

  created_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
  created_by           uuid REFERENCES app.operator_user(user_id),  -- NULL only for the bootstrap admin
  last_sign_in_at      timestamptz,
  row_version          bigint NOT NULL DEFAULT 1,

  CONSTRAINT operator_user_username_unique UNIQUE (username),
  CONSTRAINT operator_user_username_shape  CHECK (username ~ '^[a-z0-9][a-z0-9._-]{2,63}$')
);

-- operator_role: a fixed, migration-maintained code set. Read-only to the application.
GRANT SELECT ON app.operator_role TO fru_app;

-- operator_user: created and updated by an admin; NEVER deleted. app.profile_status_history
-- .actor_id and audit.audit_event.actor_id both reference user_id as plain text forever, and
-- both tables are append-only — a deleted account would orphan the four-eyes predicate
-- (V0009) and every audit attribution. Disable, do not delete. Same reasoning as app.profile.
GRANT SELECT, INSERT, UPDATE ON app.operator_user TO fru_app;
```

Notes on choices you might otherwise re-derive:
- No `email` column: nothing in the decided design sends mail to operators (no self-service reset). Adding it "for later" adds PII with no consumer.
- No `failed_attempt_count` / `locked_until`: lockout is deferred deliberately. Adding the columns now invites someone to half-implement it.
- `disabled_at` is not tied to `is_enabled` by a CHECK, because re-enabling an account would then have to null a historical timestamp. The audit trail is the record of when; this column is convenience.
- The four-eyes rule is untouched. Nothing here is enforced in the security layer.

**Bootstrapping the first admin** (no self-registration, no admin exists). Three options against this repo's own precedents:
1. Insert a literal hash in the migration — **rejected**, a credential in the repository, even a placeholder one.
2. A Flyway placeholder `${bootstrap_admin_password_hash}` sourced from an environment variable, exactly as `${fru_app_password}` works today [OBSERVED `V0001:8`, `application.properties:19`]. Works, but the operator must produce a bcrypt hash out-of-band, which is a step nobody will get right unaided.
3. **Recommended: a Spring-profile-gated `CommandLineRunner`** that generates a `SecureRandom` password, creates the admin with `must_change_password = true`, prints the password once to stdout, and exits — the exact precedent set by `ReferenceDocumentPublicationRunner` behind the `publish-reference-documents` profile at S4-03 [OBSERVED EXECUTION_PLAN.md S4-03 row]. It matches the decided flow ("the system generates an initial password, shown once"), keeps nothing in the repo, and needs no admin endpoint. It inherits R-028/R-033's known weakness — a by-hand step that can be silently skipped — which here fails loudly the first time anyone tries to sign in, so the weakness is benign.

---

## 4. What I could not determine

- **Whether `authorizeRequests()`, `antMatchers()`, `WebSecurityConfigurerAdapter` and `.and()` are *removed* in 7.0 versus merely deprecated.** The 7.0 migration pages under `docs.spring.io/spring-security/reference/migration/servlet/` returned only the authorization page's Access-API content to me; the rest I could not load. Secondary sources say removed. Practically irrelevant — the 7.1.1 documentation uses only the new forms — but marked so nobody cites me for it.
- **Whether `formLogin`'s `UsernamePasswordAuthenticationFilter` saves the `SecurityContext` to the session automatically under 7.1's `requireExplicitSave` default.** The documentation states the explicit-save requirement and demonstrates it for a *custom controller*; it does not state what the built-in filter does. One integration test settles it: POST login, then GET a protected endpoint with the returned `JSESSIONID`.
- **Whether `SecurityAutoConfiguration`'s `DefaultAuthenticationEventPublisher` survives when a `SecurityFilterChain` and an `AuthenticationProvider` bean are both defined.** Boot documents that defining those beans backs off `SpringBootWebSecurityConfiguration` and `UserDetailsServiceAutoConfiguration` respectively; it says nothing about the event publisher. Assert it with a context test before relying on failure events for audit.
- **`BCryptPasswordEncoder`'s behaviour above 72 bytes in 7.1** — truncate, throw, or something else. Not in the javadoc page I read.
- **The exact behaviour of an exception thrown from an `@EventListener` on `AuthenticationSuccessEvent`** inside `ProviderManager.authenticate`.
- **Whether `addFilterAfter(x, AuthorizationFilter.class)` is a supported placement.** The documented rule-of-thumb table stops at `AnonymousAuthenticationFilter`. I recommended the documented placement instead, so this does not block anything.
- **The 7.1.0 → 7.1.1 delta.** Boot 4.1.0 manages 7.1.0; the reference documentation serves 7.1.1. I did not diff them.
- **Whether the back office will be same-origin with the backend.** Blocked on AD-002d. It decides the cookie `SameSite`/`Secure`/CORS configuration and nothing else in this report.

---

## 5. Risks

| # | If this is wrong | What breaks | Cost to reverse |
|---|---|---|---|
| R1 | `operatorId` ships as a username rather than a UUID | The four-eyes rule silently stops matching after any rename — the single strongest control in the system, and the one operator.md calls "the strongest control bypass the system permits" | **Effectively irreversible.** `app.profile_status_history` and `audit.audit_event` are append-only; historical `actor_id` values cannot be rewritten. A backfill would require an identity-mapping table and a rewritten four-eyes predicate, and the audit chain's hashes cover the old values regardless. **Decide before the first real row.** |
| R2 | `server.servlet.session.timeout` is left unset | Operators are silently signed out after 30 minutes — the exact behaviour the product owner deliberately excluded, presenting as a flaky application rather than a configuration error | One property. Cheap — but only if someone notices. Add an assertion test on the effective session timeout. |
| R3 | The security starter is added without chain 2 | Every mobile endpoint 401s and every POST 403s on CSRF; ~549 tests fail at once | Cheap to fix, expensive to diagnose mid-session. Add chain 2 in the same commit as the dependency. |
| R4 | The `OperatorIdentityFilter` reads the role from the session principal instead of re-reading the row | Disabling an account or demoting a role has no effect on a live session, and with no idle timeout that session never ends. The `account_disabled` audit event records something that did not happen. | Cheap in code; expensive in trust — an audit trail that records an ineffective control is worse than one that records nothing. |
| R5 | Failed sign-ins are chained per submitted username | Unauthenticated input inserts unbounded rows into `audit.audit_chain`, in a schema with no DELETE path | Irreversible. Use the pre-seeded `system`/`auth` chain. |
| R6 | The attempted username is recorded verbatim | A password typed into the username field is permanently hash-chained into a 7-year-retention store | Irreversible. Decide the payload shape first. |
| R7 | Argon2 or PBKDF2 is chosen now on general "stronger" grounds | BouncyCastle enters the dependency tree, or a work factor is picked without measurement | Low: the `{id}` prefix makes the encoder swappable. This is the one decision here that is genuinely cheap to reverse — which is why BCrypt-now is safe. |
| R8 | AD-002d settles on a cross-origin back office | `csrf().spa()`'s cookie assumptions and `Secure`/`SameSite` need rework; possibly the login shape moves to option (2) | Moderate, confined to one configuration class and the SPA's login call. |

---

## 6. Card updates

### 6a. New card — `docs/components/backoffice-auth.md` (draft, full)

```markdown
# Component: Back-office authentication (AD-002e)

Status: researched, not built · Last verified: 2026-09-02
Sources: docs/sessions/2026-09-02-research-ad-002e-auth.md

## Decision
Decided by the product owner, not by research: three roles (viewer, operator, admin);
admin-created accounts, no self-registration; system-generated initial password shown once,
forced change on first sign-in; no self-service reset; server-side sessions; no idle timeout
and no lockout in v1; every authentication event audited; admin mutually exclusive with
viewer/operator on one account.

## Versions (managed by spring-boot-dependencies 4.1.0)
| Component | Version | Marker |
|---|---|---|
| Spring Security | 7.1.0 | [OBSERVED spring-boot-dependencies-4.1.0.pom:206] |
| Spring Framework | 7.0.8 | [OBSERVED same:197] |
| Tomcat | 11.0.22 | [OBSERVED same:216] |
| Spring Session | 4.1.0 (managed, NOT used) | [OBSERVED same:207] |
| Reference docs published at | 7.1.1 (one patch ahead of the managed version) | [OBSERVED docs.spring.io] |

Dependencies to add: `spring-boot-starter-security`, `spring-boot-starter-security-test` (test).

## Configuration shape — what changed since 3.x
- Lambda DSL only; `authorizeHttpRequests` / `requestMatchers`. [DOC 7.1.1]
- `DaoAuthenticationProvider(UserDetailsService)` — constructor only; no `setUserDetailsService`. [DOC javadoc 7.1.1]
- `csrf(csrf -> csrf.spa())` — new in 7.0, the supported SPA answer (cookie repository +
  plain-token handler + post-login/post-logout token refresh). [DOC javadoc @since 7.0]
- `SecurityContextHolderFilter` reads only; explicit `saveContext` required for any custom
  login or re-authentication path. [DOC session-management.html 7.1.1]
- `SessionManagementFilter` unused by default; three `sessionManagement()` methods throw. [DOC]
- `AccessDecisionManager`/`AccessDecisionVoter` moved to `spring-security-access`. [DOC migration]
- `SecurityJackson2Modules` → `SecurityJacksonModules` (Jackson 3). [DOC migration]
- Default filter order and the `addFilterAfter(x, AnonymousAuthenticationFilter.class)`
  placement rule for a filter needing authentication already done. [DOC servlet/architecture.html]

## Two chains, not one
Chain 1 `@Order(1)`: `/api/v1/operator/**`, `/api/v1/auth/**`, `/api/v1/admin/**` —
formLogin, `csrf().spa()`, `HttpStatusEntryPoint(401)`, `HttpStatusReturningLogoutSuccessHandler`.
Chain 2 `@Order(2)`: `/api/v1/**` — permitAll, CSRF disabled, STATELESS. Covers the nine
unauthenticated customer prefixes (account-check, contact-channels, otp, data-entry,
identity-scan, liveness, signature, submission, reference). Without chain 2 the starter
secures the whole application by default. [DOC Boot 4.1.1]

## Reaching `fru.operatorIdentity`
`OperatorIdentityFilter extends OncePerRequestFilter`, added
`addFilterAfter(..., AnonymousAuthenticationFilter.class)`. It re-reads `app.operator_user`
per request — that re-read, not the audit event, is what makes "account disabled" and
"role changed" take effect on a live session. It sets nothing for `admin` or anonymous.
`OperatorIdentityArgumentResolver` and `OperatorWebConfiguration` are unchanged.
**Identity is never read from a client-supplied header.**

**`OperatorIdentity.operatorId` is `app.operator_user.user_id` (UUID) as text, never a username.**
V0009's four-eyes predicate matches `h.actor_id = :operator_id` in an append-only table; a
renameable identifier would silently break it with no correction path. [OBSERVED V0009:42-46]

## Password storage
BCrypt inside `DelegatingPasswordEncoder`
(`PasswordEncoderFactories.createDelegatingPasswordEncoder()`); stored as `{bcrypt}$2a$…`.
Default strength 10; the docs say tune to ~1s — measure and pin explicitly. [DOC 7.1.1]
Argon2 needs BouncyCastle; PBKDF2 only if FIPS is required — that is the flip condition, and
it depends on OQ-001. [DOC password-storage.html]
Do NOT wire `CompromisedPasswordChecker`/HaveIBeenPwned (outbound internet call).
Cap password input at 72 bytes at the boundary — bcrypt's limit; Arabic is 2 bytes/char.
[UNVERIFIED: 7.1's behaviour above 72 bytes]

## Forced first change
`must_change_password = true` grants ONLY `ROLE_PASSWORD_CHANGE_REQUIRED`; every other rule
uses `hasRole(...)`, so `AuthorizationFilter` denies before any controller or argument
resolver runs. After the change, rebuild the `Authentication` with full authorities, save it
via `SecurityContextRepository`, and `changeSessionId()` — otherwise the user stays locked out.
Do NOT model this as `credentialsNonExpired = false`: that fails authentication outright,
leaving no session in which to change anything.

## No idle timeout — how it is actually achieved
`server.servlet.session.timeout=0`. Boot maps null/zero/negative to `0`
[OBSERVED spring-boot v4.1.0 TomcatServletWebServerFactory]; Tomcat converts `0 → -1`, "never
time out", citing SRV.13.4 [OBSERVED apache/tomcat 11.0.x StandardContext].
**Leaving the property unset gives the 30-minute default, not "no timeout".**
**Under Spring Session the same zero value means expire-immediately**
[OBSERVED spring-session 4.1.0 MapSession] — so do not adopt Spring Session without
re-verifying this. Consequence accepted: in-memory Tomcat sessions die on every restart, so
every operator is signed out on deploy. `server.servlet.session.persistent` is NOT used —
it would write live session data to disk, new at-rest PII outside AD-004/AD-002d.

## Audit integration
- Sign-in / sign-out / password change → the operator's own chain via
  `audit.ensure_operator_chain(user_id)` (V0047), `actor_kind='operator'`, `actor_id=user_id`.
- Failed sign-in → ONE pre-seeded `system` chain, `subject_id='auth'` (new migration, DML
  only, no R-035 flag — V0034 is the worked example). **Never call
  `ensure_operator_chain(submittedUsername)`**: `fru_app` can create operator chains, so
  unauthenticated input would insert unbounded, undeletable rows.
- Account created / role changed / account disabled → the TARGET user's chain, `actor_id` =
  the acting admin.
- `actor_kind` has no `admin` value (V0002:46 CHECK). Use `'operator'` + `"actorRole":"admin"`
  in the payload; do not ALTER the CHECK (DDL in schema audit, R-035).
- `payload_json` is flat RFC 8785 canonical JSON via `audit.domain.CanonicalJson` —
  String/integral/Boolean/null only. Never the password, old or new, hashed or not.
- Write the sign-in SUCCESS event in the `AuthenticationSuccessHandler`, not an
  `@EventListener`, so ordering and failure are controlled. Use
  `@EventListener(AbstractAuthenticationFailureEvent.class)` for failures only.
- `UsernameNotFoundException` and `BadCredentialsException` both publish
  `AuthenticationFailureBadCredentialsEvent` [DOC events.html] — the event cannot tell you
  whether the account existed.

## Known gaps in the decided design, stated rather than papered over
- Sign-out is audited only when explicitly called. A closed browser produces no event, and
  with no idle timeout the session persists until the JVM restarts.
- No lockout + no rate limiting (BL-007) + one serialised audit chain (R-038) = unbounded
  audit growth from failed sign-ins, with the chain-row lock as the only throttle.
- The attempted username is recorded permanently and immutably. A password typed into the
  username field is unrecoverable from the audit trail. Payload shape must be decided first.
- Admin can create an account and use it; the constraint the design relies on is that this
  shows up as a separate identity in the audit trail. Nothing here strengthens or weakens it.

## Open
- [ ] Does `formLogin` save the SecurityContext to the session automatically under 7.1's
      `requireExplicitSave` default? One integration test settles it. [UNVERIFIED]
- [ ] Does Boot's `DefaultAuthenticationEventPublisher` survive our SecurityFilterChain +
      AuthenticationProvider beans? [UNVERIFIED]
- [ ] BCrypt behaviour above 72 bytes in 7.1. [UNVERIFIED]
- [ ] Cookie `SameSite`/`Secure` and CORS — blocked on AD-002d (same-origin or not).
- [ ] Bootstrap-admin runner is a by-hand step that can be skipped (R-028 shape) — benign
      here, it fails loudly at first sign-in.
```

### 6b. `docs/components/persistence.md` — lines to add

Add to the versions/tables narrative, after the "Lock ordering" section:

```markdown
## Operator accounts (AD-002e)

`app.operator_role` (three rows: viewer/operator/admin) and `app.operator_user`. One `role`
column, not a role set — that is what makes `admin` mutually exclusive with `viewer`/`operator`
*inexpressible* rather than merely rejected; a role-set table would need a trigger, since
PostgreSQL does not support a CHECK referencing other rows
[DOC postgresql.org/docs/18/ddl-constraints.html], the same finding V0049 already relies on.

`fru_app` gets `SELECT` on `operator_role` and `SELECT, INSERT, UPDATE` — **no DELETE** — on
`operator_user`. An account is disabled, never deleted: `app.profile_status_history.actor_id`
and `audit.audit_event.actor_id` reference `user_id` as plain text forever and both tables are
append-only, so a deletion would orphan the four-eyes predicate and every audit attribution.
Same reasoning as `app.profile`.

**`actor_id` is `app.operator_user.user_id` (UUID text), never a username.** V0009's four-eyes
`UPDATE` matches `h.actor_id = :operator_id`; a renameable identifier would silently break the
control, in a table that cannot be corrected. [OBSERVED V0009:42-46]

`username` is ASCII-lowercase with a shape CHECK and a plain UNIQUE — deliberately NOT a
generated `lower()`/`ar_fold()` column, per this document's own rule against indexing a
user-text column under a non-C collation.
```

Add to "Open verification items":

```markdown
- [ ] `audit.audit_event.actor_kind` has no `'admin'` value (V0002:46). AD-002e records admin
      actions as `actor_kind='operator'` with `"actorRole":"admin"` in the payload rather than
      altering a CHECK on an append-only table (DDL in schema audit — R-035). Revisit only if
      an admin-specific query becomes necessary. [OBSERVED 2026-09-02]
```

### 6c. `docs/journeys/operator.md` — line 32–33

Replace:

```
`[OPEN: how operator accounts are provisioned and authenticated — depends on AD-002
back-office auth]`
```

with a pointer to the new card and the note that the access model gains a third role
(`admin`) which is **not** a back-office access level — it manages accounts and reaches no
operator endpoint. `OperatorAccessLevel` stays two-valued.

### 6d. PROJECT_PLAN.md

- AD-002e's "depends on AD-002d" is **narrower than stated**: only the session-cookie flags,
  the cross-origin question, and encryption at rest depend on hosting. The rest is buildable now.
- OQ-013 ("how operator accounts are provisioned and authenticated") is now answered by the
  product owner's decision plus this report.
- OQ-001's answer is the flip condition for BCrypt → PBKDF2.

### 6e. RISKS.md — two new rows

- **No lockout + no rate limiting + one serialised `system`/`auth` audit chain** = unbounded,
  undeletable audit growth from failed sign-ins with no throttle but the chain row lock.
  Both deferrals are deliberate; the combination's consequence is not recorded anywhere.
- **The submitted username is recorded permanently in an append-only, hash-chained store**;
  a password typed into the username field is unrecoverable.

---

## 7. Noticed in passing

- Spring Security 7.1 adds `AuthorizationManagerFactories.multiFactor()` with `when`/`withWhen`
  conditions and `@EnableMultiFactorAuthentication` [DOC whats-new.html]. The same primitive
  ("required factors" as authorities) would express the forced-password-change gate, but MFA is
  explicitly out of scope and the plain authority approach in §3.4 is simpler. Recorded only so
  nobody rediscovers it as a novelty.
- The working tree carries four uncommitted migrations (V0053–V0056, artifact body/derivatives,
  a read function, an abandoned-artifact purge, and dropping `face_reference_image`) and fifteen
  modified Java files [OBSERVED `git status`] — this is S5-06's artifact-storage work, running
  concurrently in a different session. AD-002e's migration number depends on those landing first.
- `docs.spring.io` publishes no `llms.txt` and no markdown variants that I could find (both
  `/spring-security/reference/llms.txt` and the 7.0 migration paths 404). The Antora HTML pages
  and the javadoc under `/spring-security/reference/api/java/...` are the machine-readable
  surface; the javadoc `@since` tags were the most reliable evidence I found for "does this exist
  in this version" questions. Worth recording for future SDK research.
- `backend/pom.xml`'s nimbus-jose-jwt comment says the dependency was added directly "since this
  project carries no Spring Security". Once `spring-boot-starter-security` lands, that rationale
  is stale — the version is still explicitly pinned at 9.37.3, which is fine, but the comment
  will mislead.

**Sources:** [Spring Security reference — What's New (7.1.1)](https://docs.spring.io/spring-security/reference/whats-new.html) · [Form login](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/form.html) · [Session management](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html) · [Password storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html) · [Authentication events](https://docs.spring.io/spring-security/reference/servlet/authentication/events.html) · [Architecture / filter order](https://docs.spring.io/spring-security/reference/servlet/architecture.html) · [CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html) · [authorizeHttpRequests](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html) · [Migrating to 7.0](https://docs.spring.io/spring-security/reference/migration/) · [7.0 servlet authorization migration](https://docs.spring.io/spring-security/reference/migration/servlet/authorization.html) · [CsrfConfigurer javadoc](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/config/annotation/web/configurers/CsrfConfigurer.html) · [DaoAuthenticationProvider javadoc](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/authentication/dao/DaoAuthenticationProvider.html) · [BCryptPasswordEncoder javadoc](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/crypto/bcrypt/BCryptPasswordEncoder.html) · [HttpStatusEntryPoint javadoc](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/web/authentication/HttpStatusEntryPoint.html) · [HttpStatusReturningLogoutSuccessHandler javadoc](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/web/authentication/logout/HttpStatusReturningLogoutSuccessHandler.html) · [Spring Boot 4.1 security](https://docs.spring.io/spring-boot/reference/web/spring-security.html) · [Spring Boot 4.1 release notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes) · [Spring Session JDBC](https://docs.spring.io/spring-session/reference/configuration/jdbc.html) · [@WebMvcTest security in Boot 4](https://github.com/spring-projects/spring-framework/issues/36423)

---

## 8. Commit/push proof

Note: this session deliberately staged only this report and the PROJECT_PLAN.md AD-002e
line, not `git add -A` — the working tree also held S5-06's concurrent, unrelated
artifact-storage work (migrations V0053–V0056 and modified backend files). That work was
committed and pushed by the S5-06 session itself (`3e2bfba`, `d1455b4`) before this
session's push landed, which is why `git status` below shows a clean tree rather than
S5-06's changes still pending — they were never included in this session's commit.

```
$ git log --oneline -5
56375e5 docs: research AD-002e (back-office auth on Spring Boot 4.1)
d1455b4 docs: record S5-06 commit/push proof in session report
3e2bfba feat: S5-06 — close AD-004, artifacts stored in the database
36ce30c docs: append commit/push proof to uqudo open-items session report
2617848 docs: close R-024 (Uqudo faceImageId) and unblock SDK-Arabic item from S1-02

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```
