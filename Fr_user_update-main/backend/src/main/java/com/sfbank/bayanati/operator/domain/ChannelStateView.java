package com.sfbank.bayanati.operator.domain;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.profile.domain.ChannelState;
import java.time.Instant;

/** One {@code app.profile_channel} row — "these determine how the bank can reach the customer". */
public record ChannelStateView(MessageChannel channel, ChannelState state, Instant verifiedAt) {}
