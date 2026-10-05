package com.sfbank.bayanati.messaging.service;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageDispatchResult;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import java.util.Map;
import java.util.Set;

/**
 * The composite the rest of the backend injects. Implements {@link MessageSender} itself, so a
 * caller depends on that type and on nothing else in this package — no registry, no per-channel
 * bean, no {@code Map} of its own to reach through.
 *
 * <p>Pure logic — a map, a validation at construction, a lookup on every call — so it lives in
 * {@code service} and is exercised by plain JUnit with no Spring context, satisfying CLAUDE.md's
 * package rule.
 */
public final class ChannelRoutingMessageSender implements MessageSender {

  /** {@link MessageDispatchResult#providerId()} for the router's own not-configured outcome. */
  public static final String ROUTING_PROVIDER_ID = "routing";

  /**
   * {@link MessageDispatchResult#providerStatusCode()} for a channel with no {@link MessageSender}
   * mapped — including a channel deliberately turned off with {@code
   * fru.messaging.<channel>.enabled=false}. Enablement is expressed by absence from the map, not by
   * a separate outcome: a disabled channel and an unconfigured one are observably the same thing to
   * a caller, and reusing one path avoids a second branch with the same effect.
   */
  public static final String CHANNEL_NOT_CONFIGURED = "CHANNEL_NOT_CONFIGURED";

  private final Map<MessageChannel, MessageSender> byChannel;

  /**
   * @throws IllegalStateException if any sender is mapped to a channel it does not itself claim to
   *     support — caught at startup, not on a customer's OTP at 22:00.
   */
  public ChannelRoutingMessageSender(Map<MessageChannel, MessageSender> byChannel) {
    byChannel.forEach(
        (channel, sender) -> {
          if (!sender.supportedChannels().contains(channel)) {
            throw new IllegalStateException(
                "sender "
                    + sender.getClass().getName()
                    + " is mapped to "
                    + channel
                    + " but supports only "
                    + sender.supportedChannels());
          }
        });
    this.byChannel = Map.copyOf(byChannel);
  }

  @Override
  public Set<MessageChannel> supportedChannels() {
    return byChannel.keySet();
  }

  @Override
  public MessageDispatchResult send(OutboundMessage message) {
    MessageSender sender = byChannel.get(message.channel());
    if (sender == null) {
      return MessageDispatchResult.permanentFailure(
          message,
          ROUTING_PROVIDER_ID,
          CHANNEL_NOT_CONFIGURED,
          "no sender configured for channel " + message.channel().wireValue());
    }
    return sender.send(message);
  }
}
