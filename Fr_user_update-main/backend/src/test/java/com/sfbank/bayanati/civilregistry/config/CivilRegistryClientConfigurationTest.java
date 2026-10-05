package com.sfbank.bayanati.civilregistry.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.civilregistry.domain.CivilRegistryClient;
import com.sfbank.bayanati.civilregistry.http.CivilRegistryHttpProperties;
import com.sfbank.bayanati.civilregistry.http.HttpCivilRegistryClient;
import com.sfbank.bayanati.civilregistry.stub.RegistryOutcome;
import com.sfbank.bayanati.civilregistry.stub.StubCivilRegistryClient;
import com.sfbank.bayanati.civilregistry.stub.StubCivilRegistryProperties;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class CivilRegistryClientConfigurationTest {

  private static final StubCivilRegistryClient STUB =
      new StubCivilRegistryClient(
          new StubCivilRegistryProperties(Map.of("NID-MISSING", RegistryOutcome.NOT_FOUND)));

  private static final CivilRegistryHttpProperties HTTP =
      new CivilRegistryHttpProperties(
          URI.create("https://registry.invalid:5353/CRSAPI/Services/GetCRSData"),
          Duration.ofSeconds(5),
          Duration.ofSeconds(15));

  /** Binding with no fru.civil-registry.http.* properties at all: Spring passes nulls. */
  private static final CivilRegistryHttpProperties HTTP_UNCONFIGURED =
      new CivilRegistryHttpProperties(null, null, null);

  @Test
  void selectingTheStubReturnsTheAlwaysConstructedStubBean() {
    // The stub bean is constructed regardless (integration tests autowire it for
    // overrideOutcome), so the selection must hand back that same instance, not a second one.
    CivilRegistryClient client = CivilRegistryClientConfiguration.select("stub", STUB, HTTP);

    assertSame(STUB, client);
  }

  @Test
  void selectingHttpBuildsTheRealAdapter() {
    CivilRegistryClient client = CivilRegistryClientConfiguration.select("http", STUB, HTTP);

    assertInstanceOf(HttpCivilRegistryClient.class, client);
  }

  @Test
  void selectingHttpWithoutAnEndpointFailsAtStartupNamingTheProperty() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> CivilRegistryClientConfiguration.select("http", STUB, HTTP_UNCONFIGURED));

    assertTrue(
        thrown.getMessage().contains("fru.civil-registry.http.endpoint"), thrown.getMessage());
  }

  @Test
  void theStubDoesNotNeedAnEndpoint() {
    assertSame(STUB, CivilRegistryClientConfiguration.select("stub", STUB, HTTP_UNCONFIGURED));
  }

  @Test
  void surroundingWhitespaceInTheSelectorIsTolerated() {
    assertSame(STUB, CivilRegistryClientConfiguration.select("  stub  ", STUB, HTTP));
  }

  @Test
  void theTimeoutDefaultsAreTheDocumentedOnes() {
    // 5 s connect / 15 s read: what application.properties documents and the research derived.
    assertEquals(Duration.ofSeconds(5), HTTP_UNCONFIGURED.connectTimeout());
    assertEquals(Duration.ofSeconds(15), HTTP_UNCONFIGURED.readTimeout());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   ", "stubb", "htpp", "real", "STUB", "HTTP", "true"})
  void anythingOtherThanARecognisedValueFailsAtStartupNamingWhatItFound(String selection) {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> CivilRegistryClientConfiguration.select(selection, STUB, HTTP));

    String message = thrown.getMessage();
    assertTrue(
        message.contains(CivilRegistryClientConfiguration.CLIENT_PROPERTY),
        "must name the property to set, got: " + message);
    assertTrue(message.contains("[stub, http]"), "must name the accepted values, got: " + message);
    if (selection == null) {
      assertTrue(message.contains("not set"), message);
    } else {
      assertTrue(
          message.contains("'" + selection + "'"), "must quote what it found, got: " + message);
    }
  }

  @Test
  void thePropertyNameIsTheOneApplicationPropertiesDocuments() {
    assertEquals("fru.civil-registry.client", CivilRegistryClientConfiguration.CLIENT_PROPERTY);
  }
}
