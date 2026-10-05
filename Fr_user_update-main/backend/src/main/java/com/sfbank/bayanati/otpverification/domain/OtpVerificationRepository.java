package com.sfbank.bayanati.otpverification.domain;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The one way application code reads and writes {@code app.profile_channel}'s verification columns
 * and {@code app.otp_challenge} for Stage 2 (docs/journeys/customer.md). A separate port from
 * {@code profile.domain.ProfileRepository} even though both touch tables that port also writes —
 * {@code ProfileRepository}'s own Javadoc says it is "deliberately narrow ... nothing scaffolded
 * for a later stage that does not exist yet"; Stage 2 gets its own port rather than growing that
 * one. The two exceptions are {@code app.profile.phone_lock_until}/{@code phone_lock_escalated},
 * which stay on {@code ProfileRepository} because that class already owns every write to {@code
 * app.profile} and Stage 1b's re-entry path (in {@code contactchannels}) needs to read them without
 * depending on this package.
 */
public interface OtpVerificationRepository {

  /** Empty when no {@code app.profile_channel} row exists for this profile/channel. */
  Optional<ChannelVerificationState> findChannelState(UUID profileId, MessageChannel channel);

  /**
   * The locking counterpart of {@link #findChannelState} — {@code SELECT ... FOR UPDATE}, so no
   * other transaction can change this channel's state until the caller's (already-open) transaction
   * commits or rolls back. Callers MUST already have established the row exists (via {@link
   * #findChannelState}) before calling this — {@code app.profile_channel} rows are never deleted,
   * so that is safe to assume once true.
   *
   * <p>Closes a race two concurrent requests against the same channel could otherwise hit: both
   * read a not-yet-locked, not-yet-capped state before either writes, both proceed past the gate,
   * and the second write then either violates {@code app.profile_channel}'s {@code CHECK}
   * constraints (wrong-attempt count past 5, resend count past 3) or — for {@link
   * com.sfbank.bayanati.otpverification.service.OtpVerificationService#resend} specifically — both
   * send a real, billed message before either write lands. Found by {@code @agent-reviewer}, S3-08.
   *
   * @throws IllegalStateException if the row does not exist — a caller bug, not a business outcome
   */
  ChannelVerificationState findChannelStateForUpdate(UUID profileId, MessageChannel channel);

  /**
   * The most recently issued challenge for this channel, regardless of whether it has since expired
   * — empty only if none was ever issued.
   */
  Optional<CurrentChallenge> findCurrentChallenge(UUID profileId, MessageChannel channel);

  /** Sets {@code consumed_at = now} on a successful verification. */
  void consumeChallenge(UUID challengeId, Instant now);

  /** Sets {@code state = 'verified', verified_at = now}. */
  void markVerified(UUID profileId, MessageChannel channel, Instant now);

  /**
   * {@code wrong_code_attempts = wrong_code_attempts + 1}, atomically, returning the new value.
   * Callers must only invoke this when the channel is not already locked — {@code
   * wrong_code_attempts}'s own {@code CHECK (<= 5)} (V0007) would otherwise reject the write.
   */
  int incrementWrongAttempts(UUID profileId, MessageChannel channel);

  /** Sets {@code locked_at = now} — the channel "cannot be retried" from this point on. */
  void lockChannel(UUID profileId, MessageChannel channel, Instant now);

  /**
   * "Both phone channels locked, or the only selected phone channel locked" (customer.md Stage 2):
   * true when every non-{@code declined} row among {@code sms}/{@code whatsapp} is locked and none
   * is verified, and at least one such row exists.
   */
  boolean allSelectedPhoneChannelsLocked(UUID profileId);

  /** {@code expires_at = LEAST(expires_at, now)} for every unexpired challenge on this channel. */
  void invalidateChallengesForChannel(UUID profileId, MessageChannel channel, Instant now);

  /**
   * Inserts a fresh {@code app.otp_challenge} row for a resend.
   *
   * @param resendIndex the channel's {@code resend_count} value *before* this resend (0, 1 or 2) —
   *     V0007's own comment: "0,1,2 -> 30s/60s/120s unlock"
   */
  void insertChallenge(
      UUID challengeId,
      UUID profileId,
      MessageChannel channel,
      byte[] codeHash,
      byte[] salt,
      Instant issuedAt,
      Instant expiresAt,
      int resendIndex);

  /** {@code resend_count = resend_count + 1}, atomically, returning the new value. */
  int incrementResendCount(UUID profileId, MessageChannel channel);

  /** {@code MAX(issued_at)} over every challenge ever issued for this profile/channel. */
  Instant mostRecentIssuedAt(UUID profileId, MessageChannel channel);
}
