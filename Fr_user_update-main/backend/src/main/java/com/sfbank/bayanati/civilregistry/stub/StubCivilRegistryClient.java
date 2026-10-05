package com.sfbank.bayanati.civilregistry.stub;

import com.sfbank.bayanati.civilregistry.domain.CivilRegistryClient;
import com.sfbank.bayanati.civilregistry.domain.RegistryLookup;
import com.sfbank.bayanati.civilregistry.domain.RegistryLookupResult;
import com.sfbank.bayanati.civilregistry.domain.RegistryUnreachableException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stands in for the bank's Civil Registry (docs/components/civil-registry.md) in local development
 * and every test. Its three outcomes are exactly the real adapter's three (AD-002b): a matching
 * record, "not found", and "no response".
 *
 * <p><strong>How a caller chooses which identity numbers return which outcome:</strong> {@link
 * StubCivilRegistryProperties#outcomes()} is a lookup table supplied entirely by configuration,
 * keyed by the raw {@code identityNumber}, mirroring {@code StubCoreBankingClient}/{@code
 * StubMessageSender}. Unknown-means-{@link RegistryOutcome#OK} is the honest default.
 *
 * <p><strong>{@link #overrideOutcome} is not part of {@link CivilRegistryClient}.</strong> It is a
 * test-only control surface on the concrete bean — seeded from {@link StubCivilRegistryProperties}
 * at construction, mutable afterward — that lets an integration test flip an identity number from
 * {@code UNREACHABLE} to {@code OK} between two calls, proving customer.md Stage 9's "on resume the
 * backend simply retries the lookup" without inventing a call-count-based flakiness mechanism.
 *
 * <p>The result carries <strong>no raw exchange and no reason</strong>: there was no HTTP call, and
 * inventing request/response bytes would put a fabricated artifact into the audit trail. Against
 * the stub, {@code IdentityScanService} therefore writes the lookup event without artifacts.
 *
 * <p>Selected by configuration and never by a runtime branch — there is no {@code if (stub)}
 * anywhere in the call path. See {@code CivilRegistryClientConfiguration}.
 */
public class StubCivilRegistryClient implements CivilRegistryClient {

  /** The bytes the synthetic record's portrait decodes to — visibly not an image. */
  public static final byte[] SYNTHETIC_PHOTOGRAPH =
      "stub-photograph-not-a-real-image".getBytes(StandardCharsets.UTF_8);

  private final Map<String, RegistryOutcome> outcomes;

  public StubCivilRegistryClient(StubCivilRegistryProperties properties) {
    this.outcomes = new ConcurrentHashMap<>(properties.outcomes());
  }

  /** Test-only: see the class Javadoc. */
  public void overrideOutcome(String identityNumber, RegistryOutcome outcome) {
    outcomes.put(identityNumber, outcome);
  }

  @Override
  public RegistryLookup lookup(String identityNumber) {
    RegistryOutcome outcome = outcomes.getOrDefault(identityNumber, RegistryOutcome.OK);
    return switch (outcome) {
      case OK -> new RegistryLookup(sampleResult(identityNumber), identityNumber, null, null);
      case NOT_FOUND -> new RegistryLookup(null, null, null, null);
      case UNREACHABLE ->
          throw new RegistryUnreachableException(
              "stub configured to simulate an unreachable registry for " + identityNumber);
    };
  }

  /**
   * One fixed, entirely synthetic record matching civil-registry.md's observed field shape
   * (CLAUDE.md hard rule: no real customer data, anywhere), keyed by the number asked for — as the
   * real service's {@code IDENTITY_NUMBER} is in a successful lookup.
   */
  private static RegistryLookupResult sampleResult(String identityNumber) {
    return new RegistryLookupResult(
        identityNumber,
        "محمد",
        "الطيب",
        "عبدالله",
        "إبراهيم",
        "فاطمة",
        "حسن",
        "عثمان",
        "آدم",
        "Mohammed",
        "Altayeb",
        "m",
        LocalDate.of(1990, 1, 1),
        "الخرطوم , محلية تجريبية , حي تجريبي , مربع تجريبي",
        SYNTHETIC_PHOTOGRAPH.clone());
  }
}
