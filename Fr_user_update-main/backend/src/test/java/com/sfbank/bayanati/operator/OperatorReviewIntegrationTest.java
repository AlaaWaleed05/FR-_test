package com.sfbank.bayanati.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * S4-01: approve/reject and re-approving a rejected profile (operator.md "Review"). Branch 16,
 * accounts 0000000451–0000000465 (see {@link AbstractPostgresIntegrationTest}).
 */
@Tag("integration")
@SpringBootTest
// S4-05: this is the canonical copy of this note (the three other operator integration tests
// point here; the original holder, ManualCompletionIntegrationTest, was deleted by AD-022 at
// S9-01) -- filters disabled here so this
// class stays scoped to approve/reject business logic via direct identity injection;
// the real-identity and rename-safety proofs live in
// auth/OperatorAuthenticationIntegrationTest, which does NOT disable filters.
@AutoConfigureMockMvc(addFilters = false)
class OperatorReviewIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String BRANCH = "16";
  private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StubUqudoClient stubUqudoClient;

  @MockitoSpyBean private MessageSender messageSender;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @Test
  void approveSucceedsAndNotifiesOnlyVerifiedChannels() throws Exception {
    String profileId = readyToSubmitProfile("0000000451", "+249900004511", "IDN-0000000451");
    submit(profileId);

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/operator/profiles/" + profileId + "/approve")
                    .with(
                        OperatorProfileListIntegrationTest.operatorIdentity(
                            "op-approve-1", OperatorAccessLevel.OPERATOR)))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertEquals("approved", body.get("status").asText());
    assertFalse(body.get("alreadyDone").asBoolean());
    assertEquals(1, body.get("notifiedChannels").size());
    assertEquals("sms", body.get("notifiedChannels").get(0).asText());

    assertEquals("approved", currentStatus(profileId));

    Map<String, Object> historyRow =
        jdbcTemplate.queryForMap(
            "SELECT from_status, actor_kind, actor_id FROM app.profile_status_history"
                + " WHERE profile_id = ?::uuid AND to_status = 'approved'",
            profileId);
    assertEquals("submitted", historyRow.get("from_status"));
    assertEquals("operator", historyRow.get("actor_kind"));
    assertEquals("op-approve-1", historyRow.get("actor_id"));

    Long auditEvents =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit.audit_event ae"
                + " JOIN audit.audit_chain ac ON ac.chain_id = ae.chain_id"
                + " WHERE ac.chain_kind = 'profile' AND ac.subject_id = ? AND ae.event_type = 'profile_approved'",
            Long.class,
            profileId);
    assertEquals(1L, auditEvents);

    // Two rows by now: the "received" notification from submit() above, and this approve's own --
    // both target sms, the only verified channel.
    List<Map<String, Object>> outboxRows =
        jdbcTemplate.queryForList(
            "SELECT channel FROM app.notification_outbox WHERE profile_id = ?::uuid", profileId);
    assertEquals(2, outboxRows.size());
    assertEquals("sms", outboxRows.get(0).get("channel"));
    assertEquals("sms", outboxRows.get(1).get("channel"));
    keepOutboxRowsUnclaimable(profileId);

    // Idempotent re-call: no second history row, no second notification.
    MvcResult second =
        mockMvc
            .perform(
                post("/api/v1/operator/profiles/" + profileId + "/approve")
                    .with(
                        OperatorProfileListIntegrationTest.operatorIdentity(
                            "op-approve-1", OperatorAccessLevel.OPERATOR)))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
    assertTrue(secondBody.get("alreadyDone").asBoolean());
    assertTrue(secondBody.get("notifiedChannels").isEmpty());
    Long outboxCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.notification_outbox WHERE profile_id = ?::uuid",
            Long.class,
            profileId);
    assertEquals(2L, outboxCount, "the idempotent re-call did not enqueue a further notification");
  }

  @Test
  void anAdminsApproveIsDistinguishableFromAnOperatorsInTheAuditChain() throws Exception {
    // R-054's compensating control, asserted against the real append-only table. AD-013 lets one
    // account act on a profile and then approve it unaided (until AD-022 that included manually
    // completing it), and the audit trail is the SOLE
    // remaining control. Both actors write actor_kind='operator' and an actor_id UUID, so without
    // actorRole in the payload the two approvals are indistinguishable in the chain -- answerable
    // only by joining to app.operator_user.role, which is mutable and therefore testifies about
    // now rather than about the moment of the action.
    String byOperator = readyToSubmitProfile("0000000461", "+249900004611", "IDN-0000000461");
    submit(byOperator);
    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + byOperator + "/approve")
                .with(
                    OperatorProfileListIntegrationTest.operatorIdentity(
                        "op-role-1", OperatorAccessLevel.OPERATOR, "operator")))
        .andExpect(status().isOk());
    // Required after every SUCCESSFUL approve -- see submit()'s Javadoc. submit() backdates only
    // the rows existing at submit time, and an approve enqueues a further one; left claimable it
    // is picked up by NotificationOutboxIntegrationTest's unscoped CLAIM_ONE_PENDING.
    keepOutboxRowsUnclaimable(byOperator);

    String byAdmin = readyToSubmitProfile("0000000462", "+249900004621", "IDN-0000000462");
    submit(byAdmin);
    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + byAdmin + "/approve")
                .with(
                    OperatorProfileListIntegrationTest.operatorIdentity(
                        "admin-role-1", OperatorAccessLevel.OPERATOR, "admin")))
        .andExpect(status().isOk());
    keepOutboxRowsUnclaimable(byAdmin);

    assertEquals("operator", approvedActorRole(byOperator));
    assertEquals("admin", approvedActorRole(byAdmin));
  }

  /** Reads actorRole straight out of the hash-chained payload, not out of any mutable join. */
  private String approvedActorRole(String profileId) {
    return jdbcTemplate.queryForObject(
        "SELECT ae.payload->>'actorRole' FROM audit.audit_event ae"
            + " JOIN audit.audit_chain ac ON ac.chain_id = ae.chain_id"
            + " WHERE ac.chain_kind = 'profile' AND ac.subject_id = ?"
            + " AND ae.event_type = 'profile_approved'",
        String.class,
        profileId);
  }

  @Test
  void rejectWithValidCodeSucceedsAndSendsTheCustomerFacingMessage() throws Exception {
    String profileId = readyToSubmitProfile("0000000452", "+249900004521", "IDN-0000000452");
    submit(profileId);

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/operator/profiles/" + profileId + "/reject")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reasonCode\":\"REJ-01\"}")
                    .with(
                        OperatorProfileListIntegrationTest.operatorIdentity(
                            "op-reject-1", OperatorAccessLevel.OPERATOR)))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertEquals("rejected", body.get("status").asText());
    assertEquals(1, body.get("notifiedChannels").size());
    keepOutboxRowsUnclaimable(profileId);

    assertEquals("rejected", currentStatus(profileId));
    Map<String, Object> historyRow =
        jdbcTemplate.queryForMap(
            "SELECT reason_code, reason_version FROM app.profile_status_history"
                + " WHERE profile_id = ?::uuid AND to_status = 'rejected'",
            profileId);
    assertEquals("REJ-01", historyRow.get("reason_code"));

    // The most recent of the two rows now on this profile -- submit() already enqueued its own
    // "received" notification before reject() enqueued this one.
    Map<String, Object> outboxRow =
        jdbcTemplate.queryForMap(
            "SELECT payload FROM app.notification_outbox WHERE profile_id = ?::uuid"
                + " ORDER BY created_at DESC LIMIT 1",
            profileId);
    String payload = outboxRow.get("payload").toString();
    // REJ-01's Arabic customer-facing message (V0019 extra.customerMessageAr), not its English
    // internal reason ("Document images illegible or poor quality") -- customer notifications are
    // Arabic-only throughout this codebase (SubmissionMessageRenderer follows the same rule).
    assertTrue(
        payload.contains("واضحة"),
        "the REJ-01 Arabic customer-facing message, not the internal reason");
  }

  @Test
  void rejectWithoutAReasonCodeIsRejected() throws Exception {
    String profileId = readyToSubmitProfile("0000000453", "+249900004531", "IDN-0000000453");
    submit(profileId);

    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + profileId + "/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .with(
                    OperatorProfileListIntegrationTest.operatorIdentity(
                        "op-reject-2", OperatorAccessLevel.OPERATOR)))
        .andExpect(status().isBadRequest());
    assertEquals(
        "submitted", currentStatus(profileId), "a rejected-for-bad-input call writes nothing");
  }

  @Test
  void rejectWithAnUnknownReasonCodeIsRejected() throws Exception {
    String profileId = readyToSubmitProfile("0000000454", "+249900004541", "IDN-0000000454");
    submit(profileId);

    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + profileId + "/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"REJ-99\"}")
                .with(
                    OperatorProfileListIntegrationTest.operatorIdentity(
                        "op-reject-3", OperatorAccessLevel.OPERATOR)))
        .andExpect(status().isBadRequest());
    assertEquals("submitted", currentStatus(profileId));
  }

  @Test
  void rej07WithoutInternalDetailIsRejected() throws Exception {
    String profileId = readyToSubmitProfile("0000000455", "+249900004551", "IDN-0000000455");
    submit(profileId);

    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + profileId + "/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"REJ-07\"}")
                .with(
                    OperatorProfileListIntegrationTest.operatorIdentity(
                        "op-reject-4", OperatorAccessLevel.OPERATOR)))
        .andExpect(status().isBadRequest());
    assertEquals("submitted", currentStatus(profileId));

    // REJ-07 WITH the mandatory internal detail succeeds.
    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + profileId + "/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"REJ-07\",\"internalNote\":\"forged watermark\"}")
                .with(
                    OperatorProfileListIntegrationTest.operatorIdentity(
                        "op-reject-4", OperatorAccessLevel.OPERATOR)))
        .andExpect(status().isOk());
    assertEquals("rejected", currentStatus(profileId));
    keepOutboxRowsUnclaimable(profileId);
  }

  /**
   * BL-154. REJ-03 «فشل أو عدم وضوح مطابقة الوجه» was withdrawn by the product owner on 2026-09-16
   * (V0074), because AD-022 ruling 3 removed the on-screen evidence for it — an operator could cite
   * a face-match failure they can no longer see, and the dashboard would aggregate it as though it
   * had been observed.
   *
   * <p>The withdrawal is {@code is_active = false}, and {@code JdbcReferenceCatalog.EXISTS} filters
   * that flag, so {@code validateReasonCode} refuses it exactly as it refuses an invented code. The
   * profile stays {@code submitted}: a refused reason must not half-reject anyone.
   *
   * <p>That the code is still RESOLVABLE for profiles already rejected under it is proved
   * separately, in {@code JdbcReferenceCatalogTest} — this asserts only that it can no longer be
   * chosen.
   */
  @Test
  void theWithdrawnFaceMatchReasonIsRefused() throws Exception {
    String profileId = readyToSubmitProfile("0000000463", "+249900004631", "IDN-0000000463");
    submit(profileId);

    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + profileId + "/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"REJ-03\"}")
                .with(
                    OperatorProfileListIntegrationTest.operatorIdentity(
                        "op-reject-rej03", OperatorAccessLevel.OPERATOR)))
        .andExpect(status().isBadRequest());
    assertEquals("submitted", currentStatus(profileId));

    // A surviving reason on the same profile still works, so the refusal is about REJ-03 and not
    // about this profile or this operator.
    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + profileId + "/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"REJ-01\"}")
                .with(
                    OperatorProfileListIntegrationTest.operatorIdentity(
                        "op-reject-rej03", OperatorAccessLevel.OPERATOR)))
        .andExpect(status().isOk());
    assertEquals("rejected", currentStatus(profileId));
    keepOutboxRowsUnclaimable(profileId);
  }

  @Test
  void rejectedProfileCanLaterBeApproved() throws Exception {
    String profileId = readyToSubmitProfile("0000000456", "+249900004561", "IDN-0000000456");
    submit(profileId);

    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + profileId + "/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"REJ-01\"}")
                .with(
                    OperatorProfileListIntegrationTest.operatorIdentity(
                        "op-reject-5", OperatorAccessLevel.OPERATOR)))
        .andExpect(status().isOk());
    keepOutboxRowsUnclaimable(profileId);
    assertEquals("rejected", currentStatus(profileId));

    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + profileId + "/approve")
                .with(
                    OperatorProfileListIntegrationTest.operatorIdentity(
                        "op-approve-5", OperatorAccessLevel.OPERATOR)))
        .andExpect(status().isOk());
    keepOutboxRowsUnclaimable(profileId);
    assertEquals("approved", currentStatus(profileId));

    Map<String, Object> historyRow =
        jdbcTemplate.queryForMap(
            "SELECT from_status FROM app.profile_status_history WHERE profile_id = ?::uuid AND to_status = 'approved'",
            profileId);
    assertEquals("rejected", historyRow.get("from_status"));
  }

  /**
   * AD-013 (2026-09-13) removed the four-eyes rule. This test was its end-to-end proof and is
   * INVERTED rather than deleted, so the new behaviour is pinned rather than merely unasserted.
   *
   * <p>The raw-SQL block that used to sit here -- a verbatim copy of V0009's conditional UPDATE,
   * asserting it refused the write with no service layer involved -- is DELETED rather than
   * inverted. Dropping its NOT EXISTS conjunct would have made it actually approve the profile
   * behind the service layer, after which every assertion following it would have been passing
   * against an idempotent already-approved response instead of a real approve.
   */
  @Test
  void theOperatorWhoManuallyCompletedAProfileMayNowApproveIt() throws Exception {
    String profileId = readyToSubmitProfile("0000000457", "+249900004571", "IDN-0000000457");
    submit(profileId);
    seedManualCompletionHistoryRow(profileId, "op-manual-completer");

    // The manual completer's OWN approve now succeeds -- this is the whole of AD-013.
    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + profileId + "/approve")
                .with(
                    OperatorProfileListIntegrationTest.operatorIdentity(
                        "op-manual-completer", OperatorAccessLevel.OPERATOR)))
        .andExpect(status().isOk());
    keepOutboxRowsUnclaimable(profileId);
    assertEquals("approved", currentStatus(profileId));

    // ...and nothing was recorded as a refusal.
    Long refusedEvents =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit.audit_event ae"
                + " JOIN audit.audit_chain ac ON ac.chain_id = ae.chain_id"
                + " WHERE ac.chain_kind = 'profile' AND ac.subject_id = ?"
                + " AND ae.event_type = 'profile_approve_refused' AND ae.actor_id = ?",
            Long.class,
            profileId,
            "op-manual-completer");
    assertEquals(0L, refusedEvents);

    // The column survives the rule: is_manual_completion is still READABLE, and this row is seeded
    // by raw SQL exactly as a pre-AD-022 profile's row still reads today.
    //
    // Corrected 2026-09-16 (S9-01): this comment used to say "is still WRITTEN ... that is what
    // makes AD-013 reversible without a migration". AD-022 deleted manual completion, so nothing
    // writes the column any more and that reversibility claim is FALSE -- restoring four-eyes would
    // now need a writer as well as the predicate. The column is kept because it is how profiles
    // completed before the ruling stay distinguishable in the history timeline, which is a
    // different and still-valid reason. R-054 carries the corrected reversal cost.
    Long manualRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.profile_status_history"
                + " WHERE profile_id = ?::uuid AND is_manual_completion AND actor_id = ?",
            Long.class,
            profileId,
            "op-manual-completer");
    assertEquals(
        1L, manualRows, "the legacy manual-completion history row must survive AD-013 and AD-022");

    // canApprove is gone from the wire entirely -- not always-true, absent.
    MvcResult viewResult =
        mockMvc
            .perform(
                get("/api/v1/operator/profiles/" + profileId)
                    .with(
                        OperatorProfileListIntegrationTest.operatorIdentity(
                            "op-manual-completer", OperatorAccessLevel.OPERATOR)))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode viewBody = objectMapper.readTree(viewResult.getResponse().getContentAsString());
    assertNull(
        viewBody.get("canApprove"), "canApprove must be absent from the response, not false");
  }

  /**
   * {@code profile_approve_refused} outlives the four-eyes rule that justified it (whether it
   * should is BL-133's call), and after the inversion above nothing else exercises it. This pins
   * the one refusal arm AD-013 leaves standing: an ineligible status.
   */
  @Test
  void approvingAnIneligibleStatusIsRefusedAndRecorded() throws Exception {
    String profileId = createProfileWithVerifiedSms("0000000460", "+249900004601");

    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + profileId + "/approve")
                .with(
                    OperatorProfileListIntegrationTest.operatorIdentity(
                        "op-too-early", OperatorAccessLevel.OPERATOR)))
        .andExpect(status().isConflict());
    assertEquals("in_progress", currentStatus(profileId));

    Long refusedEvents =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit.audit_event ae"
                + " JOIN audit.audit_chain ac ON ac.chain_id = ae.chain_id"
                + " WHERE ac.chain_kind = 'profile' AND ac.subject_id = ?"
                + " AND ae.event_type = 'profile_approve_refused' AND ae.actor_id = ?"
                + " AND ae.payload->>'reason' = 'profile_not_reviewable'",
            Long.class,
            profileId,
            "op-too-early");
    assertEquals(1L, refusedEvents);
  }

  @Test
  void viewerCannotApproveOrReject() throws Exception {
    String profileId = readyToSubmitProfile("0000000458", "+249900004581", "IDN-0000000458");
    submit(profileId);

    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + profileId + "/approve")
                .with(
                    OperatorProfileListIntegrationTest.operatorIdentity(
                        "op-viewer-only", OperatorAccessLevel.VIEWER)))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + profileId + "/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"REJ-01\"}")
                .with(
                    OperatorProfileListIntegrationTest.operatorIdentity(
                        "op-viewer-only", OperatorAccessLevel.VIEWER)))
        .andExpect(status().isForbidden());
    assertEquals("submitted", currentStatus(profileId));
  }

  @Test
  void approveAndRejectWithNoOperatorIdentityAre401() throws Exception {
    String profileId = readyToSubmitProfile("0000000459", "+249911005911", "IDN-0000000459");
    submit(profileId);

    mockMvc
        .perform(post("/api/v1/operator/profiles/" + profileId + "/approve"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + profileId + "/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"REJ-01\"}"))
        .andExpect(status().isUnauthorized());
    assertEquals("submitted", currentStatus(profileId));
  }

  // ---- helpers ----

  private String currentStatus(String profileId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM app.profile WHERE profile_id = ?::uuid", String.class, profileId);
  }

  private void keepOutboxRowsUnclaimable(String profileId) {
    jdbcTemplate.update(
        "UPDATE app.notification_outbox SET next_attempt_at = now() + interval '1 hour' WHERE profile_id = ?::uuid",
        profileId);
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
   * Also backdates the "received" notification submit() itself enqueues, immediately -- every
   * caller in this class goes on to attempt (successfully or not) an approve/reject afterward, and
   * an un-backdated row is claimable by {@code NotificationOutboxIntegrationTest}'s unscoped {@code
   * claimOnePending()} poll for the rest of the whole suite run, found under review (see also
   * {@link #keepOutboxRowsUnclaimable}'s call sites after every SUCCESSFUL approve/reject below --
   * both are needed, since a refused approve/reject never enqueues a second row for this call to
   * catch).
   */
  private void submit(String profileId) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/submission")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isOk());
    keepOutboxRowsUnclaimable(profileId);
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
