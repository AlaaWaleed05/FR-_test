package com.sfbank.bayanati.identityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.civilregistry.domain.CivilRegistryClient;
import com.sfbank.bayanati.civilregistry.stub.RegistryOutcome;
import com.sfbank.bayanati.civilregistry.stub.StubCivilRegistryClient;
import com.sfbank.bayanati.identityscan.domain.ScanAttemptBudget;
import com.sfbank.bayanati.uqudo.stub.StubUqudoClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S3-12: journey Stages 8 and 9 end to end -- the real HTTP endpoints, the real service, the real
 * JDBC writers and the real JDBC audit writer, against a real PostgreSQL 18 with every migration
 * applied.
 *
 * <p>Tagged "integration", run with {@code ./mvnw test -Pdb-integration-test}. This class's
 * account-number range -- branch {@code 16}, {@code 0000000401}-{@code 0000000410} plus {@code
 * 0000000531} (BL-034; the original ten were all spoken for), {@code 0000000532}-{@code 0000000533}
 * (S5-11), {@code 0000000534} (BL-037), {@code 0000000535}-{@code 0000000536} (BL-043/BL-044) and
 * {@code 0000000537}-{@code 0000000541} (BL-039) -- is disjoint from every other integration
 * class's (see {@link AbstractPostgresIntegrationTest}). Note that {@code 0000000411} is NOT ours:
 * the block above ends at 410 and {@code LivenessIntegrationTest} owns 411-420, which is why
 * BL-037's test continues the 531+ run instead of extending the first block.
 *
 * <p>{@link StubUqudoClient#fabricateJws} and {@link StubCivilRegistryClient#overrideOutcome} are
 * autowired directly, the same mutable-control pattern {@code StubMessageSender.recordedSends()}
 * already established -- there is no real Uqudo SDK or Civil Registry to drive these scenarios
 * with, so the stub beans themselves stand in for "the app + Uqudo's SDK" and "the bank's registry"
 * respectively.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class IdentityScanIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String BRANCH = "16";

  @Autowired private org.springframework.test.web.servlet.MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StubUqudoClient stubUqudoClient;
  @Autowired private StubCivilRegistryClient stubCivilRegistryClient;
  @Autowired private PlatformTransactionManager transactionManager;

  /**
   * BL-044's race test needs to hold one retry inside the registry call while another commits. The
   * spy wraps the SELECTED client bean (the stub, under this profile) and delegates by default, so
   * every other test in this class is unaffected; the latch lives here rather than in {@code
   * civilregistry.stub}, which is main source.
   */
  @MockitoSpyBean(name = "civilRegistryClient")
  private CivilRegistryClient civilRegistryClientSpy;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @Test
  void fullStage8ChainAcceptsScanQueriesRegistryAndAudits() throws Exception {
    String profileId = createProfile("0000000401", "+249900004011");

    JsonNode tokenResponse = requestToken(profileId, "passport");
    String sessionId = tokenResponse.get("sessionId").asText();
    String nonce = tokenResponse.get("nonce").asText();

    String jws =
        stubUqudoClient.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT,
            null,
            sessionId,
            nonce,
            "IDN-0000000401",
            false);

    MvcResult result = submitScan(profileId, sessionId, nonce, "passport", jws);
    result.getResponse().setCharacterEncoding("UTF-8");
    JsonNode scanResponse = objectMapper.readTree(result.getResponse().getContentAsString());
    assertTrue(scanResponse.get("registryReady").asBoolean());
    assertEquals("IDN-0000000401", scanResponse.get("nationalNumber").asText());

    Map<String, Object> cycleRow =
        jdbcTemplate.queryForMap(
            "SELECT state FROM app.identity_cycle WHERE profile_id = ?::uuid", profileId);
    assertEquals("active", cycleRow.get("state"));

    Long scanResultCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.scan_result sr JOIN app.identity_cycle ic"
                + " ON ic.cycle_id = sr.cycle_id WHERE ic.profile_id = ?::uuid",
            Long.class,
            profileId);
    assertEquals(1L, scanResultCount);

    Long artifactCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.artifact_ref ar JOIN app.identity_cycle ic"
                + " ON ic.cycle_id = ar.cycle_id WHERE ic.profile_id = ?::uuid",
            Long.class,
            profileId);
    assertEquals(
        4L,
        artifactCount,
        "passport: front, front frame, Uqudo portrait, plus the Civil Registry's own portrait"
            + " (field 52: store BOTH, display BOTH)");

    List<Map<String, Object>> events = profileEvents(profileId);
    List<String> eventTypes = events.stream().map(e -> (String) e.get("event_type")).toList();
    assertTrue(eventTypes.contains("scan_token_issued"));
    assertTrue(eventTypes.contains("scan_accepted"));
    assertTrue(eventTypes.contains("registry_lookup_completed"));
    assertFalse(
        eventTypes.contains("registry_lookup_requested"),
        "against the stub there is no HTTP exchange, so no request artifact and no event for it");

    // V0062 / BL-030: the returned IDENTITY_NUMBER is stored; the stub returns the number asked.
    String identityNumberReturned =
        jdbcTemplate.queryForObject(
            "SELECT rr.identity_number_returned FROM app.registry_result rr"
                + " JOIN app.identity_cycle ic ON ic.cycle_id = rr.cycle_id"
                + " WHERE ic.profile_id = ?::uuid",
            String.class,
            profileId);
    assertEquals("IDN-0000000401", identityNumberReturned);
  }

  /**
   * BL-034 against a real database: the app retained the enrolment JWS and retried the upload
   * because the acknowledgement was lost (customer.md stage 13). The retry must land on the SAME
   * accepted cycle, not collide with {@code scan_result.uqudo_jti}'s UNIQUE constraint.
   *
   * <p>This proves the persisted truth -- one cycle, one scan_result, one acceptance event -- which
   * is what a mocked transaction manager cannot. It does NOT prove the ordering that makes the bug
   * bite in production: {@code StubUqudoClient.purgeSession} is a documented no-op, so here the
   * retry can still re-download. {@code
   * IdentityScanServiceTest#identicalJtiRetryOfAcceptedCycleReturnsExistingPayloadInsteadOfFailing}
   * is the test that forces the post-purge ordering.
   */
  @Test
  void identicalJwsReuploadReturnsTheSameCycleInsteadOfCollidingOnTheJti() throws Exception {
    String profileId = createProfile("0000000531", "+249900005311");

    JsonNode tokenResponse = requestToken(profileId, "passport");
    String sessionId = tokenResponse.get("sessionId").asText();
    String nonce = tokenResponse.get("nonce").asText();

    String jws =
        stubUqudoClient.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT,
            null,
            sessionId,
            nonce,
            "IDN-0000000531",
            false);

    MvcResult first = submitScan(profileId, sessionId, nonce, "passport", jws);
    first.getResponse().setCharacterEncoding("UTF-8");
    assertEquals(200, first.getResponse().getStatus());
    JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
    String firstCycleId = firstBody.get("cycleId").asText();

    // The identical re-upload the app performs on reconnect. Before BL-034 this was a 409.
    MvcResult retry = submitScan(profileId, sessionId, nonce, "passport", jws);
    retry.getResponse().setCharacterEncoding("UTF-8");
    assertEquals(200, retry.getResponse().getStatus(), "the customer's own retry must not 409");
    JsonNode retryBody = objectMapper.readTree(retry.getResponse().getContentAsString());

    assertEquals(firstCycleId, retryBody.get("cycleId").asText(), "the SAME cycle, not a new one");
    assertEquals("IDN-0000000531", retryBody.get("nationalNumber").asText());
    assertTrue(retryBody.get("registryReady").asBoolean());
    assertEquals(
        firstBody.get("availableImageKinds"),
        retryBody.get("availableImageKinds"),
        "the retry serves the first attempt's stored images");

    Long activeCycles =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.identity_cycle"
                + " WHERE profile_id = ?::uuid AND state = 'active'",
            Long.class,
            profileId);
    assertEquals(1L, activeCycles, "no second cycle was opened");

    Long cycles =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.identity_cycle WHERE profile_id = ?::uuid",
            Long.class,
            profileId);
    assertEquals(1L, cycles, "and nothing was superseded either");

    Long scanResults =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.scan_result sr JOIN app.identity_cycle ic"
                + " ON ic.cycle_id = sr.cycle_id WHERE ic.profile_id = ?::uuid",
            Long.class,
            profileId);
    assertEquals(1L, scanResults);

    // The retry draws no attempt: customer.md counts scans, not uploads of one scan.
    Map<String, Object> budget =
        jdbcTemplate.queryForMap(
            "SELECT scan_attempts_passport, scan_attempts_total FROM app.profile"
                + " WHERE profile_id = ?::uuid",
            profileId);
    assertEquals(0, ((Number) budget.get("scan_attempts_passport")).intValue());
    assertEquals(0, ((Number) budget.get("scan_attempts_total")).intValue());

    // One acceptance in the permanently hash-chained trail, not two.
    List<String> eventTypes =
        profileEvents(profileId).stream().map(e -> (String) e.get("event_type")).toList();
    assertEquals(
        1L,
        eventTypes.stream().filter("scan_accepted"::equals).count(),
        "the recognised retry writes no second scan_accepted");

    // BL-039's regression guard, in the one place it can be read as state rather than inferred
    // from behaviour: making the pending session single-use had exactly one way to break the
    // retry above, and it was consuming it on ACCEPTANCE. The session-equality check sits above
    // the accepted-snapshot short-circuit in submitScan, so a cleared pair here would have turned
    // the retry into a 400 INVALID_SCAN_SESSION -- the exact failure BL-034 removed. The 200 above
    // already depends on this; asserting the columns says WHY, so a future edit that moves the
    // consume onto the accept path fails with a message rather than a puzzling 400.
    Map<String, Object> session =
        jdbcTemplate.queryForMap(
            "SELECT pending_scan_session_id, pending_scan_nonce FROM app.profile"
                + " WHERE profile_id = ?::uuid",
            profileId);
    assertEquals(
        sessionId,
        session.get("pending_scan_session_id"),
        "a successful scan must NOT consume the session -- BL-034's retry re-presents it");
    assertEquals(nonce, session.get("pending_scan_nonce"));
  }

  /**
   * BL-039, against a real database: one token, one attempt. The pending session was an equality
   * check that never expired and was never cleared (V0040, deliberately), so a single issued token
   * could back an unbounded number of failed posts while {@code canAttempt} was consulted only at
   * issuance — {@code scan_attempts_*} sailed past its limit and the customer landed on the 24-hour
   * block with the other document type untouched.
   *
   * <p>Consumed at the moment a try is SPENT, which is why {@code /cancel} is enough to demonstrate
   * it: no JWS, no Uqudo call, just the spend. The second post through the same session is now the
   * bare {@code 400} that {@code InvalidScanSessionException} carries — deliberately NOT one of
   * BL-037's coded 400s, because it spends nothing.
   */
  @Test
  void aSpentAttemptMakesThePendingSessionSingleUse() throws Exception {
    String profileId = createProfile("0000000537", "+249900005371");

    JsonNode tokenResponse = requestToken(profileId, "passport");
    String sessionId = tokenResponse.get("sessionId").asText();
    String nonce = tokenResponse.get("nonce").asText();

    String jws =
        stubUqudoClient.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT,
            null,
            sessionId,
            nonce,
            "IDN-0000000537",
            false);

    cancelScan(profileId, "passport");

    assertNull(
        jdbcTemplate
            .queryForMap(
                "SELECT pending_scan_session_id FROM app.profile WHERE profile_id = ?::uuid",
                profileId)
            .get("pending_scan_session_id"),
        "the spend consumed the session");

    // The post the defect allowed: a perfectly valid JWS, minted for this very session, arriving
    // after the try it was issued for was already spent. Before the fix this was accepted and the
    // attempt would have been chargeable all over again against the same token.
    MvcResult afterSpend = submitScan(profileId, sessionId, nonce, "passport", jws);
    assertEquals(400, afterSpend.getResponse().getStatus());

    // One attempt spent in total -- the refused post added nothing.
    assertEquals(1, attemptsForType(profileId, "passport"));
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT scan_attempts_total FROM app.profile WHERE profile_id = ?::uuid",
            Integer.class,
            profileId));
  }

  /**
   * BL-039's extension beyond the ticket as filed: {@code /registry-review/wrong-number} is the
   * second path that spends a stage-8 attempt, and it went through none of {@code
   * recordFailedAttempt}'s guards. It now answers {@code 409 SCAN_TYPE_EXHAUSTED} — a code the
   * controller already mapped and Stage 9 already routes back to the scan, so no new wire surface.
   *
   * <p>The assertion that matters is the second one: the refusal happens BEFORE {@code
   * supersedeActiveCycleIfAny}, so a call that declines to spend anything does not take the
   * customer's verified identity cycle with it on the way out.
   */
  @Test
  void wrongNumberAtThePerTypeLimitIsRefusedAndLeavesTheCycleActive() throws Exception {
    String profileId = createProfile("0000000538", "+249900005381");

    JsonNode tokenResponse = requestToken(profileId, "passport");
    String sessionId = tokenResponse.get("sessionId").asText();
    String nonce = tokenResponse.get("nonce").asText();
    String jws =
        stubUqudoClient.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT,
            null,
            sessionId,
            nonce,
            "IDN-0000000538",
            false);
    submitScan(profileId, sessionId, nonce, "passport", jws);

    // Spend the passport budget to its limit without touching the accepted cycle: cancels insert
    // ABANDONED cycles at the next seq, exactly the route BL-043's own proof uses.
    jdbcTemplate.update(
        "UPDATE app.profile SET scan_attempts_passport = ?, scan_attempts_total = ?"
            + " WHERE profile_id = ?::uuid",
        5,
        5,
        profileId);

    mockMvc
        .perform(
            post("/api/v1/identity-scan/registry-review/wrong-number")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SCAN_TYPE_EXHAUSTED"));

    assertEquals(
        "active",
        jdbcTemplate.queryForObject(
            "SELECT state FROM app.identity_cycle WHERE profile_id = ?::uuid",
            String.class,
            profileId),
        "a refused wrong-number must not supersede the cycle it declined to spend against");
    assertEquals(5, attemptsForType(profileId, "passport"), "and must count nothing");
  }

  /**
   * S5-11's read-only proof, against a real database. {@code StubCivilRegistryClient} records
   * nothing by design (its own Javadoc: no call log, no counter), so "the registry was never
   * called" cannot be asserted directly here — {@code
   * IdentityScanServiceTest#currentReviewPayloadReturnsTheStoredPayloadWithoutCallingTheRegistry}
   * is where the Mockito {@code never()} lives. What this test proves instead is the persisted
   * consequence, which is the part a mocked repository cannot: {@code registry_result.attempts} and
   * {@code queried_at} are untouched (a live lookup increments and re-stamps both) and no row is
   * added to any table this journey writes.
   */
  @Test
  void stage9ResumeReadReturnsTheStoredPayloadAndChangesNoRow() throws Exception {
    String profileId = createProfile("0000000532", "+249900005321");

    JsonNode tokenResponse = requestToken(profileId, "passport");
    String jws =
        stubUqudoClient.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT,
            null,
            tokenResponse.get("sessionId").asText(),
            tokenResponse.get("nonce").asText(),
            "IDN-0000000532",
            false);

    MvcResult submitted =
        submitScan(
            profileId,
            tokenResponse.get("sessionId").asText(),
            tokenResponse.get("nonce").asText(),
            "passport",
            jws);
    submitted.getResponse().setCharacterEncoding("UTF-8");
    assertEquals(200, submitted.getResponse().getStatus());
    JsonNode submittedBody = objectMapper.readTree(submitted.getResponse().getContentAsString());

    Map<String, Object> registryBefore = registryResultRow(profileId);
    Map<String, Long> countsBefore = journeyRowCounts(profileId);

    MvcResult read = currentReview(profileId);
    read.getResponse().setCharacterEncoding("UTF-8");
    assertEquals(200, read.getResponse().getStatus());
    JsonNode readBody = objectMapper.readTree(read.getResponse().getContentAsString());

    // The same payload the mutating POST returned, rebuilt from storage alone.
    assertEquals(submittedBody.get("cycleId"), readBody.get("cycleId"));
    assertEquals(submittedBody.get("nationalNumber"), readBody.get("nationalNumber"));
    assertEquals(submittedBody.get("registryReady"), readBody.get("registryReady"));
    assertEquals(submittedBody.get("nameArGiven"), readBody.get("nameArGiven"));
    assertEquals(submittedBody.get("dateOfBirth"), readBody.get("dateOfBirth"));
    assertEquals(submittedBody.get("availableImageKinds"), readBody.get("availableImageKinds"));
    assertTrue(readBody.get("registryReady").asBoolean());

    assertEquals(
        registryBefore,
        registryResultRow(profileId),
        "no live lookup fired: attempts and queried_at are untouched");
    assertEquals(countsBefore, journeyRowCounts(profileId), "the read wrote no row anywhere");

    // And it is repeatable, which is the whole point of a resume read.
    assertEquals(200, currentReview(profileId).getResponse().getStatus());
    assertEquals(registryBefore, registryResultRow(profileId));
    assertEquals(countsBefore, journeyRowCounts(profileId));
  }

  /**
   * S5-11's second guard, against a real database. Before it, {@code retryRegistryLookup}
   * re-queried an already-{@code ok} cycle and wrote the outcome over the stored one — and {@code
   * updateRegistryResultOnRetry} writes every name/DOB/address column from the new result, so a
   * retry that hit a registry outage blanked a verified record. Here the stub is switched to
   * NOT_FOUND before the retry: pre-fix, the stored Arabic name would come back null and {@code
   * attempts} would have been bumped.
   */
  @Test
  void retryOnAnAlreadyOkCycleReturnsTheStoredResultInsteadOfOverwritingIt() throws Exception {
    String profileId = createProfile("0000000533", "+249900005331");

    JsonNode tokenResponse = requestToken(profileId, "passport");
    String jws =
        stubUqudoClient.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT,
            null,
            tokenResponse.get("sessionId").asText(),
            tokenResponse.get("nonce").asText(),
            "IDN-0000000533",
            false);

    MvcResult submitted =
        submitScan(
            profileId,
            tokenResponse.get("sessionId").asText(),
            tokenResponse.get("nonce").asText(),
            "passport",
            jws);
    submitted.getResponse().setCharacterEncoding("UTF-8");
    assertEquals(200, submitted.getResponse().getStatus());
    JsonNode submittedBody = objectMapper.readTree(submitted.getResponse().getContentAsString());
    assertTrue(submittedBody.get("registryReady").asBoolean());

    Map<String, Object> registryBefore = registryResultRow(profileId);

    // The registry is now failing. A redundant retry must not carry that into storage.
    stubCivilRegistryClient.overrideOutcome("IDN-0000000533", RegistryOutcome.NOT_FOUND);

    MvcResult retried =
        mockMvc
            .perform(
                post("/api/v1/identity-scan/registry-review/retry")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"profileId\":\"" + profileId + "\"}"))
            .andReturn();
    retried.getResponse().setCharacterEncoding("UTF-8");
    assertEquals(200, retried.getResponse().getStatus());
    JsonNode retriedBody = objectMapper.readTree(retried.getResponse().getContentAsString());

    assertTrue(retriedBody.get("registryReady").asBoolean(), "the good result is not downgraded");
    assertEquals(submittedBody.get("nameArGiven"), retriedBody.get("nameArGiven"));
    assertEquals(submittedBody.get("dateOfBirth"), retriedBody.get("dateOfBirth"));
    assertEquals(
        registryBefore,
        registryResultRow(profileId),
        "the stored registry_result row is byte-identical: no re-query, no overwrite");
  }

  /** {@code app.registry_result} for the profile's active cycle — the row a retry would rewrite. */
  private Map<String, Object> registryResultRow(String profileId) {
    return jdbcTemplate.queryForMap(
        "SELECT rr.state, rr.attempts, rr.queried_at, rr.identity_number_returned,"
            + " rr.name_ar_given, rr.first_names_en, rr.last_name_en, rr.sex_registry,"
            + " rr.date_of_birth, rr.raw_address_ar"
            + " FROM app.registry_result rr JOIN app.identity_cycle ic"
            + " ON ic.cycle_id = rr.cycle_id"
            + " WHERE ic.profile_id = ?::uuid AND ic.state = 'active'",
        profileId);
  }

  /** Row counts for every table this journey writes, so "changed nothing" is a whole-row claim. */
  private Map<String, Long> journeyRowCounts(String profileId) {
    return Map.of(
        "identity_cycle",
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.identity_cycle WHERE profile_id = ?::uuid",
            Long.class,
            profileId),
        "scan_result",
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.scan_result sr JOIN app.identity_cycle ic"
                + " ON ic.cycle_id = sr.cycle_id WHERE ic.profile_id = ?::uuid",
            Long.class,
            profileId),
        "artifact_ref",
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.artifact_ref ar JOIN app.identity_cycle ic"
                + " ON ic.cycle_id = ar.cycle_id WHERE ic.profile_id = ?::uuid",
            Long.class,
            profileId),
        "profile_status_history",
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.profile_status_history WHERE profile_id = ?::uuid",
            Long.class,
            profileId),
        "audit_event",
        (long) profileEvents(profileId).size());
  }

  private MvcResult currentReview(String profileId) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/identity-scan/registry-review/current")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andReturn();
  }

  @Test
  void v0062AdmitsTheRequestArtifactKindAndStillRejectsAnUnknownOne() {
    // The real adapter's request artifact needs a kind V0002 never declared. Proven the way
    // AccountCheckIntegrationTest proves V0060: a fru_app insert inside a rolled-back transaction,
    // then the constraint provoked by name -- which also proves the DROP in V0062 hit the
    // constraint PostgreSQL actually generated for V0002's inline CHECK.
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    String chainId =
        jdbcTemplate.queryForObject(
            "SELECT chain_id::text FROM audit.audit_chain"
                + " WHERE chain_kind = 'system' AND subject_id = 'account_check'",
            String.class);
    String insert =
        "INSERT INTO audit.audit_artifact (chain_id, kind, media_type, body, byte_size, sha256)"
            + " VALUES (?::uuid, ?, 'application/json', '{\"NID\":\"0\"}'::bytea, 11,"
            + " sha256('{\"NID\":\"0\"}'::bytea))";
    Long stored =
        tx.execute(
            status -> {
              status.setRollbackOnly();
              jdbcTemplate.update(insert, chainId, "civil_registry_request");
              return jdbcTemplate.queryForObject(
                  "SELECT count(*) FROM audit.audit_artifact WHERE kind = 'civil_registry_request'",
                  Long.class);
            });
    assertEquals(1L, stored);

    org.springframework.dao.DataIntegrityViolationException rejected =
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.dao.DataIntegrityViolationException.class,
            () ->
                tx.executeWithoutResult(
                    status -> {
                      status.setRollbackOnly();
                      jdbcTemplate.update(insert, chainId, "civil_registry_probe");
                    }));
    assertTrue(rejected.getMessage().contains("audit_artifact_kind_check"), rejected.getMessage());
  }

  @Test
  void storedArtifactBodiesAreByteIdenticalToWhatWasServed() throws Exception {
    // AD-004 closed at S5-06: the backend now persists artifact bytes instead of discarding them
    // after checksum verification. Proves the stage-8 chain end to end -- a Uqudo-served image
    // and the Civil Registry's own portrait both stored byte-identical.
    String profileId = createProfile("0000000406", "+249900004061");
    JsonNode tokenResponse = requestToken(profileId, "national_id");
    String sessionId = tokenResponse.get("sessionId").asText();
    String nonce = tokenResponse.get("nonce").asText();
    String jws =
        stubUqudoClient.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_SDN_ID,
            StubUqudoClient.CARD_VARIANT_LATEST,
            sessionId,
            nonce,
            "IDN-0000000406",
            false);

    submitScan(profileId, sessionId, nonce, "national_id", jws).getResponse();

    Map<String, Object> docFrontRow =
        jdbcTemplate.queryForMap(
            "SELECT ar.uqudo_image_id, ar.uqudo_checksum, ar.body FROM app.artifact_ref ar"
                + " JOIN app.identity_cycle ic ON ic.cycle_id = ar.cycle_id"
                + " WHERE ic.profile_id = ?::uuid AND ar.kind = 'doc_front'",
            profileId);
    byte[] expectedDocFront =
        stubUqudoClient.downloadImage(
            (String) docFrontRow.get("uqudo_image_id"), (String) docFrontRow.get("uqudo_checksum"));
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        expectedDocFront,
        (byte[]) docFrontRow.get("body"),
        "doc_front's stored body is byte-identical to what the stub served");

    byte[] registryPortraitBody =
        (byte[])
            jdbcTemplate
                .queryForMap(
                    "SELECT body FROM app.artifact_ref ar"
                        + " JOIN app.identity_cycle ic ON ic.cycle_id = ar.cycle_id"
                        + " WHERE ic.profile_id = ?::uuid AND ar.kind = 'portrait_registry'",
                    profileId)
                .get("body");
    assertEquals(
        "stub-photograph-not-a-real-image",
        new String(registryPortraitBody, java.nio.charset.StandardCharsets.UTF_8),
        "portrait_registry's stored body matches StubCivilRegistryClient's fixed photograph");

    // AD-004 deliberately excludes raw capture frames from body storage (persistence.md) --
    // the row, its checksum and its byte_size are still written (matching S3-12's pre-existing
    // behaviour), only the bytes are withheld. Found missing coverage under review: reverting
    // IdentityScanService's isCaptureFrame() exclusion left every other test green.
    Map<String, Object> docFrontFrameRow =
        jdbcTemplate.queryForMap(
            "SELECT body, byte_size, sha256 FROM app.artifact_ref ar"
                + " JOIN app.identity_cycle ic ON ic.cycle_id = ar.cycle_id"
                + " WHERE ic.profile_id = ?::uuid AND ar.kind = 'doc_front_frame'",
            profileId);
    assertNull(docFrontFrameRow.get("body"), "doc_front_frame's bytes are deliberately not stored");
    assertTrue((Long) docFrontFrameRow.get("byte_size") > 0, "byte_size still reflects the image");
    org.junit.jupiter.api.Assertions.assertNotNull(
        docFrontFrameRow.get("sha256"), "the checksum is still recorded even though body is not");
  }

  @Test
  void rawJwsIsStoredByteIdenticalInTheAuditTrail() throws Exception {
    String profileId = createProfile("0000000407", "+249900004071");
    JsonNode tokenResponse = requestToken(profileId, "national_id");
    String sessionId = tokenResponse.get("sessionId").asText();
    String nonce = tokenResponse.get("nonce").asText();
    String jws =
        stubUqudoClient.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_SDN_ID,
            StubUqudoClient.CARD_VARIANT_LATEST,
            sessionId,
            nonce,
            "IDN-0000000407",
            false);

    submitScan(profileId, sessionId, nonce, "national_id", jws).getResponse();

    Map<String, Object> artifactRow =
        jdbcTemplate.queryForMap(
            "SELECT a.body, a.kind, a.media_type FROM audit.audit_artifact a"
                + " JOIN audit.audit_event e ON e.artifact_id = a.artifact_id"
                + " JOIN audit.audit_chain c ON c.chain_id = e.chain_id"
                + " WHERE c.chain_kind = 'profile' AND c.subject_id = ? AND e.event_type = 'scan_accepted'",
            profileId);
    assertEquals("uqudo_scan_jws", artifactRow.get("kind"));
    assertEquals("application/jose", artifactRow.get("media_type"));
    byte[] storedBody = (byte[]) artifactRow.get("body");
    assertEquals(
        jws,
        new String(storedBody, java.nio.charset.StandardCharsets.UTF_8),
        "the raw JWS is never re-encoded");

    String payload =
        jdbcTemplate.queryForObject(
            "SELECT e.payload_json FROM audit.audit_event e"
                + " JOIN audit.audit_chain c ON c.chain_id = e.chain_id"
                + " WHERE c.chain_kind = 'profile' AND c.subject_id = ? AND e.event_type = 'scan_accepted'",
            String.class,
            profileId);
    assertFalse(
        payload.contains(jws),
        "the JWS VALUE lives in audit_artifact, never inlined into the permanently hash-chained"
            + " payload_json under any key name");
    assertFalse(
        payload.contains("identityNumber"),
        "the national number is not inlined into the permanently hash-chained payload_json either"
            + " -- it already lives in app.scan_result.identity_number");
  }

  @Test
  void verifiedJwsWithExpiredImagesIsNotAccepted() throws Exception {
    String profileId = createProfile("0000000402", "+249900004021");
    JsonNode tokenResponse = requestToken(profileId, "passport");
    String sessionId = tokenResponse.get("sessionId").asText();
    String nonce = tokenResponse.get("nonce").asText();
    String jws =
        stubUqudoClient.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT, null, sessionId, nonce, "IDN-0000000402", true);

    submitScan(profileId, sessionId, nonce, "passport", jws).getResponse();

    Long cycleCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.identity_cycle WHERE profile_id = ?::uuid",
            Long.class,
            profileId);
    assertEquals(0L, cycleCount, "a verified-but-images-gone scan is NOT accepted");

    Integer totalAttempts =
        jdbcTemplate.queryForObject(
            "SELECT scan_attempts_total FROM app.profile WHERE profile_id = ?::uuid",
            Integer.class,
            profileId);
    assertEquals(0, totalAttempts, "images-unavailable does not count against the retry budget");
  }

  @Test
  void registryUnreachablePausesThenRetrySucceedsWithNoNewTokenOrRescan() throws Exception {
    String profileId = createProfile("0000000403", "+249900004031");
    String identityNumber = "IDN-0000000403";
    stubCivilRegistryClient.overrideOutcome(identityNumber, RegistryOutcome.UNREACHABLE);

    JsonNode tokenResponse = requestToken(profileId, "passport");
    String sessionId = tokenResponse.get("sessionId").asText();
    String nonce = tokenResponse.get("nonce").asText();
    String jws =
        stubUqudoClient.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT, null, sessionId, nonce, identityNumber, false);

    MvcResult scanResult = submitScan(profileId, sessionId, nonce, "passport", jws);
    JsonNode scanResponse = objectMapper.readTree(scanResult.getResponse().getContentAsString());
    assertFalse(scanResponse.get("registryReady").asBoolean());

    String statusAfterScan =
        jdbcTemplate.queryForObject(
            "SELECT status FROM app.profile WHERE profile_id = ?::uuid", String.class, profileId);
    assertEquals("awaiting_registry", statusAfterScan);

    Map<String, Object> scanRow =
        jdbcTemplate.queryForMap(
            "SELECT sr.identity_number FROM app.scan_result sr JOIN app.identity_cycle ic"
                + " ON ic.cycle_id = sr.cycle_id WHERE ic.profile_id = ?::uuid",
            profileId);
    assertEquals(
        identityNumber, scanRow.get("identity_number"), "the scan is intact, no rescan needed");

    stubCivilRegistryClient.overrideOutcome(identityNumber, RegistryOutcome.OK);

    mockMvc
        .perform(
            post("/api/v1/identity-scan/registry-review/retry")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registryReady").value(true));

    String statusAfterRetry =
        jdbcTemplate.queryForObject(
            "SELECT status FROM app.profile WHERE profile_id = ?::uuid", String.class, profileId);
    assertEquals("in_progress", statusAfterRetry);
  }

  @Test
  void wrongNumberSupersedesTheCycleAndCountsAgainstTheBudget() throws Exception {
    String profileId = createProfile("0000000404", "+249900004041");
    JsonNode tokenResponse = requestToken(profileId, "passport");
    String sessionId = tokenResponse.get("sessionId").asText();
    String nonce = tokenResponse.get("nonce").asText();
    String jws =
        stubUqudoClient.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT,
            null,
            sessionId,
            nonce,
            "IDN-0000000404",
            false);
    submitScan(profileId, sessionId, nonce, "passport", jws);

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
    assertEquals("superseded", cycleState);

    Integer passportAttempts =
        jdbcTemplate.queryForObject(
            "SELECT scan_attempts_passport FROM app.profile WHERE profile_id = ?::uuid",
            Integer.class,
            profileId);
    assertEquals(1, passportAttempts, "wrong-number counts against the stage 8 retry budget");
  }

  @Test
  void wrongDetailsIsTerminal() throws Exception {
    String profileId = createProfile("0000000405", "+249900004051");
    JsonNode tokenResponse = requestToken(profileId, "national_id");
    String sessionId = tokenResponse.get("sessionId").asText();
    String nonce = tokenResponse.get("nonce").asText();
    String jws =
        stubUqudoClient.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_SDN_ID,
            StubUqudoClient.CARD_VARIANT_PREVIOUS,
            sessionId,
            nonce,
            "IDN-0000000405",
            false);
    submitScan(profileId, sessionId, nonce, "national_id", jws);

    mockMvc
        .perform(
            post("/api/v1/identity-scan/registry-review/wrong-details")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isOk());

    String status =
        jdbcTemplate.queryForObject(
            "SELECT status FROM app.profile WHERE profile_id = ?::uuid", String.class, profileId);
    assertEquals("terminated_registry_mismatch", status);

    // Terminal -- a further Stage 8 action is refused, and says so distinguishably (BL-033).
    mockMvc
        .perform(
            post("/api/v1/identity-scan/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"passport\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PROFILE_TERMINAL"));
  }

  @Test
  void exhaustingBothDocumentTypesTriggersTheTwentyFourHourBlock() throws Exception {
    String profileId = createProfile("0000000408", "+249900004081");

    for (int i = 0; i < 5; i++) {
      mockMvc
          .perform(
              post("/api/v1/identity-scan/cancel")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"profileId\":\"" + profileId + "\",\"documentType\":\"national_id\"}"))
          .andExpect(status().isOk());
    }
    for (int i = 0; i < 4; i++) {
      mockMvc
          .perform(
              post("/api/v1/identity-scan/cancel")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"passport\"}"))
          .andExpect(status().isOk());
    }

    // 9 spent, still under the block -- but national_id's own 5 are used up. This and the
    // block below are the two 409s a client most needs to tell apart: one means "try your
    // passport", the other means "come back tomorrow".
    mockMvc
        .perform(
            post("/api/v1/identity-scan/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"national_id\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SCAN_TYPE_EXHAUSTED"));

    // BL-039: /cancel now refuses on the same grounds the token request does. Before the fix this
    // returned 200 and pushed scan_attempts_national_id to 6 against a limit of 5 -- the whole
    // defect, on the one endpoint that needs no pending session to reach it.
    mockMvc
        .perform(
            post("/api/v1/identity-scan/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"national_id\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SCAN_TYPE_EXHAUSTED"));
    assertEquals(
        5,
        attemptsForType(profileId, "national_id"),
        "a refused spend must leave the counter exactly where it was");

    // The 10th, on passport, trips the whole-stage block.
    mockMvc
        .perform(
            post("/api/v1/identity-scan/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"passport\"}"))
        .andExpect(status().isOk());

    String status =
        jdbcTemplate.queryForObject(
            "SELECT status FROM app.profile WHERE profile_id = ?::uuid", String.class, profileId);
    assertEquals("blocked_scan", status);

    // Compared as an Instant, not a formatted string: the handler emits this same column
    // through Instant.toString(), so an exact match is both achievable and the real
    // assertion -- a prefix match would let a wrong offset pass.
    Instant blockedUntil =
        jdbcTemplate.queryForObject(
            "SELECT scan_blocked_until FROM app.profile WHERE profile_id = ?::uuid",
            Instant.class,
            profileId);

    // The 24h block is the one 409 that carries data: without blockedUntil the app cannot
    // render the countdown customer.md Stage 8 specifies.
    mockMvc
        .perform(
            post("/api/v1/identity-scan/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"passport\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SCAN_BLOCKED"))
        .andExpect(jsonPath("$.blockedUntil").value(blockedUntil.toString()));
  }

  /**
   * BL-043 against the real database, which is where the half a mocked test cannot reach lives: the
   * falsified {@code profile_status_history} row. {@code applyScanBlock}'s {@code insertHistory}
   * hard-codes {@code 'in_progress'} as the from-status, and V0020's trigger fires only on a status
   * CHANGE — so re-running it from {@code blocked_scan} pushed {@code scan_blocked_until} out
   * another 24 hours and appended a row claiming a transition that never happened, with neither the
   * check constraint nor the deferred {@code profile_status_requires_history} trigger objecting.
   *
   * <p>The route needs no race: the profile reaches Stage 9 with an active {@code ok} cycle, then
   * the customer goes back to Stage 8 and cancels ten times (five per document type since BL-039).
   * None of that supersedes the {@code ok} cycle — {@code recordFailedAttempt} inserts ABANDONED
   * cycles at the next {@code seq} — so Stage 9's actions are still being offered when the block
   * lands.
   */
  @Test
  void wrongNumberDuringALiveBlockNeitherExtendsItNorWritesAHistoryRow() throws Exception {
    String profileId = createProfile("0000000535", "+249900005351");
    String identityNumber = "IDN-0000000535";

    JsonNode tokenResponse = requestToken(profileId, "passport");
    String jws =
        stubUqudoClient.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT,
            null,
            tokenResponse.get("sessionId").asText(),
            tokenResponse.get("nonce").asText(),
            identityNumber,
            false);
    MvcResult submitted =
        submitScan(
            profileId,
            tokenResponse.get("sessionId").asText(),
            tokenResponse.get("nonce").asText(),
            "passport",
            jws);
    submitted.getResponse().setCharacterEncoding("UTF-8");
    assertEquals(200, submitted.getResponse().getStatus());
    assertTrue(
        objectMapper
            .readTree(submitted.getResponse().getContentAsString())
            .get("registryReady")
            .asBoolean(),
        "the profile is genuinely at Stage 9 with a verified registry result");

    // Back to Stage 8 and spend the whole budget without ever touching the active ok cycle.
    // Five per type since BL-039 raised the limits (was three and three).
    for (int i = 0; i < 5; i++) {
      cancelScan(profileId, "national_id");
    }
    for (int i = 0; i < 5; i++) {
      cancelScan(profileId, "passport");
    }
    assertEquals("blocked_scan", profileStatus(profileId));

    Instant blockedUntilBefore = scanBlockedUntil(profileId);
    long historyRowsBefore = statusHistoryCount(profileId);
    String cycleStateBefore = activeCycleState(profileId);

    // Stage 9's "the national number is wrong" from a blocked profile.
    mockMvc
        .perform(
            post("/api/v1/identity-scan/registry-review/wrong-number")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SCAN_BLOCKED"))
        .andExpect(jsonPath("$.blockedUntil").value(blockedUntilBefore.toString()));

    assertEquals(
        blockedUntilBefore,
        scanBlockedUntil(profileId),
        "the live block is not pushed out another 24 hours");
    assertEquals(
        historyRowsBefore,
        statusHistoryCount(profileId),
        "no profile_status_history row for a transition that never happened");
    assertEquals(
        cycleStateBefore,
        activeCycleState(profileId),
        "the active ok cycle survives, so the customer has a path back in once the block lifts");
  }

  /**
   * BL-044, as a real two-thread race rather than a simulation of one — the sequential case is
   * BL-038's and is already covered by {@link
   * #retryOnAnAlreadyOkCycleReturnsTheStoredResultInsteadOfOverwritingIt}.
   *
   * <p>The pre-transaction already-ok short-circuit reads the context taken BEFORE the unbounded
   * registry call, so two retries that both start while the state is not-{@code ok} both pass it
   * and both query the registry. The latch below holds the LOSER inside {@code lookup} — past that
   * short-circuit, holding no lock — while the winner runs a complete retry and commits. The loser
   * then takes the freed row lock and must notice, under it, that the record is already verified.
   *
   * <p>Pre-fix both failure modes in BL-044 are reachable from exactly this interleaving: {@code
   * updateRegistryResultOnRetry} ran unconditionally, and the second {@code
   * insertArtifactRef('portrait_registry')} violates {@code app.artifact_ref}'s {@code UNIQUE
   * (cycle_id, kind)} (V0008) as a {@code DuplicateKeyException} this method does not catch — an
   * unmapped 500. Both are asserted.
   */
  @Test
  void aRetryThatLosesTheRaceOverwritesNothingAndDoesNotCollideOnTheArtifactUniqueKey()
      throws Exception {
    String profileId = createProfile("0000000536", "+249900005361");
    String identityNumber = "IDN-0000000536";
    stubCivilRegistryClient.overrideOutcome(identityNumber, RegistryOutcome.UNREACHABLE);

    JsonNode tokenResponse = requestToken(profileId, "passport");
    String jws =
        stubUqudoClient.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT,
            null,
            tokenResponse.get("sessionId").asText(),
            tokenResponse.get("nonce").asText(),
            identityNumber,
            false);
    submitScan(
        profileId,
        tokenResponse.get("sessionId").asText(),
        tokenResponse.get("nonce").asText(),
        "passport",
        jws);
    assertEquals("awaiting_registry", profileStatus(profileId));

    // The registry recovers. Both retries below will therefore come back ok -- the case that
    // collides on artifact_ref's unique key rather than merely blanking the record.
    stubCivilRegistryClient.overrideOutcome(identityNumber, RegistryOutcome.OK);

    CountDownLatch loserIsInsideTheRegistryCall = new CountDownLatch(1);
    CountDownLatch winnerHasCommitted = new CountDownLatch(1);
    AtomicBoolean firstCaller = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              // Only the first caller is held: it becomes the loser, parked past the
              // pre-transaction short-circuit and holding no database lock at all.
              if (firstCaller.compareAndSet(true, false)) {
                loserIsInsideTheRegistryCall.countDown();
                assertTrue(
                    winnerHasCommitted.await(30, TimeUnit.SECONDS), "the winner never committed");
              }
              return result;
            })
        .when(civilRegistryClientSpy)
        .lookup(identityNumber);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<MvcResult> loser = executor.submit(() -> retryRegistryLookup(profileId));
      assertTrue(
          loserIsInsideTheRegistryCall.await(30, TimeUnit.SECONDS),
          "the loser never reached the registry call");

      MvcResult winnerResult = retryRegistryLookup(profileId);
      assertEquals(200, winnerResult.getResponse().getStatus());
      Map<String, Object> afterWinner = registryResultRow(profileId);
      assertEquals("ok", afterWinner.get("state"));

      winnerHasCommitted.countDown();
      MvcResult loserResult = loser.get(30, TimeUnit.SECONDS);
      loserResult.getResponse().setCharacterEncoding("UTF-8");

      assertEquals(
          200,
          loserResult.getResponse().getStatus(),
          "not an unmapped 500 from artifact_ref's UNIQUE (cycle_id, kind)");
      assertTrue(
          objectMapper
              .readTree(loserResult.getResponse().getContentAsString())
              .get("registryReady")
              .asBoolean(),
          "the loser answers with the winner's verified result, not its own discarded outcome");
      assertEquals(
          afterWinner,
          registryResultRow(profileId),
          "the loser wrote nothing over the winner's verified registry record");
      assertEquals(
          1L,
          portraitRegistryArtifactCount(profileId),
          "exactly one portrait_registry artifact -- the second insert is skipped, not collided");
    } finally {
      winnerHasCommitted.countDown();
      executor.shutdownNow();
    }
  }

  private MvcResult retryRegistryLookup(String profileId) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/identity-scan/registry-review/retry")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andReturn();
  }

  private void cancelScan(String profileId, String documentType) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/identity-scan/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"documentType\":\""
                        + documentType
                        + "\"}"))
        .andExpect(status().isOk());
  }

  private String profileStatus(String profileId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM app.profile WHERE profile_id = ?::uuid", String.class, profileId);
  }

  /**
   * BL-039 Slice B against a real database, on the half a mocked repository cannot reach: the
   * {@code profile_status_history} rows.
   *
   * <p>This is the cap-before-lift ordering requirement. A profile at the lifetime cap whose
   * 24-hour block has just expired must be refused with the deadline already on its row and with
   * NOTHING written. Checked after the lift instead, it would be resumed ({@code blocked_scan ->
   * in_progress}, one row, every attempt counter zeroed) and immediately re-blocked ({@code
   * in_progress -> blocked_scan}, a second row) — two rows recording a round trip the customer
   * never made, in the table customer.md's "there are no silent state changes" rule exists to keep
   * trustworthy, plus a deadline pushed out another 24 hours.
   *
   * <p>Direct, not revert-restore: the row count and the deadline are both asserted against their
   * pre-call values, and the wrong ordering moves both. It cannot pass against the defect.
   */
  @Test
  void atTheCapAJustExpiredBlockIsNotLiftedAndReapplied() throws Exception {
    String profileId = createProfile("0000000539", "+249900005391");

    // Drive the profile into a real, legitimately-applied block first, so the status, the history
    // row and the deadline are all genuine rather than poked in.
    for (int i = 0; i < 5; i++) {
      cancelScan(profileId, "national_id");
    }
    for (int i = 0; i < 5; i++) {
      cancelScan(profileId, "passport");
    }
    assertEquals("blocked_scan", profileStatus(profileId));

    // Now expire that block and put the profile at its lifetime cap.
    jdbcTemplate.update(
        "UPDATE app.profile SET scan_blocked_until = now() - interval '1 minute',"
            + " scan_tokens_minted = ? WHERE profile_id = ?::uuid",
        ScanAttemptBudget.LIFETIME_TOKEN_CAP,
        profileId);

    Instant blockedUntilBefore = scanBlockedUntil(profileId);
    long historyRowsBefore = statusHistoryCount(profileId);

    mockMvc
        .perform(
            post("/api/v1/identity-scan/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"passport\"}"))
        .andExpect(status().isConflict())
        // The EXISTING block code and screen -- Slice B introduces no new wire surface.
        .andExpect(jsonPath("$.code").value("SCAN_BLOCKED"))
        .andExpect(jsonPath("$.blockedUntil").value(blockedUntilBefore.toString()));

    assertEquals(
        historyRowsBefore,
        statusHistoryCount(profileId),
        "cap-before-lift: no lift row and no reapply row");
    assertEquals(
        blockedUntilBefore,
        scanBlockedUntil(profileId),
        "the deadline is not pushed out another 24 hours");
    assertEquals("blocked_scan", profileStatus(profileId), "and the status never moved");
    assertEquals(
        ScanAttemptBudget.LIFETIME_TOKEN_CAP,
        scanTokensMinted(profileId),
        "a refused issuance mints nothing");
  }

  /**
   * The other cap arm: an {@code in_progress} profile that has minted its allowance is blocked here
   * and now. Legal from {@code in_progress} precisely because {@code applyScanBlock}'s {@code
   * insertHistory} hard-codes that from-status (BL-043), which is why the cap must never reach it
   * from {@code blocked_scan} — the test above.
   */
  @Test
  void crossingTheCapFromInProgressAppliesTheTwentyFourHourBlock() throws Exception {
    String profileId = createProfile("0000000540", "+249900005401");
    jdbcTemplate.update(
        "UPDATE app.profile SET scan_tokens_minted = ? WHERE profile_id = ?::uuid",
        ScanAttemptBudget.LIFETIME_TOKEN_CAP,
        profileId);

    long historyRowsBefore = statusHistoryCount(profileId);

    mockMvc
        .perform(
            post("/api/v1/identity-scan/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"passport\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SCAN_BLOCKED"))
        .andExpect(jsonPath("$.blockedUntil").exists());

    assertEquals("blocked_scan", profileStatus(profileId));
    assertEquals(
        historyRowsBefore + 1,
        statusHistoryCount(profileId),
        "exactly one transition, in_progress -> blocked_scan");
    assertEquals(
        "in_progress",
        jdbcTemplate.queryForObject(
            "SELECT from_status FROM app.profile_status_history WHERE profile_id = ?::uuid"
                + " ORDER BY seq DESC LIMIT 1",
            String.class,
            profileId),
        "BL-043: the hard-coded from-status must match the status the block was applied from");
  }

  /** The mint counter moves once per issued token, and never on a refusal. */
  @Test
  void everyIssuedTokenCountsOneMintAndEveryRefusalCountsNone() throws Exception {
    String profileId = createProfile("0000000541", "+249900005411");

    requestToken(profileId, "passport");
    requestToken(profileId, "passport");
    assertEquals(2, scanTokensMinted(profileId));

    // A refusal the service makes before the mint: this document type's budget is gone.
    jdbcTemplate.update(
        "UPDATE app.profile SET scan_attempts_national_id = 5, scan_attempts_total = 5"
            + " WHERE profile_id = ?::uuid",
        profileId);
    mockMvc
        .perform(
            post("/api/v1/identity-scan/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"national_id\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SCAN_TYPE_EXHAUSTED"));

    assertEquals(2, scanTokensMinted(profileId), "a refused issuance mints nothing");
  }

  private int scanTokensMinted(String profileId) {
    return jdbcTemplate.queryForObject(
        "SELECT scan_tokens_minted FROM app.profile WHERE profile_id = ?::uuid",
        Integer.class,
        profileId);
  }

  private int attemptsForType(String profileId, String documentType) {
    return jdbcTemplate.queryForObject(
        "SELECT scan_attempts_"
            + ("national_id".equals(documentType) ? "national_id" : "passport")
            + " FROM app.profile WHERE profile_id = ?::uuid",
        Integer.class,
        profileId);
  }

  private Instant scanBlockedUntil(String profileId) {
    return jdbcTemplate.queryForObject(
        "SELECT scan_blocked_until FROM app.profile WHERE profile_id = ?::uuid",
        Instant.class,
        profileId);
  }

  private long statusHistoryCount(String profileId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM app.profile_status_history WHERE profile_id = ?::uuid",
        Long.class,
        profileId);
  }

  private String activeCycleState(String profileId) {
    return jdbcTemplate.queryForObject(
        "SELECT state FROM app.identity_cycle WHERE profile_id = ?::uuid AND state = 'active'",
        String.class,
        profileId);
  }

  private long portraitRegistryArtifactCount(String profileId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM app.artifact_ref ar JOIN app.identity_cycle ic"
            + " ON ic.cycle_id = ar.cycle_id"
            + " WHERE ic.profile_id = ?::uuid AND ar.kind = 'portrait_registry'",
        Long.class,
        profileId);
  }

  /**
   * BL-037, over the real endpoint and a real database: the wire signal and the thing it claims,
   * asserted together in one request.
   *
   * <p>A code on a Stage 8 400 asserts something about state, not just about the response -- "this
   * attempt was spent". Asserting only {@code $.code} would prove the string is emitted, not that
   * it is true, so the attempt counter is read either side of the same call. The audit event is
   * checked for the same reason: it is what makes the two spending causes distinguishable
   * server-side, which is the trade for giving them one shared code on the wire.
   */
  @Test
  void aBackendRejectedScanCarriesScanRejectedAndHasSpentAnAttempt() throws Exception {
    String profileId = createProfile("0000000534", "+249900005341");
    JsonNode tokenResponse = requestToken(profileId, "passport");
    String sessionId = tokenResponse.get("sessionId").asText();
    String nonce = tokenResponse.get("nonce").asText();

    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "SELECT scan_attempts_passport FROM app.profile WHERE profile_id = ?::uuid",
            Integer.class,
            profileId));

    // Session id and nonce are the real ones this profile was issued, so the request clears every
    // client-side check and fails for the one reason under test: StubUqudoClient cannot parse this
    // as a compact JWS and raises JwsVerificationException, the same type the RS256 verifier raises
    // on a bad signature.
    mockMvc
        .perform(
            post("/api/v1/identity-scan/scan-result")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"sessionId\":\""
                        + sessionId
                        + "\",\"nonce\":\""
                        + nonce
                        + "\",\"documentType\":\"passport\",\"jws\":\"not.a.jws\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("SCAN_REJECTED"));

    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT scan_attempts_passport FROM app.profile WHERE profile_id = ?::uuid",
            Integer.class,
            profileId),
        "the code says an attempt was spent, so an attempt must actually have been spent");

    List<String> eventTypes =
        profileEvents(profileId).stream().map(e -> (String) e.get("event_type")).toList();
    assertTrue(eventTypes.contains("scan_jws_rejected"), eventTypes.toString());

    // The same 400 status a malformed request gets, and deliberately so -- what separates them is
    // the code, not the status. A client-side failure reaching the endpoint before the service
    // carries no code and spends nothing; the counter above is still 1, not 2.
    mockMvc
        .perform(
            post("/api/v1/identity-scan/scan-result")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sessionId\":\""
                        + sessionId
                        + "\",\"nonce\":\""
                        + nonce
                        + "\",\"documentType\":\"passport\",\"jws\":\"not.a.jws\"}"))
        .andExpect(status().isBadRequest());

    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT scan_attempts_passport FROM app.profile WHERE profile_id = ?::uuid",
            Integer.class,
            profileId),
        "a client-side 400 spends nothing");
  }

  // ---- helpers ----

  @Test
  void stage9ServesTheReviewImagesItAdvertisesAndRefusesEverythingElse() throws Exception {
    // The Stage 9 review screen's own round trip: scan, read what the display payload says is
    // available, then fetch each one through the real endpoint and compare with what is stored.
    String profileId = createProfile("0000000409", "+249900004091");
    JsonNode tokenResponse = requestToken(profileId, "national_id");
    String sessionId = tokenResponse.get("sessionId").asText();
    String nonce = tokenResponse.get("nonce").asText();
    String jws =
        stubUqudoClient.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_SDN_ID,
            StubUqudoClient.CARD_VARIANT_LATEST,
            sessionId,
            nonce,
            "IDN-0000000409",
            false);

    MvcResult scan = submitScan(profileId, sessionId, nonce, "national_id", jws);
    scan.getResponse().setCharacterEncoding("UTF-8");
    JsonNode scanResponse = objectMapper.readTree(scan.getResponse().getContentAsString());

    // The screen is told what exists rather than probing: a national ID has both sides, and the
    // registry portrait is there because this stub lookup succeeded.
    List<String> advertised = new java.util.ArrayList<>();
    scanResponse.get("availableImageKinds").forEach(node -> advertised.add(node.asString()));
    assertTrue(advertised.contains("doc_front"), advertised.toString());
    assertTrue(advertised.contains("doc_back"), advertised.toString());
    assertTrue(advertised.contains("portrait_uqudo"), advertised.toString());
    assertTrue(advertised.contains("portrait_registry"), advertised.toString());
    assertFalse(
        advertised.contains("doc_front_frame"),
        "capture frames are never customer-viewable and store no bytes (AD-004)");

    // Every advertised kind actually fetches, and the bytes match app.artifact_ref exactly --
    // proving the app.artifact_read() path, its checksum verification included.
    for (String kind : advertised) {
      byte[] served =
          mockMvc
              .perform(
                  org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                          "/api/v1/identity-scan/image/" + kind)
                      .param("profileId", profileId))
              .andExpect(status().isOk())
              // R-051 names this as one of the endpoint's three mitigations, so it is asserted
              // rather than merely intended: identity documents must not sit in a proxy or disk
              // cache the journey's own purge cannot reach.
              .andExpect(
                  org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                      .string("Cache-Control", "no-store"))
              .andReturn()
              .getResponse()
              .getContentAsByteArray();
      byte[] stored =
          jdbcTemplate.queryForObject(
              "SELECT ar.body FROM app.artifact_ref ar"
                  + " JOIN app.identity_cycle ic ON ic.cycle_id = ar.cycle_id"
                  + " WHERE ic.profile_id = ?::uuid AND ar.kind = ?::text",
              byte[].class,
              profileId,
              kind);
      org.junit.jupiter.api.Assertions.assertArrayEquals(served, stored, kind);
      assertTrue(served.length > 0, kind);
    }

    // The capture frame exists as a row but was deliberately stored without bytes, and is not on
    // the allowlist either -- both reasons lead to the same 404, which is the point.
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/identity-scan/image/doc_front_frame")
                .param("profileId", profileId))
        .andExpect(status().isNotFound());

    // An unknown profile is the same 404 as an unknown kind: the endpoint never reveals which.
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/identity-scan/image/doc_front")
                .param("profileId", "11111111-1111-1111-1111-111111111111"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/identity-scan/image/face_audit_trail")
                .param("profileId", profileId))
        .andExpect(status().isNotFound());
  }

  @Test
  void aPurgedImageBecomesUnavailableRatherThanServingSomethingElse() throws Exception {
    // app.purge_abandoned_artifacts() nulls the body past the retention window; app.artifact_read()
    // returns NULL for that, and the endpoint must render it as absent. A green test alone would
    // not prove this -- the body is nulled directly here so the read path is the thing under test.
    String profileId = createProfile("0000000410", "+249900004101");
    JsonNode tokenResponse = requestToken(profileId, "passport");
    String sessionId = tokenResponse.get("sessionId").asText();
    String nonce = tokenResponse.get("nonce").asText();
    String jws =
        stubUqudoClient.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT,
            null,
            sessionId,
            nonce,
            "IDN-0000000410",
            false);
    submitScan(profileId, sessionId, nonce, "passport", jws);

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/identity-scan/image/doc_front")
                .param("profileId", profileId))
        .andExpect(status().isOk());

    jdbcTemplate.update(
        "UPDATE app.artifact_ref ar SET body = NULL, state = 'purged'"
            + " FROM app.identity_cycle ic"
            + " WHERE ic.cycle_id = ar.cycle_id AND ic.profile_id = ?::uuid"
            + " AND ar.kind = 'doc_front'",
        profileId);

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/identity-scan/image/doc_front")
                .param("profileId", profileId))
        .andExpect(status().isNotFound());

    // A passport has no doc_back at all -- absent for a different reason, same answer.
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/identity-scan/image/doc_back")
                .param("profileId", profileId))
        .andExpect(status().isNotFound());
  }

  private JsonNode requestToken(String profileId, String documentType) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/identity-scan/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"profileId\":\""
                            + profileId
                            + "\",\"documentType\":\""
                            + documentType
                            + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private MvcResult submitScan(
      String profileId, String sessionId, String nonce, String documentType, String jws)
      throws Exception {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("profileId", profileId);
    body.put("sessionId", sessionId);
    body.put("nonce", nonce);
    body.put("documentType", documentType);
    body.put("jws", jws);
    return mockMvc
        .perform(
            post("/api/v1/identity-scan/scan-result")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andReturn();
  }

  private List<Map<String, Object>> profileEvents(String profileId) {
    return jdbcTemplate.queryForList(
        "SELECT e.event_type FROM audit.audit_event e"
            + " JOIN audit.audit_chain c ON c.chain_id = e.chain_id"
            + " WHERE c.chain_kind = 'profile' AND c.subject_id = ? ORDER BY e.seq",
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
