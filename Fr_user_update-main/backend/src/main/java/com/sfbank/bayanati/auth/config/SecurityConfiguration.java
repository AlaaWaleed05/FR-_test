package com.sfbank.bayanati.auth.config;

import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import com.sfbank.bayanati.auth.service.AuthAuditRecorder;
import com.sfbank.bayanati.auth.web.OperatorIdentityFilter;
import com.sfbank.bayanati.auth.web.SignInFailureHandler;
import com.sfbank.bayanati.auth.web.SignInSuccessHandler;
import com.sfbank.bayanati.auth.web.SignOutAuditLogoutHandler;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * AD-002e. TWO {@link SecurityFilterChain} beans, not one — adding {@code
 * spring-boot-starter-security} alone secures the ENTIRE application by default, including the nine
 * unauthenticated customer/mobile prefixes chain 2 below exists to keep open. Spring Security
 * dispatches each request to the first chain whose {@code securityMatcher} matches, so chain 1's
 * specific prefixes are claimed first and chain 2 catches everything else under {@code /api/v1/**}.
 * If a future endpoint is ever added OUTSIDE {@code /api/v1/**}, it falls through BOTH chains
 * unmatched, and {@code FilterChainProxy} skips security filters entirely for it — silently
 * unprotected, not 401'd. Keep every new controller under {@code /api/v1/**}.
 *
 * <p><strong>Session idle timeout: deliberately left unset.</strong> The task's product-owner
 * decision accepts Boot's Tomcat default (30 minutes) rather than the earlier "no timeout" plan
 * (which would have needed {@code server.servlet.session.timeout=0}, a value that means "never" on
 * Tomcat but "expire immediately" under Spring Session — AD-002e research report §3.5(a)). Do NOT
 * set {@code server.servlet.session.timeout}, and do not adopt Spring Session without re-verifying
 * that hazard from scratch.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

  /**
   * BL-066. Whether the operator's cookies must carry {@code Secure}.
   *
   * <p>DEFAULT FALSE, AND THAT IS NOT LAZINESS. A {@code Secure} cookie is silently discarded by
   * the browser over plain HTTP, so switching this on unconditionally would break local development
   * and every test that drives a real servlet container, in the least obvious way available: the
   * sign-in succeeds, the cookie never arrives, and the next request is anonymous. It is switched
   * on in {@code application-aws.properties}, where TLS is a property of the deployment rather than
   * a hope.
   *
   * <p>WHY THIS IS NOT DERIVED FROM THE REQUEST. It was, and that is the defect. {@code
   * server.forward-headers-strategy=framework} makes Boot trust {@code X-Forwarded-Proto}, and ELB
   * documents that header as carrying "the protocol used between the client and the load balancer".
   * CloudFront is the load balancer's client and connects over HTTP, so the ALB truthfully reports
   * {@code http}, Boot concludes the request was plaintext, and both cookies ship without {@code
   * Secure} — measured live through CloudFront at S7-08, not theorised. TLS terminates two hops
   * earlier and the ALB has no way to know. An explicit statement of what the deployment guarantees
   * is therefore more honest than an inference from a header that is accurate about the wrong hop.
   */
  private final boolean requireSecureCookies;

  SecurityConfiguration(
      @Value("${fru.security.cookies.require-secure:false}") boolean requireSecureCookies) {
    this.requireSecureCookies = requireSecureCookies;
  }

  /**
   * The CSRF cookie repository, with {@code Secure} applied when the deployment guarantees TLS.
   *
   * <p>Package-private and static so a plain unit test can drive it against a real {@link
   * org.springframework.mock.web.MockHttpServletResponse} and read the actual {@code Set-Cookie}
   * header. Asserting the flag on the built cookie is the point: this defect was invisible to every
   * existing test precisely because nothing looked at the header.
   *
   * <p>{@code withHttpOnlyFalse()} is retained deliberately — the SPA must read this cookie from
   * JavaScript to echo it back as a header, which is the whole mechanism. Only {@code Secure} is
   * added. {@code CsrfConfigurer.spa()} sets exactly this repository plus a {@code
   * SpaCsrfTokenRequestHandler}; calling {@code csrfTokenRepository} after {@code spa()} replaces
   * only the former and leaves the handler in place (verified against spring-security-config 7.1.0
   * rather than assumed).
   */
  static CookieCsrfTokenRepository csrfTokenRepository(boolean requireSecure) {
    CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    if (requireSecure) {
      repository.setCookieCustomizer(cookie -> cookie.secure(true));
    }
    return repository;
  }

  /** Chain 1: everything that requires an operator or admin identity. */
  @Bean
  @Order(1)
  SecurityFilterChain backOfficeChain(
      HttpSecurity http,
      OperatorUserRepository users,
      AuthAuditRecorder audit,
      Clock clock,
      JsonMapper jsonMapper)
      throws Exception {

    http.securityMatcher("/api/v1/operator/**", "/api/v1/auth/**", "/api/v1/admin/**")
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/v1/auth/login", "/api/v1/auth/logout")
                    .permitAll()
                    // The ONLY endpoint a must-change principal may reach (§3.4).
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/password")
                    .hasAnyRole("PASSWORD_CHANGE_REQUIRED", "VIEWER", "ADMIN")
                    .requestMatchers("/api/v1/auth/me")
                    .hasAnyRole("VIEWER", "ADMIN")
                    // Claimed for forward compatibility -- no admin HTTP endpoints exist yet
                    // (account provisioning is auth.config.CreateOperatorAccountRunner, a CLI
                    // tool, per this session's scope decision -- see BACKLOG.md). Do not remove
                    // this rule "because nothing uses it".
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/operator/profiles/*/approve",
                        "/api/v1/operator/profiles/*/reject",
                        // The */manual-complete matcher was removed here by AD-022 (S9-01), and the
                        // removal is deliberate rather than the tidying the note above forbids:
                        // that note protects matchers claimed for endpoints not built YET, and
                        // this one guarded an endpoint that has been DELETED and will not return.
                        // A rule for a route that cannot be reached is not forward compatibility,
                        // it is a false statement about what the system exposes.
                        //
                        // One behavioural consequence, stated rather than glossed: a signed-in
                        // VIEWER POSTing that path now gets 404, not 403, because it falls through
                        // to the /api/v1/operator/** VIEWER rule below and then finds no handler.
                        // No authorisation is lost -- there is no route for anyone, of any role.
                        // BL-132. A viewer views everything and prints nothing (AD-013, wayfinder
                        // ticket 06), and a print is a WRITE -- it stores a new artifact and
                        // appends an audit event in one transaction.
                        "/api/v1/operator/profiles/*/print")
                    .hasRole("OPERATOR")
                    // BL-135/AD-015. Per-field editing is a WRITE, and AD-015 names "viewers
                    // cannot edit" as one of the bounds that make a data-entry back office safe.
                    // Without this rule the request would fall through to the
                    // /api/v1/operator/** VIEWER catch-all below and a viewer would reach it --
                    // FieldEditService.requireOperatorLevel refuses them too, so this is the
                    // outer of two gates rather than the only one, matching the print rule's own
                    // belt-and-braces shape.
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/operator/profiles/*/fields/*")
                    .hasRole("OPERATOR")
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/operator/profiles/export",
                        // BL-132, and this rule is load-bearing rather than tidy: without it a
                        // re-download would fall through to the /api/v1/operator/** VIEWER
                        // catch-all below, and a viewer would reach a stored print by GET -- the
                        // one thing ticket 05 decision 6 forbids ("a re-download is a print in
                        // every way that matters"). PrintedFormService refuses a viewer too; this
                        // is the outer of the two gates, not the only one.
                        "/api/v1/operator/profiles/*/prints/*")
                    .hasRole("OPERATOR")
                    // The coarse gate on the whole operator prefix. Must come AFTER the more
                    // specific OPERATOR rules above; Spring Security uses first-match-wins
                    // ordering.
                    //
                    // This used to 403 an authenticated admin, who was never granted ROLE_VIEWER
                    // (AD-002e §3.5(e)). AD-013 (BL-139) reversed that, and NONE of these rules
                    // changed to do it: admin now carries ROLE_VIEWER and ROLE_OPERATOR from
                    // OperatorUserDetails, so it clears this rule and the two above it the same
                    // way an operator does. Keeping the hierarchy in the authority mapping rather
                    // than spreading hasAnyRole("VIEWER","ADMIN") across five rules is deliberate
                    // -- there is one place to read, and one place to get wrong.
                    .requestMatchers("/api/v1/operator/**")
                    .hasRole("VIEWER")
                    .anyRequest()
                    .denyAll())
        // spa() installs SpaCsrfTokenRequestHandler AND a repository; overriding only the
        // repository afterwards keeps the handler. See csrfTokenRepository's javadoc (BL-066).
        // BL-136/S8-24. Spring Security applies `headers(withDefaults())` to every chain it
        // builds, and `FrameOptionsConfig`'s default is X-Frame-Options: DENY -- which blocks
        // framing even by a SAME-ORIGIN page. The back office frames one thing: a PDF salary
        // certificate served by ProfileImageController, from the same origin as the SPA since
        // S7-08. Under DENY that modal renders the browser's "Refused to display" message and the
        // operator never sees the document, which is the whole deliverable.
        //
        // SAMEORIGIN rather than removing the header, plus `frame-ancestors 'self'`, which is the
        // modern equivalent and the one browsers prefer where both are present. Scope is per CHAIN
        // in Spring Security, not per route, so this covers all of chain 1 -- acceptable because
        // every response on it is JSON or artifact bytes, never an interactive page a clickjacking
        // attack would have anything to steal a click on. The SPA's own HTML is not served by this
        // application at all (CloudFront's default behaviour serves it from S3), so its framing
        // posture is unaffected either way.
        //
        // NOT covered by any test that runs the filter chain until now: every test of the image
        // endpoint uses @AutoConfigureMockMvc(addFilters = false), so the header writer never ran.
        // OperatorImageHeadersIntegrationTest exists to close exactly that gap.
        .headers(
            headers ->
                headers
                    .frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
                    .contentSecurityPolicy(csp -> csp.policyDirectives("frame-ancestors 'self'")))
        .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository(requireSecureCookies)))
        .sessionManagement(
            session ->
                session
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionFixation(fixation -> fixation.changeSessionId()))
        .exceptionHandling(
            ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .formLogin(
            form ->
                form.loginProcessingUrl("/api/v1/auth/login")
                    .successHandler(new SignInSuccessHandler(users, audit, clock, jsonMapper))
                    .failureHandler(new SignInFailureHandler()))
        .logout(
            logout ->
                logout
                    .logoutUrl("/api/v1/auth/logout")
                    .addLogoutHandler(new SignOutAuditLogoutHandler(audit))
                    .logoutSuccessHandler(
                        new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                    .deleteCookies("JSESSIONID"))
        .addFilterAfter(new OperatorIdentityFilter(users), AnonymousAuthenticationFilter.class);

    return http.build();
  }

  /**
   * Chain 2: every unauthenticated customer/mobile endpoint under {@code /api/v1/**} not matched by
   * chain 1 above — nine at AD-002e's closure (S4-05), a tenth ({@code /api/v1/salary-certificate})
   * added at S4-06. Not enumerated here by design: a new customer endpoint is unauthenticated by
   * falling under this catch-all, not by being added to a list that would otherwise go stale.
   */
  @Bean
  @Order(2)
  SecurityFilterChain customerApiChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/v1/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        // No ambient credentials exist on these endpoints -- CSRF protects a cookie-authenticated
        // browser session, which chain 1 alone has.
        .csrf(CsrfConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(RequestCacheConfigurer::disable);
    return http.build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    // "bcrypt is the default encoding algorithm" [DOC password-storage.html 7.1.1]; stored as
    // {bcrypt}$2a$.... The {id} prefix is what lets the algorithm (Argon2/PBKDF2, e.g. if OQ-001
    // ever mandates FIPS) or the bcrypt work factor change later without a data migration.
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  DaoAuthenticationProvider authenticationProvider(
      UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
    // Constructor-only in Spring Security 7.1 -- setUserDetailsService(...) was removed.
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder);
    // Presence of this bean backs off Boot's auto-configured default in-memory user.
    return provider;
  }
}
