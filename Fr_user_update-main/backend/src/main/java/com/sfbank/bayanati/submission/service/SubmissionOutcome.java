package com.sfbank.bayanati.submission.service;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import java.util.Set;

/**
 * @param status the profile's status after this call — {@code "submitted"} on success, or whatever
 *     the profile's status already was on an idempotent re-call
 * @param verifiedChannels which channels the submission notification was enqueued for — empty on an
 *     idempotent re-call, since nothing is re-enqueued
 */
public record SubmissionOutcome(
    String profileId,
    String referenceNumber,
    String status,
    Set<MessageChannel> verifiedChannels) {}
