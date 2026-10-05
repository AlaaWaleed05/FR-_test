package com.sfbank.bayanati.signature.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.signature.domain.LivenessNotCompleteException;
import com.sfbank.bayanati.signature.domain.ProfileNotEditableException;
import com.sfbank.bayanati.signature.domain.SignatureRejectedException;
import com.sfbank.bayanati.signature.domain.UnknownProfileException;
import com.sfbank.bayanati.signature.service.SignatureService;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** S5-13's Stage 11 error contract. Each assertion names the code, never the status alone. */
@WebMvcTest(SignatureController.class)
@AutoConfigureMockMvc(addFilters = false)
class SignatureControllerTest {

  private static final String CONTENT_BASE64 =
      Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SignatureService signatureService;

  private org.springframework.test.web.servlet.ResultActions postSignature(UUID profileId)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/signature")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"profileId\":\""
                    + profileId
                    + "\",\"captureMethod\":\"drawn\",\"contentType\":\"image/png\","
                    + "\"contentBase64\":\""
                    + CONTENT_BASE64
                    + "\"}"));
  }

  @Test
  void validRequestReturns200() throws Exception {
    UUID profileId = UUID.randomUUID();

    postSignature(profileId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.profileId").value(profileId.toString()));
  }

  @Test
  void unknownProfileMapsTo404() throws Exception {
    doThrow(new UnknownProfileException("no profile"))
        .when(signatureService)
        .submitSignature(any(), anyString(), anyString(), any());

    postSignature(UUID.randomUUID()).andExpect(status().isNotFound());
  }

  @Test
  void terminalProfileCarriesProfileTerminal() throws Exception {
    doThrow(new ProfileNotEditableException("profile <id> is already complete"))
        .when(signatureService)
        .submitSignature(any(), anyString(), anyString(), any());

    postSignature(UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("PROFILE_TERMINAL"))
        // The exception message embeds the profile id; the wire must not (AD-002a).
        .andExpect(jsonPath("$.detail").value("this profile can no longer be edited"));
  }

  @Test
  void livenessNotPassedCarriesLivenessRequired() throws Exception {
    doThrow(new LivenessNotCompleteException("profile <id> has not passed stage 10"))
        .when(signatureService)
        .submitSignature(any(), anyString(), anyString(), any());

    postSignature(UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LIVENESS_REQUIRED"))
        .andExpect(jsonPath("$.detail").value("the liveness check must pass before a signature"));
  }

  @Test
  void refusedSignatureCarriesSignatureRejected() throws Exception {
    doThrow(new SignatureRejectedException("signature content exceeds 5242880 bytes"))
        .when(signatureService)
        .submitSignature(any(), anyString(), anyString(), any());

    postSignature(UUID.randomUUID())
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("SIGNATURE_REJECTED"))
        .andExpect(jsonPath("$.detail").value("this signature could not be accepted"));
  }

  @Test
  void malformedProfileIdStaysABare400WithNoCode() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/signature")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\"not-a-uuid\",\"captureMethod\":\"drawn\","
                        + "\"contentType\":\"image/png\",\"contentBase64\":\""
                        + CONTENT_BASE64
                        + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").doesNotExist());
  }
}
