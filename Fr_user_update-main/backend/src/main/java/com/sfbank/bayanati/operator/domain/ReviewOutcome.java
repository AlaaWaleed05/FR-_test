package com.sfbank.bayanati.operator.domain;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import java.util.Set;

/**
 * The result of an approve or reject call, mirroring {@code submission.service.SubmissionOutcome}'s
 * idempotency shape.
 *
 * @param status the profile's status after this call
 * @param alreadyDone {@code true} when this call found the profile already in the target status (an
 *     idempotent re-call) rather than performing the transition itself — {@code notifiedChannels}
 *     is empty in that case, since nothing is re-enqueued
 * @param notifiedChannels which verified channels the notification was enqueued for
 */
public record ReviewOutcome(
    String profileId, String status, boolean alreadyDone, Set<MessageChannel> notifiedChannels) {}
