package com.sfbank.bayanati.accountcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S3-01: the stage 1a slice end to end — the real HTTP endpoint, the real service, the
 * configuration-selected stub, and the real JDBC audit writer against a real PostgreSQL 18 with
 * every migration applied.
 *
 * <p>Tagged "integration" and excluded from ./mvnw test / verify by default (pom.xml
 * excludedGroups) — run with `./mvnw test -Pdb-integration-test` (requires Docker). Container and
 * dynamic properties come from {@link AbstractPostgresIntegrationTest} — S3-09 moved every
 * integration class onto one shared, JVM-lifetime container instead of one container each; see its
 * Javadoc for how this class's account-number range keeps it isolated from the other four sharing
 * the same database.
 *
 * <p>What this proves that the unit tests cannot: that an audit event written by application code
 * as fru_app lands inside the hash chain — asserted by calling audit.verify_chain() afterwards, not
 * merely by counting rows.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class AccountCheckIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String ACTIVE_ACCOUNT = "0000000001";
  // S3-02: seeded to return the middleware's -1 "System Error" (the former INACTIVE=2 seed; AD-007
  // removed that outcome). Reaches the 503 path end to end.
  private static final String SYSTEM_ERROR_ACCOUNT = "0000000002";
  private static final String UNKNOWN_ACCOUNT = "0000009999";
  private static final String ACTIVE_ACCOUNT_WITH_TERMINAL_PROFILE = "0000000501";
  private static final String ACTIVE_ACCOUNT_WITH_INCOMPLETE_PROFILE = "0000000502";
  // NOT 0000000503 -- that belongs to auth.OperatorAuthenticationIntegrationTest's range
  // (0000000503-0000000520), per AbstractPostgresIntegrationTest's disjoint-range list.
  private static final String ACTIVE_ACCOUNT_WITH_PHONE_LOCK = "0000000003";
  // BL-032 / V0061: a terminal profile created under BRANCH, then checked under another branch.

  

  // Adds this class's own core-banking stub fixtures on top of the shared properties declared in
  // AbstractPostgresIntegrationTest — Spring invokes every @DynamicPropertySource method found
  // across the class hierarchy, so both contribute to the same dynamic property source.
  @DynamicPropertySource
  static void accountCheckFixtures(DynamicPropertyRegistry registry) {
    // The implementation is chosen here, by configuration, exactly as a deployment chooses it.
    // Nothing in the call path branches on it.
    // S3-02: the stub is keyed by account number alone -- the check carries no branch (OQ-024).
    registry.add("fru.core-banking.stub.accounts[" + ACTIVE_ACCOUNT + "]", () -> 1);
    registry.add("fru.core-banking.stub.accounts[" + SYSTEM_ERROR_ACCOUNT + "]", () -> -1);
    registry.add(
        "fru.core-banking.stub.accounts[" + ACTIVE_ACCOUNT_WITH_TERMINAL_PROFILE + "]", () -> 1);
    registry.add(
        "fru.core-banking.stub.accounts[" + ACTIVE_ACCOUNT_WITH_INCOMPLETE_PROFILE + "]", () -> 1);
    registry.add("fru.core-banking.stub.accounts[" + ACTIVE_ACCOUNT_WITH_PHONE_LOCK + "]", () -> 1);
    
  }

  @Autowired private MockMvc mockMvc;

  // Connects as fru_app — the same role and the same grants the application itself runs under.
  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private PlatformTransactionManager transactionManager;

  // Jackson 3 (tools.jackson.*) — Spring Boot 4.1 ships it in place of com.fasterxml.jackson 2.
  private final JsonMapper objectMapper = JsonMapper.builder().build();

  private String check(String accountNumber, String expectedOutcome, String expectedContinuation)
      throws Exception {
    return check(accountNumber, expectedOutcome, expectedContinuation);
  }

  private String check(
       String accountNumber, String expectedOutcome, String expectedContinuation)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/account-check")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{
                            "\",\"accountNumber\":\""
                            + accountNumber
                            + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value(expectedOutcome))
            .andExpect(jsonPath("$.continuation").value(expectedContinuation))
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    String requestId = body.get("requestId").asText();
    assertNotNull(requestId);
    return requestId;
  }

  @Test
  void bothOutcomesAreReachableThroughTheRealEndpointAndEachIsAudited() throws Exception {
    long eventsBefore = accountCheckEventCount();

    String activeRequestId = check(ACTIVE_ACCOUNT, "ACTIVE", "PROCEED");
    String invalidRequestId = check(UNKNOWN_ACCOUNT, "INVALID", "RETRY");

    assertEquals(
        eventsBefore + 2,
        accountCheckEventCount(),
        "every attempt must be recorded, including the one that creates no profile");

    assertAuditedAttempt(activeRequestId, ACTIVE_ACCOUNT, 1, "ACTIVE");
    assertAuditedAttempt(invalidRequestId, UNKNOWN_ACCOUNT, 0, "INVALID");
  }

  @Test
  void theMiddlewaresSystemErrorIs503AndIsAuditedAsSystemErrorNotAsAnOutcome() throws Exception {
    // S3-02 / BL-008: the -1 path end to end -- real endpoint, real service, real JDBC audit
    // writer. The customer gets a 503, never a 200 with an outcome; the attempt is on the record
    // under its own outcome so an investigation can tell the bank's outage from ours.
    long eventsBefore = accountCheckEventCount();

    mockMvc
        .perform(
            post("/api/v1/account-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{ "\",\"accountNumber\":\""
                        + SYSTEM_ERROR_ACCOUNT
                        + "\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.outcome").doesNotExist());

    assertEquals(eventsBefore + 1, accountCheckEventCount());
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT payload_json FROM audit.audit_event WHERE event_type = 'account_check_attempted'"
                + " AND payload_json::jsonb ->> 'accountNumber' = ? ORDER BY audit_event_id DESC LIMIT 1",
            SYSTEM_ERROR_ACCOUNT);
    JsonNode payload = objectMapper.readTree((String) row.get("payload_json"));
    assertEquals("SYSTEM_ERROR", payload.get("outcome").asText());
    assertEquals(-1, payload.get("resultCode").asInt());
  }

  @Test
  void v0060MakesOmniCheckAcceptTheMiddlewareContract() {
    // BL-031: V0008 required branch_code and pinned result_code to the Oracle (1, 2, -1). After
    // AD-007 the check carries no branch and 0 is a real code. Nothing writes this table (see the
    // component card), so the proof is a fru_app insert inside a transaction that is rolled back --
    // the same technique AppSchemaConnectivityIntegrationTest uses.
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    Integer stored =
        tx.execute(
            status -> {
              status.setRollbackOnly();
              jdbcTemplate.update(
                  "INSERT INTO app.omni_check ( account_hash, result_code, called_at)"
                      + " VALUES (NULL, sha256('0000009999'::bytea), 0, clock_timestamp())");
              return jdbcTemplate.queryForObject(
                  "SELECT result_code FROM app.omni_check WHERE branch_code IS NULL"
                      + " AND account_hash = sha256('0000009999'::bytea)",
                  Integer.class);
            });
    assertEquals(0, stored);

    // And the superseded Oracle code is no longer storable.
    org.springframework.dao.DataIntegrityViolationException rejected =
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.dao.DataIntegrityViolationException.class,
            () ->
                tx.executeWithoutResult(
                    status -> {
                      status.setRollbackOnly();
                      jdbcTemplate.update(
                          "INSERT INTO app.omni_check (account_hash, result_code,"
                              + " called_at) VALUES ('sha256('0000009999'::bytea), 2,"
                              + " clock_timestamp())");
                    }));
    assertTrue(
        rejected.getMessage().contains("omni_check_result_code_check"), rejected.getMessage());
  }

  @Test
  void theAuditEventsAreInsideTheHashChainNotMerelyInsertedRows() throws Exception {
    check(UNKNOWN_ACCOUNT, "INVALID", "RETRY");

    // audit.verify_chain() recomputes every content_hash/row_hash from the stored columns and
    // compares the result to the chain head. If chain_append() had not run over the application's
    // insert -- or the insert had supplied its own hashes -- this reports false.
    //
    // Note what this does and does not prove: verify_chain() is the accidental-corruption check,
    // NOT the integrity check against a determined owner (R-036). audit.seal_verify() is that.
    Map<String, Object> verification =
        jdbcTemplate.queryForMap(
            "SELECT ok, checked, reason FROM audit.verify_chain(?::uuid)", accountCheckChainId());

    assertEquals(
        Boolean.TRUE,
        verification.get("ok"),
        "chain verification failed: " + verification.get("reason"));
    assertTrue(((Number) verification.get("checked")).longValue() >= 1);
  }

  @Test
  void aBlankAccountNumberIsRejectedAndNotAudited() throws Exception {
    long eventsBefore = accountCheckEventCount();

    mockMvc
        .perform(
            post("/api/v1/account-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountNumber\":\"\"}"))
        .andExpect(status().isBadRequest());

    // The journey requires *account-check attempts* recorded. Nothing was attempted against the
    // core banking system, so there is no attempt to record.
    assertEquals(eventsBefore, accountCheckEventCount());
  }

  @Test
  void theSeededSystemChainExistsExactlyOnce() {
    // V0034. Two would let the application append to an arbitrary one of them.
    Integer chains =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit.audit_chain"
                + " WHERE chain_kind = 'system' AND subject_id = 'account_check'",
            Integer.class);

    assertEquals(1, chains);
  }

  @Test
  void anActiveAccountWithATerminalProfileIsTerminalAndTheProfileIsUntouched() throws Exception {
    String profileId = createProfileViaContactChannels(ACTIVE_ACCOUNT_WITH_TERMINAL_PROFILE);
    transitionToSubmitted(profileId);
    Map<String, Object> before = profileRow(profileId);

    check(ACTIVE_ACCOUNT_WITH_TERMINAL_PROFILE, "ACTIVE", "TERMINAL");

    Map<String, Object> after = profileRow(profileId);
    assertEquals(before, after, "the existence check must not mutate the profile it read");
    assertEquals("submitted", after.get("status"));
  }

  

  @Test
  void anActiveAccountWithAnIncompleteProfileStillProceeds() throws Exception {
    createProfileViaContactChannels(ACTIVE_ACCOUNT_WITH_INCOMPLETE_PROFILE);

    // Freshly created by ContactChannelsService -- status is 'in_progress', not terminal.
    check(ACTIVE_ACCOUNT_WITH_INCOMPLETE_PROFILE, "ACTIVE", "PROCEED");
  }

  @Test
  void aRelaunchDuringAnActivePhoneLockReturnsBlockedWithTheRealUnlockTimestamp() throws Exception {
    // S4-06/BL-021: customer.md Stage 0's "blocked until [time]" screen -- a relaunch (this same
    // account-check endpoint, called with no local session) must surface the lock, not silently
    // resume PROCEED into Stage 2 only to lock again on the next attempt.
    String profileId = createProfileViaContactChannels(ACTIVE_ACCOUNT_WITH_PHONE_LOCK);
    java.sql.Timestamp until = java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(900));
    jdbcTemplate.update(
        "UPDATE app.profile SET phone_lock_until = ?, phone_lock_escalated = true"
            + " WHERE profile_id = ?::uuid",
        until,
        profileId);

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/account-check")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{ "\",\"accountNumber\":\""
                            + ACTIVE_ACCOUNT_WITH_PHONE_LOCK
                            + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("ACTIVE"))
            .andExpect(jsonPath("$.continuation").value("BLOCKED"))
            .andReturn();

    // Compared against what was actually READ BACK, not the java.sql.Timestamp originally
    // written: PostgreSQL's timestamptz stores microsecond precision, so a Timestamp authored
    // with a differently-rounded nanosecond component would not compare equal to what the
    // service later reads back and reports, even though both are the same instant to the
    // precision that matters.
    java.sql.Timestamp storedUntil =
        jdbcTemplate.queryForObject(
            "SELECT phone_lock_until FROM app.profile WHERE profile_id = ?::uuid",
            java.sql.Timestamp.class,
            profileId);
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertEquals(storedUntil.toInstant().toString(), body.get("blockedUntil").asText());
  }

  @Test
  void aProbeOfAnUnrelatedNeverLockedAccountLearnsNothingNew() throws Exception {
    // The other half of BL-021's safety claim: an ordinary ACTIVE account with no phone lock
    // must carry a null blockedUntil -- adding the signal must not widen what an unauthenticated
    // probe of an unrelated account learns. Asserted against an explicit null value, not
    // doesNotExist(): no Jackson NON_NULL inclusion is configured anywhere in this backend, so
    // the field IS serialised as "blockedUntil":null on every non-blocked response --
    // doesNotExist() alone cannot distinguish an absent field from that (found by
    // @agent-reviewer, S4-06, second pass).
    mockMvc
        .perform(
            post("/api/v1/account-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"accountNumber\":\"" + ACTIVE_ACCOUNT + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("ACTIVE"))
        .andExpect(jsonPath("$.continuation").value("PROCEED"))
        .andExpect(jsonPath("$.blockedUntil").value(org.hamcrest.Matchers.nullValue()));
  }

  /** Creates a real, live profile through the same Stage 1b endpoint a customer would call. */
  private String createProfileViaContactChannels(String accountNumber) throws Exception {
    String body =
        "{ "\",\"accountNumber\":\""
            + accountNumber
            + "\",\"phoneNumber\":\"+2499000"
            + accountNumber.substring(accountNumber.length() - 4)
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
   * fru_app} in one transaction so the deferred {@code profile_status_requires_history} constraint
   * trigger sees the matching history row at commit — same technique the S3-06 report used to build
   * its regression fixtures. Reuses the profile's own {@code session_created} audit event id for
   * the history row's FK, a test-only shortcut: no Stage 11/12 service code exists yet to write a
   * real {@code profile_submitted} event.
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

  private Map<String, Object> profileRow(String profileId) {
    return jdbcTemplate.queryForMap(
        "SELECT status, status_changed_at, row_version FROM app.profile WHERE profile_id = ?::uuid",
        profileId);
  }

  private void assertAuditedAttempt(
      String requestId, String accountNumber, int expectedResultCode, String expectedOutcome)
      throws Exception {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT event_type, actor_kind, actor_id, profile_id, session_id, payload_json, seq"
                + " FROM audit.audit_event WHERE request_id = ?::uuid",
            requestId);

    // Against the STUB there is no HTTP exchange, so no account_check_requested event and no
    // artifacts (S3-02): exactly one event per attempt. Against the http adapter it is two --
    // proven by AccountCheckServiceTest, since this class runs on the stub by configuration.
    assertEquals(1, rows.size(), "exactly one audit event per attempt against the stub");
    Map<String, Object> row = rows.get(0);

    assertEquals("account_check_attempted", row.get("event_type"));
    assertEquals("customer", row.get("actor_kind"));
    assertNull(row.get("actor_id"));
    assertNull(row.get("profile_id"), "Stage 1a creates no profile");
    assertNull(row.get("session_id"), "the session begins at Stage 1b");
    assertTrue(((Number) row.get("seq")).longValue() >= 1, "chain_append() assigns the sequence");

    JsonNode payload = objectMapper.readTree((String) row.get("payload_json"));
 
    assertEquals(accountNumber, payload.get("accountNumber").asText());
    assertEquals(expectedResultCode, payload.get("resultCode").asInt());
    assertEquals(expectedOutcome, payload.get("outcome").asText());
  }

  private long accountCheckEventCount() {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit.audit_event WHERE event_type = 'account_check_attempted'",
            Long.class);
    return count == null ? 0L : count;
  }

  private String accountCheckChainId() {
    return jdbcTemplate.queryForObject(
        "SELECT chain_id::text FROM audit.audit_chain"
            + " WHERE chain_kind = 'system' AND subject_id = 'account_check'",
        String.class);
  }
}
