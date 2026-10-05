package com.sfbank.bayanati.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.auth.domain.DuplicateUsernameException;
import com.sfbank.bayanati.auth.domain.OperatorRole;
import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import com.sfbank.bayanati.auth.service.AuthAuditRecorder;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S4-05 (AD-002e) — the real authentication filter chain, end to end, unlike every operator
 * integration test in {@code operator/} (which deliberately disables security filters to stay
 * scoped to business logic — see their own updated Javadoc). This class does NOT disable filters:
 * it drives real sign-in, CSRF, forced password change, live-session disable, and identity with
 * real UUIDs, exactly as an actual browser would. Branch 16, accounts 0000000503–0000000520 (NOT
 * 0000000501/0000000502, which belong to {@code AccountCheckIntegrationTest}) — see {@link
 * AbstractPostgresIntegrationTest}. {@code app.operator_user.username} is a separate namespace;
 * every seeded account here uses the {@code s405.*} prefix.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class OperatorAuthenticationIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String BRANCH = "16";
  private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private Environment environment;
  @Autowired private ServerProperties serverProperties;
  @Autowired private OperatorUserRepository operatorUserRepository;

  @MockitoSpyBean private MessageSender messageSender;
  @MockitoSpyBean private AuthAuditRecorder auditRecorder;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @Test
  void signInReachAnOperatorEndpointAndSignOut() throws Exception {
    seedOperatorAccount("s405.viewer1", "Viewer One", "viewer", "correct-horse-1", false);

    MockHttpSession session = login("s405.viewer1", "correct-horse-1");

    // Reusing the SAME session for a second request settles task §5 item 1: does formLogin's
    // built-in filter save the SecurityContext under 7.1's requireExplicitSave default? It could
    // only reach 200 here if it did.
    mockMvc.perform(get("/api/v1/operator/profiles").session(session)).andExpect(status().isOk());

    mockMvc
        .perform(post("/api/v1/auth/logout").session(session).with(csrf()))
        .andExpect(status().isNoContent());

    // The session is invalidated by logout -- the same cookie no longer reaches an operator
    // endpoint.
    mockMvc
        .perform(get("/api/v1/operator/profiles").session(session))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void newAccountReachesOnlyThePasswordChangeEndpointByAuthorizationNotAControllerCheck()
      throws Exception {
    seedOperatorAccount(
        "s405.mustchange1", "Must Change One", "viewer", "initial-password-1", true);

    MvcResult loginResult = performLogin("s405.mustchange1", "initial-password-1");
    JsonNode loginBody = objectMapper.readTree(loginResult.getResponse().getContentAsString());
    assertTrue(loginBody.get("mustChangePassword").asBoolean());
    MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

    // Everything else refused -- by AuthorizationFilter (403), not a controller-side check.
    mockMvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isForbidden());
    mockMvc
        .perform(get("/api/v1/operator/profiles").session(session))
        .andExpect(status().isForbidden());

    // The ONE endpoint it may reach.
    mockMvc
        .perform(
            post("/api/v1/auth/password")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"currentPassword\":\"initial-password-1\",\"newPassword\":\"new-password-2\"}"))
        .andExpect(status().isNoContent());

    // Rebuilt context (AD-002e §3.4's "always forgotten" step) -- the SAME session now reaches
    // the normal viewer surface with no re-login.
    mockMvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/operator/profiles").session(session)).andExpect(status().isOk());
  }

  /**
   * Found under review: {@code PasswordChangeServiceTest}'s equivalent assertion used a mocked
   * {@code PlatformTransactionManager}, which makes the callback body run identically whether or
   * not {@code TransactionTemplate} actually wraps it -- exactly the "a green test could coexist
   * with a broken guard" case CLAUDE.md's live-proof rule names (this codebase already settled it
   * once, {@code ContactChannelsIntegrationTest}: "A mocked PlatformTransactionManager ... cannot
   * exercise real rollback"). This test, against the real database, is the actual proof: revert
   * {@code PasswordChangeService}'s {@code TransactionTemplate} wrapper and this fails (verified by
   * hand before committing -- the password row changes even though the audit write still throws).
   */
  @Test
  void aFailingAuditWriteRollsBackThePasswordChangeTogetherNotPartially() throws Exception {
    UUID userId =
        seedOperatorAccount("s405.pwrollback", "PW Rollback", "viewer", "initial-pw-1", true);
    String hashBefore =
        jdbcTemplate.queryForObject(
            "SELECT password_hash FROM app.operator_user WHERE user_id = ?::uuid",
            String.class,
            userId.toString());

    MockHttpSession session = login("s405.pwrollback", "initial-pw-1");
    org.mockito.Mockito.doThrow(new RuntimeException("simulated audit failure"))
        .when(auditRecorder)
        .passwordChanged(userId);

    // No @ExceptionHandler exists for a bare RuntimeException -- it propagates through
    // DispatcherServlet uncaught, which MockMvc re-throws from perform() rather than turning
    // into an assertable response. That IS the real production behaviour this test is proving
    // against (see docs/components/backoffice-auth.md's "Known gaps" -- a throw here surfaces as
    // a 500, not a translated response), so the throw itself is expected, not swallowed silently.
    assertThrows(
        Exception.class,
        () ->
            mockMvc.perform(
                post("/api/v1/auth/password")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"currentPassword\":\"initial-pw-1\",\"newPassword\":\"new-pw-2\"}")));

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT password_hash, must_change_password FROM app.operator_user"
                + " WHERE user_id = ?::uuid",
            userId.toString());
    assertEquals(
        hashBefore,
        row.get("password_hash"),
        "the password change must roll back together with its audit write, not commit alone");
    assertEquals(Boolean.TRUE, row.get("must_change_password"));
  }

  /**
   * AD-002e R1: {@code OperatorIdentity.operatorId} is {@code app.operator_user.user_id} (a UUID),
   * never the username, so renaming an account cannot re-attribute what that account already did.
   *
   * <p><strong>This proof has now been re-pointed TWICE, and the reason is worth keeping.</strong>
   * Until AD-013 (2026-09-13) it ran through the four-eyes rule: A manually completed a profile,
   * A's own approve was refused, A was renamed, and the approve was STILL refused. AD-013 removed
   * the rule, so that vehicle went -- and inverting the assertions in place would have proven
   * nothing, because once A's first approve succeeds the post-rename call returns an idempotent
   * already-approved 200 whether {@code actor_id} is a UUID or a username. It was then re-pointed
   * at manual completion's own history row and {@code profile_manually_completed} audit event.
   * AD-022 (S9-01) deleted manual completion outright, taking that second vehicle with it.
   *
   * <p>The SUBJECT never changed, only the vehicle, so the third vehicle is APPROVE -- the operator
   * action that still writes both an append-only {@code app.profile_status_history} row and an
   * audit event carrying {@code actor_id}. After the rename, both still carry A's UUID. Had {@code
   * operatorId} been the username, those rows would hold a STALE name ({@code s405.opa}), which
   * after the rename belongs to nobody -- and every past action would be attributed to an
   * identifier that no longer resolves. The two {@code assertNotEquals} calls pin exactly that:
   * neither spelling of the username may appear in {@code actor_id}.
   *
   * <p>The profile is promoted to {@code submitted} by raw SQL rather than by driving the whole
   * customer journey, following the established pattern in {@code DataEntryIntegrationTest} and
   * three other integration tests. The route to {@code submitted} is scaffolding here; the subject
   * is what {@code actor_id} holds after a rename.
   */
  @Test
  void actorIdIsTheUuidSoARenameDoesNotRewriteHistory() throws Exception {
    UUID operatorAId =
        seedOperatorAccount("s405.opa", "Operator A", "operator", "operator-a-pw-1", false);

    String profileId = createProfileWithVerifiedSms("0000000503", "+249911050311");

    promoteToSubmitted(profileId);

    MockHttpSession aSession = login("s405.opa", "operator-a-pw-1");

    // A's approve, driven through a real sign-in rather than an injected identity, so the actor_id
    // under test is the one a genuine session produces.
    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + profileId + "/approve")
                .session(aSession)
                .with(csrf()))
        .andExpect(status().isOk());
    keepOutboxRowsUnclaimable(profileId);
    assertEquals("approved", currentStatus(profileId));

    // R1: rename A's LOGIN NAME.
    jdbcTemplate.update(
        "UPDATE app.operator_user SET username = ? WHERE user_id = ?::uuid",
        "s405.opa-renamed",
        operatorAId.toString());

    // The history row still attributes the approval to A's UUID, unchanged by the rename.
    String historyActorId =
        jdbcTemplate.queryForObject(
            "SELECT actor_id FROM app.profile_status_history"
                + " WHERE profile_id = ?::uuid AND to_status = 'approved'",
            String.class,
            profileId);
    assertEquals(
        operatorAId.toString(),
        historyActorId,
        "actor_id must be the UUID, not either spelling of the username");
    assertNotEquals("s405.opa", historyActorId);
    assertNotEquals("s405.opa-renamed", historyActorId);

    // ...and so does the audit event for that approval.
    String auditActorId =
        jdbcTemplate.queryForObject(
            "SELECT ae.actor_id FROM audit.audit_event ae"
                + " JOIN audit.audit_chain ac ON ac.chain_id = ae.chain_id"
                + " WHERE ac.chain_kind = 'profile' AND ac.subject_id = ?"
                + " AND ae.event_type = 'profile_approved'",
            String.class,
            profileId);
    assertEquals(operatorAId.toString(), auditActorId);

    // A signs in again under the NEW username and is the same principal as before.
    MockHttpSession aSessionAfterRename = login("s405.opa-renamed", "operator-a-pw-1");
    mockMvc
        .perform(get("/api/v1/operator/profiles/" + profileId).session(aSessionAfterRename))
        .andExpect(status().isOk());
  }

  @Test
  void disablingAnAccountTakesEffectOnALiveSessionNotOnlyAtNextLogin() throws Exception {
    UUID userId =
        seedOperatorAccount("s405.disableme", "Disable Me", "viewer", "disable-pw-1", false);
    MockHttpSession session = login("s405.disableme", "disable-pw-1");

    mockMvc.perform(get("/api/v1/operator/profiles").session(session)).andExpect(status().isOk());

    // Disabled directly, mid-session -- no new login, no new session.
    jdbcTemplate.update(
        "UPDATE app.operator_user SET is_enabled = false WHERE user_id = ?::uuid",
        userId.toString());

    // The SAME session, unchanged, is now refused -- OperatorIdentityFilter's per-request re-read
    // (findActiveById) no longer finds the row, so the identity attribute is never set and
    // OperatorIdentityArgumentResolver 401s. This is the live-session effect, not next-login.
    mockMvc
        .perform(get("/api/v1/operator/profiles").session(session))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void authenticatedAdminReachesOperatorEndpoints() throws Exception {
    // INVERTED at BL-139 (was authenticatedAdminGets403OnOperatorEndpoint). AD-013 makes admin a
    // superuser, so the 403 this test used to assert -- AuthorizationFilter's AccessDeniedHandler,
    // because admin was never granted ROLE_VIEWER -- is now the defect, not the contract.
    seedOperatorAccount("s405.admin1", "Admin One", "admin", "admin-pw-1", false);
    MockHttpSession session = login("s405.admin1", "admin-pw-1");

    // The coarse hasRole("VIEWER") gate on the /api/v1/operator/** prefix.
    mockMvc.perform(get("/api/v1/operator/profiles").session(session)).andExpect(status().isOk());
  }

  @Test
  void authenticatedAdminClearsTheOperatorOnlyRules() throws Exception {
    // The gate BL-139's brief missed: approve, reject, print and export each carry their
    // OWN hasRole("OPERATOR") rule (SecurityConfiguration), ahead of the coarse VIEWER rule.
    // manual-complete was a fifth such rule until AD-022 deleted the endpoint (S9-01). Fixing
    // only the prefix gate would leave every one of AD-013's named admin powers still 403.
    //
    // A random profile id is deliberate -- this asserts AUTHORIZATION, not review behaviour. What
    // matters is that the response is no longer 403: reaching a 404 means the request passed the
    // filter chain and got as far as the handler, which is exactly the property under test.
    seedOperatorAccount("s405.admin2", "Admin Two", "admin", "admin-pw-2", false);
    MockHttpSession session = login("s405.admin2", "admin-pw-2");

    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + UUID.randomUUID() + "/approve")
                .session(session)
                .with(csrf()))
        .andExpect(status().isNotFound());

    // `format` is a required param -- without it this 400s on binding, which would still prove
    // authorization passed, but 200 says so without needing that argument.
    mockMvc
        .perform(get("/api/v1/operator/profiles/export").param("format", "csv").session(session))
        .andExpect(status().isOk());
  }

  @Test
  void failedSignInForAnUnknownUsernameRecordsAccountExistsFalseAndNeverTheUsername()
      throws Exception {
    String unknownUsername = "s405.nonexistent";

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .param("username", unknownUsername)
                .param("password", "whatever-1")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
        .andExpect(status().isUnauthorized());

    // A passing assertion here can only happen if the AbstractAuthenticationFailureEvent actually
    // fired and AuthFailureAuditListener actually ran -- this also settles task §5 item 2:
    // DefaultAuthenticationEventPublisher survives the custom SecurityFilterChain +
    // AuthenticationProvider beans.
    String payloadJson =
        jdbcTemplate.queryForObject(
            """
            SELECT ae.payload_json FROM audit.audit_event ae
              JOIN audit.audit_chain ac ON ac.chain_id = ae.chain_id
             WHERE ac.chain_kind = 'system' AND ac.subject_id = 'auth'
               AND ae.event_type = 'sign_in_failed'
             ORDER BY ae.seq DESC LIMIT 1
            """,
            String.class);
    assertNotNull(payloadJson);
    assertTrue(payloadJson.contains("\"accountExists\":false"));
    assertFalse(
        payloadJson.contains(unknownUsername),
        "the submitted username must never reach the append-only audit trail");
  }

  @Test
  void effectiveSessionTimeoutIsThirtyMinutesWithThePropertyLeftUnset() {
    // Settles task §5 item 4. MockHttpSession.getMaxInactiveInterval() was tried first and
    // rejected: it is a Spring test double, never wired to the real embedded Tomcat's session
    // configuration, so it cannot answer this question (found live -- it returned 0, not a real
    // measurement of anything server.servlet.session.timeout affects). The real chain is
    // ServerProperties.getServlet().getSession().getTimeout(), which
    // TomcatServletWebServerFactory.getSessionTimeoutInMinutes() reads directly [OBSERVED
    // decompiled org.springframework.boot.web.server.servlet.Session, spring-boot-web-server
    // 4.1.0: Duration.ofMinutes(30) is the field's own default].
    assertNull(
        environment.getProperty("server.servlet.session.timeout"),
        "server.servlet.session.timeout must stay unset -- SecurityConfiguration's own Javadoc");
    assertEquals(Duration.ofMinutes(30), serverProperties.getServlet().getSession().getTimeout());
  }

  @Test
  void unauthenticatedCustomerPrefixIsStillReachable() throws Exception {
    // Regression smoke for chain 2 -- named per CLAUDE.md's "name the test, don't re-prove it"
    // rule: the full unauthenticated-access proof for every one of the nine customer prefixes is
    // the existing suite of 15 @SpringBootTest customer-facing integration classes (unchanged by
    // this session), not re-derived here. This is the class that owns the new security config's
    // correctness, so it also carries one direct check of its own.
    mockMvc.perform(get("/api/v1/reference/manifest")).andExpect(status().isOk());
  }

  @Test
  void jdbcOperatorUserRepositoryCreateWorksAgainstTheRealDatabase() {
    // The one method every other test in this class bypasses via a hand-written INSERT
    // (seedOperatorAccount, below) -- exercised directly here against the real Testcontainers
    // database, including the RETURNING round-trip, a null createdBy bind, and the
    // DuplicateKeyException -> DuplicateUsernameException mapping the CLI runbook's recovery
    // instructions depend on (found under review: nothing else in this suite calls it).
    String hash = passwordEncoder.encode("s405-repo-test-pw");
    UUID userId =
        operatorUserRepository.create(
            "s405.repocreate", "Repo Create", OperatorRole.OPERATOR, hash, null);

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT username, display_name, role, password_hash, must_change_password, created_by"
                + " FROM app.operator_user WHERE user_id = ?::uuid",
            userId.toString());
    assertEquals("s405.repocreate", row.get("username"));
    assertEquals("Repo Create", row.get("display_name"));
    assertEquals("operator", row.get("role"));
    assertEquals(hash, row.get("password_hash"));
    assertEquals(Boolean.TRUE, row.get("must_change_password"));
    assertNull(row.get("created_by"));

    assertThrows(
        DuplicateUsernameException.class,
        () ->
            operatorUserRepository.create(
                "s405.repocreate", "Someone Else", OperatorRole.VIEWER, hash, null));
  }

  // ---- helpers ----

  private UUID seedOperatorAccount(
      String username,
      String displayName,
      String role,
      String rawPassword,
      boolean mustChangePassword) {
    String hash = passwordEncoder.encode(rawPassword);
    String userId =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO app.operator_user (username, display_name, role, password_hash, must_change_password)
            VALUES (?, ?, ?, ?, ?)
            RETURNING user_id::text
            """,
            String.class,
            username,
            displayName,
            role,
            hash,
            mustChangePassword);
    return UUID.fromString(userId);
  }

  private MockHttpSession login(String username, String rawPassword) throws Exception {
    return (MockHttpSession) performLogin(username, rawPassword).getRequest().getSession(false);
  }

  private MvcResult performLogin(String username, String rawPassword) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .param("username", username)
                .param("password", rawPassword)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
        .andExpect(status().isOk())
        .andReturn();
  }

  private String currentStatus(String profileId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM app.profile WHERE profile_id = ?::uuid", String.class, profileId);
  }

  /**
   * Promotes an {@code in_progress} profile to {@code submitted} by raw SQL, so an operator review
   * can be driven against it. The established pattern from {@code DataEntryIntegrationTest} and
   * three other integration tests, reused verbatim rather than reinvented: the history row's {@code
   * audit_event_id} points at the profile's own {@code session_created} event, and both writes go
   * in ONE transaction because {@code app.profile_status_history} is append-only and a half-written
   * promotion would leave a status with no history row behind it.
   */
  private void promoteToSubmitted(String profileId) {
    Long auditEventId =
        jdbcTemplate.queryForObject(
            "SELECT e.audit_event_id FROM audit.audit_event e"
                + " JOIN audit.audit_chain c ON c.chain_id = e.chain_id"
                + " WHERE c.chain_kind = 'profile' AND c.subject_id = ?"
                + " AND e.event_type = 'session_created'",
            Long.class,
            profileId);

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              jdbcTemplate.update(
                  "UPDATE app.profile SET status = 'submitted', status_changed_at ="
                      + " clock_timestamp(), submitted_at = clock_timestamp(), reference_number = ?"
                      + " WHERE profile_id = ?::uuid",
                  "TESTREF-" + profileId.substring(0, 8),
                  profileId);
              jdbcTemplate.update(
                  "INSERT INTO app.profile_status_history"
                      + " (profile_id, seq, from_status, to_status, actor_kind, audit_event_id)"
                      + " VALUES (?::uuid, 2, 'in_progress', 'submitted', 'system', ?::bigint)",
                  profileId,
                  auditEventId);
            });
  }

  private void keepOutboxRowsUnclaimable(String profileId) {
    jdbcTemplate.update(
        "UPDATE app.notification_outbox SET next_attempt_at = now() + interval '1 hour' WHERE profile_id = ?::uuid",
        profileId);
  }

  private String createProfileWithVerifiedSms(String accountNumber, String phoneNumber)
      throws Exception {
    String body =
        "{\"branch\":\""
            + BRANCH
            + "\",\"accountNumber\":\""
            + accountNumber
            + "\",\"phoneNumber\":\""
            + phoneNumber
            + "\",\"sms\":true,\"whatsapp\":false}";
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/contact-channels")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();
    String profileId =
        objectMapper.readTree(result.getResponse().getContentAsString()).get("profileId").asText();

    String smsCode = capturedCode(MessageChannel.SMS);
    mockMvc
        .perform(
            post("/api/v1/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"channel\":\"sms\",\"code\":\""
                        + smsCode
                        + "\"}"))
        .andExpect(status().isOk());
    return profileId;
  }

  private String capturedCode(MessageChannel channel) {
    ArgumentCaptor<OutboundMessage> captor = ArgumentCaptor.forClass(OutboundMessage.class);
    verify(messageSender, atLeastOnce()).send(captor.capture());
    List<OutboundMessage> sent = captor.getAllValues();
    for (int i = sent.size() - 1; i >= 0; i--) {
      OutboundMessage message = sent.get(i);
      if (message.channel() == channel && message.payload() instanceof SmsPayload sms) {
        Matcher matcher = SIX_DIGITS.matcher(sms.body());
        if (matcher.find()) {
          return matcher.group();
        }
      }
    }
    throw new IllegalStateException("no OTP code captured for channel " + channel);
  }
}
