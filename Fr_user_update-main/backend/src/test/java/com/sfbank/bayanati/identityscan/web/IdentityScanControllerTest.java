package com.sfbank.bayanati.identityscan.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sfbank.bayanati.identityscan.domain.IdentityScanRejectedException;
import com.sfbank.bayanati.identityscan.domain.ImagesUnavailableForAcceptanceException;
import com.sfbank.bayanati.identityscan.domain.JwsAlreadyAcceptedException;
import com.sfbank.bayanati.identityscan.domain.NoActiveRegistryReviewException;
import com.sfbank.bayanati.identityscan.domain.ProfileNotEditableException;
import com.sfbank.bayanati.identityscan.domain.RegistryReviewPendingException;
import com.sfbank.bayanati.identityscan.domain.ScanBlockReason;
import com.sfbank.bayanati.identityscan.domain.ScanTemporarilyBlockedException;
import com.sfbank.bayanati.identityscan.domain.ScanTypeExhaustedException;
import com.sfbank.bayanati.identityscan.domain.UnknownProfileException;
import com.sfbank.bayanati.identityscan.service.IdentityScanService;
import com.sfbank.bayanati.identityscan.service.ScanDisplayPayload;
import com.sfbank.bayanati.identityscan.service.TokenIssuance;
import com.sfbank.bayanati.uqudo.domain.ArtifactExpiredException;
import com.sfbank.bayanati.uqudo.domain.ImageIntegrityException;
import com.sfbank.bayanati.uqudo.domain.JwsVerificationException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(IdentityScanController.class)
// S4-05: see AccountCheckControllerTest's identical comment. Real unauthenticated access is
// proven by IdentityScanIntegrationTest.
@AutoConfigureMockMvc(addFilters = false)
class IdentityScanControllerTest {

  private static final java.time.Instant TOKEN_USABLE_UNTIL =
      java.time.Instant.parse("2026-08-30T12:30:00Z");

  private static final Instant BLOCKED_UNTIL = Instant.parse("2026-09-05T21:30:00Z");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private IdentityScanService identityScanService;

  @Test
  void tokenValidRequestReturns200() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(identityScanService.issueToken(profileId, "passport"))
        .thenReturn(
            new TokenIssuance("access-1", "sid-1", "nonce-1", "passport", TOKEN_USABLE_UNTIL));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"passport\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("access-1"))
        // BL-114(a). Pins the other end of a contract the mobile decoder hard-fails on.
        .andExpect(jsonPath("$.usableUntil").value(TOKEN_USABLE_UNTIL.toString()))
        .andExpect(jsonPath("$.sessionId").value("sid-1"));
  }

  @Test
  void tokenUnknownProfileMapsTo404() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(identityScanService.issueToken(any(), anyString()))
        .thenThrow(new UnknownProfileException("no profile"));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"passport\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void tokenTerminalProfileMapsTo409() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(identityScanService.issueToken(any(), anyString()))
        .thenThrow(new ProfileNotEditableException("already complete"));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"passport\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("PROFILE_TERMINAL"))
        // The exception message embeds the profile id; the wire must not (AD-002a).
        .andExpect(jsonPath("$.detail").value("this profile can no longer be edited"));
  }

  @Test
  void tokenBlockedMapsTo409() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(identityScanService.issueToken(any(), anyString()))
        .thenThrow(new ScanTemporarilyBlockedException(BLOCKED_UNTIL));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"passport\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("SCAN_BLOCKED"))
        .andExpect(jsonPath("$.blockedUntil").value(BLOCKED_UNTIL.toString()));
  }

  /**
   * BL-118. The cap rides on the UNCHANGED SCAN_BLOCKED code as a second property, and an ordinary
   * block omits it ENTIRELY rather than sending a null.
   *
   * <p>The omission is what makes this safe to ship without the mobile half: an app that does not
   * read the field receives a body byte-identical to the one it receives today, so it keeps
   * rendering S8-14's honest block screen with its deadline. Emitting a new CODE instead would have
   * degraded to {@code unknown} on every app in the field, and Stage 8 routes {@code unknown} to a
   * resync that drops blockedUntil.
   */
  @Test
  void aCapNamesItselfOnTheWireWhileAnOrdinaryBlockSendsNoReasonAtAll() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(identityScanService.issueToken(any(), anyString()))
        .thenThrow(
            new ScanTemporarilyBlockedException(BLOCKED_UNTIL, ScanBlockReason.LIFETIME_CAP));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"passport\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SCAN_BLOCKED"))
        .andExpect(jsonPath("$.blockedUntil").value(BLOCKED_UNTIL.toString()))
        .andExpect(jsonPath("$.blockReason").value("LIFETIME_CAP"));

    reset(identityScanService);
    when(identityScanService.issueToken(any(), anyString()))
        .thenThrow(new ScanTemporarilyBlockedException(BLOCKED_UNTIL));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"passport\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SCAN_BLOCKED"))
        .andExpect(jsonPath("$.blockReason").doesNotExist());
  }

  @Test
  void tokenInvalidDocumentTypeMapsTo400() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(identityScanService.issueToken(any(), anyString()))
        .thenThrow(new IdentityScanRejectedException("bad documentType"));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\"" + profileId + "\",\"documentType\":\"drivers_licence\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void tokenMissingProfileIdReturns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/identity-scan/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentType\":\"passport\"}"))
        .andExpect(status().isBadRequest());
  }

  /**
   * BL-037. Before this the assertion was {@code isBadRequest()} and nothing else, which is exactly
   * why the ambiguity shipped: it passes just as well against a bare 400 that a client-side
   * validation failure also produces.
   */
  @Test
  void scanResultJwsVerificationFailureMapsToScanRejected() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(identityScanService.submitScan(any(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new JwsVerificationException("bad signature"));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/scan-result")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"sessionId\":\"s\",\"nonce\":\"n\",\"documentType\":\"passport\","
                        + "\"jws\":\"a.b.c\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("SCAN_REJECTED"));
  }

  /**
   * The second budget-spending 400, and the one that had no controller test at all before BL-037. A
   * checksum mismatch is counted against the retry budget exactly like a JWS rejection
   * (IdentityScanService, "a mismatch is a hard failure"), so it answers with the same code.
   */
  @Test
  void scanResultImageIntegrityFailureMapsToScanRejected() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(identityScanService.submitScan(any(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new ImageIntegrityException("doc-front-image-id"));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/scan-result")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"sessionId\":\"s\",\"nonce\":\"n\",\"documentType\":\"passport\","
                        + "\"jws\":\"a.b.c\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("SCAN_REJECTED"));
  }

  /**
   * BL-037's actual contract, asserted as the discrimination itself rather than as two separate
   * facts: two 400s from the same endpoint, and only the one that spent an attempt carries a code.
   *
   * <p>The client-side half is asserted as "the body contains no code member" rather than with
   * {@code jsonPath("$.code").doesNotExist()}, because a ResponseStatusException renders an empty
   * body under MockMvc and JsonPath cannot be evaluated against one. Note that this half is green
   * both before and after the fix -- it is a guard against an over-broad implementation that codes
   * every 400, not a proof of the defect. The spending half is what fails against the old code.
   */
  @Test
  void aSpentAttemptCarriesACodeWhileAClientSide400DoesNot() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/identity-scan/scan-result")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sessionId\":\"s\",\"nonce\":\"n\",\"documentType\":\"passport\","
                        + "\"jws\":\"a.b.c\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(not(containsString("code"))));

    UUID profileId = UUID.randomUUID();
    when(identityScanService.submitScan(any(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new JwsVerificationException("bad signature"));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/scan-result")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"sessionId\":\"s\",\"nonce\":\"n\",\"documentType\":\"passport\","
                        + "\"jws\":\"a.b.c\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCAN_REJECTED"));
  }

  @Test
  void scanResultImagesUnavailableMapsTo409() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(identityScanService.submitScan(any(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new ImagesUnavailableForAcceptanceException("images gone"));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/scan-result")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"sessionId\":\"s\",\"nonce\":\"n\",\"documentType\":\"passport\","
                        + "\"jws\":\"a.b.c\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("IMAGES_UNAVAILABLE"));
  }

  @Test
  void wrongNumberReturnsBlockedUntilWhenBudgetExhausted() throws Exception {
    UUID profileId = UUID.randomUUID();
    java.time.Instant until = java.time.Instant.parse("2026-08-31T12:00:00Z");
    when(identityScanService.reportWrongNumber(profileId)).thenReturn(Optional.of(until));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/registry-review/wrong-number")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.blockedUntil").value(until.toString()));
  }

  @Test
  void acceptWithNoActiveCycleMapsToStateConflict() throws Exception {
    UUID profileId = UUID.randomUUID();
    org.mockito.Mockito.doThrow(NoActiveRegistryReviewException.noActiveCycle(profileId))
        .when(identityScanService)
        .acceptRegistryReview(profileId);

    mockMvc
        .perform(
            post("/api/v1/identity-scan/registry-review/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
  }

  /**
   * The other half of the BL-033 split: same exception class and same 409, different code, because
   * the customer sees a pause screen here and a state re-sync there.
   */
  @Test
  void acceptWhileRegistryNotReadyMapsToRegistryNotReady() throws Exception {
    UUID profileId = UUID.randomUUID();
    org.mockito.Mockito.doThrow(NoActiveRegistryReviewException.registryNotReady(profileId))
        .when(identityScanService)
        .acceptRegistryReview(profileId);

    mockMvc
        .perform(
            post("/api/v1/identity-scan/registry-review/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("REGISTRY_NOT_READY"));
  }

  @Test
  void tokenTypeExhaustedMapsToScanTypeExhausted() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(identityScanService.issueToken(any(), anyString()))
        .thenThrow(new ScanTypeExhaustedException("passport"));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"passport\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("SCAN_TYPE_EXHAUSTED"))
        // BL-033 decision: no otherDocumentTypeAvailable field. The attempt math makes it
        // invariantly true, so it would be dead signal in the wire contract.
        .andExpect(jsonPath("$.otherDocumentTypeAvailable").doesNotExist());
  }

  @Test
  void tokenRegistryReviewPendingMapsToRegistryPending() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(identityScanService.issueToken(any(), anyString()))
        .thenThrow(new RegistryReviewPendingException("awaiting registry"));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"passport\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("REGISTRY_PENDING"));
  }

  /** ARTIFACT_EXPIRED is the code customer.md names by name; its exception lives in uqudo. */
  @Test
  void scanResultArtifactExpiredMapsToArtifactExpired() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(identityScanService.submitScan(any(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new ArtifactExpiredException("artifact gone"));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/scan-result")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"sessionId\":\"s\",\"nonce\":\"n\",\"documentType\":\"passport\","
                        + "\"jws\":\"a.b.c\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("ARTIFACT_EXPIRED"));
  }

  @Test
  void scanResultAlreadyAcceptedMapsToStateConflict() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(identityScanService.submitScan(any(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new JwsAlreadyAcceptedException("replay"));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/scan-result")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\":\""
                        + profileId
                        + "\",\"sessionId\":\"s\",\"nonce\":\"n\",\"documentType\":\"passport\","
                        + "\"jws\":\"a.b.c\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
  }

  /**
   * blockedUntil is nullable on the exception. An unguarded toString() in the handler would turn
   * this 409 into a 500, so the property is omitted rather than emitted as null.
   */
  @Test
  void blockedWithoutTimestampStillReturns409WithoutTheProperty() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(identityScanService.issueToken(any(), anyString()))
        .thenThrow(new ScanTemporarilyBlockedException(null));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"documentType\":\"passport\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SCAN_BLOCKED"))
        .andExpect(jsonPath("$.blockedUntil").doesNotExist());
  }

  // ---- S5-11: the read-only Stage 9 resume endpoint ----

  @Test
  void currentReviewReturnsTheStoredPayload() throws Exception {
    UUID profileId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    when(identityScanService.currentReviewPayload(profileId))
        .thenReturn(
            new ScanDisplayPayload(
                profileId,
                cycleId,
                "passport",
                "NID-9",
                true,
                "محمد",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "First",
                "Last",
                "m",
                LocalDate.of(1990, 1, 2),
                "Sample address",
                List.of("doc_front", "portrait_registry")));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/registry-review/current")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cycleId").value(cycleId.toString()))
        .andExpect(jsonPath("$.nationalNumber").value("NID-9"))
        .andExpect(jsonPath("$.registryReady").value(true))
        .andExpect(jsonPath("$.dateOfBirth").value("1990-01-02"))
        .andExpect(jsonPath("$.availableImageKinds[1]").value("portrait_registry"));
  }

  /** A1: a paused cycle is a 200 with registryReady=false, not a 409 (S5-11). */
  @Test
  void currentReviewReportsAPausedCycleAsA200NotAConflict() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(identityScanService.currentReviewPayload(profileId))
        .thenReturn(
            new ScanDisplayPayload(
                profileId,
                UUID.randomUUID(),
                "national_id",
                "NID-9",
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of("doc_front")));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/registry-review/current")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registryReady").value(false))
        .andExpect(jsonPath("$.nationalNumber").value("NID-9"))
        .andExpect(jsonPath("$.nameArGiven").doesNotExist())
        .andExpect(jsonPath("$.dateOfBirth").doesNotExist());
  }

  @Test
  void currentReviewWithNoActiveCycleMapsToStateConflict() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(identityScanService.currentReviewPayload(profileId))
        .thenThrow(NoActiveRegistryReviewException.noActiveCycle(profileId));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/registry-review/current")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
  }

  @Test
  void currentReviewOnATerminalProfileMapsToProfileTerminal() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(identityScanService.currentReviewPayload(profileId))
        .thenThrow(new ProfileNotEditableException("already complete"));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/registry-review/current")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("PROFILE_TERMINAL"));
  }

  @Test
  void currentReviewUnknownProfileMapsTo404() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(identityScanService.currentReviewPayload(profileId))
        .thenThrow(new UnknownProfileException("no profile"));

    mockMvc
        .perform(
            post("/api/v1/identity-scan/registry-review/current")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\"}"))
        .andExpect(status().isNotFound());
  }
}
