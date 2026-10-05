package com.sfbank.bayanati.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * BL-135 / AD-015 — per-field editing against the real database. Branch 16, accounts
 * 0000000630–0000000640 (see {@link AbstractPostgresIntegrationTest}).
 *
 * <p>These are the proofs a mocked-transaction-manager unit test cannot give (CLAUDE.md "live proof
 * versus a passing test"): that {@code editableFields} is genuinely DERIVED from stored values
 * rather than a constant list, and that {@code app.profile.provenance} flips to {@code manual}
 * through {@code app.derived_provenance()} with nothing writing the column.
 *
 * <p>Filters disabled, identity injected directly — the same scoping note {@code
 * OperatorReviewIntegrationTest} carries; the real-identity proofs live in {@code
 * auth/OperatorAuthenticationIntegrationTest}.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class FieldEditIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String BRANCH = "16";
  private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");
  private static final String OPERATOR_ID = "faheem.operator";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StubUqudoClient stubUqudoClient;
  @MockitoSpyBean private MessageSender messageSender;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  // ------------------------------------------------------------- editability is DERIVED, not fixed

  /**
   * The load-bearing proof of AD-021's "editability is derived per profile, never a hardcoded field
   * list". Two profiles, identical but for the home address's COUNTRY, must produce different
   * editable sets against the same schema and the same code. A constant list would pass every
   * single-profile assertion and fail this one.
   */
  @Test
  void editableFieldsDifferBetweenASudanProfileAndAnIdenticalNonSudanOne() throws Exception {
    String sudan = submittedProfile("0000000630", "+249911630001", "000000630001", true);
    String abroad = submittedProfile("0000000631", "+249911631001", "000000631001", false);

    List<String> sudanFields = editableFields(sudan);
    List<String> abroadFields = editableFields(abroad);

    assertNotEquals(sudanFields, abroadFields, "a hardcoded list would make these identical");

    // Sudan: state and locality are list-picked, so there is no free text to edit.
    assertFalse(sudanFields.contains("HOME_STATE_TEXT"), sudanFields.toString());
    assertFalse(sudanFields.contains("HOME_LOCALITY_TEXT"), sudanFields.toString());
    // Abroad: customer.md Stage 5 -- "Both fall back to free text."
    assertTrue(abroadFields.contains("HOME_STATE_TEXT"), abroadFields.toString());
    assertTrue(abroadFields.contains("HOME_LOCALITY_TEXT"), abroadFields.toString());

    // The eleven unconditional fields are on both, so the difference is the cascade and nothing
    // else.
    for (String always : List.of("ETHNICITY", "EMPLOYER_NAME", "HOME_AREA", "HOME_CITY")) {
      assertTrue(sudanFields.contains(always), always);
      assertTrue(abroadFields.contains(always), always);
    }
  }

  /**
   * Field 23, the product-owner ruling of 2026-09-16 that closed BL-135's third open edge. Every
   * profile this suite builds is scanned, and the stub returns a {@code placeOfBirth} — so birth
   * city must NOT be editable, and the fallback branch is proved by the unit test.
   */
  @Test
  void birthCityIsNotEditableOnceTheScanSuppliedAPlaceOfBirth() throws Exception {
    String profileId = submittedProfile("0000000632", "+249911632001", "000000632001", true);

    String scannedBirthCity =
        jdbcTemplate.queryForObject(
            """
            SELECT sr.birth_city FROM app.scan_result sr
              JOIN app.identity_cycle ic ON ic.cycle_id = sr.cycle_id
             WHERE ic.profile_id = ?::uuid ORDER BY ic.seq DESC LIMIT 1
            """,
            String.class,
            profileId);

    // The premise, asserted rather than assumed -- if the stub stopped returning one, the
    // assertion below would pass for the wrong reason.
    assertTrue(
        scannedBirthCity != null && !scannedBirthCity.isBlank(), "stub supplied a birth city");
    assertFalse(editableFields(profileId).contains("BIRTH_CITY"));
  }

  // ------------------------------------------------------------------------------- the write path

  /**
   * One edit, end to end: the column changes, {@code app.profile_field_edit} gains its row, and the
   * audit chain gains a {@code profile_field_edited} event carrying both values.
   */
  @Test
  void anEditStoresTheValueRecordsProvenanceAndAuditsBothValues() throws Exception {
    String profileId = submittedProfile("0000000633", "+249911633001", "000000633001", true);

    mockMvc
        .perform(
            patch("/api/v1/operator/profiles/" + profileId + "/fields/HOME_AREA")
                .with(operator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"  الثورة الحارة ٢٤  \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fieldKey").value("HOME_AREA"))
        // The value as STORED, after trimming -- not as submitted.
        .andExpect(jsonPath("$.value").value("الثورة الحارة ٢٤"));

    assertEquals(
        "الثورة الحارة ٢٤",
        jdbcTemplate.queryForObject(
            "SELECT home_area FROM app.profile_customer_data WHERE profile_id = ?::uuid",
            String.class,
            profileId));

    Map<String, Object> edit =
        jdbcTemplate.queryForMap(
            "SELECT field_key, field_number, previous_value, new_value, edited_by"
                + " FROM app.profile_field_edit WHERE profile_id = ?::uuid",
            profileId);
    assertEquals("HOME_AREA", edit.get("field_key"));
    assertEquals(38, edit.get("field_number"));
    assertEquals("الثورة", edit.get("previous_value"));
    assertEquals("الثورة الحارة ٢٤", edit.get("new_value"));
    assertEquals(OPERATOR_ID, edit.get("edited_by"));

    String payload =
        jdbcTemplate.queryForObject(
            """
            SELECT payload_json::text FROM audit.audit_event
             WHERE profile_id = ?::uuid AND event_type = 'profile_field_edited'
             ORDER BY audit_event_id DESC LIMIT 1
            """,
            String.class,
            profileId);
    assertTrue(payload.contains("\"fieldKey\":\"HOME_AREA\""), payload);
    assertTrue(payload.contains("\"previousValue\":\"الثورة\""), payload);
    assertTrue(payload.contains("\"newValue\":\"الثورة الحارة ٢٤\""), payload);
    assertTrue(payload.contains("\"actorRole\":\"operator\""), payload);
  }

  /**
   * BL-155 closed. Provenance is DERIVED through {@code app.derived_provenance()} — nothing writes
   * {@code app.profile.provenance}, and this asserts the stored column is still {@code digital}
   * while every read path reports {@code manual}. A test that only checked the API would pass
   * against an implementation that stamped the column, which AD-022 §2.1 forbids.
   */
  @Test
  void theFirstEditMakesTheProfileManualWithoutWritingTheProvenanceColumn() throws Exception {
    String profileId = submittedProfile("0000000634", "+249911634001", "000000634001", true);

    assertEquals("digital", detail(profileId).get("provenance").asText());

    mockMvc
        .perform(
            patch("/api/v1/operator/profiles/" + profileId + "/fields/EMPLOYER_NAME")
                .with(operator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"الجهاز المركزي للإحصاء\"}"))
        .andExpect(status().isOk());

    assertEquals("manual", detail(profileId).get("provenance").asText());

    // The stored column is untouched -- the whole point of the derivation.
    assertEquals(
        "digital",
        jdbcTemplate.queryForObject(
            "SELECT provenance FROM app.profile WHERE profile_id = ?::uuid",
            String.class,
            profileId));

    // And the list agrees with the detail, because both call the same SQL function.
    MvcResult listed =
        mockMvc
            .perform(
                get("/api/v1/operator/profiles")
                    .with(operator())
                    .param("provenance", "manual")
                    .param("q", "0000000634"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode rows = objectMapper.readTree(listed.getResponse().getContentAsString()).get("rows");
    assertEquals(1, rows.size(), rows.toString());
    assertEquals("manual", rows.get(0).get("provenance").asText());
  }

  /** A second edit of the same field overwrites its row rather than adding one (V0073's upsert). */
  @Test
  void editingTheSameFieldTwiceKeepsOneProvenanceRowAndTwoAuditEvents() throws Exception {
    String profileId = submittedProfile("0000000635", "+249911635001", "000000635001", true);

    editHomeArea(profileId, "الأولى");
    editHomeArea(profileId, "الثانية");

    assertEquals(
        1,
        (int)
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app.profile_field_edit WHERE profile_id = ?::uuid",
                Integer.class,
                profileId));
    assertEquals(
        "الثانية",
        jdbcTemplate.queryForObject(
            "SELECT new_value FROM app.profile_field_edit WHERE profile_id = ?::uuid",
            String.class,
            profileId));
    // The tamper-evident history is the chain, which keeps BOTH.
    assertEquals(
        2,
        (int)
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit.audit_event WHERE profile_id = ?::uuid"
                    + " AND event_type = 'profile_field_edited'",
                Integer.class,
                profileId));
  }

  /**
   * Field 20's «أخرى» text — the only editable field whose target is a SECOND table ({@code
   * app.profile_income_source}, keyed by {@code (profile_id, 'OTHER')}). Its two statements are the
   * one SQL pair no other test in this class exercises, so without this the product-owner ruling
   * that the chip edits {@code other_text} and nothing else ships unrun against Postgres. Found by
   * {@code @agent-reviewer}.
   *
   * <p>Also asserts what must NOT change: the code set and the primary flag stay exactly as the
   * customer left them, because AD-021 forbids editing a list-picked value.
   */
  @Test
  void theOtherIncomeTextIsEditedWithoutTouchingTheCodesOrThePrimaryFlag() throws Exception {
    String profileId = submittedProfile("0000000640", "+249911640001", "000000640001", true);

    assertTrue(editableFields(profileId).contains("INCOME_OTHER_TEXT"));

    mockMvc
        .perform(
            patch("/api/v1/operator/profiles/" + profileId + "/fields/INCOME_OTHER_TEXT")
                .with(operator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"إيجار عقار\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.value").value("إيجار عقار"));

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT source_code, is_primary, other_text FROM app.profile_income_source"
                + " WHERE profile_id = ?::uuid",
            profileId);
    assertEquals("OTHER", row.get("source_code"));
    assertEquals(true, row.get("is_primary"));
    assertEquals("إيجار عقار", row.get("other_text"));

    // The per-field provenance row records field 20, not the column name.
    assertEquals(
        20,
        jdbcTemplate.queryForObject(
            "SELECT field_number FROM app.profile_field_edit WHERE profile_id = ?::uuid"
                + " AND field_key = 'INCOME_OTHER_TEXT'",
            Integer.class,
            profileId));
  }

  // ------------------------------------------------------------------------------------- refusals

  /**
   * The server re-derives editability rather than trusting the browser. This field is genuinely
   * editable on an abroad profile (proved above), so the refusal is about THIS profile's stored
   * values and not about the field being unknown.
   */
  @Test
  void aFieldThisProfileDoesNotExposeIsRefusedAndNothingIsWritten() throws Exception {
    String profileId = submittedProfile("0000000636", "+249911636001", "000000636001", true);

    mockMvc
        .perform(
            patch("/api/v1/operator/profiles/" + profileId + "/fields/HOME_STATE_TEXT")
                .with(operator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"Cairo Governorate\"}"))
        .andExpect(status().isConflict());

    assertEquals(
        0,
        (int)
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app.profile_field_edit WHERE profile_id = ?::uuid",
                Integer.class,
                profileId));
    assertEquals("digital", detail(profileId).get("provenance").asText());
  }

  /**
   * AD-015: editing stops at {@code approved} and is never permitted after — on BOTH sides.
   *
   * <p>The read half matters as much as the write half: {@code editableFields} is gated on the same
   * status set {@code FieldEditService} enforces, so an approved profile returns an EMPTY list
   * rather than a full one whose every «تعديل» chip would 409. Found by {@code @agent-reviewer}.
   */
  @Test
  void anApprovedProfileRefusesEveryEditAndOffersNoChips() throws Exception {
    String profileId = submittedProfile("0000000637", "+249911637001", "000000637001", true);
    assertFalse(editableFields(profileId).isEmpty(), "editable while submitted");

    mockMvc
        .perform(post("/api/v1/operator/profiles/" + profileId + "/approve").with(operator()))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            patch("/api/v1/operator/profiles/" + profileId + "/fields/HOME_AREA")
                .with(operator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"الثورة\"}"))
        .andExpect(status().isConflict());

    assertEquals(
        "الثورة",
        jdbcTemplate.queryForObject(
            "SELECT home_area FROM app.profile_customer_data WHERE profile_id = ?::uuid",
            String.class,
            profileId));
    assertTrue(editableFields(profileId).isEmpty(), "editing stops at approved, for ever (AD-015)");
  }

  /** AD-015's "viewers cannot edit", enforced by the service as well as by the route's own rule. */
  @Test
  void aViewerIsRefused() throws Exception {
    String profileId = submittedProfile("0000000638", "+249911638001", "000000638001", true);

    mockMvc
        .perform(
            patch("/api/v1/operator/profiles/" + profileId + "/fields/HOME_AREA")
                .with(viewer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"الثورة\"}"))
        .andExpect(status().isForbidden());

    assertEquals(
        0,
        (int)
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app.profile_field_edit WHERE profile_id = ?::uuid",
                Integer.class,
                profileId));
  }

  @Test
  void anUnknownFieldKeyAndABlankValueAreBothBadRequests() throws Exception {
    String profileId = submittedProfile("0000000639", "+249911639001", "000000639001", true);

    mockMvc
        .perform(
            patch("/api/v1/operator/profiles/" + profileId + "/fields/PHONE_NUMBER")
                .with(operator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"+249900000001\"}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            patch("/api/v1/operator/profiles/" + profileId + "/fields/HOME_AREA")
                .with(operator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"   \"}"))
        .andExpect(status().isBadRequest());

    assertEquals(
        "+249911639001",
        jdbcTemplate.queryForObject(
            "SELECT phone_number FROM app.profile_customer_data WHERE profile_id = ?::uuid",
            String.class,
            profileId));
  }

  // ------------------------------------------------------------------------------------- helpers

  private void editHomeArea(String profileId, String value) throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/operator/profiles/" + profileId + "/fields/HOME_AREA")
                .with(operator())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("value", value))))
        .andExpect(status().isOk());
  }

  private List<String> editableFields(String profileId) throws Exception {
    JsonNode fields = detail(profileId).get("editableFields");
    return objectMapper.convertValue(fields, new tools.jackson.core.type.TypeReference<>() {});
  }

  private JsonNode detail(String profileId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/operator/profiles/" + profileId).with(operator()))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private static RequestPostProcessor operator() {
    return identity(OPERATOR_ID, OperatorAccessLevel.OPERATOR, "operator");
  }

  private static RequestPostProcessor viewer() {
    return identity("nadia.viewer", OperatorAccessLevel.VIEWER, "viewer");
  }

  private static RequestPostProcessor identity(
      String operatorId, OperatorAccessLevel level, String actorRole) {
    return request -> {
      request.setAttribute(
          OperatorIdentityArgumentResolver.REQUEST_ATTRIBUTE,
          new OperatorIdentity(operatorId, level, actorRole));
      return request;
    };
  }

  /**
   * A profile in {@code submitted} with every stage 3-6 field populated.
   *
   * @param sudanAddress when false, the home address is in Egypt — which is what makes fields 36
   *     and 37 free text (customer.md "Addresses outside Sudan")
   */
  private String submittedProfile(
      String accountNumber, String phoneNumber, String identityNumber, boolean sudanAddress)
      throws Exception {
    String profileId = profileWithPassedLiveness(accountNumber, phoneNumber, identityNumber);

    submitStage3(profileId);
    submitStage4(profileId);
    submitStage5(profileId, sudanAddress);
    submitStage6(profileId);

    String base64 =
        Base64.getEncoder().encodeToString("fake-signature-bytes".getBytes(StandardCharsets.UTF_8));
    Map<String, Object> signature = new LinkedHashMap<>();
    signature.put("profileId", profileId);
    signature.put("captureMethod", "drawn");
    signature.put("contentType", "image/png");
    signature.put("contentBase64", base64);
    mockMvc
        .perform(
            post("/api/v1/signature")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signature)))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/api/v1/submission")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isOk());
    // Backdate the "received" notification so NotificationOutboxIntegrationTest's unscoped
    // claimOnePending() poll cannot pick it up for the rest of the suite run.
    jdbcTemplate.update(
        "UPDATE app.notification_outbox SET next_attempt_at = now() + interval '1 hour'"
            + " WHERE profile_id = ?::uuid",
        profileId);
    return profileId;
  }

  private void submitStage3(String profileId) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("profileId", profileId);
    body.put("sexDeclared", "f");
    body.put("ethnicity", "شايقية");
    body.put("countryOfResidenceCode", "SD");
    body.put("maritalStatus", "married");
    body.put("spouseName", "وليد الأمين محمد");
    body.put("hasChildren", true);
    body.put("childrenCount", 2);
    body.put("educationLevel", 6);
    body.put("birthCountryCode", "SD");
    body.put("birthStateCode", "11");
    body.put("birthCityText", "أم درمان");
    postJson("/api/v1/data-entry/stage3", body);
  }

  private void submitStage4(String profileId) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("profileId", profileId);
    body.put("occupationCode", occupationCode());
    body.put(
        "incomeSources",
        List.of(Map.of("code", "OTHER", "primary", true, "otherText", "منحة دراسية")));
    body.put("monthlyExpensesSdg", "45000");
    postJson("/api/v1/data-entry/stage4", body);
  }

  private void submitStage5(String profileId, boolean sudanAddress) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("profileId", profileId);
    if (sudanAddress) {
      body.put("countryCode", "SD");
      body.put("stateCode", "11");
      body.put("localityCode", localityCodeUnder("11"));
    } else {
      body.put("countryCode", "EG");
      body.put("stateText", "القاهرة");
      body.put("localityText", "مدينة نصر");
    }
    body.put("city", "أم درمان");
    // home_area: the value every HOME_AREA previous_value assertion reads back.
    body.put("area", "الثورة");
    body.put("street", "شارع الأربعين");
    body.put("block", "12");
    body.put("houseNumber", "47");
    postJson("/api/v1/data-entry/stage5", body);
  }

  private void submitStage6(String profileId) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("profileId", profileId);
    body.put("employer", "الجهاز المركزي");
    body.put("countryCode", "SD");
    body.put("stateCode", "11");
    body.put("localityCode", localityCodeUnder("11"));
    body.put("city", "الخرطوم");
    body.put("area", "المقرن");
    body.put("street", "شارع النيل");
    body.put("block", "3");
    body.put("salaryCertificateAttached", false);
    postJson("/api/v1/data-entry/stage6", body);
  }

  private void postJson(String path, Map<String, Object> body) throws Exception {
    mockMvc
        .perform(
            post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  /** Read from the seeded catalogue rather than hardcoded — CLAUDE.md's reference-list rule. */
  private String occupationCode() {
    return jdbcTemplate.queryForObject(
        """
        SELECT item_code FROM ref.reference_item ri
          JOIN ref.reference_list_version v
            ON v.list_code = ri.list_code AND v.version = ri.version AND v.is_current
         WHERE ri.list_code = 'occupation' AND ri.is_active ORDER BY ri.item_code LIMIT 1
        """,
        String.class);
  }

  private String localityCodeUnder(String stateCode) {
    return jdbcTemplate.queryForObject(
        """
        SELECT item_code FROM ref.reference_item ri
          JOIN ref.reference_list_version v
            ON v.list_code = ri.list_code AND v.version = ri.version AND v.is_current
         WHERE ri.list_code = 'admin_division' AND ri.parent_code = ? AND ri.is_active
         ORDER BY ri.item_code LIMIT 1
        """,
        String.class,
        stateCode);
  }

  // The scan/liveness chain, mirroring OperatorProfileViewIntegrationTest's proven helpers.

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

    String smsCode = capturedCode();
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

  private String capturedCode() {
    org.mockito.ArgumentCaptor<OutboundMessage> captor =
        org.mockito.ArgumentCaptor.forClass(OutboundMessage.class);
    verify(messageSender, org.mockito.Mockito.atLeastOnce()).send(captor.capture());
    List<OutboundMessage> sent = captor.getAllValues();
    for (int i = sent.size() - 1; i >= 0; i--) {
      OutboundMessage message = sent.get(i);
      if (message.channel() == MessageChannel.SMS && message.payload() instanceof SmsPayload sms) {
        Matcher matcher = SIX_DIGITS.matcher(sms.body());
        if (matcher.find()) {
          return matcher.group();
        }
      }
    }
    throw new IllegalStateException("no OTP code captured");
  }
}
