package com.sfbank.bayanati.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.uqudo.stub.StubUqudoClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
 * S3-13: journey Stage 11 end to end. Tagged "integration", run with {@code ./mvnw test
 * -Pdb-integration-test}. This class's account-number range -- branch {@code 16}, {@code
 * 0000000421}-{@code 0000000426} -- is disjoint from every other integration class's (see {@link
 * AbstractPostgresIntegrationTest}).
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class SignatureIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String BRANCH = "16";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StubUqudoClient stubUqudoClient;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @Test
  void signatureStoredViaTheDrawnRoute() throws Exception {
    String profileId = profileWithPassedLiveness("0000000421", "+249900004211", "IDN-0000000421");

    submitSignature(profileId, "drawn", "image/png", "fake-png-bytes-drawn")
        .andExpect(status().isOk());

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT kind, content_type, body FROM app.artifact_ref"
                + " WHERE profile_id = ?::uuid AND kind = 'signature'",
            profileId);
    assertEquals("image/png", row.get("content_type"));
    assertEquals(
        "fake-png-bytes-drawn",
        new String((byte[]) row.get("body"), StandardCharsets.UTF_8),
        "AD-004 (S5-06): the submitted content is stored byte-identical, not discarded");
  }

  @Test
  void signatureStoredViaTheUploadedRoute() throws Exception {
    String profileId = profileWithPassedLiveness("0000000422", "+249900004221", "IDN-0000000422");

    submitSignature(profileId, "uploaded", "image/jpeg", "fake-jpeg-bytes-uploaded")
        .andExpect(status().isOk());

    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.artifact_ref WHERE profile_id = ?::uuid AND kind = 'signature'",
            Long.class,
            profileId);
    assertEquals(1L, count);

    Long eventCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit.audit_event e JOIN audit.audit_chain c"
                + " ON c.chain_id = e.chain_id WHERE c.chain_kind = 'profile' AND c.subject_id = ?"
                + " AND e.event_type = 'signature_captured'",
            Long.class,
            profileId);
    assertEquals(1L, eventCount);
  }

  @Test
  void resubmittingASignatureReplacesTheRowInPlace() throws Exception {
    // Regression (@agent-reviewer, S3-13 first pass): app.artifact_ref's only relevant constraint
    // is UNIQUE(cycle_id, kind), and 'signature' rows always have cycle_id NULL -- Postgres does
    // not dedupe NULLs, so a plain INSERT let a redraw (customer.md Stage 11: "a signature pad,
    // with a clear-and-retry control") multiply retained-PII rows instead of replacing the
    // previous attempt (V0046's partial unique index + the upsert in JdbcSignatureRepository).
    String profileId = profileWithPassedLiveness("0000000425", "+249900004251", "IDN-0000000425");

    submitSignature(profileId, "drawn", "image/png", "first-attempt").andExpect(status().isOk());
    submitSignature(profileId, "uploaded", "image/jpeg", "second-attempt-replaces-the-first")
        .andExpect(status().isOk());

    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT content_type FROM app.artifact_ref WHERE profile_id = ?::uuid AND kind = 'signature'",
            profileId);
    assertEquals(1, rows.size(), "the second submission replaces the first, not adds to it");
    assertEquals("image/jpeg", rows.get(0).get("content_type"));
  }

  @Test
  void resubmittingAfterAPurgeResetsStateSoTheRowIsPurgeableAgain() throws Exception {
    // Regression (@agent-reviewer, S4-06, found while reviewing the identical defect in the new
    // salarycertificate upsert): the DO UPDATE SET originally left `state` untouched. A row purged
    // by app.purge_abandoned_artifacts() (V0055, state='purged', body=NULL) and then resubmitted to
    // would have restored `body` while leaving `state` stuck at 'purged' -- and V0055's own `WHERE
    // ar.state <> 'purged'` would then permanently exclude the row from ever being purged again.
    String profileId = profileWithPassedLiveness("0000000426", "+249900004261", "IDN-0000000426");
    submitSignature(profileId, "drawn", "image/png", "original-attempt").andExpect(status().isOk());
    jdbcTemplate.update(
        "UPDATE app.artifact_ref SET body = NULL, state = 'purged'"
            + " WHERE profile_id = ?::uuid AND kind = 'signature'",
        profileId);

    submitSignature(profileId, "uploaded", "image/jpeg", "resubmitted-after-purge")
        .andExpect(status().isOk());

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT state, body FROM app.artifact_ref WHERE profile_id = ?::uuid AND kind = 'signature'",
            profileId);
    assertEquals("committed", row.get("state"));
    assertEquals(
        "resubmitted-after-purge", new String((byte[]) row.get("body"), StandardCharsets.UTF_8));
  }

  @Test
  void signatureRejectedBeforeLivenessCompletes() throws Exception {
    // Stage 1b only -- no scan, no liveness at all.
    String profileId = createProfile("0000000423", "+249900004231");

    submitSignature(profileId, "drawn", "image/png", "fake-png-bytes")
        .andExpect(status().isConflict());
  }

  @Test
  void signatureRejectedForAnUnsupportedContentType() throws Exception {
    String profileId = profileWithPassedLiveness("0000000424", "+249900004241", "IDN-0000000424");

    submitSignature(profileId, "drawn", "application/pdf", "fake-pdf-bytes")
        .andExpect(status().isBadRequest());
  }

  // ---- helpers ----

  private org.springframework.test.web.servlet.ResultActions submitSignature(
      String profileId, String captureMethod, String contentType, String content) throws Exception {
    String base64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("profileId", profileId);
    body.put("captureMethod", captureMethod);
    body.put("contentType", contentType);
    body.put("contentBase64", base64);
    return mockMvc.perform(
        post("/api/v1/signature")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
  }

  /** Runs the customer through stage 1b, 8, 9's Accept and a passing stage 10 result. */
  private String profileWithPassedLiveness(
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
