package com.sfbank.bayanati.uqudo.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.Base64URL;
import com.sfbank.bayanati.uqudo.domain.JwsVerificationException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The security core of the real adapter: what it accepts, and — more importantly — what it refuses.
 * A locally generated RSA keypair stands in for Uqudo's, served through an in-memory {@link
 * ImmutableJWKSet}, so every rejection path is exercised without a network.
 *
 * <p>Values are structural only: no name, no number, no document. The keypair is generated per test
 * run and is not a credential.
 */
class UqudoJwksSignatureVerifierTest {

  private static final String KID = "test-kid-1";
  private static final String ISSUER = "https://id.uqudo.invalid";
  private static final String AUDIENCE = "test-client-id-not-real";

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static RSAKey signingKey;
  private static JWKSource<SecurityContext> jwkSource;

  @BeforeAll
  static void generateKeys() throws Exception {
    signingKey = new RSAKeyGenerator(2048).keyID(KID).keyUse(KeyUse.SIGNATURE).generate();
    jwkSource = new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()));
  }

  private static UqudoJwksSignatureVerifier verifier() {
    return new UqudoJwksSignatureVerifier(jwkSource, ISSUER, AUDIENCE);
  }

  private static Map<String, Object> claims() {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", ISSUER);
    claims.put("aud", AUDIENCE);
    claims.put("iat", Instant.now().getEpochSecond());
    claims.put("exp", Instant.now().plusSeconds(7200).getEpochSecond());
    claims.put("jti", "session-1");
    return claims;
  }

  private static String signRs256(Map<String, Object> claims, String kid) {
    try {
      JWSObject jws =
          new JWSObject(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(),
              new Payload(MAPPER.writeValueAsString(claims)));
      jws.sign(new RSASSASigner(signingKey));
      return jws.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ---- the accept path ----

  @Test
  void aGenuineRs256JwsIsAcceptedAndItsPayloadReturned() {
    JsonNode payload = verifier().verifiedPayload(signRs256(claims(), KID));

    assertEquals("session-1", payload.path("jti").asString());
    assertTrue(payload.path("exp").isNumber());
  }

  @Test
  void expIsNotCheckedHereBecauseItIsTheParsersJobAndHasItsOwnNonCountingOutcome() {
    Map<String, Object> expired = claims();
    expired.put("exp", Instant.now().minusSeconds(60).getEpochSecond());

    // No throw: an expired-but-genuine JWS must reach UqudoJwsParser, which raises the distinct
    // ArtifactExpiredException that does NOT count against the customer's retry budget. Rejecting
    // it here would silently turn a stale artifact into a counted verification failure.
    JsonNode payload = verifier().verifiedPayload(signRs256(expired, KID));

    assertTrue(payload.path("exp").isNumber());
  }

  // ---- signature and algorithm ----

  @Test
  void aTamperedPayloadIsRejected() {
    String valid = signRs256(claims(), KID);
    String[] parts = valid.split("\\.", 3);
    ObjectNode payload = (ObjectNode) MAPPER.readTree(new Base64URL(parts[1]).decodeToString());
    payload.put("jti", "session-1-tampered");
    String tampered =
        parts[0]
            + "."
            + Base64URL.encode(MAPPER.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8))
            + "."
            + parts[2];

    assertThrows(JwsVerificationException.class, () -> verifier().verifiedPayload(tampered));
  }

  @Test
  void anHs256JwsIsRejectedEvenThoughItIsWellFormed() throws Exception {
    // Algorithm confusion: a symmetric signature must never be accepted where RS256 is expected.
    JWSObject hs256 =
        new JWSObject(
            new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KID).build(),
            new Payload(MAPPER.writeValueAsString(claims())));
    hs256.sign(new MACSigner("a-32-byte-secret-for-hs256-only!".getBytes(StandardCharsets.UTF_8)));

    JwsVerificationException thrown =
        assertThrows(
            JwsVerificationException.class, () -> verifier().verifiedPayload(hs256.serialize()));

    assertTrue(thrown.getMessage().contains("RS256"));
  }

  @Test
  void anUnsecuredNoneAlgorithmJwsIsRejected() {
    String header = Base64URL.encode("{\"alg\":\"none\",\"kid\":\"" + KID + "\"}").toString();
    String payload =
        Base64URL.encode(MAPPER.writeValueAsString(claims()).getBytes(StandardCharsets.UTF_8))
            .toString();

    assertThrows(
        JwsVerificationException.class,
        () -> verifier().verifiedPayload(header + "." + payload + "."));
  }

  @Test
  void anUnknownKidFailsClosedRatherThanFallingBackToAnyKey() {
    // Signed with the real key but announcing a kid the JWKS does not carry. Accepting it would
    // mean "any RSA key in the set will do", which is the whole point of a kid.
    assertThrows(
        JwsVerificationException.class,
        () -> verifier().verifiedPayload(signRs256(claims(), "some-other-kid")));
  }

  @Test
  void aJwsWithNoKidAtAllIsRejected() throws Exception {
    JWSObject noKid =
        new JWSObject(
            new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
            new Payload(MAPPER.writeValueAsString(claims())));
    noKid.sign(new RSASSASigner(signingKey));

    JwsVerificationException thrown =
        assertThrows(
            JwsVerificationException.class, () -> verifier().verifiedPayload(noKid.serialize()));

    assertTrue(thrown.getMessage().contains("kid"));
  }

  @Test
  void garbageIsRejectedAsMalformedRatherThanCrashing() {
    assertThrows(JwsVerificationException.class, () -> verifier().verifiedPayload("not-a-jws"));
  }

  // ---- envelope claims ----

  @Test
  void aJwsFromAnotherUqudoTenantIsRejectedOnAud() {
    Map<String, Object> otherTenant = claims();
    otherTenant.put("aud", "a-different-tenant-client-id");

    JwsVerificationException thrown =
        assertThrows(
            JwsVerificationException.class,
            () -> verifier().verifiedPayload(signRs256(otherTenant, KID)));

    assertTrue(thrown.getMessage().contains("aud"));
    assertFalse(thrown.getMessage().contains("a-different-tenant-client-id"));
  }

  @Test
  void anAudienceArrayContainingOurClientIdIsAccepted() {
    Map<String, Object> arrayAud = claims();
    arrayAud.put("aud", List.of("someone-else", AUDIENCE));

    assertEquals(
        "session-1", verifier().verifiedPayload(signRs256(arrayAud, KID)).path("jti").asString());
  }

  @Test
  void aWrongIssuerIsRejected() {
    Map<String, Object> wrongIssuer = claims();
    wrongIssuer.put("iss", "https://id.somewhere-else.invalid");

    JwsVerificationException thrown =
        assertThrows(
            JwsVerificationException.class,
            () -> verifier().verifiedPayload(signRs256(wrongIssuer, KID)));

    assertTrue(thrown.getMessage().contains("iss"));
  }

  @Test
  void anIatFurtherAheadThanTheClockSkewIsRejected() {
    Map<String, Object> future = claims();
    future.put(
        "iat",
        Instant.now()
            .plus(UqudoJwksSignatureVerifier.CLOCK_SKEW)
            .plusSeconds(120)
            .getEpochSecond());

    assertThrows(
        JwsVerificationException.class, () -> verifier().verifiedPayload(signRs256(future, KID)));
  }

  @Test
  void anIatWellInThePastIsAcceptedBecauseTheJourneyAllowsATwoHourResumeRetry() {
    // customer.md Stage 13: a dropped upload may be re-presented for as long as the JWS lives,
    // measured at S1-02 as two hours. The vendor's "iat +/- 60s" guidance read symmetrically would
    // reject every one of those, turning a supported resume into a counted verification failure.
    Map<String, Object> nearlyTwoHoursOld = claims();
    Instant issued = Instant.now().minus(Duration.ofMinutes(115));
    nearlyTwoHoursOld.put("iat", issued.getEpochSecond());
    nearlyTwoHoursOld.put("exp", issued.plusSeconds(7200).getEpochSecond());

    assertEquals(
        "session-1",
        verifier().verifiedPayload(signRs256(nearlyTwoHoursOld, KID)).path("jti").asString());
  }

  @Test
  void aMissingIatIsRejected() {
    Map<String, Object> noIat = claims();
    noIat.remove("iat");

    assertThrows(
        JwsVerificationException.class, () -> verifier().verifiedPayload(signRs256(noIat, KID)));
  }
}
