package com.sfbank.bayanati.contactchannels.domain;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.profile.domain.ChannelState;

/**
 * One channel's resolved outcome from {@link ChannelSelection#resolve}.
 *
 * @param state {@link ChannelState#UNVERIFIED} means "challenge it" — this stage never produces
 *     {@link ChannelState#VERIFIED}, since nothing is verified until Stage 2.
 */
public record ChannelDecision(MessageChannel channel, ChannelState state) {

  public boolean toBeChallenged() {
    return state == ChannelState.UNVERIFIED;
  }
}
