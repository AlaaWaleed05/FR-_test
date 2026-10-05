package com.sfbank.bayanati.corebanking.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.corebanking.domain.CoreBankingClient;
import com.sfbank.bayanati.corebanking.http.CoreBankingHttpProperties;
import com.sfbank.bayanati.corebanking.http.HttpCoreBankingClient;
import com.sfbank.bayanati.corebanking.stub.StubCoreBankingClient;
import com.sfbank.bayanati.corebanking.stub.StubCoreBankingProperties;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class CoreBankingClientConfigurationTest {

  private static final StubCoreBankingProperties SEED =
      new StubCoreBankingProperties(Map.of("0000000001", 1));

  private static final CoreBankingHttpProperties HTTP =
      new CoreBankingHttpProperties(
          URI.create("https://middleware.invalid:9494/OMNI_PH3/resources/bankRoutes/CheckAccount"),
          Duration.ofSeconds(5),
          Duration.ofSeconds(10));

  /** Binding with no fru.core-banking.http.* properties at all: Spring passes nulls. */
  private static final CoreBankingHttpProperties HTTP_UNCONFIGURED =
      new CoreBankingHttpProperties(null, null, null);

  @Test
  void selectingTheStubBuildsTheStubClientFromItsConfiguredSeed() {
    CoreBankingClient client = CoreBankingClientConfiguration.select("stub", SEED, HTTP);

    assertInstanceOf(StubCoreBankingClient.class, client);
    assertEquals(1, client.check("0000000001").code());
  }

  @Test
  void selectingHttpBuildsTheRealAdapter() {
    CoreBankingClient client = CoreBankingClientConfiguration.select("http", SEED, HTTP);

    assertInstanceOf(HttpCoreBankingClient.class, client);
  }

  @Test
  void selectingHttpWithoutAnEndpointFailsAtStartupNamingTheProperty() {
    // The middleware URL is configuration, never a default (PROJECT_PLAN.md Constraints). An
    // http deployment that forgot it must not start and call nowhere.
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> CoreBankingClientConfiguration.select("http", SEED, HTTP_UNCONFIGURED));

    assertTrue(thrown.getMessage().contains("fru.core-banking.http.endpoint"), thrown.getMessage());
  }

  @Test
  void theStubDoesNotNeedAnEndpoint() {
    assertInstanceOf(
        StubCoreBankingClient.class,
        CoreBankingClientConfiguration.select("stub", SEED, HTTP_UNCONFIGURED));
  }

  @Test
  void surroundingWhitespaceInTheSelectorIsTolerated() {
    // An env var or a properties file trailing a space should not take a bank's backend down.
    assertInstanceOf(
        StubCoreBankingClient.class, CoreBankingClientConfiguration.select("  stub  ", SEED, HTTP));
  }

  @Test
  void theTimeoutDefaultsAreTheDocumentedOnes() {
    // Omitting a timeout is the dangerous case, so the record fills them in rather than leaving
    // the client unbounded. 5 s / 10 s are what application.properties documents.
    assertEquals(Duration.ofSeconds(5), HTTP_UNCONFIGURED.connectTimeout());
    assertEquals(Duration.ofSeconds(10), HTTP_UNCONFIGURED.readTimeout());
  }

  /**
   * Missing, empty, and — the case a sentinel-bean guard misses — a plain typo. All must fail at
   * startup rather than leaving no CoreBankingClient bean and a "no qualifying bean" error several
   * beans away from the cause. "oracle" is here on purpose: the value S3-01's comments once
   * promised is not, and must never silently become, a recognised selection.
   */
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   ", "stubb", "htpp", "oracle", "STUB", "HTTP", "true"})
  void anythingOtherThanARecognisedValueFailsAtStartupNamingWhatItFound(String selection) {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> CoreBankingClientConfiguration.select(selection, SEED, HTTP));

    String message = thrown.getMessage();
    assertTrue(
        message.contains(CoreBankingClientConfiguration.CLIENT_PROPERTY),
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
    assertEquals("fru.core-banking.client", CoreBankingClientConfiguration.CLIENT_PROPERTY);
  }
}
