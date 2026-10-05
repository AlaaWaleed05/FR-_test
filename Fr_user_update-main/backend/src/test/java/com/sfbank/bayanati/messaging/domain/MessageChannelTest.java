package com.sfbank.bayanati.messaging.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The wire values must stay byte-identical to the {@code CHECK} constraints on {@code
 * app.profile_channel.channel} (V0007), {@code app.otp_challenge.channel} (V0021) and {@code
 * app.notification_outbox.channel} (V0035) — an enum whose {@code name()} is uppercase would
 * silently violate all three if it were ever used instead of {@link MessageChannel#wireValue()}.
 */
class MessageChannelTest {

  @Test
  void wireValuesAreExactlyTheThreeLowercaseTokensTheCheckConstraintsAllow() {
    assertEquals("sms", MessageChannel.SMS.wireValue());
    assertEquals("whatsapp", MessageChannel.WHATSAPP.wireValue());
    assertEquals("email", MessageChannel.EMAIL.wireValue());
  }

  @Test
  void nameIsNotTheWireValue() {
    // The trap this test exists to catch: MessageChannel.SMS.name() is "SMS", not "sms". Any
    // code that reaches for .name() instead of .wireValue() when talking to the database is
    // wrong, and this assertion is what would have caught it.
    // Locale.ROOT on the case conversion, same family of brittleness as the Locale.ROOT format
    // fixes in this commit: under tr_TR, "EMAIL".toLowerCase() is "emaıl" with a dotless ı, and
    // this assertion fails on a channel whose wire value is perfectly correct.
    for (MessageChannel channel : MessageChannel.values()) {
      assertEquals(channel.name().toLowerCase(java.util.Locale.ROOT), channel.wireValue());
      org.junit.jupiter.api.Assertions.assertNotEquals(channel.name(), channel.wireValue());
    }
  }
}
