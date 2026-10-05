package com.sfbank.bayanati.civilregistry.stub;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.civilregistry.domain.RegistryLookup;
import com.sfbank.bayanati.civilregistry.domain.RegistryUnreachableException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StubCivilRegistryClientTest {

  @Test
  void unknownIdentityNumberDefaultsToOkWithTheRequestedNumberAsTheReturnedOne() {
    StubCivilRegistryClient client =
        new StubCivilRegistryClient(new StubCivilRegistryProperties(Map.of()));

    RegistryLookup lookup = client.lookup("ANY-NID");

    assertTrue(lookup.found());
    assertEquals("ANY-NID", lookup.record().identityNumber());
    assertEquals("ANY-NID", lookup.identityNumberReturned());
    assertArrayEquals(StubCivilRegistryClient.SYNTHETIC_PHOTOGRAPH, lookup.record().photograph());
  }

  @Test
  void theStubInventsNoExchangeAndNoReason() {
    // No HTTP call happened, so nothing may reach the audit trail as a "raw" artifact.
    StubCivilRegistryClient client =
        new StubCivilRegistryClient(new StubCivilRegistryProperties(Map.of()));

    RegistryLookup lookup = client.lookup("ANY-NID");

    assertNull(lookup.exchange());
    assertNull(lookup.reason());
  }

  @Test
  void configuredNotFoundReturnsNoRecord() {
    StubCivilRegistryClient client =
        new StubCivilRegistryClient(
            new StubCivilRegistryProperties(Map.of("NID-MISSING", RegistryOutcome.NOT_FOUND)));

    RegistryLookup lookup = client.lookup("NID-MISSING");

    assertFalse(lookup.found());
    assertNull(lookup.record());
    assertNull(lookup.identityNumberReturned());
    assertNull(lookup.exchange());
  }

  @Test
  void configuredUnreachableThrowsWithNoExchange() {
    StubCivilRegistryClient client =
        new StubCivilRegistryClient(
            new StubCivilRegistryProperties(Map.of("NID-DOWN", RegistryOutcome.UNREACHABLE)));

    RegistryUnreachableException thrown =
        assertThrows(RegistryUnreachableException.class, () -> client.lookup("NID-DOWN"));

    assertNull(thrown.exchange());
  }

  @Test
  void overrideOutcomeFlipsAnAlreadyConfiguredIdentityNumber() {
    StubCivilRegistryClient client =
        new StubCivilRegistryClient(
            new StubCivilRegistryProperties(Map.of("NID-FLIP", RegistryOutcome.UNREACHABLE)));

    assertThrows(RegistryUnreachableException.class, () -> client.lookup("NID-FLIP"));

    client.overrideOutcome("NID-FLIP", RegistryOutcome.OK);

    assertTrue(client.lookup("NID-FLIP").found());
  }
}
