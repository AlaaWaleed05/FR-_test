package com.sfbank.bayanati.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.operator.domain.ExportResult;
import com.sfbank.bayanati.operator.domain.ExportRow;
import com.sfbank.bayanati.operator.domain.OperatorAccessLevel;
import com.sfbank.bayanati.operator.domain.ProfileListFilter;
import com.sfbank.bayanati.operator.domain.ProfileListRepository;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
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
 * S4-02: export (operator.md "Export"). Branch 16, accounts 0000000481–0000000495 (see {@link
 * AbstractPostgresIntegrationTest}).
 */
@Tag("integration")
@SpringBootTest
// S4-05: see OperatorReviewIntegrationTest's identical comment -- filters disabled here so this
// class stays scoped to export business logic via direct identity injection; the real auth layer
// is proven separately in auth/OperatorAuthenticationIntegrationTest.
@AutoConfigureMockMvc(addFilters = false)
class ProfileExportIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String BRANCH = "16";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ProfileListRepository profileListRepository;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @Test
  void exportHonoursFilterAndProvenanceIsItsOwnColumn() throws Exception {
    // Two profiles with disjoint search terms -- proves the filter excludes the non-matching one,
    // and that a profile whose provenance is 'manual' carries a visibly different column value
    // from a 'digital' one. THIS IS A TEST OF THE EXPORT COLUMN, not of how the value got there.
    createInProgressProfile("0000000481", "+249911008111");
    String manualProfileId = createInProgressProfile("0000000482", "+249911008211");
    seedLegacyManualProvenance(manualProfileId);

    MvcResult digitalResult =
        mockMvc
            .perform(
                get("/api/v1/operator/profiles/export")
                    .param("format", "csv")
                    .param("q", "+249911008111")
                    .with(
                        OperatorProfileListIntegrationTest.operatorIdentity(
                            "op-export-1", OperatorAccessLevel.OPERATOR)))
            .andExpect(status().isOk())
            .andReturn();
    assertEquals("1", digitalResult.getResponse().getHeader("X-Export-Row-Count"));
    assertEquals("false", digitalResult.getResponse().getHeader("X-Export-Truncated"));
    List<String[]> digitalRows = parseCsv(digitalResult.getResponse().getContentAsByteArray());
    assertEquals(2, digitalRows.size(), "header + exactly one matching data row");
    assertEquals(List.of(ExportRow.COLUMNS), Arrays.asList(digitalRows.get(0)));
    assertEquals("digital", digitalRows.get(1)[provenanceColumnIndex()]);

    MvcResult manualResult =
        mockMvc
            .perform(
                get("/api/v1/operator/profiles/export")
                    .param("format", "csv")
                    .param("q", "+249911008211")
                    .with(
                        OperatorProfileListIntegrationTest.operatorIdentity(
                            "op-export-1", OperatorAccessLevel.OPERATOR)))
            .andExpect(status().isOk())
            .andReturn();
    List<String[]> manualRows = parseCsv(manualResult.getResponse().getContentAsByteArray());
    assertEquals(2, manualRows.size());
    assertEquals("manual", manualRows.get(1)[provenanceColumnIndex()]);
  }

  @Test
  void exportAuditEventRecordsFiltersRowCountAndFields() throws Exception {
    createInProgressProfile("0000000483", "+249911008311");

    mockMvc
        .perform(
            get("/api/v1/operator/profiles/export")
                .param("format", "xlsx")
                .param("q", "+249911008311")
                .with(
                    OperatorProfileListIntegrationTest.operatorIdentity(
                        "op-export-audit", OperatorAccessLevel.OPERATOR)))
        .andExpect(status().isOk());

    java.util.Map<String, Object> auditRow =
        jdbcTemplate.queryForMap(
            "SELECT payload ->> 'format' AS format, payload ->> 'rowCount' AS row_count,"
                + " payload ->> 'truncated' AS truncated, payload ->> 'searchText' AS search_text,"
                + " payload ->> 'fields' AS fields"
                + " FROM audit.audit_event ae JOIN audit.audit_chain ac ON ac.chain_id = ae.chain_id"
                + " WHERE ac.chain_kind = 'operator' AND ac.subject_id = ?"
                + " AND ae.event_type = 'profile_exported'"
                + " ORDER BY ae.audit_event_id DESC LIMIT 1",
            "op-export-audit");
    assertEquals("XLSX", auditRow.get("format"));
    assertEquals("1", auditRow.get("row_count"));
    assertEquals("false", auditRow.get("truncated"));
    assertEquals("+249911008311", auditRow.get("search_text"));
    assertEquals(String.join(",", ExportRow.COLUMNS), auditRow.get("fields"));
  }

  @Test
  void rowCapTruncatesAndReportsTruncated() throws Exception {
    createInProgressProfile("0000000484", "+249911008411");
    createInProgressProfile("0000000485", "+249911008421");
    createInProgressProfile("0000000486", "+249911008431");

    // Common prefix of all three phone numbers below ("+2499110084" -- 11 chars: the three
    // fixtures differ only at the 12th character, 1/2/3).
    ProfileListFilter filter =
        new ProfileListFilter(null, null, null, null, null, null, "+2499110084");
    ExportResult capped = profileListRepository.forExport(filter, 2);
    assertTrue(capped.truncated());
    assertEquals(2, capped.rows().size());

    ExportResult uncapped = profileListRepository.forExport(filter, 10);
    assertFalse(uncapped.truncated());
    assertEquals(3, uncapped.rows().size());

    // The exact boundary: rowLimit equal to the number of matching rows must not report
    // truncated (found under review as an untested boundary -- LIMIT rowLimit+1 returns exactly
    // rowLimit rows here, so rows.size() > rowLimit is false).
    ExportResult exact = profileListRepository.forExport(filter, 3);
    assertFalse(exact.truncated());
    assertEquals(3, exact.rows().size());
  }

  @Test
  void viewerCannotExport() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/operator/profiles/export")
                .param("format", "csv")
                .with(
                    OperatorProfileListIntegrationTest.operatorIdentity(
                        "op-export-viewer", OperatorAccessLevel.VIEWER)))
        .andExpect(status().isForbidden());
  }

  @Test
  void unknownFormatIs400() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/operator/profiles/export")
                .param("format", "pdf")
                .with(
                    OperatorProfileListIntegrationTest.operatorIdentity(
                        "op-export-badformat", OperatorAccessLevel.OPERATOR)))
        .andExpect(status().isBadRequest());
  }

  // ---- helpers ----

  private static int provenanceColumnIndex() {
    for (int i = 0; i < ExportRow.COLUMNS.length; i++) {
      if ("provenance".equals(ExportRow.COLUMNS[i])) {
        return i;
      }
    }
    throw new IllegalStateException("provenance column not found");
  }

  /**
   * Strips the UTF-8 BOM and splits on CRLF/comma -- safe here since no test fixture value below
   * contains a comma, quote or newline.
   */
  private static List<String[]> parseCsv(byte[] content) {
    String text = new String(content, StandardCharsets.UTF_8);
    if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
      text = text.substring(1);
    }
    List<String[]> rows = new java.util.ArrayList<>();
    for (String line : text.split("\r\n")) {
      if (!line.isEmpty()) {
        rows.add(line.split(",", -1));
      }
    }
    return rows;
  }

  /**
   * Sets {@code app.profile.provenance = 'manual'} directly, the way a profile completed BEFORE
   * AD-022 still reads today.
   *
   * <p>This used to drive {@code POST /manual-complete}, which was the only writer of {@code
   * 'manual'} anywhere in the system. S9-01 deleted that endpoint, so no code path can produce the
   * value any more and the assertion below cannot be reached through the API at all. Seeding it is
   * not a weakening of the test: its subject is that the export renders provenance as its own
   * column with the stored value in it, and legacy {@code 'manual'} rows are exactly what that
   * column still has to render correctly. The same raw-SQL-seed pattern as {@code
   * OperatorReviewIntegrationTest#seedManualCompletionHistoryRow}, and for the same reason.
   *
   * <p>When BL-135 ships per-field editing, {@code 'manual'} gets a writer again and this can be
   * re-pointed at it.
   */
  private void seedLegacyManualProvenance(String profileId) {
    jdbcTemplate.update(
        "UPDATE app.profile SET provenance = 'manual' WHERE profile_id = ?::uuid", profileId);
  }

  private String createInProgressProfile(String accountNumber, String phoneNumber)
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
    JsonNode responseBody = objectMapper.readTree(result.getResponse().getContentAsString());
    return responseBody.get("profileId").asText();
  }
}
