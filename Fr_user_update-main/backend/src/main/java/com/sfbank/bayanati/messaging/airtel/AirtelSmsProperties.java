package com.sfbank.bayanati.messaging.airtel;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link AirtelSmsSender}, bound from {@code fru.messaging.sms.http.*}.
 *
 * <p>The endpoint is configuration, never hardcoded and never baked into a build — same rule as
 * {@code CoreBankingHttpProperties}. The credentials are configuration for a stronger reason: they
 * are secrets, and CLAUDE.md forbids them from the repository, a prompt, a log or a session report.
 * In a deployed environment they arrive from Secrets Manager; locally they live only in a
 * gitignored file. {@link #endpoint}, {@link #username}, {@link #password} and {@link #senderId}
 * therefore have <strong>no defaults</strong>: an {@code http} deployment that did not state them
 * fails at startup naming the properties, never the values.
 *
 * <p><strong>{@link #toString()} is overridden, and that is not decoration.</strong> A record's
 * generated {@code toString} prints every component, so a single {@code log.debug("props={}",
 * properties)} anywhere — or a Spring startup failure that includes the bean in its message — would
 * write the SMS gateway password into the log. The override makes that impossible rather than
 * merely discouraged.
 *
 * @param endpoint the full send URL, e.g. {@code https://<host>/api/html_send_sms/}
 * @param username the gateway account name
 * @param password the gateway account password. Travels in the QUERY STRING (the captured contract
 *     is a GET), which is why {@link AirtelSmsSender} never logs the request URI
 * @param senderId the customer-visible sender. Registered with the operator, not a per-message
 *     value — see {@code SmsPayload}'s note on why there is no per-call sender id. R-041 is live
 *     and accepted: an unregistered sender can be dropped silently by a network
 * @param connectTimeout TCP+TLS handshake budget. Default 5 s, matching the core banking adapter
 * @param readTimeout response budget once connected. Default 15 s — longer than core banking's 10 s
 *     because an INTERACTIVE OTP send happens on the request thread and a gateway is slower than a
 *     middleware lookup, but still bounded: the port requires an implementation to bound its own
 *     latency and return TRANSIENT_FAILURE on timeout
 */
@ConfigurationProperties(prefix = "fru.messaging.sms.http")
public record AirtelSmsProperties(
    URI endpoint,
    String username,
    String password,
    String senderId,
    Duration connectTimeout,
    Duration readTimeout) {

  public AirtelSmsProperties {
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
    readTimeout = readTimeout == null ? Duration.ofSeconds(15) : readTimeout;
  }

  /**
   * Never prints a credential. The endpoint and the sender id are not secrets and are printed
   * because they are what a misconfiguration diagnosis actually needs; the username and password
   * are reported only as set/not set.
   */
  @Override
  public String toString() {
    return "AirtelSmsProperties[endpoint="
        + endpoint
        + ", senderId="
        + senderId
        + ", username="
        + (username == null || username.isBlank() ? "not set" : "set")
        + ", password="
        + (password == null || password.isBlank() ? "not set" : "REDACTED")
        + ", connectTimeout="
        + connectTimeout
        + ", readTimeout="
        + readTimeout
        + "]";
  }
}
