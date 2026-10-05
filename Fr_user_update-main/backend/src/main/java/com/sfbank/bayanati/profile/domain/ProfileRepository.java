package com.sfbank.bayanati.profile.domain;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The one way application code writes {@code app.profile} and the tables Stage 1b populates
 * alongside it. A port, mirroring {@code notification.domain.NotificationOutboxRepository}'s shape:
 * everything a caller needs done, nothing about how it is done.
 *
 * <p>Deliberately narrow — Stage 1b's five writes, nothing scaffolded for a later stage that does
 * not exist yet (CLAUDE.md: "do not scaffold packages for features that do not exist yet" applies
 * equally to methods on a port that does exist). Split into single-purpose methods rather than one
 * "create everything" call because the caller must interleave them with an audit write it owns (the
 * profile's first status-history row needs an {@code audit_event_id} the caller — not this port —
 * obtains from {@code AuditEventWriter}).
 */
public interface ProfileRepository {

  /**
   * Ensures the audit chain this profile will write to exists, via {@code
   * audit.ensure_profile_chain} (V0036). Idempotent — safe to call before every audit write on a
   * profile's chain, not only the first.
   */
  void ensureAuditChain(UUID profileId);

  /**
   * Inserts {@code app.profile} (status {@code in_progress}) and its first {@code
   * app.profile_status_history} row ({@code from_status=NULL, to_status='in_progress'}) together —
   * both must land in the same transaction the deferred {@code app.profile_status_requires_history}
   * constraint trigger checks at commit (docs/components/persistence.md).
   *
   * @param auditEventId the {@code audit_event_id} of the {@code session_created} event the caller
   *     already wrote via {@code AuditEventWriter} — the history row's {@code NOT NULL} foreign key
   *     onto it
   */
  void insertProfile(
      UUID profileId, String accountNumber, Instant now, long auditEventId);

  /**
   * Inserts {@code app.profile_customer_data}, populating only the two fields Stage 1b collects
   * ({@code phone_number}, {@code email_address}). Every other column is left {@code NULL} — later
   * stages populate them; nothing here invents a value for a field this stage never asked about.
   *
   * @param emailAddress {@code null} when the customer supplied none
   */
  void insertContactDetails(UUID profileId, String phoneNumber, String emailAddress, Instant now);

  /**
   * Overwrites {@code app.profile_customer_data.phone_number}/{@code email_address} on a Stage 1b
   * re-entry (S3-07) — customer.md: "The fresh OTP verification at 1b overwrites the recorded
   * channel states entirely", and the destination fields are what those channels verify against.
   *
   * @param emailAddress {@code null} when this submission supplied none
   */
  void updateContactDetails(UUID profileId, String phoneNumber, String emailAddress, Instant now);

  /**
   * Overwrites only {@code app.profile_customer_data.email_address} (S4-06, BL-012) — narrower than
   * {@link #updateContactDetails}, which also requires a phone number and is Stage-1b
   * re-entry-specific. Used by Stage 2's OTP resend when the customer corrects a mistyped email
   * address in place before resending (customer.md Stage 2 "Corrections": "Email address mistyped →
   * editable in place on its row, then resend").
   */
  void updateEmailAddress(UUID profileId, String emailAddress, Instant now);

  /**
   * Inserts or replaces one {@code app.profile_channel} row ({@code INSERT ... ON CONFLICT
   * (profile_id, channel) DO UPDATE}). Used by both a brand-new profile's first channels (never
   * conflicts) and a Stage 1b re-entry's replaced ones (S3-07) — on conflict, {@code verified_at},
   * {@code locked_at} and both attempt counters reset to their fresh-challenge defaults, since a
   * re-entry starts a new verification cycle for every channel it touches.
   */
  void upsertChannel(UUID profileId, MessageChannel channel, ChannelState state);

  /**
   * Declines (never deletes — {@code app.profile_channel} grants {@code fru_app} no {@code DELETE})
   * every {@code app.profile_channel} row for this profile whose channel is not in {@code keep}.
   * Only meaningful on a Stage 1b re-entry (S3-07) where email was supplied before but is absent
   * from this submission — SMS and WhatsApp always appear in {@code keep}. A no-op (0 rows
   * affected) for a channel with no prior row.
   */
  void declineChannelsNotIn(UUID profileId, Set<MessageChannel> keep);

  /**
   * Inserts one {@code app.otp_challenge} row, {@code resend_index=0} (the first send, before any
   * resend).
   */
  void insertOtpChallenge(
      UUID challengeId,
      UUID profileId,
      MessageChannel channel,
      byte[] codeHash,
      byte[] salt,
      Instant issuedAt,
      Instant expiresAt);

  /**
   * Invalidates every {@code app.otp_challenge} row for this profile by setting {@code expires_at =
   * LEAST(expires_at, now)} (S3-07, BL-009) — never a {@code DELETE}, which the table's grants
   * don't permit and which would destroy the evidence that a code was ever issued. A code from an
   * abandoned Stage 1b attempt can then never verify, because it is already past its (now earlier)
   * expiry.
   */
  void invalidateOtpChallenges(UUID profileId, Instant now);

  /**
   * The one profile already on record for this account, if any — {@code profile_one_per_account}
   * ({@code UNIQUE (account_number)} since V0061, BL-032) guarantees at most one. Keyed on the
   * account number alone: the account is the customer's identity bank-wide and is what the
   * core-banking check keys on (OQ-024); the branch the customer selected is descriptive data on
   * the profile and never part of this lookup. Backs both Stage 1a's profile-existence branch and
   * Stage 1b's re-entry/rejection decision (S3-07, BL-006/BL-009).
   */
  Optional<ExistingProfile> findExisting(String accountNumber);

  /**
   * Overwrites {@code app.profile.branch_code} with the branch the customer selected on a Stage 1b
   * re-entry (S3-07) — since V0061 (BL-032) the branch is descriptive data, not part of the
   * profile's identity, so the profile records the latest selection rather than the first. No
   * {@code row_version} bump, the same posture as {@link #touchLastActivity}: this is not a status
   * or identity change. The {@code session_reentered} audit event already records the branch
   * submitted, so the previous value is on the permanent record.
   */
  

  /**
   * Re-checks, under {@code SELECT ... FOR UPDATE}, whether a profile {@link #findExisting} earlier
   * found non-terminal is <em>still</em> non-terminal — closing the gap between that read (taken
   * before the OTP send loop, deliberately outside any transaction) and the re-entry writes this
   * transaction is about to make. A profile can turn terminal in between — an operator's manual
   * completion, most plausibly — and this transaction must not overwrite the completed profile's
   * data even though it cannot un-send the OTPs already dispatched. The row lock is held only for
   * the remainder of this (already-open, I/O-free) transaction, not across the send loop.
   *
   * @return {@code true} if the profile is still eligible for re-entry (not terminal)
   */
  boolean lockAndCheckStillEligibleForReentry(UUID profileId);

  /**
   * Every {@code app.profile_channel} row this profile currently has, keyed by channel — read
   * before a Stage 1b re-entry overwrites them, so the {@code session_reentered} audit event can
   * record what the previous selection was (S3-07).
   */
  Map<MessageChannel, ChannelState> currentChannelStates(UUID profileId);

  /**
   * {@code app.profile_customer_data}'s phone/email as they stand before a Stage 1b re-entry
   * overwrites them, for the same reason as {@link #currentChannelStates}.
   */
  ContactSnapshot currentContactDetails(UUID profileId);

  /**
   * Bumps {@code app.profile.last_activity_at} with no status change — the re-entry path for every
   * in-flight status except {@code abandoned} (S3-07). {@code in_progress}, {@code
   * awaiting_registry}, {@code blocked_scan} and {@code blocked_liveness} are left otherwise
   * untouched: those states are about scan/liveness/registry progress, not contact-channel
   * identity, and a Stage 1b resubmission does not resolve them.
   */
  void touchLastActivity(UUID profileId, Instant now);

  /**
   * Transitions an {@code abandoned} profile back to {@code in_progress} on a Stage 1b re-entry
   * (S3-07), with the matching {@code app.profile_status_history} row the deferred {@code
   * profile_status_requires_history} trigger requires in the same transaction. Legal per {@code
   * app.status_transition} (V0020), whose own comment cites exactly this case: "customer.md Stage
   * 1a: 'An incomplete profile exists' continues through 1b ... abandoned is classified 'In
   * flight'". Only ever called when the existing profile's status is {@code abandoned} — every
   * other in-flight status uses {@link #touchLastActivity} instead.
   *
   * @param auditEventId the {@code session_reentered} event's id, the history row's {@code NOT
   *     NULL} foreign key onto it
   */
  void reactivateFromAbandoned(UUID profileId, Instant now, long auditEventId);

  /**
   * {@code app.profile.phone_lock_until} (V0037, S3-08, R-044) — non-empty and in the future means
   * a Stage 1b re-entry must be refused. Read by {@code ContactChannelsService}; written only by
   * {@link #applyPhoneSessionLock}, called from {@code
   * com.sfbank.bayanati.otpverification.service.OtpVerificationService} when Stage 2 detects every
   * selected phone channel has locked.
   */
  Optional<Instant> currentPhoneLockUntil(UUID profileId);

  /**
   * {@code app.profile.phone_lock_escalated} (V0037) — once true, every future {@link
   * #applyPhoneSessionLock} call for this profile should use the 1-hour duration rather than 15
   * minutes; it is never reset.
   */
  boolean phoneLockEscalated(UUID profileId);

  /**
   * Sets {@code phone_lock_until = until} and {@code phone_lock_escalated = true} together. The
   * caller (Stage 2) has already decided {@code until} using {@link #phoneLockEscalated}'s pre-call
   * value — 15 minutes the first time, 1 hour on every call after.
   */
  void applyPhoneSessionLock(UUID profileId, Instant until);

  /**
   * Takes a {@code SELECT ... FOR UPDATE} row lock on {@code app.profile} — no data returned,
   * purely a serialization point. Used by {@code OtpVerificationService} before evaluating "every
   * selected phone channel is locked": that evaluation reads {@code app.profile_channel} rows for
   * *both* phone channels, but each {@code verify()} call only holds a row lock on the one channel
   * it is deciding — two concurrent calls locking SMS and WhatsApp in parallel would otherwise both
   * read each other's channel as still-unlocked under READ COMMITTED and neither would ever write
   * the session lock. Locking the shared {@code app.profile} row first forces the two calls to
   * serialise, so the second one to run sees the first's already-committed channel lock. Found by
   * {@code @agent-reviewer}'s second pass, S3-08.
   */
  void lockProfileRow(UUID profileId);
}
