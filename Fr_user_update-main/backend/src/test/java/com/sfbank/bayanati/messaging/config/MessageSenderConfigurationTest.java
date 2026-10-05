package com.sfbank.bayanati.messaging.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.messaging.airtel.AirtelSmsProperties;
import com.sfbank.bayanati.messaging.airtel.AirtelSmsSender;
import com.sfbank.bayanati.messaging.domain.DispatchOutcome;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageDispatchResult;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.Urgency;
import com.sfbank.bayanati.messaging.domain.WhatsAppPayload;
import com.sfbank.bayanati.messaging.domain.WhatsAppTemplateCategory;
import com.sfbank.bayanati.messaging.service.ChannelRoutingMessageSender;
import com.sfbank.bayanati.messaging.stub.StubMessageSender;
import com.sfbank.bayanati.messaging.stub.StubMessagingProperties;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class MessageSenderConfigurationTest {

  private static final StubMessagingProperties EMPTY_SEED =
      new StubMessagingProperties(Map.of(), 0, false);

  /** Synthetic throughout — CLAUDE.md forbids a real credential or endpoint in the repository. */
  private static final AirtelSmsProperties AIRTEL_CONFIG =
      new AirtelSmsProperties(
          URI.create("https://sms.invalid/api/html_send_sms/"),
          "test-user",
          "test-secret-not-real",
          "SFB",
          Duration.ofSeconds(5),
          Duration.ofSeconds(15));

  /**
   * Fails the test if the real sender is ever built. Used wherever no channel selects {@code http},
   * which is what proves the supplier is not invoked speculatively.
   */
  private static final Supplier<MessageSender> AIRTEL_MUST_NOT_BE_BUILT =
      () -> {
        throw new AssertionError("the http sender was built for a channel that did not select it");
      };

  @Test
  void selectingTheStubBuildsTheStubSender() {
    MessageSender sender =
        MessageSenderConfiguration.select(
            MessageChannel.SMS, "stub", EMPTY_SEED, AIRTEL_MUST_NOT_BE_BUILT);

    assertInstanceOf(StubMessageSender.class, sender);
  }

  @Test
  void surroundingWhitespaceInTheSelectorIsTolerated() {
    assertInstanceOf(
        StubMessageSender.class,
        MessageSenderConfiguration.select(
            MessageChannel.EMAIL, "  stub  ", EMPTY_SEED, AIRTEL_MUST_NOT_BE_BUILT));
  }

  @Test
  void selectingHttpForSmsBuildsTheRealAirtelSender() {
    MessageSender sender =
        MessageSenderConfiguration.select(
            MessageChannel.SMS,
            "http",
            EMPTY_SEED,
            () -> MessageSenderConfiguration.airtelSmsSender(AIRTEL_CONFIG));

    assertInstanceOf(AirtelSmsSender.class, sender);
  }

  /**
   * SMS is the only channel with a real adapter (BL-080; SMS-only release, product-owner decision
   * 2026-09-07). Selecting {@code http} for another channel must fail at startup rather than map an
   * SMS-only sender onto WhatsApp or email.
   */
  @ParameterizedTest
  @ValueSource(strings = {"whatsapp", "email"})
  void httpIsRefusedForEveryChannelOtherThanSms(String wireValue) {
    MessageChannel channel = MessageChannel.fromWireValue(wireValue);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                MessageSenderConfiguration.select(
                    channel, "http", EMPTY_SEED, AIRTEL_MUST_NOT_BE_BUILT));

    assertTrue(
        thrown.getMessage().contains(MessageSenderConfiguration.providerProperty(channel)),
        "must name the property to change, got: " + thrown.getMessage());
  }

  /** Mirrors CoreBankingClientConfigurationTest: missing, empty, and a plain typo all fail. */
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   ", "stubb", "bank-gateway", "STUB", "airtel", "HTTP"})
  void anythingOtherThanARecognisedValueFailsAtStartupNamingWhatItFound(String selection) {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                MessageSenderConfiguration.select(
                    MessageChannel.WHATSAPP, selection, EMPTY_SEED, AIRTEL_MUST_NOT_BE_BUILT));

    String message = thrown.getMessage();
    assertTrue(
        message.contains("fru.messaging.whatsapp.provider"),
        "must name the property to set, got: " + message);
    assertTrue(message.contains("[stub, http]"), "must name the accepted values, got: " + message);
    if (selection == null) {
      assertTrue(message.contains("not set"), message);
    } else {
      assertTrue(
          message.contains("'" + selection + "'"), "must quote what it found, got: " + message);
    }
  }

  /**
   * The endpoint and both credentials are configuration with no default. An environment that picked
   * the real gateway without supplying them must fail at startup — and the message must name the
   * properties without printing any value, since two of them are secrets.
   */
  @Test
  void httpWithNoEndpointOrCredentialsFailsStartupNamingThePropertiesButNoValues() {
    AirtelSmsProperties unconfigured = new AirtelSmsProperties(null, null, null, null, null, null);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> MessageSenderConfiguration.airtelSmsSender(unconfigured));

    String message = thrown.getMessage();
    assertTrue(message.contains("fru.messaging.sms.http.endpoint"), message);
    assertTrue(message.contains("fru.messaging.sms.http.username"), message);
    assertTrue(message.contains("fru.messaging.sms.http.password"), message);
    assertTrue(message.contains("fru.messaging.sms.http.sender-id"), message);
  }

  @Test
  void aBlankPasswordIsTreatedAsUnsetRatherThanSent() {
    AirtelSmsProperties blankPassword =
        new AirtelSmsProperties(
            URI.create("https://sms.invalid/api/html_send_sms/"),
            "test-user",
            "   ",
            "SFB",
            null,
            null);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> MessageSenderConfiguration.airtelSmsSender(blankPassword));

    assertTrue(
        thrown.getMessage().contains("fru.messaging.sms.http.password"), thrown.getMessage());
  }

  /**
   * The reason the properties record overrides toString: a record's generated one prints every
   * component, so any log of the object — or a Spring failure message quoting the bean — would leak
   * the gateway password.
   */
  @Test
  void thePropertiesNeverPrintTheCredentials() {
    String printed = AIRTEL_CONFIG.toString();

    assertTrue(printed.contains("REDACTED"), printed);
    assertTrue(
        !printed.contains("test-secret-not-real"),
        "toString must not print the password: " + printed);
    assertTrue(!printed.contains("test-user"), "toString must not print the username: " + printed);
  }

  @Test
  void aDisabledChannelIsExcludedFromTheBuiltRouterEvenWithAValidProvider() {
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("fru.messaging.sms.provider", "stub");
    environment.setProperty("fru.messaging.whatsapp.provider", "stub");
    environment.setProperty("fru.messaging.whatsapp.enabled", "false");
    environment.setProperty("fru.messaging.email.provider", "stub");

    MessageSender router =
        new MessageSenderConfiguration().messageSender(environment, EMPTY_SEED, AIRTEL_CONFIG);

    OutboundMessage toWhatsapp =
        new OutboundMessage(
            UUID.randomUUID(),
            MessageChannel.WHATSAPP,
            "+249900000000",
            new WhatsAppPayload(
                "otp", "ar", List.of("123456"), WhatsAppTemplateCategory.AUTHENTICATION),
            Urgency.DEFERRED,
            "corr-1");

    MessageDispatchResult result = router.send(toWhatsapp);

    assertEquals(DispatchOutcome.PERMANENT_FAILURE, result.outcome());
    assertEquals(ChannelRoutingMessageSender.CHANNEL_NOT_CONFIGURED, result.providerStatusCode());
  }

  @Test
  void anEnabledChannelWithAnUnsetProviderStillFailsStartup() {
    // Enablement does not excuse an unset provider: a typo'd/blank provider must be caught at
    // startup regardless of whether the channel is offered today.
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("fru.messaging.sms.provider", "stub");
    environment.setProperty("fru.messaging.whatsapp.enabled", "true");
    // fru.messaging.whatsapp.provider deliberately unset
    environment.setProperty("fru.messaging.email.provider", "stub");

    assertThrows(
        IllegalStateException.class,
        () ->
            new MessageSenderConfiguration().messageSender(environment, EMPTY_SEED, AIRTEL_CONFIG));
  }

  @Test
  void aDisabledChannelWithAnInvalidProviderStillFailsStartup() {
    // The specific claim the class Javadoc makes: select() runs before isEnabled() is even
    // consulted, so a disabled channel does not get to hide a typo'd provider — otherwise
    // flipping enabled=true later would silently activate a configuration nobody re-checked.
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("fru.messaging.sms.provider", "stub");
    environment.setProperty("fru.messaging.whatsapp.provider", "bank-gateway");
    environment.setProperty("fru.messaging.whatsapp.enabled", "false");
    environment.setProperty("fru.messaging.email.provider", "stub");

    assertThrows(
        IllegalStateException.class,
        () ->
            new MessageSenderConfiguration().messageSender(environment, EMPTY_SEED, AIRTEL_CONFIG));
  }

  /**
   * The whole point of the memoised supplier. {@code select()} runs for every channel before {@code
   * isEnabled()}, so building the sender inside {@code select} would create a live {@code
   * HttpClient} — selector thread and connection pool — for a channel that is disabled and then
   * discarded. With every channel on the stub, the real sender must never be constructed at all;
   * {@code AIRTEL_MUST_NOT_BE_BUILT} would fail the test if it were.
   */
  @Test
  void theRealSenderIsNotBuiltWhenNoChannelSelectsIt() {
    for (MessageChannel channel : MessageChannel.values()) {
      assertInstanceOf(
          StubMessageSender.class,
          MessageSenderConfiguration.select(channel, "stub", EMPTY_SEED, AIRTEL_MUST_NOT_BE_BUILT),
          "channel " + channel.wireValue() + " must resolve to the stub without touching http");
    }
  }

  /**
   * The case the memoisation comment used to describe wrongly. {@code select()} runs before {@code
   * isEnabled()}, and the HTTP branch resolves the supplier immediately, so a channel that selects
   * {@code http} and is then disabled STILL builds the sender and still demands its credentials.
   * That is deliberate and matches this class's existing rule for a typo'd provider on a disabled
   * channel: a configuration nobody re-checked must not hide behind {@code enabled=false} and then
   * activate silently when someone flips it back.
   */
  @Test
  void httpOnADisabledSmsChannelStillValidatesItsCredentials() {
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("fru.messaging.sms.provider", "http");
    environment.setProperty("fru.messaging.sms.enabled", "false");
    environment.setProperty("fru.messaging.whatsapp.provider", "stub");
    environment.setProperty("fru.messaging.email.provider", "stub");
    AirtelSmsProperties unconfigured = new AirtelSmsProperties(null, null, null, null, null, null);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                new MessageSenderConfiguration()
                    .messageSender(environment, EMPTY_SEED, unconfigured));

    assertTrue(
        thrown.getMessage().contains("fru.messaging.sms.http.endpoint"), thrown.getMessage());
  }

  @Test
  void propertyNamesFollowTheChannelWireValue() {
    assertEquals(
        "fru.messaging.sms.provider",
        MessageSenderConfiguration.providerProperty(MessageChannel.SMS));
    assertEquals(
        "fru.messaging.whatsapp.provider",
        MessageSenderConfiguration.providerProperty(MessageChannel.WHATSAPP));
    assertEquals(
        "fru.messaging.email.enabled",
        MessageSenderConfiguration.enabledProperty(MessageChannel.EMAIL));
  }
}
