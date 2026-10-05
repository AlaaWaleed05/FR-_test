package com.sfbank.bayanati.contactchannels.service;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.profile.domain.ChannelState;

/**
 * One channel's outcome, as reported back to the caller. {@code maskedDestination} never carries
 * the OTP code — only the masked phone/email.
 */
public record ChannelOutcome(
    MessageChannel channel, ChannelState state, String maskedDestination) {}
