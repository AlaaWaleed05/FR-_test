package com.sfbank.bayanati.corebanking.http;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link HttpCoreBankingClient}, bound from {@code fru.core-banking.http.*}.
 *
 * <p>The endpoint is configuration, never hardcoded and never baked into a build (PROJECT_PLAN.md
 * Constraints: "Host and path are configuration") — the middleware host changes before production.
 * The timeouts are configuration too, so a wedged middleware can be tuned around without a rebuild;
 * they carry sensible defaults because omitting a timeout is the dangerous case, not a wrong value.
 *
 * @param endpoint the full {@code CheckAccount} URL, e.g. {@code
 *     https://<host>:9494/OMNI_PH3/resources/bankRoutes/CheckAccount}. No default: an {@code http}
 *     deployment that did not state it must fail at startup, not call nowhere.
 * @param connectTimeout how long to wait for the TCP+TLS handshake. Default 5 s — observed
 *     round-trips were ~1.2 s from outside the bank, so this is ample headroom on the handshake
 *     alone.
 * @param readTimeout how long to wait for the response once connected. Default 10 s — the reply is
 *     ~60 bytes, so this is ~8× the whole observed round-trip; its real job is to stop a wedged
 *     middleware from parking a request thread indefinitely.
 */
@ConfigurationProperties(prefix = "fru.core-banking.http")
public record CoreBankingHttpProperties(
    URI endpoint, Duration connectTimeout, Duration readTimeout) {

  public CoreBankingHttpProperties {
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
    readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
  }
}
