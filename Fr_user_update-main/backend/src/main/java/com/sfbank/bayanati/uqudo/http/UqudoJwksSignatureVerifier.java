package com.sfbank.bayanati.uqudo.http;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.sfbank.bayanati.uqudo.domain.JwsSignatureVerifier;
import com.sfbank.bayanati.uqudo.domain.JwsVerificationException;
import com.sfbank.bayanati.uqudo.domain.UqudoJwsParser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real signature step: RS256 against Uqudo's JWKS, plus the three envelope claims that only a
 * configured tenant can check ({@code iss}, {@code aud}, {@code iat}). Everything after this —
 * {@code exp} and every payload field name — is {@link UqudoJwsParser}, shared with the stub.
 *
 * <p>What is enforced, and why each one (docs/components/uqudo-sdk.md "JWS verification"):
 *
 * <ul>
 *   <li><strong>{@code alg} pinned to RS256.</strong> Not "whatever the header says" — that is the
 *       algorithm-confusion attack, and {@code none} is the degenerate case of it. Anything else,
 *       including any HS* or EC*, is rejected before a key is even looked up.
 *   <li><strong>{@code kid} must resolve in the JWKS.</strong> The {@link JWKSource} handed in
 *       caches, refreshes ahead and rate-limits, so an unknown {@code kid} triggers at most one
 *       fetch and never a per-request one. If it still does not resolve, this fails closed — an
 *       unrecognised signing key is never treated as an unsigned document.
 *   <li><strong>{@code iss} and {@code aud} must equal the configured values.</strong> {@code aud}
 *       is the tenant client id (observed S1-02), so this is what stops a JWS minted for a
 *       different Uqudo tenant from being accepted as ours.
 *   <li><strong>{@code iat} may not be in the future</strong> beyond {@link #CLOCK_SKEW}.
 * </ul>
 *
 * <p><strong>How far in the past {@code iat} may be is deliberately NOT bounded.</strong> The
 * vendor guidance reads "iat ±60s", but a symmetric window would reject the journey's own
 * legitimate retry: customer.md Stage 13 lets a customer whose upload dropped re-present the same
 * JWS for as long as it lives, measured at S1-02 as two hours. {@code exp} is what bounds the past
 * side, it is checked by the parser, and its failure is the distinct non-counting {@code
 * ArtifactExpiredException}. A 60-second past-bound here would have turned every resumed upload
 * into a counted verification failure.
 *
 * <p>No exception message ever carries a payload value — only a claim name and, for {@code alg},
 * the algorithm actually presented, which is not identity data.
 */
public class UqudoJwksSignatureVerifier implements JwsSignatureVerifier {

  /** Tolerance for a future-dated {@code iat}, i.e. our clock behind Uqudo's. */
  static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

  private final JWKSource<SecurityContext> jwkSource;
  private final String expectedIssuer;
  private final String expectedAudience;
  private final JsonMapper json = JsonMapper.builder().build();

  public UqudoJwksSignatureVerifier(
      JWKSource<SecurityContext> jwkSource, String expectedIssuer, String expectedAudience) {
    this.jwkSource = jwkSource;
    this.expectedIssuer = expectedIssuer;
    this.expectedAudience = expectedAudience;
  }

  @Override
  public JsonNode verifiedPayload(String jws) {
    JWSObject jwsObject = parse(jws);
    JWSHeader header = jwsObject.getHeader();

    if (!JWSAlgorithm.RS256.equals(header.getAlgorithm())) {
      throw new JwsVerificationException(
          "unexpected JWS algorithm '" + header.getAlgorithm() + "'; only RS256 is accepted");
    }
    if (header.getKeyID() == null || header.getKeyID().isBlank()) {
      throw new JwsVerificationException("JWS header carries no 'kid'");
    }

    RSAKey key = signingKey(header.getKeyID());
    try {
      if (!jwsObject.verify(new RSASSAVerifier(key))) {
        throw new JwsVerificationException("signature does not verify");
      }
    } catch (JwsVerificationException alreadyTyped) {
      throw alreadyTyped;
    } catch (Exception verificationFailed) {
      throw new JwsVerificationException(
          "signature could not be checked: " + verificationFailed.getClass().getSimpleName());
    }

    JsonNode root = payload(jwsObject);
    checkEnvelope(root);
    return root;
  }

  private JWSObject parse(String jws) {
    try {
      return JWSObject.parse(jws);
    } catch (Exception malformed) {
      // The exception's own message names a parsing problem, never a payload value.
      throw new JwsVerificationException("malformed JWS: " + malformed.getMessage());
    }
  }

  /**
   * Resolves {@code kid} through the caching, refresh-ahead, rate-limited {@link JWKSource}. Fails
   * closed on an unknown key: no fallback to another key, no "any RSA key will do".
   */
  private RSAKey signingKey(String kid) {
    List<JWK> matches;
    try {
      matches =
          jwkSource.get(
              new JWKSelector(
                  new JWKMatcher.Builder()
                      .keyID(kid)
                      .keyType(KeyType.RSA)
                      .keyUses(KeyUse.SIGNATURE, null)
                      .build()),
              null);
    } catch (Exception jwksUnavailable) {
      throw new JwsVerificationException(
          "the Uqudo JWKS could not be read: " + jwksUnavailable.getClass().getSimpleName());
    }
    if (matches.isEmpty()) {
      throw new JwsVerificationException("the JWS 'kid' does not match any key in the Uqudo JWKS");
    }
    JWK match = matches.get(0);
    if (!(match instanceof RSAKey rsaKey)) {
      throw new JwsVerificationException("the JWKS key for this 'kid' is not an RSA key");
    }
    return rsaKey;
  }

  private JsonNode payload(JWSObject jwsObject) {
    try {
      return json.readTree(jwsObject.getPayload().toString());
    } catch (Exception notJson) {
      throw new JwsVerificationException("payload is not a JSON document");
    }
  }

  /** {@code iss}, {@code aud} and the future half of {@code iat}. {@code exp} is the parser's. */
  private void checkEnvelope(JsonNode root) {
    if (!expectedIssuer.equals(text(root, "iss"))) {
      throw new JwsVerificationException("'iss' is not this tenant's Uqudo issuer");
    }
    if (!audienceMatches(root.path("aud"))) {
      throw new JwsVerificationException("'aud' is not this tenant's client id");
    }
    JsonNode iat = root.path("iat");
    if (!iat.isNumber()) {
      throw new JwsVerificationException("payload is missing required numeric field 'iat'");
    }
    if (Instant.ofEpochSecond(iat.asLong()).isAfter(Instant.now().plus(CLOCK_SKEW))) {
      throw new JwsVerificationException("'iat' is in the future beyond the accepted clock skew");
    }
  }

  /**
   * {@code aud} was a plain string on every JWS observed at S1-02, but RFC 7519 allows an array and
   * costs nothing to honour.
   */
  private boolean audienceMatches(JsonNode aud) {
    if (aud.isArray()) {
      for (JsonNode candidate : aud) {
        if (candidate.isString() && expectedAudience.equals(candidate.asString())) {
          return true;
        }
      }
      return false;
    }
    return aud.isString() && expectedAudience.equals(aud.asString());
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isString() ? value.asString() : null;
  }
}
