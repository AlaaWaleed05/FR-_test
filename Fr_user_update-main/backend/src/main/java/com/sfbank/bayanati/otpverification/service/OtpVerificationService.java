package com.sfbank.bayanati.otpverification.service;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.audit.domain.CanonicalJson;
import com.sfbank.bayanati.contactchannels.domain.DestinationMasker;
import com.sfbank.bayanati.contactchannels.domain.OtpCodeGenerator;
import com.sfbank.bayanati.contactchannels.domain.OtpMessageRenderer;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageDispatchResult;
import com.sfbank.bayanati.messaging.domain.MessagePayload;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.Urgency;
import com.sfbank.bayanati.otpverification.domain.ChannelVerificationState;
import com.sfbank.bayanati.otpverification.domain.CurrentChallenge;
import com.sfbank.bayanati.otpverification.domain.EmailCorrectionNotApplicableException;
import com.sfbank.bayanati.otpverification.domain.OtpVerificationPolicy;
import com.sfbank.bayanati.otpverification.domain.OtpVerificationRepository;
import com.sfbank.bayanati.otpverification.domain.ResendOutcome;
import com.sfbank.bayanati.otpverification.domain.UnknownOtpChannelException;
import com.sfbank.bayanati.otpverification.domain.VerificationOutcome;
import com.sfbank.bayanati.profile.domain.ChannelState;
import com.sfbank.bayanati.profile.domain.ContactSnapshot;
import com.sfbank.bayanati.profile.domain.ProfileRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Journey Stage 2 — OTP verification, resend, per-channel and session lockout
 * (docs/journeys/customer.md). See the S3-08 session report for the full R-044 reasoning this class
 * implements.
 *
 * <p><strong>Verification never logs, returns or audits a submitted or stored code.</strong> Only
 * {@link VerificationOutcome}, the channel and a count ever leave {@link #verify}.
 *
 * <p><strong>The session-terminal phone lock (customer.md: "Both phone channels locked, or the only
 * selected phone channel locked -> terminal ... 15 minutes, escalating to 1 hour") gates the next
 * Stage 1b re-entry, not this class's own endpoints.</strong> A channel that reaches its
 * wrong-attempt limit "cannot be retried" (V0007) regardless of the session lock, so nothing in
 * {@link #verify}/{@link #resend} needs to consult {@code app.profile.phone_lock_until} — {@code
 * ContactChannelsService} does, on re-entry.
 */
@Service
public class OtpVerificationService {

  private static final Logger log = LoggerFactory.getLogger(OtpVerificationService.class);

  static final String CHAIN_KIND = "profile";
  static final String EVENT_VERIFICATION_ATTEMPTED = "otp_verification_attempted";
  static final String EVENT_CHANNEL_LOCKED = "otp_channel_locked";
  static final String EVENT_SESSION_LOCKED = "otp_session_locked";
  static final String EVENT_RESEND_ISSUED = "otp_resend_issued";
  static final String EVENT_NOTIFICATION_DISPATCHED = "notification_dispatched";
  static final String EVENT_EMAIL_CORRECTED = "email_address_corrected";

  private static final int OTP_VALIDITY_MINUTES =
      (int) OtpVerificationPolicy.CODE_VALIDITY.toMinutes();

  private final OtpVerificationRepository otpVerificationRepository;
  private final ProfileRepository profileRepository;
  private final AuditEventWriter auditEventWriter;
  private final MessageSender messageSender;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  public OtpVerificationService(
      OtpVerificationRepository otpVerificationRepository,
      ProfileRepository profileRepository,
      AuditEventWriter auditEventWriter,
      MessageSender messageSender,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.otpVerificationRepository = otpVerificationRepository;
    this.profileRepository = profileRepository;
    this.auditEventWriter = auditEventWriter;
    this.messageSender = messageSender;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * @throws UnknownOtpChannelException if this profile has no challenged (non-{@code declined}) row
   *     for {@code channel}
   */
  public VerificationAttemptResult verify(UUID profileId, MessageChannel channel, String code) {
    Instant now = clock.instant();
    UUID requestId = UUID.randomUUID();
    // Cheap, non-locking existence/declined check outside any transaction -- a stale read here
    // only matters if the row is deleted between now and the lock below, and profile_channel rows
    // are never deleted.
    loadChannelStateOrThrow(profileId, channel);

    VerificationAttemptResult[] holder = new VerificationAttemptResult[1];
    transactionTemplate.executeWithoutResult(
        status -> {
          // Re-read under SELECT ... FOR UPDATE as the transaction's first statement: two
          // concurrent verify() calls on the same channel must not both pass the locked/verified
          // gate on a stale read and both try to write past app.profile_channel's CHECK
          // constraints. Found by @agent-reviewer, S3-08.
          ChannelVerificationState state =
              otpVerificationRepository.findChannelStateForUpdate(profileId, channel);
          holder[0] = doVerify(profileId, channel, code, state, requestId, now);
        });
    return holder[0];
  }

  private VerificationAttemptResult doVerify(
      UUID profileId,
      MessageChannel channel,
      String code,
      ChannelVerificationState state,
      UUID requestId,
      Instant now) {
    if (state.state() == ChannelState.VERIFIED) {
      // Idempotent: an already-verified channel reports VERIFIED for any well-formed code without
      // re-checking it -- the backend already owns and trusts this state, and nothing is written,
      // so no privilege is gained by skipping the check.
      return finish(
          profileId,
          channel,
          requestId,
          VerificationOutcome.VERIFIED,
          ChannelState.VERIFIED,
          state.wrongCodeAttempts(),
          now);
    }
    if (state.locked()) {
      return finish(
          profileId,
          channel,
          requestId,
          VerificationOutcome.CHANNEL_LOCKED,
          ChannelState.UNVERIFIED,
          state.wrongCodeAttempts(),
          now);
    }

    Optional<CurrentChallenge> challenge =
        otpVerificationRepository.findCurrentChallenge(profileId, channel);
    if (challenge.isEmpty() || challenge.get().expired(now)) {
      return finish(
          profileId,
          channel,
          requestId,
          VerificationOutcome.EXPIRED,
          ChannelState.UNVERIFIED,
          state.wrongCodeAttempts(),
          now);
    }

    CurrentChallenge current = challenge.get();
    if (OtpCodeGenerator.matches(code, current.salt(), current.codeHash())) {
      otpVerificationRepository.consumeChallenge(current.challengeId(), now);
      otpVerificationRepository.markVerified(profileId, channel, now);
      return finish(
          profileId,
          channel,
          requestId,
          VerificationOutcome.VERIFIED,
          ChannelState.VERIFIED,
          state.wrongCodeAttempts(),
          now);
    }

    int newWrongAttempts = otpVerificationRepository.incrementWrongAttempts(profileId, channel);
    if (newWrongAttempts >= OtpVerificationPolicy.WRONG_ATTEMPT_LIMIT) {
      otpVerificationRepository.lockChannel(profileId, channel, now);
      auditEventWriter.append(channelLockedEvent(profileId, requestId, channel, newWrongAttempts));
      // customer.md: "Email locking has no effect on progression" -- only a PHONE channel locking
      // can trigger the session-wide block. Without this guard, locking email after both phone
      // channels were already locked would re-fire applySessionLock and escalate a 15-minute block
      // to 1 hour for no reason connected to phone verification at all. Found by @agent-reviewer,
      // S3-08.
      if (channel != MessageChannel.EMAIL) {
        // Lock the shared app.profile row before evaluating "every selected phone channel is
        // locked": this transaction only holds a row lock on the ONE profile_channel row it just
        // locked (channel above), not on the other phone channel. Two verify() calls locking SMS
        // and WhatsApp in parallel would otherwise both read each other's channel as still
        // unlocked under READ COMMITTED and neither would ever write the session lock -- both
        // phones end locked with phone_lock_until left NULL, defeating the whole point of R-044's
        // re-entry throttle. Serialising here forces the second call to see the first's
        // already-committed lock. Found by @agent-reviewer's second pass, S3-08.
        profileRepository.lockProfileRow(profileId);
        if (otpVerificationRepository.allSelectedPhoneChannelsLocked(profileId)) {
          applySessionLock(profileId, requestId, now);
        }
      }
    }
    return finish(
        profileId,
        channel,
        requestId,
        VerificationOutcome.WRONG_CODE,
        ChannelState.UNVERIFIED,
        newWrongAttempts,
        now);
  }

  /**
   * customer.md: "15 minutes, escalating to 1 hour on a repeat in the same session" — {@code
   * phone_lock_escalated} (V0037) is read before this call decides which duration applies, then
   * unconditionally set {@code true}, so it never un-escalates.
   */
  private void applySessionLock(UUID profileId, UUID requestId, Instant now) {
    // Defensive: the caller only reaches here on the phone channel that just newly completed
    // "every selected phone channel locked", which can happen at most once, but this guard costs
    // one read and removes any doubt about a duplicate call re-escalating an already-fresh lock.
    Instant existing = profileRepository.currentPhoneLockUntil(profileId).orElse(null);
    if (existing != null && existing.isAfter(now)) {
      return;
    }
    boolean escalated = profileRepository.phoneLockEscalated(profileId);
    Duration duration =
        escalated
            ? OtpVerificationPolicy.SESSION_LOCK_REPEAT
            : OtpVerificationPolicy.SESSION_LOCK_FIRST;
    Instant until = now.plus(duration);
    profileRepository.applyPhoneSessionLock(profileId, until);
    auditEventWriter.append(sessionLockedEvent(profileId, requestId, until, escalated));
  }

  private VerificationAttemptResult finish(
      UUID profileId,
      MessageChannel channel,
      UUID requestId,
      VerificationOutcome outcome,
      ChannelState resultingState,
      int attemptNumber,
      Instant now) {
    auditEventWriter.append(
        verificationAttemptedEvent(profileId, requestId, channel, outcome, attemptNumber));
    Instant blockedUntil = activePhoneLock(profileId, now);
    return new VerificationAttemptResult(channel, outcome, resultingState, blockedUntil);
  }

  private Instant activePhoneLock(UUID profileId, Instant now) {
    return profileRepository
        .currentPhoneLockUntil(profileId)
        .filter(until -> until.isAfter(now))
        .orElse(null);
  }

  /**
   * @param correctedEmailAddress non-{@code null} only when the customer edited a mistyped email
   *     address in place before tapping resend (customer.md Stage 2 "Corrections": "Email address
   *     mistyped → editable in place on its row, then resend", S4-06/BL-012). Must be {@code null}
   *     for every channel but {@link MessageChannel#EMAIL} — changing the phone number is
   *     deliberately out of scope here; the journey routes that back to Stage 1b instead, because
   *     it changes what the session authenticates against.
   * @throws UnknownOtpChannelException if this profile has no challenged (non-{@code declined}) row
   *     for {@code channel}
   * @throws EmailCorrectionNotApplicableException if {@code correctedEmailAddress} is supplied for
   *     any channel but {@code EMAIL}
   */
  public ResendAttemptResult resend(
      UUID profileId, MessageChannel channel, String correctedEmailAddress) {
    Instant now = clock.instant();
    UUID requestId = UUID.randomUUID();
    loadChannelStateOrThrow(profileId, channel);
    if (correctedEmailAddress != null && channel != MessageChannel.EMAIL) {
      throw new EmailCorrectionNotApplicableException(
          "correctedEmailAddress is only applicable to the email channel, not "
              + channel.wireValue());
    }

    // Phase 1: reserve the resend slot under a row lock, entirely before any send -- this is what
    // makes the CAP race-free: two concurrent resend() calls serialise on this transaction, and
    // the second one sees the first's already-incremented resend_count and correctly refuses
    // CAP_EXHAUSTED *before* ever calling MessageSender.send(), instead of both sending a real,
    // billed message and one of them then losing its write to a CHECK-constraint rollback. Found
    // by @agent-reviewer, S3-08 -- the exact "sent, but the record of having sent it rolled back"
    // failure BL-009 was filed about, reachable here without this fix.
    //
    // The DELAY check is not fully race-free by the same mechanism: mostRecentIssuedAt only
    // advances once Phase 2 inserts the new challenge row, after this lock is released, so two
    // requests arriving within the same short window can both pass the delay gate against the
    // same stale issued_at. The CAP still bounds total spend either way -- flagged by
    // @agent-reviewer's second pass as a residual, accepted gap, not closed here.
    Reservation[] holder = new Reservation[1];
    transactionTemplate.executeWithoutResult(
        status ->
            holder[0] = reserveResend(profileId, channel, correctedEmailAddress, requestId, now));
    Reservation reservation = holder[0];

    // Computed AFTER Phase 1 commits, not before: an email correction is applied inside that same
    // transaction (see reserveResend), so a refused-but-corrected resend (e.g. CAP_EXHAUSTED)
    // must still report the corrected address, not a stale pre-correction mask.
    String masked = maskedDestination(profileId, channel);

    if (reservation.outcome() != null) {
      return new ResendAttemptResult(
          channel, reservation.outcome(), masked, reservation.secondsRemaining());
    }

    ContactSnapshot contact = profileRepository.currentContactDetails(profileId);
    String destination =
        channel == MessageChannel.EMAIL ? contact.emailAddress() : contact.phoneNumber();

    OtpCodeGenerator.GeneratedOtp otp = OtpCodeGenerator.generate();
    MessagePayload payload = renderPayload(channel, otp.code());
    UUID challengeId = UUID.randomUUID();
    OutboundMessage message =
        new OutboundMessage(
            challengeId, channel, destination, payload, Urgency.INTERACTIVE, profileId.toString());
    MessageDispatchResult sendResult = sendCatchingSenderFailure(message);

    Instant expiresAt = now.plus(OtpVerificationPolicy.CODE_VALIDITY);

    // Phase 2: persist the fresh challenge and audit the send. The slot is already reserved, so
    // this phase cannot fail on the cap/delay -- only on genuine infrastructure trouble. If it
    // fails after the send above already went out, the record of that send is lost along with the
    // reservation (resend_count stays consumed with no otp_challenge/audit row to show for it) --
    // the same accepted, fail-closed shape ContactChannelsService's own send-then-persist path
    // already carries; not a new gap introduced here, and not compensated for further.
    transactionTemplate.executeWithoutResult(
        status -> {
          otpVerificationRepository.invalidateChallengesForChannel(profileId, channel, now);
          otpVerificationRepository.insertChallenge(
              challengeId,
              profileId,
              channel,
              otp.codeHash(),
              otp.salt(),
              now,
              expiresAt,
              reservation.resendIndexUsed());
          auditEventWriter.append(
              resendIssuedEvent(profileId, requestId, channel, reservation.resendIndexUsed() + 1));
          auditEventWriter.append(notificationDispatchedEvent(profileId, requestId, sendResult));
        });

    return new ResendAttemptResult(channel, ResendOutcome.ISSUED, masked, -1);
  }

  /**
   * @param outcome non-{@code null} means the reservation was refused and nothing was reserved;
   *     {@code null} means it succeeded and {@link #resendIndexUsed} is the pre-increment {@code
   *     resend_count} the caller must use as the new challenge's {@code resend_index}
   */
  private record Reservation(ResendOutcome outcome, long secondsRemaining, int resendIndexUsed) {}

  /**
   * Must run inside the transaction that holds {@link
   * OtpVerificationRepository#findChannelStateForUpdate}'s row lock.
   *
   * <p>An email correction (S4-06/BL-012), when supplied, is applied here — inside this same lock,
   * before the reservation outcome is decided, and regardless of what that outcome turns out to be.
   * Editing the row and tapping resend are two logically separate actions (customer.md: "editable
   * in place on its row, <em>then</em> resend"), so a throttled or capped resend must not silently
   * discard a typo fix the customer already made. Skipped, silently, if the channel is already
   * {@code VERIFIED} — defensive only: the real app shows the edit control solely on an unverified
   * row, so correcting an already-verified channel's destination would break the "verified proves
   * this destination" invariant with no legitimate path to reach it.
   */
  private Reservation reserveResend(
      UUID profileId,
      MessageChannel channel,
      String correctedEmailAddress,
      UUID requestId,
      Instant now) {
    ChannelVerificationState state =
        otpVerificationRepository.findChannelStateForUpdate(profileId, channel);

    if (correctedEmailAddress != null && state.state() != ChannelState.VERIFIED) {
      applyEmailCorrectionIfChanged(profileId, requestId, correctedEmailAddress, now);
    }

    if (state.state() == ChannelState.VERIFIED) {
      return new Reservation(ResendOutcome.ALREADY_VERIFIED, -1, -1);
    }
    if (state.locked()) {
      return new Reservation(ResendOutcome.CHANNEL_LOCKED, -1, -1);
    }
    if (state.resendCount() >= OtpVerificationPolicy.RESEND_LIMIT) {
      return new Reservation(ResendOutcome.CAP_EXHAUSTED, -1, -1);
    }

    // null is a legal return (no challenge ever issued) per the port's contract, though
    // unreachable via the current flow -- every non-declined channel gets its first challenge at
    // Stage 1b before this method can ever be called. Treated as "no prior issuance, delay already
    // elapsed" rather than left to NPE if that invariant ever changes.
    Instant lastIssuedAt = otpVerificationRepository.mostRecentIssuedAt(profileId, channel);
    Duration requiredDelay = OtpVerificationPolicy.RESEND_DELAYS[state.resendCount()];
    Instant allowedAt = lastIssuedAt == null ? now : lastIssuedAt.plus(requiredDelay);
    if (now.isBefore(allowedAt)) {
      long millisRemaining = Duration.between(now, allowedAt).toMillis();
      long secondsRemaining = (millisRemaining + 999) / 1000;
      return new Reservation(ResendOutcome.TOO_SOON, secondsRemaining, -1);
    }

    int resendIndexUsed = state.resendCount();
    otpVerificationRepository.incrementResendCount(profileId, channel);
    return new Reservation(null, -1, resendIndexUsed);
  }

  /**
   * No-op (no write, no audit) when the supplied address equals what is already stored — avoids a
   * spurious {@code email_address_corrected} event on a resubmission that changed nothing.
   *
   * <p><strong>Invalidates the prior email challenge itself</strong>, here, rather than relying on
   * {@link #resend}'s Phase 2 {@code invalidateChallengesForChannel} call — that call only runs
   * when the reservation succeeds (a {@code TOO_SOON}/{@code CAP_EXHAUSTED}/{@code CHANNEL_LOCKED}
   * refusal returns before Phase 2 ever executes). Without this, a correction applied ahead of a
   * refused resend would leave the OLD address's still-unexpired challenge live: entering that code
   * would then mark the email channel {@code VERIFIED} against an address that was never itself
   * challenged — exactly the "a single code sent to several channels proves none of them" failure
   * CLAUDE.md's OTP rule exists to prevent. Found by {@code @agent-reviewer}, S4-06.
   */
  private void applyEmailCorrectionIfChanged(
      UUID profileId, UUID requestId, String newEmailAddress, Instant now) {
    ContactSnapshot previous = profileRepository.currentContactDetails(profileId);
    if (newEmailAddress.equals(previous.emailAddress())) {
      return;
    }
    profileRepository.updateEmailAddress(profileId, newEmailAddress, now);
    otpVerificationRepository.invalidateChallengesForChannel(profileId, MessageChannel.EMAIL, now);
    auditEventWriter.append(
        emailCorrectedEvent(profileId, requestId, previous.emailAddress(), newEmailAddress));
  }

  private static AuditEvent emailCorrectedEvent(
      UUID profileId, UUID requestId, String previousEmailAddress, String newEmailAddress) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("previousEmailAddress", previousEmailAddress);
    payload.put("newEmailAddress", newEmailAddress);
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        EVENT_EMAIL_CORRECTED,
        "customer",
        null,
        profileId,
        profileId,
        requestId,
        CanonicalJson.object(payload));
  }

  private String maskedDestination(UUID profileId, MessageChannel channel) {
    ContactSnapshot contact = profileRepository.currentContactDetails(profileId);
    return channel == MessageChannel.EMAIL
        ? DestinationMasker.maskEmail(contact.emailAddress())
        : DestinationMasker.maskPhone(contact.phoneNumber());
  }

  /**
   * @throws UnknownOtpChannelException if no row exists, or the row is {@link
   *     ChannelState#DECLINED} — neither has anything to verify or resend
   */
  private ChannelVerificationState loadChannelStateOrThrow(UUID profileId, MessageChannel channel) {
    ChannelVerificationState state =
        otpVerificationRepository
            .findChannelState(profileId, channel)
            .orElseThrow(
                () ->
                    new UnknownOtpChannelException(
                        "no channel "
                            + channel.wireValue()
                            + " was offered for profile "
                            + profileId));
    if (state.state() == ChannelState.DECLINED) {
      throw new UnknownOtpChannelException(
          "channel " + channel.wireValue() + " was declined for profile " + profileId);
    }
    return state;
  }

  /**
   * Same exception-safety shape as {@code ContactChannelsService#sendCatchingSenderFailure} —
   * duplicated rather than shared, to avoid touching that already-reviewed class for a ~15-line
   * helper. {@code getMessage()} never reaches the audit trail; only the exception's class name
   * does, since a future adapter's exception could quote a request containing the OTP code.
   */
  private MessageDispatchResult sendCatchingSenderFailure(OutboundMessage message) {
    try {
      return messageSender.send(message);
    } catch (RuntimeException sendFailed) {
      log.error(
          "MessageSender.send threw for channel {} on resend -- recorded as a failed attempt",
          message.channel(),
          sendFailed);
      return MessageDispatchResult.permanentFailure(
          message, "internal", "SEND_THREW", sendFailed.getClass().getName());
    }
  }

  private static MessagePayload renderPayload(MessageChannel channel, String code) {
    return switch (channel) {
      case SMS -> OtpMessageRenderer.renderSms(code, OTP_VALIDITY_MINUTES);
      case WHATSAPP -> OtpMessageRenderer.renderWhatsApp(code);
      case EMAIL -> OtpMessageRenderer.renderEmail(code, OTP_VALIDITY_MINUTES);
    };
  }

  private static AuditEvent verificationAttemptedEvent(
      UUID profileId,
      UUID requestId,
      MessageChannel channel,
      VerificationOutcome outcome,
      int attemptNumber) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("channel", channel.wireValue());
    payload.put("outcome", outcome.name());
    payload.put("attemptNumber", attemptNumber);
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        EVENT_VERIFICATION_ATTEMPTED,
        "customer",
        null,
        profileId,
        profileId,
        requestId,
        CanonicalJson.object(payload));
  }

  private static AuditEvent channelLockedEvent(
      UUID profileId, UUID requestId, MessageChannel channel, int wrongCodeAttempts) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("channel", channel.wireValue());
    payload.put("wrongCodeAttempts", wrongCodeAttempts);
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        EVENT_CHANNEL_LOCKED,
        "system",
        null,
        profileId,
        profileId,
        requestId,
        CanonicalJson.object(payload));
  }

  private static AuditEvent sessionLockedEvent(
      UUID profileId, UUID requestId, Instant until, boolean escalated) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("untilIso", until.toString());
    payload.put("escalated", escalated);
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        EVENT_SESSION_LOCKED,
        "system",
        null,
        profileId,
        profileId,
        requestId,
        CanonicalJson.object(payload));
  }

  private static AuditEvent resendIssuedEvent(
      UUID profileId, UUID requestId, MessageChannel channel, int resendNumber) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("channel", channel.wireValue());
    payload.put("resendNumber", resendNumber);
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        EVENT_RESEND_ISSUED,
        "customer",
        null,
        profileId,
        profileId,
        requestId,
        CanonicalJson.object(payload));
  }

  private static AuditEvent notificationDispatchedEvent(
      UUID profileId, UUID requestId, MessageDispatchResult result) {
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        EVENT_NOTIFICATION_DISPATCHED,
        "system",
        null,
        profileId,
        profileId,
        requestId,
        CanonicalJson.object(result.canonicalPayload()));
  }
}
