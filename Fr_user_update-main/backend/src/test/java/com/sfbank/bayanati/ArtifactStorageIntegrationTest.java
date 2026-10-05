package com.sfbank.bayanati;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.uqudo.stub.StubUqudoClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
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
 * S5-06: AD-004's two new SQL functions, {@code app.artifact_read()} and {@code
 * app.purge_abandoned_artifacts()}. Tagged "integration", run with {@code ./mvnw test
 * -Pdb-integration-test}. This class's account-number range -- branch {@code 16}, {@code
 * 0000000497}-{@code 0000000500} -- is disjoint from every other integration class's (see {@link
 * AbstractPostgresIntegrationTest}).
 *
 * <p>{@code app.purge_abandoned_artifacts()} deliberately has no grant to {@code fru_app} -- not
 * because {@code fru_app} is structurally incapable of touching an artifact (it already holds
 * {@code UPDATE} on {@code app.artifact_ref}, V0010, for its ordinary writes), but because the
 * BULK, time-based sweep across every abandoned profile stays an admin-only, deliberately-invoked
 * operation, never something the application's own request path can trigger -- see the migration's
 * own comment. So this class opens its own {@code fru_migrator} connection to call it, the same
 * technique {@code ReferenceDocumentPublisherIntegrationTest} uses for a similarly admin-only
 * function. {@code app.artifact_read()} IS granted to {@code fru_app} and is called through the
 * ordinary shared connection.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class ArtifactStorageIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String BRANCH = "16";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private StubUqudoClient stubUqudoClient;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @Test
  void artifactReadReturnsVerifiedBytesAndRefusesATamperedBody() throws Exception {
    String profileId = acceptedScanProfile("0000000497", "+249900004971", "IDN-0000000497");
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT ar.artifact_ref_id, ar.body FROM app.artifact_ref ar"
                + " JOIN app.identity_cycle ic ON ic.cycle_id = ar.cycle_id"
                + " WHERE ic.profile_id = ?::uuid AND ar.kind = 'portrait_uqudo'",
            profileId);
    String artifactRefId = row.get("artifact_ref_id").toString();
    byte[] originalBody = (byte[]) row.get("body");

    byte[] verified =
        jdbcTemplate.queryForObject(
            "SELECT app.artifact_read(?::uuid)", byte[].class, artifactRefId);
    assertArrayEquals(originalBody, verified, "a good row returns its verified bytes");

    // Tamper the stored body directly -- fru_app already holds UPDATE on app.artifact_ref
    // (V0010), the same "any writer with the grant" scenario a real corruption or bug would
    // produce. sha256 is deliberately left untouched, so it now disagrees with body.
    jdbcTemplate.update(
        "UPDATE app.artifact_ref SET body = ?::bytea WHERE artifact_ref_id = ?::uuid",
        "tampered-bytes-do-not-match-the-stored-checksum".getBytes(),
        artifactRefId);

    DataAccessException thrown =
        assertThrows(
            DataAccessException.class,
            () ->
                jdbcTemplate.queryForObject(
                    "SELECT app.artifact_read(?::uuid)", byte[].class, artifactRefId),
            "a checksum mismatch on read must be refused, never silently returned");
    assertTrue(
        thrown.getMostSpecificCause().getMessage().contains("failed checksum verification"),
        thrown.getMostSpecificCause().getMessage());
  }

  @Test
  void artifactReadReturnsNullForAPurgedBodyRatherThanRaising() throws Exception {
    // Absence (never stored, or purged) is a legitimate, expected outcome -- distinct in kind
    // from a checksum mismatch, which IS refused (see the test above). A reactivated abandoned
    // profile whose artifacts were genuinely purged past 90 days must get a defined "go rescan"
    // outcome from LivenessService, not a raw 500 -- this is the DB-level half of that guarantee.
    String profileId = acceptedScanProfile("0000000498", "+249900004981", "IDN-0000000498");
    String artifactRefId =
        jdbcTemplate.queryForObject(
            "SELECT ar.artifact_ref_id::text FROM app.artifact_ref ar"
                + " JOIN app.identity_cycle ic ON ic.cycle_id = ar.cycle_id"
                + " WHERE ic.profile_id = ?::uuid AND ar.kind = 'portrait_uqudo'",
            String.class,
            profileId);
    jdbcTemplate.update(
        "UPDATE app.artifact_ref SET body = NULL, state = 'purged' WHERE artifact_ref_id = ?::uuid",
        artifactRefId);

    byte[] result =
        jdbcTemplate.queryForObject(
            "SELECT app.artifact_read(?::uuid)", byte[].class, artifactRefId);
    assertNull(result);
  }

  @Test
  void purgeAbandonedArtifactsNullsOnlyProfilesAbandonedOver90DaysAndNeverTouchesAuditArtifact()
      throws Exception {
    String eligibleProfile = acceptedScanProfile("0000000499", "+249900004991", "IDN-0000000499");
    transitionToAbandoned(eligibleProfile, "92 days");

    String tooRecentProfile = acceptedScanProfile("0000000500", "+249900005001", "IDN-0000000500");
    transitionToAbandoned(tooRecentProfile, "10 days");

    List<byte[]> eligibleBodiesBefore = artifactBodies(eligibleProfile);
    assertTrue(
        eligibleBodiesBefore.stream().allMatch(b -> b != null && b.length > 0),
        "precondition: the eligible profile's artifacts are stored");

    Long auditArtifactCountBefore =
        jdbcTemplate.queryForObject("SELECT count(*) FROM audit.audit_artifact", Long.class);

    DriverManagerDataSource migratorDataSource =
        new DriverManagerDataSource(jdbcUrl(), "fru_migrator", FRU_MIGRATOR_PASSWORD);
    JdbcTemplate migrator = new JdbcTemplate(migratorDataSource);
    Integer purgedCount =
        migrator.queryForObject("SELECT app.purge_abandoned_artifacts()", Integer.class);
    assertTrue(
        purgedCount != null && purgedCount >= 4, "purged at least the eligible profile's rows");

    List<Map<String, Object>> eligibleRowsAfter =
        jdbcTemplate.queryForList(
            "SELECT ar.body, ar.state FROM app.artifact_ref ar"
                + " JOIN app.identity_cycle ic ON ic.cycle_id = ar.cycle_id"
                + " WHERE ic.profile_id = ?::uuid",
            eligibleProfile);
    assertTrue(!eligibleRowsAfter.isEmpty());
    for (Map<String, Object> r : eligibleRowsAfter) {
      assertNull(r.get("body"), "purged rows must have body nulled");
      assertEquals("purged", r.get("state"));
    }

    List<byte[]> tooRecentBodiesAfter = artifactBodies(tooRecentProfile);
    assertTrue(
        tooRecentBodiesAfter.stream().allMatch(b -> b != null && b.length > 0),
        "an abandoned profile not yet past 90 days must be untouched");

    Long auditArtifactCountAfter =
        jdbcTemplate.queryForObject("SELECT count(*) FROM audit.audit_artifact", Long.class);
    assertEquals(
        auditArtifactCountBefore,
        auditArtifactCountAfter,
        "purge_abandoned_artifacts() never touches audit.audit_artifact -- a different schema,"
            + " different retention, never referenced by that function's body");
  }

  // ---- helpers ----

  /**
   * Deliberately excludes {@code doc_front_frame}/{@code doc_back_frame}: AD-004 never stores their
   * bytes (persistence.md, "raw capture frames... NOT stored"), so their {@code body} is NULL from
   * the moment of accept, not because of anything the purge does.
   */
  private List<byte[]> artifactBodies(String profileId) {
    return jdbcTemplate.queryForList(
        "SELECT ar.body FROM app.artifact_ref ar"
            + " JOIN app.identity_cycle ic ON ic.cycle_id = ar.cycle_id"
            + " WHERE ic.profile_id = ?::uuid"
            + " AND ar.kind NOT IN ('doc_front_frame', 'doc_back_frame')",
        byte[].class,
        profileId);
  }

  /**
   * Legal single-hop transition (V0020: {@code in_progress -> abandoned}), built by hand as {@code
   * fru_app} in one transaction -- same technique {@code
   * ContactChannelsIntegrationTest#transitionToAbandoned} established. {@code last_activity_at} is
   * backdated so the 90-day purge window can be tested without an actual 90-day wait.
   */
  private void transitionToAbandoned(String profileId, String activityAge) {
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
                  "UPDATE app.profile SET status = 'abandoned', status_changed_at = clock_timestamp(),"
                      + " last_activity_at = clock_timestamp() - ?::interval WHERE profile_id = ?::uuid",
                  activityAge,
                  profileId);
              jdbcTemplate.update(
                  "INSERT INTO app.profile_status_history"
                      + " (profile_id, seq, from_status, to_status, actor_kind, audit_event_id)"
                      + " VALUES (?::uuid,"
                      + " (SELECT coalesce(max(seq), 0) + 1 FROM app.profile_status_history WHERE profile_id = ?::uuid),"
                      + " 'in_progress', 'abandoned', 'system', ?::bigint)",
                  profileId,
                  profileId,
                  auditEventId);
            });
  }

  /** Runs the customer through stage 1b and an accepted stage 8 scan (passport, registry ok). */
  private String acceptedScanProfile(
      String accountNumber, String phoneNumber, String identityNumber) throws Exception {
    String profileId = createProfile(accountNumber, phoneNumber);

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
    String jws =
        stubUqudoClient.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT, null, sessionId, nonce, identityNumber, false);

    Map<String, Object> scanBody = new java.util.LinkedHashMap<>();
    scanBody.put("profileId", profileId);
    scanBody.put("sessionId", sessionId);
    scanBody.put("nonce", nonce);
    scanBody.put("documentType", "passport");
    scanBody.put("jws", jws);
    mockMvc
        .perform(
            post("/api/v1/identity-scan/scan-result")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(scanBody)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registryReady").value(true));

    return profileId;
  }

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
