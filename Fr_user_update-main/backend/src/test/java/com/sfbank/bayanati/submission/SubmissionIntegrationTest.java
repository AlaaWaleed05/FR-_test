package com.sfbank.bayanati.submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.uqudo.stub.StubUqudoClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S3-13: journey Stage 12 end to end. Tagged "integration", run with {@code ./mvnw test
 * -Pdb-integration-test}. This class's account-number range -- branch {@code 16}, {@code
 * 0000000430}-{@code 0000000440}, plus {@code 0000000496} for the S4-04 {@code
 * ref.profile_reference_version} proof -- is disjoint from every other integration class's (see
 * {@link AbstractPostgresIntegrationTest}).
 *
 * <p>Enqueued outbox rows are immediately pushed an hour into the future ({@link
 * #keepOutboxRowsUnclaimable}), the same convention {@code NotificationOutboxIntegrationTest}
 * established: {@code JdbcNotificationOutboxRepository.claimOnePending()} polls with no {@code
 * profile_id} predicate, so an un-backdated row this class enqueues would be claimable by whichever
 * other integration class's dispatcher call happens to run next against the one shared container.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class SubmissionIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String BRANCH = "16";
  private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StubUqudoClient stubUqudoClient;
  @Autowired private PlatformTransactionManager transactionManager;

  /** Real delegate preserved -- every send still goes through the real stub. */
  @MockitoSpyBean private MessageSender messageSender;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @Test
  void submissionSetsStatusAssignsReferenceNumberAndEnqueuesOnlyVerifiedChannels()
      throws Exception {
    String profileId = readyToSubmitProfile("0000000430", "+249900004301", "IDN-0000000430");

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/submission")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"profileId\":\"" + profileId + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
    assertEquals("submitted", response.get("status").asText());
    String referenceNumber = response.get("referenceNumber").asText();
    // S8-07: the bank asked for its own initials. Asserting the WHOLE shape, not just the
    // prefix -- a sed that changed the prefix but disturbed the lpad would still pass a
    // startsWith check.
    assertTrue(
        referenceNumber.matches("SFB-\\d{9}"), "expected SFB- + 9 digits, got " + referenceNumber);

    // Ordering: by the time the HTTP response is even parseable, the DB write has already
    // committed (the response can only be built from a returned, already-committed value) --
    // this is exactly the state a dropped connection would leave for the app to reconcile from.
    Map<String, Object> profileRow =
        jdbcTemplate.queryForMap(
            "SELECT status, reference_number, submitted_at FROM app.profile WHERE profile_id = ?::uuid",
            profileId);
    assertEquals("submitted", profileRow.get("status"));
    assertEquals(referenceNumber, profileRow.get("reference_number"));
    assertNotNull(profileRow.get("submitted_at"));

    Map<String, Object> historyRow =
        jdbcTemplate.queryForMap(
            "SELECT from_status, to_status FROM app.profile_status_history"
                + " WHERE profile_id = ?::uuid AND to_status = 'submitted'",
            profileId);
    assertEquals("in_progress", historyRow.get("from_status"));

    // Only the VERIFIED channel (sms) gets a notification -- whatsapp was selected but never
    // OTP-verified in this flow, so it stays 'unverified' and must get nothing.
    List<Map<String, Object>> outboxRows =
        jdbcTemplate.queryForList(
            "SELECT channel, state FROM app.notification_outbox WHERE profile_id = ?::uuid",
            profileId);
    assertEquals(1, outboxRows.size());
    assertEquals("sms", outboxRows.get(0).get("channel"));
    assertEquals(
        "pending",
        outboxRows.get(0).get("state"),
        "enqueued for later dispatch, never sent inline from this request");
    keepOutboxRowsUnclaimable(profileId);

    // Never sent inline: the spy sees no submission-notification SMS body containing the
    // reference number sent DURING this request (only the earlier stage-2 OTP send, if any).
    verify(messageSender, org.mockito.Mockito.never())
        .send(
            org.mockito.ArgumentMatchers.argThat(
                m ->
                    m.channel() == MessageChannel.SMS
                        && m.payload() instanceof SmsPayload sms
                        && sms.body().contains(referenceNumber)));
  }

  @Test
  void resubmissionIsIdempotentAndReturnsTheSameReferenceNumber() throws Exception {
    String profileId = readyToSubmitProfile("0000000431", "+249900004311", "IDN-0000000431");

    MvcResult first =
        mockMvc
            .perform(
                post("/api/v1/submission")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"profileId\":\"" + profileId + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    String firstReference =
        objectMapper
            .readTree(first.getResponse().getContentAsString())
            .get("referenceNumber")
            .asText();
    keepOutboxRowsUnclaimable(profileId);

    MvcResult second =
        mockMvc
            .perform(
                post("/api/v1/submission")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"profileId\":\"" + profileId + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode secondResponse = objectMapper.readTree(second.getResponse().getContentAsString());
    assertEquals(firstReference, secondResponse.get("referenceNumber").asText());
    assertEquals("submitted", secondResponse.get("status").asText());
    assertTrue(
        secondResponse.get("verifiedChannels").isEmpty(),
        "an idempotent re-call enqueues nothing a second time");

    Long outboxCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.notification_outbox WHERE profile_id = ?::uuid",
            Long.class,
            profileId);
    assertEquals(1L, outboxCount, "the resubmission did not enqueue a second notification");
  }

  /**
   * S8-07's generation-point property: an already-issued {@code FRU-} reference number is NEVER
   * rewritten, whatever later write touches the profile.
   *
   * <p><strong>Re-homed here at S9-01.</strong> This property was covered only by {@code
   * ManualCompletionIntegrationTest#referenceNumberIssuedUnderTheOldFruPrefixIsNeverRewritten},
   * which AD-022 deleted along with the rest of manual completion. {@code
   * submission.domain.SubmissionRepository}'s javadoc still asserts the property, so deleting its
   * only test would have left a documented guarantee with nothing behind it -- found by {@code
   * @agent-reviewer} on this session's own diff. The vehicle changes from manual completion to
   * resubmission; the property is identical, and submission is now the ONLY mint site.
   *
   * <p>{@code FRU-000000001} stands in for the S7-12 acceptance-walk profile, whose number a real
   * person is holding and whose value the audit trail records. Writing a literal into a UNIQUE
   * column is safe only because minting now yields {@code SFB-}: were the prefix ever reverted, low
   * {@code reference_number_seq} values could collide and this would fail with a unique violation
   * rather than on its assertion.
   */
  @Test
  void referenceNumberIssuedUnderTheOldFruPrefixIsNeverRewritten() throws Exception {
    String profileId = readyToSubmitProfile("0000000438", "+249900004381", "IDN-0000000438");

    mockMvc
        .perform(
            post("/api/v1/submission")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isOk());
    keepOutboxRowsUnclaimable(profileId);

    jdbcTemplate.update(
        "UPDATE app.profile SET reference_number = 'FRU-000000001' WHERE profile_id = ?::uuid",
        profileId);

    MvcResult reCall =
        mockMvc
            .perform(
                post("/api/v1/submission")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"profileId\":\"" + profileId + "\"}"))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode body = objectMapper.readTree(reCall.getResponse().getContentAsString());
    assertEquals("FRU-000000001", body.get("referenceNumber").asText());
    assertEquals(
        "FRU-000000001",
        jdbcTemplate.queryForObject(
            "SELECT reference_number FROM app.profile WHERE profile_id = ?::uuid",
            String.class,
            profileId),
        "a legacy FRU- number must survive any later write untouched");
  }

  /**
   * S5-13, the Stage 10-12 resume read. The S5-11 proof shape applied to this endpoint: the read
   * returns the stored state, writes no row anywhere, and is repeatable.
   *
   * <p>{@code pending_face_session_id} and {@code liveness_attempts} are in the snapshot on
   * purpose. The obvious wrong way to build a Stage 10 resume probe would have been to call {@code
   * /api/v1/liveness/token}, which mints a Uqudo Face Session and writes {@code
   * pending_face_session_id} — so asserting that column is unchanged is what proves this read is
   * not that.
   */
  @Test
  void stage10To12ResumeReadReturnsTheStoredStateAndChangesNoRow() throws Exception {
    String profileId = readyToSubmitProfile("0000000433", "+249900004331", "IDN-0000000433");

    MvcResult submitted =
        mockMvc
            .perform(
                post("/api/v1/submission")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"profileId\":\"" + profileId + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode submittedBody = objectMapper.readTree(submitted.getResponse().getContentAsString());
    keepOutboxRowsUnclaimable(profileId);

    Map<String, Object> profileBefore = resumeSnapshot(profileId);
    Map<String, Long> countsBefore = journeyRowCounts(profileId);

    MvcResult read = currentPointer(profileId);
    assertEquals(200, read.getResponse().getStatus());
    JsonNode readBody = objectMapper.readTree(read.getResponse().getContentAsString());

    assertEquals("SUBMITTED", readBody.get("stage").asText());
    assertEquals(
        submittedBody.get("referenceNumber").asText(), readBody.get("referenceNumber").asText());

    assertEquals(profileBefore, resumeSnapshot(profileId), "the read mutated no profile column");
    assertEquals(countsBefore, journeyRowCounts(profileId), "the read wrote no row anywhere");

    // And it is repeatable, which is the whole point of a resume read.
    MvcResult again = currentPointer(profileId);
    assertEquals(200, again.getResponse().getStatus());
    assertEquals(
        readBody,
        objectMapper.readTree(again.getResponse().getContentAsString()),
        "a second read returns byte-identical state");
    assertEquals(profileBefore, resumeSnapshot(profileId));
    assertEquals(countsBefore, journeyRowCounts(profileId));
  }

  /**
   * S5-13's answer to G-9. customer.md l.1005 requires the confirmation screen to show which
   * channels carry the decision, but an idempotent re-submit correctly reports an EMPTY {@code
   * verifiedChannels} — that field means "channels this call enqueued a notification for", and a
   * re-call enqueues nothing. This proves the resume read is a real source for what the screen
   * needs, on exactly the path where the submission response cannot be one.
   */
  @Test
  void resumeReadCarriesTheChannelsAnIdempotentResubmitCannot() throws Exception {
    String profileId = readyToSubmitProfile("0000000434", "+249900004341", "IDN-0000000434");

    mockMvc
        .perform(
            post("/api/v1/submission")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isOk());
    keepOutboxRowsUnclaimable(profileId);

    // The lost-acknowledgement retry: same reference number, and no channels.
    MvcResult resubmit =
        mockMvc
            .perform(
                post("/api/v1/submission")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"profileId\":\"" + profileId + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode resubmitBody = objectMapper.readTree(resubmit.getResponse().getContentAsString());
    assertTrue(
        resubmitBody.get("verifiedChannels").isEmpty(),
        "unchanged by S5-13: the submission response still reports only what it enqueued");

    JsonNode readBody =
        objectMapper.readTree(currentPointer(profileId).getResponse().getContentAsString());
    List<String> channels = new java.util.ArrayList<>();
    readBody.get("verifiedChannels").forEach(node -> channels.add(node.asText()));
    assertEquals(
        List.of(MessageChannel.SMS.wireValue()),
        channels,
        "the read tells the confirmation screen what the resubmit could not");
    assertEquals(
        resubmitBody.get("referenceNumber").asText(), readBody.get("referenceNumber").asText());
  }

  /** The pointer walks the customer through 10, 11 and 12 rather than only reporting the end. */
  @Test
  void resumeReadDistinguishesTheStagesBeforeSubmission() throws Exception {
    String noSignatureYet =
        profileWithPassedLiveness("0000000435", "+249900004351", "IDN-0000000435");
    assertEquals(
        "SIGNATURE",
        objectMapper
            .readTree(currentPointer(noSignatureYet).getResponse().getContentAsString())
            .get("stage")
            .asText());

    String readyToSubmit = readyToSubmitProfile("0000000436", "+249900004361", "IDN-0000000436");
    assertEquals(
        "SUBMIT",
        objectMapper
            .readTree(currentPointer(readyToSubmit).getResponse().getContentAsString())
            .get("stage")
            .asText());
  }

  /**
   * The only test that reads a non-null {@code liveness_blocked_until} out of real PostgreSQL, so
   * it is what proves the new column's {@code Timestamp -> Instant} conversion as well as the clock
   * comparison. Both directions are asserted from one profile: a future deadline blocks, and the
   * SAME row with an elapsed deadline falls through to the stage the block was holding.
   *
   * <p>The elapsed half is the one that matters. {@code LivenessService} clears a lapsed block on
   * the next token request rather than on a timer, so {@code status} still reads {@code
   * blocked_liveness} long after the deadline passed; answering on status alone would show a block
   * screen carrying an already-past time to a customer whose {@code /liveness/token} would have
   * succeeded.
   */
  @Test
  void resumeReadTreatsALapsedLivenessBlockAsResumable() throws Exception {
    String profileId = profileWithPassedLiveness("0000000437", "+249900004371", "IDN-0000000437");
    // Face-match has passed for this profile, so LIVENESS is reachable only via the block guard --
    // which makes the fall-through assertion below unambiguous.
    blockLiveness(profileId, Instant.now().plus(Duration.ofHours(24)));

    JsonNode blocked =
        objectMapper.readTree(currentPointer(profileId).getResponse().getContentAsString());
    assertEquals("LIVENESS_BLOCKED", blocked.get("stage").asText());
    assertNotNull(blocked.get("blockedUntil").asText(), "the deadline crosses the wire");

    blockLiveness(profileId, Instant.now().minus(Duration.ofMinutes(1)));

    JsonNode lapsed =
        objectMapper.readTree(currentPointer(profileId).getResponse().getContentAsString());
    assertEquals(
        "SIGNATURE",
        lapsed.get("stage").asText(),
        "a lapsed block is resumable, exactly as LivenessService treats it");
  }

  /**
   * {@code in_progress -> blocked_liveness} is a legal transition (V0020/V0043), but V0020's
   * deferred {@code profile_status_requires_history} trigger also demands a matching {@code
   * app.profile_status_history} row — so the status change and its history row go in ONE
   * transaction, the same shape {@code AccountCheckIntegrationTest} uses to manufacture a status.
   * Set directly rather than by burning five real liveness attempts, which this class has no cheap
   * way to drive and which would prove nothing extra about the read.
   *
   * <p>Only the first call is a status CHANGE; a later call moving the deadline leaves {@code
   * status} already {@code blocked_liveness}, so the trigger does not fire and no second history
   * row is written.
   */
  private void blockLiveness(String profileId, Instant blockedUntil) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            tx -> {
              String previousStatus =
                  jdbcTemplate.queryForObject(
                      "SELECT status FROM app.profile WHERE profile_id = ?::uuid",
                      String.class,
                      profileId);
              jdbcTemplate.update(
                  "UPDATE app.profile SET status = 'blocked_liveness',"
                      + " status_changed_at = clock_timestamp(),"
                      + " liveness_blocked_until = ?::timestamptz WHERE profile_id = ?::uuid",
                  blockedUntil.toString(),
                  profileId);
              if ("blocked_liveness".equals(previousStatus)) {
                return; // no status change, so the guard does not fire
              }
              Long auditEventId =
                  jdbcTemplate.queryForObject(
                      "SELECT max(audit_event_id) FROM audit.audit_event WHERE profile_id = ?::uuid",
                      Long.class,
                      profileId);
              jdbcTemplate.update(
                  "INSERT INTO app.profile_status_history"
                      + " (profile_id, seq, from_status, to_status, actor_kind, audit_event_id)"
                      + " VALUES (?::uuid,"
                      + " (SELECT coalesce(max(seq), 0) + 1 FROM app.profile_status_history"
                      + "   WHERE profile_id = ?::uuid),"
                      + " ?::text, 'blocked_liveness', 'system', ?::bigint)",
                  profileId,
                  profileId,
                  previousStatus,
                  auditEventId);
            });
  }

  private MvcResult currentPointer(String profileId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/submission/current")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"profileId\":\"" + profileId + "\"}"))
            .andReturn();
    result.getResponse().setCharacterEncoding("UTF-8");
    return result;
  }

  /**
   * Every {@code app.profile} column any Stage 10-12 write would touch. Two are here for specific
   * reasons: {@code pending_face_session_id} would move if this read ever minted a Uqudo Face
   * Session (the wrong way to build a Stage 10 probe would have been to call {@code
   * /api/v1/liveness/token}, which does exactly that), and {@code last_activity_at} would move if
   * anyone added the {@code touchLastActivity} call every other Stage 10-12 path makes — a write
   * that deliberately does not bump {@code row_version}, so nothing else in this snapshot would
   * catch it.
   */
  private Map<String, Object> resumeSnapshot(String profileId) {
    return jdbcTemplate.queryForMap(
        "SELECT status, reference_number, submitted_at, row_version, liveness_attempts,"
            + " liveness_blocked_until, pending_face_session_id, last_activity_at"
            + " FROM app.profile WHERE profile_id = ?::uuid",
        profileId);
  }

  private Map<String, Long> journeyRowCounts(String profileId) {
    Map<String, Long> counts = new java.util.LinkedHashMap<>();
    counts.put(
        "face_result",
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.face_result fr"
                + " JOIN app.identity_cycle ic ON ic.cycle_id = fr.cycle_id"
                + " WHERE ic.profile_id = ?::uuid",
            Long.class,
            profileId));
    counts.put(
        "artifact_ref",
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.artifact_ref WHERE profile_id = ?::uuid",
            Long.class,
            profileId));
    counts.put(
        "status_history",
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.profile_status_history WHERE profile_id = ?::uuid",
            Long.class,
            profileId));
    counts.put(
        "notification_outbox",
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.notification_outbox WHERE profile_id = ?::uuid",
            Long.class,
            profileId));
    counts.put(
        "audit_event",
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit.audit_event WHERE profile_id = ?::uuid",
            Long.class,
            profileId));
    return counts;
  }

  @Test
  void submissionWithoutASignatureIsRejected() throws Exception {
    String profileId = profileWithPassedLiveness("0000000432", "+249900004321", "IDN-0000000432");
    // No /api/v1/signature call.

    mockMvc
        .perform(
            post("/api/v1/submission")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isConflict());

    String status =
        jdbcTemplate.queryForObject(
            "SELECT status FROM app.profile WHERE profile_id = ?::uuid", String.class, profileId);
    assertEquals("in_progress", status, "a rejected submission leaves the profile untouched");

    Long referenceVersionCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ref.profile_reference_version WHERE profile_id = ?::uuid",
            Long.class,
            profileId);
    assertEquals(
        0L,
        referenceVersionCount,
        "a rejected submission never opens the transaction that would write these rows");
  }

  /**
   * S4-04, AD-002f §6.2: {@code ref.profile_reference_version} gets one row per reference list the
   * profile actually used, written in the same transaction as the status update -- see {@code
   * SubmissionService}'s Javadoc. Drives stages 3, 4 and 5 (not 6) so all four possible lists are
   * exercised, not just occupation/income_source: {@code admin_division} via stage 3's Sudan birth
   * state AND stage 5's Sudan home address, and {@code country} via stage 3's two country fields
   * (residence, birth) AND stage 5's home country.
   *
   * <p><strong>What this does and does not prove</strong> (a second review pass corrected an
   * overclaim here): it proves all four lists produce a row, and that {@code country}'s four
   * independent version columns collapse to exactly one row rather than four or zero. It does NOT
   * prove the {@code COALESCE} in {@code JdbcSubmissionRepository#usedReferenceListVersions} reads
   * the *correct* column for {@code country} versus, say, {@code admin_div_version} — every seeded
   * list is at version 1, so every candidate column holds the identical value and a transposed
   * mapping would still pass. Distinguishing that needs two lists at genuinely different versions,
   * which needs a second published version (see {@code DataEntryIntegrationTest}'s
   * throwaway-version pattern) -- left as a known gap rather than built speculatively here.
   */
  @Test
  void referenceListVersionsUsedByThisProfileAreRecordedAtSubmission() throws Exception {
    String profileId = readyToSubmitProfile("0000000496", "+249900004961", "IDN-0000000496");

    Map<String, Object> stage3Body = new java.util.LinkedHashMap<>();
    stage3Body.put("profileId", profileId);
    stage3Body.put("sexDeclared", "f");
    stage3Body.put("ethnicity", "Nubian");
    stage3Body.put("countryOfResidenceCode", "SD");
    stage3Body.put("maritalStatus", "single");
    stage3Body.put("educationLevel", 6);
    stage3Body.put("birthCountryCode", "SD");
    stage3Body.put("birthStateCode", "11");
    stage3Body.put("birthCityText", "Khartoum");
    mockMvc
        .perform(
            post("/api/v1/data-entry/stage3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(stage3Body)))
        .andExpect(status().isOk());

    Map<String, Object> stage4Body = new java.util.LinkedHashMap<>();
    stage4Body.put("profileId", profileId);
    stage4Body.put("occupationCode", "86");
    stage4Body.put("incomeSources", List.of(Map.of("code", "RATIB", "primary", true)));
    stage4Body.put("monthlyExpensesSdg", "1000");
    mockMvc
        .perform(
            post("/api/v1/data-entry/stage4")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(stage4Body)))
        .andExpect(status().isOk());

    Map<String, Object> stage5Body = new java.util.LinkedHashMap<>();
    stage5Body.put("profileId", profileId);
    stage5Body.put("countryCode", "SD");
    stage5Body.put("stateCode", "11");
    stage5Body.put("localityCode", "1101");
    stage5Body.put("city", "Halfa");
    stage5Body.put("area", "Area");
    stage5Body.put("street", "Street");
    stage5Body.put("block", "Block");
    stage5Body.put("houseNumber", "12");
    mockMvc
        .perform(
            post("/api/v1/data-entry/stage5")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(stage5Body)))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/api/v1/submission")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isOk());
    keepOutboxRowsUnclaimable(profileId);

    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT list_code, version FROM ref.profile_reference_version"
                + " WHERE profile_id = ?::uuid ORDER BY list_code",
            profileId);
    assertEquals(
        List.of(
            Map.of("list_code", "admin_division", "version", 1),
            Map.of("list_code", "country", "version", 1),
            Map.of("list_code", "income_source", "version", 1),
            Map.of("list_code", "occupation", "version", 1)),
        rows);
  }

  // ---- helpers ----

  private void keepOutboxRowsUnclaimable(String profileId) {
    jdbcTemplate.update(
        "UPDATE app.notification_outbox SET next_attempt_at = now() + interval '1 hour'"
            + " WHERE profile_id = ?::uuid",
        profileId);
  }

  /**
   * Stage 1b through a captured, verified SMS OTP -- so at least one channel is genuinely VERIFIED.
   */
  private String createProfileWithVerifiedSms(String accountNumber, String phoneNumber)
      throws Exception {
    String body =
        "{\"branch\":\""
            + BRANCH
            + "\",\"accountNumber\":\""
            + accountNumber
            + "\",\"phoneNumber\":\""
            + phoneNumber
            + "\",\"sms\":true,\"whatsapp\":true}";
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

  /**
   * Runs the customer through stage 1b (verified SMS), 8, 9's Accept and a passing stage 10 result.
   */
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

    Map<String, Object> scanBody = new java.util.LinkedHashMap<>();
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

    Map<String, Object> faceBody = new java.util.LinkedHashMap<>();
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

  /**
   * {@link #profileWithPassedLiveness} plus a captured signature -- ready for {@code /submission}.
   */
  private String readyToSubmitProfile(
      String accountNumber, String phoneNumber, String identityNumber) throws Exception {
    String profileId = profileWithPassedLiveness(accountNumber, phoneNumber, identityNumber);

    String base64 =
        Base64.getEncoder().encodeToString("fake-signature-bytes".getBytes(StandardCharsets.UTF_8));
    Map<String, Object> signatureBody = new java.util.LinkedHashMap<>();
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
}
