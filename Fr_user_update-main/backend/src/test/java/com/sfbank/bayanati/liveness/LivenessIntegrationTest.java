package com.sfbank.bayanati.liveness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.uqudo.stub.StubUqudoClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S3-13: journey Stage 10 end to end -- the real HTTP endpoints, the real service, the real JDBC
 * writers, against a real PostgreSQL 18 with every migration applied.
 *
 * <p>Tagged "integration", run with {@code ./mvnw test -Pdb-integration-test}. This class's
 * account-number range -- branch {@code 16}, {@code 0000000411}-{@code 0000000420} -- is disjoint
 * from every other integration class's (see {@link AbstractPostgresIntegrationTest}).
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class LivenessIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String BRANCH = "16";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StubUqudoClient stubUqudoClient;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @Test
  void faceMatchFailureAndLivenessTerminationAreRecordedAsDistinctEventTypes() throws Exception {
    String profileId = acceptedProfile("0000000411", "+249900004111", "IDN-0000000411");

    // A signed JWS with match=false -- a SUCCESSFUL call, per customer.md, not a rejection.
    String faceSessionId = requestFaceToken(profileId).get("faceSessionId").asText();
    String jws = stubUqudoClient.fabricateFaceJws(faceSessionId, false, 5);
    JsonNode resultResponse = submitFaceResult(profileId, faceSessionId, jws);
    assertFalse(resultResponse.get("passed").asBoolean());

    List<String> events = eventTypes(profileId);
    assertTrue(events.contains("face_match_evaluated"));

    Map<String, Object> faceResultRow =
        jdbcTemplate.queryForMap(
            "SELECT match, match_level FROM app.face_result fr JOIN app.identity_cycle ic"
                + " ON ic.cycle_id = fr.cycle_id WHERE ic.profile_id = ?::uuid",
            profileId);
    assertFalse((Boolean) faceResultRow.get("match"));

    Long artifactForEvaluated =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit.audit_artifact a JOIN audit.audit_event e"
                + " ON e.artifact_id = a.artifact_id JOIN audit.audit_chain c"
                + " ON c.chain_id = e.chain_id WHERE c.chain_kind = 'profile' AND c.subject_id = ?"
                + " AND e.event_type = 'face_match_evaluated'",
            Long.class,
            profileId);
    assertEquals(
        1L, artifactForEvaluated, "the raw face-session JWS is stored as an audit artifact");

    // No JWS at all -- the other channel, distinct event type, no artifact.
    String secondFaceSessionId = requestFaceToken(profileId).get("faceSessionId").asText();
    mockMvc
        .perform(
            post("/api/v1/liveness/terminated")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"faceSessionId\":\""
                        + secondFaceSessionId
                        + "\",\"sdkErrorCode\":\"SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS\"}"))
        .andExpect(status().isOk());

    List<String> eventsAfter = eventTypes(profileId);
    assertTrue(eventsAfter.contains("liveness_attempt_terminated"));

    Long artifactForTerminated =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit.audit_artifact a JOIN audit.audit_event e"
                + " ON e.artifact_id = a.artifact_id JOIN audit.audit_chain c"
                + " ON c.chain_id = e.chain_id WHERE c.chain_kind = 'profile' AND c.subject_id = ?"
                + " AND e.event_type = 'liveness_attempt_terminated'",
            Long.class,
            profileId);
    assertEquals(0L, artifactForTerminated, "no JWS was ever produced -- nothing to store");
  }

  @Test
  void terminatedWithAPartialJwsStoresItAsAnArtifactOnTheTerminatedEvent() throws Exception {
    // BL-028: returnDataForIncompleteSession() hands the app a signed partial JWS on the
    // terminated path; the backend verifies it against the issued Face Session id and hash-chains
    // it beside the liveness_attempt_terminated event. One countable attempt, same as without.
    String profileId = acceptedProfile("0000000420", "+249900004201", "IDN-0000000420");
    String faceSessionId = requestFaceToken(profileId).get("faceSessionId").asText();
    String partialJws = stubUqudoClient.fabricateIncompleteFaceJws(faceSessionId, false, 1, null);

    mockMvc
        .perform(
            post("/api/v1/liveness/terminated")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "profileId",
                            profileId,
                            "faceSessionId",
                            faceSessionId,
                            "sdkErrorCode",
                            "SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS",
                            "partialJws",
                            partialJws))))
        .andExpect(status().isOk());

    Map<String, Object> artifactRow =
        jdbcTemplate.queryForMap(
            "SELECT a.kind, a.body, e.payload_json::text AS payload FROM audit.audit_artifact a"
                + " JOIN audit.audit_event e ON e.artifact_id = a.artifact_id"
                + " JOIN audit.audit_chain c ON c.chain_id = e.chain_id"
                + " WHERE c.chain_kind = 'profile' AND c.subject_id = ?"
                + " AND e.event_type = 'liveness_attempt_terminated'",
            profileId);
    assertEquals("uqudo_face_jws", artifactRow.get("kind"));
    assertEquals(
        partialJws,
        new String((byte[]) artifactRow.get("body"), java.nio.charset.StandardCharsets.UTF_8),
        "the partial JWS is stored byte-identical, never re-encoded");
    String payload = (String) artifactRow.get("payload");
    assertTrue(payload.contains("\"partialJwsStatus\":\"verified\""), payload);
    assertTrue(payload.contains("\"partialMatch\":false"), payload);

    Integer attempts =
        jdbcTemplate.queryForObject(
            "SELECT liveness_attempts FROM app.profile WHERE profile_id = ?::uuid",
            Integer.class,
            profileId);
    assertEquals(1, attempts, "one countable attempt, exactly as without a partial JWS");
    Long faceResultRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.face_result fr JOIN app.identity_cycle ic"
                + " ON ic.cycle_id = fr.cycle_id WHERE ic.profile_id = ?::uuid",
            Long.class,
            profileId);
    assertEquals(0L, faceResultRows, "evidence, not an outcome -- no face_result row");
  }

  @Test
  void storedFaceAuditTrailBodyIsByteIdenticalToWhatWasServed() throws Exception {
    // AD-004 closed at S5-06: the backend now persists the face-audit-trail image instead of
    // discarding it after checksum verification.
    String profileId = acceptedProfile("0000000418", "+249900004181", "IDN-0000000418");
    String faceSessionId = requestFaceToken(profileId).get("faceSessionId").asText();
    submitFaceResult(
        profileId, faceSessionId, stubUqudoClient.fabricateFaceJws(faceSessionId, true, 5));

    Map<String, Object> auditTrailRow =
        jdbcTemplate.queryForMap(
            "SELECT ar.uqudo_image_id, ar.uqudo_checksum, ar.body FROM app.artifact_ref ar"
                + " JOIN app.identity_cycle ic ON ic.cycle_id = ar.cycle_id"
                + " WHERE ic.profile_id = ?::uuid AND ar.kind = 'face_audit_trail'",
            profileId);
    byte[] expected =
        stubUqudoClient.downloadImage(
            (String) auditTrailRow.get("uqudo_image_id"),
            (String) auditTrailRow.get("uqudo_checksum"));
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        expected, (byte[]) auditTrailRow.get("body"));
  }

  @Test
  void aPurgedReferenceImageIsRefusedAsConflictNotA500() throws Exception {
    // Found by @agent-reviewer: app.purge_abandoned_artifacts() (S5-06) can null a profile's
    // portrait_uqudo body after 90 days abandoned; abandoned -> in_progress reactivation
    // (ContactChannelsService, Stage 1b re-entry) can then bring the customer straight back to
    // this same accepted cycle with a token request. Must be a defined "go rescan" outcome
    // (409), never a raw 500.
    String profileId = acceptedProfile("0000000419", "+249900004191", "IDN-0000000419");
    jdbcTemplate.update(
        "UPDATE app.artifact_ref SET body = NULL, state = 'purged'"
            + " WHERE cycle_id = (SELECT cycle_id FROM app.identity_cycle WHERE profile_id ="
            + " ?::uuid) AND kind = 'portrait_uqudo'",
        profileId);

    mockMvc
        .perform(
            post("/api/v1/liveness/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void serverSideThresholdRejectsMatchLevelBelowThreeDespiteVerifiedJws() throws Exception {
    String profileId = acceptedProfile("0000000412", "+249900004121", "IDN-0000000412");
    String faceSessionId = requestFaceToken(profileId).get("faceSessionId").asText();

    // match=true, matchLevel=2 -- the JWS verifies and Uqudo itself calls it a match, but the
    // server-side threshold (default 3, uqudo-sdk.md) still rejects it.
    String jws = stubUqudoClient.fabricateFaceJws(faceSessionId, true, 2);
    JsonNode response = submitFaceResult(profileId, faceSessionId, jws);
    assertFalse(response.get("passed").asBoolean());
    assertEquals(2, response.get("matchLevel").asInt());
  }

  @Test
  void retryAfterFailedAttemptReusesTheAcceptedScanWithNoRescan() throws Exception {
    String profileId = acceptedProfile("0000000413", "+249900004131", "IDN-0000000413");

    String firstFaceSessionId = requestFaceToken(profileId).get("faceSessionId").asText();
    submitFaceResult(
        profileId,
        firstFaceSessionId,
        stubUqudoClient.fabricateFaceJws(firstFaceSessionId, false, 1));

    // A second token issuance succeeds with no new stage-8 token/scan -- same identity_cycle.
    String cycleIdBefore =
        jdbcTemplate.queryForObject(
            "SELECT cycle_id::text FROM app.identity_cycle WHERE profile_id = ?::uuid",
            String.class,
            profileId);

    String secondFaceSessionId = requestFaceToken(profileId).get("faceSessionId").asText();
    JsonNode passResponse =
        submitFaceResult(
            profileId,
            secondFaceSessionId,
            stubUqudoClient.fabricateFaceJws(secondFaceSessionId, true, 5));
    assertTrue(passResponse.get("passed").asBoolean());

    String cycleIdAfter =
        jdbcTemplate.queryForObject(
            "SELECT cycle_id::text FROM app.identity_cycle WHERE profile_id = ?::uuid",
            String.class,
            profileId);
    assertEquals(
        cycleIdBefore, cycleIdAfter, "the retry reuses the same accepted cycle, no rescan");
  }

  @Test
  void exhaustingTheLivenessBudgetBlocksWithPriorStagesIntact() throws Exception {
    String profileId = acceptedProfile("0000000414", "+249900004141", "IDN-0000000414");

    for (int i = 0; i < 5; i++) {
      String faceSessionId = requestFaceToken(profileId).get("faceSessionId").asText();
      submitFaceResult(
          profileId, faceSessionId, stubUqudoClient.fabricateFaceJws(faceSessionId, false, 1));
    }

    String status =
        jdbcTemplate.queryForObject(
            "SELECT status FROM app.profile WHERE profile_id = ?::uuid", String.class, profileId);
    assertEquals("blocked_liveness", status);

    // A further token request is refused while blocked.
    mockMvc
        .perform(
            post("/api/v1/liveness/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isConflict());

    // Everything before stage 10 stays intact and resumable.
    Map<String, Object> scanRow =
        jdbcTemplate.queryForMap(
            "SELECT sr.identity_number FROM app.scan_result sr JOIN app.identity_cycle ic"
                + " ON ic.cycle_id = sr.cycle_id WHERE ic.profile_id = ?::uuid",
            profileId);
    assertEquals("IDN-0000000414", scanRow.get("identity_number"));
  }

  @Test
  void passingFaceMatchDoesNotClearThePortraitArtifact() throws Exception {
    // S5-06: replaces the old face_reference_image bridge column (V0041/V0042, dropped) with a
    // durable read from app.artifact_ref (kind='portrait_uqudo') -- AD-004 retains the portrait
    // permanently, so a pass must NOT clear anything any more. See RISKS.md R-047.
    String profileId = acceptedProfile("0000000415", "+249900004151", "IDN-0000000415");
    assertPortraitArtifactBodyNotNull(profileId);

    String faceSessionId = requestFaceToken(profileId).get("faceSessionId").asText();
    JsonNode response =
        submitFaceResult(
            profileId, faceSessionId, stubUqudoClient.fabricateFaceJws(faceSessionId, true, 5));
    assertTrue(response.get("passed").asBoolean());

    assertPortraitArtifactBodyNotNull(
        profileId, "AD-004 retains the portrait permanently -- a pass no longer clears anything");
  }

  @Test
  void portraitArtifactSurvivesWhenTheIdentityCycleIsSuperseded() throws Exception {
    // The old V0042 trigger cleared face_reference_image here; now retired (S5-06) because the
    // durable copy in app.artifact_ref was never cleared on supersede even before this session --
    // a superseded cycle's other artifacts (doc_front, doc_back, etc.) have always survived as
    // the evidentiary record of that scan attempt. This proves the portrait is now consistent
    // with its own sibling rows, not an anomaly.
    String profileId = acceptedProfile("0000000416", "+249900004161", "IDN-0000000416");
    assertPortraitArtifactBodyNotNull(profileId);

    // Stage 9's "wrong number" outcome supersedes the active cycle (identityscan feature).
    mockMvc
        .perform(
            post("/api/v1/identity-scan/registry-review/wrong-number")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isOk());

    String cycleState =
        jdbcTemplate.queryForObject(
            "SELECT state FROM app.identity_cycle WHERE profile_id = ?::uuid",
            String.class,
            profileId);
    assertEquals("superseded", cycleState, "precondition: the cycle really was superseded");
    assertPortraitArtifactBodyNotNull(
        profileId, "the superseded cycle's portrait_uqudo row is retained, not cleared");
  }

  @Test
  void portraitArtifactSurvivesWhenTheProfileReachesATerminalStatus() throws Exception {
    // The old V0042 trigger cleared face_reference_image here; now retired (S5-06) because no
    // other column is ever nulled on reaching a terminal status in this codebase either --
    // submitted/approved/rejected/terminated_registry_mismatch profiles keep their PII
    // indefinitely as the bank's record (only abandoned profiles are ever purged, and only after
    // 90 days -- app.purge_abandoned_artifacts()).
    String profileId = acceptedProfile("0000000417", "+249900004171", "IDN-0000000417");
    assertPortraitArtifactBodyNotNull(profileId);

    // Stage 9's "wrong details" outcome is terminal (terminated_registry_mismatch) -- it does not
    // touch identity_cycle.state at all, so this proves the profile-side path specifically, not
    // the identity_cycle one exercised by the previous test.
    mockMvc
        .perform(
            post("/api/v1/identity-scan/registry-review/wrong-details")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isOk());

    String statusAfter =
        jdbcTemplate.queryForObject(
            "SELECT status FROM app.profile WHERE profile_id = ?::uuid", String.class, profileId);
    assertEquals("terminated_registry_mismatch", statusAfter);

    assertPortraitArtifactBodyNotNull(
        profileId, "a terminal profile's portrait_uqudo row is retained, not cleared");
  }

  // ---- helpers ----

  private void assertPortraitArtifactBodyNotNull(String profileId) {
    assertPortraitArtifactBodyNotNull(profileId, "precondition: the portrait artifact is stored");
  }

  private void assertPortraitArtifactBodyNotNull(String profileId, String because) {
    byte[] bytes =
        jdbcTemplate.queryForObject(
            "SELECT ar.body FROM app.artifact_ref ar JOIN app.identity_cycle ic"
                + " ON ic.cycle_id = ar.cycle_id"
                + " WHERE ic.profile_id = ?::uuid AND ar.kind = 'portrait_uqudo'",
            byte[].class,
            profileId);
    assertTrue(bytes != null && bytes.length > 0, because);
  }

  private List<String> eventTypes(String profileId) {
    return jdbcTemplate.queryForList(
        "SELECT e.event_type FROM audit.audit_event e"
            + " JOIN audit.audit_chain c ON c.chain_id = e.chain_id"
            + " WHERE c.chain_kind = 'profile' AND c.subject_id = ? ORDER BY e.seq",
        String.class,
        profileId);
  }

  private JsonNode requestFaceToken(String profileId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/liveness/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"profileId\":\"" + profileId + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode submitFaceResult(String profileId, String faceSessionId, String jws)
      throws Exception {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("profileId", profileId);
    body.put("faceSessionId", faceSessionId);
    body.put("jws", jws);
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/liveness/result")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  /**
   * Runs the customer through stage 1b, stage 8 (accepted scan, registry ok) and stage 9's Accept
   * action, landing on {@code in_progress} with one {@code active}, {@code accepted_at IS NOT NULL}
   * identity cycle and a populated {@code portrait_uqudo} {@code app.artifact_ref} row -- the
   * precondition every stage 10 test in this class needs.
   */
  private String acceptedProfile(String accountNumber, String phoneNumber, String identityNumber)
      throws Exception {
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

    mockMvc
        .perform(
            post("/api/v1/identity-scan/registry-review/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isOk());

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
