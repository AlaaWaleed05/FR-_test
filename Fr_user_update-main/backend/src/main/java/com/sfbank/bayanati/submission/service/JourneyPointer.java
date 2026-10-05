package com.sfbank.bayanati.submission.service;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.submission.domain.JourneyStage;
import java.time.Instant;
import java.util.Set;

/**
 * What the Stage 10-12 resume read answers. Every field beyond {@code stage} is populated only for
 * the stages that have something to say, and is {@code null}/empty otherwise — the same "null when
 * not applicable" discipline {@code ScanDisplayPayload} uses for its registry fields.
 *
 * @param blockedUntil non-{@code null} only for {@link JourneyStage#LIVENESS_BLOCKED}, and even
 *     then only when the column is actually set — see {@code SubmissionState#livenessBlockedUntil}
 * @param referenceNumber non-{@code null} only for {@code SUBMITTED}/{@code APPROVED}/{@code
 *     REJECTED}. The customer's only artifact once local storage clears (customer.md l.1005).
 * @param verifiedChannels which channels carry the decision, populated only for {@code
 *     SUBMITTED}/{@code APPROVED}/{@code REJECTED}. Recomputed from the profile's current channel
 *     states, the same derivation {@code SubmissionService#submit} uses, so the two can never
 *     disagree.
 *     <p><strong>Not the same thing as {@code SubmissionOutcome#verifiedChannels}</strong>, which
 *     means "channels the submission notification was enqueued for" and is correctly EMPTY on an
 *     idempotent re-call because nothing is re-enqueued. This field is what the confirmation screen
 *     needs, and it is the reason the screen reads it here on every path rather than branching on
 *     whether the submission response happened to be the first one.
 */
public record JourneyPointer(
    String profileId,
    JourneyStage stage,
    Instant blockedUntil,
    String referenceNumber,
    Set<MessageChannel> verifiedChannels) {}
