package com.sfbank.bayanati.submission.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.submission.domain.JourneyStage;
import com.sfbank.bayanati.submission.domain.LivenessNotCompleteException;
import com.sfbank.bayanati.submission.domain.NotInFinalStagesException;
import com.sfbank.bayanati.submission.domain.ProfileNotEligibleException;
import com.sfbank.bayanati.submission.domain.SignatureMissingException;
import com.sfbank.bayanati.submission.domain.UnknownProfileException;
import com.sfbank.bayanati.submission.service.JourneyPointer;
import com.sfbank.bayanati.submission.service.SubmissionService;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** S5-13's Stage 12 error contract and the Stage 10-12 resume read's wire shape. */
@WebMvcTest(SubmissionController.class)
@AutoConfigureMockMvc(addFilters = false)
class SubmissionControllerTest {

  private static final Instant BLOCKED_UNTIL = Instant.parse("2026-09-06T09:15:00Z");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SubmissionService submissionService;

  private org.springframework.test.web.servlet.ResultActions postTo(String path, UUID profileId)
      throws Exception {
    return mockMvc.perform(
        post(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"profileId\":\"" + profileId + "\"}"));
  }

  // ---- Stage 12's conflicts --------------------------------------------------------------------

  @Test
  void unknownProfileMapsTo404() throws Exception {
    when(submissionService.submit(any())).thenThrow(new UnknownProfileException("no profile"));

    postTo("/api/v1/submission", UUID.randomUUID()).andExpect(status().isNotFound());
  }

  @Test
  void terminalProfileCarriesProfileTerminal() throws Exception {
    when(submissionService.submit(any()))
        .thenThrow(
            ProfileNotEligibleException.terminalStatus(
                UUID.randomUUID(), "terminated_registry_mismatch"));

    postTo("/api/v1/submission", UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("PROFILE_TERMINAL"))
        // The exception message embeds the profile id; the wire must not (AD-002a).
        .andExpect(jsonPath("$.detail").value("this profile can no longer be edited"));
  }

  /**
   * The discriminator S5-13 added, and the reason it matters: the status the profile raced into can
   * be {@code blocked_liveness}, which is temporary. Answering {@code PROFILE_TERMINAL} there would
   * tell a customer their journey was over when it was not.
   */
  @Test
  void lostRaceCarriesStateConflictNotProfileTerminal() throws Exception {
    when(submissionService.submit(any()))
        .thenThrow(
            ProfileNotEligibleException.becameIneligible(UUID.randomUUID(), "blocked_liveness"));

    postTo("/api/v1/submission", UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
  }

  @Test
  void livenessNotPassedCarriesLivenessRequired() throws Exception {
    when(submissionService.submit(any()))
        .thenThrow(new LivenessNotCompleteException("profile <id> has not passed stage 10"));

    postTo("/api/v1/submission", UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LIVENESS_REQUIRED"));
  }

  @Test
  void missingSignatureCarriesSignatureRequired() throws Exception {
    when(submissionService.submit(any()))
        .thenThrow(new SignatureMissingException("profile <id> has no signature"));

    postTo("/api/v1/submission", UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SIGNATURE_REQUIRED"))
        .andExpect(jsonPath("$.detail").value("a signature must be captured before submission"));
  }

  // ---- The Stage 10-12 resume read -------------------------------------------------------------

  /**
   * The answer the read exists for. A customer whose app died between submitting and rendering the
   * confirmation screen recovers their reference number AND the channels carrying the decision here
   * — the latter being exactly what an idempotent re-submit cannot give them, since {@code
   * SubmissionResponse.verifiedChannels} correctly reports only what that call enqueued.
   */
  @Test
  void readAnswersSubmittedWithReferenceNumberAndChannels() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(submissionService.currentJourneyPointer(profileId))
        .thenReturn(
            new JourneyPointer(
                profileId.toString(),
                JourneyStage.SUBMITTED,
                null,
                "FRU-000000042",
                Set.of(MessageChannel.SMS, MessageChannel.EMAIL)));

    postTo("/api/v1/submission/current", profileId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stage").value("SUBMITTED"))
        .andExpect(jsonPath("$.referenceNumber").value("FRU-000000042"))
        .andExpect(jsonPath("$.verifiedChannels[0]").value("email"))
        .andExpect(jsonPath("$.verifiedChannels[1]").value("sms"))
        .andExpect(jsonPath("$.blockedUntil").doesNotExist());
  }

  @Test
  void readAnswersLivenessBlockedWithTheDeadline() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(submissionService.currentJourneyPointer(profileId))
        .thenReturn(
            new JourneyPointer(
                profileId.toString(),
                JourneyStage.LIVENESS_BLOCKED,
                BLOCKED_UNTIL,
                null,
                Set.of()));

    postTo("/api/v1/submission/current", profileId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stage").value("LIVENESS_BLOCKED"))
        .andExpect(jsonPath("$.blockedUntil").value(BLOCKED_UNTIL.toString()))
        .andExpect(jsonPath("$.referenceNumber").doesNotExist());
  }

  @Test
  void readAnswersTheMidJourneyStages() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(submissionService.currentJourneyPointer(profileId))
        .thenReturn(
            new JourneyPointer(profileId.toString(), JourneyStage.SIGNATURE, null, null, Set.of()));

    postTo("/api/v1/submission/current", profileId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stage").value("SIGNATURE"))
        .andExpect(jsonPath("$.verifiedChannels").isEmpty());
  }

  @Test
  void readOnAnUnknownProfileMapsTo404() throws Exception {
    when(submissionService.currentJourneyPointer(any()))
        .thenThrow(new UnknownProfileException("no profile"));

    postTo("/api/v1/submission/current", UUID.randomUUID()).andExpect(status().isNotFound());
  }

  @Test
  void readOutsideStagesTenToTwelveCarriesStateConflict() throws Exception {
    when(submissionService.currentJourneyPointer(any()))
        .thenThrow(new NotInFinalStagesException(UUID.randomUUID(), "blocked_scan"));

    postTo("/api/v1/submission/current", UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
  }
}
