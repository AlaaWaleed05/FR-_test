package com.sfbank.bayanati.liveness.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.liveness.domain.AuditTrailImageUnavailableException;
import com.sfbank.bayanati.liveness.domain.FaceJwsAlreadyAcceptedException;
import com.sfbank.bayanati.liveness.domain.FaceMatchAlreadyPassedException;
import com.sfbank.bayanati.liveness.domain.InvalidFaceSessionException;
import com.sfbank.bayanati.liveness.domain.LivenessBlockReason;
import com.sfbank.bayanati.liveness.domain.LivenessTemporarilyBlockedException;
import com.sfbank.bayanati.liveness.domain.NoAcceptedIdentityCycleException;
import com.sfbank.bayanati.liveness.domain.ProfileNotEditableException;
import com.sfbank.bayanati.liveness.domain.RegistryReviewPendingException;
import com.sfbank.bayanati.liveness.domain.UnknownProfileException;
import com.sfbank.bayanati.liveness.service.FaceResultOutcome;
import com.sfbank.bayanati.liveness.service.FaceSessionIssuance;
import com.sfbank.bayanati.liveness.service.LivenessService;
import com.sfbank.bayanati.uqudo.domain.ArtifactExpiredException;
import com.sfbank.bayanati.uqudo.domain.ImageIntegrityException;
import com.sfbank.bayanati.uqudo.domain.JwsVerificationException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * S5-13's Stage 10 error contract.
 *
 * <p><strong>Every assertion here names the exact {@code code} on the wire, never the status
 * alone.</strong> That is the BL-033/BL-037 lesson made concrete: before this slice all eight
 * conflicts were one indistinguishable 409 and both budget-spending rejections were one
 * indistinguishable 400, so a status-only test passed against the collapsed behaviour and proved
 * nothing. Each of these is a direct wrong-value assertion — a body carrying no {@code code} at all
 * cannot satisfy {@code jsonPath("$.code").value(...)} — so none needs a revert-restore to show it
 * guards the defect.
 */
@WebMvcTest(LivenessController.class)
// S4-05: see AccountCheckControllerTest's identical comment. Real unauthenticated access is proven
// by LivenessIntegrationTest.
@AutoConfigureMockMvc(addFilters = false)
class LivenessControllerTest {

  private static final Instant BLOCKED_UNTIL = Instant.parse("2026-09-06T09:15:00Z");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private LivenessService livenessService;

  private org.springframework.test.web.servlet.ResultActions postResult(UUID profileId)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/liveness/result")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"profileId\":\""
                    + profileId
                    + "\",\"faceSessionId\":\"fs-1\",\"jws\":\"a.b.c\"}"));
  }

  @Test
  void resultValidRequestReturns200() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(livenessService.submitFaceResult(profileId, "fs-1", "a.b.c"))
        .thenReturn(new FaceResultOutcome(true, 5, null));

    postResult(profileId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passed").value(true))
        .andExpect(jsonPath("$.matchLevel").value(5));
  }

  @Test
  void unknownProfileMapsTo404() throws Exception {
    when(livenessService.submitFaceResult(any(), anyString(), anyString()))
        .thenThrow(new UnknownProfileException("no profile"));

    postResult(UUID.randomUUID()).andExpect(status().isNotFound());
  }

  @Test
  void terminalProfileCarriesProfileTerminal() throws Exception {
    when(livenessService.submitFaceResult(any(), anyString(), anyString()))
        .thenThrow(new ProfileNotEditableException("profile <id> is already complete"));

    postResult(UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("PROFILE_TERMINAL"))
        // The exception message embeds the profile id; the wire must not (AD-002a).
        .andExpect(jsonPath("$.detail").value("this profile can no longer be edited"));
  }

  @Test
  void blockedCarriesLivenessBlockedAndTheDeadline() throws Exception {
    when(livenessService.submitFaceResult(any(), anyString(), anyString()))
        .thenThrow(new LivenessTemporarilyBlockedException(BLOCKED_UNTIL));

    postResult(UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("LIVENESS_BLOCKED"))
        // The whole point of the field: the controller used to discard it via getMessage().
        .andExpect(jsonPath("$.blockedUntil").value(BLOCKED_UNTIL.toString()));
  }

  /** The null-guard. An unguarded {@code toString()} would turn this 409 into a 500. */
  @Test
  void blockedWithNoDeadlineOmitsTheFieldRatherThanFailing() throws Exception {
    when(livenessService.submitFaceResult(any(), anyString(), anyString()))
        .thenThrow(new LivenessTemporarilyBlockedException(null));

    postResult(UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LIVENESS_BLOCKED"))
        .andExpect(jsonPath("$.blockedUntil").doesNotExist());
  }

  /**
   * BL-118. With the mobile half cut from V1, THE WIRE IS THE ENTIRE DELIVERABLE of that item, so
   * both halves of it are pinned here: the cap names itself, and an ordinary block omits the member
   * rather than sending a null.
   *
   * <p>The omission is what makes the backend half safe to ship alone. An app that does not read
   * {@code blockReason} must receive a body identical to the one it receives today — a null-valued
   * member would still change the payload, and a new error CODE would have been worse still
   * (unrecognised codes degrade to a generic path that discards {@code blockedUntil}).
   *
   * <p>Mirrors {@code IdentityScanControllerTest}'s equivalent. Found missing by
   * {@code @agent-reviewer} at S8-15's second pass — the scan side had it, this side did not.
   */
  @Test
  void aCapNamesItselfOnTheWireWhileAnOrdinaryBlockSendsNoReasonAtAll() throws Exception {
    when(livenessService.submitFaceResult(any(), anyString(), anyString()))
        .thenThrow(
            new LivenessTemporarilyBlockedException(
                BLOCKED_UNTIL, LivenessBlockReason.LIFETIME_CAP));

    postResult(UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LIVENESS_BLOCKED"))
        .andExpect(jsonPath("$.blockedUntil").value(BLOCKED_UNTIL.toString()))
        .andExpect(jsonPath("$.blockReason").value("LIFETIME_CAP"));

    reset(livenessService);
    when(livenessService.submitFaceResult(any(), anyString(), anyString()))
        .thenThrow(new LivenessTemporarilyBlockedException(BLOCKED_UNTIL));

    postResult(UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LIVENESS_BLOCKED"))
        .andExpect(jsonPath("$.blockReason").doesNotExist());
  }

  /**
   * BL-114(a). Nothing on either tier connected the two ends of this contract: the mobile decoder
   * hard-fails on a missing {@code usableUntil} and the mobile tests supply their own maps, so a
   * backend that stopped sending it would have been caught by no test anywhere
   * ({@code @agent-reviewer}, S8-15 second pass).
   */
  @Test
  void theTokenResponseCarriesUsableUntil() throws Exception {
    UUID profileId = UUID.randomUUID();
    Instant usableUntil = Instant.parse("2026-09-06T09:10:00Z");
    when(livenessService.issueFaceSessionToken(any()))
        .thenReturn(new FaceSessionIssuance("access-1", "fs-1", usableUntil));

    mockMvc
        .perform(
            post("/api/v1/liveness/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.faceSessionId").value("fs-1"))
        .andExpect(jsonPath("$.usableUntil").value(usableUntil.toString()));
  }

  @Test
  void alreadyPassedCarriesLivenessAlreadyPassed() throws Exception {
    when(livenessService.submitFaceResult(any(), anyString(), anyString()))
        .thenThrow(new FaceMatchAlreadyPassedException("profile <id> already passed"));

    postResult(UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LIVENESS_ALREADY_PASSED"))
        .andExpect(jsonPath("$.detail").value("face-match has already passed for this profile"));
  }

  /**
   * The discriminator S5-13 added. Both halves used to be the same exception with the same wire
   * shape, so the app could not tell "re-sync" from "go and rescan" — two different screens.
   */
  @Test
  void purgedReferenceImageCarriesRescanRequired() throws Exception {
    when(livenessService.submitFaceResult(any(), anyString(), anyString()))
        .thenThrow(NoAcceptedIdentityCycleException.referenceImageUnavailable(UUID.randomUUID()));

    postResult(UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RESCAN_REQUIRED"))
        .andExpect(jsonPath("$.detail").value("a fresh document scan is required before liveness"));
  }

  @Test
  void noAcceptedCycleCarriesStateConflict() throws Exception {
    when(livenessService.submitFaceResult(any(), anyString(), anyString()))
        .thenThrow(NoAcceptedIdentityCycleException.noAcceptedCycle(UUID.randomUUID()));

    postResult(UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
  }

  @Test
  void replayedFaceJwsCarriesStateConflict() throws Exception {
    when(livenessService.submitFaceResult(any(), anyString(), anyString()))
        .thenThrow(new FaceJwsAlreadyAcceptedException("already accepted"));

    postResult(UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
  }

  @Test
  void registryRetryInFlightCarriesRegistryPending() throws Exception {
    when(livenessService.submitFaceResult(any(), anyString(), anyString()))
        .thenThrow(new RegistryReviewPendingException("retry in flight"));

    postResult(UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REGISTRY_PENDING"));
  }

  @Test
  void expiredCaptureCarriesArtifactExpired() throws Exception {
    when(livenessService.submitFaceResult(any(), anyString(), anyString()))
        .thenThrow(new ArtifactExpiredException("images gone"));

    postResult(UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ARTIFACT_EXPIRED"));
  }

  @Test
  void missingAuditTrailImageCarriesItsOwnCode() throws Exception {
    when(livenessService.submitFaceResult(any(), anyString(), anyString()))
        .thenThrow(new AuditTrailImageUnavailableException("audit trail image gone"));

    postResult(UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("AUDIT_TRAIL_UNAVAILABLE"));
  }

  // ---- The 400 side: a `code` means an attempt was spent ----------------------------------------

  @Test
  void rejectedJwsCarriesLivenessRejected() throws Exception {
    when(livenessService.submitFaceResult(any(), anyString(), anyString()))
        .thenThrow(new JwsVerificationException("bad signature"));

    postResult(UUID.randomUUID())
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("LIVENESS_REJECTED"))
        .andExpect(jsonPath("$.detail").value("this liveness check could not be verified"));
  }

  @Test
  void imageIntegrityFailureCarriesTheSameCodeBecauseItSpendsAnAttemptToo() throws Exception {
    when(livenessService.submitFaceResult(any(), anyString(), anyString()))
        .thenThrow(new ImageIntegrityException("checksum mismatch"));

    postResult(UUID.randomUUID())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("LIVENESS_REJECTED"));
  }

  /**
   * The other half of BL-037's rule, and the reason it is worth asserting: a wrong face-session id
   * spends NO attempt, so its 400 must stay bare. If it ever grew a {@code code}, a client counting
   * codes as attempts would under-report how many the customer has left.
   */
  @Test
  void wrongFaceSessionStaysABare400WithNoCode() throws Exception {
    when(livenessService.submitFaceResult(any(), anyString(), anyString()))
        .thenThrow(new InvalidFaceSessionException("session mismatch"));

    postResult(UUID.randomUUID())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").doesNotExist());
  }

  @Test
  void malformedProfileIdStaysABare400WithNoCode() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/liveness/result")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"not-a-uuid\",\"faceSessionId\":\"fs-1\",\"jws\":\"x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").doesNotExist());
  }
}
