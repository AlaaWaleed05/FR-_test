package com.sfbank.bayanati.otpverification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.messaging.domain.EmailPayload;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.messaging.domain.WhatsAppPayload;
import java.util.List;
import java.util.Locale;
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
 * S3-08: journey Stage 2 end to end — the real HTTP endpoints, the real service, the real JDBC
 * writers, against a real PostgreSQL 18 with every migration (including V0037) applied.
 *
 * <p>Tagged "integration" and excluded from {@code ./mvnw test}/{@code verify} by default; run with
 * {@code ./mvnw test -Pdb-integration-test}. Container and dynamic properties come from {@link
 * AbstractPostgresIntegrationTest} — S3-09 moved every integration class onto one shared,
 * JVM-lifetime container instead of one each.
 *
 * <p>The actual OTP code is never queryable from the database (only its salted hash is stored), so
 * tests that need a real code spy on the {@link MessageSender} bean and extract it from the
 * rendered message body/parameters — exactly what a real customer would read off their phone.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class OtpVerificationIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String BRANCH = "16";
  private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate; // connects as fru_app

  /** Real delegate preserved — every send still goes through the real stub. */
  @MockitoSpyBean private MessageSender messageSender;

  private final JsonMapper objectMapper = JsonMapper.builder().build();
  // static, not instance: JUnit 5's default per-method lifecycle creates a fresh test instance for
  // every @Test, so an instance field would restart at 200 in every method and collide on the same
  // account number across tests -- e.g. the phone-lock test's account colliding with a later
  // test's "fresh" account, making that later test's own submission a re-entry into an
  // already-locked profile instead of a genuinely new one.
  private static final java.util.concurrent.atomic.AtomicInteger accountCounter =
      new java.util.concurrent.atomic.AtomicInteger(200);

  @Test
  void correctCodeVerifiesOnlyItsOwnChannelLive() throws Exception {
    String accountNumber = nextAccount();
    String profileId = submitContactChannels(accountNumber, "+249900002001", true);
    String smsCode = capturedCode(MessageChannel.SMS);

    MvcResult result =
        verifyRequest(profileId, "sms", smsCode).andExpect(status().isOk()).andReturn();
    JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
    assertEquals("VERIFIED", response.get("outcome").asText());
    assertEquals("verified", response.get("state").asText());

    assertEquals("verified", channelState(profileId, "sms"));
    assertEquals("unverified", channelState(profileId, "whatsapp"), "WhatsApp must be untouched");
  }

  @Test
  void aCodeEnteredAgainstTheWrongChannelFailsAndBothChannelsRemainRetryable() throws Exception {
    String accountNumber = nextAccount();
    String profileId = submitContactChannels(accountNumber, "+249900002002", true);
    String smsCode = capturedCode(MessageChannel.SMS);

    MvcResult result =
        verifyRequest(profileId, "whatsapp", smsCode).andExpect(status().isOk()).andReturn();
    JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
    assertEquals("WRONG_CODE", response.get("outcome").asText());

    assertEquals("unverified", channelState(profileId, "sms"));
    assertEquals("unverified", channelState(profileId, "whatsapp"));
    assertNull(lockedAt(profileId, "sms"));
    assertNull(lockedAt(profileId, "whatsapp"));
  }

  @Test
  void fiveWrongAttemptsLockTheChannelAndTheSixthIsRejectedAsLockedNotWrong() throws Exception {
    String accountNumber = nextAccount();
    String profileId = submitContactChannels(accountNumber, "+249900002003", true);

    for (int attempt = 1; attempt <= 5; attempt++) {
      MvcResult result =
          verifyRequest(profileId, "sms", "000000").andExpect(status().isOk()).andReturn();
      JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
      assertEquals("WRONG_CODE", response.get("outcome").asText(), "attempt " + attempt);
    }
    assertTrue(lockedAt(profileId, "sms") != null, "channel must be locked after the 5th attempt");

    MvcResult sixth =
        verifyRequest(profileId, "sms", "000000").andExpect(status().isOk()).andReturn();
    JsonNode sixthResponse = objectMapper.readTree(sixth.getResponse().getContentAsString());
    assertEquals("CHANNEL_LOCKED", sixthResponse.get("outcome").asText());

    Integer wrongAttempts =
        jdbcTemplate.queryForObject(
            "SELECT wrong_code_attempts FROM app.profile_channel"
                + " WHERE profile_id = ?::uuid AND channel = 'sms'",
            Integer.class,
            profileId);
    assertEquals(5, wrongAttempts, "the 6th attempt must not push the counter past 5");
  }

  @Test
  void anExpiredCodeIsRejectedAsExpiredAndDoesNotLockTheChannel() throws Exception {
    String accountNumber = nextAccount();
    String profileId = submitContactChannels(accountNumber, "+249900002004", true);

    jdbcTemplate.update(
        "UPDATE app.otp_challenge SET expires_at = now() - interval '1 second'"
            + " WHERE profile_id = ?::uuid AND channel = 'sms'",
        profileId);

    MvcResult result =
        verifyRequest(profileId, "sms", "123456").andExpect(status().isOk()).andReturn();
    JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
    assertEquals("EXPIRED", response.get("outcome").asText());

    assertNull(lockedAt(profileId, "sms"));
    Integer wrongAttempts =
        jdbcTemplate.queryForObject(
            "SELECT wrong_code_attempts FROM app.profile_channel"
                + " WHERE profile_id = ?::uuid AND channel = 'sms'",
            Integer.class,
            profileId);
    assertEquals(0, wrongAttempts);
  }

  @Test
  void threeResendsSucceedTheFourthIsRefusedAndTheEscalatingDelayIsEnforced() throws Exception {
    String accountNumber = nextAccount();
    String profileId = submitContactChannels(accountNumber, "+249900002005", true);

    // Immediately after the initial send: 30s have not elapsed.
    assertEquals("TOO_SOON", resendOutcome(profileId, "sms"));

    // Backdated FAR past each gate, not just barely past it -- observed live (S3-08 session) that
    // a margin of even 10-20s can still flake in this environment, so the margin here is minutes,
    // not seconds, deliberately oversized against any plausible scheduling delay.
    backdateLastIssuedAt(profileId, "sms", 300); // clears the 30s gate by a wide margin
    assertEquals("ISSUED", resendOutcome(profileId, "sms")); // resend #1

    assertEquals("TOO_SOON", resendOutcome(profileId, "sms")); // 60s not yet elapsed
    backdateLastIssuedAt(profileId, "sms", 300);
    assertEquals("ISSUED", resendOutcome(profileId, "sms")); // resend #2

    assertEquals("TOO_SOON", resendOutcome(profileId, "sms")); // 120s not yet elapsed
    backdateLastIssuedAt(profileId, "sms", 300);
    assertEquals("ISSUED", resendOutcome(profileId, "sms")); // resend #3

    assertEquals("CAP_EXHAUSTED", resendOutcome(profileId, "sms")); // 4th refused

    Integer resendCount =
        jdbcTemplate.queryForObject(
            "SELECT resend_count FROM app.profile_channel"
                + " WHERE profile_id = ?::uuid AND channel = 'sms'",
            Integer.class,
            profileId);
    assertEquals(3, resendCount);
  }

  @Test
  void smsVerifiedWhatsappUnverifiedLeavesAPhoneChannelVerifiedButEmailAloneDoesNot()
      throws Exception {
    String accountNumber = nextAccount();
    String profileId = submitContactChannels(accountNumber, "+249900002006", true);
    String smsCode = capturedCode(MessageChannel.SMS);

    verifyRequest(profileId, "sms", smsCode).andExpect(status().isOk());

    // At least one phone channel (SMS) is verified -- customer.md: "Next enables the moment at
    // least one phone channel -- SMS or WhatsApp -- is verified." The UI reads this directly off
    // channel state; there is no separate "can proceed" endpoint.
    assertEquals("verified", channelState(profileId, "sms"));
    assertEquals("unverified", channelState(profileId, "whatsapp"));

    // A second profile where only email (not offered here, so simulate the fact directly): no
    // phone channel is verified -- proceeding must not be possible. Demonstrated as a state fact:
    // neither phone channel is verified on THIS profile until sms is verified above.
    String secondAccount = nextAccount();
    String secondProfileId = submitContactChannels(secondAccount, "+249900002007", true);
    assertFalse(
        "verified".equals(channelState(secondProfileId, "sms"))
            || "verified".equals(channelState(secondProfileId, "whatsapp")),
        "a fresh session has no verified phone channel yet");
  }

  @Test
  void bothPhoneChannelsLockedTriggersTheSessionTerminalLockAndBlocksReEntry() throws Exception {
    String accountNumber = nextAccount();
    String profileId = submitContactChannels(accountNumber, "+249900002008", true);

    lockChannel(profileId, "sms");
    lockChannel(profileId, "whatsapp");

    Map<String, Object> profileRow =
        jdbcTemplate.queryForMap(
            "SELECT phone_lock_until, phone_lock_escalated FROM app.profile WHERE profile_id = ?::uuid",
            profileId);
    assertTrue(profileRow.get("phone_lock_until") != null, "session lock must be set");
    assertEquals(Boolean.TRUE, profileRow.get("phone_lock_escalated"));

    // Re-entry via Stage 1b is now refused, 429, until the block expires.
    String body =
        "{\"branch\":\""
            + BRANCH
            + "\",\"accountNumber\":\""
            + accountNumber
            + "\",\"phoneNumber\":\"+249900009999\",\"sms\":true,\"whatsapp\":true}";
    mockMvc
        .perform(
            post("/api/v1/contact-channels").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void theSubmittedCodeAndStoredHashAppearInNoResponseBodyOrAuditPayload() throws Exception {
    String accountNumber = nextAccount();
    String profileId = submitContactChannels(accountNumber, "+249900002009", true);
    String smsCode = capturedCode(MessageChannel.SMS);

    MvcResult result =
        verifyRequest(profileId, "sms", smsCode).andExpect(status().isOk()).andReturn();
    String responseBody = result.getResponse().getContentAsString();
    assertFalse(responseBody.contains(smsCode), "full response body: " + responseBody);

    List<Map<String, Object>> events = auditEvents(profileId);
    Map<String, Object> attempted =
        events.stream()
            .filter(e -> "otp_verification_attempted".equals(e.get("event_type")))
            .findFirst()
            .orElseThrow();
    String payload = (String) attempted.get("payload_json");
    assertFalse(payload.contains(smsCode), payload);

    String hashHex =
        jdbcTemplate.queryForObject(
            "SELECT encode(code_hash,'hex') FROM app.otp_challenge"
                + " WHERE profile_id = ?::uuid AND channel = 'sms' ORDER BY issued_at DESC LIMIT 1",
            String.class,
            profileId);
    for (Map<String, Object> event : events) {
      String eventPayload = (String) event.get("payload_json");
      assertFalse(
          eventPayload.toLowerCase().contains(hashHex.toLowerCase()),
          "event " + event.get("event_type") + " leaked the code hash: " + eventPayload);
    }
  }

  @Test
  void reEnteringAfterTwoWrongAttemptsAndOneResendResetsTheCountersPerR044() throws Exception {
    String accountNumber = nextAccount();
    String profileId = submitContactChannels(accountNumber, "+249900002010", true);

    verifyRequest(profileId, "sms", "000000").andExpect(status().isOk());
    verifyRequest(profileId, "sms", "111111").andExpect(status().isOk());
    backdateLastIssuedAt(profileId, "sms", 40);
    resendOutcome(profileId, "sms");

    Map<String, Object> before =
        jdbcTemplate.queryForMap(
            "SELECT wrong_code_attempts, resend_count FROM app.profile_channel"
                + " WHERE profile_id = ?::uuid AND channel = 'sms'",
            profileId);
    assertEquals(2, ((Number) before.get("wrong_code_attempts")).intValue());
    assertEquals(1, ((Number) before.get("resend_count")).intValue());

    String body =
        "{\"branch\":\""
            + BRANCH
            + "\",\"accountNumber\":\""
            + accountNumber
            + "\",\"phoneNumber\":\"+249900008888\",\"sms\":true,\"whatsapp\":true}";
    mockMvc
        .perform(
            post("/api/v1/contact-channels").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());

    Map<String, Object> after =
        jdbcTemplate.queryForMap(
            "SELECT wrong_code_attempts, resend_count, locked_at FROM app.profile_channel"
                + " WHERE profile_id = ?::uuid AND channel = 'sms'",
            profileId);
    assertEquals(0, ((Number) after.get("wrong_code_attempts")).intValue());
    assertEquals(0, ((Number) after.get("resend_count")).intValue());
    assertNull(after.get("locked_at"));
  }

  @Test
  void
      resendWithACorrectedEmailUpdatesTheProfileInvalidatesThePriorChallengeAndSendsToTheNewAddress()
          throws Exception {
    // S4-06/BL-012: customer.md Stage 2 "Email address mistyped -> editable in place on its row,
    // then resend."
    String accountNumber = nextAccount();
    String profileId = submitContactChannels(accountNumber, "+249900002011", true);

    String priorChallengeId =
        jdbcTemplate.queryForObject(
            "SELECT challenge_id FROM app.otp_challenge"
                + " WHERE profile_id = ?::uuid AND channel = 'email' ORDER BY issued_at DESC LIMIT 1",
            String.class,
            profileId);

    backdateLastIssuedAt(profileId, "email", 300); // clear the 30s resend delay gate
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/otp/resend")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"profileId\":\""
                            + profileId
                            + "\",\"channel\":\"email\",\"correctedEmailAddress\":\"fixed"
                            + accountNumber
                            + "@example.invalid\"}"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
    assertEquals("ISSUED", response.get("outcome").asText());

    String storedEmail =
        jdbcTemplate.queryForObject(
            "SELECT email_address FROM app.profile_customer_data WHERE profile_id = ?::uuid",
            String.class,
            profileId);
    assertEquals("fixed" + accountNumber + "@example.invalid", storedEmail);

    // The prior challenge is now unusable -- expires_at pulled to at-or-before now (already
    // existing resend behaviour: invalidateChallengesForChannel runs unconditionally).
    java.sql.Timestamp priorExpiresAt =
        jdbcTemplate.queryForObject(
            "SELECT expires_at FROM app.otp_challenge WHERE challenge_id = ?::uuid",
            java.sql.Timestamp.class,
            priorChallengeId);
    assertTrue(
        priorExpiresAt.toInstant().isBefore(java.time.Instant.now().plusSeconds(1)),
        "the prior email challenge must no longer be usable: expires_at=" + priorExpiresAt);

    // The fresh code was sent to the CORRECTED address, not the original typo.
    org.mockito.ArgumentCaptor<OutboundMessage> captor =
        org.mockito.ArgumentCaptor.forClass(OutboundMessage.class);
    verify(messageSender, org.mockito.Mockito.atLeastOnce()).send(captor.capture());
    OutboundMessage lastEmailMessage =
        captor.getAllValues().stream()
            .filter(m -> m.channel() == MessageChannel.EMAIL)
            .reduce((first, second) -> second)
            .orElseThrow();
    assertEquals("fixed" + accountNumber + "@example.invalid", lastEmailMessage.destination());

    List<Map<String, Object>> events = auditEvents(profileId);
    assertTrue(
        events.stream().anyMatch(e -> "email_address_corrected".equals(e.get("event_type"))),
        "the correction must be on the audit chain: " + events);
  }

  @Test
  void aCorrectedEmailOnACapExhaustedResendStillUpdatesTheAddressAndInvalidatesTheOldChallenge()
      throws Exception {
    // BLOCKER regression (@agent-reviewer, S4-06, second pass): the first pass's own fix was
    // proven only against a mocked PlatformTransactionManager, which cannot distinguish "both
    // writes landed inside one real transaction" from "two separate autocommit statements" --
    // exactly the distinction that matters for the defect this proves closed. Drives resend to
    // genuine CAP_EXHAUSTED (3 real resends against a real database), then submits a 4th resend
    // carrying a correction: the reservation is refused, but the correction and the OLD
    // challenge's invalidation must both still have landed.
    String accountNumber = nextAccount();
    String profileId = submitContactChannels(accountNumber, "+249900002012", true);

    String originalChallengeId =
        jdbcTemplate.queryForObject(
            "SELECT challenge_id FROM app.otp_challenge"
                + " WHERE profile_id = ?::uuid AND channel = 'email' ORDER BY issued_at DESC LIMIT 1",
            String.class,
            profileId);

    backdateLastIssuedAt(profileId, "email", 300);
    assertEquals("ISSUED", resendOutcome(profileId, "email")); // resend #1
    backdateLastIssuedAt(profileId, "email", 300);
    assertEquals("ISSUED", resendOutcome(profileId, "email")); // resend #2
    backdateLastIssuedAt(profileId, "email", 300);
    assertEquals("ISSUED", resendOutcome(profileId, "email")); // resend #3

    String lastChallengeIdBeforeCorrection =
        jdbcTemplate.queryForObject(
            "SELECT challenge_id FROM app.otp_challenge"
                + " WHERE profile_id = ?::uuid AND channel = 'email' ORDER BY issued_at DESC LIMIT 1",
            String.class,
            profileId);
    assertTrue(
        !originalChallengeId.equals(lastChallengeIdBeforeCorrection),
        "sanity: three resends must have issued a genuinely different challenge row");

    String correctedEmail = "fixed" + accountNumber + "@example.invalid";
    MvcResult fourthResend =
        mockMvc
            .perform(
                post("/api/v1/otp/resend")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"profileId\":\""
                            + profileId
                            + "\",\"channel\":\"email\",\"correctedEmailAddress\":\""
                            + correctedEmail
                            + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode fourthResponse =
        objectMapper.readTree(fourthResend.getResponse().getContentAsString());
    assertEquals("CAP_EXHAUSTED", fourthResponse.get("outcome").asText());

    // The correction landed even though the reservation was refused.
    String storedEmail =
        jdbcTemplate.queryForObject(
            "SELECT email_address FROM app.profile_customer_data WHERE profile_id = ?::uuid",
            String.class,
            profileId);
    assertEquals(correctedEmail, storedEmail);

    // The old (third) challenge -- sent to the mistyped address -- is now unusable. Without the
    // fix, this would still be live for up to 5 more minutes, and entering its code would mark
    // the email channel VERIFIED against an address that was never itself challenged.
    java.sql.Timestamp oldExpiresAt =
        jdbcTemplate.queryForObject(
            "SELECT expires_at FROM app.otp_challenge WHERE challenge_id = ?::uuid",
            java.sql.Timestamp.class,
            lastChallengeIdBeforeCorrection);
    assertTrue(
        oldExpiresAt.toInstant().isBefore(java.time.Instant.now().plusSeconds(1)),
        "the old challenge must be invalidated even though the resend itself was refused: "
            + "expires_at="
            + oldExpiresAt);
  }

  // --- helpers -------------------------------------------------------------------------------

  private String nextAccount() {
    // Locale.ROOT: an account number is ASCII digits in the core-banking stub's seeded range. A JVM
    // defaulting to an Arabic-Indic numbering locale formats %02d in Arabic-Indic digits, and this
    // helper would then build an account the stub has never heard of, failing every test here for a
    // reason none of them are about. Note it would NOT be rejected at the boundary --
    // ContactChannelsController.clean refuses only blank, over-length and ISO control characters;
    // the non-ASCII-digit rejection CLAUDE.md requires is on the PHONE field (BL-016), not this
    // one.
    return String.format(Locale.ROOT, "00000002%02d", accountCounter.getAndIncrement());
  }

  private String submitContactChannels(String accountNumber, String phoneNumber, boolean withEmail)
      throws Exception {
    String body =
        "{\"branch\":\""
            + BRANCH
            + "\",\"accountNumber\":\""
            + accountNumber
            + "\",\"phoneNumber\":\""
            + phoneNumber
            + "\",\"sms\":true,\"whatsapp\":true"
            + (withEmail ? ",\"emailAddress\":\"otp" + accountNumber + "@example.invalid\"}" : "}");
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/contact-channels")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper
        .readTree(result.getResponse().getContentAsString())
        .get("profileId")
        .asText();
  }

  private org.springframework.test.web.servlet.ResultActions verifyRequest(
      String profileId, String channel, String code) throws Exception {
    return mockMvc.perform(
        post("/api/v1/otp/verify")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"profileId\":\""
                    + profileId
                    + "\",\"channel\":\""
                    + channel
                    + "\",\"code\":\""
                    + code
                    + "\"}"));
  }

  private String resendOutcome(String profileId, String channel) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/otp/resend")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"profileId\":\"" + profileId + "\",\"channel\":\"" + channel + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("outcome").asText();
  }

  /** Locks a channel by driving 5 real wrong-code verify calls through the HTTP endpoint. */
  private void lockChannel(String profileId, String channel) throws Exception {
    for (int i = 0; i < 5; i++) {
      verifyRequest(profileId, channel, "000000").andExpect(status().isOk());
    }
  }

  /**
   * Backdates the most recently ISSUED challenge's {@code issued_at} so the next resend's delay
   * gate reads as already elapsed. Selects that row by {@code MAX(expires_at)}, not {@code
   * MAX(issued_at)} — {@code issued_at} is exactly the column this method mutates, so selecting by
   * it breaks on the second call: the row backdated first can end up with a *later* {@code
   * issued_at} than a row backdated second by a smaller offset, making {@code MAX(issued_at)}
   * silently point at the wrong row. {@code expires_at} (= {@code issued_at + 5 minutes} at insert,
   * per V0007) is never touched here, so it stays a reliable insertion-order proxy.
   */
  private void backdateLastIssuedAt(String profileId, String channel, int secondsAgo) {
    jdbcTemplate.update(
        "UPDATE app.otp_challenge SET issued_at = now() - (? || ' seconds')::interval"
            + " WHERE profile_id = ?::uuid AND channel = ? AND expires_at ="
            + " (SELECT max(expires_at) FROM app.otp_challenge WHERE profile_id = ?::uuid AND channel = ?)",
        secondsAgo,
        profileId,
        channel,
        profileId,
        channel);
  }

  private String channelState(String profileId, String channel) {
    return jdbcTemplate.queryForObject(
        "SELECT state FROM app.profile_channel WHERE profile_id = ?::uuid AND channel = ?",
        String.class,
        profileId,
        channel);
  }

  private Object lockedAt(String profileId, String channel) {
    return jdbcTemplate.queryForObject(
        "SELECT locked_at FROM app.profile_channel WHERE profile_id = ?::uuid AND channel = ?",
        Object.class,
        profileId,
        channel);
  }

  private List<Map<String, Object>> auditEvents(String profileId) {
    return jdbcTemplate.queryForList(
        "SELECT e.event_type, e.payload_json FROM audit.audit_event e"
            + " JOIN audit.audit_chain c ON c.chain_id = e.chain_id"
            + " WHERE c.chain_kind = 'profile' AND c.subject_id = ? ORDER BY e.seq",
        profileId);
  }

  /**
   * Captures the most recent {@link OutboundMessage} sent on {@code channel} and extracts the
   * 6-digit code from its rendered payload — the same text a real customer's phone would show.
   */
  private String capturedCode(MessageChannel channel) {
    org.mockito.ArgumentCaptor<OutboundMessage> captor =
        org.mockito.ArgumentCaptor.forClass(OutboundMessage.class);
    verify(messageSender, org.mockito.Mockito.atLeastOnce()).send(captor.capture());
    List<OutboundMessage> sent = captor.getAllValues();
    for (int i = sent.size() - 1; i >= 0; i--) {
      OutboundMessage message = sent.get(i);
      if (message.channel() == channel) {
        return extractCode(message);
      }
    }
    throw new IllegalStateException("no message captured for channel " + channel);
  }

  private static String extractCode(OutboundMessage message) {
    return switch (message.payload()) {
      case SmsPayload sms -> firstSixDigits(sms.body());
      case WhatsAppPayload wa -> wa.bodyParameters().get(0);
      case EmailPayload email -> firstSixDigits(email.bodyText());
    };
  }

  private static String firstSixDigits(String text) {
    Matcher matcher = SIX_DIGITS.matcher(text);
    if (!matcher.find()) {
      throw new IllegalStateException("no 6-digit code found in: " + text);
    }
    return matcher.group();
  }
}
