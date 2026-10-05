package com.sfbank.bayanati.messaging.config;

import com.sfbank.bayanati.messaging.airtel.AirtelSmsProperties;
import com.sfbank.bayanati.messaging.airtel.AirtelSmsSender;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.service.ChannelRoutingMessageSender;
import com.sfbank.bayanati.messaging.stub.StubMessageSender;
import com.sfbank.bayanati.messaging.stub.StubMessagingProperties;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Selects a {@link MessageSender} implementation per channel from configuration, exactly the {@code
 * CoreBankingClientConfiguration} pattern (S3-01): the choice is made once, at startup, from one
 * property per channel, with no default — an unset, empty or unrecognised value fails startup
 * naming the property, what was found, and what is accepted.
 *
 * <p><strong>Enablement is a separate axis from selection.</strong> {@code
 * fru.messaging.<channel>.provider} must always resolve to a real implementation — a deployment
 * declares an implementation for every channel it might ever use, whether or not this environment
 * currently offers that channel. {@code fru.messaging.<channel>.enabled=false} then removes that
 * channel from the built {@link ChannelRoutingMessageSender}'s map entirely, so a message sent to a
 * disabled channel gets {@code ChannelRoutingMessageSender.CHANNEL_NOT_CONFIGURED} — a returned
 * result, not an exception. Stage 1b must read the same {@code enabled} flags before offering a
 * channel at all (R-042, docs/components/messaging.md).
 */
@Configuration
@EnableConfigurationProperties({StubMessagingProperties.class, AirtelSmsProperties.class})
public class MessageSenderConfiguration {

  /** The stub. Still the default choice for local development and every test. */
  public static final String STUB = "stub";

  /**
   * The real gateway. <strong>SMS only</strong> — the only real adapter this system has is {@link
   * AirtelSmsSender} (BL-080), and WhatsApp and email have no provider at all (product-owner
   * decision 2026-09-07: SMS is the only messaging channel in the release version). The value is
   * {@code http} rather than a provider name, matching {@code fru.core-banking.client=http} and
   * {@code fru.civil-registry.client=http}, so all three integrations read the same way.
   */
  public static final String HTTP = "http";

  private static final List<String> ACCEPTED_PROVIDERS = List.of(STUB, HTTP);

  @Bean
  public MessageSender messageSender(
      Environment environment,
      StubMessagingProperties stubProperties,
      AirtelSmsProperties airtelProperties) {
    // A Supplier, so a deployment that selects the stub for every channel never constructs the
    // real sender at all -- no HttpClient, no selector thread, no connection pool, and no demand
    // for credentials it does not need. theRealSenderIsNotBuiltWhenNoChannelSelectsIt holds that.
    //
    // What this does NOT do, deliberately: it does not skip construction for a channel that
    // selects 'http' and is then disabled. select() runs before isEnabled() below, and the HTTP
    // branch resolves the supplier immediately, so 'sms.provider=http' with 'sms.enabled=false'
    // still builds the sender and still demands its credentials at startup. That is the same rule
    // this class already applies to a typo'd provider on a disabled channel (see select's Javadoc):
    // a configuration nobody re-checked must not be able to hide behind enabled=false and then
    // activate silently when someone flips it back. httpOnADisabledSmsChannelStillValidatesIts-
    // Credentials holds that, so the behaviour is asserted rather than merely described here.
    Supplier<MessageSender> airtelSender = () -> airtelSmsSender(airtelProperties);

    Map<MessageChannel, MessageSender> byChannel = new EnumMap<>(MessageChannel.class);
    for (MessageChannel channel : MessageChannel.values()) {
      String providerProperty = providerProperty(channel);
      MessageSender sender =
          select(channel, environment.getProperty(providerProperty), stubProperties, airtelSender);
      if (isEnabled(environment, channel)) {
        byChannel.put(channel, sender);
      }
    }
    return new ChannelRoutingMessageSender(byChannel);
  }

  /** e.g. {@code fru.messaging.sms.provider}. */
  public static String providerProperty(MessageChannel channel) {
    return "fru.messaging." + channel.wireValue() + ".provider";
  }

  /** e.g. {@code fru.messaging.sms.enabled}. */
  public static String enabledProperty(MessageChannel channel) {
    return "fru.messaging." + channel.wireValue() + ".enabled";
  }

  private static boolean isEnabled(Environment environment, MessageChannel channel) {
    return environment.getProperty(enabledProperty(channel), Boolean.class, true);
  }

  /**
   * Package-private and static so selection can be tested for every input — recognised, missing,
   * empty and unrecognised — without a Spring context, exactly as {@code
   * CoreBankingClientConfiguration.select} is.
   *
   * <p>The implementation an unrecognised value fails <em>even for a disabled channel</em>: a
   * typo'd provider must be caught at startup regardless of whether the channel is currently
   * offered, otherwise flipping {@code enabled=true} later silently activates a broken
   * configuration nobody re-checked.
   */
  static MessageSender select(
      MessageChannel channel,
      String selection,
      StubMessagingProperties stubProperties,
      Supplier<MessageSender> airtelSender) {
    String normalised = selection == null ? "" : selection.trim();
    return switch (normalised) {
      case STUB -> new StubMessageSender(stubProperties);
      case HTTP -> {
        if (channel != MessageChannel.SMS) {
          throw new IllegalStateException(smsOnlyMessage(channel));
        }
        yield airtelSender.get();
      }
      default -> throw new IllegalStateException(rejectionMessage(channel, selection));
    };
  }

  /**
   * The real {@link RestClient} the Airtel adapter runs on, with both timeouts set explicitly.
   *
   * <p>Identical reasoning to {@code CoreBankingClientConfiguration.restClient}: on this classpath
   * (no Apache/Jetty/Netty client, no Boot http-client autoconfiguration) {@code
   * RestClient.builder().build()} has <em>no connect timeout and no read timeout</em>, so a wedged
   * gateway would park a request thread forever — and an INTERACTIVE OTP send happens on the
   * request thread with a customer watching. The connect timeout goes on the {@code HttpClient}
   * (the factory has no setter for it); the read timeout goes on the factory. Redirects are never
   * followed: a redirect would replay the query string — which carries the account password — to
   * whatever host the response named.
   */
  static RestClient restClient(AirtelSmsProperties properties) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(properties.readTimeout());
    return RestClient.builder().requestFactory(factory).build();
  }

  /**
   * Fails startup naming every missing property — and never a value, because two of them are
   * credentials (CLAUDE.md: no secrets in logs). Same discipline as {@code
   * CoreBankingClientConfiguration.requireEndpoint}: an environment that selected the real gateway
   * without configuring it must fail loudly here, not silently send nothing at 22:00.
   */
  static AirtelSmsSender airtelSmsSender(AirtelSmsProperties properties) {
    List<String> missing = new ArrayList<>();
    if (properties == null || properties.endpoint() == null) {
      missing.add("fru.messaging.sms.http.endpoint");
    }
    if (properties == null || isBlank(properties.username())) {
      missing.add("fru.messaging.sms.http.username");
    }
    if (properties == null || isBlank(properties.password())) {
      missing.add("fru.messaging.sms.http.password");
    }
    if (properties == null || isBlank(properties.senderId())) {
      missing.add("fru.messaging.sms.http.sender-id");
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          providerProperty(MessageChannel.SMS)
              + " is '"
              + HTTP
              + "' but these are not set: "
              + missing
              + ". The endpoint and the credentials are configuration, never defaults and never"
              + " committed — supply them from this environment's secret store.");
    }
    return new AirtelSmsSender(restClient(properties), properties);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String smsOnlyMessage(MessageChannel channel) {
    return providerProperty(channel)
        + " is '"
        + HTTP
        + "', but the only real adapter in this system is the SMS gateway (BL-080). WhatsApp and"
        + " email have no provider — SMS is the only messaging channel in the release version"
        + " (product-owner decision 2026-09-07). Set this channel to '"
        + STUB
        + "', or disable it with "
        + enabledProperty(channel)
        + "=false.";
  }

  private static String rejectionMessage(MessageChannel channel, String selection) {
    String found = selection == null ? "not set" : "'" + selection + "'";
    return providerProperty(channel)
        + " is "
        + found
        + "; accepted values are "
        + ACCEPTED_PROVIDERS
        + ". There is no default: a backend that silently sent a real customer's message into a"
        + " stub, or silently sent nothing, would be a serious defect and an invisible one. Set it"
        + " to 'stub' for local development and testing, or to the real implementation's name in"
        + " any environment that must reach a real provider.";
  }
}
