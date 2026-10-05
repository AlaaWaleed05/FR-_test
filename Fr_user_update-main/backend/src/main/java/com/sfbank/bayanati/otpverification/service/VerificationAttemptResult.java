package com.sfbank.bayanati.otpverification.service;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.otpverification.domain.VerificationOutcome;
import com.sfbank.bayanati.profile.domain.ChannelState;
import java.time.Instant;

/**
 * @param sessionBlockedUntil non-{@code null} only when this attempt is what triggered (or found
 *     already active) the "both/only phone channel locked" session block — customer.md's own "not
 *     verified — won't be saved" / retry-time copy reads this
 */
public record VerificationAttemptResult(
    MessageChannel channel,
    VerificationOutcome outcome,
    ChannelState channelState,
    Instant sessionBlockedUntil) {}
