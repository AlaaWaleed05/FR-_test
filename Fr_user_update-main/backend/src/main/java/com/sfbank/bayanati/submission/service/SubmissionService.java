package com.sfbank.bayanati.submission.service;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.audit.domain.CanonicalJson;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessagePayload;
import com.sfbank.bayanati.notification.domain.NotificationOutboxRepository;
import com.sfbank.bayanati.profile.domain.ChannelState;
import com.sfbank.bayanati.profile.domain.ContactSnapshot;
import com.sfbank.bayanati.profile.domain.ProfileRepository;
import com.sfbank.bayanati.submission.domain.JourneyStage;
import com.sfbank.bayanati.submission.domain.LivenessNotCompleteException;
import com.sfbank.bayanati.submission.domain.NotInFinalStagesException;
import com.sfbank.bayanati.submission.domain.ProfileNotEligibleException;
import com.sfbank.bayanati.submission.domain.SignatureMissingException;
import com.sfbank.bayanati.submission.domain.SubmissionMessageRenderer;
import com.sfbank.bayanati.submission.domain.SubmissionRepository;
import com.sfbank.bayanati.submission.domain.SubmissionState;
import com.sfbank.bayanati.submission.domain.UnknownProfileException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Journey Stage 12 — completion and submission (docs/journeys/customer.md). The customer's journey
 * ends here; the profile's life does not.
 *
 * <p><strong>The ordering is satisfied by construction, not by extra bookkeeping</strong>: this
 * class's one transaction covers steps 1-2 of customer.md's numbered list (status set to {@code
 * submitted}, reference number assigned) together with the audit event and the notification-outbox
 * enqueue; step 3 (the backend confirming to the app) can only happen after {@link #submit}
 * returns, which is after that transaction commits; step 4 (the app clearing local storage) is
 * mobile-side and out of scope; step 5 (dispatch) is the enqueue above, never an inline send — the
 * scheduled dispatcher that drains {@code app.notification_outbox} is separately built (S3-05) and
 * out of scope here.
 *
 * <p><strong>{@code ref.profile_reference_version} (S4-04, AD-002f §6.2)</strong> is written in the
 * same transaction, right alongside the status update — the standing constraint "the list version
 * used for a submission is recorded on the profile" (PROJECT_PLAN.md/CLAUDE.md). This class
 * performs no reference-code validation itself (it never calls {@code ReferenceCatalog}), so it has
 * nothing of its own to pin; the versions it records are exactly what stages 3-6 already resolved
 * and stored on {@code app.profile_customer_data} — see {@code
 * SubmissionRepository#usedReferenceListVersions}.
 *
 * <p><strong>Idempotent, not merely re-triable</strong>: customer.md Stage 13's "the app reconciles
 * on resume, never assumes completion" means a resubmission after a dropped connection — the app
 * never learned whether its first call landed — must return the SAME reference number rather than
 * erroring or creating a second one. Checked before anything is read further, mirroring how {@code
 * ContactChannelsService}/{@code IdentityScanService} check terminal-profile rejections before any
 * external call.
 */
@Service
public class SubmissionService {

  static final String CHAIN_KIND = "profile";
  static final String EVENT_PROFILE_SUBMITTED = "profile_submitted";
  static final String EVENT_SUBMISSION_REJECTED = "submission_rejected";

  /** Statuses a resubmission call treats as "already done", never as an error. */
  private static final Set<String> ALREADY_SUBMITTED_FAMILY =
      Set.of("submitted", "approved", "rejected");

  /**
   * The only non-terminal statuses a customer can hold while inside Stages 10-12 (S5-13). Used by
   * {@link #currentJourneyPointer} to fail closed rather than guess a stage for {@code
   * awaiting_registry}, {@code blocked_scan} or {@code abandoned}.
   */
  private static final Set<String> IN_FINAL_STAGES_STATUSES =
      Set.of("in_progress", "blocked_liveness");

  private final SubmissionRepository submissionRepository;
  private final ProfileRepository profileRepository;
  private final NotificationOutboxRepository notificationOutboxRepository;
  private final AuditEventWriter auditEventWriter;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  public SubmissionService(
      SubmissionRepository submissionRepository,
      ProfileRepository profileRepository,
      NotificationOutboxRepository notificationOutboxRepository,
      AuditEventWriter auditEventWriter,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.submissionRepository = submissionRepository;
    this.profileRepository = profileRepository;
    this.notificationOutboxRepository = notificationOutboxRepository;
    this.auditEventWriter = auditEventWriter;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * Journey Stage 13's resume read for Stages 10-12 (S5-13) — "where am I", not "may I continue".
   *
   * <p>customer.md l.1112-1114: "The backend alone decides whether the session is still open. Every
   * resume begins by asking. The answer can be <em>proceed</em>, <em>already complete</em>, or
   * <em>blocked until X</em>." All three are answers here; only a profile that is not in these
   * stages at all, or is terminal with nothing to report, is refused.
   *
   * <p><strong>Read-only, and structurally so.</strong> It calls {@code
   * SubmissionRepository#checkState}, the non-locking read — never {@code lockAndCheckState} and
   * never {@code LivenessRepository#lockAndGetLivenessState}, both of which are {@code SELECT ...
   * FOR UPDATE}. It mints no face session (it never touches {@code UqudoClient}, unlike {@code
   * /api/v1/liveness/token}, which calls {@code createFaceSession} and writes {@code
   * pending_face_session_id} and so could never have served as a resume probe), writes nothing,
   * transitions no status, draws no attempt budget and appends no audit event.
   *
   * @throws UnknownProfileException no such profile — {@code 404}
   * @throws NotInFinalStagesException the customer is not in Stages 10-12 — {@code STATE_CONFLICT}
   * @throws ProfileNotEligibleException terminal with nothing to report ({@code
   *     terminated_registry_mismatch}) — {@code PROFILE_TERMINAL}
   */
  public JourneyPointer currentJourneyPointer(UUID profileId) {
    SubmissionState state =
        submissionRepository
            .checkState(profileId)
            .orElseThrow(() -> new UnknownProfileException("no profile with id " + profileId));

    // The already-complete answer, checked FIRST and deliberately ahead of the terminal guard:
    // these three statuses are terminal, and refusing them is precisely what would dead-end a
    // customer whose app died between submitting and rendering the confirmation screen.
    if (ALREADY_SUBMITTED_FAMILY.contains(state.status())) {
      return new JourneyPointer(
          profileId.toString(),
          JourneyStage.valueOf(state.status().toUpperCase(Locale.ROOT)),
          null,
          state.referenceNumber(),
          verifiedChannels(profileId));
    }
    if (state.terminal()) {
      // terminated_registry_mismatch: no reference number, no signature, nothing to resume.
      throw ProfileNotEligibleException.terminalStatus(profileId, state.status());
    }
    // Fail closed: in_progress and blocked_liveness are the ONLY non-terminal statuses that can be
    // inside stages 10-12. awaiting_registry and blocked_scan are stage 8/9; abandoned re-enters
    // through stage 1a/1b, which is what reactivates it.
    if (!IN_FINAL_STAGES_STATUSES.contains(state.status()) || !state.hasAcceptedCycle()) {
      throw new NotInFinalStagesException(profileId, state.status());
    }
    // Mirrors LivenessService's own guard EXACTLY (issueFaceSessionToken, :132-138 and :221-226):
    // still blocked when the deadline is null OR in the future; an ELAPSED deadline is resumable,
    // and LivenessService clears the block on the next token request rather than on a timer. If
    // this read answered LIVENESS_BLOCKED on status alone it would show a block screen carrying an
    // already-past deadline to a customer whose /liveness/token call would have succeeded --
    // leaving the app to re-derive the expiry from its own clock, which is exactly the client-side
    // state inference R-052 and AD-002a exist to prevent.
    if ("blocked_liveness".equals(state.status())
        && (state.livenessBlockedUntil() == null
            || state.livenessBlockedUntil().isAfter(clock.instant()))) {
      return pointer(profileId, JourneyStage.LIVENESS_BLOCKED, state.livenessBlockedUntil());
    }
    if (!state.facePassed()) {
      return pointer(profileId, JourneyStage.LIVENESS, null);
    }
    if (!state.hasSignature()) {
      return pointer(profileId, JourneyStage.SIGNATURE, null);
    }
    return pointer(profileId, JourneyStage.SUBMIT, null);
  }

  private static JourneyPointer pointer(UUID profileId, JourneyStage stage, Instant blockedUntil) {
    return new JourneyPointer(profileId.toString(), stage, blockedUntil, null, Set.of());
  }

  /**
   * The profile's currently VERIFIED channels — the same filter {@code submit} applies when it
   * decides who gets the submission notification, so the confirmation screen and the notification
   * can never disagree about which channels carry the decision.
   */
  private Set<MessageChannel> verifiedChannels(UUID profileId) {
    Set<MessageChannel> verified = new LinkedHashSet<>();
    for (Map.Entry<MessageChannel, ChannelState> entry :
        profileRepository.currentChannelStates(profileId).entrySet()) {
      if (entry.getValue() == ChannelState.VERIFIED) {
        verified.add(entry.getKey());
      }
    }
    return Set.copyOf(verified);
  }

  public SubmissionOutcome submit(UUID profileId) {
    UUID requestId = UUID.randomUUID();
    Instant now = clock.instant();

    SubmissionState precheck =
        submissionRepository
            .checkState(profileId)
            .orElseThrow(() -> new UnknownProfileException("no profile with id " + profileId));

    if (ALREADY_SUBMITTED_FAMILY.contains(precheck.status())) {
      return new SubmissionOutcome(
          profileId.toString(), precheck.referenceNumber(), precheck.status(), Set.of());
    }
    if (precheck.terminal()) {
      // Only terminated_registry_mismatch can reach here: it never has a passing liveness result
      // or a signature, so it could not have failed either check below first.
      auditRejection(profileId, requestId, "profile_not_eligible", precheck.status());
      throw ProfileNotEligibleException.terminalStatus(profileId, precheck.status());
    }
    if (!precheck.facePassed()) {
      auditRejection(profileId, requestId, "liveness_not_complete", null);
      throw new LivenessNotCompleteException(
          "profile " + profileId + " has not yet passed stage 10 liveness/face-match");
    }
    if (!precheck.hasSignature()) {
      auditRejection(profileId, requestId, "signature_missing", null);
      throw new SignatureMissingException(
          "profile " + profileId + " has not yet captured a signature");
    }

    Map<MessageChannel, ChannelState> channelStates =
        profileRepository.currentChannelStates(profileId);
    ContactSnapshot contact = profileRepository.currentContactDetails(profileId);

    String[] referenceNumberHolder = new String[1];
    boolean[] becameIneligible = {false};
    Set<MessageChannel> verifiedChannels = new LinkedHashSet<>();

    transactionTemplate.executeWithoutResult(
        status -> {
          SubmissionState fresh =
              submissionRepository
                  .lockAndCheckState(profileId)
                  .orElseThrow(
                      () -> new UnknownProfileException("no profile with id " + profileId));
          if (!"in_progress".equals(fresh.status())) {
            // Raced: a concurrent call already submitted this profile (or it turned terminal by
            // some other path) between the pre-check above and this lock. Re-read outside this
            // (otherwise empty) transaction rather than trust the stale pre-check value.
            becameIneligible[0] = true;
            return;
          }

          String referenceNumber = submissionRepository.nextReferenceNumber();
          referenceNumberHolder[0] = referenceNumber;

          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("referenceNumber", referenceNumber);
          long eventId =
              auditEventWriter.append(
                  simpleEvent(profileId, requestId, EVENT_PROFILE_SUBMITTED, payload));

          submissionRepository.submit(profileId, referenceNumber, now, eventId);

          // S4-04: recorded in the SAME transaction as the status update, so a rollback leaves no
          // ref.profile_reference_version rows -- see this class's Javadoc.
          submissionRepository.recordReferenceVersions(
              profileId, submissionRepository.usedReferenceListVersions(profileId));

          // Enqueued in the SAME transaction as the status update, per
          // NotificationOutboxRepository's own contract -- never sent inline (Urgency.DEFERRED is
          // the outbox dispatcher's job, drained later, unlike Stage 2's inline OTP send).
          for (Map.Entry<MessageChannel, ChannelState> entry : channelStates.entrySet()) {
            if (entry.getValue() != ChannelState.VERIFIED) {
              continue; // declined/unverified channels get nothing (customer.md Stage 12)
            }
            MessageChannel channel = entry.getKey();
            String destination =
                channel == MessageChannel.EMAIL ? contact.emailAddress() : contact.phoneNumber();
            MessagePayload messagePayload = renderPayload(channel, referenceNumber);
            notificationOutboxRepository.enqueue(profileId, channel, destination, messagePayload);
            verifiedChannels.add(channel);
          }
        });

    if (becameIneligible[0]) {
      String currentStatus =
          submissionRepository.checkState(profileId).map(SubmissionState::status).orElse(null);
      if (currentStatus != null && ALREADY_SUBMITTED_FAMILY.contains(currentStatus)) {
        // A concurrent submission won the race -- idempotent outcome, not an error.
        String referenceNumber =
            submissionRepository
                .checkState(profileId)
                .map(SubmissionState::referenceNumber)
                .orElse(null);
        return new SubmissionOutcome(
            profileId.toString(), referenceNumber, currentStatus, Set.of());
      }
      auditRejection(
          profileId, requestId, "profile_became_ineligible_during_processing", currentStatus);
      throw ProfileNotEligibleException.becameIneligible(profileId, currentStatus);
    }

    return new SubmissionOutcome(
        profileId.toString(), referenceNumberHolder[0], "submitted", Set.copyOf(verifiedChannels));
  }

  private static MessagePayload renderPayload(MessageChannel channel, String referenceNumber) {
    return switch (channel) {
      case SMS -> SubmissionMessageRenderer.renderSms(referenceNumber);
      case WHATSAPP -> SubmissionMessageRenderer.renderWhatsApp(referenceNumber);
      case EMAIL -> SubmissionMessageRenderer.renderEmail(referenceNumber);
    };
  }

  private void auditRejection(UUID profileId, UUID requestId, String reason, String detail) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("reason", reason);
    payload.put("detail", detail);
    auditEventWriter.append(simpleEvent(profileId, requestId, EVENT_SUBMISSION_REJECTED, payload));
  }

  private static AuditEvent simpleEvent(
      UUID profileId, UUID requestId, String eventType, Map<String, Object> payload) {
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        eventType,
        "customer",
        null,
        profileId,
        profileId,
        requestId,
        CanonicalJson.object(payload));
  }
}
