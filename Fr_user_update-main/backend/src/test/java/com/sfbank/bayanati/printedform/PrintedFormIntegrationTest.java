package com.sfbank.bayanati.printedform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.operator.domain.OperatorAccessLevel;
import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.web.OperatorIdentityArgumentResolver;
import com.sfbank.bayanati.printedform.domain.PrintedFormRepository;
import com.sfbank.bayanati.reference.domain.ReferenceCatalog;
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
 * BL-132's endpoint against the real database: what is STORED, what is STREAMED, what is AUDITED,
 * and who is refused. Branch 16, accounts 0000000610–0000000624 (see {@link
 * AbstractPostgresIntegrationTest}).
 *
 * <p>The form's CONTENT is not asserted here — that is {@code PrintedFormAssemblerTest}'s job, with
 * no database in the way. What needs a real database is everything a unit test cannot see: that the
 * stored row survives {@code app.artifact_read()}'s checksum verification, that a reprint is not a
 * constraint violation, that {@code app.profile_provenance_matrix_version} actually accepts the
 * write, and that the audit events land on the profile's own hash chain.
 *
 * <p>Filters are disabled, as in every other operator integration class: this class is scoped to
 * the endpoint's OWN authorization, not to the servlet auth layer. The consequence is stated
 * honestly — it proves the HANDLER refuses a viewer. That the ROUTE is also gated at {@code
 * SecurityConfiguration} is a separate rule, asserted by {@code
 * auth.OperatorAuthenticationIntegrationTest} for the prefix and readable in the matcher itself.
 *
 * <p>Every byte here is synthetic — fabricated by the Uqudo stub or written by these fixtures.
 * Nothing is read from, or derived from, any real environment.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class PrintedFormIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String BRANCH = "16";
  private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PrintedFormRepository printedFormRepository;
  @Autowired private ReferenceCatalog referenceCatalog;
  @Autowired private StubUqudoClient stubUqudoClient;

  @MockitoSpyBean private MessageSender messageSender;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  // ---- the two reads S9-03 added, against the real database ----------------------------------

  /**
   * The «معدَّل» marker's query, proved against real rows.
   *
   * <p>Worth an integration test rather than only a stubbed one, because everything above this
   * query is exercised with a fake: {@code PrintedFormServiceTest}'s {@code FakeRepository} answers
   * whatever the test hands it, so a SQL statement that returned nothing for every profile would
   * leave the whole unit suite green and simply print no markers, ever. The failure mode is a form
   * that is quietly less informative, which is exactly the kind nobody notices.
   *
   * <p>Field 10 («الجنس», ethnicity) rather than 38 («المنطقة») which the approved page 2 shows
   * marked: this suite's profiles leave most address columns NULL, and an ABSENT value is never
   * marked — correctly, since an operator cannot have keyed a value that is not there. Field 10 is
   * one of the nineteen {@code EditableField}s and one of the few this fixture populates.
   */
  @Test
  void theEditedFieldQueryReadsRealRows() throws Exception {
    String profileId = submittedProfile("0000000626", "+249911006261", "IDN-0000000626");

    assertTrue(
        printedFormRepository.editedFieldNumbers(UUID.fromString(profileId)).isEmpty(),
        "a profile nobody has edited carries no marked fields");

    // The value the operator is recorded as having keyed. This suite's profiles carry only what
    // the journey needs, so most customer columns are NULL -- and an ABSENT field is never marked,
    // correctly, since nobody can key a value that is not there. Setting it is what makes the
    // marker's precondition true rather than hunting for a populated column.
    assertEquals(
        1,
        jdbcTemplate.update(
            "UPDATE app.profile_customer_data SET ethnicity = ? WHERE profile_id = ?::uuid",
            "شايقية",
            profileId),
        "the fixture must actually carry the value the marker sits beside");

    jdbcTemplate.update(
        "INSERT INTO app.profile_field_edit"
            + " (profile_id, field_key, field_number, previous_value, new_value, edited_by,"
            + " edited_at) VALUES (?::uuid, 'ETHNICITY', 10, 'old', 'new', 'op-1', now())",
        profileId);

    assertEquals(
        java.util.Set.of(10),
        printedFormRepository.editedFieldNumbers(UUID.fromString(profileId)),
        "the edited field comes back by its denormalised number");

    // AND THE PRINT USES IT. The query being right is half the claim; the other half is that
    // PrintedFormService passes the result into PrintedFormSources and the assembler acts on it.
    // Nothing above this asserted that: replacing the two new arguments with empty values left
    // every unit test green while the form silently printed no markers and raw alpha-3 codes.
    MvcResult printed =
        print(profileId, "op-1", OperatorAccessLevel.OPERATOR, false)
            .andExpect(status().isOk())
            .andReturn();
    String text = pdfText(printed.getResponse().getContentAsByteArray());

    // THE MARKER CANNOT BE ASSERTED AS A STRING, and that is a property of reading a PDF back
    // rather than of writing one. Measured on this very render: «معدَّل» extracts as the sequence
    // «ل» … «الجنس» U+E002 «معد» — the shadda-plus-fatha pair renders through a font glyph with no
    // reverse cmap entry, so it comes back as a PRIVATE-USE codepoint, and the final «ل» is
    // reordered to the head of the line. Stripping the combining marks does not help, because they
    // are not in the extracted text as combining marks at all. The same class of artifact is
    // already recorded against «الفرع» in PrintedFormRendererTest, and more severely against the
    // header's Amiri span, where the whole word extracts as private-use codepoints.
    //
    // So this asserts the letters that DO survive, pinned to exactly one occurrence: «معد» appears
    // nowhere else in the document, and it appears here only because the marker rendered. The
    // marker's own spelling and styling are asserted against the FO, where they are text, by
    // PrintedFormRendererTest.anEditedFieldIsMarkedEditedRatherThanManual.
    assertEquals(1, countOf(text, "معد"), "the printed form marks the edited field exactly once");
    assertTrue(text.contains("شايقية"), "beside the value the operator is recorded as keying");

    assertTrue(
        text.contains("السودان"),
        "and the MRZ's alpha-3 nationality printed as the country's Arabic name");
  }

  private static int countOf(String haystack, String needle) {
    int count = 0;
    for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
      count++;
    }
    return count;
  }

  /** The text of a rendered print, for the few assertions that are about what an officer reads. */
  private static String pdfText(byte[] pdf) throws Exception {
    try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
      return new org.apache.pdfbox.text.PDFTextStripper().getText(document);
    }
  }

  /**
   * The alpha-3 country lookup, proved against the seeded list.
   *
   * <p>This is the query BL-157 turned on. Fields 4 and 48 carry the MRZ's alpha-3 («SDN») while
   * {@code ref.reference_item.item_code} is alpha-2 («SD»), and the mapping lives in V0022's seeded
   * {@code extra}. If the JSON path were wrong the lookup would simply find nothing and both fields
   * would fall back to printing the raw code — which is a legitimate outcome for an unknown code,
   * so no assertion above this level can tell the two apart. Only a real row can.
   */
  @Test
  void theAlphaThreeCountryLookupResolvesAgainstTheSeededList() {
    int version = referenceCatalog.currentVersion("country");

    assertEquals(
        "السودان",
        referenceCatalog.findByAlpha3("country", version, "SDN").orElseThrow().labelAr(),
        "the MRZ's alpha-3 resolves to the same Arabic name the alpha-2 code does");
    assertEquals(
        "السودان",
        referenceCatalog.findByAlpha3("country", version, "sdn").orElseThrow().labelAr(),
        "and case does not matter, since an MRZ is not guaranteed upper case");
    assertTrue(
        referenceCatalog.findByAlpha3("country", version, "XXA").isEmpty(),
        "an ICAO code with no ISO country row resolves to nothing, and the form prints it raw");
  }

  // ---- the happy path, and the proof the product owner asked for ------------------------------

  /**
   * One print: a real PDF, the required headers, and — the assertion that matters — the bytes the
   * operator received are byte-identical to the bytes {@code app.artifact_read()} returns for the
   * stored row.
   *
   * <p>That read is not a plain SELECT: V0054's function recomputes {@code sha256(body)} and RAISES
   * on a mismatch rather than returning bytes that changed underneath. So this assertion proves
   * three things at once — the render happened once, the store took the same bytes, and the stored
   * row's checksum describes what the operator holds. Ticket 05 decision 1 turns on exactly this,
   * because the render is not byte-reproducible across runs and nobody can recompute it later.
   */
  @Test
  void printsStoresAndStreamsTheSameBytesWithACheckableChecksum() throws Exception {
    String profileId = submittedProfile("0000000610", "+249911006101", "IDN-0000000610");

    MvcResult result =
        print(profileId, "op-1", OperatorAccessLevel.OPERATOR, false)
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", MediaType.APPLICATION_PDF_VALUE))
            .andExpect(header().string("Cache-Control", "no-store, private"))
            .andExpect(header().string("Content-Disposition", "inline"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andReturn();

    byte[] streamed = result.getResponse().getContentAsByteArray();
    assertTrue(streamed.length > 0, "an operator cannot print an empty body");
    assertEquals("%PDF-", new String(streamed, 0, 5, StandardCharsets.ISO_8859_1));

    String artifactId = result.getResponse().getHeader("X-Printed-Form-Artifact-Id");
    assertNotNull(artifactId, "the response names the row this print became");
    byte[] stored =
        jdbcTemplate.queryForObject("SELECT app.artifact_read(?::uuid)", byte[].class, artifactId);
    assertArrayEquals(stored, streamed, "the stored print and the streamed print are one render");

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT kind, content_type, state, byte_size, cycle_id FROM app.artifact_ref"
                + " WHERE artifact_ref_id = ?::uuid",
            artifactId);
    assertEquals("printed_form", row.get("kind"));
    assertEquals("application/pdf", row.get("content_type"));
    assertEquals("committed", row.get("state"));
    assertEquals((long) streamed.length, ((Number) row.get("byte_size")).longValue());
    // Profile-keyed with a NULL cycle: what makes a reprint legal under UNIQUE (cycle_id, kind).
    assertEquals(null, row.get("cycle_id"));
  }

  /**
   * ONE kind, against the real constraint. AD-022 (S9-01) collapsed the two forms, and V0072
   * narrowed {@code artifact_ref_kind_check} to admit {@code printed_form} alone.
   *
   * <p>This replaces {@code theAttributedVariantIsStoredUnderItsOwnKind}. It is worth keeping as an
   * INTEGRATION test rather than folding into the service unit test, because the property it now
   * pins is that the in-code constant and the database constraint agree: were {@code
   * PrintedFormRepository#PRINTED_FORM_KIND} ever to drift from V0072's CHECK list, this fails on
   * the INSERT with a constraint violation, which no stubbed repository can reproduce.
   */
  @Test
  void everyPrintIsStoredUnderTheOnePrintedFormKind() throws Exception {
    String profileId = submittedProfile("0000000611", "+249911006111", "IDN-0000000611");

    String artifactId =
        print(profileId, "op-1", OperatorAccessLevel.OPERATOR, false)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("X-Printed-Form-Artifact-Id");

    assertEquals(
        "printed_form",
        jdbcTemplate.queryForObject(
            "SELECT kind FROM app.artifact_ref WHERE artifact_ref_id = ?::uuid",
            String.class,
            artifactId));
  }

  /**
   * Ticket 05 decision 2: every print is its own artifact and nothing is superseded. This is the
   * assertion V0070's comment points at — without profile keying, V0008's {@code UNIQUE (cycle_id,
   * kind)} would make this second print a constraint violation rather than a second row.
   */
  @Test
  void aSecondPrintIsASecondArtifactAndNotAConstraintViolation() throws Exception {
    String profileId = submittedProfile("0000000612", "+249911006121", "IDN-0000000612");

    String first = printedArtifactId(profileId);
    String second = printedArtifactId(profileId);

    assertNotEquals(first, second);
    assertEquals(
        2,
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.artifact_ref WHERE profile_id = ?::uuid"
                + " AND kind = 'printed_form'",
            Integer.class,
            profileId));
  }

  // ---- who may print --------------------------------------------------------------------------

  /** AD-013 / ticket 06: a viewer views everything and prints nothing. */
  @Test
  void aViewerIsRefusedAndNothingIsStored() throws Exception {
    String profileId = submittedProfile("0000000613", "+249911006131", "IDN-0000000613");

    print(profileId, "op-viewer", OperatorAccessLevel.VIEWER, false)
        .andExpect(status().isForbidden());

    assertEquals(0, storedPrintCount(profileId));
    assertEquals(0, auditEventCount(profileId, "profile_form_printed"));
  }

  /** A viewer may not re-download a print someone else made either. */
  @Test
  void aViewerIsRefusedAReDownload() throws Exception {
    String profileId = submittedProfile("0000000614", "+249911006141", "IDN-0000000614");
    String artifactId = printedArtifactId(profileId);

    mockMvc
        .perform(
            get("/api/v1/operator/profiles/" + profileId + "/prints/" + artifactId)
                .with(operatorIdentity("op-viewer", OperatorAccessLevel.VIEWER)))
        .andExpect(status().isForbidden());
  }

  // ---- which profiles may be printed ------------------------------------------------------------

  /** Ticket 05 decision 8: only {@code submitted} and {@code approved}. */
  @Test
  void anInProgressProfileIsRefusedWithAConflictRatherThanANotFound() throws Exception {
    // Stops short of submission, so the profile is still in_progress.
    String profileId = createProfileWithVerifiedSms("0000000615", "+249911006151");

    print(profileId, "op-1", OperatorAccessLevel.OPERATOR, false).andExpect(status().isConflict());

    assertEquals(0, storedPrintCount(profileId));
  }

  @Test
  void anApprovedProfileMayStillBePrinted() throws Exception {
    String profileId = submittedProfile("0000000616", "+249911006161", "IDN-0000000616");
    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + profileId + "/approve")
                .with(operatorIdentity("op-approver", OperatorAccessLevel.OPERATOR)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("approved"));

    print(profileId, "op-1", OperatorAccessLevel.OPERATOR, false).andExpect(status().isOk());
  }

  @Test
  void anUnknownProfileIs404AndAMalformedIdIs400() throws Exception {
    print("99999999-9999-9999-9999-999999999999", "op-1", OperatorAccessLevel.OPERATOR, false)
        .andExpect(status().isNotFound());

    print("not-a-uuid", "op-1", OperatorAccessLevel.OPERATOR, false)
        .andExpect(status().isBadRequest());
  }

  /**
   * A refused request renders nothing and stores nothing.
   *
   * <p>This was {@code anUnknownVariantIsRefusedBeforeAnythingIsRendered} until AD-022 (S9-01)
   * removed the variant, which was the only field on the request that could be invalid. The
   * property it guarded is not about variants at all — it is that the refusal happens BEFORE the
   * FOP pass and the store, so a bad request cannot leave a stored copy of a customer's whole
   * record behind. Re-pointed at a malformed body, which is the 400 arm that still exists.
   */
  @Test
  void aMalformedRequestIsRefusedBeforeAnythingIsRendered() throws Exception {
    String profileId = submittedProfile("0000000617", "+249911006171", "IDN-0000000617");

    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + profileId + "/print")
                .with(operatorIdentity("op-1", OperatorAccessLevel.OPERATOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"includeAttachments\": \"not-a-boolean\"}"))
        .andExpect(status().isBadRequest());

    assertEquals(0, storedPrintCount(profileId));
  }

  /**
   * A stale client still sending the withdrawn {@code variant} field is NOT refused — the field is
   * simply ignored, and the print succeeds under the one kind.
   *
   * <p>Worth pinning because the back office is deployed separately from the backend (S8-36's own
   * deployment order), so a browser holding the previous bundle will keep sending {@code variant}
   * until it reloads. Refusing it would break printing for exactly as long as that cache lives.
   */
  @Test
  void aStaleClientStillSendingTheWithdrawnVariantFieldIsNotRefused() throws Exception {
    String profileId = submittedProfile("0000000625", "+249911006251", "IDN-0000000625");

    mockMvc
        .perform(
            post("/api/v1/operator/profiles/" + profileId + "/print")
                .with(operatorIdentity("op-1", OperatorAccessLevel.OPERATOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variant\":\"attributed\",\"includeAttachments\":false}"))
        .andExpect(status().isOk());

    // storedPrintCount already filters to kind = 'printed_form'; the profile also carries its
    // signature and identity artifacts, so an unfiltered query here returns more than one row.
    assertEquals(1, storedPrintCount(profileId));
  }

  // ---- the audit trail
  // ----------------------------------------------------------------------------

  /**
   * ONE print event on the PROFILE chain, carrying what ticket 09 decision 2 requires. Read back
   * out of {@code audit.audit_event} rather than out of a mock, because the payload has to survive
   * the {@code ::jsonb} cast and the hash chain's own append trigger.
   */
  @Test
  void thePrintIsOnTheProfilesAuditChainWithTheKindAndTheVersions() throws Exception {
    String profileId = submittedProfile("0000000618", "+249911006181", "IDN-0000000618");
    String artifactId = printedArtifactId(profileId);

    Map<String, Object> event =
        jdbcTemplate.queryForMap(
            """
            SELECT c.chain_kind, c.subject_id, e.actor_kind, e.actor_id, e.payload_json
              FROM audit.audit_event e
              JOIN audit.audit_chain c ON c.chain_id = e.chain_id
             WHERE e.profile_id = ?::uuid AND e.event_type = 'profile_form_printed'
             ORDER BY e.audit_event_id DESC LIMIT 1
            """,
            profileId);

    // The PROFILE chain, with the profile as its subject -- the convention every other
    // profile-scoped operator event already follows (profile_viewed, approve, reject, manual
    // completion, image view), rather than the operator chain the list and export events use.
    assertEquals("profile", event.get("chain_kind"));
    assertEquals(profileId, String.valueOf(event.get("subject_id")));
    assertEquals("operator", event.get("actor_kind"));
    assertEquals("op-1", event.get("actor_id"));

    // payload_json, not the generated `payload` jsonb column: payload_json is the canonical string
    // that actually gets hashed into the chain (V0002:52), so asserting against it is asserting
    // against the record itself rather than against PostgreSQL's re-serialisation of it.
    String payload = String.valueOf(event.get("payload_json"));
    assertTrue(
        payload.contains(artifactId), "the event names the artifact it produced: " + payload);
    // `variant` was dropped from the payload by AD-022 (S9-01) rather than pinned to a constant:
    // a member that can only ever hold one value records nothing. Asserted ABSENT so a future
    // change cannot quietly reintroduce a field the trail's readers would take as meaningful.
    assertFalse(payload.contains("\"variant\""), payload);
    assertTrue(payload.contains("\"kind\":\"printed_form\""), payload);
    assertTrue(payload.contains("\"provenanceMatrixVersion\":2"), payload);
    assertTrue(payload.contains("\"referenceListVersions\":"), payload);
    assertTrue(payload.contains("\"actorRole\":\"operator\""), payload);
    // No PII on a payload that can never be erased: payload_json is permanently hash-chained,
    // unlike audit_artifact.body, which a lawful purge can reach.
    assertFalse(payload.contains("0000000618"), payload);
    assertFalse(payload.contains("+249911006181"), payload);
  }

  /**
   * The writer V0027 and V0029 never had. Nothing in this system wrote {@code
   * app.profile_provenance_matrix_version} before BL-132, and ticket 09 says populating it is part
   * of the print work rather than an assumption.
   */
  @Test
  void thePrintRecordsTheProvenanceMatrixVersionOnTheProfile() throws Exception {
    String profileId = submittedProfile("0000000619", "+249911006191", "IDN-0000000619");

    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.profile_provenance_matrix_version WHERE profile_id = ?::uuid",
            Integer.class,
            profileId));

    printedArtifactId(profileId);

    // THE MATRIX MOVES ON between the two prints. Without this the test could not fail: V0027 and
    // V0029 seed versions 1 and 2, so max(version) is always 2 and an implementation that returned
    // the CURRENT version instead of the RECORDED one would pass every assertion below. Found at
    // review.
    //
    // Inserted and REMOVED again in the same method. app.provenance_matrix_version is a seed table
    // and AppSchemaConnectivityIntegrationTest asserts its exact row count, so a third row left
    // behind in the shared container would fail that class depending on ordering -- precisely the
    // cross-class collision AbstractPostgresIntegrationTest's contract warns about. Safe to remove:
    // this profile pins version 2, never 3, so no foreign key holds it.
    String reprint;
    asMigrator(
        "INSERT INTO app.provenance_matrix_version (version, effective_date, note)"
            + " VALUES (3, DATE '2026-09-15', 'synthetic edition, inserted and removed by"
            + " PrintedFormIntegrationTest') ON CONFLICT (version) DO NOTHING");
    try {
      reprint = printedArtifactId(profileId);
    } finally {
      asMigrator("DELETE FROM app.provenance_matrix_version WHERE version = 3");
    }

    Map<String, Object> pinned =
        jdbcTemplate.queryForMap(
            "SELECT version FROM app.profile_provenance_matrix_version WHERE profile_id = ?::uuid",
            profileId);
    // ONE row after TWO prints -- written once, never revised (fru_app holds SELECT and INSERT on
    // this table and no UPDATE at all), and still version 2 although 3 is now the latest.
    assertEquals(2, ((Number) pinned.get("version")).intValue());

    // And the REPRINT's own event says 2, which is what "a reprint in a year says the same thing"
    // actually asks for -- the pinned row alone would not prove the payload read it.
    String payload =
        jdbcTemplate.queryForObject(
            """
            SELECT payload_json FROM audit.audit_event
             WHERE profile_id = ?::uuid AND event_type = 'profile_form_printed'
             ORDER BY audit_event_id DESC LIMIT 1
            """,
            String.class,
            profileId);
    assertTrue(payload.contains(reprint), payload);
    assertTrue(payload.contains("\"provenanceMatrixVersion\":2"), payload);
  }

  /**
   * A re-download serves the same bytes again and is its OWN event type. A log that cannot tell a
   * re-download from the original print cannot answer how many copies of that document exist
   * (ticket 05 decision 6).
   */
  @Test
  void aReDownloadServesTheSameBytesAndIsItsOwnEvent() throws Exception {
    String profileId = submittedProfile("0000000620", "+249911006201", "IDN-0000000620");

    MvcResult printed =
        print(profileId, "op-1", OperatorAccessLevel.OPERATOR, false)
            .andExpect(status().isOk())
            .andReturn();
    String artifactId = printed.getResponse().getHeader("X-Printed-Form-Artifact-Id");

    MvcResult again =
        mockMvc
            .perform(
                get("/api/v1/operator/profiles/" + profileId + "/prints/" + artifactId)
                    .with(operatorIdentity("op-1", OperatorAccessLevel.OPERATOR)))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", MediaType.APPLICATION_PDF_VALUE))
            .andExpect(header().string("Cache-Control", "no-store, private"))
            .andReturn();

    assertArrayEquals(
        printed.getResponse().getContentAsByteArray(),
        again.getResponse().getContentAsByteArray(),
        "a re-download is the stored document, not a re-render");

    assertEquals(1, auditEventCount(profileId, "profile_form_printed"));
    assertEquals(1, auditEventCount(profileId, "profile_form_redownloaded"));
  }

  /**
   * An operator cannot walk the re-download route onto another profile's print, nor onto a
   * non-print artifact of their own profile. Both directions, so a handler that simply refused
   * everything would not pass.
   */
  @Test
  void theReDownloadRouteReachesNeitherAnotherProfilesPrintNorANonPrintArtifact() throws Exception {
    String mine = submittedProfile("0000000621", "+249911006211", "IDN-0000000621");
    String theirs = submittedProfile("0000000622", "+249911006221", "IDN-0000000622");

    String theirPrint = printedArtifactId(theirs);
    String myPortrait =
        jdbcTemplate.queryForObject(
            """
            SELECT ar.artifact_ref_id::text FROM app.artifact_ref ar
             WHERE ar.cycle_id IN (SELECT cycle_id FROM app.identity_cycle WHERE profile_id = ?::uuid)
               AND ar.kind = 'portrait_uqudo' AND ar.state = 'committed'
             LIMIT 1
            """,
            String.class,
            mine);
    assertNotNull(myPortrait, "fixture expected a stored portrait on this profile");

    redownload(mine, theirPrint).andExpect(status().isNotFound());
    redownload(mine, myPortrait).andExpect(status().isNotFound());
    // And the same print IS reachable under its own profile, so the refusals above are the check
    // working rather than the route being broken.
    redownload(theirs, theirPrint).andExpect(status().isOk());
  }

  /**
   * The attachments opt-in, driven through the endpoint against a real stored certificate — the one
   * path no other test here reaches, because every other call passes {@code
   * includeAttachments=false} and {@code PrintedFormService} then never reads the certificate at
   * all. Found at review: a wrong column, cast or state predicate in the repository's certificate
   * query would have shipped undetected, and so would the PDFBox append path, which is the only
   * place this system opens UNTRUSTED customer bytes.
   *
   * <p>The certificate is a real one-page PDF, so the bundle genuinely grows a sheet: asserted as
   * more bytes AND more pages than the same profile's form alone, because a larger file could be
   * explained by anything and a page count cannot.
   *
   * <p>Synthetic throughout — a blank generated page, nobody's pay document.
   */
  @Test
  void theAttachmentsOptInAppendsTheStoredCertificateToTheSameDocument() throws Exception {
    String profileId = submittedProfile("0000000624", "+249911006241", "IDN-0000000624");
    seedSalaryCertificate(profileId);

    byte[] formAlone =
        print(profileId, "op-1", OperatorAccessLevel.OPERATOR, false)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    MvcResult bundled =
        print(profileId, "op-1", OperatorAccessLevel.OPERATOR, true)
            .andExpect(status().isOk())
            .andReturn();
    byte[] withAttachments = bundled.getResponse().getContentAsByteArray();

    assertTrue(
        sheets(withAttachments) > sheets(formAlone),
        "the attachments bundle must run to more sheets than the form alone: "
            + sheets(formAlone)
            + " -> "
            + sheets(withAttachments));

    // The event records that the customer's pay document left the building, and names it.
    String payload =
        jdbcTemplate.queryForObject(
            """
            SELECT payload_json FROM audit.audit_event
             WHERE profile_id = ?::uuid AND event_type = 'profile_form_printed'
             ORDER BY audit_event_id DESC LIMIT 1
            """,
            String.class,
            profileId);
    assertTrue(payload.contains("\"salaryCertificateIncluded\":true"), payload);
    assertTrue(payload.contains("\"attachmentsIncluded\":true"), payload);
    assertTrue(payload.contains(certificateArtifactId(profileId)), payload);

    // And the STORED print is the bundle, not the form -- the same bytes, once more.
    byte[] stored =
        jdbcTemplate.queryForObject(
            "SELECT app.artifact_read(?::uuid)",
            byte[].class,
            bundled.getResponse().getHeader("X-Printed-Form-Artifact-Id"));
    assertArrayEquals(stored, withAttachments);
  }

  /**
   * Runs one statement as {@code fru_migrator} on its own connection.
   *
   * <p>Needed because {@code app.provenance_matrix_version} is a SEED table: V0027 grants {@code
   * fru_app} {@code SELECT} on it and nothing more, so the application role cannot add an edition
   * of {@code field-provenance.md} — only a migration can. That grant is correct and this helper
   * does not weaken it; it steps outside the application's own role for a fixture, exactly as a
   * migration would, and puts the row back afterwards.
   *
   * <p>Discovered by the gate rather than assumed: the first version of this test inserted through
   * {@code jdbcTemplate} and got {@code permission denied for table provenance_matrix_version}.
   */
  private void asMigrator(String sql) throws Exception {
    try (java.sql.Connection connection =
            java.sql.DriverManager.getConnection(jdbcUrl(), "fru_migrator", FRU_MIGRATOR_PASSWORD);
        java.sql.Statement statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    }
  }

  /** How many sheets a PDF runs to, read out of the file rather than reasoned about. */
  private static int sheets(byte[] pdf) throws Exception {
    try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
      return document.getNumberOfPages();
    }
  }

  /** A one-page PDF standing in for a payslip. Synthetic: blank, nobody's pay document. */
  private void seedSalaryCertificate(String profileId) throws Exception {
    byte[] pdf;
    try (org.apache.pdfbox.pdmodel.PDDocument document =
            new org.apache.pdfbox.pdmodel.PDDocument();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
      document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
      document.save(bytes);
      pdf = bytes.toByteArray();
    }
    jdbcTemplate.update(
        """
        INSERT INTO app.artifact_ref
          (profile_id, cycle_id, kind, storage_key, content_type, byte_size, sha256, body, state)
        VALUES (?::uuid, NULL, 'salary_certificate', ?::text, 'application/pdf', ?::bigint,
                sha256(?::bytea), ?::bytea, 'committed')
        """,
        profileId,
        "certificate:" + java.util.UUID.randomUUID(),
        (long) pdf.length,
        pdf,
        pdf);
  }

  private String certificateArtifactId(String profileId) {
    return jdbcTemplate.queryForObject(
        "SELECT artifact_ref_id::text FROM app.artifact_ref WHERE profile_id = ?::uuid"
            + " AND kind = 'salary_certificate' AND state = 'committed'",
        String.class,
        profileId);
  }

  /**
   * The S8-35 guard, reached through the endpoint for the first time: bytes FOP cannot decode fail
   * the WHOLE print rather than producing a finished-looking PDF with an empty box.
   *
   * <p>FOP reports an unresolvable or undecodable image as an ERROR EVENT, not an exception — it
   * logs {@code Image not found} and lays the page out without it — so before S8-35 this path
   * returned a perfectly valid PDF that silently claimed nothing was missing. That matters more
   * here than anywhere, because the bytes become the print of record.
   *
   * <p>The fixture is the STUB's own output, left exactly as it arrives: {@code
   * "fake-image-bytes:<id>"} under a declared {@code image/jpeg}. Not a contrived corruption — it
   * is what every stub-built profile in this suite carries, which is why the other tests here call
   * {@code replaceStubImageBytesWithRealImages} and this one deliberately does not.
   *
   * <p>A 500, not a 409: something present and wrong is not the same as something absent, and
   * rendering it as "no image" would hide a corrupted identity document behind a missing-image
   * icon. Same posture the image endpoint takes on a checksum mismatch.
   */
  @Test
  void anUndecodableStoredImageFailsThePrintRatherThanPrintingAnEmptyBox() throws Exception {
    // Everything submittedProfile() does EXCEPT the byte replacement.
    String profileId = profileWithPassedLiveness("0000000623", "+249911006231", "IDN-0000000623");
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

    assertThrows(
        ServletException.class,
        () -> print(profileId, "op-1", OperatorAccessLevel.OPERATOR, false));

    // And the refusal stored NOTHING. A failed print must not leave a partial artifact behind,
    // which is what the single transaction is for.
    assertEquals(0, storedPrintCount(profileId));
    assertEquals(0, auditEventCount(profileId, "profile_form_printed"));
  }

  // ---- fixtures -------------------------------------------------------------------------------

  /**
   * The operator's identity, injected the way {@code OperatorIdentityArgumentResolver} reads it. A
   * local copy rather than a shared helper: {@code OperatorProfileListIntegrationTest}'s version is
   * package-private to {@code ...operator} and this class is not in that package. The role follows
   * the level, which is every combination this class needs — since AD-013 an admin arrives at
   * OPERATOR level too, and {@code PrintedFormServiceTest} covers that case.
   */
  private static org.springframework.test.web.servlet.request.RequestPostProcessor operatorIdentity(
      String operatorId, OperatorAccessLevel level) {
    String actorRole = level == OperatorAccessLevel.OPERATOR ? "operator" : "viewer";
    return request -> {
      request.setAttribute(
          OperatorIdentityArgumentResolver.REQUEST_ATTRIBUTE,
          new OperatorIdentity(operatorId, level, actorRole));
      return request;
    };
  }

  private ResultActions print(
      String profileId, String operatorId, OperatorAccessLevel level, boolean includeAttachments)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/operator/profiles/" + profileId + "/print")
            .with(operatorIdentity(operatorId, level))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"includeAttachments\":" + includeAttachments + "}"));
  }

  private ResultActions redownload(String profileId, String artifactId) throws Exception {
    return mockMvc.perform(
        get("/api/v1/operator/profiles/" + profileId + "/prints/" + artifactId)
            .with(operatorIdentity("op-1", OperatorAccessLevel.OPERATOR)));
  }

  private String printedArtifactId(String profileId) throws Exception {
    return print(profileId, "op-1", OperatorAccessLevel.OPERATOR, false)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getHeader("X-Printed-Form-Artifact-Id");
  }

  private int storedPrintCount(String profileId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app.artifact_ref WHERE profile_id = ?::uuid"
                + " AND kind = 'printed_form'",
            Integer.class,
            profileId);
    return count == null ? 0 : count;
  }

  private int auditEventCount(String profileId, String eventType) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit.audit_event WHERE profile_id = ?::uuid"
                + " AND event_type = ?::text",
            Integer.class,
            profileId,
            eventType);
    return count == null ? 0 : count;
  }

  /** The whole customer journey through to {@code submitted}. */
  private String submittedProfile(String accountNumber, String phoneNumber, String identityNumber)
      throws Exception {
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

    mockMvc
        .perform(
            post("/api/v1/submission")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isOk());
    // Backdated for the reason every other class's identical helper documents: an un-backdated row
    // is claimable by NotificationOutboxIntegrationTest's unscoped poll for the rest of the run.
    jdbcTemplate.update(
        "UPDATE app.notification_outbox SET next_attempt_at = now() + interval '1 hour'"
            + " WHERE profile_id = ?::uuid",
        profileId);
    replaceStubImageBytesWithRealImages(profileId);
    return profileId;
  }

  /**
   * Gives this profile's stored images bytes that are actually images.
   *
   * <p><strong>Why this is needed, and why it is a fixture step rather than a workaround.</strong>
   * {@code StubUqudoClient.fakeImageBytes} stores the ASCII string {@code "fake-image-bytes:<id>"}
   * under a declared {@code image/jpeg}. Nothing before BL-132 ever decoded those bytes — the
   * operator image endpoint streams them into an {@code <img>} that fails client-side, and no
   * server-side code opened one. The printed form is the first consumer that must DECODE an
   * identity image, and FOP cannot decode a sentence.
   *
   * <p>So a stub-built profile cannot be printed, and the print refusing is CORRECT: S8-35 made the
   * renderer refuse any render FOP could not complete, precisely so that a truncated or mislabelled
   * image fails the print rather than printing an empty box where the absence rule requires «غير
   * متاح». That behaviour is asserted directly by {@link
   * #anUndecodableStoredImageFailsThePrintRatherThanPrintingAnEmptyBox}. This helper exists so the
   * other tests can exercise a profile shaped like a real one — staging and production both run
   * {@code FRU_UQUDO_CLIENT=http}, where the bytes are real photographs.
   *
   * <p>The swatch is a generated rectangle, never a photograph of anybody: CLAUDE.md forbids
   * identity-document images in the repo and in fixtures, and a checked-in face would be one
   * whether or not it belonged to a customer.
   */
  private void replaceStubImageBytesWithRealImages(String profileId) {
    byte[] swatch = PrintedFormServiceTest.pngSwatch();
    jdbcTemplate.update(
        """
        UPDATE app.artifact_ref ar
           SET body = ?::bytea, sha256 = sha256(?::bytea), byte_size = ?::bigint,
               content_type = 'image/png'
         WHERE (ar.profile_id = ?::uuid
                OR ar.cycle_id IN (SELECT cycle_id FROM app.identity_cycle WHERE profile_id = ?::uuid))
           AND ar.kind = ANY ('{doc_front,portrait_uqudo,portrait_registry,face_audit_trail,signature}'::text[])
           AND ar.state = 'committed'
        """,
        swatch,
        swatch,
        (long) swatch.length,
        profileId,
        profileId);
  }

  /** Mirrors {@code OperatorImageIntegrationTest}'s proven helper chain. */
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
