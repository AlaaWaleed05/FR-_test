package com.sfbank.bayanati.messaging.domain;

/**
 * The three verifiable channels. The wire values MUST stay byte-identical to the {@code CHECK}
 * constraint on {@code app.profile_channel.channel} (V0007), {@code app.otp_challenge.channel}
 * (V0021) and {@code app.notification_outbox.channel} (V0035): {@code sms}, {@code whatsapp},
 * {@code email}. Adding a fourth channel is a migration, not an enum edit.
 */
public enum MessageChannel {
  SMS("sms"),
  WHATSAPP("whatsapp"),
  EMAIL("email");

  private final String wireValue;

  MessageChannel(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  /**
   * The inverse of {@link #wireValue()}. Fails closed on an unrecognised value rather than guessing
   * — the only callers read this back out of a database column the {@code CHECK} constraints above
   * already bound to these three values.
   */
  public static MessageChannel fromWireValue(String wireValue) {
    for (MessageChannel channel : values()) {
      if (channel.wireValue.equals(wireValue)) {
        return channel;
      }
    }
    throw new IllegalArgumentException("unrecognised channel: " + wireValue);
  }
}
