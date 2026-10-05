package com.sfbank.bayanati.operator.web;

import com.sfbank.bayanati.operator.domain.ChannelStateView;
import java.time.Instant;

public record ChannelStateResponse(String channel, String state, Instant verifiedAt) {

  public static ChannelStateResponse from(ChannelStateView view) {
    return new ChannelStateResponse(
        view.channel().wireValue(), view.state().wireValue(), view.verifiedAt());
  }
}
