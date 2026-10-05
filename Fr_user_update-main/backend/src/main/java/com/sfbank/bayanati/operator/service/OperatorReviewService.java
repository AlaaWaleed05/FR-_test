package com.sfbank.bayanati.operator.service;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.audit.domain.CanonicalJson;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessagePayload;
import com.sfbank.bayanati.notification.domain.NotificationOutboxRepository;
import com.sfbank.bayanati.operator.domain.AccessLevelRequiredException;
import com.sfbank.bayanati.operator.domain.InvalidRejectionReasonException;
import com.sfbank.bayanati.operator.domain.OperatorAccessLevel;
import com.sfbank.bayanati.operator.domain.OperatorAuditPayload;
import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.domain.ProfileNotReviewableException;
import com.sfbank.bayanati.operator.domain.RejectionReasonExtra;
import com.sfbank.bayanati.operator.domain.ReviewMessageRenderer;
import com.sfbank.bayanati.operator.domain.ReviewOutcome;
import com.sfbank.bayanati.operator.domain.ReviewRepository;
import com.sfbank.bayanati.operator.domain.UnknownProfileException;
import com.sfbank.bayanati.profile.domain.ChannelState;
import com.sfbank.bayanati.profile.domain.ContactSnapshot;
import com.sfbank.bayanati.profile.domain.ProfileRepository;
import com.sfbank.bayanati.reference.domain.ReferenceCatalog;
import com.sfbank.bayanati.reference.domain.ReferenceItemDetail;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * operator.md "Review — approve or reject" and "Re-approving a rejected profile". Mirrors {@code
 * submission.service.SubmissionService}'s shape: a pre-check for a friendly 404/idempotent-no-op,
 * one transaction covering the guarded {@code UPDATE}, its history row, its audit event and the
 * outbox enqueue together, and a post-failure re-read to distinguish a lost race/idempotent re-call
 * from a genuine refusal.
 */
@Service
public class OperatorReviewService {

  static final String CHAIN_KIND = "profile";
  static final String EVENT_PROFILE_APPROVED = "profile_approved";
  static final String EVENT_PROFILE_APPROVE_REFUSED = "profile_approve_refused";
  static final String EVENT_PROFILE_REJECTED = "profile_rejected";
  static final String REJECTION_REASON_LIST = "rejection_reason";
  static final String REJ_07 = "REJ-07";

  private final ReviewRepository reviewRepository;
  private final ProfileRepository profileRepository;
  private final NotificationOutboxRepository notificationOutboxRepository;
  private final ReferenceCatalog referenceCatalog;
  private final AuditEventWriter auditEventWriter;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  public OperatorReviewService(
      ReviewRepository reviewRepository,
      ProfileRepository profileRepository,
      NotificationOutboxRepository notificationOutboxRepository,
      ReferenceCatalog referenceCatalog,
      AuditEventWriter auditEventWriter,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.reviewRepository = reviewRepository;
    this.profileRepository = profileRepository;
    this.notificationOutboxRepository = notificationOutboxRepository;
    this.referenceCatalog = referenceCatalog;
    this.auditEventWriter = auditEventWriter;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public ReviewOutcome approve(OperatorIdentity identity, UUID profileId) {
    requireOperatorLevel(identity);
    // Unlocked pre-check, purely for a friendly 404 and to skip opening a transaction at all for
    // the common idempotent re-call — the authoritative read is lockAndReadStatus, below.
    String precheckStatus =
        reviewRepository
            .currentStatus(profileId)
            .orElseThrow(() -> new UnknownProfileException("no profile with id " + profileId));
    if ("approved".equals(precheckStatus)) {
      return new ReviewOutcome(profileId.toString(), precheckStatus, true, Set.of());
    }

    UUID requestId = UUID.randomUUID();
    Instant now = clock.instant();
    Map<MessageChannel, ChannelState> channelStates =
        profileRepository.currentChannelStates(profileId);
    ContactSnapshot contact = profileRepository.currentContactDetails(profileId);

    boolean[] approved = {false};
    String[] lockedStatusHolder = {precheckStatus};
    Set<MessageChannel> notified = new LinkedHashSet<>();

    transactionTemplate.executeWithoutResult(
        status -> {
          // Must be the FIRST statement in this transaction: it takes the app.profile row lock
          // that keeps the status this method reads, decides on, and records as fromStatus
          // consistent with whatever reviewRepository.approve()'s own UPDATE actually acts on
          // immediately after — see JdbcReviewRepository's class Javadoc for the race this closes.
          String lockedStatus =
              reviewRepository
                  .lockAndReadStatus(profileId)
                  .orElseThrow(
                      () -> new UnknownProfileException("no profile with id " + profileId));
          lockedStatusHolder[0] = lockedStatus;
          if ("approved".equals(lockedStatus)) {
            // Raced: a concurrent call already approved this profile between the pre-check above
            // and this lock. No write, no audit event -- handleApproveRefusal reports it as the
            // idempotent outcome it is, not a refusal.
            status.setRollbackOnly();
            return;
          }

          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("fromStatus", lockedStatus);
          long eventId =
              auditEventWriter.append(
                  simpleEvent(profileId, requestId, EVENT_PROFILE_APPROVED, identity, payload));

          approved[0] =
              reviewRepository.approve(
                  profileId, identity.operatorId(), lockedStatus, now, eventId);
          if (!approved[0]) {
            status.setRollbackOnly();
            return;
          }

          for (Map.Entry<MessageChannel, ChannelState> entry : channelStates.entrySet()) {
            if (entry.getValue() != ChannelState.VERIFIED) {
              continue;
            }
            MessageChannel channel = entry.getKey();
            String destination =
                channel == MessageChannel.EMAIL ? contact.emailAddress() : contact.phoneNumber();
            MessagePayload messagePayload = renderApproved(channel);
            notificationOutboxRepository.enqueue(profileId, channel, destination, messagePayload);
            notified.add(channel);
          }
        });

    if (!approved[0]) {
      return handleApproveRefusal(identity, profileId, lockedStatusHolder[0]);
    }
    return new ReviewOutcome(profileId.toString(), "approved", false, Set.copyOf(notified));
  }

  public ReviewOutcome reject(
      OperatorIdentity identity, UUID profileId, String reasonCode, String internalNote) {
    requireOperatorLevel(identity);
    int reasonVersion = referenceCatalog.currentVersion(REJECTION_REASON_LIST);
    ReferenceItemDetail reason = validateReasonCode(reasonCode, internalNote, reasonVersion);

    // Unlocked pre-check, purely for a friendly 404 and to skip opening a transaction at all for
    // the common idempotent re-call — the authoritative read is lockAndReadStatus, below.
    String precheckStatus =
        reviewRepository
            .currentStatus(profileId)
            .orElseThrow(() -> new UnknownProfileException("no profile with id " + profileId));
    if ("rejected".equals(precheckStatus)) {
      return new ReviewOutcome(profileId.toString(), precheckStatus, true, Set.of());
    }

    UUID requestId = UUID.randomUUID();
    Instant now = clock.instant();
    Map<MessageChannel, ChannelState> channelStates =
        profileRepository.currentChannelStates(profileId);
    ContactSnapshot contact = profileRepository.currentContactDetails(profileId);
    String customerMessageAr = RejectionReasonExtra.customerMessageAr(reason.extraJson());

    boolean[] rejected = {false};
    String[] lockedStatusHolder = {precheckStatus};
    Set<MessageChannel> notified = new LinkedHashSet<>();

    transactionTemplate.executeWithoutResult(
        status -> {
          // Must be the FIRST statement in this transaction, before the audit-chain write below —
          // taking the app.profile row lock and the audit_chain row lock in the OPPOSITE order
          // approve() does is exactly how two concurrent reviews of the same profile (one approve,
          // one reject) could deadlock (40P01), found under review. Locking app.profile first here
          // too means the two methods always contend for the same two locks in the same order.
          String lockedStatus =
              reviewRepository
                  .lockAndReadStatus(profileId)
                  .orElseThrow(
                      () -> new UnknownProfileException("no profile with id " + profileId));
          lockedStatusHolder[0] = lockedStatus;
          if ("rejected".equals(lockedStatus)) {
            // Raced: a concurrent call already rejected this profile between the pre-check above
            // and this lock. No write, no audit event.
            status.setRollbackOnly();
            return;
          }

          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("reasonCode", reasonCode);
          payload.put("internalNote", internalNote);
          long eventId =
              auditEventWriter.append(
                  simpleEvent(profileId, requestId, EVENT_PROFILE_REJECTED, identity, payload));

          rejected[0] =
              reviewRepository.reject(
                  profileId,
                  reasonCode,
                  reasonVersion,
                  internalNote,
                  identity.operatorId(),
                  now,
                  eventId);
          if (!rejected[0]) {
            status.setRollbackOnly();
            return;
          }

          for (Map.Entry<MessageChannel, ChannelState> entry : channelStates.entrySet()) {
            if (entry.getValue() != ChannelState.VERIFIED) {
              continue;
            }
            MessageChannel channel = entry.getKey();
            String destination =
                channel == MessageChannel.EMAIL ? contact.emailAddress() : contact.phoneNumber();
            MessagePayload messagePayload = renderRejected(channel, customerMessageAr);
            notificationOutboxRepository.enqueue(profileId, channel, destination, messagePayload);
            notified.add(channel);
          }
        });

    if (!rejected[0]) {
      String lockedStatus = lockedStatusHolder[0];
      if ("rejected".equals(lockedStatus)) {
        return new ReviewOutcome(profileId.toString(), lockedStatus, true, Set.of());
      }
      throw new ProfileNotReviewableException(
          "profile "
              + profileId
              + " is in status "
              + lockedStatus
              + ", which reject requires to be 'submitted'");
    }
    return new ReviewOutcome(profileId.toString(), "rejected", false, Set.copyOf(notified));
  }

  /**
   * @param lockedStatus the status {@code approve()}'s own transaction already read under {@code
   *     SELECT ... FOR UPDATE} immediately before refusing — authoritative as of that lock, so no
   *     second read is needed here (the transaction has since rolled back and released it, but
   *     nothing else could have changed the row while this call held it).
   */
  private ReviewOutcome handleApproveRefusal(
      OperatorIdentity identity, UUID profileId, String lockedStatus) {
    if ("approved".equals(lockedStatus)) {
      return new ReviewOutcome(profileId.toString(), lockedStatus, true, Set.of());
    }
    // Recorded OUTSIDE the (already rolled-back) transaction, mirroring
    // submission.service.SubmissionService#auditRejection. This event existed because a refused
    // approve could be a four-eyes-rule violation attempt; AD-013 (2026-09-13) removed that rule,
    // so the only refusal reason left is an ineligible status. The event is KEPT rather than
    // dropped by inference -- whether it still earns its place is wayfinder ticket 09 / BL-133's
    // call, not this session's.
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("reason", "profile_not_reviewable");
    payload.put("status", lockedStatus);
    auditEventWriter.append(
        simpleEvent(
            profileId, UUID.randomUUID(), EVENT_PROFILE_APPROVE_REFUSED, identity, payload));
    throw new ProfileNotReviewableException(
        "profile "
            + profileId
            + " is in status "
            + lockedStatus
            + ", which approve requires to be 'submitted' or 'rejected'");
  }

  private ReferenceItemDetail validateReasonCode(
      String reasonCode, String internalNote, int reasonVersion) {
    if (reasonCode == null || reasonCode.isBlank()) {
      throw new InvalidRejectionReasonException(
          "a rejection requires a reason code from the fixed list — free text alone is not accepted");
    }
    if (!referenceCatalog.exists(REJECTION_REASON_LIST, reasonVersion, reasonCode)) {
      throw new InvalidRejectionReasonException("unknown rejection reason code: " + reasonCode);
    }
    if (REJ_07.equals(reasonCode) && (internalNote == null || internalNote.isBlank())) {
      throw new InvalidRejectionReasonException(REJ_07 + " requires a mandatory internal detail");
    }
    return referenceCatalog
        .find(REJECTION_REASON_LIST, reasonVersion, reasonCode)
        .orElseThrow(
            () ->
                new IllegalStateException("reason code passed exists() but find() found nothing"));
  }

  private void requireOperatorLevel(OperatorIdentity identity) {
    if (identity.accessLevel() != OperatorAccessLevel.OPERATOR) {
      throw new AccessLevelRequiredException(
          "operator "
              + identity.operatorId()
              + " has access level "
              + identity.accessLevel()
              + ", which may not approve or reject");
    }
  }

  private static MessagePayload renderApproved(MessageChannel channel) {
    return switch (channel) {
      case SMS -> ReviewMessageRenderer.renderApprovedSms();
      case WHATSAPP -> ReviewMessageRenderer.renderApprovedWhatsApp();
      case EMAIL -> ReviewMessageRenderer.renderApprovedEmail();
    };
  }

  private static MessagePayload renderRejected(MessageChannel channel, String customerMessageAr) {
    return switch (channel) {
      case SMS -> ReviewMessageRenderer.renderRejectedSms(customerMessageAr);
      case WHATSAPP -> ReviewMessageRenderer.renderRejectedWhatsApp(customerMessageAr);
      case EMAIL -> ReviewMessageRenderer.renderRejectedEmail(customerMessageAr);
    };
  }

  /**
   * Every event this service writes goes through here, so {@code actorRole} is stamped in exactly
   * one place rather than at each call site. Takes the whole {@link OperatorIdentity} rather than a
   * bare operator id for that reason — the id alone cannot say whether an admin or an operator
   * performed the action, which since AD-013 is a distinction only the payload preserves.
   */
  private static AuditEvent simpleEvent(
      UUID profileId,
      UUID requestId,
      String eventType,
      OperatorIdentity identity,
      Map<String, Object> payload) {
    return new AuditEvent(
        CHAIN_KIND,
        profileId.toString(),
        eventType,
        "operator",
        identity.operatorId(),
        profileId,
        null,
        requestId,
        CanonicalJson.object(OperatorAuditPayload.withActorRole(identity, payload)));
  }
}
