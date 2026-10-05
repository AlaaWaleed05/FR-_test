package com.sfbank.bayanati.uqudo.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.uqudo.domain.UqudoClient;
import com.sfbank.bayanati.uqudo.http.HttpUqudoClient;
import com.sfbank.bayanati.uqudo.http.UqudoHttpProperties;
import com.sfbank.bayanati.uqudo.stub.StubUqudoClient;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The selection matrix — recognised, missing, empty, unrecognised — and the fail-loud rule that
 * makes an unset selector a startup failure rather than a silent stub. No Spring context: {@code
 * select} is static precisely so this can be exhaustive and fast.
 *
 * <p>Every value here is invented. The "credentials" are obviously-fake literals; nothing in this
 * file is or resembles a real Uqudo tenant secret (CLAUDE.md).
 */
class UqudoClientConfigurationTest {

  private static final StubUqudoClient STUB_CLIENT = new StubUqudoClient();

  private static UqudoHttpProperties fullyConfigured() {
    return new UqudoHttpProperties(
        URI.create("https://auth.uqudo.invalid/api"),
        URI.create("https://id.uqudo.invalid"),
        URI.create("https://id.uqudo.invalid/api/.well-known/jwks.json"),
        "test-client-id-not-real",
        "test-client-secret-not-real",
        "https://id.uqudo.invalid",
        Duration.ofSeconds(5),
        Duration.ofSeconds(15),
        Duration.ofMinutes(15));
  }

  @Test
  void stubSelectsTheStub() {
    assertEquals(
        STUB_CLIENT,
        UqudoClientConfiguration.select(
            UqudoClientConfiguration.STUB, STUB_CLIENT, fullyConfigured()));
  }

  @Test
  void surroundingWhitespaceIsToleratedOnTheSelector() {
    assertEquals(
        STUB_CLIENT, UqudoClientConfiguration.select("  stub  ", STUB_CLIENT, fullyConfigured()));
  }

  @Test
  void httpSelectsTheRealClient() {
    UqudoClient selected =
        UqudoClientConfiguration.select(
            UqudoClientConfiguration.HTTP, STUB_CLIENT, fullyConfigured());

    assertInstanceOf(HttpUqudoClient.class, selected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "https", "HTTP", "Stub", "real", "mock"})
  void anythingUnrecognisedFailsStartupNamingTheProperty(String selection) {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> UqudoClientConfiguration.select(selection, STUB_CLIENT, fullyConfigured()));

    assertTrue(thrown.getMessage().contains(UqudoClientConfiguration.CLIENT_PROPERTY));
    assertTrue(thrown.getMessage().contains("stub"));
    assertTrue(thrown.getMessage().contains("http"));
  }

  @Test
  void anUnsetSelectorFailsStartupRatherThanDefaultingToTheStub() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> UqudoClientConfiguration.select(null, STUB_CLIENT, fullyConfigured()));

    assertTrue(thrown.getMessage().contains("not set"));
  }

  @Test
  void httpWithNoConfigurationAtAllNamesEveryMissingKeyAtOnce() {
    UqudoHttpProperties empty =
        new UqudoHttpProperties(null, null, null, null, null, null, null, null, null);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                UqudoClientConfiguration.select(UqudoClientConfiguration.HTTP, STUB_CLIENT, empty));

    // All six named together: a half-configured environment should not need six restarts to learn
    // what it is missing.
    assertTrue(thrown.getMessage().contains("fru.uqudo.http.auth-url"));
    assertTrue(thrown.getMessage().contains("fru.uqudo.http.api-base"));
    assertTrue(thrown.getMessage().contains("fru.uqudo.http.jwks-url"));
    assertTrue(thrown.getMessage().contains("fru.uqudo.http.client-id"));
    assertTrue(thrown.getMessage().contains("fru.uqudo.http.client-secret"));
    assertTrue(thrown.getMessage().contains("fru.uqudo.http.issuer"));
  }

  @Test
  void theMissingConfigurationMessageNeverEchoesASecret() {
    UqudoHttpProperties secretButNoHosts =
        new UqudoHttpProperties(
            null, null, null, "id-value", "secret-value", null, null, null, null);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                UqudoClientConfiguration.select(
                    UqudoClientConfiguration.HTTP, STUB_CLIENT, secretButNoHosts));

    assertFalse(thrown.getMessage().contains("secret-value"));
    assertFalse(thrown.getMessage().contains("id-value"));
  }

  @Test
  void aBlankSecretCountsAsMissingRatherThanBeingSentAsAnEmptyCredential() {
    UqudoHttpProperties blankSecret =
        new UqudoHttpProperties(
            URI.create("https://auth.uqudo.invalid/api"),
            URI.create("https://id.uqudo.invalid"),
            URI.create("https://id.uqudo.invalid/api/.well-known/jwks.json"),
            "test-client-id-not-real",
            "   ",
            "https://id.uqudo.invalid",
            null,
            null,
            null);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                UqudoClientConfiguration.select(
                    UqudoClientConfiguration.HTTP, STUB_CLIENT, blankSecret));

    assertTrue(thrown.getMessage().contains("fru.uqudo.http.client-secret"));
  }

  @Test
  void timeoutsAndTheJwksWindowCarryDefaultsBecauseOmittingOneIsTheDangerousCase() {
    UqudoHttpProperties defaulted =
        new UqudoHttpProperties(
            URI.create("https://auth.uqudo.invalid/api"),
            URI.create("https://id.uqudo.invalid"),
            URI.create("https://id.uqudo.invalid/api/.well-known/jwks.json"),
            "test-client-id-not-real",
            "test-client-secret-not-real",
            "https://id.uqudo.invalid",
            null,
            null,
            null);

    assertEquals(Duration.ofSeconds(5), defaulted.connectTimeout());
    assertEquals(Duration.ofSeconds(15), defaulted.readTimeout());
    assertEquals(Duration.ofMinutes(15), defaulted.jwksCacheDuration());
  }

  @Test
  void endpointsAreAppendedToTheConfiguredBaseWithoutSwallowingItsOwnPath() {
    UqudoHttpProperties properties = fullyConfigured();

    // URI.resolve("/oauth/token") against https://auth.uqudo.invalid/api would silently drop
    // "/api" and call the wrong host. That is the bug this asserts against.
    assertEquals(
        URI.create("https://auth.uqudo.invalid/api/oauth/token"), properties.tokenEndpoint());
    assertEquals(
        URI.create("https://id.uqudo.invalid/api/v1/info/img/abc"),
        properties.imageEndpoint("abc"));
    assertEquals(
        URI.create("https://id.uqudo.invalid/api/v1/info/sess-1"),
        properties.sessionEndpoint("sess-1"));
    assertEquals(
        URI.create("https://id.uqudo.invalid/api/v1/face"), properties.faceSessionEndpoint());
  }

  @Test
  void aTrailingSlashOnAConfiguredBaseDoesNotProduceADoubleSlash() {
    UqudoHttpProperties trailing =
        new UqudoHttpProperties(
            URI.create("https://auth.uqudo.invalid/api/"),
            URI.create("https://id.uqudo.invalid/"),
            URI.create("https://id.uqudo.invalid/api/.well-known/jwks.json"),
            "id",
            "secret",
            "iss",
            null,
            null,
            null);

    assertEquals(
        URI.create("https://auth.uqudo.invalid/api/oauth/token"), trailing.tokenEndpoint());
    assertEquals(
        URI.create("https://id.uqudo.invalid/api/v1/face"), trailing.faceSessionEndpoint());
  }
}
