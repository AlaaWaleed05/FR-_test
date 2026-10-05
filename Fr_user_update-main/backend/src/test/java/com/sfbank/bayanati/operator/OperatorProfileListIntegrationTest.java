package com.sfbank.bayanati.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.AbstractPostgresIntegrationTest;
import com.sfbank.bayanati.operator.domain.OperatorAccessLevel;
import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.web.OperatorIdentityArgumentResolver;
import com.sfbank.bayanati.uqudo.stub.StubUqudoClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S4-01: the profile list — server-side filter/sort/search/pagination (operator.md, BL-015). Branch
 * 16, accounts 0000000441–0000000445 (see {@link AbstractPostgresIntegrationTest}).
 *
 * <p>Every fixture's phone number carries a test-method-specific prefix and every assertion filters
 * by it via {@code q} — branch/account-number scoping alone is not enough to isolate this test's
 * row count from the hundreds of other rows every other integration class leaves in the same shared
 * container (see {@code AbstractPostgresIntegrationTest}'s own Javadoc on why disjoint keys are not
 * the whole isolation story).
 */
@Tag("integration")
@SpringBootTest
// S4-05: see OperatorReviewIntegrationTest's identical comment -- filters disabled here so this
// class stays scoped to list/filter/search business logic via direct identity injection; the real
// auth layer is proven separately in auth/OperatorAuthenticationIntegrationTest.
@AutoConfigureMockMvc(addFilters = false)
class OperatorProfileListIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String BRANCH = "16";
  private static final String OPERATOR_ID = "op-list-test";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StubUqudoClient stubUqudoClient;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @Test
  void searchFiltersServerSideIncludingArabicFold() throws Exception {
    // "+2499000..." collides with several other integration classes' own phone-number conventions
    // (e.g. ContactChannelsIntegrationTest also uses "+249900001..."/"+249900002..." for unrelated
    // fixtures). "+249911..." is this class's own reserved sub-range within the operator classes'
    // shared "+249911..." prefix -- +249911001/+249911002 are THIS class's (see the other test
    // method below), +249911003/+249911005 belong to OperatorProfileViewIntegrationTest/
    // OperatorReviewIntegrationTest (AbstractPostgresIntegrationTest's own range map) -- none of
    // the three overlaps as a substring of another.
    String phonePrefix = "+249911001";
    createContactChannelsProfile("0000000441", phonePrefix + "1");
    String profileWithScan = createContactChannelsProfile("0000000442", phonePrefix + "2");
    pushThroughScan(profileWithScan, "0000000442");

    // The shared prefix finds both fixtures, proving the search predicate really is an OR across
    // the scoped field set, not just one column.
    MvcResult both =
        mockMvc
            .perform(get("/api/v1/operator/profiles").param("q", phonePrefix).with(viewer()))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode bothBody = objectMapper.readTree(both.getResponse().getContentAsString());
    assertEquals(2, bothBody.get("total").asLong());

    // The Arabic-folded on-document name (StubUqudoClient's fixed "محمد الطيب") finds the scanned
    // profile, proving ref.ar_fold is actually applied, not a plain byte-for-byte LIKE. Not
    // asserted as the ONLY match: every other integration class that runs a passport/national-ID
    // scan gets the identical stub name, so this profile is one of several real matches across the
    // shared container, not a unique one -- presence, not exclusivity, is what this proves.
    MvcResult arabicMatch =
        mockMvc
            .perform(
                get("/api/v1/operator/profiles")
                    .param("q", "الطيب")
                    .param("pageSize", "200")
                    .with(viewer()))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode arabicBody = objectMapper.readTree(arabicMatch.getResponse().getContentAsString());
    assertTrue(arabicBody.get("total").asLong() >= 1);
    boolean found = false;
    for (JsonNode row : arabicBody.get("rows")) {
      found |= profileWithScan.equals(row.get("profileId").asText());
    }
    assertTrue(
        found,
        "the Arabic-folded search must include the fixture with the matching on-document name");

    // Status filter combined with the search scope.
    MvcResult filtered =
        mockMvc
            .perform(
                get("/api/v1/operator/profiles")
                    .param("q", phonePrefix)
                    .param("status", "in_progress")
                    .with(viewer()))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode filteredBody = objectMapper.readTree(filtered.getResponse().getContentAsString());
    assertEquals(2, filteredBody.get("total").asLong(), "both fixtures are still in_progress");
  }

  @Test
  void listSearchIsItsOwnAuditEventOnTheOperatorChain() throws Exception {
    // A dedicated operator id, not the shared OPERATOR_ID every other test method in this class
    // also searches as -- otherwise the count below includes every other test's own searches too,
    // depending on JUnit's (unspecified) method execution order.
    RequestPostProcessor thisTestsOperator =
        operatorIdentity("op-list-audit-test", OperatorAccessLevel.VIEWER);
    mockMvc
        .perform(
            get("/api/v1/operator/profiles").param("q", "no-match-xyz").with(thisTestsOperator))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            get("/api/v1/operator/profiles").param("q", "no-match-xyz").with(thisTestsOperator))
        .andExpect(status().isOk());

    // Two calls, each its own audit event, both on the SAME operator chain -- proving
    // audit.ensure_operator_chain (V0047) is genuinely idempotent (the second call did not fail
    // trying to create a chain that already exists) and that OperatorProfileListService writes one
    // event per call, not once per operator.
    Long searchEvents =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit.audit_event ae"
                + " JOIN audit.audit_chain ac ON ac.chain_id = ae.chain_id"
                + " WHERE ac.chain_kind = 'operator' AND ac.subject_id = ?"
                + " AND ae.event_type = 'profile_list_searched'",
            Long.class,
            "op-list-audit-test");
    assertEquals(2L, searchEvents);
  }

  @Test
  void paginationTotalIsCorrectAcrossPages() throws Exception {
    String phonePrefix = "+249911002";
    createContactChannelsProfile("0000000443", phonePrefix + "3");
    createContactChannelsProfile("0000000444", phonePrefix + "4");
    createContactChannelsProfile("0000000445", phonePrefix + "5");

    MvcResult page1 =
        mockMvc
            .perform(
                get("/api/v1/operator/profiles")
                    .param("q", phonePrefix)
                    .param("page", "1")
                    .param("pageSize", "2")
                    .with(viewer()))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode page1Body = objectMapper.readTree(page1.getResponse().getContentAsString());
    assertEquals(3, page1Body.get("total").asLong());
    assertEquals(2, page1Body.get("rows").size());

    MvcResult page2 =
        mockMvc
            .perform(
                get("/api/v1/operator/profiles")
                    .param("q", phonePrefix)
                    .param("page", "2")
                    .param("pageSize", "2")
                    .with(viewer()))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode page2Body = objectMapper.readTree(page2.getResponse().getContentAsString());
    assertEquals(
        3,
        page2Body.get("total").asLong(),
        "total is stable across pages, not the page's own row count");

    // Regression for a tiebreaker bug found under review: these three fixtures are all
    // in_progress (submitted_at IS NULL) under the default sort, so without a deterministic
    // tiebreaker column ORDER BY gives no guarantee two separate queries order a tied group the
    // same way -- a profile could appear on both pages, or on neither.
    java.util.Set<String> page1Ids = new java.util.HashSet<>();
    for (JsonNode row : page1Body.get("rows")) {
      page1Ids.add(row.get("profileId").asText());
    }
    for (JsonNode row : page2Body.get("rows")) {
      assertFalse(
          page1Ids.contains(row.get("profileId").asText()),
          "page 2 must not repeat a profile already returned on page 1");
    }
    assertEquals(1, page2Body.get("rows").size(), "the last, partial page");

    // A page requested past the end of the matching set must still report the true total, not 0 —
    // the bug a naive count(*) OVER() window column would produce (see JdbcProfileListRepository).
    MvcResult pastTheEnd =
        mockMvc
            .perform(
                get("/api/v1/operator/profiles")
                    .param("q", phonePrefix)
                    .param("page", "5")
                    .param("pageSize", "2")
                    .with(viewer()))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode pastTheEndBody = objectMapper.readTree(pastTheEnd.getResponse().getContentAsString());
    assertEquals(3, pastTheEndBody.get("total").asLong());
    assertEquals(0, pastTheEndBody.get("rows").size());
  }

  @Test
  void listWithNoOperatorIdentityIs401() throws Exception {
    mockMvc.perform(get("/api/v1/operator/profiles")).andExpect(status().isUnauthorized());
  }

  private String createContactChannelsProfile(String accountNumber, String phoneNumber)
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
    return objectMapper
        .readTree(result.getResponse().getContentAsString())
        .get("profileId")
        .asText();
  }

  private void pushThroughScan(String profileId, String identityNumber) throws Exception {
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

    java.util.Map<String, Object> scanBody = new java.util.LinkedHashMap<>();
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
  }

  private static RequestPostProcessor viewer() {
    return operatorIdentity(OPERATOR_ID, OperatorAccessLevel.VIEWER);
  }

  /**
   * The access level's own role — viewer for VIEWER, operator for OPERATOR. Use {@link
   * #operatorIdentity(String, OperatorAccessLevel, String)} for the admin case, which is the one
   * combination the level cannot imply: since AD-013 an admin arrives at OPERATOR level too.
   */
  static RequestPostProcessor operatorIdentity(String operatorId, OperatorAccessLevel level) {
    return operatorIdentity(
        operatorId, level, level == OperatorAccessLevel.OPERATOR ? "operator" : "viewer");
  }

  static RequestPostProcessor operatorIdentity(
      String operatorId, OperatorAccessLevel level, String actorRole) {
    return request -> {
      request.setAttribute(
          OperatorIdentityArgumentResolver.REQUEST_ATTRIBUTE,
          new OperatorIdentity(operatorId, level, actorRole));
      return request;
    };
  }
}
