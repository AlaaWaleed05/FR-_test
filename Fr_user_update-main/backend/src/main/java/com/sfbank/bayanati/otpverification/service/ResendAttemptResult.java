package com.sfbank.bayanati.otpverification.service;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.otpverification.domain.ResendOutcome;

/**
 * @param maskedDestination the destination Stage 2's row would show, e.g. {@code "•••• 4821"} —
 *     present regardless of outcome, since it identifies which row this answers even on refusal
 * @param secondsUntilAllowed only meaningful for {@link ResendOutcome#TOO_SOON}; {@code -1}
 *     otherwise
 */
public record ResendAttemptResult(
    MessageChannel channel,
    ResendOutcome outcome,
    String maskedDestination,
    long secondsUntilAllowed) {}
