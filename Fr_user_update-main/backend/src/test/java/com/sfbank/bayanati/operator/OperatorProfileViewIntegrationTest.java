package com.sfbank.bayanati.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.operator.domain.OperatorAccessLevel;
import com.sfbank.bayanati.uqudo.stub.StubUqudoClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S4-01: the single profile view (operator.md). Branch 16, accounts 0000000446–0000000450 (see
 * {@link AbstractPostgresIntegrationTest}).
 */
@Tag("integration")
@SpringBootTest
// S4-05: see OperatorReviewIntegrationTest's identical comment -- filters disabled here so this
// class stays scoped to single-profile-view business logic via direct identity injection; the
// real auth layer is proven separately in auth/OperatorAuthenticationIntegrationTest.
@AutoConfigureMockMvc(addFilters = false)
class OperatorProfileViewIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String BRANCH = "16";
  private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StubUqudoClient stubUqudoClient;

  @MockitoSpyBean private MessageSender messageSender;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @Test
  void viewReturnsFullAggregateAndNeverCarriesCanApprove() throws Exception {
    String profileId = readyToSubmitProfile("0000000446", "+249911003461", "IDN-0000000446");
    submit(profileId);

    // Unknown operator, viewer level: full data returned. AD-013 removed the four-eyes rule and
    // canApprove went with it, so no caller of any access level receives the field at all -- the
    // back office gates its Approve button on the operator's own role and the profile's status.
    JsonNode viewerBody = view(profileId, "op-viewer", OperatorAccessLevel.VIEWER, status().isOk());
    assertEquals("submitted", viewerBody.get("status").asText());
    assertNull(viewerBody.get("canApprove"), "canApprove must be absent, not false");
    // app.scan_result.document_type stores Uqudo's own vocabulary ("PASSPORT"), not the app-level
    // "passport" identityscan.domain.DocumentTypes uses.
    assertEquals("PASSPORT", viewerBody.get("scanResult").get("documentType").asText());
    assertTrue(viewerBody.get("faceResult").get("passed").asBoolean());
    assertTrue(viewerBody.get("artifacts").size() > 0, "at least the signature artifact");
    assertTrue(viewerBody.get("statusHistory").size() > 0);

    boolean foundPassportPortraitLabel = false;
    for (JsonNode artifact : viewerBody.get("artifacts")) {
      if ("portrait_uqudo".equals(artifact.get("kind").asText())) {
        assertEquals("Uqudo — passport", artifact.get("label").asText());
        foundPassportPortraitLabel = true;
      }
    }
    assertTrue(
        foundPassportPortraitLabel, "expected a portrait_uqudo artifact labelled by document type");

    JsonNode freshOperatorBody =
        view(profileId, "op-fresh", OperatorAccessLevel.OPERATOR, status().isOk());
    assertNull(freshOperatorBody.get("canApprove"));

    // A manual-completion history row is the precondition the four-eyes guard used to react to.
    // It is still READABLE and still surfaced on the timeline. Nothing writes it since AD-022
    // deleted manual completion (S9-01), which is why this row is seeded by raw SQL below; the
    // column is kept so profiles completed before that ruling stay distinguishable in their own
    // history.
    seedManualCompletionHistoryRow(profileId, "op-manual");

    JsonNode manualCompleterBody =
        view(profileId, "op-manual", OperatorAccessLevel.OPERATOR, status().isOk());
    assertNull(
        manualCompleterBody.get("canApprove"),
        "the operator who manually completed this profile is no longer distinguished (AD-013)");

    boolean sawManualCompletionOnTimeline = false;
    for (JsonNode entry : manualCompleterBody.get("statusHistory")) {
      if (entry.get("isManualCompletion").asBoolean()) {
        sawManualCompletionOnTimeline = true;
      }
    }
    assertTrue(
        sawManualCompletionOnTimeline,
        "is_manual_completion must still reach the operator's timeline after AD-013");

    JsonNode otherOperatorBody =
        view(profileId, "op-other", OperatorAccessLevel.OPERATOR, status().isOk());
    assertNull(otherOperatorBody.get("canApprove"));

    Long viewedEvents =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit.audit_event ae"
                + " JOIN audit.audit_chain ac ON ac.chain_id = ae.chain_id"
                + " WHERE ac.chain_kind = 'profile' AND ac.subject_id = ?"
                + " AND ae.event_type = 'profile_viewed'",
            Long.class,
            profileId);
    assertNotNull(viewedEvents);
    assertEquals(4L, viewedEvents, "one profile_viewed event per view() call above");
  }

  @Test
  void viewOfAnUnknownProfileIs404() throws Exception {
    view(
        UUID.randomUUID().toString(),
        "op-viewer",
        OperatorAccessLevel.VIEWER,
        status().isNotFound());
  }

  @Test
  void viewWithNoOperatorIdentityIs401() throws Exception {
    mockMvc
        .perform(get("/api/v1/operator/profiles/" + UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }

  /**
   * BL-122 — the three answers, end to end, against the real database.
   *
   * <p>All three profiles reach the operator view carrying NO {@code salary_certificate} artifact
   * row. That is the whole point: before this, they were indistinguishable to the person deciding
   * whether to approve. Every assertion below therefore also checks the artifact really is absent,
   * so a regression that started writing an artifact row could not make these pass for the wrong
   * reason.
   */
  @Test
  void missingSalaryCertificateDistinguishesDeclinedFromFailedUpload() throws Exception {
    // DECLINED: Stage 6 arrived saying no file was attached.
    String declined = readyToSubmitProfile("0000000447", "+249911003471", "IDN-0000000447");
    submitStage6(declined, false);

    // ATTACH_FAILED: Stage 6 arrived saying a file WAS attached, and no upload ever followed --
    // exactly what a customer whose connection died after picking their certificate leaves behind.
    String failed = readyToSubmitProfile("0000000448", "+249911003481", "IDN-0000000448");
    submitStage6(failed, true);

    // NOT_REACHED: Stage 6 never arrived at all. Reachable in practice -- the backend enforces no
    // stage ordering and Stage 6's POST is offline-queued on the handset.
    String notReached = readyToSubmitProfile("0000000449", "+249911003491", "IDN-0000000449");

    assertEquals("DECLINED", salaryCertificateState(declined));
    assertEquals("ATTACH_FAILED", salaryCertificateState(failed));
    assertEquals("NOT_REACHED", salaryCertificateState(notReached));

    for (String profileId : List.of(declined, failed, notReached)) {
      assertEquals(
          0,
          (int)
              jdbcTemplate.queryForObject(
                  "SELECT count(*) FROM app.artifact_ref"
                      + " WHERE profile_id = ?::uuid AND kind = 'salary_certificate'",
                  Integer.class,
                  profileId),
          "the distinction must hold with NO certificate artifact on any of the three");
    }
  }

  /**
   * How the claim is written, which only a real database can settle: an explicit {@code false} is a
   * CORRECTION and clears it, while a client that omits the field entirely leaves it standing.
   *
   * <p>Both halves matter and they pull in opposite directions. Clearing on {@code false} is what
   * lets an AD-008 device-less re-entry fix the record — the new customer's Stage 6 corrects every
   * other column on this row, and this must not be the one their submission cannot reach, or a
   * previous person's claim is reported about them for ever (BL-143). Not clearing on {@code null}
   * is what stops a client built before this field existed silently asserting "the customer
   * declined" about every profile it touches — the same reason {@code Stage6Request} boxes the
   * field. A plain assignment would fail the second half; a plain COALESCE would fail the first.
   */
  @Test
  void anExplicitFalseClearsTheClaimButAnAbsentFieldDoesNot() throws Exception {
    String profileId = readyToSubmitProfile("0000000450", "+249911003501", "IDN-0000000450");

    submitStage6(profileId, true);
    assertEquals("ATTACH_FAILED", salaryCertificateState(profileId));

    // A client too old to send the field must not be read as saying "nothing attached".
    submitStage6(profileId, null);
    assertEquals("ATTACH_FAILED", salaryCertificateState(profileId));

    // An explicit "nothing is attached" is a correction and must land.
    submitStage6(profileId, false);
    assertEquals("DECLINED", salaryCertificateState(profileId));
  }

  /**
   * PRESENT, through the real SQL rather than a resolved boolean. {@code
   * SalaryCertificateStateTest} takes {@code hasCommittedArtifact} as a parameter, so it exercises
   * none of the {@code EXISTS} predicate in {@code JdbcProfileViewRepository} — a wrong {@code
   * kind}, {@code state} or join key there would pass every other test in this change. Found by
   * {@code @agent-reviewer}.
   *
   * <p>The purged half pins the fall-through the state filter creates: a certificate the bank
   * received and then deleted under retention (V0055) stops being PRESENT, which is BL-142.
   * Asserted so that the behaviour is on the record as known rather than discovered later as a bug.
   */
  @Test
  void aCommittedCertificateIsPresentAndAPurgedOneIsNotFoundByTheQuery() throws Exception {
    String profileId = readyToSubmitProfile("0000000590", "+249911003901", "IDN-0000000590");
    submitStage6(profileId, true);

    String base64 =
        Base64.getEncoder().encodeToString("fake-certificate".getBytes(StandardCharsets.UTF_8));
    Map<String, Object> certificate = new LinkedHashMap<>();
    certificate.put("profileId", profileId);
    certificate.put("contentType", "application/pdf");
    certificate.put("contentBase64", base64);
    mockMvc
        .perform(
            post("/api/v1/salary-certificate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(certificate)))
        .andExpect(status().isOk());

    // The artifact outranks the standing claim -- no write retired it.
    assertEquals("PRESENT", salaryCertificateState(profileId));

    jdbcTemplate.update(
        "UPDATE app.artifact_ref SET state = 'purged', body = NULL"
            + " WHERE profile_id = ?::uuid AND kind = 'salary_certificate'",
        profileId);

    assertEquals("ATTACH_FAILED", salaryCertificateState(profileId), "BL-142");
  }

  private String salaryCertificateState(String profileId) throws Exception {
    return view(profileId, "op-viewer", OperatorAccessLevel.VIEWER, status().isOk())
        .get("salaryCertificateState")
        .asText();
  }

  private void submitStage6(String profileId, Boolean salaryCertificateAttached) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("profileId", profileId);
    body.put("employer", "Acme");
    body.put("countryCode", "SD");
    body.put("stateCode", "11");
    body.put("localityCode", "1101");
    body.put("city", "Halfa");
    body.put("area", "Area");
    body.put("street", "Street");
    body.put("block", "Block");
    if (salaryCertificateAttached != null) {
      body.put("salaryCertificateAttached", salaryCertificateAttached);
    }
    mockMvc
        .perform(
            post("/api/v1/data-entry/stage6")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  private JsonNode view(
      String profileId,
      String operatorId,
      OperatorAccessLevel level,
      org.springframework.test.web.servlet.ResultMatcher expectedStatus)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get("/api/v1/operator/profiles/" + profileId)
                    .with(OperatorProfileListIntegrationTest.operatorIdentity(operatorId, level)))
            .andExpect(expectedStatus)
            .andReturn();
    String body = result.getResponse().getContentAsString();
    return body.isBlank() ? null : objectMapper.readTree(body);
  }

  private void seedManualCompletionHistoryRow(String profileId, String operatorId) {
    long auditEventId =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO audit.audit_event
              (chain_id, seq, occurred_at, event_type, actor_kind, actor_id,
               profile_id, session_id, request_id, payload_json,
               prev_hash, content_hash, row_hash)
            SELECT ac.chain_id, 0, clock_timestamp(), 'test_manual_completion_seed', 'operator', ?::text,
                   ?::uuid, NULL::uuid, gen_random_uuid(), '{}'::text,
                   ''::bytea, ''::bytea, ''::bytea
              FROM audit.audit_chain ac WHERE ac.chain_kind = 'profile' AND ac.subject_id = ?::text
            RETURNING audit_event_id
            """,
            Long.class,
            operatorId,
            profileId,
            profileId);
    jdbcTemplate.update(
        """
        INSERT INTO app.profile_status_history
          (profile_id, seq, from_status, to_status, actor_kind, actor_id, is_manual_completion, audit_event_id)
        VALUES (?::uuid,
                (SELECT coalesce(max(seq), 0) + 1 FROM app.profile_status_history WHERE profile_id = ?::uuid),
                'submitted', 'submitted', 'operator', ?, true, ?::bigint)
        """,
        profileId,
        profileId,
        operatorId,
        auditEventId);
  }

  /**
   * Also backdates the "received" notification submit() itself enqueues, immediately -- an
   * un-backdated row is claimable by {@code NotificationOutboxIntegrationTest}'s unscoped {@code
   * claimOnePending()} poll for the rest of the whole suite run (found under review; this class has
   * only one caller of submit(), but the fix belongs in the helper, not the call site).
   */
  private void submit(String profileId) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/submission")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isOk());
    jdbcTemplate.update(
        "UPDATE app.notification_outbox SET next_attempt_at = now() + interval '1 hour' WHERE profile_id = ?::uuid",
        profileId);
  }

  /** Mirrors {@code SubmissionIntegrationTest}'s proven helper chain exactly. */
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
    org.mockito.ArgumentCaptor<OutboundMessage> captor =
        org.mockito.ArgumentCaptor.forClass(OutboundMessage.class);
    verify(messageSender, org.mockito.Mockito.atLeastOnce()).send(captor.capture());
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

  private String readyToSubmitProfile(
      String accountNumber, String phoneNumber, String identityNumber) throws Exception {
    String profileId = profileWithPassedLiveness(accountNumber, phoneNumber, identityNumber);

    String base64 =
        Base64.getEncoder().encodeToString("fake-signature-bytes".getBytes(StandardCharsets.UTF_8));
    Map<String, Object> signatureBody = new LinkedHashMap<>();
    signatureBody.put("profileId", profileId);
    signatureBody.put("captureMethod", "drawn");
    signatureBody.put("contentType", "image/png");
    signatureBody.put("contentBase64", base64);
    mockMvc
        .perform(
            post("/api/v1/signature")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signatureBody)))
        .andExpect(status().isOk());
    return profileId;
  }

  private String profileWithPassedLiveness(
      String accountNumber, String phoneNumber, String identityNumber) throws Exception {
    String profileId = createProfileWithVerifiedSms(accountNumber, phoneNumber);

    MvcResult tokenResult =
        mockMvc
            .perform(
                post("/api/v1/identity-scan/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"passport\"}"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode tokenResponse = objectMapper.readTree(tokenResult.getResponse().getContentAsString());
    String sessionId = tokenResponse.get("sessionId").asText();
    String nonce = tokenResponse.get("nonce").asText();
    String scanJws =
        stubUqudoClient.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT, null, sessionId, nonce, identityNumber, false);

    Map<String, Object> scanBody = new LinkedHashMap<>();
    scanBody.put("profileId", profileId);
    scanBody.put("sessionId", sessionId);
    scanBody.put("nonce", nonce);
    scanBody.put("documentType", "passport");
    scanBody.put("jws", scanJws);
    mockMvc
        .perform(
            post("/api/v1/identity-scan/scan-result")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(scanBody)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registryReady").value(true));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/registry-review/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isOk());

    MvcResult faceTokenResult =
        mockMvc
            .perform(
                post("/api/v1/liveness/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"profileId\":\"" + profileId + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    String faceSessionId =
        objectMapper
            .readTree(faceTokenResult.getResponse().getContentAsString())
            .get("faceSessionId")
            .asText();
    String faceJws = stubUqudoClient.fabricateFaceJws(faceSessionId, true, 5);

    Map<String, Object> faceBody = new LinkedHashMap<>();
    faceBody.put("profileId", profileId);
    faceBody.put("faceSessionId", faceSessionId);
    faceBody.put("jws", faceJws);
    mockMvc
        .perform(
            post("/api/v1/liveness/result")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(faceBody)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passed").value(true));

    return profileId;
  }
}
