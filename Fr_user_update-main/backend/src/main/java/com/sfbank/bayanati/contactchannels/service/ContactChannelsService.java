package com.sfbank.bayanati.contactchannels.service;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.audit.domain.CanonicalJson;
import com.sfbank.bayanati.contactchannels.domain.ChannelDecision;
import com.sfbank.bayanati.contactchannels.domain.ChannelSelection;
import com.sfbank.bayanati.contactchannels.domain.DestinationMasker;
import com.sfbank.bayanati.contactchannels.domain.OtpCodeGenerator;
import com.sfbank.bayanati.contactchannels.domain.OtpCodeGenerator.GeneratedOtp;
import com.sfbank.bayanati.contactchannels.domain.OtpMessageRenderer;
import com.sfbank.bayanati.contactchannels.domain.ProfileAlreadyCompleteException;
import com.sfbank.bayanati.contactchannels.domain.SessionTemporarilyBlockedException;
import com.sfbank.bayanati.identityscan.domain.DeviceLessReentrySuperseder;
import com.sfbank.bayanati.identityscan.domain.SupersededIdentity;
import com.sfbank.bayanati.messaging.config.MessageSenderConfiguration;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageDispatchResult;
import com.sfbank.bayanati.messaging.domain.MessagePayload;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.Urgency;
import com.sfbank.bayanati.profile.domain.ChannelState;
import com.sfbank.bayanati.profile.domain.ContactSnapshot;
import com.sfbank.bayanati.profile.domain.ExistingProfile;
import com.sfbank.bayanati.profile.domain.ProfileRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Journey Stage 1b — contact channels (docs/journeys/customer.md).
 *
 * <p><strong>Three outcomes, decided by whether a profile already exists for the account (S3-07,
 * BL-006/BL-009), checked before anything is generated or sent:</strong>
 *
 * <ul>
 *   <li><strong>No existing profile</strong> — the original S3-06 behaviour: creates the session
 *       and profile, records the per-channel initial state, and issues one distinct OTP code per
 *       selected, available channel.
 *   <li><strong>An existing profile whose status is terminal</strong> — rejected with {@link
 *       com.sfbank.bayanati.contactchannels.domain.ProfileAlreadyCompleteException} after one audit
 *       event records the attempt. Nothing is generated, sent, or written to {@code app.*}. This is
 *       what makes the check server-side rather than a courtesy the app performs — a terminal
 *       profile submitted here directly, bypassing Stage 1a, is refused identically.
 *   <li><strong>An existing profile whose status is not terminal</strong> — a <em>re-entry</em>:
 *       customer.md Stage 2 "Phone number wrong → Back to 1b ... Channel selections may also be
 *       changed there", and the device-less resume path (BL-006's implementable part). Updates,
 *       rather than inserts: the phone/email, the channel selections and their states are replaced,
 *       prior {@code otp_challenge} rows are invalidated (expired, never deleted — see {@link
 *       ProfileRepository#invalidateOtpChallenges}), and fresh challenges are issued. This is what
 *       closes BL-009: the resend that used to hit {@code profile_one_per_account} after three real
 *       OTPs went out now never reaches that constraint, because the second submission updates the
 *       same profile instead of trying to insert a second one.
 * </ul>
 *
 * <p><strong>A re-entry can also be refused by Stage 2's own escalating block (S3-08,
 * R-044).</strong> When every selected phone channel locks after 5 wrong attempts, {@code
 * OtpVerificationService} writes {@code app.profile.phone_lock_until} — 15 minutes the first time,
 * 1 hour on every occurrence after. This class checks that column, {@link
 * #checkPhoneLockNotActive}, before generating or sending anything on the re-entry path: a channel
 * that reached its wrong-attempt limit "cannot be retried" (V0007), so the only way back into
 * verification is a fresh Stage 1b submission, and the block exists precisely to bound how often
 * that submission can happen.
 *
 * <p>Re-entry is its own audited event ({@code session_reentered}), not a silent overwrite — it
 * carries the previous phone/email and previous per-channel states alongside what replaced them, so
 * the permanent record shows a second Stage 1b submission happened, not just its end state.
 *
 * <p><strong>The existence check that decides re-entry-vs-rejection runs twice.</strong> The first
 * read ({@code findExisting}) happens before the send loop, deliberately outside any transaction —
 * by design, nothing here holds a lock across unbounded external I/O. That leaves a window: the
 * profile could turn terminal between that read and this transaction's writes (an operator's manual
 * completion, most plausibly). {@link ProfileRepository#lockAndCheckStillEligibleForReentry}
 * re-checks under {@code SELECT ... FOR UPDATE} as the first statement inside the transaction, and
 * if the answer changed, the callback returns without writing anything else — the transaction still
 * commits, having done nothing. Only <em>after</em> {@code executeWithoutResult} returns does
 * {@link #submit} write the {@code contact_channels_rejected} audit event and throw. This is
 * deliberate, not incidental: a rejection audited *inside* the same callback that then throws would
 * be rolled back with everything else — {@code TransactionTemplate} rolls back on any exception
 * escaping the callback, and the audit writer shares this transaction's connection. The first draft
 * of this fix did exactly that and lost the event; caught by @agent-reviewer's second pass, not the
 * first. This cannot recall OTPs already sent, but it does stop a stale read from overwriting a
 * completed profile's data, and it does so with the rejection itself durably on the record.
 *
 * <p><strong>The OTP is sent inline, never through {@code app.notification_outbox}.</strong> A
 * stage 2 OTP is {@link Urgency#INTERACTIVE} — the customer is watching a countdown — and AD-002c's
 * closed design (docs/components/messaging.md; {@code
 * com.sfbank.bayanati.notification.service.OutboxDispatcher}'s own Javadoc) is explicit that
 * INTERACTIVE sends bypass the outbox entirely, because the outbox exists only for {@link
 * Urgency#DEFERRED} sends a scheduler drains later, and no scheduler exists (out of scope here and
 * at S3-05). The S3-06 task text asked for an outbox row per channel; this class does not do that,
 * because doing so would mean a real customer never receives their code until a dispatcher exists —
 * see the session report.
 *
 * <p><strong>Sends happen before the database transaction opens</strong>, mirroring {@code
 * OutboxDispatcher}'s own reasoning: a provider call is unbounded external I/O and must not hold a
 * database transaction open across it. Only the persistence side — the profile, its channels, its
 * challenges, and every audit event — is transactional. The profile-existence check and the
 * terminal-rejection audit write both happen even earlier, before the send loop — no OTP may be
 * dispatched for an account whose profile turns out to be terminal.
 */
@Service
public class ContactChannelsService {

  private static final Logger log = LoggerFactory.getLogger(ContactChannelsService.class);

  static final String CHAIN_KIND = "profile";
  static final String EVENT_SESSION_CREATED = "session_created";
  static final String EVENT_SESSION_REENTERED = "session_reentered";

  /**
   * AD-008/BL-041. {@code audit.audit_event.event_type} is a controlled vocabulary held in code,
   * not a CHECK constraint (V0002), so this needed no migration.
   */
  static final String EVENT_IDENTITY_SUPERSEDED = "identity_superseded";

  static final String EVENT_CONTACT_CHANNELS_REJECTED = "contact_channels_rejected";
  static final String EVENT_OTP_ISSUED = "otp_issued";
  static final String EVENT_NOTIFICATION_DISPATCHED = "notification_dispatched";

  static final String STATUS_ABANDONED = "abandoned";

  /** customer.md Policy values: "Code validity — 5 minutes". */
  static final Duration OTP_VALIDITY = Duration.ofMinutes(5);

  private static final int OTP_VALIDITY_MINUTES = (int) OTP_VALIDITY.toMinutes();

  private final ProfileRepository profileRepository;
  private final AuditEventWriter auditEventWriter;
  private final MessageSender messageSender;
  private final DeviceLessReentrySuperseder deviceLessReentrySuperseder;
  private final Environment environment;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  public ContactChannelsService(
      ProfileRepository profileRepository,
      AuditEventWriter auditEventWriter,
      MessageSender messageSender,
      DeviceLessReentrySuperseder deviceLessReentrySuperseder,
      Environment environment,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.profileRepository = profileRepository;
    this.auditEventWriter = auditEventWriter;
    this.messageSender = messageSender;
    this.deviceLessReentrySuperseder = deviceLessReentrySuperseder;
    this.environment = environment;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * @param emailAddress {@code null} or blank when the customer supplied none
   * @throws com.sfbank.bayanati.contactchannels.domain.NoPhoneChannelSelectedException if neither
   *     SMS nor WhatsApp survives selection and enablement. Thrown before anything is written or
   *     sent.
   * @throws com.sfbank.bayanati.contactchannels.domain.ProfileAlreadyCompleteException (S3-07) if
   *     an existing profile for this account has already reached a terminal status. Thrown before
   *     anything is generated, sent, or written to {@code app.*} — only the rejection itself is
   *     audited.
   */
  public ContactChannelsResult submit(
      String branchCode,
      String accountNumber,
      String phoneNumber,
      boolean smsSelected,
      boolean whatsappSelected,
      String emailAddress) {
    boolean emailPresent = emailAddress != null && !emailAddress.isBlank();

    List<ChannelDecision> decisions =
        ChannelSelection.resolve(
            smsSelected,
            isEnabled(MessageChannel.SMS),
            whatsappSelected,
            isEnabled(MessageChannel.WHATSAPP),
            emailPresent,
            isEnabled(MessageChannel.EMAIL));

    UUID requestId = UUID.randomUUID();
    // Account number alone (V0061, BL-032): the branch the customer picked is descriptive data and
    // must not decide whether this is the same customer's existing profile.
    Optional<ExistingProfile> existing = profileRepository.findExisting(accountNumber);

    if (existing.isPresent() && existing.get().terminal()) {
      rejectTerminalReentry(existing.get(), requestId, branchCode, accountNumber);
    }

    boolean reentry = existing.isPresent();
    UUID profileId = reentry ? existing.get().profileId() : UUID.randomUUID();
    Instant now = clock.instant();

    if (reentry) {
      checkPhoneLockNotActive(profileId, requestId, branchCode, accountNumber, now);
    }

    Instant expiresAt = now.plus(OTP_VALIDITY);

    List<ChallengeAttempt> attempts = new ArrayList<>();
    for (ChannelDecision decision : decisions) {
      if (!decision.toBeChallenged()) {
        continue;
      }
      String destination = decision.channel() == MessageChannel.EMAIL ? emailAddress : phoneNumber;
      GeneratedOtp otp = OtpCodeGenerator.generate();
      MessagePayload payload = renderPayload(decision.channel(), otp.code());
      UUID challengeId = UUID.randomUUID();
      OutboundMessage message =
          new OutboundMessage(
              challengeId,
              decision.channel(),
              destination,
              payload,
              Urgency.INTERACTIVE,
              profileId.toString());
      MessageDispatchResult result = sendCatchingSenderFailure(message);
      attempts.add(
          new ChallengeAttempt(
              decision.channel(), challengeId, otp.codeHash(), otp.salt(), result));
    }

    Set<MessageChannel> challengedOrDeclinedChannels =
        decisions.stream().map(ChannelDecision::channel).collect(Collectors.toSet());

    // Set inside the transaction, read after it returns -- NOT thrown from inside the callback.
    // TransactionTemplate rolls back on any RuntimeException escaping it, and JdbcAuditEventWriter
    // shares this same transaction-bound connection, so an audit event appended and then followed
    // by a throw in the SAME callback would be rolled back with everything else — silently losing
    // the exact evidence this rejection exists to record (found by @agent-reviewer's second pass;
    // the first draft did this and the event never persisted). Returning normally instead lets the
    // (otherwise empty) transaction commit cleanly, and the audit write + throw happen afterward,
    // outside any transaction — the same shape rejectTerminalReentry already uses correctly.
    boolean[] becameIneligibleDuringProcessing = {false};

    transactionTemplate.executeWithoutResult(
        status -> {
          if (reentry) {
            ExistingProfile ex = existing.get();

            // Re-check under a row lock: findExisting's read happened before the send loop,
            // deliberately outside any transaction, so the profile could have turned terminal in
            // between (an operator's manual completion, most plausibly). The OTPs already sent
            // cannot be un-sent, but this transaction must not overwrite a completed profile's
            // data on a stale read.
            if (!profileRepository.lockAndCheckStillEligibleForReentry(profileId)) {
              becameIneligibleDuringProcessing[0] = true;
              return;
            }

            Map<MessageChannel, ChannelState> previousStates =
                profileRepository.currentChannelStates(profileId);
            ContactSnapshot previousContact = profileRepository.currentContactDetails(profileId);

            long reentryEventId =
                auditEventWriter.append(
                    sessionReenteredEvent(
                        profileId,
                        requestId,
                        branchCode,
                        accountNumber,
                        decisions,
                        smsSelected,
                        whatsappSelected,
                        emailPresent,
                        previousStates,
                        previousContact));

            // AD-008/BL-041. A Stage 1b re-POST for a profile that ALREADY holds an active
            // accepted identity cycle cannot be any same-device route back here -- the "wrong
            // phone number?" correction lives on the Stage 2 screen, and every in-journey route
            // back to account entry clears local state first (see the port's Javadoc). So that
            // condition IS the device-less signal, and the superseder applies it itself, as the
            // WHERE clause of its own UPDATE. On any same-device re-entry it matches nothing and
            // writes nothing.
            SupersededIdentity superseded =
                deviceLessReentrySuperseder.supersedeInheritedIdentity(profileId, now);
            if (superseded.anything()) {
              long supersededEventId =
                  auditEventWriter.append(
                      identitySupersededEvent(profileId, requestId, superseded));
              if (superseded.registryPauseToClear()) {
                // The profile was parked at awaiting_registry, whose every exit needs the cycle
                // just superseded -- leaving the status alone strands it permanently. Runs after
                // the event above because the status-history row references its id.
                deviceLessReentrySuperseder.clearRegistryPauseAfterSupersession(
                    profileId, now, supersededEventId);
              }
            }

            if (STATUS_ABANDONED.equals(ex.status())) {
              profileRepository.reactivateFromAbandoned(profileId, now, reentryEventId);
            } else {
              profileRepository.touchLastActivity(profileId, now);
            }

            profileRepository.updateContactDetails(
                profileId, phoneNumber, emailPresent ? emailAddress : null, now);
            // The branch is refreshed to this submission's selection (V0061, BL-032): descriptive
            // data, so the latest choice wins; the session_reentered event above already carries
            // it.
            profileRepository.updateBranchCode(profileId, branchCode);
            profileRepository.invalidateOtpChallenges(profileId, now);

            for (ChannelDecision decision : decisions) {
              profileRepository.upsertChannel(profileId, decision.channel(), decision.state());
            }
            profileRepository.declineChannelsNotIn(profileId, challengedOrDeclinedChannels);
          } else {
            profileRepository.ensureAuditChain(profileId);

            long sessionEventId =
                auditEventWriter.append(
                    sessionCreatedEvent(
                        profileId,
                        requestId,
                        branchCode,
                        accountNumber,
                        decisions,
                        smsSelected,
                        whatsappSelected,
                        emailPresent));
            profileRepository.insertProfile(
                profileId, branchCode, accountNumber, now, sessionEventId);
            profileRepository.insertContactDetails(
                profileId, phoneNumber, emailPresent ? emailAddress : null, now);

            for (ChannelDecision decision : decisions) {
              profileRepository.upsertChannel(profileId, decision.channel(), decision.state());
            }
          }

          for (ChallengeAttempt attempt : attempts) {
            profileRepository.insertOtpChallenge(
                attempt.challengeId(),
                profileId,
                attempt.channel(),
                attempt.codeHash(),
                attempt.salt(),
                now,
                expiresAt);
            auditEventWriter.append(
                otpIssuedEvent(profileId, requestId, attempt.channel(), expiresAt));
            auditEventWriter.append(
                notificationDispatchedEvent(profileId, requestId, attempt.result()));
          }
        });

    if (becameIneligibleDuringProcessing[0]) {
      // Outside the (already-committed, empty) transaction above, so this write survives even
      // though the profile's data was correctly never touched. Re-read the status fresh rather
      // than trust the pre-send-loop `existing` value, which is now known stale.
      String currentStatus =
          profileRepository.findExisting(accountNumber).map(ExistingProfile::status).orElse(null);
      auditEventWriter.append(
          contactChannelsRejectedEvent(
              profileId,
              requestId,
              branchCode,
              accountNumber,
              currentStatus,
              "profile_became_complete_during_processing",
              null));
      throw new ProfileAlreadyCompleteException(
          "account "
              + accountNumber
              + " at branch "
              + branchCode
              + " completed its profile while this request was in flight");
    }

    List<ChannelOutcome> outcomes = new ArrayList<>();
    for (ChannelDecision decision : decisions) {
      String destination = decision.channel() == MessageChannel.EMAIL ? emailAddress : phoneNumber;
      String masked =
          decision.channel() == MessageChannel.EMAIL
              ? DestinationMasker.maskEmail(destination)
              : DestinationMasker.maskPhone(destination);
      outcomes.add(new ChannelOutcome(decision.channel(), decision.state(), masked));
    }
    return new ContactChannelsResult(profileId, List.copyOf(outcomes));
  }

  /**
   * customer.md Stage 1a: "there is no re-entry, no supersede path". Writes one audit event to the
   * profile's existing chain — it already exists, since the only way a profile is created is
   * through this same class — then throws. No OTP, no message, no {@code app.*} write of any kind
   * happens on this path; this method is the last thing {@link #submit} does before returning
   * control to the caller as an exception.
   */
  private void rejectTerminalReentry(
      ExistingProfile existing, UUID requestId, String branchCode, String accountNumber) {
    auditEventWriter.append(
        contactChannelsRejectedEvent(
            existing.profileId(),
            requestId,
            branchCode,
            accountNumber,
            existing.status(),
            "profile_already_complete",
            null));
    throw new ProfileAlreadyCompleteException(
        "account "
            + accountNumber
            + " at branch "
            + branchCode
            + " already has a completed profile");
  }

  /**
   * S3-08/R-044: refuses a re-entry while {@code app.profile.phone_lock_until} is still in the
   * future (see {@link SessionTemporarilyBlockedException}) — the escalating 15-minute/1-hour block
   * Stage 2 applies when every selected phone channel locks. Checked here, before anything is
   * generated or sent, the same place and shape as {@link #rejectTerminalReentry}. Unlike that
   * check, this one is scoped to the re-entry branch only — a brand-new profile has no prior lock
   * to consult.
   */
  private void checkPhoneLockNotActive(
      UUID profileId, UUID requestId, String branchCode, String accountNumber, Instant now) {
    Optional<Instant> blockedUntil = profileRepository.currentPhoneLockUntil(profileId);
    if (blockedUntil.isPresent() && blockedUntil.get().isAfter(now)) {
      auditEventWriter.append(
          contactChannelsRejectedEvent(
              profileId,
              requestId,
              branchCode,
              accountNumber,
              null,
              "phone_temporarily_blocked",
              blockedUntil.get().toString()));
      throw new SessionTemporarilyBlockedException(blockedUntil.get());
    }
  }

  /**
   * {@code profileStatus} goes into the audit payload only, never into the exception message the
   * controller turns into an HTTP response — this endpoint is unauthenticated (BL-007), so leaking
   * whether a guessed account is {@code submitted} vs {@code approved} vs {@code rejected} to an
   * unauthenticated caller would be a real disclosure. The audit trail's own access control is what
   * protects the status value. Callers pass {@code null} only when no current value is worth the
   * cost of fetching one — {@link #submit}'s mid-transaction rejection re-reads it fresh instead,
   * since the pre-send-loop {@code existing} value is by then known stale.
   *
   * @param blockedUntilIso non-{@code null} only for the S3-08 phone-lock rejection reason
   */
  private static AuditEvent contactChannelsRejectedEvent(
      UUID profileId,
      UUID requestId,
      String branchCode,
      String accountNumber,
      String profileStatus,
      String reason,
      String blockedUntilIso) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("branch", branchCode);
    payload.put("accountNumber", accountNumber);
    payload.put("profileStatus", profileStatus);
    payload.put("reason", reason);
    payload.put("blockedUntilIso", blockedUntilIso);
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        EVENT_CONTACT_CHANNELS_REJECTED,
        "customer",
        null,
        profileId,
        null,
        requestId,
        CanonicalJson.object(payload));
  }

  private boolean isEnabled(MessageChannel channel) {
    return environment.getProperty(
        MessageSenderConfiguration.enabledProperty(channel), Boolean.class, true);
  }

  /**
   * {@link MessageSender}'s contract says only a programming error throws — every provider outcome,
   * including a timeout, is meant to come back as a {@link MessageDispatchResult}. That contract
   * belongs to adapters that do not exist yet (only the stub does today), so this class does not
   * trust it: a thrown exception here is recorded as a failed attempt like any other, rather than
   * aborting the whole request and leaving a message that may already be on its way to a real
   * handset with nothing in the audit trail to show for it.
   *
   * <p><strong>{@code getMessage()} never reaches the audit trail.</strong> A future adapter's
   * exception could quote its request — for an SMS/WhatsApp send that request contains the OTP code
   * — and {@code providerStatusText} flows straight into a permanent, unmodifiable {@code
   * notification_dispatched} event. Only the exception's class name is recorded; the full
   * exception, message included, goes to the log instead, which is not retained for 7 years.
   */
  private MessageDispatchResult sendCatchingSenderFailure(OutboundMessage message) {
    try {
      return messageSender.send(message);
    } catch (RuntimeException sendFailed) {
      log.error(
          "MessageSender.send threw for channel {} — recorded as a failed attempt, not"
              + " retried inline (see StubMessageSender's contract in messaging.md)",
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

  /**
   * Records the customer's raw selection ({@code <channel>Selected}) separately from what actually
   * got challenged ({@code <channel>Challenged}). A channel disabled by {@code
   * fru.messaging.<channel>.enabled} is recorded {@link
   * com.sfbank.bayanati.profile.domain.ChannelState#DECLINED} on {@code app.profile_channel} — the
   * schema has no fourth state — but the two-flag audit payload keeps "the customer deselected it"
   * and "the customer selected it and this deployment couldn't serve it" distinguishable in the
   * permanent record, even though {@code app.profile_channel} itself cannot distinguish them.
   */
  private static AuditEvent sessionCreatedEvent(
      UUID profileId,
      UUID requestId,
      String branchCode,
      String accountNumber,
      List<ChannelDecision> decisions,
      boolean smsSelected,
      boolean whatsappSelected,
      boolean emailPresent) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("branch", branchCode);
    payload.put("accountNumber", accountNumber);
    payload.put("smsSelected", smsSelected);
    payload.put("smsChallenged", challenged(decisions, MessageChannel.SMS));
    payload.put("whatsappSelected", whatsappSelected);
    payload.put("whatsappChallenged", challenged(decisions, MessageChannel.WHATSAPP));
    payload.put("emailSelected", emailPresent);
    if (emailPresent) {
      payload.put("emailChallenged", challenged(decisions, MessageChannel.EMAIL));
    }
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        EVENT_SESSION_CREATED,
        "customer",
        null,
        profileId,
        profileId,
        requestId,
        CanonicalJson.object(payload));
  }

  /**
   * The re-entry counterpart of {@link #sessionCreatedEvent} (S3-07, BL-009): same
   * selected/challenged flags for the new submission, plus what the previous submission had on
   * record, so the permanent audit trail shows a second Stage 1b submission happened for this
   * account, not just its end state. {@code previousStates} has no entry for a channel that never
   * had a row (e.g. email, if the first submission had none) — recorded as {@code null}.
   */
  private static AuditEvent sessionReenteredEvent(
      UUID profileId,
      UUID requestId,
      String branchCode,
      String accountNumber,
      List<ChannelDecision> decisions,
      boolean smsSelected,
      boolean whatsappSelected,
      boolean emailPresent,
      Map<MessageChannel, ChannelState> previousStates,
      ContactSnapshot previousContact) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("branch", branchCode);
    payload.put("accountNumber", accountNumber);
    payload.put("previousPhoneNumber", previousContact.phoneNumber());
    payload.put("previousEmailAddress", previousContact.emailAddress());
    payload.put("previousSmsState", previousWireValue(previousStates, MessageChannel.SMS));
    payload.put(
        "previousWhatsappState", previousWireValue(previousStates, MessageChannel.WHATSAPP));
    payload.put("previousEmailState", previousWireValue(previousStates, MessageChannel.EMAIL));
    payload.put("smsSelected", smsSelected);
    payload.put("smsChallenged", challenged(decisions, MessageChannel.SMS));
    payload.put("whatsappSelected", whatsappSelected);
    payload.put("whatsappChallenged", challenged(decisions, MessageChannel.WHATSAPP));
    payload.put("emailSelected", emailPresent);
    if (emailPresent) {
      payload.put("emailChallenged", challenged(decisions, MessageChannel.EMAIL));
    }
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        EVENT_SESSION_REENTERED,
        "customer",
        null,
        profileId,
        profileId,
        requestId,
        CanonicalJson.object(payload));
  }

  /**
   * AD-008/BL-041 — the evidentiary record that a device-less re-entry revoked an inherited
   * identity. Actor is {@code system}: the customer asked to re-enter, not to supersede; the
   * supersession is the backend's own anti-fraud consequence, the same reasoning that makes {@code
   * applyScanBlock}'s status history a system actor rather than a customer one.
   */
  private static AuditEvent identitySupersededEvent(
      UUID profileId, UUID requestId, SupersededIdentity superseded) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("reason", "device_less_reentry");
    payload.put("cycleSuperseded", superseded.cycleSuperseded());
    payload.put("signatureSuperseded", superseded.signatureSuperseded());
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        EVENT_IDENTITY_SUPERSEDED,
        "system",
        null,
        profileId,
        profileId,
        requestId,
        CanonicalJson.object(payload));
  }

  private static String previousWireValue(
      Map<MessageChannel, ChannelState> previousStates, MessageChannel channel) {
    ChannelState state = previousStates.get(channel);
    return state == null ? null : state.wireValue();
  }

  private static boolean challenged(List<ChannelDecision> decisions, MessageChannel channel) {
    return decisions.stream()
        .filter(d -> d.channel() == channel)
        .anyMatch(ChannelDecision::toBeChallenged);
  }

  private static AuditEvent otpIssuedEvent(
      UUID profileId, UUID requestId, MessageChannel channel, Instant expiresAt) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("channel", channel.wireValue());
    payload.put("expiresAtIso", expiresAt.toString());
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        EVENT_OTP_ISSUED,
        "system",
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

  /**
   * One channel's in-flight send, carried from the pre-transaction send loop into the transaction.
   * Carries only the hash and salt, never the code itself — {@link OtpCodeGenerator}'s own contract
   * is that the code lives no longer than the stack frame that renders it into a message.
   */
  private record ChallengeAttempt(
      MessageChannel channel,
      UUID challengeId,
      byte[] codeHash,
      byte[] salt,
      MessageDispatchResult result) {}
}
