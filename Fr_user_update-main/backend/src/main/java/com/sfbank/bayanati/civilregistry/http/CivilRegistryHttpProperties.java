package com.sfbank.bayanati.civilregistry.http;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link HttpCivilRegistryClient}, bound from {@code fru.civil-registry.http.*}.
 *
 * <p>The endpoint is configuration, never hardcoded and never baked into a build (AD-002b: "HTTPS
 * on a hostname (configuration)"). The timeouts are configuration too, with deliberate defaults —
 * omitting a timeout is the dangerous case, not a wrong value.
 *
 * @param endpoint the full {@code GetCRSData} URL, e.g. {@code https://<registry
 *     host>:5353/CRSAPI/Services/GetCRSData}. No default: an {@code http} deployment that did not
 *     state it must fail at startup, not call nowhere.
 * @param connectTimeout how long to wait for the TCP+TLS handshake. Default 5 s — the registry
 *     shares the core-banking middleware's host and certificate chain, whose only observed
 *     round-trip was ~1.2 s, so this is ample headroom on the handshake alone.
 * @param readTimeout how long to wait for the response once connected. Default 15 s. No timing was
 *     ever recorded for this endpoint; the reply carries a base64 JPEG (~25 KB observed) plus an
 *     unmeasured server-side lookup, so this is wider than core banking's 10 s — and it stays
 *     inside the mobile client's 30 s receive timeout ({@code
 *     mobile/lib/core/network/dio_provider.dart}) so the backend never outlives the customer's
 *     socket. A wedged registry pauses the journey (customer.md Stage 9), it does not fail it, so
 *     the cost of waiting is a wait, not an error.
 */
@ConfigurationProperties(prefix = "fru.civil-registry.http")
public record CivilRegistryHttpProperties(
    URI endpoint, Duration connectTimeout, Duration readTimeout) {

  public CivilRegistryHttpProperties {
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
    readTimeout = readTimeout == null ? Duration.ofSeconds(15) : readTimeout;
  }
}
