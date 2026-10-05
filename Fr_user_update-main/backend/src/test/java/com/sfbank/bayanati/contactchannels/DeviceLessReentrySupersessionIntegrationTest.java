package com.sfbank.bayanati.contactchannels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.civilregistry.stub.RegistryOutcome;
import com.sfbank.bayanati.civilregistry.stub.StubCivilRegistryClient;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
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
 * BL-041 / AD-008 — a device-less re-entry must not inherit the previous session's identity
 * artifacts. Tagged "integration", run with {@code ./mvnw test -Pdb-integration-test}. Branch
 * {@code 16}, accounts {@code 0000000550}–{@code 0000000560}, disjoint from every other integration
 * class (see {@link AbstractPostgresIntegrationTest}).
 *
 * <p><strong>The attack this class exists to make impossible.</strong> A person enters ANOTHER
 * customer's account number on a new device. Before this fix the Stage 1b re-entry transaction
 * superseded nothing, so they inherited that customer's accepted identity cycle — document scan,
 * extracted document data, Civil Registry data, face match — and, because it is keyed to the
 * profile rather than the cycle, that customer's signature. They could then edit the data, receive
 * every OTP on their own handset, and submit a profile carrying someone else's proof of identity.
 *
 * <p>These tests assert the attack is <em>impossible</em>, not merely that a supersede method was
 * called: they drive the real endpoints and read the real gates. The load-bearing one is {@link
 * #theInheritedSignatureCannotCarryAnImpostorPastStageEleven()}, which plays the attack to its end
 * — supersede, then rescan and pass liveness, which is exactly what AD-008 forces the impostor to
 * do — because a fix that superseded only the cycle passes every other test in this class and still
 * lets the victim's signature reach a submission.
 *
 * <p>No test here submits, so no {@code app.notification_outbox} row is left claimable by another
 * class (Stage 1b's own OTP sends are INTERACTIVE and bypass the outbox entirely).
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class DeviceLessReentrySupersessionIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String BRANCH = "16";
  private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StubUqudoClient stubUqudoClient;
  @Autowired private StubCivilRegistryClient stubCivilRegistryClient;

  /** Real delegate preserved — every send still goes through the real stub. */
  @MockitoSpyBean private MessageSender messageSender;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  // ---------------------------------------------------------------------------------------------
  // The core adversarial test
  // ---------------------------------------------------------------------------------------------

  /**
   * The whole vulnerability, end to end. Every assertion here is a direct wrong-value assertion —
   * none of these states is written by the pre-fix code at all, so none could pass against it.
   */
  @Test
  void aDeviceLessReentrySupersedesEveryInheritedIdentityArtifactAndBlocksSubmission()
      throws Exception {
    String account = "0000000550";
    String profileId = readyToSubmitProfile(account, "+249900005501", "IDN-0000000550");

    // Precondition: this profile really could submit before the re-entry.
    assertEquals(
        "SUBMIT",
        pointerStage(profileId),
        "precondition: a complete identity answers SUBMIT before the re-entry");

    // The attack: the same account number, a DIFFERENT phone, on a device with no local state.
    String reenteredProfileId = submitStage1b(account, "+249900005509");
    assertEquals(
        profileId,
        reenteredProfileId,
        "re-entry resolves to the SAME profile — that is the attack");

    // 1-8: the identity cycle and, with it, every cycle-keyed artifact.
    assertEquals("superseded", cycleState(profileId));
    assertNotNull(
        jdbcTemplate.queryForObject(
            "SELECT superseded_at FROM app.identity_cycle WHERE profile_id = ?::uuid",
            java.sql.Timestamp.class,
            profileId),
        "superseded_at is stamped, so the audit trail can date the revocation");

    // 9: the profile-keyed signature — the one no cycle supersede can reach.
    assertEquals("superseded", signatureState(profileId));

    // The submission gate now refuses, and the S5-08 resume pointer refuses with it. The pointer
    // was built as placement-not-authorization SO this fix could land; this proves it is safe.
    mockMvc
        .perform(
            post("/api/v1/submission")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LIVENESS_REQUIRED"));

    mockMvc
        .perform(
            post("/api/v1/submission/current")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

    // No profile reached 'submitted', and no reference number was ever minted.
    assertEquals("in_progress", profileStatus(profileId));
    assertEquals(
        0L,
        (long)
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app.profile"
                    + " WHERE profile_id = ?::uuid AND reference_number IS NOT NULL",
                Long.class,
                profileId));
  }

  /**
   * The assertion the signature migration exists for, and the one a cycle-only fix fails.
   *
   * <p>AD-008 requires the impostor to rescan. Once they do, a fresh {@code active} accepted cycle
   * and a fresh passed {@code face_result} exist — so every cycle-keyed gate is satisfied again. If
   * the victim's profile-keyed signature were still readable, {@code currentJourneyPointer} would
   * skip SIGNATURE, answer SUBMIT, and the impostor would submit a profile carrying a signature
   * drawn by someone else, never having been shown Stage 11.
   *
   * <p><strong>Needs revert-restore</strong> (a silent no-op would pass the happy path): reverting
   * {@code JdbcSubmissionRepository}'s {@code ar.state = 'committed'} filter makes this fail with
   * SUBMIT.
   */
  @Test
  void theInheritedSignatureCannotCarryAnImpostorPastStageEleven() throws Exception {
    String account = "0000000551";
    String profileId = readyToSubmitProfile(account, "+249900005511", "IDN-0000000551");

    submitStage1b(account, "+249900005519");
    assertEquals("superseded", signatureState(profileId));

    // The impostor does exactly what AD-008 forces them to do: rescan, and pass liveness.
    passScanAndLiveness(profileId, "IDN-0000000551B");

    assertEquals(
        "active",
        cycleState(profileId),
        "precondition: the rescan really did build a fresh accepted cycle");

    // THE assertion. Not SUBMIT.
    assertEquals(
        "SIGNATURE",
        pointerStage(profileId),
        "the victim's signature must not carry the impostor past Stage 11");

    // And the gate agrees with the pointer — a direct submit is refused for the signature, not
    // silently accepted.
    mockMvc
        .perform(
            post("/api/v1/submission")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SIGNATURE_REQUIRED"));
  }

  // ---------------------------------------------------------------------------------------------
  // The same-device path is untouched
  // ---------------------------------------------------------------------------------------------

  /**
   * AD-008 governs the no-local-state case ONLY: "the same-device resume is unchanged". The trigger
   * is journey position — an {@code active} cycle with {@code accepted_at} set — and every
   * legitimate same-device route back into Stage 1b happens BEFORE the Stage 8 scan (the "wrong
   * phone number?" correction lives on the Stage 2 screen). This is that case.
   *
   * <p><strong>Needs revert-restore</strong>: a supersede wired to fire unconditionally — one that
   * reset the budget and superseded the signature on EVERY re-entry rather than only when an active
   * cycle was found — would pass every artifact assertion in the tests above, because those
   * profiles all have an identity to supersede. Only this test catches it. Removing the {@code
   * cyclesSuperseded == 0} early return in {@code JdbcDeviceLessReentrySuperseder} makes it fail.
   */
  @Test
  void aReentryBeforeTheScanSupersedesNothingAndLeavesTheSameDevicePathUntouched()
      throws Exception {
    String account = "0000000552";
    String profileId = createProfileWithVerifiedSms(account, "+249900005521");

    // Spend a scan attempt, so a wrongly-fired budget reset would be visible.
    spendOneScanAttempt(profileId);
    // ...then leave a LIVE pending session standing. BL-039 made the session single-use, so the
    // /cancel inside spendOneScanAttempt now consumes it -- without this second token request
    // budgetBefore would carry NULL handles and the "clears no pending handle" assertion below
    // would compare NULL to NULL, silently ceasing to guard the same-device path it was written
    // for (found by @agent-reviewer against the BL-039 diff).
    issueScanToken(profileId);
    Map<String, Object> budgetBefore = scanBudget(profileId);
    assertEquals(1, ((Number) budgetBefore.get("scan_attempts_passport")).intValue());
    assertEquals(1, ((Number) budgetBefore.get("scan_attempts_total")).intValue());
    assertNotNull(
        budgetBefore.get("pending_scan_session_id"),
        "precondition: a live handle must exist for the assertion below to be able to fail");
    assertNotNull(budgetBefore.get("pending_scan_nonce"));

    // The Stage 2 correction: same device, re-POST Stage 1b with a corrected phone number.
    submitStage1b(account, "+249900005529");

    assertEquals(
        budgetBefore,
        scanBudget(profileId),
        "a same-device re-entry resets no counter and clears no pending handle");
    assertEquals(
        0L,
        artifactCount(profileId, "signature"),
        "precondition: this profile never had a signature to supersede");
    assertEquals(
        0L,
        (long)
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app.identity_cycle"
                    + " WHERE profile_id = ?::uuid AND state = 'superseded'",
                Long.class,
                profileId),
        "nothing was superseded");
    assertEquals(
        0L,
        supersessionAuditEvents(profileId),
        "and no identity_superseded event was written for a path that superseded nothing");
  }

  /**
   * AD-008: "the identity-scan stage — document scan through Civil Registry acceptance — is atomic.
   * A customer who does not complete it in one session does not resume inside it."
   *
   * <p>An {@code active} cycle whose {@code accepted_at} is still NULL is an ordinary reachable
   * state, not an edge case: {@code INSERT_ACTIVE_CYCLE} inserts with it NULL and only Stage 9's
   * "Accept" stamps it, so this is every customer sitting on the registry-review screen.
   * Superseding only ACCEPTED cycles would leave it inheritable — and while the submission gates
   * all require {@code accepted_at} and so could not be passed, a re-entering impostor would be
   * shown the previous customer's extracted document data, Civil Registry record and document
   * images on the Stage 9 review screen, and could stamp {@code accepted_at} on that cycle
   * themselves.
   */
  @Test
  void aDeviceLessReentryMidRegistryReviewSupersedesTheUnacceptedCycleToo() throws Exception {
    String account = "0000000558";
    String profileId = createProfileWithVerifiedSms(account, "+249900005581");
    passScanOnly(profileId, "IDN-0000000558");

    assertEquals("active", cycleState(profileId), "precondition: the scan landed");
    assertNull(
        jdbcTemplate.queryForObject(
            "SELECT accepted_at FROM app.identity_cycle WHERE profile_id = ?::uuid",
            java.sql.Timestamp.class,
            profileId),
        "precondition: Stage 9 review is still open, so accepted_at is NULL");

    submitStage1b(account, "+249900005589");

    assertEquals(
        "superseded",
        cycleState(profileId),
        "the identity-scan stage is atomic — a device-less re-entry does not resume inside it");
    assertEquals(1L, supersessionAuditEvents(profileId));
  }

  /**
   * Found by {@code @agent-reviewer} against this diff, and it is a defect this change INTRODUCED.
   *
   * <p>A registry lookup that fails leaves the profile at status {@code awaiting_registry} with an
   * {@code active}, un-accepted cycle ({@code IdentityScanService} transitions on a non-ok
   * outcome). Superseding that cycle without touching the status strands the profile: {@code
   * issueToken} and {@code submitScan} refuse with {@code RegistryReviewPendingException} while the
   * status says {@code awaiting_registry}, and the three Stage 9 actions plus the review read all
   * enter through {@code requireActiveReview}, which needs the active cycle that was just
   * superseded. V0020's only {@code awaiting_registry -> in_progress} arc is written by {@code
   * retryRegistryLookup}, which itself needs that cycle — and there is no abandonment sweeper, so
   * the profile never self-heals. The only escape would be an operator manual completion.
   *
   * <p>So the supersede also clears the registry pause, which is a legal V0020 transition and is
   * audited with its own {@code app.profile_status_history} row.
   */
  @Test
  void aDeviceLessReentryWhileAwaitingRegistryClearsThePauseInsteadOfStrandingTheProfile()
      throws Exception {
    String account = "0000000559";
    String identityNumber = "IDN-0000000559";
    String profileId = createProfileWithVerifiedSms(account, "+249900005591");

    stubCivilRegistryClient.overrideOutcome(identityNumber, RegistryOutcome.UNREACHABLE);
    passScanOnly(profileId, identityNumber);
    assertEquals(
        "awaiting_registry", profileStatus(profileId), "precondition: the registry pause is on");
    assertEquals("active", cycleState(profileId));

    submitStage1b(account, "+249900005599");

    assertEquals("superseded", cycleState(profileId));
    assertEquals(
        "in_progress",
        profileStatus(profileId),
        "the registry pause must be cleared, or the profile can never move again");

    // The real proof is not the column: it is that the customer can actually rescan.
    mockMvc
        .perform(
            post("/api/v1/identity-scan/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"passport\"}"))
        .andExpect(status().isOk());

    // And the transition is on the permanent record, not silent.
    assertEquals(
        1L,
        (long)
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app.profile_status_history"
                    + " WHERE profile_id = ?::uuid AND from_status = 'awaiting_registry'"
                    + "   AND to_status = 'in_progress' AND actor_kind = 'system'",
                Long.class,
                profileId));
  }

  // ---------------------------------------------------------------------------------------------
  // What AD-008 requires to SURVIVE
  // ---------------------------------------------------------------------------------------------

  /**
   * AD-008: "manually-entered earlier-stage data (contact, social, address) is NOT discarded — the
   * customer lands at their last completed pre-scan stage; only the identity artifacts go." The
   * customer is not punished by re-typing what is not identity-derived.
   */
  @Test
  void manuallyEnteredDataSurvivesTheDeviceLessReentry() throws Exception {
    String account = "0000000553";
    String profileId = createProfileWithVerifiedSms(account, "+249900005531");
    submitStage3(profileId);

    Map<String, Object> before = customerData(profileId);
    assertEquals("Nubian", before.get("ethnicity"), "precondition: stage 3 data really landed");

    // Give this profile a full identity, so the re-entry genuinely triggers supersession.
    passScanAndLiveness(profileId, "IDN-0000000553");
    submitStage1b(account, "+249900005539");

    assertEquals("superseded", cycleState(profileId), "precondition: supersession really fired");
    assertEquals(before, customerData(profileId), "not one customer-entered column was touched");
  }

  /**
   * AD-008 resets the per-type budget on this forced restart ("an interrupted session is not the
   * customer's failure") while keeping its own promise that "the total-based 24-hour block still
   * keys on cumulative attempts across sessions, so abuse stays bounded despite the reset."
   *
   * <p>This is the test that would catch reusing {@code RESUME_FROM_SCAN_BLOCK}, which zeroes all
   * three counters — on an endpoint that is unauthenticated by design (R-051), that would make the
   * 24-hour block resettable at will by anyone holding an account number.
   */
  @Test
  void theScanBudgetResetsPerTypeButTheTotalThatBoundsAbuseSurvives() throws Exception {
    String account = "0000000554";
    String profileId = createProfileWithVerifiedSms(account, "+249900005541");

    spendOneScanAttempt(profileId);
    passScanAndLiveness(profileId, "IDN-0000000554");

    Map<String, Object> before = scanBudget(profileId);
    int totalBefore = ((Number) before.get("scan_attempts_total")).intValue();
    assertTrue(totalBefore > 0, "precondition: attempts really were spent");

    submitStage1b(account, "+249900005549");

    Map<String, Object> after = scanBudget(profileId);
    assertEquals(0, ((Number) after.get("scan_attempts_national_id")).intValue());
    assertEquals(0, ((Number) after.get("scan_attempts_passport")).intValue());
    assertEquals(
        totalBefore,
        ((Number) after.get("scan_attempts_total")).intValue(),
        "scan_attempts_total is what bounds abuse across sessions — it must NOT reset");
    assertEquals(
        before.get("scan_blocked_until"),
        after.get("scan_blocked_until"),
        "the 24-hour block deadline survives a re-entry");
    assertEquals(
        before.get("liveness_attempts"),
        after.get("liveness_attempts"),
        "AD-008 is silent on the liveness budget, so it is deliberately left alone");
    assertEquals(null, after.get("pending_scan_session_id"), "stale challenge material is cleared");
    assertEquals(null, after.get("pending_scan_nonce"));
    assertEquals(null, after.get("pending_face_session_id"));
  }

  /**
   * customer.md l.174-175: "The prior artifacts are retained in the backend, marked superseded, so
   * the audit trail records that a scan occurred and was replaced." Non-inheritance comes from the
   * readers joining {@code ic.state = 'active'}, NOT from destroying evidence.
   *
   * <p>The AD-008 sibling to {@code LivenessIntegrationTest.portraitArtifactSurvivesWhenTheIdentity
   * CycleIsSuperseded}, which asserts the same retention for Stage 9's "wrong number" supersede.
   * That test stays as it is: it exercises a different path, and its assertion is the correct one
   * for this path too.
   */
  @Test
  void supersededArtifactsAreRetainedAsEvidenceEvenThoughTheyAreNoLongerInheritable()
      throws Exception {
    String account = "0000000555";
    String profileId = readyToSubmitProfile(account, "+249900005551", "IDN-0000000555");

    long portraitsBefore = artifactCount(profileId, "portrait_uqudo");
    assertTrue(portraitsBefore > 0, "precondition: the scan really stored a portrait");

    submitStage1b(account, "+249900005559");

    assertEquals(
        portraitsBefore,
        artifactCount(profileId, "portrait_uqudo"),
        "the cycle-keyed portrait row is retained — supersession revokes readability, not evidence");
    assertNotNull(
        jdbcTemplate.queryForObject(
            "SELECT body FROM app.artifact_ref"
                + " WHERE profile_id = ?::uuid AND kind = 'signature'",
            byte[].class,
            profileId),
        "the superseded signature keeps its bytes — customer.md l.174-175");

    // And the scan_result / registry_result rows are still there, still attached to the now
    // superseded cycle: the audit trail records that a scan occurred and was replaced.
    assertEquals(
        1L,
        (long)
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app.scan_result sr"
                    + " JOIN app.identity_cycle ic ON ic.cycle_id = sr.cycle_id"
                    + " WHERE ic.profile_id = ?::uuid AND ic.state = 'superseded'",
                Long.class,
                profileId));
    assertEquals(
        1L,
        (long)
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app.registry_result rr"
                    + " JOIN app.identity_cycle ic ON ic.cycle_id = rr.cycle_id"
                    + " WHERE ic.profile_id = ?::uuid AND ic.state = 'superseded'",
                Long.class,
                profileId));
  }

  /**
   * The supersession is evidence too: one {@code identity_superseded} audit event, actor system.
   */
  @Test
  void theSupersessionWritesOneAuditEventNamingWhatItRevoked() throws Exception {
    String account = "0000000556";
    String profileId = readyToSubmitProfile(account, "+249900005561", "IDN-0000000556");

    submitStage1b(account, "+249900005569");

    assertEquals(1L, supersessionAuditEvents(profileId));
    Map<String, Object> event =
        jdbcTemplate.queryForMap(
            "SELECT e.actor_kind, e.payload_json FROM audit.audit_event e"
                + " JOIN audit.audit_chain c ON c.chain_id = e.chain_id"
                + " WHERE c.chain_kind = 'profile' AND c.subject_id = ?"
                + "   AND e.event_type = 'identity_superseded'",
            profileId);
    assertEquals("system", event.get("actor_kind"));
    String payload = String.valueOf(event.get("payload_json"));
    assertTrue(payload.contains("device_less_reentry"), payload);
    assertTrue(payload.contains("\"cycleSuperseded\":true"), payload);
    assertTrue(payload.contains("\"signatureSuperseded\":true"), payload);
  }

  /**
   * A redraw after the forced restart returns the row to {@code committed} with no extra code —
   * {@code JdbcSignatureRepository}'s upsert already carries {@code state = EXCLUDED.state}.
   * Without this the fix would strand a customer who legitimately re-enters their own profile:
   * superseded once, never signable again.
   */
  @Test
  void aFreshSignatureAfterSupersessionReturnsTheRowToCommitted() throws Exception {
    String account = "0000000557";
    String profileId = readyToSubmitProfile(account, "+249900005571", "IDN-0000000557");

    submitStage1b(account, "+249900005579");
    assertEquals("superseded", signatureState(profileId));

    passScanAndLiveness(profileId, "IDN-0000000557B");
    captureSignature(profileId);

    assertEquals("committed", signatureState(profileId));
    assertEquals(
        1L, artifactCount(profileId, "signature"), "still exactly one signature row per profile");
    assertEquals("SUBMIT", pointerStage(profileId), "and the journey can complete again");
  }

  // ---------------------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------------------

  private String readyToSubmitProfile(
      String accountNumber, String phoneNumber, String identityNumber) throws Exception {
    String profileId = createProfileWithVerifiedSms(accountNumber, phoneNumber);
    passScanAndLiveness(profileId, identityNumber);
    captureSignature(profileId);
    return profileId;
  }

  /** Stage 8 through Stage 10: scan accepted, registry accepted, liveness passed. */
  private void passScanAndLiveness(String profileId, String identityNumber) throws Exception {
    passScanOnly(profileId, identityNumber);
    acceptRegistryReview(profileId);
    passLiveness(profileId);
  }

  /** Stage 8 only: the scan lands and the cycle goes active, with accepted_at still NULL. */
  private void passScanOnly(String profileId, String identityNumber) throws Exception {
    MvcResult tokenResult =
        mockMvc
            .perform(
                post("/api/v1/identity-scan/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"passport\"}"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode token = objectMapper.readTree(tokenResult.getResponse().getContentAsString());
    String sessionId = token.get("sessionId").asText();
    String nonce = token.get("nonce").asText();

    Map<String, Object> scanBody = new LinkedHashMap<>();
    scanBody.put("profileId", profileId);
    scanBody.put("sessionId", sessionId);
    scanBody.put("nonce", nonce);
    scanBody.put("documentType", "passport");
    scanBody.put(
        "jws",
        stubUqudoClient.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT, null, sessionId, nonce, identityNumber, false));
    mockMvc
        .perform(
            post("/api/v1/identity-scan/scan-result")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(scanBody)))
        .andExpect(status().isOk());
  }

  private void acceptRegistryReview(String profileId) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/identity-scan/registry-review/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isOk());
  }

  private void passLiveness(String profileId) throws Exception {
    MvcResult faceToken =
        mockMvc
            .perform(
                post("/api/v1/liveness/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"profileId\":\"" + profileId + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    String faceSessionId =
        objectMapper
            .readTree(faceToken.getResponse().getContentAsString())
            .get("faceSessionId")
            .asText();

    Map<String, Object> faceBody = new LinkedHashMap<>();
    faceBody.put("profileId", profileId);
    faceBody.put("faceSessionId", faceSessionId);
    faceBody.put("jws", stubUqudoClient.fabricateFaceJws(faceSessionId, true, 5));
    mockMvc
        .perform(
            post("/api/v1/liveness/result")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(faceBody)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passed").value(true));
  }

  private void captureSignature(String profileId) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("profileId", profileId);
    body.put("captureMethod", "drawn");
    body.put("contentType", "image/png");
    body.put(
        "contentBase64",
        Base64.getEncoder()
            .encodeToString("fake-signature-bytes".getBytes(StandardCharsets.UTF_8)));
    mockMvc
        .perform(
            post("/api/v1/signature")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  /** One scan-token request, returning the sessionId it minted. */
  private String issueScanToken(String profileId) throws Exception {
    MvcResult tokenResult =
        mockMvc
            .perform(
                post("/api/v1/identity-scan/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"passport\"}"))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper
        .readTree(tokenResult.getResponse().getContentAsString())
        .get("sessionId")
        .asText();
  }

  /** Issues a scan token and cancels it, which spends exactly one countable attempt. */
  private void spendOneScanAttempt(String profileId) throws Exception {
    String sessionId = issueScanToken(profileId);
    mockMvc
        .perform(
            post("/api/v1/identity-scan/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"sessionId\":\""
                        + sessionId
                        + "\",\"documentType\":\"passport\"}"))
        .andExpect(status().isOk());
  }

  private void submitStage3(String profileId) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("profileId", profileId);
    body.put("sexDeclared", "f");
    body.put("ethnicity", "Nubian");
    body.put("countryOfResidenceCode", "SD");
    body.put("maritalStatus", "single");
    body.put("spouseName", null);
    body.put("hasChildren", null);
    body.put("childrenCount", null);
    body.put("educationLevel", 6);
    body.put("birthCountryCode", "SD");
    body.put("birthStateCode", "11");
    body.put("birthStateText", null);
    body.put("birthCityText", "Khartoum");
    mockMvc
        .perform(
            post("/api/v1/data-entry/stage3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  /** Stage 1b, returning the profile id. The device-less re-entry IS this call, replayed. */
  private String submitStage1b(String accountNumber, String phoneNumber) throws Exception {
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
    return objectMapper
        .readTree(result.getResponse().getContentAsString())
        .get("profileId")
        .asText();
  }

  private String createProfileWithVerifiedSms(String accountNumber, String phoneNumber)
      throws Exception {
    String profileId = submitStage1b(accountNumber, phoneNumber);
    mockMvc
        .perform(
            post("/api/v1/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"channel\":\"sms\",\"code\":\""
                        + capturedCode(MessageChannel.SMS)
                        + "\"}"))
        .andExpect(status().isOk());
    return profileId;
  }

  // ---------------------------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------------------------

  /** The S5-08 resume pointer's stage, or the problem code when it refuses. */
  private String pointerStage(String profileId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/submission/current")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"profileId\":\"" + profileId + "\"}"))
            .andReturn();
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    return result.getResponse().getStatus() == 200
        ? body.get("stage").asText()
        : body.get("code").asText();
  }

  private String cycleState(String profileId) {
    return jdbcTemplate.queryForObject(
        "SELECT state FROM app.identity_cycle WHERE profile_id = ?::uuid ORDER BY seq DESC LIMIT 1",
        String.class,
        profileId);
  }

  private String signatureState(String profileId) {
    return jdbcTemplate.queryForObject(
        "SELECT state FROM app.artifact_ref WHERE profile_id = ?::uuid AND kind = 'signature'",
        String.class,
        profileId);
  }

  private String profileStatus(String profileId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM app.profile WHERE profile_id = ?::uuid", String.class, profileId);
  }

  private long artifactCount(String profileId, String kind) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM app.artifact_ref ar"
            + " LEFT JOIN app.identity_cycle ic ON ic.cycle_id = ar.cycle_id"
            + " WHERE coalesce(ar.profile_id, ic.profile_id) = ?::uuid AND ar.kind = ?",
        Long.class,
        profileId,
        kind);
  }

  private Map<String, Object> scanBudget(String profileId) {
    return jdbcTemplate.queryForMap(
        "SELECT scan_attempts_national_id, scan_attempts_passport, scan_attempts_total,"
            + " scan_blocked_until, liveness_attempts, liveness_blocked_until,"
            + " pending_scan_session_id, pending_scan_nonce, pending_face_session_id"
            + " FROM app.profile WHERE profile_id = ?::uuid",
        profileId);
  }

  private Map<String, Object> customerData(String profileId) {
    return jdbcTemplate.queryForMap(
        "SELECT sex_declared, ethnicity, country_of_residence_code, marital_status,"
            + " education_level, birth_country_code, birth_state_code, birth_city_text"
            + " FROM app.profile_customer_data WHERE profile_id = ?::uuid",
        profileId);
  }

  private long supersessionAuditEvents(String profileId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM audit.audit_event e"
            + " JOIN audit.audit_chain c ON c.chain_id = e.chain_id"
            + " WHERE c.chain_kind = 'profile' AND c.subject_id = ?"
            + "   AND e.event_type = 'identity_superseded'",
        Long.class,
        profileId);
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
}
