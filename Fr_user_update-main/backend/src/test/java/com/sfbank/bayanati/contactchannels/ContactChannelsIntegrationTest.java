package com.sfbank.bayanati.contactchannels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.profile.domain.ExistingProfile;
import com.sfbank.bayanati.profile.domain.ProfileRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * S3-06: journey Stage 1b end to end — the real HTTP endpoint, the real service, the real JDBC
 * profile writer and the real JDBC audit writer, against a real PostgreSQL 18 with every migration
 * (including V0036) applied.
 *
 * <p>Tagged "integration" and excluded from ./mvnw test / verify by default (pom.xml
 * excludedGroups) — run with {@code ./mvnw test -Pdb-integration-test}. Container and dynamic
 * properties come from {@link AbstractPostgresIntegrationTest} — S3-09 moved every integration
 * class onto one shared, JVM-lifetime container instead of one each.
 *
 * <p>Each test uses its own account number — {@code app.profile}'s {@code UNIQUE (account_number)}
 * ({@code profile_one_per_account}, account-only since V0061/BL-032) means a second profile for the
 * same account would fail, and the container is shared across every test method in this class (and,
 * since S3-09, across every other integration class in the same run — this class's
 * 0000000101–0000000112 range is disjoint from theirs; see {@link
 * AbstractPostgresIntegrationTest}'s Javadoc for the full account-number map).
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class ContactChannelsIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String BRANCH = "16";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate; // connects as fru_app
  @Autowired private PlatformTransactionManager transactionManager;

  /**
   * A spy on the real bean, not a mock: every method delegates to the real {@code
   * JdbcProfileRepository} against the real database unless a test explicitly overrides one call.
   * Used by {@code #aRejectionMidReEntryPersistsItsAuditEventDespiteTheTransactionDoingNothing} to
   * simulate the race {@code lockAndCheckStillEligibleForReentry} exists to catch,
   * deterministically — without needing genuine concurrent threads to hit the timing window.
   */
  @MockitoSpyBean private ProfileRepository profileRepository;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @Test
  void threeChannelsSelectedCreatesEverythingLiveAndAuditsOnTheProfilesOwnChain() throws Exception {
    String accountNumber = "0000000101";
    String body =
        "{\"branch\":\""
            + BRANCH
            + "\",\"accountNumber\":\""
            + accountNumber
            + "\",\"phoneNumber\":\"+249900001101\",\"sms\":true,\"whatsapp\":true,"
            + "\"emailAddress\":\"ahmed101@example.invalid\"}";

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/contact-channels")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();

    String responseBody = result.getResponse().getContentAsString();
    JsonNode response = objectMapper.readTree(responseBody);
    String profileId = response.get("profileId").asText();
    assertEquals(3, response.get("channels").size());

    // --- The code appears nowhere in the HTTP response. Full body shown for the session report.
    // ---
    assertFalse(responseBody.toLowerCase().contains("code"), "full response body: " + responseBody);

    // --- Three profile_channel rows ---
    List<Map<String, Object>> channelRows =
        jdbcTemplate.queryForList(
            "SELECT channel, state FROM app.profile_channel WHERE profile_id = ?::uuid ORDER BY channel",
            profileId);
    assertEquals(3, channelRows.size(), channelRows.toString());
    assertTrue(
        channelRows.stream().allMatch(r -> "unverified".equals(r.get("state"))),
        channelRows.toString());

    // --- Three otp_challenge rows with THREE DISTINCT codes (hashes) ---
    List<Map<String, Object>> challengeRows =
        jdbcTemplate.queryForList(
            "SELECT channel, encode(code_hash,'hex') AS code_hash_hex, encode(salt,'hex') AS salt_hex"
                + " FROM app.otp_challenge WHERE profile_id = ?::uuid",
            profileId);
    assertEquals(3, challengeRows.size(), challengeRows.toString());
    Set<String> distinctHashes = new HashSet<>();
    Set<String> distinctSalts = new HashSet<>();
    for (Map<String, Object> row : challengeRows) {
      distinctHashes.add((String) row.get("code_hash_hex"));
      distinctSalts.add((String) row.get("salt_hex"));
    }
    assertEquals(3, distinctHashes.size(), "three distinct codes: " + challengeRows);
    assertEquals(3, distinctSalts.size(), "three distinct salts: " + challengeRows);

    // --- Audit events on the PROFILE's own chain, not the seeded system/account_check chain ---
    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            "SELECT e.event_type, e.payload_json FROM audit.audit_event e"
                + " JOIN audit.audit_chain c ON c.chain_id = e.chain_id"
                + " WHERE c.chain_kind = 'profile' AND c.subject_id = ?"
                + " ORDER BY e.seq",
            profileId);
    List<String> eventTypes = events.stream().map(e -> (String) e.get("event_type")).toList();
    assertEquals(
        1, eventTypes.stream().filter("session_created"::equals).count(), eventTypes.toString());
    assertEquals(
        3, eventTypes.stream().filter("otp_issued"::equals).count(), eventTypes.toString());
    assertEquals(
        3,
        eventTypes.stream().filter("notification_dispatched"::equals).count(),
        eventTypes.toString());

    // No otp_issued payload carries a code.
    for (Map<String, Object> event : events) {
      if ("otp_issued".equals(event.get("event_type"))) {
        String payload = (String) event.get("payload_json");
        assertTrue(payload.contains("channel"), payload);
        assertTrue(payload.contains("expiresAtIso"), payload);
        assertFalse(payload.toLowerCase().contains("\"code\""), payload);
      }
    }

    // --- A realistic Arabic OTP body's billedSegments, recorded on the SMS notification_dispatched
    // event ---
    String smsResultPayload =
        events.stream()
            .filter(e -> "notification_dispatched".equals(e.get("event_type")))
            .map(e -> (String) e.get("payload_json"))
            .filter(p -> p.contains("\"channel\":\"sms\""))
            .findFirst()
            .orElseThrow();
    JsonNode smsResult = objectMapper.readTree(smsResultPayload);
    assertEquals(2, smsResult.get("billedSegments").asInt(), smsResultPayload);
    assertEquals("ACCEPTED", smsResult.get("outcome").asText(), smsResultPayload);

    // --- The chain actually verifies ---
    String chainId =
        jdbcTemplate.queryForObject(
            "SELECT chain_id::text FROM audit.audit_chain WHERE chain_kind='profile' AND subject_id=?",
            String.class,
            profileId);
    Map<String, Object> verification =
        jdbcTemplate.queryForMap("SELECT ok, reason FROM audit.verify_chain(?::uuid)", chainId);
    assertEquals(
        Boolean.TRUE,
        verification.get("ok"),
        "chain verification failed: " + verification.get("reason"));
  }

  @Test
  void whatsAppDeselectedYieldsTwoChallengesAndRecordsWhatsAppAsDeclined() throws Exception {
    // All three channels offered (SMS, WhatsApp, email), only WhatsApp deselected -- so two
    // channels remain challenged (SMS + email), matching the task's own proof scenario.
    String accountNumber = "0000000102";
    String body =
        "{\"branch\":\""
            + BRANCH
            + "\",\"accountNumber\":\""
            + accountNumber
            + "\",\"phoneNumber\":\"+249900001102\",\"sms\":true,\"whatsapp\":false,"
            + "\"emailAddress\":\"ahmed102@example.invalid\"}";

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

    Integer challengeCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.otp_challenge WHERE profile_id = ?::uuid",
            Integer.class,
            profileId);
    assertEquals(2, challengeCount);

    String whatsappState =
        jdbcTemplate.queryForObject(
            "SELECT state FROM app.profile_channel WHERE profile_id = ?::uuid AND channel = 'whatsapp'",
            String.class,
            profileId);
    assertEquals("declined", whatsappState);
  }

  @Test
  void bothPhoneChannelsDeselectedIsRejectedAndCreatesNoProfileAtAll() throws Exception {
    String accountNumber = "0000000103";
    String body =
        "{\"branch\":\""
            + BRANCH
            + "\",\"accountNumber\":\""
            + accountNumber
            + "\",\"phoneNumber\":\"+249900001103\",\"sms\":false,\"whatsapp\":false}";

    mockMvc
        .perform(
            post("/api/v1/contact-channels").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());

    Integer profileCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.profile WHERE branch_code = ? AND account_number = ?",
            Integer.class,
            BRANCH,
            accountNumber);
    assertEquals(0, profileCount, "no profile row of any kind for a rejected request");
  }

  @Test
  void noEmailAddressCreatesNoEmailChannelRowNoChallengeAndNoEmailAuditEvent() throws Exception {
    String accountNumber = "0000000104";
    String body =
        "{\"branch\":\""
            + BRANCH
            + "\",\"accountNumber\":\""
            + accountNumber
            + "\",\"phoneNumber\":\"+249900001104\"}"; // sms/whatsapp default true, no emailAddress

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

    Integer emailChannelRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.profile_channel WHERE profile_id = ?::uuid AND channel = 'email'",
            Integer.class,
            profileId);
    assertEquals(0, emailChannelRows);

    Integer emailChallengeRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.otp_challenge WHERE profile_id = ?::uuid AND channel = 'email'",
            Integer.class,
            profileId);
    assertEquals(0, emailChallengeRows);

    String emailAddressStored =
        jdbcTemplate.queryForObject(
            "SELECT email_address FROM app.profile_customer_data WHERE profile_id = ?::uuid",
            String.class,
            profileId);
    assertEquals(null, emailAddressStored);
  }

  // --- S3-07 / BL-009: re-entry and the terminal-profile rejection, end to end -----------------

  @Test
  void aSecondSubmissionWithADifferentPhoneNumberSucceedsAndPreservesBothAuditTrails()
      throws Exception {
    String accountNumber = "0000000105";
    String firstProfileId = submitContactChannels(accountNumber, "+249900001050");

    List<Map<String, Object>> firstChallenges = challengeRows(firstProfileId);
    assertEquals(2, firstChallenges.size(), firstChallenges.toString());

    String secondProfileId = submitContactChannels(accountNumber, "+249900002050");
    assertEquals(firstProfileId, secondProfileId, "re-entry must reuse the existing profile");

    // --- The new phone number is now on record ---
    String phoneStored =
        jdbcTemplate.queryForObject(
            "SELECT phone_number FROM app.profile_customer_data WHERE profile_id = ?::uuid",
            String.class,
            secondProfileId);
    assertEquals("+249900002050", phoneStored);

    // --- Exactly one profile row for this account -- BL-009's constraint violation never fires
    Integer profileCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.profile WHERE branch_code = ? AND account_number = ?",
            Integer.class,
            BRANCH,
            accountNumber);
    assertEquals(1, profileCount);

    // --- Four otp_challenge rows total: the first two, still present, plus two fresh ones ---
    List<Map<String, Object>> allChallenges = challengeRows(firstProfileId);
    assertEquals(4, allChallenges.size(), allChallenges.toString());

    // --- The first submission's two challenges are now expired: they cannot be used ---
    for (Map<String, Object> firstChallenge : firstChallenges) {
      Boolean nowExpired =
          jdbcTemplate.queryForObject(
              "SELECT expires_at <= now() FROM app.otp_challenge WHERE challenge_id = ?::uuid",
              Boolean.class,
              firstChallenge.get("challenge_id").toString());
      assertEquals(
          Boolean.TRUE, nowExpired, "the abandoned attempt's challenge must be invalidated");
    }

    // --- The second submission's two challenges are still valid ---
    List<String> firstChallengeIds =
        firstChallenges.stream().map(r -> r.get("challenge_id").toString()).toList();
    List<Map<String, Object>> freshChallenges =
        allChallenges.stream()
            .filter(r -> !firstChallengeIds.contains(r.get("challenge_id").toString()))
            .toList();
    assertEquals(2, freshChallenges.size());
    for (Map<String, Object> fresh : freshChallenges) {
      Boolean stillValid =
          jdbcTemplate.queryForObject(
              "SELECT expires_at > now() FROM app.otp_challenge WHERE challenge_id = ?::uuid",
              Boolean.class,
              fresh.get("challenge_id").toString());
      assertEquals(Boolean.TRUE, stillValid);
    }

    // --- Every audit event from BOTH submissions survives: 1 session_created + 2 otp_issued +
    // 2 notification_dispatched, then 1 session_reentered + 2 otp_issued + 2
    // notification_dispatched
    List<Map<String, Object>> events = auditEvents(firstProfileId);
    List<String> eventTypes = events.stream().map(e -> (String) e.get("event_type")).toList();
    assertEquals(10, eventTypes.size(), eventTypes.toString());
    assertEquals(
        1, eventTypes.stream().filter("session_created"::equals).count(), eventTypes.toString());
    assertEquals(
        1, eventTypes.stream().filter("session_reentered"::equals).count(), eventTypes.toString());
    assertEquals(
        4, eventTypes.stream().filter("otp_issued"::equals).count(), eventTypes.toString());
    assertEquals(
        4,
        eventTypes.stream().filter("notification_dispatched"::equals).count(),
        eventTypes.toString());

    String reenteredPayload =
        events.stream()
            .filter(e -> "session_reentered".equals(e.get("event_type")))
            .map(e -> (String) e.get("payload_json"))
            .findFirst()
            .orElseThrow();
    assertTrue(
        reenteredPayload.contains("\"previousPhoneNumber\":\"+249900001050\""), reenteredPayload);

    // --- The chain still verifies over all ten events ---
    String chainId =
        jdbcTemplate.queryForObject(
            "SELECT chain_id::text FROM audit.audit_chain WHERE chain_kind='profile' AND subject_id=?",
            String.class,
            firstProfileId);
    Map<String, Object> verification =
        jdbcTemplate.queryForMap("SELECT ok, reason FROM audit.verify_chain(?::uuid)", chainId);
    assertEquals(
        Boolean.TRUE,
        verification.get("ok"),
        "chain verification failed: " + verification.get("reason"));
  }

  @Test
  void aTerminalProfileSubmittedDirectlyIsRejectedWithNoMessageSentAndNoNewRows() throws Exception {
    String accountNumber = "0000000106";
    String profileId = submitContactChannels(accountNumber, "+249900001060");
    transitionToSubmitted(profileId);

    int channelRowsBefore = channelRows(profileId).size();
    int challengeRowsBefore = challengeRows(profileId).size();
    int auditEventsBefore = auditEvents(profileId).size();

    String body =
        "{\"branch\":\""
            + BRANCH
            + "\",\"accountNumber\":\""
            + accountNumber
            + "\",\"phoneNumber\":\"+249900009999\",\"sms\":true,\"whatsapp\":true}";

    mockMvc
        .perform(
            post("/api/v1/contact-channels").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict());

    assertEquals(channelRowsBefore, channelRows(profileId).size(), "no new profile_channel rows");
    assertEquals(challengeRowsBefore, challengeRows(profileId).size(), "no new otp_challenge rows");

    List<Map<String, Object>> events = auditEvents(profileId);
    // Exactly one event added -- the rejection -- and it is the only one of its type: no OTP, no
    // send, no other write happened on this path.
    assertEquals(auditEventsBefore + 1, events.size(), events.toString());
    Map<String, Object> lastEvent = events.get(events.size() - 1);
    assertEquals("contact_channels_rejected", lastEvent.get("event_type"));
    assertEquals(
        1,
        events.stream()
            .filter(e -> "contact_channels_rejected".equals(e.get("event_type")))
            .count());
  }

  @Test
  void aRejectionMidReEntryPersistsItsAuditEventDespiteTheTransactionDoingNothing()
      throws Exception {
    // Reproduces, deterministically, the exact bug @agent-reviewer's second pass caught in the
    // first draft of this fix: the rejection audit event was written INSIDE the same
    // TransactionTemplate callback that then threw, so a real transaction manager rolled it back
    // along with everything else -- the fix moved the write to after executeWithoutResult returns.
    // A mocked PlatformTransactionManager (as ContactChannelsServiceTest uses) cannot exercise real
    // rollback and so cannot catch this class of bug; this test runs against the real database.
    String accountNumber = "0000000109";
    String profileId = submitContactChannels(accountNumber, "+249900001090");
    transitionToSubmitted(profileId); // the real row is now terminal

    // The spy lies on the FIRST read only (as if findExisting still saw the pre-race state);
    // lockAndCheckStillEligibleForReentry is untouched and hits the real, now-terminal row.
    doReturn(
            Optional.of(
                new ExistingProfile(java.util.UUID.fromString(profileId), "in_progress", false)))
        .when(profileRepository)
        .findExisting(accountNumber);

    long auditEventsBefore = auditEvents(profileId).size();

    String body =
        "{\"branch\":\""
            + BRANCH
            + "\",\"accountNumber\":\""
            + accountNumber
            + "\",\"phoneNumber\":\"+249900009999\",\"sms\":true,\"whatsapp\":true}";
    mockMvc
        .perform(
            post("/api/v1/contact-channels").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict());

    List<Map<String, Object>> events = auditEvents(profileId);
    assertEquals(auditEventsBefore + 1, events.size(), events.toString());
    Map<String, Object> lastEvent = events.get(events.size() - 1);
    assertEquals("contact_channels_rejected", lastEvent.get("event_type"));
    assertTrue(
        ((String) lastEvent.get("payload_json"))
            .contains("\"reason\":\"profile_became_complete_during_processing\""),
        (String) lastEvent.get("payload_json"));
  }

  @Test
  void aPreviouslyVerifiedChannelComesBackUnverifiedWithNoVerifiedAtOnReEntry() throws Exception {
    // customer.md: "The fresh OTP verification at 1b overwrites the recorded channel states
    // entirely" -- a channel marked verified by a (not-yet-built) Stage 2 must not still read
    // verified_at after a re-entry resets its state to unverified.
    String accountNumber = "0000000110";
    String profileId = submitContactChannels(accountNumber, "+249900001100");

    jdbcTemplate.update(
        "UPDATE app.profile_channel SET state = 'verified', verified_at = clock_timestamp()"
            + " WHERE profile_id = ?::uuid AND channel = 'sms'",
        profileId);
    Map<String, Object> before =
        jdbcTemplate.queryForMap(
            "SELECT state, verified_at FROM app.profile_channel WHERE profile_id = ?::uuid AND channel = 'sms'",
            profileId);
    assertEquals("verified", before.get("state"));
    assertTrue(before.get("verified_at") != null, "fixture setup sanity check");

    String body =
        "{\"branch\":\""
            + BRANCH
            + "\",\"accountNumber\":\""
            + accountNumber
            + "\",\"phoneNumber\":\"+249900002100\",\"sms\":true,\"whatsapp\":true}";
    mockMvc
        .perform(
            post("/api/v1/contact-channels").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());

    Map<String, Object> after =
        jdbcTemplate.queryForMap(
            "SELECT state, verified_at FROM app.profile_channel WHERE profile_id = ?::uuid AND channel = 'sms'",
            profileId);
    assertEquals("unverified", after.get("state"));
    assertEquals(null, after.get("verified_at"));
  }

  @Test
  void reEntryToAnAbandonedProfileReactivatesItLive() throws Exception {
    String accountNumber = "0000000107";
    String profileId = submitContactChannels(accountNumber, "+249900001070");
    transitionToAbandoned(profileId);

    Map<String, Object> beforeReentry =
        jdbcTemplate.queryForMap(
            "SELECT status, row_version FROM app.profile WHERE profile_id = ?::uuid", profileId);
    assertEquals("abandoned", beforeReentry.get("status"));

    String body =
        "{\"branch\":\""
            + BRANCH
            + "\",\"accountNumber\":\""
            + accountNumber
            + "\",\"phoneNumber\":\"+249900002070\",\"sms\":true,\"whatsapp\":true}";
    mockMvc
        .perform(
            post("/api/v1/contact-channels").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());

    Map<String, Object> afterReentry =
        jdbcTemplate.queryForMap(
            "SELECT status, row_version FROM app.profile WHERE profile_id = ?::uuid", profileId);
    assertEquals("in_progress", afterReentry.get("status"));
    assertEquals(
        ((Number) beforeReentry.get("row_version")).longValue() + 1,
        ((Number) afterReentry.get("row_version")).longValue());

    List<Map<String, Object>> history =
        jdbcTemplate.queryForList(
            "SELECT seq, from_status, to_status FROM app.profile_status_history"
                + " WHERE profile_id = ?::uuid ORDER BY seq",
            profileId);
    Map<String, Object> lastHop = history.get(history.size() - 1);
    assertEquals("abandoned", lastHop.get("from_status"));
    assertEquals("in_progress", lastHop.get("to_status"));
  }

  @Test
  void emailDroppedOnReEntryIsDeclinedNotDeletedLive() throws Exception {
    String accountNumber = "0000000108";
    String body1 =
        "{\"branch\":\""
            + BRANCH
            + "\",\"accountNumber\":\""
            + accountNumber
            + "\",\"phoneNumber\":\"+249900001080\",\"sms\":true,\"whatsapp\":true,"
            + "\"emailAddress\":\"first108@example.invalid\"}";
    mockMvc
        .perform(
            post("/api/v1/contact-channels").contentType(MediaType.APPLICATION_JSON).content(body1))
        .andExpect(status().isOk());

    Integer emailRowsBefore =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.profile_channel pc JOIN app.profile p ON p.profile_id = pc.profile_id"
                + " WHERE p.branch_code = ? AND p.account_number = ? AND pc.channel = 'email'",
            Integer.class,
            BRANCH,
            accountNumber);
    assertEquals(1, emailRowsBefore);

    // Resubmit with NO email this time.
    String body2 =
        "{\"branch\":\""
            + BRANCH
            + "\",\"accountNumber\":\""
            + accountNumber
            + "\",\"phoneNumber\":\"+249900002080\",\"sms\":true,\"whatsapp\":true}";
    mockMvc
        .perform(
            post("/api/v1/contact-channels").contentType(MediaType.APPLICATION_JSON).content(body2))
        .andExpect(status().isOk());

    Map<String, Object> emailChannel =
        jdbcTemplate.queryForMap(
            "SELECT pc.state FROM app.profile_channel pc JOIN app.profile p ON p.profile_id = pc.profile_id"
                + " WHERE p.branch_code = ? AND p.account_number = ? AND pc.channel = 'email'",
            BRANCH,
            accountNumber);
    assertEquals("declined", emailChannel.get("state"), "the row must survive, not vanish");
  }

  // --- BL-032 / V0061: the profile's identity is the account number alone ---------------------

  @Test
  void reEntryUnderADifferentBranchReusesTheProfileAndRefreshesItsBranchLive() throws Exception {
    // The same customer (same account) comes back having selected a different branch. Before
    // V0061 the composite UNIQUE (branch_code, account_number) would have let a second profile row
    // in and findExisting would have missed the first; now the account alone is the identity.
    String accountNumber = "0000000111";
    String firstProfileId = submitContactChannels(BRANCH, accountNumber, "+249900001110");
    assertEquals("16", branchCodeOf(firstProfileId));

    String secondProfileId = submitContactChannels("22", accountNumber, "+249900002110");
    assertEquals(firstProfileId, secondProfileId, "the lookup must ignore the branch");

    assertEquals("22", branchCodeOf(firstProfileId), "the latest selection is what the row holds");
    Integer rowsForAccount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.profile WHERE account_number = ?",
            Integer.class,
            accountNumber);
    assertEquals(1, rowsForAccount, "still exactly one profile for the account");

    // The re-entry event carries the branch submitted this time, so the previous value ('16')
    // is on the permanent record even though app.profile now holds only the latest.
    String reenteredPayload =
        auditEvents(firstProfileId).stream()
            .filter(e -> "session_reentered".equals(e.get("event_type")))
            .map(e -> (String) e.get("payload_json"))
            .findFirst()
            .orElseThrow();
    assertTrue(reenteredPayload.contains("\"branch\":\"22\""), reenteredPayload);
  }

  @Test
  void aSecondProfileRowForTheSameAccountUnderAnotherBranchIsRejectedByTheDatabase()
      throws Exception {
    // V0061 landed under the SAME constraint name as V0005, on purpose. This proves the constraint
    // is now account-only: an insert that the composite constraint would have accepted (different
    // branch, same account) is rejected, and the rejection names profile_one_per_account.
    String accountNumber = "0000000112";
    submitContactChannels(BRANCH, accountNumber, "+249900001120");

    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    org.springframework.dao.DataIntegrityViolationException rejected =
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.dao.DataIntegrityViolationException.class,
            () ->
                tx.executeWithoutResult(
                    status -> {
                      status.setRollbackOnly();
                      jdbcTemplate.update(
                          "INSERT INTO app.profile (branch_code, account_number, status,"
                              + " status_changed_at, last_activity_at)"
                              + " VALUES ('99', ?, 'in_progress', clock_timestamp(),"
                              + " clock_timestamp())",
                          accountNumber);
                    }));
    assertTrue(rejected.getMessage().contains("profile_one_per_account"), rejected.getMessage());

    Integer rowsForAccount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.profile WHERE account_number = ?",
            Integer.class,
            accountNumber);
    assertEquals(1, rowsForAccount, "the rejected insert left nothing behind");
  }

  private String branchCodeOf(String profileId) {
    return jdbcTemplate.queryForObject(
        "SELECT branch_code FROM app.profile WHERE profile_id = ?::uuid", String.class, profileId);
  }

  /**
   * Legal single-hop transition (V0020: {@code in_progress -> abandoned}), built by hand as {@code
   * fru_app} in one transaction — same technique as {@link #transitionToSubmitted}.
   */
  private void transitionToAbandoned(String profileId) {
    Long auditEventId =
        jdbcTemplate.queryForObject(
            "SELECT e.audit_event_id FROM audit.audit_event e"
                + " JOIN audit.audit_chain c ON c.chain_id = e.chain_id"
                + " WHERE c.chain_kind = 'profile' AND c.subject_id = ? AND e.event_type = 'session_created'",
            Long.class,
            profileId);

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              jdbcTemplate.update(
                  "UPDATE app.profile SET status = 'abandoned', status_changed_at = clock_timestamp()"
                      + " WHERE profile_id = ?::uuid",
                  profileId);
              jdbcTemplate.update(
                  "INSERT INTO app.profile_status_history"
                      + " (profile_id, seq, from_status, to_status, actor_kind, audit_event_id)"
                      + " VALUES (?::uuid, 2, 'in_progress', 'abandoned', 'system', ?::bigint)",
                  profileId,
                  auditEventId);
            });
  }

  /** Submits Stage 1b through the real endpoint and returns the resulting profile id. */
  private String submitContactChannels(String accountNumber, String phoneNumber) throws Exception {
    return submitContactChannels(BRANCH, accountNumber, phoneNumber);
  }

  private String submitContactChannels(String branch, String accountNumber, String phoneNumber)
      throws Exception {
    String body =
        "{\"branch\":\""
            + branch
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
    return objectMapper
        .readTree(result.getResponse().getContentAsString())
        .get("profileId")
        .asText();
  }

  /**
   * Legal single-hop transition (V0020: {@code in_progress -> submitted}), built by hand as {@code
   * fru_app} in one transaction — same technique as {@code AccountCheckIntegrationTest}. Reuses the
   * profile's own {@code session_created} event id for the history row's FK: a test-only shortcut,
   * since no Stage 11/12 service code exists yet to write a real completion event.
   */
  private void transitionToSubmitted(String profileId) {
    Long auditEventId =
        jdbcTemplate.queryForObject(
            "SELECT e.audit_event_id FROM audit.audit_event e"
                + " JOIN audit.audit_chain c ON c.chain_id = e.chain_id"
                + " WHERE c.chain_kind = 'profile' AND c.subject_id = ? AND e.event_type = 'session_created'",
            Long.class,
            profileId);

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              jdbcTemplate.update(
                  "UPDATE app.profile SET status = 'submitted', status_changed_at = clock_timestamp(),"
                      + " submitted_at = clock_timestamp(), reference_number = ? WHERE profile_id = ?::uuid",
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

  private List<Map<String, Object>> channelRows(String profileId) {
    return jdbcTemplate.queryForList(
        "SELECT channel, state FROM app.profile_channel WHERE profile_id = ?::uuid", profileId);
  }

  private List<Map<String, Object>> challengeRows(String profileId) {
    return jdbcTemplate.queryForList(
        "SELECT challenge_id, channel, issued_at, expires_at FROM app.otp_challenge"
            + " WHERE profile_id = ?::uuid ORDER BY issued_at",
        profileId);
  }

  private List<Map<String, Object>> auditEvents(String profileId) {
    return jdbcTemplate.queryForList(
        "SELECT e.event_type, e.payload_json FROM audit.audit_event e"
            + " JOIN audit.audit_chain c ON c.chain_id = e.chain_id"
            + " WHERE c.chain_kind = 'profile' AND c.subject_id = ? ORDER BY e.seq",
        profileId);
  }
}
