package com.sfbank.bayanati.civilregistry.stub;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Seed data for {@link StubCivilRegistryClient}, bound from {@code
 * fru.civil-registry.stub.outcomes}.
 *
 * @param outcomes the identity numbers the stub has an opinion about, keyed by the raw {@code
 *     identityNumber} string. Any identity number absent from this map returns {@link
 *     RegistryOutcome#OK}, mirroring {@code StubCoreBankingProperties}/{@code
 *     StubMessagingProperties}' unknown-means-the-honest-default reasoning. SYNTHETIC VALUES ONLY
 *     (CLAUDE.md hard rule).
 */
@ConfigurationProperties(prefix = "fru.civil-registry.stub")
public record StubCivilRegistryProperties(Map<String, RegistryOutcome> outcomes) {

  public StubCivilRegistryProperties {
    outcomes = outcomes == null ? Map.of() : Map.copyOf(outcomes);
  }
}
