package com.sfbank.bayanati.identityscan.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.civilregistry.domain.CivilRegistryClient;
import com.sfbank.bayanati.civilregistry.domain.RegistryExchange;
import com.sfbank.bayanati.civilregistry.domain.RegistryLookup;
import com.sfbank.bayanati.civilregistry.domain.RegistryLookupResult;
import com.sfbank.bayanati.civilregistry.domain.RegistryUnreachableException;
import com.sfbank.bayanati.identityscan.domain.AcceptedScanSnapshot;
import com.sfbank.bayanati.identityscan.domain.ActiveRegistryContext;
import com.sfbank.bayanati.identityscan.domain.DocumentTypes;
import com.sfbank.bayanati.identityscan.domain.IdentityScanRepository;
import com.sfbank.bayanati.identityscan.domain.ImagesUnavailableForAcceptanceException;
import com.sfbank.bayanati.identityscan.domain.InvalidScanSessionException;
import com.sfbank.bayanati.identityscan.domain.JwsAlreadyAcceptedException;
import com.sfbank.bayanati.identityscan.domain.NoActiveRegistryReviewException;
import com.sfbank.bayanati.identityscan.domain.ProfileNotEditableException;
import com.sfbank.bayanati.identityscan.domain.RegistryReviewPendingException;
import com.sfbank.bayanati.identityscan.domain.ScanAttemptBudget;
import com.sfbank.bayanati.identityscan.domain.ScanBlockReason;
import com.sfbank.bayanati.identityscan.domain.ScanState;
import com.sfbank.bayanati.identityscan.domain.ScanTemporarilyBlockedException;
import com.sfbank.bayanati.identityscan.domain.ScanTypeExhaustedException;
import com.sfbank.bayanati.identityscan.domain.UnknownProfileException;
import com.sfbank.bayanati.profile.domain.ProfileRepository;
import com.sfbank.bayanati.uqudo.domain.ArtifactExpiredException;
import com.sfbank.bayanati.uqudo.domain.ImageUnavailableException;
import com.sfbank.bayanati.uqudo.domain.IssuedAccessToken;
import com.sfbank.bayanati.uqudo.domain.JwsVerificationException;
import com.sfbank.bayanati.uqudo.domain.ParsedEnrolmentResult;
import com.sfbank.bayanati.uqudo.domain.ParsedImage;
import com.sfbank.bayanati.uqudo.domain.UqudoClient;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * {@code IdentityScanService}'s orchestration, every collaborator mocked -- no Spring context, no
 * database (CLAUDE.md's business-logic testability rule). The ordering proofs here (images
 * unavailable -> nothing written; purge only after storage) are the ones {@code
 * IdentityScanIntegrationTest} repeats against a real database, per the task's "live proof versus a
 * passing test" rule -- a mocked transaction manager cannot itself prove a rollback works, but it
 * can prove call order and which repository methods this class does or does not invoke.
 */
class IdentityScanServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

  /** The token life the real tenant returns, so usableUntil assertions mean something. */
  private static final Instant TOKEN_EXPIRY = NOW.plusSeconds(1800);

  private static IssuedAccessToken issuedToken(String value) {
    return new IssuedAccessToken(value, TOKEN_EXPIRY);
  }

  private static final UUID PROFILE_ID = UUID.randomUUID();

  /** \u0645\u062d\u0645\u062f -- the stored Arabic given name in BL-034's fixtures. */
  private static final String STORED_NAME_AR_GIVEN = "\u0645\u062d\u0645\u062f";

  private final IdentityScanRepository identityScanRepository = mock(IdentityScanRepository.class);
  private final ProfileRepository profileRepository = mock(ProfileRepository.class);
  private final UqudoClient uqudoClient = mock(UqudoClient.class);
  private final CivilRegistryClient civilRegistryClient = mock(CivilRegistryClient.class);
  private final AuditEventWriter auditEventWriter = mock(AuditEventWriter.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private final IdentityScanService service =
      new IdentityScanService(
          identityScanRepository,
          profileRepository,
          uqudoClient,
          civilRegistryClient,
          auditEventWriter,
          clock,
          transactionManager);

  {
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(transactionStatus);
    when(auditEventWriter.append(any())).thenReturn(1L);
    when(auditEventWriter.appendWithArtifact(any(), any())).thenReturn(1L);
    // The port now returns a record, not an Optional, so an unstubbed mock would hand back null;
    // the stub-shaped "not found" is the neutral default, exactly what the old Optional.empty()
    // was.
    when(civilRegistryClient.lookup(any())).thenReturn(stubNotFound());
  }

  private static ScanState freshState() {
    return new ScanState("in_progress", false, 0, 0, 0, null, "sid", "nonce", 0);
  }

  /** What {@code StubCivilRegistryClient} returns for NOT_FOUND: no record, no reason, no bytes. */
  private static RegistryLookup stubNotFound() {
    return new RegistryLookup(null, null, null, null);
  }

  /** A matching record with a real (fabricated) HTTP exchange, as the http adapter returns it. */
  private static RegistryLookup httpMatched(
      String identityNumber, byte[] request, byte[] response) {
    RegistryLookupResult record =
        new RegistryLookupResult(
            identityNumber,
            "محمد",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "m",
            null,
            null,
            new byte[] {7, 7, 7});
    return RegistryLookup.matched(
        record, new RegistryExchange(request, response, "application/json", 200));
  }

  // ---- issueToken ----

  @Test
  void issueTokenHappyPathReturnsIssuanceAndAudits() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    when(uqudoClient.issueAccessToken()).thenReturn(issuedToken("token-123"));

    TokenIssuance issuance = service.issueToken(PROFILE_ID, DocumentTypes.PASSPORT);

    assertEquals("token-123", issuance.accessToken());
    assertEquals(DocumentTypes.PASSPORT, issuance.documentType());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter).append(captor.capture());
    assertEquals("scan_token_issued", captor.getValue().eventType());
    verify(profileRepository).touchLastActivity(PROFILE_ID, NOW);
  }

  @Test
  void issueTokenUnknownProfileThrowsWithNoAudit() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID)).thenReturn(Optional.empty());

    assertThrows(
        UnknownProfileException.class,
        () -> service.issueToken(PROFILE_ID, DocumentTypes.PASSPORT));
    verify(auditEventWriter, never()).append(any());
  }

  @Test
  void issueTokenTerminalProfileIsRejectedAndAudited() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(new ScanState("submitted", true, 0, 0, 0, null, null, null, 0)));

    assertThrows(
        ProfileNotEditableException.class,
        () -> service.issueToken(PROFILE_ID, DocumentTypes.PASSPORT));

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter).append(captor.capture());
    assertEquals("identity_scan_rejected", captor.getValue().eventType());
    verify(uqudoClient, never()).issueAccessToken();
  }

  @Test
  void issueTokenStillBlockedIsRejected() {
    Instant blockedUntil = NOW.plusSeconds(3600);
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new ScanState("blocked_scan", false, 3, 3, 6, blockedUntil, null, null, 0)));

    ScanTemporarilyBlockedException thrown =
        assertThrows(
            ScanTemporarilyBlockedException.class,
            () -> service.issueToken(PROFILE_ID, DocumentTypes.PASSPORT));
    assertEquals(blockedUntil, thrown.blockedUntil());
    verify(uqudoClient, never()).issueAccessToken();
  }

  @Test
  void issueTokenResumesAfterBlockExpiresAndResetsCounters() {
    Instant blockedUntilInThePast = NOW.minusSeconds(60);
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new ScanState(
                    "blocked_scan", false, 3, 3, 6, blockedUntilInThePast, null, null, 0)));
    when(uqudoClient.issueAccessToken()).thenReturn(issuedToken("token-resumed"));

    TokenIssuance issuance = service.issueToken(PROFILE_ID, DocumentTypes.NATIONAL_ID);

    assertEquals("token-resumed", issuance.accessToken());
    verify(identityScanRepository).resumeFromScanBlock(eq(PROFILE_ID), eq(NOW), anyLong());
    verify(profileRepository, never()).touchLastActivity(any(), any());
  }

  @Test
  void issueTokenTypeExhaustedButTotalBelowLimitIsRejectedWithoutBlocking() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(new ScanState("in_progress", false, 5, 0, 5, null, null, null, 0)));

    assertThrows(
        ScanTypeExhaustedException.class,
        () -> service.issueToken(PROFILE_ID, DocumentTypes.NATIONAL_ID));
    verify(uqudoClient, never()).issueAccessToken();
    verify(identityScanRepository, never()).applyScanBlock(any(), any(), any(), anyLong());
  }

  // ---- BL-039 Slice B: the lifetime token cap ----

  @Test
  void issueTokenAtTheLifetimeCapAppliesTheExistingBlockFromInProgress() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new ScanState(
                    "in_progress",
                    false,
                    0,
                    0,
                    0,
                    null,
                    null,
                    null,
                    ScanAttemptBudget.LIFETIME_TOKEN_CAP)));

    ScanTemporarilyBlockedException blocked =
        assertThrows(
            ScanTemporarilyBlockedException.class,
            () -> service.issueToken(PROFILE_ID, DocumentTypes.NATIONAL_ID));

    // The EXISTING block response, with a real deadline the app can render -- no new code.
    assertEquals(NOW.plus(IdentityScanService.SCAN_BLOCK_DURATION), blocked.blockedUntil());
    verify(identityScanRepository)
        .applyScanBlock(
            eq(PROFILE_ID),
            eq(NOW.plus(IdentityScanService.SCAN_BLOCK_DURATION)),
            eq(NOW),
            anyLong());
    // Refused before the mint, so the cap does not run away from itself.
    verify(identityScanRepository, never()).incrementScanTokensMinted(any());
    verify(identityScanRepository, never()).recordPendingSession(any(), any(), any());
    verify(uqudoClient, never()).issueAccessToken();
  }

  /**
   * The ordering requirement the DECISION spells out, and the reason the cap check sits ABOVE the
   * expired-block lift rather than below it.
   *
   * <p>A profile at the cap whose 24-hour block has just expired: checked after the lift, it would
   * be resumed ({@code blocked_scan -> in_progress}, one status-history row, every counter zeroed)
   * and then immediately re-blocked ({@code in_progress -> blocked_scan}, a second row) — two rows
   * recording a round trip the customer never made. Checked before it, nothing is written at all
   * and the deadline already on the row is what comes back.
   */
  @Test
  void theCapIsCheckedBeforeTheExpiredBlockIsLiftedSoNothingIsWritten() {
    Instant expired = NOW.minusSeconds(1);
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new ScanState(
                    "blocked_scan",
                    false,
                    0,
                    0,
                    0,
                    expired,
                    null,
                    null,
                    ScanAttemptBudget.LIFETIME_TOKEN_CAP)));

    ScanTemporarilyBlockedException blocked =
        assertThrows(
            ScanTemporarilyBlockedException.class,
            () -> service.issueToken(PROFILE_ID, DocumentTypes.NATIONAL_ID));

    // The ORIGINAL deadline, not a fresh now+24h: nothing was reapplied.
    assertEquals(expired, blocked.blockedUntil());
    verify(identityScanRepository, never()).resumeFromScanBlock(any(), any(), anyLong());
    verify(identityScanRepository, never()).applyScanBlock(any(), any(), any(), anyLong());
    verify(identityScanRepository, never()).incrementScanTokensMinted(any());
  }

  /**
   * BLOCKER found by {@code @agent-reviewer} against the first Slice B draft. The cap branch
   * applied {@code blocked_scan} from any status that was not already {@code blocked_scan} — but
   * V0020 defines no transition into it from {@code blocked_liveness} or {@code abandoned} either,
   * and both are reachable at Stage 8 (Stage 10's purged-reference-image route sends a customer
   * back to the scan; the abandonment sweep parks profiles at {@code abandoned}). {@code
   * applyScanBlock}'s {@code insertHistory} hard-codes {@code 'in_progress'}, so calling it from
   * either would raise 23514 — a 500 where the customer should have seen a 409.
   */
  @Test
  void theScanCapNeverAppliesABlockFromANonInProgressStatus() {
    for (String status : new String[] {"blocked_liveness", "abandoned"}) {
      IdentityScanRepository repo = mock(IdentityScanRepository.class);
      when(repo.lockAndGetScanState(PROFILE_ID))
          .thenReturn(
              Optional.of(
                  new ScanState(
                      status,
                      false,
                      0,
                      0,
                      0,
                      null,
                      null,
                      null,
                      ScanAttemptBudget.LIFETIME_TOKEN_CAP)));
      IdentityScanService isolated =
          new IdentityScanService(
              repo,
              profileRepository,
              uqudoClient,
              civilRegistryClient,
              auditEventWriter,
              Clock.fixed(NOW, ZoneOffset.UTC),
              transactionManager);

      assertThrows(
          ScanTemporarilyBlockedException.class,
          () -> isolated.issueToken(PROFILE_ID, DocumentTypes.NATIONAL_ID),
          status + " must still be refused");
      verify(repo, never()).applyScanBlock(any(), any(), any(), anyLong());
      verify(repo, never()).incrementScanTokensMinted(any());
    }
  }

  /**
   * BL-118, S8-15. Both cap arms name themselves on the wire; every other block stays silent.
   *
   * <p>The silence is the load-bearing half. {@code null} means "this arm does not know why the
   * block exists", NOT "an ordinary block" — {@code submitScan}, {@code cancelScan} and {@code
   * reportWrongNumber} all re-report a {@code blocked_scan} row applied earlier by an arm they
   * cannot see, and a screen that read an absent reason as proof of an ordinary block would state
   * something the app never observed, which is BL-114(b) at a new door.
   *
   * <p>The code itself is deliberately UNCHANGED: a new code would degrade to {@code unknown} on
   * every app already in the field, and Stage 8 routes {@code unknown} to a resync that drops
   * {@code blockedUntil} — so naming the cap on the wire would have taken a capped customer OFF the
   * honest block screen S8-14 built for exactly that person.
   */
  @Test
  void bothCapArmsNameTheCapWhileAnOrdinaryBlockNamesNothing() {
    // Arm 1: at the cap, in_progress -- the block is applied now.
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new ScanState(
                    "in_progress",
                    false,
                    0,
                    0,
                    0,
                    null,
                    null,
                    null,
                    ScanAttemptBudget.LIFETIME_TOKEN_CAP)));
    assertEquals(
        ScanBlockReason.LIFETIME_CAP,
        assertThrows(
                ScanTemporarilyBlockedException.class,
                () -> service.issueToken(PROFILE_ID, DocumentTypes.NATIONAL_ID))
            .blockReason());

    // Arm 2: at the cap, NOT in_progress -- refused with the stored deadline, nothing written.
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new ScanState(
                    "abandoned",
                    false,
                    0,
                    0,
                    0,
                    null,
                    null,
                    null,
                    ScanAttemptBudget.LIFETIME_TOKEN_CAP)));
    assertEquals(
        ScanBlockReason.LIFETIME_CAP,
        assertThrows(
                ScanTemporarilyBlockedException.class,
                () -> service.issueToken(PROFILE_ID, DocumentTypes.NATIONAL_ID))
            .blockReason());

    // An ordinary live block, below the cap: no reason, because this arm genuinely does not know.
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new ScanState(
                    "blocked_scan", false, 3, 3, 6, NOW.plusSeconds(3600), null, null, 0)));
    assertNull(
        assertThrows(
                ScanTemporarilyBlockedException.class,
                () -> service.issueToken(PROFILE_ID, DocumentTypes.NATIONAL_ID))
            .blockReason(),
        "an arm that cannot know why the block exists must not claim it is an ordinary one");
  }

  /**
   * BL-114(a). The app cannot decide whether an issuance left unused by a camera-permission denial
   * is safe to re-launch without knowing when it dies, and the only figure it could otherwise
   * reason from is a single S1-02 observation. On the scan stage the token's own expiry IS the
   * deadline — the sessionId/nonce minted alongside it carry none of their own.
   */
  @Test
  void issueTokenReturnsTheAccessTokensOwnExpiryAsUsableUntil() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    when(uqudoClient.issueAccessToken()).thenReturn(issuedToken("token-123"));

    assertEquals(
        TOKEN_EXPIRY, service.issueToken(PROFILE_ID, DocumentTypes.PASSPORT).usableUntil());
  }

  /** Below the cap, an expired block still lifts exactly as it did before Slice B. */
  @Test
  void belowTheCapAnExpiredBlockStillResumesAndMints() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new ScanState(
                    "blocked_scan", false, 5, 5, 10, NOW.minusSeconds(1), null, null, 19)));
    when(uqudoClient.issueAccessToken()).thenReturn(issuedToken("token-resumed-below-cap"));

    service.issueToken(PROFILE_ID, DocumentTypes.NATIONAL_ID);

    verify(identityScanRepository).resumeFromScanBlock(eq(PROFILE_ID), eq(NOW), anyLong());
    verify(identityScanRepository).incrementScanTokensMinted(PROFILE_ID);
  }

  @Test
  void aSuccessfulIssuanceCountsExactlyOneMint() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    when(uqudoClient.issueAccessToken()).thenReturn(issuedToken("token-one-mint"));

    service.issueToken(PROFILE_ID, DocumentTypes.NATIONAL_ID);

    verify(identityScanRepository, times(1)).incrementScanTokensMinted(PROFILE_ID);
  }

  /** Every refusal that precedes the mint must leave the counter alone. */
  @Test
  void aRefusedIssuanceMintsNothing() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(new ScanState("submitted", true, 0, 0, 0, null, null, null, 3)));

    assertThrows(
        ProfileNotEditableException.class,
        () -> service.issueToken(PROFILE_ID, DocumentTypes.NATIONAL_ID));

    verify(identityScanRepository, never()).incrementScanTokensMinted(any());
  }

  // ---- submitScan: the ordering rule ----

  @Test
  void verifiedJwsWithUnavailableImagesWritesNothing() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    ParsedEnrolmentResult parsed = passportResult("NID-1", List.of(image("id-1")));
    when(uqudoClient.verifyAndParse(any(), any(), any(), any())).thenReturn(parsed);
    when(uqudoClient.downloadImage(any(), any())).thenThrow(new ImageUnavailableException("id-1"));

    assertThrows(
        ImagesUnavailableForAcceptanceException.class,
        () -> service.submitScan(PROFILE_ID, "sid", "nonce", DocumentTypes.PASSPORT, "jws"));

    verify(identityScanRepository, never()).insertAcceptedCycle(any(), any());
    verify(identityScanRepository, never()).insertScanResult(any(), any(), any());
    verify(identityScanRepository, never())
        .insertArtifactRef(
            any(), any(), any(), any(), any(), any(), anyLong(), any(), any(), any());
    verify(identityScanRepository, never()).applyScanAttempt(any(), any());
    // BL-039: the second exempt failure. A system-timing fact, not a scan-quality problem -- the
    // token stays live so the customer's retry is not charged a fresh mint either.
    verify(identityScanRepository, never()).consumePendingScanSession(any());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter).append(captor.capture());
    assertEquals("scan_images_unavailable", captor.getValue().eventType());
  }

  @Test
  void jwsRejectionCountsAgainstBudgetAndDoesNotAcceptTheScan() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    when(uqudoClient.verifyAndParse(any(), any(), any(), any()))
        .thenThrow(new JwsVerificationException("bad signature"));

    String rawJws = "header.payloadXYZ123.signature";
    assertThrows(
        JwsVerificationException.class,
        () -> service.submitScan(PROFILE_ID, "sid", "nonce", DocumentTypes.PASSPORT, rawJws));

    verify(identityScanRepository).applyScanAttempt(PROFILE_ID, DocumentTypes.PASSPORT);
    verify(identityScanRepository, never()).insertAcceptedCycle(any(), any());
    ArgumentCaptor<AuditEvent> eventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
    ArgumentCaptor<com.sfbank.bayanati.audit.domain.AuditArtifact> artifactCaptor =
        ArgumentCaptor.forClass(com.sfbank.bayanati.audit.domain.AuditArtifact.class);
    verify(auditEventWriter, times(1))
        .appendWithArtifact(eventCaptor.capture(), artifactCaptor.capture());
    assertEquals("scan_jws_rejected", eventCaptor.getValue().eventType());
    assertEquals(rawJws, new String(artifactCaptor.getValue().body()));
    assertFalse(
        eventCaptor.getValue().payloadJson().contains(rawJws),
        "the raw JWS VALUE never appears in payload_json, under any key name");
  }

  @Test
  void mismatchedSessionIsRejectedWithoutCallingUqudo() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));

    assertThrows(
        InvalidScanSessionException.class,
        () ->
            service.submitScan(
                PROFILE_ID, "not-the-issued-session-id", "nonce", DocumentTypes.PASSPORT, "jws"));

    verify(uqudoClient, never()).verifyAndParse(any(), any(), any(), any());
    verify(identityScanRepository, never()).applyScanAttempt(any(), any());
  }

  @Test
  void noPendingSessionAtAllIsRejected() {
    // issueToken was never called for this profile -- pending_scan_session_id/nonce are NULL.
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(new ScanState("in_progress", false, 0, 0, 0, null, null, null, 0)));

    assertThrows(
        InvalidScanSessionException.class,
        () -> service.submitScan(PROFILE_ID, "sid", "nonce", DocumentTypes.PASSPORT, "jws"));
  }

  @Test
  void submitScanWhileBlockedIsRejectedEvenWithAValidPendingSession() {
    // A pending session minted before the block must not let the 24h block be bypassed.
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new ScanState(
                    "blocked_scan", false, 3, 3, 6, NOW.plusSeconds(3600), "sid", "nonce", 0)));

    assertThrows(
        ScanTemporarilyBlockedException.class,
        () -> service.submitScan(PROFILE_ID, "sid", "nonce", DocumentTypes.PASSPORT, "jws"));
    verify(uqudoClient, never()).verifyAndParse(any(), any(), any(), any());
  }

  @Test
  void submitScanWhileAwaitingRegistryIsRejected() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new ScanState("awaiting_registry", false, 0, 0, 0, null, "sid", "nonce", 0)));

    assertThrows(
        RegistryReviewPendingException.class,
        () -> service.submitScan(PROFILE_ID, "sid", "nonce", DocumentTypes.PASSPORT, "jws"));
  }

  @Test
  void expiredArtifactIsNotCountedAgainstTheBudget() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    when(uqudoClient.verifyAndParse(any(), any(), any(), any()))
        .thenThrow(new ArtifactExpiredException("exp has passed"));

    assertThrows(
        ArtifactExpiredException.class,
        () -> service.submitScan(PROFILE_ID, "sid", "nonce", DocumentTypes.PASSPORT, "jws"));

    verify(identityScanRepository, never()).applyScanAttempt(any(), any());
    verify(identityScanRepository, never()).applyScanBlock(any(), any(), any(), anyLong());
    // BL-039: an exempt failure costs the customer nothing, so it must not burn the token either.
    // A stale clock is not a scan-quality problem (R-012/R-021) -- the SAME session is relaunched.
    verify(identityScanRepository, never()).consumePendingScanSession(any());
  }

  @Test
  void duplicateJtiIsMappedToJwsAlreadyAcceptedException() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    ParsedEnrolmentResult parsed = passportResult("NID-DUP", List.of(image("id-1")));
    when(uqudoClient.verifyAndParse(any(), any(), any(), any())).thenReturn(parsed);
    when(uqudoClient.downloadImage(any(), any())).thenReturn(new byte[] {1});
    when(civilRegistryClient.lookup(any())).thenReturn(stubNotFound());
    org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("uqudo_jti"))
        .when(identityScanRepository)
        .insertScanResult(any(), any(), any());

    assertThrows(
        JwsAlreadyAcceptedException.class,
        () -> service.submitScan(PROFILE_ID, "sid", "nonce", DocumentTypes.PASSPORT, "jws"));
  }

  // ---- BL-034: the dropped-acknowledgement retry ----

  /**
   * The defect-guarding test. It stubs {@code downloadImage} to throw {@code
   * ImageUnavailableException} deliberately: that is not an edge case here, it is the ordering
   * production actually hits. The first attempt's {@code purgeSession} runs the moment that attempt
   * commits, whether or not the client ever received the response, so by the time the app retries
   * the upload Uqudo's images are already gone. Against the pre-BL-034 code this test fails with
   * {@code ImagesUnavailableForAcceptanceException} -- exactly the 409 the customer was shown --
   * which is what makes it a proof rather than a restatement of the happy path.
   *
   * <p>It rules out more than "returns 200": no second Uqudo download, no second Civil Registry
   * call, no new cycle, no second hash-chained acceptance event, no second purge, and no draw from
   * the retry budget.
   */
  @Test
  void identicalJtiRetryOfAcceptedCycleReturnsExistingPayloadInsteadOfFailing() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    ParsedEnrolmentResult parsed = passportResult("NID-RETRY", List.of(image("id-1")));
    when(uqudoClient.verifyAndParse(any(), any(), any(), any())).thenReturn(parsed);
    when(uqudoClient.downloadImage(any(), any())).thenThrow(new ImageUnavailableException("id-1"));
    UUID existingCycleId = UUID.randomUUID();
    when(identityScanRepository.currentAcceptedScanSnapshot(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new AcceptedScanSnapshot(
                    existingCycleId,
                    parsed.jti(),
                    "NID-RETRY",
                    "PASSPORT",
                    "ok",
                    storedRegistryFields("NID-RETRY"))));
    when(identityScanRepository.activeCycleArtifactKinds(PROFILE_ID))
        .thenReturn(List.of("doc_front", "portrait_registry"));

    ScanDisplayPayload payload =
        service.submitScan(PROFILE_ID, "sid", "nonce", DocumentTypes.PASSPORT, "jws");

    // The SAME cycle, carrying the first attempt's stored registry answer -- not a new one.
    assertEquals(existingCycleId, payload.cycleId());
    assertEquals("NID-RETRY", payload.nationalNumber());
    assertEquals(DocumentTypes.PASSPORT, payload.documentType());
    assertEquals(true, payload.registryReady());
    assertEquals(STORED_NAME_AR_GIVEN, payload.nameArGiven());
    assertEquals(LocalDate.of(1990, 1, 2), payload.dateOfBirth());
    assertEquals(List.of("doc_front", "portrait_registry"), payload.availableImageKinds());

    verify(uqudoClient, never()).downloadImage(any(), any());
    verify(civilRegistryClient, never()).lookup(any());
    verify(uqudoClient, never()).purgeSession(any());
    verify(identityScanRepository, never()).insertAcceptedCycle(any(), any());
    verify(identityScanRepository, never()).insertScanResult(any(), any(), any());
    verify(identityScanRepository, never())
        .insertRegistryResult(any(), any(), any(), anyInt(), any(), any());
    verify(identityScanRepository, never()).applyScanAttempt(any(), any());
    verify(auditEventWriter, never()).appendWithArtifact(any(), any());

    // BL-039's load-bearing regression guard. Making the pending session single-use had exactly one
    // way to break BL-034: consuming it on the ACCEPTED path. The session-equality check sits
    // ABOVE this short-circuit in submitScan, so a retry of a scan that already landed re-presents
    // the same (sessionId, nonce, jws) triple -- if acceptance had cleared it, this retry would
    // die on INVALID_SCAN_SESSION and the customer would be told to rescan a scan the backend
    // already holds. That is the precise failure BL-034 was filed to remove.
    verify(identityScanRepository, never()).consumePendingScanSession(any());
  }

  /**
   * The other half of BL-034: the fix must not have disabled the duplicate guard. A DIFFERENT jti
   * against an already-populated active cycle falls through to the unchanged submission path and
   * opens a new cycle -- the legitimate "go back and scan a different document" flow, which
   * supersedes the active cycle rather than erroring.
   */
  @Test
  void differentJtiOnPopulatedCycleFallsThroughAndOpensANewCycle() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    ParsedEnrolmentResult parsed = passportResult("NID-OTHER", List.of(image("id-1")));
    when(uqudoClient.verifyAndParse(any(), any(), any(), any())).thenReturn(parsed);
    when(uqudoClient.downloadImage(any(), any())).thenReturn(new byte[] {9});
    when(identityScanRepository.currentAcceptedScanSnapshot(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new AcceptedScanSnapshot(
                    UUID.randomUUID(),
                    "a-completely-different-jti",
                    "NID-EARLIER",
                    "PASSPORT",
                    "ok",
                    storedRegistryFields("NID-EARLIER"))));
    UUID newCycleId = UUID.randomUUID();
    when(identityScanRepository.insertAcceptedCycle(any(), any())).thenReturn(newCycleId);

    ScanDisplayPayload payload =
        service.submitScan(PROFILE_ID, "sid", "nonce", DocumentTypes.PASSPORT, "jws");

    assertEquals(newCycleId, payload.cycleId());
    assertEquals("NID-OTHER", payload.nationalNumber());
    verify(identityScanRepository).insertAcceptedCycle(eq(PROFILE_ID), eq(NOW));
    verify(identityScanRepository).insertScanResult(eq(newCycleId), eq(parsed), eq(NOW));
    verify(uqudoClient).purgeSession(parsed.jti());
  }

  /**
   * And a different jti that is a genuine global replay -- one already accepted elsewhere -- still
   * errors on {@code scan_result.uqudo_jti}'s UNIQUE constraint (V0008). BL-034's read runs
   * unlocked and can miss, so this catch is a backstop it does not replace.
   */
  @Test
  void differentJtiThatIsAGlobalDuplicateStillThrowsJwsAlreadyAccepted() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    ParsedEnrolmentResult parsed = passportResult("NID-REPLAY", List.of(image("id-1")));
    when(uqudoClient.verifyAndParse(any(), any(), any(), any())).thenReturn(parsed);
    when(uqudoClient.downloadImage(any(), any())).thenReturn(new byte[] {9});
    when(identityScanRepository.currentAcceptedScanSnapshot(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new AcceptedScanSnapshot(
                    UUID.randomUUID(),
                    "not-this-jti",
                    "NID-EARLIER",
                    "PASSPORT",
                    "ok",
                    storedRegistryFields("NID-EARLIER"))));
    org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("uqudo_jti"))
        .when(identityScanRepository)
        .insertScanResult(any(), any(), any());

    assertThrows(
        JwsAlreadyAcceptedException.class,
        () -> service.submitScan(PROFILE_ID, "sid", "nonce", DocumentTypes.PASSPORT, "jws"));
  }

  /**
   * The registry-{@code ok} scope limit (BL-035) made explicit: an active cycle whose stored
   * registry state is not {@code ok} does not short-circuit even on an identical jti, so this fix
   * changed nothing on that path.
   */
  @Test
  void identicalJtiDoesNotShortCircuitWhenStoredRegistryStateIsNotOk() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    ParsedEnrolmentResult parsed = passportResult("NID-PENDING", List.of(image("id-1")));
    when(uqudoClient.verifyAndParse(any(), any(), any(), any())).thenReturn(parsed);
    when(uqudoClient.downloadImage(any(), any())).thenReturn(new byte[] {9});
    when(identityScanRepository.currentAcceptedScanSnapshot(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new AcceptedScanSnapshot(
                    UUID.randomUUID(),
                    parsed.jti(),
                    "NID-PENDING",
                    "PASSPORT",
                    "not_found",
                    null)));
    UUID newCycleId = UUID.randomUUID();
    when(identityScanRepository.insertAcceptedCycle(any(), any())).thenReturn(newCycleId);

    ScanDisplayPayload payload =
        service.submitScan(PROFILE_ID, "sid", "nonce", DocumentTypes.PASSPORT, "jws");

    assertEquals(newCycleId, payload.cycleId());
    verify(identityScanRepository).insertAcceptedCycle(eq(PROFILE_ID), eq(NOW));
  }

  /** The registry values as {@code app.registry_result} holds them -- photograph never included. */
  private static RegistryLookupResult storedRegistryFields(String identityNumber) {
    return new RegistryLookupResult(
        identityNumber,
        STORED_NAME_AR_GIVEN,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "m",
        LocalDate.of(1990, 1, 2),
        null,
        null);
  }

  @Test
  void cancelWhileAwaitingRegistryIsRejectedWithoutMutatingCounters() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new ScanState("awaiting_registry", false, 0, 0, 0, null, "sid", "nonce", 0)));

    assertThrows(
        RegistryReviewPendingException.class,
        () -> service.cancelScan(PROFILE_ID, DocumentTypes.PASSPORT));
    verify(identityScanRepository, never()).applyScanAttempt(any(), any());
  }

  @Test
  void cancelWhileAlreadyBlockedIsRejectedWithoutReapplyingTheBlock() {
    Instant blockedUntil = NOW.plusSeconds(3600);
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new ScanState("blocked_scan", false, 3, 3, 6, blockedUntil, "sid", "nonce", 0)));

    ScanTemporarilyBlockedException thrown =
        assertThrows(
            ScanTemporarilyBlockedException.class,
            () -> service.cancelScan(PROFILE_ID, DocumentTypes.PASSPORT));
    assertEquals(blockedUntil, thrown.blockedUntil());
    verify(identityScanRepository, never()).applyScanBlock(any(), any(), any(), anyLong());
  }

  @Test
  void wrongNumberRequiresRegistryOk() {
    when(identityScanRepository.currentActiveRegistryContext(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new ActiveRegistryContext(UUID.randomUUID(), "NID", "PASSPORT", "unreachable", 1)));
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));

    // The variant, not just the class: this site must produce REGISTRY_NOT_READY, because the
    // customer is legitimately paused and Stage 9 gives that its own screen. Asserting the
    // class alone would stay green if the throw site were flipped to noActiveCycle, which
    // would put STATE_CONFLICT on the wire and send them to a re-sync instead (BL-033).
    NoActiveRegistryReviewException thrown =
        assertThrows(
            NoActiveRegistryReviewException.class, () -> service.reportWrongNumber(PROFILE_ID));
    assertTrue(thrown.registryNotReady());
    verify(identityScanRepository, never()).supersedeActiveCycleIfAny(any(), any());
  }

  @Test
  void purgeSessionHappensOnlyAfterImagesAndCycleAreStored() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    ParsedEnrolmentResult parsed =
        passportResult("NID-2", List.of(image("id-front"), image("id-face")));
    when(uqudoClient.verifyAndParse(any(), any(), any(), any())).thenReturn(parsed);
    when(uqudoClient.downloadImage(any(), any())).thenReturn(new byte[] {1, 2, 3});
    when(identityScanRepository.insertAcceptedCycle(any(), any())).thenReturn(UUID.randomUUID());
    when(civilRegistryClient.lookup(any())).thenReturn(stubNotFound());

    service.submitScan(PROFILE_ID, "sid", "nonce", DocumentTypes.PASSPORT, "jws");

    InOrder order = inOrder(uqudoClient, identityScanRepository, uqudoClient);
    order.verify(uqudoClient, times(2)).downloadImage(any(), any());
    order.verify(identityScanRepository).insertAcceptedCycle(any(), any());
    order.verify(uqudoClient).purgeSession(parsed.jti());
  }

  @Test
  void registryNotFoundPausesTheProfile() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    ParsedEnrolmentResult parsed = passportResult("NID-3", List.of(image("id-1")));
    when(uqudoClient.verifyAndParse(any(), any(), any(), any())).thenReturn(parsed);
    when(uqudoClient.downloadImage(any(), any())).thenReturn(new byte[] {9});
    when(identityScanRepository.insertAcceptedCycle(any(), any())).thenReturn(UUID.randomUUID());
    when(civilRegistryClient.lookup(any())).thenReturn(stubNotFound());

    ScanDisplayPayload payload =
        service.submitScan(PROFILE_ID, "sid", "nonce", DocumentTypes.PASSPORT, "jws");

    assertEquals(false, payload.registryReady());
    verify(identityScanRepository).transitionToAwaitingRegistry(eq(PROFILE_ID), eq(NOW), anyLong());
  }

  @Test
  void registryUnreachablePausesTheProfileWithoutFailingTheScan() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    ParsedEnrolmentResult parsed = passportResult("NID-4", List.of(image("id-1")));
    when(uqudoClient.verifyAndParse(any(), any(), any(), any())).thenReturn(parsed);
    when(uqudoClient.downloadImage(any(), any())).thenReturn(new byte[] {9});
    when(identityScanRepository.insertAcceptedCycle(any(), any())).thenReturn(UUID.randomUUID());
    when(civilRegistryClient.lookup(any())).thenThrow(new RegistryUnreachableException("down"));

    ScanDisplayPayload payload =
        service.submitScan(PROFILE_ID, "sid", "nonce", DocumentTypes.PASSPORT, "jws");

    assertEquals(false, payload.registryReady());
    verify(identityScanRepository).transitionToAwaitingRegistry(eq(PROFILE_ID), eq(NOW), anyLong());
  }

  // ---- cancel ----

  @Test
  void cancelCountsAgainstBudget() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));

    service.cancelScan(PROFILE_ID, DocumentTypes.NATIONAL_ID);

    verify(identityScanRepository).applyScanAttempt(PROFILE_ID, DocumentTypes.NATIONAL_ID);
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter).append(captor.capture());
    assertEquals("scan_cancelled", captor.getValue().eventType());
  }

  @Test
  void cancelOnATerminalProfileIsRejectedWithoutMutatingCounters() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(new ScanState("submitted", true, 0, 0, 0, null, null, null, 0)));

    assertThrows(
        ProfileNotEditableException.class,
        () -> service.cancelScan(PROFILE_ID, DocumentTypes.PASSPORT));
    verify(identityScanRepository, never()).applyScanAttempt(any(), any());
  }

  @Test
  void tenthCancelAcrossBothTypesTriggersTheBlock() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(new ScanState("in_progress", false, 5, 4, 9, null, null, null, 0)));

    service.cancelScan(PROFILE_ID, DocumentTypes.PASSPORT);

    verify(identityScanRepository)
        .applyScanBlock(
            eq(PROFILE_ID),
            eq(NOW.plus(IdentityScanService.SCAN_BLOCK_DURATION)),
            eq(NOW),
            anyLong());
  }

  /**
   * BL-039, the defect as filed: the budget was consulted at token issuance and nowhere that spent
   * a try, so a spend path could carry the counter past its own limit. {@code /cancel} is the
   * cleanest demonstration because it needs no pending session at all — nothing about it was ever
   * gated on the budget.
   */
  @Test
  void cancelAtThePerTypeLimitIsRefusedWithoutSpendingAnything() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(new ScanState("in_progress", false, 5, 0, 5, null, "sid", "n", 0)));

    assertThrows(
        ScanTypeExhaustedException.class,
        () -> service.cancelScan(PROFILE_ID, DocumentTypes.NATIONAL_ID));

    // Refused, not counted: the customer is sent to the other document type with a fresh budget,
    // and nothing about this call moves them closer to the 24-hour block.
    verify(identityScanRepository, never()).applyScanAttempt(any(), any());
    verify(identityScanRepository, never()).consumePendingScanSession(any());
    verify(identityScanRepository, never()).insertAbandonedCycle(any(), any());
    verify(identityScanRepository, never()).applyScanBlock(any(), any(), any(), anyLong());
  }

  /**
   * BL-039's other half: one token backs exactly one attempt. The session that was standing when
   * the try was spent is consumed in the same transaction, so a second post through it is {@code
   * INVALID_SCAN_SESSION} rather than a second free draw.
   */
  @Test
  void aSpentAttemptConsumesThePendingSession() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));

    service.cancelScan(PROFILE_ID, DocumentTypes.NATIONAL_ID);

    InOrder order = inOrder(identityScanRepository);
    order.verify(identityScanRepository).applyScanAttempt(PROFILE_ID, DocumentTypes.NATIONAL_ID);
    order.verify(identityScanRepository).consumePendingScanSession(PROFILE_ID);
  }

  /** The same rule on the JWS-rejection path, which is where BL-039 was originally observed. */
  @Test
  void aRejectedJwsSpendsTheAttemptAndConsumesTheSession() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    when(uqudoClient.verifyAndParse(any(), any(), any(), any()))
        .thenThrow(new JwsVerificationException("bad signature"));

    assertThrows(
        JwsVerificationException.class,
        () -> service.submitScan(PROFILE_ID, "sid", "nonce", DocumentTypes.PASSPORT, "jws"));

    verify(identityScanRepository).applyScanAttempt(PROFILE_ID, DocumentTypes.PASSPORT);
    verify(identityScanRepository).consumePendingScanSession(PROFILE_ID);
  }

  // ---- Stage 9 ----

  @Test
  void acceptRequiresRegistryOk() {
    when(identityScanRepository.currentActiveRegistryContext(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new ActiveRegistryContext(UUID.randomUUID(), "NID", "PASSPORT", "unreachable", 1)));
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));

    NoActiveRegistryReviewException thrown =
        assertThrows(
            NoActiveRegistryReviewException.class, () -> service.acceptRegistryReview(PROFILE_ID));
    assertTrue(thrown.registryNotReady());
  }

  /** The third pause site. Same reasoning as wrongNumberRequiresRegistryOk. */
  @Test
  void wrongDetailsRequiresRegistryOkAndReportsThePauseVariant() {
    when(identityScanRepository.currentActiveRegistryContext(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new ActiveRegistryContext(UUID.randomUUID(), "NID", "PASSPORT", "pending", 1)));
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));

    NoActiveRegistryReviewException thrown =
        assertThrows(
            NoActiveRegistryReviewException.class, () -> service.reportWrongDetails(PROFILE_ID));
    assertTrue(thrown.registryNotReady());
  }

  /**
   * The other half of the split: no active cycle at all is a client-state defect, not a pause, and
   * must surface as STATE_CONFLICT so the app re-syncs through Stage 13.
   */
  @Test
  void noActiveCycleReportsTheStateConflictVariantRatherThanThePause() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    when(identityScanRepository.currentActiveRegistryContext(PROFILE_ID))
        .thenReturn(Optional.empty());

    NoActiveRegistryReviewException thrown =
        assertThrows(
            NoActiveRegistryReviewException.class, () -> service.acceptRegistryReview(PROFILE_ID));
    assertFalse(thrown.registryNotReady());
  }

  @Test
  void acceptMarksTheCycleAccepted() {
    UUID cycleId = UUID.randomUUID();
    when(identityScanRepository.currentActiveRegistryContext(PROFILE_ID))
        .thenReturn(Optional.of(new ActiveRegistryContext(cycleId, "NID", "PASSPORT", "ok", 1)));
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));

    service.acceptRegistryReview(PROFILE_ID);

    verify(identityScanRepository).markCycleAccepted(cycleId, NOW);
  }

  @Test
  void wrongNumberSupersedesAndCountsAgainstBudget() {
    UUID cycleId = UUID.randomUUID();
    when(identityScanRepository.currentActiveRegistryContext(PROFILE_ID))
        .thenReturn(Optional.of(new ActiveRegistryContext(cycleId, "NID", "PASSPORT", "ok", 1)));
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));

    Optional<Instant> blockedUntil = service.reportWrongNumber(PROFILE_ID);

    assertEquals(Optional.empty(), blockedUntil);
    verify(identityScanRepository).supersedeActiveCycleIfAny(PROFILE_ID, NOW);
    verify(identityScanRepository).applyScanAttempt(PROFILE_ID, DocumentTypes.PASSPORT);
    // BL-039: a spend path, so it consumes the session like the other one does.
    verify(identityScanRepository).consumePendingScanSession(PROFILE_ID);
  }

  /**
   * BL-039's extension beyond the ticket as filed. {@code reportWrongNumber} is the second path
   * that spends a stage-8 attempt, and — like {@code recordFailedAttempt} before this fix — it
   * consulted no budget at all before incrementing.
   *
   * <p>The refusal is placed before {@code supersedeActiveCycleIfAny} on purpose, and that is what
   * the second assertion protects: a call that declines to spend anything must not also destroy the
   * customer's verified identity cycle on the way out. Same ordering discipline BL-043's {@code
   * blocked_scan} guard already established here.
   */
  @Test
  void wrongNumberAtThePerTypeLimitIsRefusedAndLeavesTheActiveCycleStanding() {
    UUID cycleId = UUID.randomUUID();
    when(identityScanRepository.currentActiveRegistryContext(PROFILE_ID))
        .thenReturn(Optional.of(new ActiveRegistryContext(cycleId, "NID", "PASSPORT", "ok", 1)));
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(new ScanState("in_progress", false, 0, 5, 5, null, "sid", "n", 0)));

    assertThrows(ScanTypeExhaustedException.class, () -> service.reportWrongNumber(PROFILE_ID));

    verify(identityScanRepository, never()).supersedeActiveCycleIfAny(any(), any());
    verify(identityScanRepository, never()).applyScanAttempt(any(), any());
    verify(identityScanRepository, never()).consumePendingScanSession(any());
    verify(identityScanRepository, never()).applyScanBlock(any(), any(), any(), anyLong());
  }

  @Test
  void wrongDetailsTransitionsToTerminatedMismatch() {
    when(identityScanRepository.currentActiveRegistryContext(PROFILE_ID))
        .thenReturn(
            Optional.of(new ActiveRegistryContext(UUID.randomUUID(), "NID", "PASSPORT", "ok", 1)));
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));

    service.reportWrongDetails(PROFILE_ID);

    verify(identityScanRepository)
        .transitionToTerminatedMismatch(eq(PROFILE_ID), eq(NOW), anyLong());
  }

  @Test
  void wrongDetailsReLocksTheProfileRowInsideItsOwnTransactionBeforeTheAuditWrite() {
    // Regression for a lock-order inversion found by @agent-reviewer at S4-02:
    // requireActiveContext's own lockAndGetScanState call (outside any transaction this method
    // opens) is released with that unwrapped statement, so without a second lock call as this
    // transaction's first statement, auditEventWriter.append (the audit-chain lock) would run
    // BEFORE any app.profile row lock inside the transaction -- inverting this codebase's lock
    // order (see operator.domain.ReviewRepository#lockAndReadStatus and
    // docs/components/persistence.md "Lock ordering for operator writers") and reachable now
    // that S4-02's manual completion is a second legal writer on the same in_progress profiles.
    when(identityScanRepository.currentActiveRegistryContext(PROFILE_ID))
        .thenReturn(
            Optional.of(new ActiveRegistryContext(UUID.randomUUID(), "NID", "PASSPORT", "ok", 1)));
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));

    service.reportWrongDetails(PROFILE_ID);

    InOrder order = inOrder(identityScanRepository, auditEventWriter);
    order.verify(identityScanRepository, times(2)).lockAndGetScanState(PROFILE_ID);
    order.verify(auditEventWriter).append(any());
    order
        .verify(identityScanRepository)
        .transitionToTerminatedMismatch(eq(PROFILE_ID), eq(NOW), anyLong());
  }

  @Test
  void retrySucceedsAndTransitionsBackToInProgressWithoutTouchingUqudo() {
    UUID cycleId = UUID.randomUUID();
    when(identityScanRepository.currentActiveRegistryContext(PROFILE_ID))
        .thenReturn(
            Optional.of(new ActiveRegistryContext(cycleId, "NID", "PASSPORT", "unreachable", 1)));
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    when(civilRegistryClient.lookup("NID"))
        .thenReturn(new RegistryLookup(sampleRegistryResult(), "NID", null, null));

    service.retryRegistryLookup(PROFILE_ID);

    verify(uqudoClient, never()).issueAccessToken();
    verify(uqudoClient, never()).verifyAndParse(any(), any(), any(), any());
    verify(identityScanRepository)
        .transitionBackToInProgressFromRegistry(eq(PROFILE_ID), eq(NOW), anyLong());
  }

  @Test
  void retryStillUnreachableStaysPaused() {
    UUID cycleId = UUID.randomUUID();
    when(identityScanRepository.currentActiveRegistryContext(PROFILE_ID))
        .thenReturn(
            Optional.of(new ActiveRegistryContext(cycleId, "NID", "PASSPORT", "unreachable", 1)));
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    when(civilRegistryClient.lookup("NID")).thenReturn(stubNotFound());

    service.retryRegistryLookup(PROFILE_ID);

    verify(identityScanRepository, never())
        .transitionBackToInProgressFromRegistry(any(), any(), anyLong());
  }

  // ---- S5-11: the read-only Stage 9 resume payload, and retryRegistryLookup's two new guards ----

  /**
   * The whole point of the read endpoint: the stored payload comes back and nothing is called or
   * written. The field assertions are what make this a proof rather than a smoke test -- they are
   * the values {@code currentAcceptedScanSnapshot} returned, not values a lookup produced.
   */
  @Test
  void currentReviewPayloadReturnsTheStoredPayloadWithoutCallingTheRegistry() {
    UUID cycleId = UUID.randomUUID();
    when(identityScanRepository.readScanState(PROFILE_ID)).thenReturn(Optional.of(freshState()));
    when(identityScanRepository.currentAcceptedScanSnapshot(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new AcceptedScanSnapshot(
                    cycleId, "jti-1", "NID-9", "PASSPORT", "ok", storedRegistryFields("NID-9"))));
    when(identityScanRepository.activeCycleArtifactKinds(PROFILE_ID))
        .thenReturn(List.of("doc_front", "portrait_registry"));

    ScanDisplayPayload payload = service.currentReviewPayload(PROFILE_ID);

    assertEquals(cycleId, payload.cycleId());
    assertEquals(DocumentTypes.PASSPORT, payload.documentType());
    assertEquals("NID-9", payload.nationalNumber());
    assertTrue(payload.registryReady());
    assertEquals(STORED_NAME_AR_GIVEN, payload.nameArGiven());
    assertEquals(LocalDate.of(1990, 1, 2), payload.dateOfBirth());
    assertEquals(List.of("doc_front", "portrait_registry"), payload.availableImageKinds());

    verify(civilRegistryClient, never()).lookup(any());
    verify(uqudoClient, never()).verifyAndParse(any(), any(), any(), any());
    verify(identityScanRepository, never()).lockAndGetScanState(any());
    verify(identityScanRepository, never())
        .updateRegistryResultOnRetry(any(), any(), any(), anyInt(), any(), any());
    verify(identityScanRepository, never())
        .insertRegistryResult(any(), any(), any(), anyInt(), any(), any());
    verify(identityScanRepository, never()).applyScanAttempt(any(), any());
    verify(identityScanRepository, never()).markCycleAccepted(any(), any());
    verify(identityScanRepository, never()).supersedeActiveCycleIfAny(any(), any());
    verify(identityScanRepository, never())
        .transitionBackToInProgressFromRegistry(any(), any(), anyLong());
    verify(profileRepository, never()).touchLastActivity(any(), any());
    verify(auditEventWriter, never()).append(any());
    verify(auditEventWriter, never()).appendWithArtifact(any(), any());
  }

  /**
   * A paused cycle is a {@code 200} with {@code registryReady=false}, never a conflict -- a
   * customer checking their own paused status must not be answered with an error (S5-11, A1).
   */
  @Test
  void currentReviewPayloadReportsAPausedCycleAsNotReadyRatherThanThrowing() {
    when(identityScanRepository.readScanState(PROFILE_ID)).thenReturn(Optional.of(freshState()));
    when(identityScanRepository.currentAcceptedScanSnapshot(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new AcceptedScanSnapshot(
                    UUID.randomUUID(), "jti-1", "NID-9", "SDN_ID", "unreachable", null)));

    ScanDisplayPayload payload = service.currentReviewPayload(PROFILE_ID);

    assertFalse(payload.registryReady());
    assertEquals("NID-9", payload.nationalNumber(), "the national number is shown first and alone");
    assertEquals(null, payload.nameArGiven());
    assertEquals(null, payload.dateOfBirth());
    verify(civilRegistryClient, never()).lookup(any());
  }

  @Test
  void currentReviewPayloadRejectsAnUnknownProfile() {
    when(identityScanRepository.readScanState(PROFILE_ID)).thenReturn(Optional.empty());

    assertThrows(UnknownProfileException.class, () -> service.currentReviewPayload(PROFILE_ID));
    verify(identityScanRepository, never()).currentAcceptedScanSnapshot(any());
  }

  @Test
  void currentReviewPayloadRejectsATerminalProfile() {
    when(identityScanRepository.readScanState(PROFILE_ID))
        .thenReturn(Optional.of(new ScanState("submitted", true, 0, 0, 0, null, null, null, 0)));

    assertThrows(ProfileNotEditableException.class, () -> service.currentReviewPayload(PROFILE_ID));
    verify(identityScanRepository, never()).currentAcceptedScanSnapshot(any());
  }

  /** No active cycle -- STATE_CONFLICT, not REGISTRY_NOT_READY: the app re-syncs via Stage 13. */
  @Test
  void currentReviewPayloadWithoutAnActiveCycleIsAStateConflict() {
    when(identityScanRepository.readScanState(PROFILE_ID)).thenReturn(Optional.of(freshState()));
    when(identityScanRepository.currentAcceptedScanSnapshot(PROFILE_ID))
        .thenReturn(Optional.empty());

    NoActiveRegistryReviewException thrown =
        assertThrows(
            NoActiveRegistryReviewException.class, () -> service.currentReviewPayload(PROFILE_ID));
    assertFalse(thrown.registryNotReady(), "STATE_CONFLICT, not REGISTRY_NOT_READY");
  }

  /**
   * The defect this slice fixes. Before the guard, a retry against an already-{@code ok} cycle
   * re-queried the registry and wrote the outcome over the stored one -- and {@code
   * updateRegistryResultOnRetry} writes EVERY name/DOB/address column from {@code fields}, which a
   * {@code not_found} outcome leaves null. One retry during an outage blanked a verified record.
   *
   * <p><strong>Direct wrong-value assertion, so no revert-restore proof is required.</strong> The
   * stubbed live lookup returns {@code sampleRegistryResult()} ("Given", 1990-01-01); the stored
   * snapshot holds {@code storedRegistryFields} (محمد, 1990-01-02). The assertions below name the
   * STORED values, which the pre-fix code cannot return -- it returns the freshly queried ones. The
   * {@code verify(never())} calls corroborate; they are not what the test rests on.
   */
  @Test
  void retryOnAnAlreadyOkResultReturnsTheStoredPayloadAndNeverRequeriesTheRegistry() {
    UUID cycleId = UUID.randomUUID();
    when(identityScanRepository.currentActiveRegistryContext(PROFILE_ID))
        .thenReturn(Optional.of(new ActiveRegistryContext(cycleId, "NID-9", "PASSPORT", "ok", 1)));
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    when(identityScanRepository.readScanState(PROFILE_ID)).thenReturn(Optional.of(freshState()));
    when(identityScanRepository.currentAcceptedScanSnapshot(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new AcceptedScanSnapshot(
                    cycleId, "jti-1", "NID-9", "PASSPORT", "ok", storedRegistryFields("NID-9"))));
    when(civilRegistryClient.lookup("NID-9"))
        .thenReturn(new RegistryLookup(sampleRegistryResult(), "NID-9", null, null));

    ScanDisplayPayload payload = service.retryRegistryLookup(PROFILE_ID);

    assertTrue(payload.registryReady());
    assertEquals(
        STORED_NAME_AR_GIVEN, payload.nameArGiven(), "the STORED name, not a re-queried one");
    assertEquals(LocalDate.of(1990, 1, 2), payload.dateOfBirth(), "the STORED date of birth");
    assertEquals(cycleId, payload.cycleId());

    verify(civilRegistryClient, never()).lookup(any());
    verify(identityScanRepository, never())
        .updateRegistryResultOnRetry(any(), any(), any(), anyInt(), any(), any());
    verify(identityScanRepository, never())
        .insertArtifactRef(
            any(), any(), any(), any(), any(), any(), anyLong(), any(), any(), any());
  }

  /**
   * The second missing guard. {@code markCycleAccepted} does not change {@code
   * identity_cycle.state}, so a submitted/approved/rejected profile still has an ACTIVE cycle and
   * {@code requireActiveReview} finds it -- a stray retry used to re-query the registry and
   * overwrite a finished journey's stored result.
   *
   * <p><strong>Direct assertion, no revert-restore required:</strong> the pre-fix code returns
   * normally here, so {@code assertThrows} cannot pass against it.
   */
  @Test
  void retryOnATerminalProfileIsRejectedBeforeAnyRegistryCall() {
    UUID cycleId = UUID.randomUUID();
    when(identityScanRepository.currentActiveRegistryContext(PROFILE_ID))
        .thenReturn(
            Optional.of(new ActiveRegistryContext(cycleId, "NID", "PASSPORT", "unreachable", 1)));
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(new ScanState("submitted", true, 0, 0, 0, null, null, null, 0)));

    assertThrows(ProfileNotEditableException.class, () -> service.retryRegistryLookup(PROFILE_ID));

    verify(civilRegistryClient, never()).lookup(any());
    verify(identityScanRepository, never())
        .updateRegistryResultOnRetry(any(), any(), any(), anyInt(), any(), any());
  }

  /**
   * The same guard under the write lock. The pre-transaction check reads {@code app.profile} before
   * the unbounded Civil Registry call, so a profile that reaches a terminal status *during* that
   * call would still have its {@code registry_result} overwritten -- BL-038's defect (2) in its
   * race form. Here the first {@code lockAndGetScanState} (pre-transaction) is healthy and the
   * second (inside the transaction) is terminal.
   *
   * <p>Direct assertion again: the pre-fix code -- and the first draft of this fix -- returns
   * normally and writes the row.
   */
  @Test
  void retryAbortsWithoutWritingIfTheProfileTurnsTerminalDuringTheRegistryCall() {
    UUID cycleId = UUID.randomUUID();
    when(identityScanRepository.currentActiveRegistryContext(PROFILE_ID))
        .thenReturn(
            Optional.of(new ActiveRegistryContext(cycleId, "NID", "PASSPORT", "unreachable", 1)));
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(
            Optional.of(freshState()),
            Optional.of(new ScanState("submitted", true, 0, 0, 0, null, null, null, 0)));
    when(civilRegistryClient.lookup("NID"))
        .thenReturn(new RegistryLookup(sampleRegistryResult(), "NID", null, null));

    assertThrows(ProfileNotEditableException.class, () -> service.retryRegistryLookup(PROFILE_ID));

    verify(identityScanRepository, never())
        .updateRegistryResultOnRetry(any(), any(), any(), anyInt(), any(), any());
    verify(identityScanRepository, never())
        .transitionBackToInProgressFromRegistry(any(), any(), anyLong());
    verify(identityScanRepository, never())
        .insertArtifactRef(
            any(), any(), any(), any(), any(), any(), anyLong(), any(), any(), any());
  }

  // ---- BL-042: terminality parity across Stage 9's other three writers -------------------------

  private static ScanState terminalState() {
    return new ScanState("submitted", true, 0, 0, 0, null, null, null, 0);
  }

  private static ScanState blockedState(Instant blockedUntil) {
    return new ScanState("blocked_scan", false, 3, 3, 6, blockedUntil, null, null, 0);
  }

  private void stage9OkContext(UUID cycleId) {
    when(identityScanRepository.currentActiveRegistryContext(PROFILE_ID))
        .thenReturn(Optional.of(new ActiveRegistryContext(cycleId, "NID", "PASSPORT", "ok", 1)));
  }

  /**
   * BL-042, the silent-commit case. Pre-fix this method COMMITTED against a finished journey:
   * {@code markCycleAccepted} does not change {@code identity_cycle.state}, so a submitted profile
   * still has an ACTIVE cycle whose registry result is {@code ok}, and V0020's trigger fires only
   * on a status CHANGE -- which this path does not make. The result was {@code
   * identity_cycle.accepted_at} rewritten to now on a cycle the operator had already approved, plus
   * a {@code registry_review_accepted} event attributed to the customer.
   *
   * <p><strong>Direct wrong-value assertion, so no revert-restore is strictly required:</strong>
   * the pre-fix code returns normally, so {@code assertThrows} cannot pass against it. A revert was
   * nonetheless run for this case (session report) because the guard's second half is an ORDERING
   * claim, which the companion test below is what actually pins.
   */
  @Test
  void acceptOnATerminalProfileIsRejectedAndWritesNothing() {
    UUID cycleId = UUID.randomUUID();
    stage9OkContext(cycleId);
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(terminalState()));

    assertThrows(ProfileNotEditableException.class, () -> service.acceptRegistryReview(PROFILE_ID));

    verify(identityScanRepository, never()).markCycleAccepted(any(), any());
    verify(auditEventWriter, never()).append(any());
  }

  /**
   * The same guard under the write lock, where {@code requireActiveReview}'s own lock has already
   * been released. Without this half the guard is only an early exit: {@code app.profile} has real
   * concurrent writers (operator approve/reject, S4-02 manual completion, submission), and a
   * single-state stub cannot tell the two halves apart -- which is why this variant exists rather
   * than resting on the test above.
   */
  @Test
  void acceptAbortsWithoutWritingIfTheProfileTurnsTerminalUnderTheLock() {
    UUID cycleId = UUID.randomUUID();
    stage9OkContext(cycleId);
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()), Optional.of(terminalState()));

    assertThrows(ProfileNotEditableException.class, () -> service.acceptRegistryReview(PROFILE_ID));

    verify(identityScanRepository, never()).markCycleAccepted(any(), any());
    verify(auditEventWriter, never()).append(any());
  }

  /**
   * BL-042's other silent commit, and the more damaging one: {@code supersedeActiveCycleIfAny} and
   * {@code applyScanAttempt} change no status, so V0020 never fired and a completed profile's
   * identity cycle was marked superseded with its scan-attempt counters moved. {@code
   * state='active' AND accepted_at IS NOT NULL} is what {@code JdbcLivenessRepository} and {@code
   * JdbcSubmissionRepository} gate on, so the link between a submitted profile and its verified
   * scan was gone. Direct assertion; pre-fix this returns {@code Optional.empty()} normally.
   */
  @Test
  void wrongNumberOnATerminalProfileIsRejectedAndWritesNothing() {
    stage9OkContext(UUID.randomUUID());
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(terminalState()));

    assertThrows(ProfileNotEditableException.class, () -> service.reportWrongNumber(PROFILE_ID));

    verify(identityScanRepository, never()).supersedeActiveCycleIfAny(any(), any());
    verify(identityScanRepository, never()).applyScanAttempt(any(), any());
    verify(identityScanRepository, never()).applyScanBlock(any(), any(), any(), anyLong());
    verify(auditEventWriter, never()).append(any());
  }

  /** The under-lock half, as for accept. */
  @Test
  void wrongNumberAbortsWithoutWritingIfTheProfileTurnsTerminalUnderTheLock() {
    stage9OkContext(UUID.randomUUID());
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()), Optional.of(terminalState()));

    assertThrows(ProfileNotEditableException.class, () -> service.reportWrongNumber(PROFILE_ID));

    verify(identityScanRepository, never()).supersedeActiveCycleIfAny(any(), any());
    verify(identityScanRepository, never()).applyScanAttempt(any(), any());
    verify(auditEventWriter, never()).append(any());
  }

  /**
   * The one of the three V0020 did catch, and caught wrongly: {@code submitted ->
   * terminated_registry_mismatch} is in no {@code app.status_transition} row, so the trigger raised
   * {@code 23514} and the customer got an UNMAPPED 500 where every sibling answers {@code
   * PROFILE_TERMINAL} (409). Direct assertion on the exception TYPE -- pre-fix the failure is a
   * data-integrity exception from the trigger, not this one, so the test cannot pass against it.
   */
  @Test
  void wrongDetailsOnATerminalProfileIsRejectedWithProfileTerminalNotADatabaseError() {
    stage9OkContext(UUID.randomUUID());
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(terminalState()));

    assertThrows(ProfileNotEditableException.class, () -> service.reportWrongDetails(PROFILE_ID));

    verify(identityScanRepository, never()).transitionToTerminatedMismatch(any(), any(), anyLong());
    verify(auditEventWriter, never()).append(any());
  }

  /**
   * The under-lock half for the third sibling. Added after {@code @agent-reviewer} found that the
   * test above stubs the terminal state on the FIRST {@code lockAndGetScanState} call, so the
   * pre-transaction guard fires and the transaction body is never entered — leaving this method's
   * under-lock re-check the one of the three that no test executed, and that could be deleted with
   * the suite still green.
   */
  @Test
  void wrongDetailsAbortsWithoutWritingIfTheProfileTurnsTerminalUnderTheLock() {
    stage9OkContext(UUID.randomUUID());
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()), Optional.of(terminalState()));

    assertThrows(ProfileNotEditableException.class, () -> service.reportWrongDetails(PROFILE_ID));

    verify(identityScanRepository, never()).transitionToTerminatedMismatch(any(), any(), anyLong());
    verify(auditEventWriter, never()).append(any());
  }

  // ---- BL-043: reportWrongNumber re-checks blocked_scan / awaiting_registry --------------------

  /**
   * BL-043. {@code blocked_scan} is NOT terminal (V0005), so BL-042's guard does not cover it, and
   * the state is reachable with no race at all: the customer returns to Stage 8, requests a token
   * and cancels the SDK six times -- none of which supersedes the active {@code ok} cycle -- and
   * {@code currentReviewPayload} still serves the Stage 9 payload, because it checks {@code
   * terminal()} alone. Pre-fix, "the national number is wrong" from there re-ran {@code
   * applyScanBlock} (blockTriggered(7) is true), V0020 saw no status change and passed, {@code
   * scan_blocked_until} was pushed out ANOTHER 24 hours, and {@code insertHistory} wrote a {@code
   * profile_status_history} row for a transition that never occurred.
   *
   * <p><strong>ORDERING DEFECT -- revert-restore proof required and performed</strong> (session
   * report): the guard has to sit BEFORE the writes, and against mocked collaborators a guard
   * placed after them would still throw and still satisfy a naive assertion. The {@code never()}
   * verifications are what carry the claim, and they are indirect.
   */
  @Test
  void wrongNumberDuringALiveBlockIsRefusedWithoutExtendingItOrWritingHistory() {
    Instant blockLiftsAt = NOW.plusSeconds(3600);
    stage9OkContext(UUID.randomUUID());
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(blockedState(blockLiftsAt)));

    ScanTemporarilyBlockedException thrown =
        assertThrows(
            ScanTemporarilyBlockedException.class, () -> service.reportWrongNumber(PROFILE_ID));

    assertEquals(
        blockLiftsAt,
        thrown.blockedUntil(),
        "the ORIGINAL expiry, never now + 24h -- a refused report must not move when the block lifts");
    verify(identityScanRepository, never()).applyScanBlock(any(), any(), any(), anyLong());
    verify(identityScanRepository, never()).applyScanAttempt(any(), any());
    verify(identityScanRepository, never()).supersedeActiveCycleIfAny(any(), any());

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter).append(captor.capture());
    assertEquals(
        "identity_scan_rejected",
        captor.getValue().eventType(),
        "the refusal is audited -- this surface is unauthenticated by design (R-051)");
  }

  /**
   * Parity with {@code recordFailedAttempt}'s third re-check rather than a reachable defect: the
   * registry-ready guard already refuses unless the ACTIVE cycle is {@code ok}, and a profile only
   * reaches {@code awaiting_registry} with a not-{@code ok} active cycle. <strong>This passing test
   * is evidence that the guard holds, NOT that the state is reachable today.</strong> It is here
   * because this method's whole defect was inheriting three sibling checks and getting none of
   * them; the driver stubs the state directly to exercise the branch.
   */
  @Test
  void wrongNumberWhileAwaitingRegistryIsRefusedForParityWithRecordFailedAttempt() {
    stage9OkContext(UUID.randomUUID());
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(
            Optional.of(new ScanState("awaiting_registry", false, 1, 0, 1, null, null, null, 0)));

    assertThrows(RegistryReviewPendingException.class, () -> service.reportWrongNumber(PROFILE_ID));

    verify(identityScanRepository, never()).applyScanAttempt(any(), any());
    verify(identityScanRepository, never()).supersedeActiveCycleIfAny(any(), any());
  }

  // ---- BL-044: the already-ok decision, re-made under the lock ---------------------------------

  /**
   * BL-044 at the unit level, and the reason it is not the same defect as BL-038: the
   * pre-transaction short-circuit reads the context taken BEFORE the unbounded registry call, so
   * two retries that both start while the state is not-{@code ok} both pass it and both query the
   * registry. The row lock serialises them, and the loser must notice under that lock that the
   * winner already stored a verified result. Here the first {@code currentActiveRegistryContext}
   * (pre-transaction) is {@code unreachable} and the second (inside the transaction) is {@code ok}
   * -- the interleaving the lock actually produces, using the consecutive-return pattern {@code
   * retryAbortsWithoutWritingIfTheProfileTurnsTerminalDuringTheRegistryCall} established.
   *
   * <p>Direct assertion: pre-fix, {@code updateRegistryResultOnRetry} is called unconditionally, so
   * the {@code never()} on it cannot pass. The real two-thread race is proven against a live
   * database in {@code IdentityScanIntegrationTest}; this pins the decision itself.
   */
  @Test
  void retryDoesNotOverwriteAResultAConcurrentRetryAlreadyStoredUnderTheLock() {
    UUID cycleId = UUID.randomUUID();
    when(identityScanRepository.currentActiveRegistryContext(PROFILE_ID))
        .thenReturn(
            Optional.of(new ActiveRegistryContext(cycleId, "NID-9", "PASSPORT", "unreachable", 1)),
            Optional.of(new ActiveRegistryContext(cycleId, "NID-9", "PASSPORT", "ok", 2)));
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    when(identityScanRepository.readScanState(PROFILE_ID)).thenReturn(Optional.of(freshState()));
    when(identityScanRepository.currentAcceptedScanSnapshot(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new AcceptedScanSnapshot(
                    cycleId, "jti-1", "NID-9", "PASSPORT", "ok", storedRegistryFields("NID-9"))));
    when(civilRegistryClient.lookup("NID-9"))
        .thenReturn(new RegistryLookup(sampleRegistryResult(), "NID-9", null, null));

    ScanDisplayPayload payload = service.retryRegistryLookup(PROFILE_ID);

    // The winner's stored values, not this call's own freshly-queried ones.
    assertEquals(
        STORED_NAME_AR_GIVEN, payload.nameArGiven(), "the STORED name the winner committed");
    assertEquals(LocalDate.of(1990, 1, 2), payload.dateOfBirth(), "the STORED date of birth");

    verify(identityScanRepository, never())
        .updateRegistryResultOnRetry(any(), any(), any(), anyInt(), any(), any());
    verify(identityScanRepository, never())
        .insertArtifactRef(
            any(), any(), any(), any(), any(), any(), anyLong(), any(), any(), any());
    verify(identityScanRepository, never())
        .transitionBackToInProgressFromRegistry(any(), any(), anyLong());
  }

  /**
   * The other half of BL-044's fix, and the one it would be easy to get wrong: the losing retry
   * really did send the national number to the Civil Registry and really did get an answer, so that
   * outbound exchange is audited even though its result is discarded. Returning before the audit
   * write would introduce BL-040's class of evidence loss as part of the fix for BL-044.
   */
  @Test
  void theLosingRetryStillAuditsTheRegistryExchangeItActuallyMade() {
    UUID cycleId = UUID.randomUUID();
    when(identityScanRepository.currentActiveRegistryContext(PROFILE_ID))
        .thenReturn(
            Optional.of(new ActiveRegistryContext(cycleId, "NID-9", "PASSPORT", "unreachable", 1)),
            Optional.of(new ActiveRegistryContext(cycleId, "NID-9", "PASSPORT", "ok", 2)));
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    when(identityScanRepository.readScanState(PROFILE_ID)).thenReturn(Optional.of(freshState()));
    when(identityScanRepository.currentAcceptedScanSnapshot(PROFILE_ID))
        .thenReturn(
            Optional.of(
                new AcceptedScanSnapshot(
                    cycleId, "jti-1", "NID-9", "PASSPORT", "ok", storedRegistryFields("NID-9"))));
    when(civilRegistryClient.lookup("NID-9"))
        .thenReturn(new RegistryLookup(sampleRegistryResult(), "NID-9", null, null));

    service.retryRegistryLookup(PROFILE_ID);

    verify(civilRegistryClient).lookup("NID-9");
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter).append(captor.capture());
    assertEquals(
        "registry_lookup_completed",
        captor.getValue().eventType(),
        "the exchange happened, so it is on the chain even though its result was discarded");
  }

  private static RegistryLookupResult sampleRegistryResult() {
    return new RegistryLookupResult(
        "NID",
        "Given",
        "Father",
        "Grandfather",
        "GreatGrandfather",
        "Mother",
        "MotherFather",
        "MotherGrandfather",
        "MotherGreatGrandfather",
        "First",
        "Last",
        "m",
        java.time.LocalDate.of(1990, 1, 1),
        "Sample address",
        null);
  }

  // ---- the real adapter's exchange: two events, one chain, the returned identity number ----

  @Test
  void aRealExchangeWritesTheRequestAndCompletedEventsWithTheirArtifactsInOrder() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    ParsedEnrolmentResult parsed = passportResult("NID-5", List.of(image("id-1")));
    when(uqudoClient.verifyAndParse(any(), any(), any(), any())).thenReturn(parsed);
    when(uqudoClient.downloadImage(any(), any())).thenReturn(new byte[] {9});
    when(identityScanRepository.insertAcceptedCycle(any(), any())).thenReturn(UUID.randomUUID());
    byte[] request = "{\"NID\":\"NID-5\"}".getBytes();
    byte[] response = "{\"IDENTITY_NUMBER\":\"NID-5\",\"NAME\":\"x\"}".getBytes();
    when(civilRegistryClient.lookup("NID-5")).thenReturn(httpMatched("NID-5", request, response));

    ScanDisplayPayload payload =
        service.submitScan(PROFILE_ID, "sid", "nonce", DocumentTypes.PASSPORT, "jws");

    assertEquals(true, payload.registryReady());
    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    ArgumentCaptor<com.sfbank.bayanati.audit.domain.AuditArtifact> artifacts =
        ArgumentCaptor.forClass(com.sfbank.bayanati.audit.domain.AuditArtifact.class);
    verify(auditEventWriter, times(3)).appendWithArtifact(events.capture(), artifacts.capture());
    List<String> types = events.getAllValues().stream().map(AuditEvent::eventType).toList();
    assertEquals(
        List.of("scan_accepted", "registry_lookup_requested", "registry_lookup_completed"), types);

    AuditEvent requested = events.getAllValues().get(1);
    assertEquals("civil_registry_request", artifacts.getAllValues().get(1).kind());
    assertEquals("application/json", artifacts.getAllValues().get(1).mediaType());
    assertEquals(new String(request), new String(artifacts.getAllValues().get(1).body()));
    assertEquals("{\"requestBytes\":" + request.length + "}", requested.payloadJson());
    assertFalse(
        requested.payloadJson().contains("NID-5"),
        "the national number never enters the permanently hash-chained payload_json");

    AuditEvent completed = events.getAllValues().get(2);
    assertEquals("civil_registry_response", artifacts.getAllValues().get(2).kind());
    assertEquals("application/json", artifacts.getAllValues().get(2).mediaType());
    assertEquals(new String(response), new String(artifacts.getAllValues().get(2).body()));
    assertEquals(requested.requestId(), completed.requestId(), "same requestId, one chain");
    assertEquals("system", completed.actorKind());
    assertEquals(
        true, completed.payloadJson().contains("\"state\":\"ok\""), completed.payloadJson());
    assertEquals(
        true, completed.payloadJson().contains("\"reason\":\"matched\""), completed.payloadJson());
    assertEquals(
        true, completed.payloadJson().contains("\"httpStatus\":200"), completed.payloadJson());
    assertEquals(
        true,
        completed.payloadJson().contains("\"responseBytes\":" + response.length),
        completed.payloadJson());
    assertFalse(completed.payloadJson().contains("NID-5"));

    verify(identityScanRepository)
        .insertRegistryResult(any(), eq("ok"), eq(NOW), eq(1), any(), eq("NID-5"));
    verify(identityScanRepository, never()).transitionToAwaitingRegistry(any(), any(), anyLong());
  }

  @Test
  void aMismatchIsNotFoundWithTheReturnedNumberStoredAndNoRecordFields() {
    // BL-030: the guard's evidence is kept even though the stranger's record is not.
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    ParsedEnrolmentResult parsed = passportResult("NID-6", List.of(image("id-1")));
    when(uqudoClient.verifyAndParse(any(), any(), any(), any())).thenReturn(parsed);
    when(uqudoClient.downloadImage(any(), any())).thenReturn(new byte[] {9});
    when(identityScanRepository.insertAcceptedCycle(any(), any())).thenReturn(UUID.randomUUID());
    byte[] response = "{\"IDENTITY_NUMBER\":\"NID-OTHER\"}".getBytes();
    when(civilRegistryClient.lookup("NID-6"))
        .thenReturn(
            RegistryLookup.notFound(
                RegistryLookup.IDENTITY_NUMBER_MISMATCH,
                "NID-OTHER",
                new RegistryExchange("{}".getBytes(), response, "application/json", 200)));

    ScanDisplayPayload payload =
        service.submitScan(PROFILE_ID, "sid", "nonce", DocumentTypes.PASSPORT, "jws");

    assertEquals(false, payload.registryReady());
    verify(identityScanRepository)
        .insertRegistryResult(
            any(),
            eq("not_found"),
            eq(NOW),
            eq(1),
            org.mockito.ArgumentMatchers.isNull(),
            eq("NID-OTHER"));
    verify(identityScanRepository).transitionToAwaitingRegistry(eq(PROFILE_ID), eq(NOW), anyLong());
    verify(identityScanRepository, never())
        .insertArtifactRef(
            any(),
            eq("portrait_registry"),
            any(),
            any(),
            any(),
            any(),
            anyLong(),
            any(),
            any(),
            any());
    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, times(3)).appendWithArtifact(events.capture(), any());
    AuditEvent completed = events.getAllValues().get(2);
    assertEquals("registry_lookup_completed", completed.eventType());
    assertEquals(
        true,
        completed.payloadJson().contains("\"reason\":\"identity_number_mismatch\""),
        completed.payloadJson());
    assertFalse(completed.payloadJson().contains("NID-OTHER"));
  }

  @Test
  void unreachableAfterARealAttemptAuditsTheRequestArtifactOnly() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    ParsedEnrolmentResult parsed = passportResult("NID-7", List.of(image("id-1")));
    when(uqudoClient.verifyAndParse(any(), any(), any(), any())).thenReturn(parsed);
    when(uqudoClient.downloadImage(any(), any())).thenReturn(new byte[] {9});
    when(identityScanRepository.insertAcceptedCycle(any(), any())).thenReturn(UUID.randomUUID());
    byte[] request = "{\"NID\":\"NID-7\"}".getBytes();
    when(civilRegistryClient.lookup("NID-7"))
        .thenThrow(
            new RegistryUnreachableException(
                "no response", new RegistryExchange(request, null, null, 0), new Exception()));

    ScanDisplayPayload payload =
        service.submitScan(PROFILE_ID, "sid", "nonce", DocumentTypes.PASSPORT, "jws");

    assertEquals(false, payload.registryReady());
    ArgumentCaptor<AuditEvent> withArtifact = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, times(2)).appendWithArtifact(withArtifact.capture(), any());
    assertEquals(
        List.of("scan_accepted", "registry_lookup_requested"),
        withArtifact.getAllValues().stream().map(AuditEvent::eventType).toList());
    ArgumentCaptor<AuditEvent> plain = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter).append(plain.capture());
    assertEquals("registry_lookup_completed", plain.getValue().eventType());
    assertEquals(
        true,
        plain.getValue().payloadJson().contains("\"reason\":\"no_response\""),
        plain.getValue().payloadJson());
    assertEquals(
        true, plain.getValue().payloadJson().contains("\"httpStatus\":null"), "nothing came back");
    verify(identityScanRepository)
        .insertRegistryResult(
            any(),
            eq("unreachable"),
            eq(NOW),
            eq(1),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull());
  }

  @Test
  void againstTheStubTheLookupEventCarriesNoArtifactAndNoReason() {
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(Optional.of(freshState()));
    ParsedEnrolmentResult parsed = passportResult("NID-8", List.of(image("id-1")));
    when(uqudoClient.verifyAndParse(any(), any(), any(), any())).thenReturn(parsed);
    when(uqudoClient.downloadImage(any(), any())).thenReturn(new byte[] {9});
    when(identityScanRepository.insertAcceptedCycle(any(), any())).thenReturn(UUID.randomUUID());
    // The default stub in the initializer: no record, no reason, no exchange.

    service.submitScan(PROFILE_ID, "sid", "nonce", DocumentTypes.PASSPORT, "jws");

    ArgumentCaptor<AuditEvent> withArtifact = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, times(1)).appendWithArtifact(withArtifact.capture(), any());
    assertEquals("scan_accepted", withArtifact.getValue().eventType());
    ArgumentCaptor<AuditEvent> plain = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter).append(plain.capture());
    assertEquals("registry_lookup_completed", plain.getValue().eventType());
    assertEquals(
        true,
        plain.getValue().payloadJson().contains("\"reason\":null"),
        plain.getValue().payloadJson());
  }

  @Test
  void aRetryWithARealExchangeWritesBothEventsAndStoresTheReturnedNumber() {
    UUID cycleId = UUID.randomUUID();
    when(identityScanRepository.lockAndGetScanState(PROFILE_ID))
        .thenReturn(
            Optional.of(new ScanState("awaiting_registry", false, 0, 0, 0, null, "s", "n", 0)));
    when(identityScanRepository.currentActiveRegistryContext(PROFILE_ID))
        .thenReturn(
            Optional.of(new ActiveRegistryContext(cycleId, "NID-9", "PASSPORT", "unreachable", 1)));
    byte[] request = "{\"NID\":\"NID-9\"}".getBytes();
    byte[] response = "{\"IDENTITY_NUMBER\":\"NID-9\"}".getBytes();
    when(civilRegistryClient.lookup("NID-9")).thenReturn(httpMatched("NID-9", request, response));

    ScanDisplayPayload payload = service.retryRegistryLookup(PROFILE_ID);

    assertEquals(true, payload.registryReady());
    verify(identityScanRepository)
        .updateRegistryResultOnRetry(eq(cycleId), eq("ok"), eq(NOW), eq(2), any(), eq("NID-9"));
    verify(identityScanRepository)
        .transitionBackToInProgressFromRegistry(eq(PROFILE_ID), eq(NOW), anyLong());
    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, times(2)).appendWithArtifact(events.capture(), any());
    assertEquals(
        List.of("registry_lookup_requested", "registry_lookup_completed"),
        events.getAllValues().stream().map(AuditEvent::eventType).toList());
    assertEquals(
        true,
        events.getAllValues().get(1).payloadJson().contains("\"retried\":true"),
        events.getAllValues().get(1).payloadJson());
  }

  private static ParsedImage image(String id) {
    return new ParsedImage(ParsedImage.DOC_FRONT, id, "sha256:deadbeef");
  }

  private static ParsedEnrolmentResult passportResult(
      String identityNumber, List<ParsedImage> images) {
    return new ParsedEnrolmentResult(
        UUID.randomUUID().toString(),
        "PASSPORT",
        null,
        identityNumber,
        "MRZ-" + identityNumber,
        true,
        "SDN",
        "m",
        null,
        null,
        null,
        null,
        null,
        "Arabic Name",
        "English Name",
        null,
        "Khartoum",
        null,
        null,
        null,
        images);
  }
}
