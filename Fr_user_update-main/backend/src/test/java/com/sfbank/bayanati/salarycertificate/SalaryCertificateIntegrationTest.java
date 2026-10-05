package com.sfbank.bayanati.salarycertificate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.uqudo.stub.StubUqudoClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S4-06: journey Stage 6's optional attachment endpoint end to end (BL-022). Tagged "integration",
 * run with {@code ./mvnw test -Pdb-integration-test}. This class's account-number range — branch
 * {@code 16}, {@code 0000000521}–{@code 0000000530} — is disjoint from every other integration
 * class's (see {@link AbstractPostgresIntegrationTest}).
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class SalaryCertificateIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String BRANCH = "16";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private StubUqudoClient stubUqudoClient;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @Test
  void certificateUploadedAndReadableChecksumVerified() throws Exception {
    String profileId = createProfile("0000000521", "+249900005211");

    submitCertificate(profileId, "image/jpeg", "fake-jpeg-bytes").andExpect(status().isOk());

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT content_type, body FROM app.artifact_ref"
                + " WHERE profile_id = ?::uuid AND kind = 'salary_certificate'",
            profileId);
    assertEquals("image/jpeg", row.get("content_type"));
    assertEquals(
        "fake-jpeg-bytes",
        new String((byte[]) row.get("body"), StandardCharsets.UTF_8),
        "AD-004: the submitted content is stored byte-identical, not discarded");

    // app.artifact_read() is the checksum-verified read path -- confirms the stored body passes
    // its own sha256 check, not merely that a row exists.
    String artifactRefId =
        jdbcTemplate.queryForObject(
            "SELECT artifact_ref_id::text FROM app.artifact_ref"
                + " WHERE profile_id = ?::uuid AND kind = 'salary_certificate'",
            String.class,
            profileId);
    byte[] readBack =
        jdbcTemplate.queryForObject(
            "SELECT app.artifact_read(?::uuid)", byte[].class, artifactRefId);
    assertEquals("fake-jpeg-bytes", new String(readBack, StandardCharsets.UTF_8));
  }

  @Test
  void reuploadingACertificateReplacesTheRowInPlace() throws Exception {
    // Mirrors SignatureIntegrationTest's resubmittingASignatureReplacesTheRowInPlace -- the same
    // V0046 precedent, applied here via V0059's partial unique index.
    String profileId = createProfile("0000000522", "+249900005221");

    submitCertificate(profileId, "image/png", "first-attempt").andExpect(status().isOk());
    submitCertificate(profileId, "application/pdf", "second-attempt-replaces-the-first")
        .andExpect(status().isOk());

    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT content_type FROM app.artifact_ref"
                + " WHERE profile_id = ?::uuid AND kind = 'salary_certificate'",
            profileId);
    assertEquals(1, rows.size(), "the second upload replaces the first, not adds to it");
    assertEquals("application/pdf", rows.get(0).get("content_type"));
  }

  @Test
  void reuploadingAfterAPurgeResetsStateSoTheRowIsPurgeableAgain() throws Exception {
    // Regression (@agent-reviewer, S4-06): the upsert's DO UPDATE SET originally left `state`
    // untouched. A row purged by app.purge_abandoned_artifacts() (V0055, state='purged',
    // body=NULL) and then re-uploaded to would have restored `body` while leaving `state` stuck
    // at 'purged' -- and V0055's own `WHERE ar.state <> 'purged'` would then permanently exclude
    // the row from ever being purged again, even though it once again holds a live, ungoverned
    // body. Simulates exactly the row shape V0055 leaves behind, without running the 90-day sweep
    // itself (already proven separately by ArtifactStorageIntegrationTest).
    String profileId = createProfile("0000000527", "+249900005271");
    submitCertificate(profileId, "image/png", "original-attempt").andExpect(status().isOk());
    jdbcTemplate.update(
        "UPDATE app.artifact_ref SET body = NULL, state = 'purged'"
            + " WHERE profile_id = ?::uuid AND kind = 'salary_certificate'",
        profileId);

    submitCertificate(profileId, "image/jpeg", "re-uploaded-after-purge")
        .andExpect(status().isOk());

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT state, body FROM app.artifact_ref"
                + " WHERE profile_id = ?::uuid AND kind = 'salary_certificate'",
            profileId);
    assertEquals("committed", row.get("state"));
    assertEquals(
        "re-uploaded-after-purge", new String((byte[]) row.get("body"), StandardCharsets.UTF_8));
  }

  @Test
  void submissionSucceedsWithNoCertificateUploadedAtAll() throws Exception {
    // customer.md Stage 6: "gates nothing -- no status, no completion, no operator action
    // depends on it." A profile with signature+liveness but NO salary certificate must still
    // reach `submitted` -- proves the new upload code adds no gate to SubmissionService.
    String profileId = profileReadyToSubmit("0000000523", "+249900005231", "IDN-0000000523");

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
    assertNotNull(response.get("referenceNumber").asText());

    Long certificateCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.artifact_ref"
                + " WHERE profile_id = ?::uuid AND kind = 'salary_certificate'",
            Long.class,
            profileId);
    assertEquals(0L, certificateCount);
  }

  @Test
  void anOversizedCertificateIsRejectedAtTheBoundary() throws Exception {
    String profileId = createProfile("0000000524", "+249900005241");
    String tooBig = "x".repeat(10 * 1024 * 1024 + 1);

    submitCertificate(profileId, "image/jpeg", tooBig).andExpect(status().isBadRequest());

    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.artifact_ref"
                + " WHERE profile_id = ?::uuid AND kind = 'salary_certificate'",
            Long.class,
            profileId);
    assertEquals(0L, count);
  }

  @Test
  void anUnsupportedContentTypeIsRejectedAtTheBoundary() throws Exception {
    String profileId = createProfile("0000000525", "+249900005251");

    submitCertificate(profileId, "application/zip", "not-a-real-certificate")
        .andExpect(status().isBadRequest());
  }

  @Test
  void aTerminalProfileRefusesFurtherCertificateUploads() throws Exception {
    String profileId = createProfile("0000000526", "+249900005261");
    transitionToSubmitted(profileId);

    submitCertificate(profileId, "image/png", "too-late-now").andExpect(status().isConflict());
  }

  @Test
  void anUnknownProfileIs404() throws Exception {
    submitCertificate(java.util.UUID.randomUUID().toString(), "image/png", "content")
        .andExpect(status().isNotFound());
  }

  // ---- helpers ----

  private org.springframework.test.web.servlet.ResultActions submitCertificate(
      String profileId, String contentType, String content) throws Exception {
    String base64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("profileId", profileId);
    body.put("contentType", contentType);
    body.put("contentBase64", base64);
    return mockMvc.perform(
        post("/api/v1/salary-certificate")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
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

  /**
   * Runs the customer through stage 1b, a passing stage 8/9/10 result and stage 11 -- the minimum
   * SubmissionService actually requires. Salary certificate is deliberately NOT uploaded here,
   * proving stage 12 does not need it.
   */
  private String profileReadyToSubmit(
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
        .andExpect(status().isOk());

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
        .andExpect(status().isOk());

    Map<String, Object> signatureBody = new LinkedHashMap<>();
    signatureBody.put("profileId", profileId);
    signatureBody.put("captureMethod", "drawn");
    signatureBody.put("contentType", "image/png");
    signatureBody.put(
        "contentBase64",
        Base64.getEncoder().encodeToString("sig-bytes".getBytes(StandardCharsets.UTF_8)));
    mockMvc
        .perform(
            post("/api/v1/signature")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signatureBody)))
        .andExpect(status().isOk());

    return profileId;
  }

  /**
   * Legal single-hop transition (V0020: {@code in_progress -> submitted}), built by hand — same
   * technique as {@code AccountCheckIntegrationTest.transitionToSubmitted}.
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
}
