package com.sfbank.bayanati.civilregistry.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.civilregistry.config.CivilRegistryClientConfigurationTestAccess;
import com.sfbank.bayanati.civilregistry.domain.RegistryLookup;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The real Civil Registry, called through the real adapter with the <strong>one kind of value the
 * product owner authorised and that returns no person's record</strong>: a three-zero {@code NID},
 * which the five authorised tests of 2026-09-04 showed answers with the HTTP 400 {@code text/html}
 * page. Inert unless {@code FRU_CIVIL_REGISTRY_LIVE_ENDPOINT} names the endpoint (configuration,
 * never committed) and the {@code live} group is enabled; excluded by default alongside {@code
 * integration} in pom.xml.
 *
 * <p>Why only this path: every other value already exercised against the service ({@code 0}, eleven
 * zeros) returns a real citizen's populated record, and the product owner knows no synthetic test
 * number (Q8). A success-path live test would pull a stranger's PII into a test run; the {@code
 * matched} path is therefore proven against the mock only. Nothing here prints or keeps a body:
 * status, content type and byte count only.
 *
 * <p>Run: {@code FRU_CIVIL_REGISTRY_LIVE_ENDPOINT=<url> ./mvnw test -Dgroups=live
 * -Dexcluded.test.groups= -Dtest=HttpCivilRegistryClientLiveTest}
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "FRU_CIVIL_REGISTRY_LIVE_ENDPOINT", matches = "https://.+")
class HttpCivilRegistryClientLiveTest {

  private HttpCivilRegistryClient client() {
    CivilRegistryHttpProperties properties =
        new CivilRegistryHttpProperties(
            URI.create(System.getenv("FRU_CIVIL_REGISTRY_LIVE_ENDPOINT")),
            Duration.ofSeconds(5),
            Duration.ofSeconds(15));
    return new HttpCivilRegistryClient(
        CivilRegistryClientConfigurationTestAccess.restClient(properties), properties);
  }

  @Test
  void aThreeZeroValueIsTheServicesOwnNotFoundPage() {
    RegistryLookup lookup = client().lookup("000");

    assertNotNull(lookup.exchange());
    System.out.println(
        "[live] input=000 http="
            + lookup.exchange().httpStatus()
            + " content-type="
            + lookup.exchange().responseMediaType()
            + " bytes="
            + lookup.exchange().responseBody().length
            + " reason="
            + lookup.reason());
    assertFalse(lookup.found());
    assertEquals(RegistryLookup.NON_SUCCESS_STATUS, lookup.reason());
    assertEquals(400, lookup.exchange().httpStatus());
    assertTrue(lookup.exchange().responseMediaType().startsWith("text/html"));
  }
}
