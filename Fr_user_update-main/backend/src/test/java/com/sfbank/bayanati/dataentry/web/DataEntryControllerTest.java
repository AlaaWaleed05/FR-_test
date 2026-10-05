package com.sfbank.bayanati.dataentry.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.dataentry.domain.DataEntryRejectedException;
import com.sfbank.bayanati.dataentry.domain.ProfileNotEditableException;
import com.sfbank.bayanati.dataentry.domain.UnknownProfileException;
import com.sfbank.bayanati.dataentry.service.DataEntryService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DataEntryController.class)
// S4-05: see AccountCheckControllerTest's identical comment. Real unauthenticated access is
// proven by DataEntryIntegrationTest.
@AutoConfigureMockMvc(addFilters = false)
class DataEntryControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private DataEntryService dataEntryService;

  private static String stage3Body(UUID profileId) {
    return "{\"profileId\":\""
        + profileId
        + "\",\"sexDeclared\":\"f\",\"ethnicity\":\"Nubian\","
        + "\"countryOfResidenceCode\":\"SD\",\"maritalStatus\":\"single\","
        + "\"educationLevel\":6,\"birthCountryCode\":\"SD\",\"birthStateCode\":\"11\","
        + "\"birthCityText\":\"Khartoum\",\"countryListVersion\":5,"
        + "\"adminDivisionListVersion\":7}";
  }

  @Test
  void stage3ValidRequestReturns200() throws Exception {
    UUID profileId = UUID.randomUUID();
    // The two new pin arguments are asserted with eq(), not any() -- a swapped-argument bug
    // (countryListVersion/adminDivisionListVersion passed in the wrong order) must fail this test.
    when(dataEntryService.submitStage3(
            eq(profileId),
            eq("f"),
            eq("Nubian"),
            eq("SD"),
            eq("single"),
            any(),
            any(),
            any(),
            eq(6),
            eq("SD"),
            eq("11"),
            any(),
            eq("Khartoum"),
            eq(5),
            eq(7)))
        .thenReturn("stage3");

    mockMvc
        .perform(
            post("/api/v1/data-entry/stage3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(stage3Body(profileId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.profileId").value(profileId.toString()))
        .andExpect(jsonPath("$.stage").value("stage3"));
  }

  @Test
  void stage3MissingProfileIdReturns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/data-entry/stage3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sexDeclared\":\"f\",\"ethnicity\":\"Nubian\","
                        + "\"countryOfResidenceCode\":\"SD\",\"maritalStatus\":\"single\","
                        + "\"educationLevel\":6,\"birthCountryCode\":\"SD\",\"birthStateCode\":\"11\","
                        + "\"birthCityText\":\"Khartoum\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void stage3MissingEducationLevelReturns400() throws Exception {
    UUID profileId = UUID.randomUUID();
    mockMvc
        .perform(
            post("/api/v1/data-entry/stage3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"sexDeclared\":\"f\",\"ethnicity\":\"Nubian\","
                        + "\"countryOfResidenceCode\":\"SD\",\"maritalStatus\":\"single\","
                        + "\"birthCountryCode\":\"SD\",\"birthStateCode\":\"11\","
                        + "\"birthCityText\":\"Khartoum\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownProfileMapsTo404() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(dataEntryService.submitStage3(
            any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any(), any(), any(),
            any(), any(), any()))
        .thenThrow(new UnknownProfileException("no profile"));

    mockMvc
        .perform(
            post("/api/v1/data-entry/stage3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(stage3Body(profileId)))
        .andExpect(status().isNotFound());
  }

  @Test
  void terminalProfileMapsTo409() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(dataEntryService.submitStage3(
            any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any(), any(), any(),
            any(), any(), any()))
        .thenThrow(new ProfileNotEditableException("already complete"));

    mockMvc
        .perform(
            post("/api/v1/data-entry/stage3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(stage3Body(profileId)))
        .andExpect(status().isConflict());
  }

  @Test
  void rejectedBusinessRuleMapsTo400() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(dataEntryService.submitStage3(
            any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any(), any(), any(),
            any(), any(), any()))
        .thenThrow(new DataEntryRejectedException("unknown country code"));

    mockMvc
        .perform(
            post("/api/v1/data-entry/stage3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(stage3Body(profileId)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void stage4NonDigitMonthlyExpensesReturns400() throws Exception {
    UUID profileId = UUID.randomUUID();
    mockMvc
        .perform(
            post("/api/v1/data-entry/stage4")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"occupationCode\":\"86\","
                        + "\"incomeSources\":[{\"code\":\"RATIB\",\"primary\":true}],"
                        + "\"monthlyExpensesSdg\":\"1234.56\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void stage4ArabicIndicDigitMonthlyExpensesReturns400() throws Exception {
    UUID profileId = UUID.randomUUID();
    mockMvc
        .perform(
            post("/api/v1/data-entry/stage4")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"occupationCode\":\"86\","
                        + "\"incomeSources\":[{\"code\":\"RATIB\",\"primary\":true}],"
                        + "\"monthlyExpensesSdg\":\"١٢٣٤\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void stage4ValidRequestReturns200() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(dataEntryService.submitStage4(
            eq(profileId), eq("86"), anyList(), eq(1000L), eq(9), eq(11)))
        .thenReturn("stage4");

    mockMvc
        .perform(
            post("/api/v1/data-entry/stage4")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"occupationCode\":\"86\","
                        + "\"incomeSources\":[{\"code\":\"RATIB\",\"primary\":true}],"
                        + "\"monthlyExpensesSdg\":\"1000\",\"occupationListVersion\":9,"
                        + "\"incomeSourceListVersion\":11}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stage").value("stage4"));
  }

  @Test
  void stage5ValidRequestReturns200() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(dataEntryService.submitStage5(
            eq(profileId),
            eq("EG"),
            any(),
            anyString(),
            any(),
            anyString(),
            eq("Cairo"),
            eq("Area"),
            eq("Street"),
            eq("Block"),
            eq("12"),
            eq(3),
            eq(4)))
        .thenReturn("stage5");

    mockMvc
        .perform(
            post("/api/v1/data-entry/stage5")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"countryCode\":\"EG\","
                        + "\"stateText\":\"Cairo Governorate\",\"localityText\":\"Nasr City\","
                        + "\"city\":\"Cairo\",\"area\":\"Area\",\"street\":\"Street\","
                        + "\"block\":\"Block\",\"houseNumber\":\"12\",\"countryListVersion\":3,"
                        + "\"adminDivisionListVersion\":4}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stage").value("stage5"));
  }

  /**
   * Note the request body below carries no {@code salaryCertificateAttached} — this is the
   * older-client shape, and the {@code isNull()} matcher is what pins BL-122's boxed-Boolean
   * decision. If the field were ever made a primitive {@code boolean}, it would arrive here as
   * {@code false} and silently assert "the customer declined" about a client that never said
   * anything, which is the exact misclassification BL-122 exists to remove. This test fails if that
   * happens.
   */
  @Test
  void stage6ValidRequestReturns200() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(dataEntryService.submitStage6(
            eq(profileId),
            eq("Acme"),
            eq("EG"),
            any(),
            anyString(),
            any(),
            anyString(),
            eq("Cairo"),
            eq("Area"),
            eq("Street"),
            eq("Block"),
            eq(1),
            eq(2),
            isNull()))
        .thenReturn("stage6");

    mockMvc
        .perform(
            post("/api/v1/data-entry/stage6")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"employer\":\"Acme\",\"countryCode\":\"EG\","
                        + "\"stateText\":\"Cairo Governorate\",\"localityText\":\"Nasr City\","
                        + "\"city\":\"Cairo\",\"area\":\"Area\",\"street\":\"Street\","
                        + "\"block\":\"Block\",\"countryListVersion\":1,"
                        + "\"adminDivisionListVersion\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stage").value("stage6"));
  }

  /** BL-122: a client that does send the claim has it reach the service as a real {@code true}. */
  @Test
  void stage6CarriesTheSalaryCertificateClaim() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(dataEntryService.submitStage6(
            eq(profileId),
            eq("Acme"),
            eq("EG"),
            any(),
            anyString(),
            any(),
            anyString(),
            eq("Cairo"),
            eq("Area"),
            eq("Street"),
            eq("Block"),
            eq(1),
            eq(2),
            eq(Boolean.TRUE)))
        .thenReturn("stage6");

    mockMvc
        .perform(
            post("/api/v1/data-entry/stage6")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"employer\":\"Acme\",\"countryCode\":\"EG\","
                        + "\"stateText\":\"Cairo Governorate\",\"localityText\":\"Nasr City\","
                        + "\"city\":\"Cairo\",\"area\":\"Area\",\"street\":\"Street\","
                        + "\"block\":\"Block\",\"countryListVersion\":1,"
                        + "\"adminDivisionListVersion\":2,"
                        + "\"salaryCertificateAttached\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stage").value("stage6"));
  }

  @Test
  void stage7ValidRequestReturns200() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(dataEntryService.submitStage7(eq(profileId), eq("passport"))).thenReturn("stage7");

    mockMvc
        .perform(
            post("/api/v1/data-entry/stage7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"identityType\":\"passport\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stage").value("stage7"));
  }

  @Test
  void stage7MissingIdentityTypeReturns400() throws Exception {
    UUID profileId = UUID.randomUUID();
    mockMvc
        .perform(
            post("/api/v1/data-entry/stage7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isBadRequest());
  }
}
