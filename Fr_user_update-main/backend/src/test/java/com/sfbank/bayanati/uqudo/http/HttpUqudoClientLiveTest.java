package com.sfbank.bayanati.uqudo.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyType;
import com.sfbank.bayanati.uqudo.config.UqudoClientConfigurationTestAccess;
import com.sfbank.bayanati.uqudo.domain.UqudoJwsParser;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The real Uqudo tenant, called through the real adapter, using <strong>only the two calls that
 * touch no customer data at all</strong>: minting a client-credentials token, and reading the
 * public JWKS. Inert unless the credentials are present in the environment (from the local
 * gitignored config, never the repository) and the {@code live} group is enabled; excluded by
 * default alongside {@code integration} in pom.xml.
 *
 * <p>Why only these two: every other call needs a real scan — an image id, a session id, a portrait
 * — and there is no synthetic one. An image download or a purge against the borrowed FIB tenant
 * (R-001) would be reaching for another bank's customer data, so those paths are proven against
 * {@code MockRestServiceServer} only, on shapes S1-02 observed. What this test does prove is the
 * half that no mock can: that the configured hosts resolve, the TLS chain is accepted, the
 * credentials are the right shape, and the JWKS actually parses as RSA signing keys.
 *
 * <p>Nothing here prints or asserts a token value, a key value or a secret — lengths, counts and
 * booleans only (CLAUDE.md).
 *
 * <p>Run: {@code UQUDO_CLIENT_ID=… UQUDO_CLIENT_SECRET=… ./mvnw test -Dgroups=live
 * -Dexcluded.test.groups= -Dtest=HttpUqudoClientLiveTest}
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "UQUDO_CLIENT_ID", matches = ".+")
class HttpUqudoClientLiveTest {

  private static UqudoHttpProperties properties() {
    return new UqudoHttpProperties(
        URI.create(env("UQUDO_AUTH_URL", "https://auth.uqudo.io/api")),
        URI.create(env("UQUDO_API_BASE", "https://id.uqudo.io")),
        URI.create(env("UQUDO_JWKS_URL", "https://id.uqudo.io/api/.well-known/jwks.json")),
        System.getenv("UQUDO_CLIENT_ID"),
        System.getenv("UQUDO_CLIENT_SECRET"),
        env("UQUDO_ISSUER", "https://id.uqudo.io"),
        Duration.ofSeconds(5),
        Duration.ofSeconds(15),
        Duration.ofMinutes(15));
  }

  /**
   * Defaults here are test-only conveniences for a developer running this by hand, and are
   * deliberately NOT how the application resolves these — production configuration has no defaults
   * at all ({@code UqudoClientConfiguration.requireFullyConfigured}).
   */
  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }

  @Test
  void theRealTenantIssuesAnAccessToken() {
    UqudoHttpProperties properties = properties();
    HttpUqudoClient client =
        new HttpUqudoClient(
            UqudoClientConfigurationTestAccess.restClient(properties),
            properties,
            new UqudoJwsParser(jws -> null));

    String token = client.issueAccessToken().value();

    assertNotNull(token);
    assertFalse(token.isBlank());
    // Length only. The token is a bearer credential and never belongs in output.
    System.out.println("[live] uqudo token minted, length=" + token.length());
  }

  @Test
  void theRealJwksIsReadableAndCarriesRsaSigningKeys() throws Exception {
    List<JWK> keys =
        UqudoClientConfigurationTestAccess.jwkSource(properties())
            .get(new JWKSelector(new JWKMatcher.Builder().keyType(KeyType.RSA).build()), null);

    assertNotNull(keys);
    assertFalse(keys.isEmpty(), "the Uqudo JWKS returned no RSA keys");
    // Count and key ids only — a public key is not a secret, but there is no reason to print one.
    System.out.println("[live] uqudo jwks rsa keys=" + keys.size());
  }
}
