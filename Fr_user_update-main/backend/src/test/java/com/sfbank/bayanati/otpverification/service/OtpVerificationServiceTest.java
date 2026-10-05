package com.sfbank.bayanati.otpverification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.contactchannels.domain.OtpCodeGenerator;
import com.sfbank.bayanati.contactchannels.domain.OtpCodeGenerator.GeneratedOtp;
import com.sfbank.bayanati.messaging.domain.DispatchOutcome;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageDispatchResult;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.otpverification.domain.ChannelVerificationState;
import com.sfbank.bayanati.otpverification.domain.CurrentChallenge;
import com.sfbank.bayanati.otpverification.domain.EmailCorrectionNotApplicableException;
import com.sfbank.bayanati.otpverification.domain.OtpVerificationRepository;
import com.sfbank.bayanati.otpverification.domain.ResendOutcome;
import com.sfbank.bayanati.otpverification.domain.UnknownOtpChannelException;
import com.sfbank.bayanati.otpverification.domain.VerificationOutcome;
import com.sfbank.bayanati.profile.domain.ChannelState;
import com.sfbank.bayanati.profile.domain.ContactSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * {@code OtpVerificationService}'s orchestration, with every collaborator faked or mocked — no
 * Spring context, no database, no real clock (CLAUDE.md's business-logic testability rule). Same
 * shape as {@code ContactChannelsServiceTest}.
 */
class OtpVerificationServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
  private static final UUID PROFILE_ID = UUID.randomUUID();

  private final OtpVerificationRepository otpVerificationRepository =
      mock(OtpVerificationRepository.class);
  private final com.sfbank.bayanati.profile.domain.ProfileRepository profileRepository =
      mock(com.sfbank.bayanati.profile.domain.ProfileRepository.class);
  private final AuditEventWriter auditEventWriter = mock(AuditEventWriter.class);
  private final MessageSender messageSender = mock(MessageSender.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  {
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(transactionStatus);
    when(auditEventWriter.append(any())).thenReturn(1L);
    when(profileRepository.currentPhoneLockUntil(any())).thenReturn(Optional.empty());
    when(profileRepository.currentContactDetails(any()))
        .thenReturn(new ContactSnapshot("+249900004821", "ahmed@example.invalid"));
    when(messageSender.send(any()))
        .thenAnswer(
            invocation -> {
              OutboundMessage message = invocation.getArgument(0);
              return new MessageDispatchResult(
                  message.messageId().toString(),
                  message.channel().wireValue(),
                  DispatchOutcome.ACCEPTED,
                  "stub",
                  "provider-id",
                  "0",
                  "ok",
                  message.channel() == MessageChannel.SMS ? 2 : -1,
                  NOW.toString(),
                  5L);
            });
  }

  private OtpVerificationService service() {
    return new OtpVerificationService(
        otpVerificationRepository,
        profileRepository,
        auditEventWriter,
        messageSender,
        clock,
        transactionManager);
  }

  private static ChannelVerificationState unverified(int wrongAttempts, int resendCount) {
    return new ChannelVerificationState(ChannelState.UNVERIFIED, null, wrongAttempts, resendCount);
  }

  private static ChannelVerificationState locked(int wrongAttempts) {
    return new ChannelVerificationState(
        ChannelState.UNVERIFIED, NOW.minusSeconds(10), wrongAttempts, 0);
  }

  /**
   * Stubs both the non-locking existence check ({@code findChannelState}, read once before any
   * transaction opens) and the locking read ({@code findChannelStateForUpdate}, read as the first
   * statement inside the transaction) to the same value — {@code verify}/{@code resend} consult
   * both in sequence (S3-08 review fix: the second read closes a race the first read alone cannot).
   */
  private void stubState(MessageChannel channel, ChannelVerificationState state) {
    when(otpVerificationRepository.findChannelState(PROFILE_ID, channel))
        .thenReturn(Optional.of(state));
    when(otpVerificationRepository.findChannelStateForUpdate(PROFILE_ID, channel))
        .thenReturn(state);
  }

  // --- verify() ----------------------------------------------------------------------------

  @Test
  void unknownChannelThrows() {
    when(otpVerificationRepository.findChannelState(PROFILE_ID, MessageChannel.SMS))
        .thenReturn(Optional.empty());

    assertThrows(
        UnknownOtpChannelException.class,
        () -> service().verify(PROFILE_ID, MessageChannel.SMS, "123456"));
  }

  @Test
  void aDeclinedChannelThrows() {
    when(otpVerificationRepository.findChannelState(PROFILE_ID, MessageChannel.WHATSAPP))
        .thenReturn(Optional.of(new ChannelVerificationState(ChannelState.DECLINED, null, 0, 0)));

    assertThrows(
        UnknownOtpChannelException.class,
        () -> service().verify(PROFILE_ID, MessageChannel.WHATSAPP, "123456"));
  }

  @Test
  void aCorrectCodeVerifiesOnlyItsOwnChannel() {
    GeneratedOtp otp = OtpCodeGenerator.generate();
    UUID challengeId = UUID.randomUUID();
    stubState(MessageChannel.SMS, unverified(0, 0));
    when(otpVerificationRepository.findCurrentChallenge(PROFILE_ID, MessageChannel.SMS))
        .thenReturn(
            Optional.of(
                new CurrentChallenge(
                    challengeId,
                    otp.codeHash(),
                    otp.salt(),
                    NOW.minusSeconds(30),
                    NOW.plusSeconds(270))));

    VerificationAttemptResult result = service().verify(PROFILE_ID, MessageChannel.SMS, otp.code());

    assertEquals(VerificationOutcome.VERIFIED, result.outcome());
    assertEquals(ChannelState.VERIFIED, result.channelState());
    verify(otpVerificationRepository).consumeChallenge(challengeId, NOW);
    verify(otpVerificationRepository).markVerified(PROFILE_ID, MessageChannel.SMS, NOW);
    verify(otpVerificationRepository, never()).incrementWrongAttempts(any(), any());
    // Never touches WhatsApp/email.
    verify(otpVerificationRepository, never())
        .markVerified(any(), eq(MessageChannel.WHATSAPP), any());
    verify(otpVerificationRepository, never()).markVerified(any(), eq(MessageChannel.EMAIL), any());
  }

  @Test
  void aWrongCodeIncrementsAndDoesNotLockBeforeTheLimit() {
    GeneratedOtp otp = OtpCodeGenerator.generate();
    stubState(MessageChannel.SMS, unverified(2, 0));
    when(otpVerificationRepository.findCurrentChallenge(PROFILE_ID, MessageChannel.SMS))
        .thenReturn(
            Optional.of(
                new CurrentChallenge(
                    UUID.randomUUID(),
                    otp.codeHash(),
                    otp.salt(),
                    NOW.minusSeconds(30),
                    NOW.plusSeconds(270))));
    when(otpVerificationRepository.incrementWrongAttempts(PROFILE_ID, MessageChannel.SMS))
        .thenReturn(3);

    VerificationAttemptResult result = service().verify(PROFILE_ID, MessageChannel.SMS, "000000");

    assertEquals(VerificationOutcome.WRONG_CODE, result.outcome());
    assertEquals(ChannelState.UNVERIFIED, result.channelState());
    verify(otpVerificationRepository, never()).lockChannel(any(), any(), any());
  }

  @Test
  void theFifthWrongAttemptLocksTheChannel() {
    GeneratedOtp otp = OtpCodeGenerator.generate();
    stubState(MessageChannel.SMS, unverified(4, 0));
    when(otpVerificationRepository.findCurrentChallenge(PROFILE_ID, MessageChannel.SMS))
        .thenReturn(
            Optional.of(
                new CurrentChallenge(
                    UUID.randomUUID(),
                    otp.codeHash(),
                    otp.salt(),
                    NOW.minusSeconds(30),
                    NOW.plusSeconds(270))));
    when(otpVerificationRepository.incrementWrongAttempts(PROFILE_ID, MessageChannel.SMS))
        .thenReturn(5);
    when(otpVerificationRepository.allSelectedPhoneChannelsLocked(PROFILE_ID)).thenReturn(false);

    VerificationAttemptResult result = service().verify(PROFILE_ID, MessageChannel.SMS, "wrong0");

    assertEquals(VerificationOutcome.WRONG_CODE, result.outcome());
    verify(otpVerificationRepository).lockChannel(PROFILE_ID, MessageChannel.SMS, NOW);
    // The profile row is still locked before the (false) evaluation -- serialising against a
    // concurrent lock on the other phone channel is needed regardless of what this evaluation
    // finds.
    verify(profileRepository).lockProfileRow(PROFILE_ID);
    verify(profileRepository, never()).applyPhoneSessionLock(any(), any());
  }

  @Test
  void aSixthAttemptAgainstALockedChannelIsRejectedAsLockedNotWrong() {
    stubState(MessageChannel.SMS, locked(5));

    VerificationAttemptResult result = service().verify(PROFILE_ID, MessageChannel.SMS, "123456");

    assertEquals(VerificationOutcome.CHANNEL_LOCKED, result.outcome());
    verify(otpVerificationRepository, never()).incrementWrongAttempts(any(), any());
    verify(otpVerificationRepository, never()).findCurrentChallenge(any(), any());
  }

  @Test
  void anExpiredChallengeIsRejectedAsExpiredAndDoesNotLock() {
    GeneratedOtp otp = OtpCodeGenerator.generate();
    stubState(MessageChannel.SMS, unverified(0, 0));
    when(otpVerificationRepository.findCurrentChallenge(PROFILE_ID, MessageChannel.SMS))
        .thenReturn(
            Optional.of(
                new CurrentChallenge(
                    UUID.randomUUID(),
                    otp.codeHash(),
                    otp.salt(),
                    NOW.minusSeconds(600),
                    NOW.minusSeconds(1))));

    VerificationAttemptResult result = service().verify(PROFILE_ID, MessageChannel.SMS, otp.code());

    assertEquals(VerificationOutcome.EXPIRED, result.outcome());
    verify(otpVerificationRepository, never()).incrementWrongAttempts(any(), any());
    verify(otpVerificationRepository, never()).lockChannel(any(), any(), any());
  }

  @Test
  void aNoLongerExistingChallengeIsRejectedAsExpired() {
    stubState(MessageChannel.SMS, unverified(0, 0));
    when(otpVerificationRepository.findCurrentChallenge(PROFILE_ID, MessageChannel.SMS))
        .thenReturn(Optional.empty());

    VerificationAttemptResult result = service().verify(PROFILE_ID, MessageChannel.SMS, "123456");

    assertEquals(VerificationOutcome.EXPIRED, result.outcome());
  }

  @Test
  void alreadyVerifiedIsIdempotent() {
    stubState(MessageChannel.SMS, new ChannelVerificationState(ChannelState.VERIFIED, null, 1, 0));

    VerificationAttemptResult result = service().verify(PROFILE_ID, MessageChannel.SMS, "123456");

    assertEquals(VerificationOutcome.VERIFIED, result.outcome());
    verify(otpVerificationRepository, never()).findCurrentChallenge(any(), any());
    verify(otpVerificationRepository, never()).markVerified(any(), any(), any());
  }

  @Test
  void bothPhoneChannelsLockingTriggersTheFirstFifteenMinuteBlock() {
    GeneratedOtp otp = OtpCodeGenerator.generate();
    stubState(MessageChannel.WHATSAPP, unverified(4, 0));
    when(otpVerificationRepository.findCurrentChallenge(PROFILE_ID, MessageChannel.WHATSAPP))
        .thenReturn(
            Optional.of(
                new CurrentChallenge(
                    UUID.randomUUID(),
                    otp.codeHash(),
                    otp.salt(),
                    NOW.minusSeconds(30),
                    NOW.plusSeconds(270))));
    when(otpVerificationRepository.incrementWrongAttempts(PROFILE_ID, MessageChannel.WHATSAPP))
        .thenReturn(5);
    when(otpVerificationRepository.allSelectedPhoneChannelsLocked(PROFILE_ID)).thenReturn(true);
    when(profileRepository.phoneLockEscalated(PROFILE_ID)).thenReturn(false);

    VerificationAttemptResult result =
        service().verify(PROFILE_ID, MessageChannel.WHATSAPP, "wrong0");

    ArgumentCaptor<Instant> until = ArgumentCaptor.forClass(Instant.class);
    verify(profileRepository).applyPhoneSessionLock(eq(PROFILE_ID), until.capture());
    assertEquals(NOW.plus(java.time.Duration.ofMinutes(15)), until.getValue());
    verify(profileRepository).lockProfileRow(PROFILE_ID);
    // The mock does not make applyPhoneSessionLock's write visible to a later
    // currentPhoneLockUntil read -- that round trip is proven live in
    // OtpVerificationIntegrationTest. Here, the write call's own argument is the assertion.
    assertNull(result.sessionBlockedUntil());
  }

  @Test
  void aRepeatSessionLockEscalatesToOneHour() {
    GeneratedOtp otp = OtpCodeGenerator.generate();
    stubState(MessageChannel.SMS, unverified(4, 0));
    when(otpVerificationRepository.findCurrentChallenge(PROFILE_ID, MessageChannel.SMS))
        .thenReturn(
            Optional.of(
                new CurrentChallenge(
                    UUID.randomUUID(),
                    otp.codeHash(),
                    otp.salt(),
                    NOW.minusSeconds(30),
                    NOW.plusSeconds(270))));
    when(otpVerificationRepository.incrementWrongAttempts(PROFILE_ID, MessageChannel.SMS))
        .thenReturn(5);
    when(otpVerificationRepository.allSelectedPhoneChannelsLocked(PROFILE_ID)).thenReturn(true);
    when(profileRepository.phoneLockEscalated(PROFILE_ID)).thenReturn(true); // already escalated

    service().verify(PROFILE_ID, MessageChannel.SMS, "wrong0");

    ArgumentCaptor<Instant> until = ArgumentCaptor.forClass(Instant.class);
    verify(profileRepository).applyPhoneSessionLock(eq(PROFILE_ID), until.capture());
    assertEquals(NOW.plus(java.time.Duration.ofHours(1)), until.getValue());
  }

  @Test
  void anEmailChannelLockingNeverTriggersOrEscalatesTheSessionLock() {
    // customer.md: "Email locking has no effect on progression." Even if both phone channels
    // already locked earlier (allSelectedPhoneChannelsLocked would read true from the DB in that
    // case), locking EMAIL must never re-evaluate or re-escalate the session lock -- the channel
    // type itself gates the check, before allSelectedPhoneChannelsLocked is even asked.
    GeneratedOtp otp = OtpCodeGenerator.generate();
    stubState(MessageChannel.EMAIL, unverified(4, 0));
    when(otpVerificationRepository.findCurrentChallenge(PROFILE_ID, MessageChannel.EMAIL))
        .thenReturn(
            Optional.of(
                new CurrentChallenge(
                    UUID.randomUUID(),
                    otp.codeHash(),
                    otp.salt(),
                    NOW.minusSeconds(30),
                    NOW.plusSeconds(270))));
    when(otpVerificationRepository.incrementWrongAttempts(PROFILE_ID, MessageChannel.EMAIL))
        .thenReturn(5);

    VerificationAttemptResult result = service().verify(PROFILE_ID, MessageChannel.EMAIL, "wrong0");

    assertEquals(VerificationOutcome.WRONG_CODE, result.outcome());
    verify(otpVerificationRepository).lockChannel(PROFILE_ID, MessageChannel.EMAIL, NOW);
    verify(otpVerificationRepository, never()).allSelectedPhoneChannelsLocked(any());
    verify(profileRepository, never()).applyPhoneSessionLock(any(), any());
    // The cross-channel serialisation lock is only needed to evaluate the phone-channel
    // condition, so it must never be taken on an email lock either.
    verify(profileRepository, never()).lockProfileRow(any());
  }

  @Test
  void noAuditPayloadEverContainsTheSubmittedCodeOrItsHash() {
    GeneratedOtp otp = OtpCodeGenerator.generate();
    stubState(MessageChannel.SMS, unverified(0, 0));
    when(otpVerificationRepository.findCurrentChallenge(PROFILE_ID, MessageChannel.SMS))
        .thenReturn(
            Optional.of(
                new CurrentChallenge(
                    UUID.randomUUID(),
                    otp.codeHash(),
                    otp.salt(),
                    NOW.minusSeconds(30),
                    NOW.plusSeconds(270))));

    service().verify(PROFILE_ID, MessageChannel.SMS, otp.code());

    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, times(1)).append(events.capture());
    String payload = events.getValue().payloadJson();
    assertFalse(payload.contains(otp.code()), payload);
    String hashHex = java.util.HexFormat.of().formatHex(otp.codeHash());
    assertFalse(payload.toLowerCase().contains(hashHex), payload);
  }

  // --- resend() ------------------------------------------------------------------------------

  @Test
  void resendUnknownChannelThrows() {
    when(otpVerificationRepository.findChannelState(PROFILE_ID, MessageChannel.EMAIL))
        .thenReturn(Optional.empty());

    assertThrows(
        UnknownOtpChannelException.class,
        () -> service().resend(PROFILE_ID, MessageChannel.EMAIL, null));
  }

  @Test
  void resendOnALockedChannelIsRefusedWithNoSend() {
    stubState(MessageChannel.SMS, locked(5));

    ResendAttemptResult result = service().resend(PROFILE_ID, MessageChannel.SMS, null);

    assertEquals(ResendOutcome.CHANNEL_LOCKED, result.outcome());
    verify(messageSender, never()).send(any());
  }

  @Test
  void resendOnAnAlreadyVerifiedChannelIsRefused() {
    stubState(MessageChannel.SMS, new ChannelVerificationState(ChannelState.VERIFIED, null, 0, 0));

    ResendAttemptResult result = service().resend(PROFILE_ID, MessageChannel.SMS, null);

    assertEquals(ResendOutcome.ALREADY_VERIFIED, result.outcome());
    verify(messageSender, never()).send(any());
  }

  @Test
  void theFourthResendIsRefusedAsCapExhausted() {
    stubState(MessageChannel.SMS, unverified(0, 3));

    ResendAttemptResult result = service().resend(PROFILE_ID, MessageChannel.SMS, null);

    assertEquals(ResendOutcome.CAP_EXHAUSTED, result.outcome());
    verify(messageSender, never()).send(any());
  }

  @Test
  void aResendBeforeTheThirtySecondDelayIsRefusedAsTooSoon() {
    stubState(MessageChannel.SMS, unverified(0, 0));
    when(otpVerificationRepository.mostRecentIssuedAt(PROFILE_ID, MessageChannel.SMS))
        .thenReturn(NOW.minusSeconds(10)); // only 10s elapsed, 30s required before the 1st resend

    ResendAttemptResult result = service().resend(PROFILE_ID, MessageChannel.SMS, null);

    assertEquals(ResendOutcome.TOO_SOON, result.outcome());
    assertTrue(result.secondsUntilAllowed() > 0, "expected a positive countdown");
    verify(messageSender, never()).send(any());
  }

  @Test
  void aResendAfterTheDelayIssuesAFreshChallengeAndIncrementsResendCount() {
    stubState(MessageChannel.SMS, unverified(0, 0));
    when(otpVerificationRepository.mostRecentIssuedAt(PROFILE_ID, MessageChannel.SMS))
        .thenReturn(NOW.minusSeconds(31));

    ResendAttemptResult result = service().resend(PROFILE_ID, MessageChannel.SMS, null);

    assertEquals(ResendOutcome.ISSUED, result.outcome());
    // Phase 1 (reservation) increments resend_count before any send.
    verify(otpVerificationRepository).incrementResendCount(PROFILE_ID, MessageChannel.SMS);
    verify(otpVerificationRepository)
        .invalidateChallengesForChannel(PROFILE_ID, MessageChannel.SMS, NOW);
    ArgumentCaptor<Integer> resendIndex = ArgumentCaptor.forClass(Integer.class);
    verify(otpVerificationRepository)
        .insertChallenge(
            any(),
            eq(PROFILE_ID),
            eq(MessageChannel.SMS),
            any(),
            any(),
            eq(NOW),
            any(),
            resendIndex.capture());
    assertEquals(0, resendIndex.getValue(), "the pre-increment resend_count (0) is the index used");
    verify(messageSender).send(any());
  }

  @Test
  void theSecondResendRequiresTheSixtySecondDelay() {
    stubState(MessageChannel.SMS, unverified(0, 1));
    when(otpVerificationRepository.mostRecentIssuedAt(PROFILE_ID, MessageChannel.SMS))
        .thenReturn(NOW.minusSeconds(45)); // < 60s required before the 2nd resend

    ResendAttemptResult result = service().resend(PROFILE_ID, MessageChannel.SMS, null);

    assertEquals(ResendOutcome.TOO_SOON, result.outcome());
  }

  @Test
  void theThirdResendRequiresTheOneHundredTwentySecondDelay() {
    stubState(MessageChannel.SMS, unverified(0, 2));
    when(otpVerificationRepository.mostRecentIssuedAt(PROFILE_ID, MessageChannel.SMS))
        .thenReturn(NOW.minusSeconds(90)); // < 120s required before the 3rd resend

    ResendAttemptResult result = service().resend(PROFILE_ID, MessageChannel.SMS, null);

    assertEquals(ResendOutcome.TOO_SOON, result.outcome());
  }

  @Test
  void aSenderExceptionOnResendIsRecordedAsAFailedAttemptNotPropagated() {
    stubState(MessageChannel.SMS, unverified(0, 0));
    when(otpVerificationRepository.mostRecentIssuedAt(PROFILE_ID, MessageChannel.SMS))
        .thenReturn(NOW.minusSeconds(31));
    // doThrow(...).when(...), not when(...).thenThrow(...): the latter would re-invoke the
    // existing thenAnswer stub (registered in the constructor) as part of registering this one --
    // any() passes a null placeholder during that re-invocation, NPEing inside that lambda.
    org.mockito.Mockito.doThrow(new RuntimeException("provider unreachable"))
        .when(messageSender)
        .send(any());

    ResendAttemptResult result = service().resend(PROFILE_ID, MessageChannel.SMS, null);

    // The reservation already committed (resend_count incremented) before the throw -- a send
    // attempt was genuinely made, so it still consumes the slot, matching the pre-existing
    // ContactChannelsService#sendCatchingSenderFailure contract this duplicates.
    assertEquals(ResendOutcome.ISSUED, result.outcome());
    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, times(2)).append(events.capture());
    AuditEvent dispatched =
        events.getAllValues().stream()
            .filter(e -> e.eventType().equals("notification_dispatched"))
            .findFirst()
            .orElseThrow();
    assertFalse(
        dispatched.payloadJson().contains("provider unreachable"), dispatched.payloadJson());
    assertTrue(dispatched.payloadJson().contains("RuntimeException"), dispatched.payloadJson());
  }

  @Test
  void resendReservationRunsBeforeAnySendSoACapHitNeverSends() {
    // Direct proof of the S3-08 review fix: findChannelStateForUpdate (the reservation read) is
    // what decides CAP_EXHAUSTED here, not the pre-transaction findChannelState -- if the two ever
    // disagreed (the race the fix closes), this pins which one the outcome actually follows.
    when(otpVerificationRepository.findChannelState(PROFILE_ID, MessageChannel.SMS))
        .thenReturn(Optional.of(unverified(0, 0))); // stale: looks eligible
    when(otpVerificationRepository.findChannelStateForUpdate(PROFILE_ID, MessageChannel.SMS))
        .thenReturn(unverified(0, 3)); // fresh, under lock: cap already hit

    ResendAttemptResult result = service().resend(PROFILE_ID, MessageChannel.SMS, null);

    assertEquals(ResendOutcome.CAP_EXHAUSTED, result.outcome());
    verify(messageSender, never()).send(any());
  }

  // --- resend() email correction (S4-06, BL-012) --------------------------------------------

  @Test
  void aCorrectedEmailOnANonEmailChannelIsRejected() {
    stubState(MessageChannel.SMS, unverified(0, 0));

    assertThrows(
        EmailCorrectionNotApplicableException.class,
        () -> service().resend(PROFILE_ID, MessageChannel.SMS, "fixed@example.com"));
    verify(profileRepository, never()).updateEmailAddress(any(), any(), any());
  }

  @Test
  void aCorrectedEmailUpdatesTheStoredAddressAndSendsTheFreshCodeThere() {
    stubState(MessageChannel.EMAIL, unverified(0, 0));
    when(otpVerificationRepository.mostRecentIssuedAt(PROFILE_ID, MessageChannel.EMAIL))
        .thenReturn(NOW.minusSeconds(31));
    when(profileRepository.currentContactDetails(PROFILE_ID))
        .thenReturn(new ContactSnapshot("+249900004821", "typo@example.invalid"))
        .thenReturn(new ContactSnapshot("+249900004821", "fixed@example.com"));

    ResendAttemptResult result =
        service().resend(PROFILE_ID, MessageChannel.EMAIL, "fixed@example.com");

    assertEquals(ResendOutcome.ISSUED, result.outcome());
    verify(profileRepository).updateEmailAddress(PROFILE_ID, "fixed@example.com", NOW);
    ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
    verify(messageSender).send(sent.capture());
    assertEquals("fixed@example.com", sent.getValue().destination());
  }

  @Test
  void aCorrectedEmailIsAppliedEvenWhenTheReservationIsThenRefused() {
    // customer.md: "editable in place on its row, THEN resend" -- editing and resending are two
    // separate actions. A throttled/capped resend must not silently discard a typo fix the
    // customer already made.
    stubState(MessageChannel.EMAIL, unverified(0, 3)); // CAP_EXHAUSTED
    when(profileRepository.currentContactDetails(PROFILE_ID))
        .thenReturn(new ContactSnapshot("+249900004821", "typo@example.invalid"))
        .thenReturn(new ContactSnapshot("+249900004821", "fixed@example.com"));

    ResendAttemptResult result =
        service().resend(PROFILE_ID, MessageChannel.EMAIL, "fixed@example.com");

    assertEquals(ResendOutcome.CAP_EXHAUSTED, result.outcome());
    verify(profileRepository).updateEmailAddress(PROFILE_ID, "fixed@example.com", NOW);
    verify(messageSender, never()).send(any());
    // The moved maskedDestination() read (after Phase 1 commits) must reflect the correction,
    // not the stale pre-correction address, even on a refused reservation.
    assertEquals("f•••@example.com", result.maskedDestination());
  }

  @Test
  void aCorrectedEmailOnARefusedResendStillInvalidatesTheOldChallenge() {
    // BLOCKER found by @agent-reviewer, S4-06: resend's Phase 2 (which normally invalidates the
    // prior challenge) never runs on a refused reservation (TOO_SOON/CAP_EXHAUSTED/CHANNEL_LOCKED)
    // -- without invalidating the old challenge inside applyEmailCorrectionIfChanged itself, the
    // OLD address's still-unexpired code could later verify the email channel against an address
    // that was never actually challenged. Revert-tested: removing the
    // invalidateChallengesForChannel call from applyEmailCorrectionIfChanged makes this fail.
    stubState(MessageChannel.EMAIL, unverified(0, 3)); // CAP_EXHAUSTED -- refused
    when(profileRepository.currentContactDetails(PROFILE_ID))
        .thenReturn(new ContactSnapshot("+249900004821", "typo@example.invalid"))
        .thenReturn(new ContactSnapshot("+249900004821", "fixed@example.com"));

    ResendAttemptResult result =
        service().resend(PROFILE_ID, MessageChannel.EMAIL, "fixed@example.com");

    assertEquals(ResendOutcome.CAP_EXHAUSTED, result.outcome());
    verify(otpVerificationRepository)
        .invalidateChallengesForChannel(PROFILE_ID, MessageChannel.EMAIL, NOW);
  }

  @Test
  void aCorrectedEmailOnAnAlreadyVerifiedChannelIsSilentlyIgnored() {
    // Defensive only -- the real app shows the edit control solely on an unverified row, so this
    // path only guards against a direct API call, not a real customer scenario.
    stubState(
        MessageChannel.EMAIL, new ChannelVerificationState(ChannelState.VERIFIED, null, 0, 0));

    ResendAttemptResult result =
        service().resend(PROFILE_ID, MessageChannel.EMAIL, "fixed@example.com");

    assertEquals(ResendOutcome.ALREADY_VERIFIED, result.outcome());
    verify(profileRepository, never()).updateEmailAddress(any(), any(), any());
    verify(auditEventWriter, never()).append(any());
  }

  @Test
  void aCorrectionIdenticalToTheStoredAddressWritesNothingAndAuditsNothing() {
    // Revert-tested per CLAUDE.md: with the no-op guard removed, this would write an identical
    // value and append a spurious email_address_corrected event even though nothing changed.
    stubState(MessageChannel.EMAIL, unverified(0, 0));
    when(otpVerificationRepository.mostRecentIssuedAt(PROFILE_ID, MessageChannel.EMAIL))
        .thenReturn(NOW.minusSeconds(31));
    when(profileRepository.currentContactDetails(PROFILE_ID))
        .thenReturn(new ContactSnapshot("+249900004821", "ahmed@example.invalid"));

    service().resend(PROFILE_ID, MessageChannel.EMAIL, "ahmed@example.invalid");

    verify(profileRepository, never()).updateEmailAddress(any(), any(), any());
    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, times(2)).append(events.capture()); // resend issued + dispatched only
    assertTrue(
        events.getAllValues().stream()
            .noneMatch(e -> e.eventType().equals("email_address_corrected")),
        events.getAllValues().toString());
  }

  @Test
  void aCorrectedEmailAppendsAnEmailAddressCorrectedAuditEventWithBothValues() {
    stubState(MessageChannel.EMAIL, unverified(0, 0));
    when(otpVerificationRepository.mostRecentIssuedAt(PROFILE_ID, MessageChannel.EMAIL))
        .thenReturn(NOW.minusSeconds(31));
    when(profileRepository.currentContactDetails(PROFILE_ID))
        .thenReturn(new ContactSnapshot("+249900004821", "typo@example.invalid"))
        .thenReturn(new ContactSnapshot("+249900004821", "fixed@example.com"));

    service().resend(PROFILE_ID, MessageChannel.EMAIL, "fixed@example.com");

    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, times(3)).append(events.capture());
    AuditEvent corrected =
        events.getAllValues().stream()
            .filter(e -> e.eventType().equals("email_address_corrected"))
            .findFirst()
            .orElseThrow();
    assertTrue(
        corrected.payloadJson().contains("\"previousEmailAddress\":\"typo@example.invalid\""));
    assertTrue(corrected.payloadJson().contains("\"newEmailAddress\":\"fixed@example.com\""));
  }
}
