package com.sfbank.bayanati.operator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.operator.domain.OperatorAccessLevel;
import com.sfbank.bayanati.uqudo.stub.StubUqudoClient;
import jakarta.servlet.ServletException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * BL-075: the operator image endpoint. Branch 16, accounts 0000000570–0000000585 (see {@link
 * AbstractPostgresIntegrationTest}).
 *
 * <p>Filters are disabled here for the same reason every other operator integration class disables
 * them — this class is scoped to the endpoint's OWN authorization (which artifact, whose profile),
 * not to the servlet auth layer, which is proven separately in {@code
 * auth.OperatorAuthenticationIntegrationTest}. The consequence matters for what this class can
 * claim: it proves the HANDLER refuses another profile's artifact. That the ROUTE is reachable only
 * by an authenticated {@code ROLE_VIEWER} is Spring Security's {@code /api/v1/operator/**}
 * catch-all, asserted by that other class for the prefix as a whole.
 *
 * <p>Every byte in this class is synthetic — fabricated by the Uqudo stub, or written by these
 * fixtures. Nothing is read from, or derived from, any real environment.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class OperatorImageIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String BRANCH = "16";
  private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StubUqudoClient stubUqudoClient;

  @MockitoSpyBean private MessageSender messageSender;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  // ---- the happy path -------------------------------------------------------------------------

  /**
   * The whole point of BL-075: an operator gets the REAL stored bytes, not a placeholder. Asserted
   * byte-for-byte against what {@code app.artifact_read()} returns for the same row, so a handler
   * that served a truncated, re-encoded or substituted body would fail.
   *
   * <p>The four headers are asserted together because each is a requirement in its own right.
   * {@code Cache-Control} is the one that turns a correct decision into a broken one if missed: the
   * browser is a cache CloudFront's {@code CachingDisabled} policy does not cover, and an {@code
   * <img>} re-rendered from it never reaches the origin, silently losing the audit event. Note the
   * literal expected value — {@code CacheControl.noStore()} on its own emits {@code no-store} with
   * no {@code private}, which is why {@code IdentityScanIntegrationTest} asserts the bare string
   * for the customer endpoint and this one must not.
   */
  @Test
  void servesTheStoredBytesWithAPinnedContentTypeAndTheRequiredHeaders() throws Exception {
    String profileId = readyToSubmitProfile("0000000570", "+249911005701", "IDN-0000000570");
    String artifactId = artifactIdOf(profileId, "portrait_uqudo");

    MvcResult result =
        fetch(profileId, artifactId, "op-1", OperatorAccessLevel.OPERATOR)
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store, private"))
            .andExpect(header().string("Content-Disposition", "inline"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Type", "image/jpeg"))
            .andReturn();

    byte[] served = result.getResponse().getContentAsByteArray();
    byte[] stored =
        jdbcTemplate.queryForObject("SELECT app.artifact_read(?::uuid)", byte[].class, artifactId);
    assertNotNull(stored);
    assertTrue(served.length > 0, "an <img> cannot render an empty body");
    assertArrayEquals(stored, served, "the operator must see the stored bytes, unmodified");
  }

  /**
   * Wayfinder ticket 06's ladder: a viewer views everything and prints or edits nothing. Images are
   * part of "everything" — {@code OperatorAccessLevel} has asserted that since S4-01, and ticket 02
   * decision 4 deliberately left it standing rather than re-deciding it.
   */
  @Test
  void aViewerMayFetchAnImage() throws Exception {
    String profileId = readyToSubmitProfile("0000000571", "+249911005711", "IDN-0000000571");
    fetch(profileId, artifactIdOf(profileId, "doc_front"), "op-viewer", OperatorAccessLevel.VIEWER)
        .andExpect(status().isOk());
  }

  // ---- the vulnerability class this endpoint is written against -------------------------------

  /**
   * The real risk on this feature, and it is unrelated to how the URL is addressed: without a
   * per-profile check, an operator entitled to one customer can walk artifact ids across every
   * other. Both directions are asserted — each artifact is fetchable under its OWN profile and
   * refused under the other's — so a handler that simply refused everything would not pass.
   */
  @Test
  void anotherProfilesArtifactIsRefusedEvenThoughTheOperatorMayViewBothProfiles() throws Exception {
    String profileA = readyToSubmitProfile("0000000572", "+249911005721", "IDN-0000000572");
    String profileB = readyToSubmitProfile("0000000573", "+249911005731", "IDN-0000000573");
    String artifactOfA = artifactIdOf(profileA, "portrait_uqudo");
    String artifactOfB = artifactIdOf(profileB, "portrait_uqudo");

    fetch(profileA, artifactOfA, "op-1", OperatorAccessLevel.OPERATOR).andExpect(status().isOk());
    fetch(profileB, artifactOfB, "op-1", OperatorAccessLevel.OPERATOR).andExpect(status().isOk());

    fetch(profileA, artifactOfB, "op-1", OperatorAccessLevel.OPERATOR)
        .andExpect(status().isNotFound());
    fetch(profileB, artifactOfA, "op-1", OperatorAccessLevel.OPERATOR)
        .andExpect(status().isNotFound());
  }

  /**
   * One 404 for every absence, so nothing tells a caller which artifact ids are real. An unknown
   * profile and an unknown artifact are deliberately indistinguishable from another customer's
   * artifact above.
   */
  @Test
  void anUnknownProfileOrArtifactIsTheSame404() throws Exception {
    String profileId = readyToSubmitProfile("0000000574", "+249911005741", "IDN-0000000574");

    fetch(profileId, UUID.randomUUID().toString(), "op-1", OperatorAccessLevel.OPERATOR)
        .andExpect(status().isNotFound());
    fetch(
            UUID.randomUUID().toString(),
            artifactIdOf(profileId, "doc_front"),
            "op-1",
            OperatorAccessLevel.OPERATOR)
        .andExpect(status().isNotFound());
  }

  @Test
  void aMalformedIdIs400NotAServerError() throws Exception {
    String profileId = readyToSubmitProfile("0000000575", "+249911005751", "IDN-0000000575");
    mockMvc
        .perform(
            get("/api/v1/operator/profiles/" + profileId + "/artifacts/not-a-uuid")
                .with(
                    OperatorProfileListIntegrationTest.operatorIdentity(
                        "op-1", OperatorAccessLevel.OPERATOR)))
        .andExpect(status().isBadRequest());
  }

  // ---- what must not be offered ---------------------------------------------------------------

  /**
   * BL-075 requirement (a), and the one whose cost was measured rather than predicted: {@code
   * doc_front_frame} declares {@code image/jpeg} and a byte size (1.7 MB on the dev database) over
   * {@code body IS NULL}, because AD-004 deliberately stores no bytes for the capture frames. It
   * must 404 — never return an empty body, which an {@code <img>} renders as a broken image on the
   * largest row in the attachments table.
   *
   * <p>The fixture reproduces exactly that shape: a declared type and a non-zero declared size over
   * a NULL body. Asserting the response carries no content matters as much as the status, since a
   * 200 with an empty body is the failure this requirement names.
   */
  @Test
  void aBytelessCaptureFrameIs404AndNeverAnEmptyBody() throws Exception {
    String profileId = readyToSubmitProfile("0000000576", "+249911005761", "IDN-0000000576");
    String frameId = seedArtifact(profileId, "doc_front_frame", "image/jpeg", null, "committed");

    MvcResult result =
        fetch(profileId, frameId, "op-1", OperatorAccessLevel.OPERATOR)
            .andExpect(status().isNotFound())
            .andReturn();
    assertEquals(0, result.getResponse().getContentAsByteArray().length);
  }

  /**
   * {@code doc_back} carries REAL bytes and is still refused, which is why it has to be proven
   * rather than assumed from the byte-less frame case above: those could plausibly 404 for want of
   * a body, and this one cannot. It is wayfinder ticket 02's explicit decision ("the set is the
   * set").
   */
  @Test
  void docBackIsRefusedEvenThoughItHasBytes() throws Exception {
    String profileId = readyToSubmitProfile("0000000577", "+249911005771", "IDN-0000000577");
    byte[] bytes = "synthetic-artifact-bytes".getBytes(StandardCharsets.UTF_8);

    String docBackId = seedArtifact(profileId, "doc_back", "image/jpeg", bytes, "committed");

    fetch(profileId, docBackId, "op-1", OperatorAccessLevel.OPERATOR)
        .andExpect(status().isNotFound());
  }

  /**
   * BL-136, closed at S8-24 by product-owner decision: an operator may view the customer's income
   * evidence. It was refused until then by an arithmetic slip rather than a ruling — ticket 02's
   * own premise counted six byte-carrying kinds when there are seven — and since S8-23 deleted the
   * metadata attachments table, refusing it left the document invisible in every surface there is.
   */
  @Test
  void aSalaryCertificateIsServedAndAudited() throws Exception {
    String profileId = readyToSubmitProfile("0000000583", "+249911005831", "IDN-0000000583");
    byte[] bytes = "synthetic-certificate-bytes".getBytes(StandardCharsets.UTF_8);
    String salaryId =
        seedArtifact(profileId, "salary_certificate", "image/jpeg", bytes, "committed");

    MvcResult result =
        fetch(profileId, salaryId, "op-1", OperatorAccessLevel.OPERATOR)
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/jpeg"))
            .andReturn();

    assertArrayEquals(bytes, result.getResponse().getContentAsByteArray());
    assertEquals(1L, imageViewEventCount(profileId), "viewing income evidence is audited too");
  }

  /**
   * The case that makes BL-136 more than a one-line allow-list edit, and the likeliest real one: a
   * payslip is at least as often a PDF as a photograph. {@code SalaryCertificateService} accepts
   * {@code application/pdf} and {@code salary_certificate_field.dart} offers it in the picker,
   * storing a picked PDF byte-identical — so a type allow-list of JPEG and PNG alone would have
   * gone on 404ing the commonest certificate while looking, from the kind set, entirely fixed.
   */
  @Test
  void aPdfSalaryCertificateIsServedAsAPdf() throws Exception {
    String profileId = readyToSubmitProfile("0000000584", "+249911005841", "IDN-0000000584");
    byte[] bytes = "%PDF-1.7 synthetic-not-a-real-document".getBytes(StandardCharsets.UTF_8);
    String salaryId =
        seedArtifact(profileId, "salary_certificate", "application/pdf", bytes, "committed");

    MvcResult result =
        fetch(profileId, salaryId, "op-1", OperatorAccessLevel.OPERATOR)
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/pdf"))
            .andExpect(header().string("Content-Disposition", "inline"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andReturn();

    assertArrayEquals(bytes, result.getResponse().getContentAsByteArray());
  }

  /**
   * The type allow-list is scoped PER KIND, and this proves it end to end rather than only in the
   * policy unit test. An identity artifact whose {@code content_type} column claims {@code
   * application/pdf} is refused — the column is DECLARED by whoever wrote the row, and admitting
   * PDFs globally to serve the certificate would have made every other kind forgeable into one.
   */
  @Test
  void anIdentityKindDeclaringPdfIsStillRefused() throws Exception {
    String profileId = readyToSubmitProfile("0000000585", "+249911005851", "IDN-0000000585");
    byte[] bytes = "%PDF-1.7 synthetic-not-a-real-document".getBytes(StandardCharsets.UTF_8);
    String docFrontId = seedArtifact(profileId, "doc_front", "application/pdf", bytes, "committed");

    fetch(profileId, docFrontId, "op-1", OperatorAccessLevel.OPERATOR)
        .andExpect(status().isNotFound());
    assertEquals(0L, imageViewEventCount(profileId), "a refusal writes no event");
  }

  /**
   * {@code app.artifact_ref.content_type} is DECLARED by whoever stored the row and can lie, so the
   * served type is pinned from an allow-list. A row declaring something outside it is refused
   * outright rather than downgraded to {@code application/octet-stream} — in an {@code <img>} that
   * is a broken image with extra steps, and it would let a stored {@code text/html} reach a browser
   * from the API's own origin.
   */
  @Test
  void aContentTypeOutsideTheAllowListIsRefused() throws Exception {
    String profileId = readyToSubmitProfile("0000000578", "+249911005781", "IDN-0000000578");
    byte[] bytes = "<html>not an image</html>".getBytes(StandardCharsets.UTF_8);
    String artifactId = seedArtifact(profileId, "doc_front", "text/html", bytes, "committed");

    fetch(profileId, artifactId, "op-1", OperatorAccessLevel.OPERATOR)
        .andExpect(status().isNotFound());
  }

  /**
   * Only {@code committed} is servable. {@code purged} has had its body NULLed by V0055's retention
   * sweep; {@code superseded} (V0063) is the signature of a customer a device-less re-entry
   * replaced, retained as audit evidence precisely so it is NOT read as this profile's current one
   * (AD-008/BL-041). Refusing it keeps the endpoint agreeing with the attachments listing, which
   * already filters on the same state.
   */
  @Test
  void supersededAndPurgedRowsAreRefused() throws Exception {
    String profileId = readyToSubmitProfile("0000000579", "+249911005791", "IDN-0000000579");
    byte[] bytes = "synthetic-artifact-bytes".getBytes(StandardCharsets.UTF_8);

    String supersededId =
        seedArtifact(profileId, "portrait_registry", "image/jpeg", bytes, "superseded");
    String purgedId = seedArtifact(profileId, "face_audit_trail", "image/jpeg", null, "purged");

    fetch(profileId, supersededId, "op-1", OperatorAccessLevel.OPERATOR)
        .andExpect(status().isNotFound());
    fetch(profileId, purgedId, "op-1", OperatorAccessLevel.OPERATOR)
        .andExpect(status().isNotFound());
  }

  // ---- the checksum guard ---------------------------------------------------------------------

  /**
   * Proves {@code app.artifact_read()} (V0054) is genuinely in the read path rather than merely
   * named in a comment: corrupt the stored checksum so the function's verification fails, and the
   * request must fail loudly rather than degrade.
   *
   * <p>Failing LOUDLY, deliberately not as a 404. Something present and wrong is not the same as
   * something absent, and rendering a corrupted identity document as a missing-image icon would
   * hide exactly the condition V0054 exists to surface. The successful fetch before the corruption
   * is what makes this a before/after on one row rather than an assertion about some other failure.
   *
   * <p><strong>Why this asserts a propagated exception and not {@code
   * status().isInternalServerError()}.</strong> Nothing handles this exception, by design. On a
   * real server that reaches Spring Boot's default error handling and the operator's browser gets a
   * 500; {@code MockMvc} has no such handling and rethrows out of {@code perform()} instead, so
   * asserting a rendered 500 here would be asserting a behaviour this harness does not simulate.
   * The assertion is therefore the thing that IS true at this layer — the request dies inside the
   * handler carrying the database's own verification message — plus the two consequences that
   * actually matter and that a wrong implementation would break.
   *
   * <p>The audit count is the sharper half of this test. No event may be written for a read that
   * served nothing: the trail must never record that an operator viewed an image they were never
   * given. That also pins the ordering — repository read first, audit append second — since an
   * implementation that appended the event before reading the bytes would leave a second event
   * behind here and fail.
   */
  @Test
  void aChecksumMismatchFailsLoudlyAndServesNoBytes() throws Exception {
    String profileId = readyToSubmitProfile("0000000580", "+249911005801", "IDN-0000000580");
    String artifactId = artifactIdOf(profileId, "doc_front");

    fetch(profileId, artifactId, "op-1", OperatorAccessLevel.OPERATOR).andExpect(status().isOk());
    assertEquals(1L, imageViewEventCount(profileId), "the good fetch was audited");

    jdbcTemplate.update(
        "UPDATE app.artifact_ref SET sha256 = sha256('a-different-artifact'::bytea)"
            + " WHERE artifact_ref_id = ?::uuid",
        artifactId);

    ServletException thrown =
        assertThrows(
            ServletException.class,
            () -> fetch(profileId, artifactId, "op-1", OperatorAccessLevel.OPERATOR).andReturn(),
            "a body that failed verification must never reach the operator");
    assertTrue(
        rootCauseMessage(thrown).contains("failed checksum verification on read"),
        "the failure must be V0054's verification, not some other error: "
            + rootCauseMessage(thrown));

    assertEquals(
        1L,
        imageViewEventCount(profileId),
        "a read that served no bytes must not claim in the audit trail that an image was viewed");
  }

  private long imageViewEventCount(String profileId) {
    Long count =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*)
              FROM audit.audit_event ae
              JOIN audit.audit_chain ac ON ac.chain_id = ae.chain_id
             WHERE ac.chain_kind = 'profile' AND ac.subject_id = ?
               AND ae.event_type = 'profile_image_viewed'
            """,
            Long.class,
            profileId);
    assertNotNull(count);
    return count;
  }

  private static String rootCauseMessage(Throwable thrown) {
    Throwable cause = thrown;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());
  }

  // ---- the audit trail ------------------------------------------------------------------------

  /**
   * "Viewing an image is its own audit event" (operator.md) — the property R-046's closure was
   * chosen to preserve, since every view is an origin request and the event therefore fires when
   * the image is SEEN rather than when a URL was issued.
   *
   * <p>Three fetches, three events, on the profile's own chain, each carrying exactly wayfinder
   * ticket 09's payload: {@code artifactId} and {@code kind}, and nothing that duplicates a
   * first-class column. The second fetch of the SAME artifact is what proves the count is per FETCH
   * — a design that recorded "this operator has seen this image" once would pass a
   * one-event-per-artifact assertion and fail this one.
   *
   * <p>A refused fetch writes nothing. The event asserts that an image WAS VIEWED; a 404 served no
   * image, and an event for it would make the trail claim something that did not happen.
   */
  @Test
  void oneAuditEventPerOriginFetchCarryingTheArtifactIdAndKind() throws Exception {
    String profileId = readyToSubmitProfile("0000000581", "+249911005811", "IDN-0000000581");
    String portraitId = artifactIdOf(profileId, "portrait_uqudo");
    String docFrontId = artifactIdOf(profileId, "doc_front");

    fetch(profileId, portraitId, "op-audit", OperatorAccessLevel.OPERATOR)
        .andExpect(status().isOk());
    fetch(profileId, portraitId, "op-audit", OperatorAccessLevel.OPERATOR)
        .andExpect(status().isOk());
    fetch(profileId, docFrontId, "op-audit", OperatorAccessLevel.OPERATOR)
        .andExpect(status().isOk());

    // A refusal must add nothing.
    fetch(profileId, UUID.randomUUID().toString(), "op-audit", OperatorAccessLevel.OPERATOR)
        .andExpect(status().isNotFound());

    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            """
            SELECT ae.payload_json, ae.actor_kind, ae.actor_id, ae.profile_id::text AS profile_id
              FROM audit.audit_event ae
              JOIN audit.audit_chain ac ON ac.chain_id = ae.chain_id
             WHERE ac.chain_kind = 'profile' AND ac.subject_id = ?
               AND ae.event_type = 'profile_image_viewed'
             ORDER BY ae.seq
            """,
            profileId);

    assertEquals(3, events.size(), "one event per origin fetch that actually served bytes");
    assertEquals("operator", events.get(0).get("actor_kind"));
    assertEquals("op-audit", events.get(0).get("actor_id"));
    assertEquals(profileId, events.get(0).get("profile_id"));

    JsonNode first = objectMapper.readTree((String) events.get(0).get("payload_json"));
    assertEquals(portraitId, first.get("artifactId").asText());
    assertEquals("portrait_uqudo", first.get("kind").asText());
    assertEquals(2, first.size(), "ticket 09: the payload is kind + artifactId and nothing else");

    JsonNode second = objectMapper.readTree((String) events.get(1).get("payload_json"));
    assertEquals(portraitId, second.get("artifactId").asText());

    JsonNode third = objectMapper.readTree((String) events.get(2).get("payload_json"));
    assertEquals(docFrontId, third.get("artifactId").asText());
    assertEquals("doc_front", third.get("kind").asText());

    // The events joined the profile's existing hash chain rather than starting a parallel one.
    Long chainCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit.audit_chain WHERE chain_kind = 'profile' AND subject_id = ?",
            Long.class,
            profileId);
    assertEquals(1L, chainCount, "one profile chain, ensured at Stage 1b, never a second");
  }

  /**
   * The attachments listing now carries the id the endpoint is addressed by, and the two must
   * agree: every listed artifact is a real UUID, and the one the operator will actually click
   * fetches its bytes. A listing that offered an id the endpoint refused would be the same defect
   * the byte-less capture frame caused, one level up.
   */
  @Test
  void theProfileDetailListingCarriesTheArtifactIdTheEndpointServes() throws Exception {
    String profileId = readyToSubmitProfile("0000000582", "+249911005821", "IDN-0000000582");
    submit(profileId);

    MvcResult result =
        mockMvc
            .perform(
                get("/api/v1/operator/profiles/" + profileId)
                    .with(
                        OperatorProfileListIntegrationTest.operatorIdentity(
                            "op-1", OperatorAccessLevel.VIEWER)))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode artifacts =
        objectMapper.readTree(result.getResponse().getContentAsString()).get("artifacts");
    assertTrue(artifacts.size() > 0);

    boolean fetchedOne = false;
    for (JsonNode artifact : artifacts) {
      String id = artifact.get("artifactRefId").asText();
      assertNotNull(UUID.fromString(id), "every listed artifact is addressable");
      if ("portrait_uqudo".equals(artifact.get("kind").asText())) {
        fetch(profileId, id, "op-1", OperatorAccessLevel.VIEWER).andExpect(status().isOk());
        fetchedOne = true;
      }
    }
    assertTrue(fetchedOne, "the listing's own id fetched the bytes");
  }

  // ---- fixtures -------------------------------------------------------------------------------

  private ResultActions fetch(
      String profileId, String artifactId, String operatorId, OperatorAccessLevel level)
      throws Exception {
    return mockMvc.perform(
        get("/api/v1/operator/profiles/" + profileId + "/artifacts/" + artifactId)
            .with(OperatorProfileListIntegrationTest.operatorIdentity(operatorId, level)));
  }

  private String artifactIdOf(String profileId, String kind) {
    String id =
        jdbcTemplate.queryForObject(
            """
            SELECT ar.artifact_ref_id::text
              FROM app.artifact_ref ar
             WHERE (ar.profile_id = ?::uuid
                    OR ar.cycle_id IN (SELECT cycle_id FROM app.identity_cycle WHERE profile_id = ?::uuid))
               AND ar.kind = ?::text AND ar.state = 'committed' AND ar.body IS NOT NULL
             ORDER BY ar.created_at DESC
             LIMIT 1
            """,
            String.class,
            profileId,
            profileId,
            kind);
    assertNotNull(id, "fixture expected a stored " + kind + " on this profile");
    return id;
  }

  /**
   * A synthetic artifact row, profile-keyed with a NULL {@code cycle_id} so it never collides with
   * the journey's own cycle-keyed rows under {@code UNIQUE (cycle_id, kind)}. {@code byte_size} is
   * written as a DECLARED value independent of the body — that is the whole point for the
   * capture-frame case, where a real row declares 1.7 MB over no bytes at all.
   */
  private String seedArtifact(
      String profileId, String kind, String contentType, byte[] body, String state) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO app.artifact_ref
          (profile_id, cycle_id, kind, storage_key, content_type, byte_size, sha256, body, state)
        VALUES (?::uuid, NULL, ?::text, ?::text, ?::text, ?::bigint,
                coalesce(sha256(?::bytea), sha256(''::bytea)), ?::bytea, ?::text)
        RETURNING artifact_ref_id::text
        """,
        String.class,
        profileId,
        kind,
        "artifact:" + UUID.randomUUID(),
        contentType,
        body == null ? 1_726_304L : (long) body.length,
        body,
        body,
        state);
  }

  /**
   * Backdates the "received" notification submit() enqueues, for the reason {@code
   * OperatorProfileViewIntegrationTest}'s identical helper documents: an un-backdated row is
   * claimable by {@code NotificationOutboxIntegrationTest}'s unscoped poll for the rest of the run.
   */
  private void submit(String profileId) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/submission")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isOk());
    jdbcTemplate.update(
        "UPDATE app.notification_outbox SET next_attempt_at = now() + interval '1 hour'"
            + " WHERE profile_id = ?::uuid",
        profileId);
  }

  private String readyToSubmitProfile(
      String accountNumber, String phoneNumber, String identityNumber) throws Exception {
    String profileId = profileWithPassedLiveness(accountNumber, phoneNumber, identityNumber);

    String base64 =
        Base64.getEncoder()
            .encodeToString("synthetic-signature-bytes".getBytes(StandardCharsets.UTF_8));
    Map<String, Object> signatureBody = new LinkedHashMap<>();
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

  /** Mirrors {@code OperatorProfileViewIntegrationTest}'s proven helper chain. */
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

    Map<String, Object> faceBody = new LinkedHashMap<>();
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

  private String createProfileWithVerifiedSms(String accountNumber, String phoneNumber)
      throws Exception {
    String body =
        "{\"branch\":\""
            + BRANCH
            + "\",\"accountNumber\":\""
            + accountNumber
            + "\",\"phoneNumber\":\""
            + phoneNumber
            + "\",\"sms\":true,\"whatsapp\":false}";
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
}
