package com.sfbank.bayanati.messaging.config;

import com.sfbank.bayanati.messaging.airtel.AirtelSmsProperties;
import org.springframework.web.client.RestClient;

/**
 * Reaches {@link MessageSenderConfiguration}'s package-private {@code restClient} factory from
 * another package, so a test can exercise the timeout wiring itself against a real socket.
 *
 * <p>Mirrors {@code CoreBankingClientConfigurationTestAccess}. The factory stays package-private in
 * production code — it is not part of the configuration's API — and this shim exists only so {@code
 * AirtelSmsSenderTimeoutTest} can build the same client the application builds, rather than an
 * approximation of it that could drift.
 */
public final class MessageSenderConfigurationTestAccess {

  private MessageSenderConfigurationTestAccess() {}

  public static RestClient restClient(AirtelSmsProperties properties) {
    return MessageSenderConfiguration.restClient(properties);
  }
}
