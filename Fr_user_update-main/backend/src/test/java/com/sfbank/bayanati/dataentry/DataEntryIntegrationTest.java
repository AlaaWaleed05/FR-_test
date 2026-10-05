package com.sfbank.bayanati.dataentry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S3-11: journey Stages 3-6 end to end — the real HTTP endpoints, the real service, the real JDBC
 * writers and the real JDBC audit writer, against a real PostgreSQL 18 with every migration
 * applied. Also carries the BL-016 (E.164) live proofs, since they exercise the same real {@code
 * /api/v1/contact-channels} endpoint and the same audit-chain machinery this class already sets up.
 *
 * <p>Tagged "integration", run with {@code ./mvnw test -Pdb-integration-test}. Container and
 * dynamic properties come from {@link AbstractPostgresIntegrationTest}. This class's account-number
 * range — branch {@code 16}, {@code 0000000301}-{@code 0000000307} for the data-entry proofs,
 * {@code 0000000320}-{@code 0000000322} for the E.164 proofs, {@code 0000000330} for the stage 7
 * proof, and {@code 0000000331}-{@code 0000000333} for the S4-04 version-pinning proofs — is
 * disjoint from every other integration class's (see {@link AbstractPostgresIntegrationTest}'s
 * Javadoc for the full map, updated by this task).
 *
 * <p><strong>S4-04's pinning tests are the one place in this class that mutates shared {@code ref}
 * schema state</strong> — a throwaway {@code occupation} version 2, inserted to prove the pinning
 * behaviour, and {@code occupation}'s {@code is_current} pointer — rather than only writing into
 * its own disjoint {@code app.profile} rows. Both are fully undone in a {@code finally} block
 * ({@link #restoreOccupationToOnlyVersionOne}: the version-2 rows are deleted, not merely left with
 * {@code is_current = false}), so every other class in the same shared container still sees {@code
 * occupation} exactly as it was seeded, matching {@link AbstractPostgresIntegrationTest}'s
 * isolation contract. A first review pass found the original version of this fix left the version-2
 * rows in place, which {@code AppSchemaConnectivityIntegrationTest}'s unscoped {@code occupation}
 * row-count assertion silently doubled under a reversed run order — proven live and closed same
 * session.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class DataEntryIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String BRANCH = "16";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @Test
  void stage3FirstSubmissionPersistsAndAuditsWithNoPreviousValues() throws Exception {
    String profileId = createProfile("0000000301", "+249900003011");

    submitStage3(
            profileId,
            "f",
            "Nubian",
            "SD",
            "single",
            null,
            null,
            null,
            6,
            "SD",
            "11",
            null,
            "Khartoum")
        .andExpect(status().isOk());

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT sex_declared, ethnicity, country_of_residence_code, marital_status,"
                + " education_level, birth_country_code, birth_state_code, birth_city_text"
                + " FROM app.profile_customer_data WHERE profile_id = ?::uuid",
            profileId);
    assertEquals("f", row.get("sex_declared"));
    assertEquals("Nubian", row.get("ethnicity"));
    assertEquals("SD", row.get("country_of_residence_code"));
    assertEquals("Khartoum", row.get("birth_city_text"));

    List<Map<String, Object>> events = stage3Events(profileId);
    assertEquals(1, events.size(), events.toString());
    String payload = (String) events.get(0).get("payload_json");
    assertTrue(payload.contains("\"previousEthnicity\":null"), payload);
    assertTrue(payload.contains("\"ethnicity\":\"Nubian\""), payload);
  }

  @Test
  void resubmittingAStageStoresTheNewValueAndKeepsThePreviousOneInAudit() throws Exception {
    String profileId = createProfile("0000000302", "+249900003021");
    submitStage3(
            profileId,
            "f",
            "Nubian",
            "SD",
            "single",
            null,
            null,
            null,
            6,
            "SD",
            "11",
            null,
            "Khartoum")
        .andExpect(status().isOk());

    submitStage3(
            profileId,
            "f",
            "Beja",
            "SD",
            "married",
            "Amina",
            true,
            2,
            6,
            "SD",
            "11",
            null,
            "Khartoum")
        .andExpect(status().isOk());

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT ethnicity, marital_status, spouse_name, children_count"
                + " FROM app.profile_customer_data WHERE profile_id = ?::uuid",
            profileId);
    assertEquals("Beja", row.get("ethnicity"), "the new value is stored");
    assertEquals("married", row.get("marital_status"));
    assertEquals("Amina", row.get("spouse_name"));
    assertEquals(2, row.get("children_count"));

    List<Map<String, Object>> events = stage3Events(profileId);
    assertEquals(2, events.size(), events.toString());
    String secondPayload = (String) events.get(1).get("payload_json");
    assertTrue(
        secondPayload.contains("\"previousEthnicity\":\"Nubian\""),
        "the previous value is recoverable from audit: " + secondPayload);
    assertTrue(secondPayload.contains("\"ethnicity\":\"Beja\""), secondPayload);
  }

  /**
   * Live proof, not a mocked-transaction-manager unit test (CLAUDE.md "live proof versus a passing
   * test"): {@code DataEntryServiceTest}'s equivalent case cannot catch the S3-07-style bug of
   * writing the {@code data_entry_rejected} event <em>inside</em> the callback that then throws,
   * because a mocked {@code PlatformTransactionManager} never actually rolls anything back. Against
   * the real database, that ordering bug would roll the event back with the (otherwise empty)
   * transaction and this test would fail.
   */
  @Test
  void terminalProfileRejectionIsStillAuditedAgainstARealTransaction() throws Exception {
    String profileId = createProfile("0000000307", "+249900003071");
    transitionToSubmitted(profileId);

    submitStage3(
            profileId,
            "f",
            "Nubian",
            "SD",
            "single",
            null,
            null,
            null,
            6,
            "SD",
            "11",
            null,
            "Khartoum")
        .andExpect(status().isConflict());

    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            "SELECT e.payload_json FROM audit.audit_event e"
                + " JOIN audit.audit_chain c ON c.chain_id = e.chain_id"
                + " WHERE c.chain_kind = 'profile' AND c.subject_id = ? AND e.event_type = 'data_entry_rejected'",
            profileId);
    assertEquals(1, events.size(), "the rejection is durably recorded: " + events);
    assertTrue(((String) events.get(0).get("payload_json")).contains("\"stage\":\"stage3\""));

    Long rowCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.profile_customer_data"
                + " WHERE profile_id = ?::uuid AND ethnicity IS NOT NULL",
            Long.class,
            profileId);
    assertEquals(0L, rowCount, "the terminal profile's data was not overwritten");
  }

  @Test
  void unknownCountryCodeIsRejectedAtTheBoundary() throws Exception {
    String profileId = createProfile("0000000306", "+249900003061");

    submitStage3(
            profileId,
            "f",
            "Nubian",
            "ZZ",
            "single",
            null,
            null,
            null,
            6,
            "SD",
            "11",
            null,
            "Khartoum")
        .andExpect(status().isBadRequest());

    Long rowCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.profile_customer_data"
                + " WHERE profile_id = ?::uuid AND country_of_residence_code IS NOT NULL",
            Long.class,
            profileId);
    assertEquals(0L, rowCount, "no partial write on rejection");
  }

  @Test
  void unknownOccupationCodeIsRejectedAtTheBoundary() throws Exception {
    String profileId = createProfile("0000000303", "+249900003031");

    mockMvc
        .perform(
            post("/api/v1/data-entry/stage4")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"occupationCode\":\"999999\","
                        + "\"incomeSources\":[{\"code\":\"RATIB\",\"primary\":true}],"
                        + "\"monthlyExpensesSdg\":\"5000\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void fractionalMonthlyExpensesIsRejectedAtTheBoundary() throws Exception {
    String profileId = createProfile("0000000304", "+249900003041");

    mockMvc
        .perform(
            post("/api/v1/data-entry/stage4")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"occupationCode\":\"86\","
                        + "\"incomeSources\":[{\"code\":\"RATIB\",\"primary\":true}],"
                        + "\"monthlyExpensesSdg\":\"1234.56\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void twoPrimaryIncomeSourcesAreRejected() throws Exception {
    String profileId = createProfile("0000000305", "+249900003051");

    mockMvc
        .perform(
            post("/api/v1/data-entry/stage4")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"occupationCode\":\"86\","
                        + "\"incomeSources\":[{\"code\":\"RATIB\",\"primary\":true},"
                        + "{\"code\":\"PENSION\",\"primary\":true}],"
                        + "\"monthlyExpensesSdg\":\"5000\"}"))
        .andExpect(status().isBadRequest());
  }

  // ---- BL-016 (E.164) ----

  @Test
  void localFormatPhoneNumberIsNormalisedToE164() throws Exception {
    String profileId = createProfile("0000000320", "0900003201");

    String stored =
        jdbcTemplate.queryForObject(
            "SELECT phone_number FROM app.profile_customer_data WHERE profile_id = ?::uuid",
            String.class,
            profileId);
    assertEquals("+249900003201", stored);
  }

  @Test
  void arabicIndicDigitPhoneNumberIsRejected() throws Exception {
    String body =
        "{\"branch\":\""
            + BRANCH
            + "\",\"accountNumber\":\"0000000321\",\"phoneNumber\":\"+٢٤٩900003211\","
            + "\"sms\":true,\"whatsapp\":true}";

    mockMvc
        .perform(
            post("/api/v1/contact-channels").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void reentryWithTheSamePhoneInADifferentFormatProducesNoFalseDeltaInAudit() throws Exception {
    String accountNumber = "0000000322";
    createProfile(accountNumber, "0900003221");

    // Re-enter with the SAME underlying number, this time already E.164 -- both paths normalise
    // to the identical string, so the re-entry payload must show no change.
    String body =
        "{\"branch\":\""
            + BRANCH
            + "\",\"accountNumber\":\""
            + accountNumber
            + "\",\"phoneNumber\":\"+249900003221\",\"sms\":true,\"whatsapp\":true}";
    mockMvc
        .perform(
            post("/api/v1/contact-channels").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());

    String profileId =
        jdbcTemplate.queryForObject(
            "SELECT profile_id::text FROM app.profile WHERE branch_code = ? AND account_number = ?",
            String.class,
            BRANCH,
            accountNumber);
    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            "SELECT e.payload_json FROM audit.audit_event e"
                + " JOIN audit.audit_chain c ON c.chain_id = e.chain_id"
                + " WHERE c.chain_kind = 'profile' AND c.subject_id = ? AND e.event_type = 'session_reentered'"
                + " ORDER BY e.seq DESC LIMIT 1",
            profileId);
    assertEquals(1, events.size());
    String payload = (String) events.get(0).get("payload_json");
    assertTrue(
        payload.contains("\"previousPhoneNumber\":\"+249900003221\""),
        "no false delta -- previous and new are the same normalised number: " + payload);
  }

  // ---- Stage 7 ----

  @Test
  void stage7ValidSubmissionPersistsAndAudits() throws Exception {
    String profileId = createProfile("0000000330", "+249900003301");

    mockMvc
        .perform(
            post("/api/v1/data-entry/stage7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"identityType\":\"passport\"}"))
        .andExpect(status().isOk());

    String stored =
        jdbcTemplate.queryForObject(
            "SELECT identity_type FROM app.profile_customer_data WHERE profile_id = ?::uuid",
            String.class,
            profileId);
    assertEquals("passport", stored);

    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            "SELECT e.payload_json FROM audit.audit_event e"
                + " JOIN audit.audit_chain c ON c.chain_id = e.chain_id"
                + " WHERE c.chain_kind = 'profile' AND c.subject_id = ? AND e.event_type = 'stage7_data_submitted'",
            profileId);
    assertEquals(1, events.size(), events.toString());
    assertTrue(
        ((String) events.get(0).get("payload_json")).contains("\"identityType\":\"passport\""));
  }

  // ---- Version pinning (S4-04, AD-002f §5.3) ----

  /**
   * The motivating case (task step 5): pin a version, publish a newer one, submit a code valid only
   * in the pinned version — accepted. Then the SAME call with no pin (falls back to current) is
   * rejected, proving the change does something. Live proof, not a unit test with mocks: only a
   * real request against the real endpoint proves the wire contract actually carries the pin
   * through to {@code DataEntryService}.
   */
  @Test
  void pinnedOlderVersionAcceptedThenSameCodeRejectedAgainstCurrent() throws Exception {
    String profileIdPinned = createProfile("0000000331", "+249900003311");
    String profileIdUnpinned = createProfile("0000000332", "+249900003321");
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(jdbcUrl(), "fru_migrator", FRU_MIGRATOR_PASSWORD);
    JdbcTemplate migrator = new JdbcTemplate(dataSource);

    try {
      publishThrowawayOccupationVersionTwoWithoutCode86(migrator);

      // Pinned to v1, where "86" still exists -- accepted even though the server has since
      // published a version that dropped it.
      submitStage4(profileIdPinned, "86", "1000", 1, null).andExpect(status().isOk());

      // No pin -- falls back to current (now v2, which no longer has "86"). The identical code
      // that just succeeded above now fails, proving the pin is what made the difference.
      submitStage4(profileIdUnpinned, "86", "1000", null, null).andExpect(status().isBadRequest());
    } finally {
      restoreOccupationToOnlyVersionOne(migrator);
    }
  }

  @Test
  void pinningAVersionThatWasNeverPublishedIsRejected() throws Exception {
    String profileId = createProfile("0000000333", "+249900003331");

    // 0, not e.g. 999: current is 1 here, and a pin ABOVE current is rejected by a different guard
    // (newer-than-current) before versionExists is even consulted -- 0 is below current (so that
    // guard does not fire) and simply was never published, isolating the versionExists rejection.
    submitStage4(profileId, "86", "1000", 0, null).andExpect(status().isBadRequest());
  }

  /**
   * Inserts a throwaway {@code occupation} version 2 — every version-1 row except item code {@code
   * "86"} — and flips {@code is_current} to it. The caller MUST restore the shared container's
   * {@code occupation} list to exactly its pre-test state in a {@code finally} block via {@link
   * #restoreOccupationToOnlyVersionOne} — this mutates the real, shared {@code occupation} list,
   * not a disjoint throwaway {@code list_code}, so every other integration class in the same run
   * (e.g. {@code AppSchemaConnectivityIntegrationTest}'s unscoped {@code count(*) ... WHERE
   * list_code = 'occupation'} row-count assertion, which a leftover version-2 row would silently
   * double) depends on that restoration happening, not merely on {@code is_current} being flipped
   * back.
   */
  private void publishThrowawayOccupationVersionTwoWithoutCode86(JdbcTemplate migrator) {
    migrator.update(
        "INSERT INTO ref.reference_list_version (list_code, version, content_hash, item_count,"
            + " is_current) VALUES ('occupation', 2, decode('00', 'hex'),"
            + " (SELECT count(*) - 1 FROM ref.reference_item"
            + "   WHERE list_code = 'occupation' AND version = 1), false)");
    migrator.update(
        "INSERT INTO ref.reference_item"
            + " (list_code, version, item_code, parent_code, label_ar, label_en, sort_ordinal,"
            + "  is_active, extra)"
            + " SELECT list_code, 2, item_code, parent_code, label_ar, label_en, sort_ordinal,"
            + "        is_active, extra"
            + "   FROM ref.reference_item WHERE list_code = 'occupation' AND version = 1"
            + "    AND item_code <> '86'");
    migrator.update(
        "UPDATE ref.reference_list_version SET is_current = false"
            + " WHERE list_code = 'occupation' AND version = 1");
    migrator.update(
        "UPDATE ref.reference_list_version SET is_current = true"
            + " WHERE list_code = 'occupation' AND version = 2");
  }

  /**
   * Idempotent and safe to call even if {@link #publishThrowawayOccupationVersionTwoWithoutCode86}
   * only partially completed (each statement is a no-op if its target row is already in the desired
   * state or does not exist) — deletes the throwaway version-2 rows entirely rather than only
   * flipping {@code is_current}, which a first review pass found left them permanently in the
   * shared container (see this method's callers' Javadoc). {@code reference_item} is deleted before
   * {@code reference_list_version} to respect the FK.
   *
   * <p><strong>Order within the two {@code UPDATE}s matters</strong> — version 2 must be flipped to
   * {@code false} BEFORE version 1 is flipped to {@code true}, not the other way round: {@code
   * ref_one_current} is a unique partial index on {@code is_current}, and setting version 1 to
   * {@code true} while version 2 is still {@code true} (its state right after {@link
   * #publishThrowawayOccupationVersionTwoWithoutCode86}) violates it immediately — found live by a
   * second review pass (`ERROR: duplicate key value violates unique constraint "ref_one_current"`)
   * against the reversed order this method originally shipped with.
   */
  private void restoreOccupationToOnlyVersionOne(JdbcTemplate migrator) {
    migrator.update(
        "UPDATE ref.reference_list_version SET is_current = false"
            + " WHERE list_code = 'occupation' AND version = 2");
    migrator.update(
        "UPDATE ref.reference_list_version SET is_current = true"
            + " WHERE list_code = 'occupation' AND version = 1");
    migrator.update(
        "DELETE FROM ref.reference_item WHERE list_code = 'occupation' AND version = 2");
    migrator.update(
        "DELETE FROM ref.reference_list_version WHERE list_code = 'occupation' AND version = 2");
  }

  private org.springframework.test.web.servlet.ResultActions submitStage4(
      String profileId,
      String occupationCode,
      String monthlyExpensesSdg,
      Integer occupationListVersion,
      Integer incomeSourceListVersion)
      throws Exception {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("profileId", profileId);
    body.put("occupationCode", occupationCode);
    body.put("incomeSources", List.of(Map.of("code", "RATIB", "primary", true)));
    body.put("monthlyExpensesSdg", monthlyExpensesSdg);
    body.put("occupationListVersion", occupationListVersion);
    body.put("incomeSourceListVersion", incomeSourceListVersion);
    String json = objectMapper.writeValueAsString(body);
    return mockMvc.perform(
        post("/api/v1/data-entry/stage4").contentType(MediaType.APPLICATION_JSON).content(json));
  }

  // ---- helpers ----

  /**
   * Legal single-hop transition (V0020: {@code in_progress -> submitted}), built by hand as {@code
   * fru_app} in one transaction — same technique as {@code ContactChannelsIntegrationTest}. Reuses
   * the profile's own {@code session_created} event id for the history row's FK: a test-only
   * shortcut, since no Stage 11/12 service code exists yet to write a real completion event.
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

  private org.springframework.test.web.servlet.ResultActions submitStage3(
      String profileId,
      String sexDeclared,
      String ethnicity,
      String countryOfResidenceCode,
      String maritalStatus,
      String spouseName,
      Boolean hasChildren,
      Integer childrenCount,
      int educationLevel,
      String birthCountryCode,
      String birthStateCode,
      String birthStateText,
      String birthCityText)
      throws Exception {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("profileId", profileId);
    body.put("sexDeclared", sexDeclared);
    body.put("ethnicity", ethnicity);
    body.put("countryOfResidenceCode", countryOfResidenceCode);
    body.put("maritalStatus", maritalStatus);
    body.put("spouseName", spouseName);
    body.put("hasChildren", hasChildren);
    body.put("childrenCount", childrenCount);
    body.put("educationLevel", educationLevel);
    body.put("birthCountryCode", birthCountryCode);
    body.put("birthStateCode", birthStateCode);
    body.put("birthStateText", birthStateText);
    body.put("birthCityText", birthCityText);
    String json = objectMapper.writeValueAsString(body);
    return mockMvc.perform(
        post("/api/v1/data-entry/stage3").contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private List<Map<String, Object>> stage3Events(String profileId) {
    return jdbcTemplate.queryForList(
        "SELECT e.payload_json FROM audit.audit_event e"
            + " JOIN audit.audit_chain c ON c.chain_id = e.chain_id"
            + " WHERE c.chain_kind = 'profile' AND c.subject_id = ? AND e.event_type = 'stage3_data_submitted'"
            + " ORDER BY e.seq",
        profileId);
  }

  /** Submits Stage 1b through the real endpoint and returns the resulting profile id. */
  private String createProfile(String accountNumber, String phoneNumber) throws Exception {
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
    JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
    return response.get("profileId").asText();
  }
}
