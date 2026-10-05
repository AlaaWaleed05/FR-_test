package com.sfbank.bayanati.uqudo.domain;

import tools.jackson.databind.JsonNode;

/**
 * The one thing that differs between a stubbed and a real Uqudo client: how a compact JWS's
 * signature is checked. The stub verifies HS256 against a fixed stub-only secret; the real client
 * verifies RS256 against Uqudo's JWKS. Everything after the signature — {@code exp}, the claim
 * bindings, and every field name in the payload — is identical for both, and lives in {@link
 * UqudoJwsParser}.
 *
 * <p>This seam is what keeps CLAUDE.md's rule true with two implementations in the tree: the parser
 * stays quarantined in a single class, and neither client duplicates a field name.
 */
@FunctionalInterface
public interface JwsSignatureVerifier {

  /**
   * Verifies the signature and returns the payload as JSON. Implementations check the signature and
   * nothing else — no {@code exp}, no claim bindings, both of which are the parser's job.
   *
   * @throws JwsVerificationException if the JWS is malformed, the algorithm is not the one this
   *     verifier accepts, the signing key is unknown, the signature does not verify, or the payload
   *     is not a JSON document
   */
  JsonNode verifiedPayload(String jws);
}
