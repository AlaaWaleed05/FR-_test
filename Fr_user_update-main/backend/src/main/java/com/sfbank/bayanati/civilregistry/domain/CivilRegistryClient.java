package com.sfbank.bayanati.civilregistry.domain;

/**
 * The bank's Civil Registry lookup (docs/components/civil-registry.md), reached only for journey
 * Stage 9 with Uqudo's {@code identityNumber} — never {@code documentNumber}. Implementations are
 * selected by configuration, never a runtime {@code if (mock)} branch — see {@code
 * CivilRegistryClientConfiguration}. The stub returns a synthetic record; {@code
 * civilregistry.http.HttpCivilRegistryClient} calls the real service.
 *
 * <p>The contract is AD-002b's, closed by product-owner decision on 2026-09-04 and deliberately
 * coarse: a 2xx JSON object whose {@code IDENTITY_NUMBER} equals the number sent is this customer's
 * record; <em>any other response</em> — the HTTP 400 HTML page the service uses for an unknown
 * number, a 5xx, a 204, an empty or non-JSON body, a record with a different {@code
 * IDENTITY_NUMBER} — is "invalid / not found"; and <em>no response</em> is "did not reach the
 * registry". customer.md Stage 9 pauses the journey identically for the last two, while {@code
 * app.registry_result.state} still records which one happened.
 */
public interface CivilRegistryClient {

  /**
   * {@code POST /CRSAPI/Services/GetCRSData} with {@code {"NID": identityNumber}}, the number
   * forwarded exactly as scanned — no length, structure or check-digit validation (Q10).
   *
   * @return the classified answer: {@link RegistryLookup#found()} true with the parsed record
   *     ({@code app.registry_result.state = 'ok'}), or false with the reason ({@code state =
   *     'not_found'})
   * @throws RegistryUnreachableException when no response arrived at all — connect or read timeout,
   *     refused connection, TLS or DNS failure ({@code state = 'unreachable'})
   */
  RegistryLookup lookup(String identityNumber);
}
