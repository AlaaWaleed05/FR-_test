package com.sfbank.bayanati.submission.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.notification.domain.NotificationOutboxRepository;
import com.sfbank.bayanati.profile.domain.ChannelState;
import com.sfbank.bayanati.profile.domain.ProfileRepository;
import com.sfbank.bayanati.submission.domain.JourneyStage;
import com.sfbank.bayanati.submission.domain.NotInFinalStagesException;
import com.sfbank.bayanati.submission.domain.ProfileNotEligibleException;
import com.sfbank.bayanati.submission.domain.SubmissionRepository;
import com.sfbank.bayanati.submission.domain.SubmissionState;
import com.sfbank.bayanati.submission.domain.UnknownProfileException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * S5-13's Stage 10-12 resume pointer: the stage-selection logic, and the proof that reading it
 * mutates nothing.
 */
class SubmissionServiceJourneyPointerTest {

  private static final Instant BLOCKED_UNTIL = Instant.parse("2026-09-06T09:15:00Z");

  private SubmissionRepository submissionRepository;
  private ProfileRepository profileRepository;
  private NotificationOutboxRepository notificationOutboxRepository;
  private AuditEventWriter auditEventWriter;
  private SubmissionService service;

  @BeforeEach
  void setUp() {
    submissionRepository = mock(SubmissionRepository.class);
    profileRepository = mock(ProfileRepository.class);
    notificationOutboxRepository = mock(NotificationOutboxRepository.class);
    auditEventWriter = mock(AuditEventWriter.class);
    service =
        new SubmissionService(
            submissionRepository,
            profileRepository,
            notificationOutboxRepository,
            auditEventWriter,
            Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneOffset.UTC),
            mock(PlatformTransactionManager.class));
  }

  private void givenState(SubmissionState state) {
    when(submissionRepository.checkState(any())).thenReturn(Optional.of(state));
  }

  private static SubmissionState state(
      String status,
      boolean terminal,
      String referenceNumber,
      boolean facePassed,
      boolean hasSignature,
      Instant livenessBlockedUntil,
      boolean hasAcceptedCycle) {
    return new SubmissionState(
        status,
        terminal,
        referenceNumber,
        facePassed,
        hasSignature,
        livenessBlockedUntil,
        hasAcceptedCycle);
  }

  private void givenVerifiedChannels(MessageChannel... channels) {
    Map<MessageChannel, ChannelState> states = new LinkedHashMap<>();
    states.put(MessageChannel.SMS, ChannelState.UNVERIFIED);
    states.put(MessageChannel.WHATSAPP, ChannelState.DECLINED);
    states.put(MessageChannel.EMAIL, ChannelState.UNVERIFIED);
    for (MessageChannel channel : channels) {
      states.put(channel, ChannelState.VERIFIED);
    }
    when(profileRepository.currentChannelStates(any())).thenReturn(states);
  }

  // ---- Stage selection -------------------------------------------------------------------------

  @Test
  void livenessNotPassedAnswersLiveness() {
    givenState(state("in_progress", false, null, false, false, null, true));

    JourneyPointer pointer = service.currentJourneyPointer(UUID.randomUUID());

    assertEquals(JourneyStage.LIVENESS, pointer.stage());
    assertNull(pointer.blockedUntil());
  }

  @Test
  void blockedLivenessAnswersBlockedWithTheDeadline() {
    givenState(state("blocked_liveness", false, null, false, false, BLOCKED_UNTIL, true));

    JourneyPointer pointer = service.currentJourneyPointer(UUID.randomUUID());

    assertEquals(JourneyStage.LIVENESS_BLOCKED, pointer.stage());
    assertEquals(BLOCKED_UNTIL, pointer.blockedUntil());
  }

  /**
   * {@code liveness_blocked_until} is set independently of the status transition, so the pointer
   * must survive the column being null rather than assuming the two always agree.
   */
  @Test
  void blockedLivenessWithNoDeadlineStillAnswersBlocked() {
    givenState(state("blocked_liveness", false, null, false, false, null, true));

    JourneyPointer pointer = service.currentJourneyPointer(UUID.randomUUID());

    assertEquals(JourneyStage.LIVENESS_BLOCKED, pointer.stage());
    assertNull(pointer.blockedUntil());
  }

  /**
   * An ELAPSED block is resumable, and the pointer must say so. {@code LivenessService} clears the
   * block on the next token request rather than on a timer, so the status column still reads {@code
   * blocked_liveness} well after the deadline has passed. Answering {@code LIVENESS_BLOCKED} on
   * status alone would show a block screen carrying an already-past deadline to a customer whose
   * {@code /liveness/token} call would have succeeded — and leave the app to re-derive the expiry
   * from its own clock, the client-side state inference R-052 exists to prevent.
   */
  @Test
  void anElapsedLivenessBlockFallsThroughToTheStageItWasBlockingOn() {
    Instant elapsed = Instant.parse("2026-09-05T11:59:59Z"); // one second before the fixed clock
    givenState(state("blocked_liveness", false, null, false, false, elapsed, true));

    JourneyPointer pointer = service.currentJourneyPointer(UUID.randomUUID());

    assertEquals(JourneyStage.LIVENESS, pointer.stage());
    assertNull(pointer.blockedUntil());
  }

  /**
   * The boundary, pinned to {@code LivenessService}'s own comparison: {@code isAfter} is strict, so
   * a deadline exactly at {@code now} has lapsed and the customer may proceed. Asserted here so
   * that if either side's comparison is ever loosened to {@code >=}, the two stop agreeing loudly
   * rather than silently.
   */
  @Test
  void aBlockExpiringExactlyNowHasLapsed() {
    givenState(
        state(
            "blocked_liveness",
            false,
            null,
            false,
            false,
            Instant.parse("2026-09-05T12:00:00Z"),
            true));

    assertEquals(JourneyStage.LIVENESS, service.currentJourneyPointer(UUID.randomUUID()).stage());
  }

  @Test
  void passedLivenessWithoutASignatureAnswersSignature() {
    givenState(state("in_progress", false, null, true, false, null, true));

    assertEquals(JourneyStage.SIGNATURE, service.currentJourneyPointer(UUID.randomUUID()).stage());
  }

  @Test
  void passedLivenessWithASignatureAnswersSubmit() {
    givenState(state("in_progress", false, null, true, true, null, true));

    assertEquals(JourneyStage.SUBMIT, service.currentJourneyPointer(UUID.randomUUID()).stage());
  }

  /**
   * The answer the whole endpoint exists for. Note it is reached DESPITE {@code submitted} being a
   * terminal status — the sibling Stage 9 read refuses terminal profiles, and copying that here
   * would dead-end the customer whose app died before the confirmation screen rendered.
   */
  @Test
  void submittedAnswersSubmittedWithReferenceNumberAndVerifiedChannels() {
    givenState(state("submitted", true, "FRU-000000042", true, true, null, true));
    givenVerifiedChannels(MessageChannel.SMS, MessageChannel.EMAIL);

    JourneyPointer pointer = service.currentJourneyPointer(UUID.randomUUID());

    assertEquals(JourneyStage.SUBMITTED, pointer.stage());
    assertEquals("FRU-000000042", pointer.referenceNumber());
    assertEquals(Set.of(MessageChannel.SMS, MessageChannel.EMAIL), pointer.verifiedChannels());
  }

  @Test
  void approvedAndRejectedAlsoAnswerRatherThanRefuse() {
    givenVerifiedChannels(MessageChannel.SMS);

    givenState(state("approved", true, "FRU-000000043", true, true, null, true));
    assertEquals(JourneyStage.APPROVED, service.currentJourneyPointer(UUID.randomUUID()).stage());

    givenState(state("rejected", true, "FRU-000000044", true, true, null, true));
    assertEquals(JourneyStage.REJECTED, service.currentJourneyPointer(UUID.randomUUID()).stage());
  }

  /** Only VERIFIED channels carry the decision — the same filter {@code submit} applies. */
  @Test
  void unverifiedAndDeclinedChannelsAreExcluded() {
    givenState(state("submitted", true, "FRU-000000045", true, true, null, true));
    givenVerifiedChannels(MessageChannel.SMS);

    assertEquals(
        Set.of(MessageChannel.SMS),
        service.currentJourneyPointer(UUID.randomUUID()).verifiedChannels());
  }

  // ---- Refusals --------------------------------------------------------------------------------

  @Test
  void unknownProfileThrows() {
    when(submissionRepository.checkState(any())).thenReturn(Optional.empty());

    assertThrows(
        UnknownProfileException.class, () -> service.currentJourneyPointer(UUID.randomUUID()));
  }

  @Test
  void terminatedRegistryMismatchIsRefusedAsTerminal() {
    givenState(state("terminated_registry_mismatch", true, null, false, false, null, true));

    ProfileNotEligibleException thrown =
        assertThrows(
            ProfileNotEligibleException.class,
            () -> service.currentJourneyPointer(UUID.randomUUID()));
    assertTrue(thrown.terminal(), "maps to PROFILE_TERMINAL, not STATE_CONFLICT");
  }

  /**
   * Fails closed. {@code blocked_scan} and {@code awaiting_registry} are Stage 8/9, and {@code
   * abandoned} re-enters through Stage 1a — none of them gets a Stage 10-12 answer invented for it.
   * This is also where BL-051's scan half is deliberately left open.
   */
  @Test
  void stagesEightAndNineAndAbandonedAreRefused() {
    for (String status : new String[] {"blocked_scan", "awaiting_registry", "abandoned"}) {
      givenState(state(status, false, null, false, false, null, true));
      assertThrows(
          NotInFinalStagesException.class,
          () -> service.currentJourneyPointer(UUID.randomUUID()),
          status + " must not receive a stage 10-12 answer");
    }
  }

  @Test
  void noAcceptedIdentityCycleIsRefused() {
    givenState(state("in_progress", false, null, false, false, null, false));

    assertThrows(
        NotInFinalStagesException.class, () -> service.currentJourneyPointer(UUID.randomUUID()));
  }

  // ---- The read really is a read ---------------------------------------------------------------

  /**
   * The S5-11 proof shape. Named individually rather than as a blanket {@code
   * verifyNoMoreInteractions} so a future writer added to this service fails the test by name
   * rather than silently slipping through: the read must never draw a reference number, never lock,
   * never write a status, never enqueue a notification and never append an audit event.
   *
   * <p>It also cannot mint a face session: unlike {@code /api/v1/liveness/token}, which calls
   * {@code createFaceSession} and writes {@code pending_face_session_id}, this service holds no
   * Uqudo client at all. The database-level proof of that is in {@code SubmissionIntegrationTest}.
   */
  @Test
  void readingThePointerMutatesNothing() {
    givenState(state("submitted", true, "FRU-000000042", true, true, null, true));
    givenVerifiedChannels(MessageChannel.SMS);

    service.currentJourneyPointer(UUID.randomUUID());

    verify(submissionRepository, never()).lockAndCheckState(any());
    verify(submissionRepository, never()).nextReferenceNumber();
    verify(submissionRepository, never()).submit(any(), anyString(), any(), anyLong());
    verify(submissionRepository, never()).recordReferenceVersions(any(), any());
    verify(submissionRepository, never()).usedReferenceListVersions(any());
    // profileRepository cannot take verifyNoInteractions -- the read legitimately calls
    // currentChannelStates -- so its one writer is named explicitly. touchLastActivity is the
    // easiest write to add here by accident, since every other stage 10-12 path calls it, and it
    // updates only last_activity_at without bumping row_version: it would reset the 30-day
    // abandonment and 90-day purge clocks on every poll while staying invisible to a
    // column-comparison snapshot.
    verify(profileRepository, never()).touchLastActivity(any(), any());
    verifyNoInteractions(notificationOutboxRepository);
    verifyNoInteractions(auditEventWriter);
  }
}
