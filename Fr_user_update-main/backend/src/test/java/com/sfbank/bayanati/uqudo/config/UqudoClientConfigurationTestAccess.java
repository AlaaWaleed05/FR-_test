package com.sfbank.bayanati.uqudo.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.sfbank.bayanati.uqudo.http.UqudoHttpProperties;
import org.springframework.web.client.RestClient;

/**
 * Exposes {@link UqudoClientConfiguration}'s package-private {@code restClient} factory to the
 * {@code uqudo.http} tests, so a timeout test can exercise the real wiring rather than a hand-built
 * client that only looks like it. Mirrors {@code CivilRegistryClientConfigurationTestAccess}.
 */
public final class UqudoClientConfigurationTestAccess {

  private UqudoClientConfigurationTestAccess() {}

  public static RestClient restClient(UqudoHttpProperties properties) {
    return UqudoClientConfiguration.restClient(properties);
  }

  /**
   * The caching, refresh-ahead, rate-limited JWKS source, for the live test's reachability check.
   */
  public static JWKSource<SecurityContext> jwkSource(UqudoHttpProperties properties) {
    return UqudoClientConfiguration.jwkSource(properties);
  }
}
