package com.sfbank.bayanati.uqudo.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sfbank.bayanati.uqudo.domain.ImageIntegrityException;
import com.sfbank.bayanati.uqudo.domain.ImageUnavailableException;
import com.sfbank.bayanati.uqudo.domain.UqudoJwsParser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The real adapter's HTTP behaviour against every response shape S1-02 observed, plus the failure
 * shapes the journey depends on telling apart. {@code MockRestServiceServer} replaces the request
 * factory, so nothing here proves the timeouts — {@link HttpUqudoClientTimeoutTest} does — and
 * nothing here proves signature verification, which is {@link UqudoJwksSignatureVerifierTest}.
 *
 * <p>Every value is invented: the "token" and "secret" literals are obviously fake, and the image
 * bytes are ASCII, not an image (CLAUDE.md — no identity-document images, anywhere).
 */
class HttpUqudoClientTest {

  private static final String AUTH = "https://auth.uqudo.invalid/api";
  private static final String API = "https://id.uqudo.invalid";
  private static final String TOKEN = "fake-access-token-not-real";
  private static final byte[] IMAGE = "not-a-real-image".getBytes(StandardCharsets.UTF_8);

  private MockRestServiceServer server;
  private HttpUqudoClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    UqudoHttpProperties properties =
        new UqudoHttpProperties(
            URI.create(AUTH),
            URI.create(API),
            URI.create(API + "/api/.well-known/jwks.json"),
            "test-client-id-not-real",
            "test-client-secret-not-real",
            API,
            Duration.ofSeconds(5),
            Duration.ofSeconds(15),
            Duration.ofMinutes(15));
    client =
        new HttpUqudoClient(
            builder.build(),
            properties,
            // The parser is exercised by StubUqudoClientTest; here it only has to be present.
            new UqudoJwsParser(jws -> null));
  }

  private void expectTokenMint() {
    server
        .expect(once(), requestTo(AUTH + "/oauth/token"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"access_token\":\""
                    + TOKEN
                    + "\",\"token_type\":\"bearer\",\"expires_in\":1800}",
                MediaType.APPLICATION_JSON));
  }

  private static String checksumOf(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ---- token ----

  @Test
  void theTokenCallIsFormEncodedClientCredentials() {
    server
        .expect(once(), requestTo(AUTH + "/oauth/token"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("client_credentials")))
        .andRespond(
            withSuccess(
                "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":1800}",
                MediaType.APPLICATION_JSON));

    assertEquals(TOKEN, client.issueAccessToken().value());
    server.verify();
  }

  @Test
  void aDeviceFacingTokenIsMintedFreshEveryTimeRatherThanServedFromTheInternalCache() {
    // customer.md Stage 7: the token is requested at the moment of tapping to scan precisely so the
    // handset never receives a nearly-dead one. A cache here would reintroduce that.
    expectTokenMint();
    server
        .expect(once(), requestTo(AUTH + "/oauth/token"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"access_token\":\"second-token-not-real\",\"expires_in\":1800}",
                MediaType.APPLICATION_JSON));

    String first = client.issueAccessToken().value();
    String second = client.issueAccessToken().value();

    assertNotEquals(first, second, "each device-facing token must be a fresh mint");
    server.verify();
  }

  @Test
  void aTokenResponseWithoutAnAccessTokenFails() {
    server
        .expect(once(), requestTo(AUTH + "/oauth/token"))
        .andRespond(withSuccess("{\"error\":\"invalid_client\"}", MediaType.APPLICATION_JSON));

    assertThrows(IllegalStateException.class, () -> client.issueAccessToken());
  }

  @Test
  void aFailedTokenCallNeverEchoesWhatWasSent() {
    server
        .expect(once(), requestTo(AUTH + "/oauth/token"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("test-client-secret-not-real"));

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> client.issueAccessToken());

    assertTrue(
        !thrown.getMessage().contains("test-client-secret-not-real"),
        "the credential must never reach an exception message");
  }

  // ---- images ----

  @Test
  void anImageIsReturnedWhenItsChecksumMatches() {
    expectTokenMint();
    server
        .expect(once(), requestTo(API + "/api/v1/info/img/img-1"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer " + TOKEN))
        .andRespond(withSuccess(IMAGE, MediaType.IMAGE_JPEG));

    assertArrayEquals(IMAGE, client.downloadImage("img-1", checksumOf(IMAGE)));
    server.verify();
  }

  @Test
  void aChecksumIsComparedCaseInsensitivelyAndWithOrWithoutItsPrefix() {
    expectTokenMint();
    server
        .expect(once(), requestTo(API + "/api/v1/info/img/img-1"))
        .andRespond(withSuccess(IMAGE, MediaType.IMAGE_JPEG));

    String bare =
        checksumOf(IMAGE).substring("sha256:".length()).toUpperCase(java.util.Locale.ROOT);

    assertArrayEquals(IMAGE, client.downloadImage("img-1", bare));
  }

  @Test
  void aFourZeroFourMeansRetentionExpiredAndNotAFailedAttempt() {
    // R-012/R-021: images live exactly as long as the enrolment JWS (2 hours, S1-02). This is the
    // routine end of that window, and must not be reported as a verification failure.
    expectTokenMint();
    server
        .expect(once(), requestTo(API + "/api/v1/info/img/img-gone"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND).body("Resource not found or expired"));

    assertThrows(
        ImageUnavailableException.class, () -> client.downloadImage("img-gone", checksumOf(IMAGE)));
  }

  @Test
  void aGoneIsTreatedTheSameWayAsANotFound() {
    expectTokenMint();
    server
        .expect(once(), requestTo(API + "/api/v1/info/img/img-gone"))
        .andRespond(withStatus(HttpStatus.GONE));

    assertThrows(
        ImageUnavailableException.class, () -> client.downloadImage("img-gone", checksumOf(IMAGE)));
  }

  @Test
  void bytesThatDoNotMatchTheChecksumAreARefusalNotAWarning() {
    expectTokenMint();
    server
        .expect(once(), requestTo(API + "/api/v1/info/img/img-1"))
        .andRespond(
            withSuccess("different-bytes".getBytes(StandardCharsets.UTF_8), MediaType.IMAGE_JPEG));

    assertThrows(
        ImageIntegrityException.class, () -> client.downloadImage("img-1", checksumOf(IMAGE)));
  }

  @Test
  void anAbsentChecksumFailsClosedRatherThanSkippingTheCheck() {
    // An image we cannot prove intact is not an image worth storing as identity evidence.
    expectTokenMint();
    server
        .expect(once(), requestTo(API + "/api/v1/info/img/img-1"))
        .andRespond(withSuccess(IMAGE, MediaType.IMAGE_JPEG));

    assertThrows(ImageIntegrityException.class, () -> client.downloadImage("img-1", null));
  }

  @Test
  void anUnexpectedImageStatusBecomesTheAuditedNonCountingOutcomeNotAnOpaqueFailure() {
    // 500, 401 on a revoked token, 429 — none of these are the customer's scan quality, and none
    // may escape as an unchecked exception: IdentityScanService turns ImageUnavailableException
    // into an audited scan_images_unavailable event that does not count against the retry budget.
    // Anything else would be a bare 500 on a scan whose JWS had already verified, with no trace.
    expectTokenMint();
    server
        .expect(once(), requestTo(API + "/api/v1/info/img/img-1"))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

    ImageUnavailableException thrown =
        assertThrows(
            ImageUnavailableException.class,
            () -> client.downloadImage("img-1", checksumOf(IMAGE)));

    // The coarse event name is deliberate; the message carries the real cause into the audit
    // payload's `reason`, so the trail still says which kind of unavailable this was.
    assertTrue(thrown.getMessage().contains("500"), thrown.getMessage());
  }

  @Test
  void aTransportFailureOnAnImageDownloadIsAlsoTheAuditedNonCountingOutcome() {
    expectTokenMint();
    server
        .expect(once(), requestTo(API + "/api/v1/info/img/img-1"))
        .andRespond(withException(new java.io.IOException("connection reset")));

    assertThrows(
        ImageUnavailableException.class, () -> client.downloadImage("img-1", checksumOf(IMAGE)));
  }

  @Test
  void aTransportFailureOnThePurgeIsSwallowedBecauseTheAcceptanceHasAlreadyCommitted() {
    // purgeSession runs after the accept transaction commits. Throwing here cannot roll anything
    // back — it can only turn a scan submission that actually succeeded into a 500, whose retry
    // then hits JwsAlreadyAcceptedException. The DELETE and the token mint are both covered.
    expectTokenMint();
    server
        .expect(once(), requestTo(API + "/api/v1/info/session-1"))
        .andRespond(withException(new java.io.IOException("connection reset")));

    client.purgeSession("session-1");
    server.verify();
  }

  @Test
  void aPurgeWhoseTokenCannotBeMintedIsAlsoSwallowed() {
    server
        .expect(once(), requestTo(AUTH + "/oauth/token"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

    client.purgeSession("session-1");
    server.verify();
  }

  // ---- purge ----

  @Test
  void thePurgeIsADeleteOnTheSessionId() {
    expectTokenMint();
    server
        .expect(once(), requestTo(API + "/api/v1/info/session-1"))
        .andExpect(method(HttpMethod.DELETE))
        .andExpect(header("Authorization", "Bearer " + TOKEN))
        .andRespond(withStatus(HttpStatus.NO_CONTENT));

    client.purgeSession("session-1");
    server.verify();
  }

  @Test
  void aFailedPurgeIsLoggedRatherThanThrownSoItCannotRollBackAGoodAcceptance() {
    expectTokenMint();
    server
        .expect(once(), requestTo(API + "/api/v1/info/session-1"))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

    client.purgeSession("session-1");
    server.verify();
  }

  // ---- face session ----

  @Test
  void aFaceSessionIsCreatedByMultipartUploadAndReturnsItsSessionId() {
    expectTokenMint();
    server
        .expect(once(), requestTo(API + "/api/v1/face"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .body("{\"sessionId\":\"face-session-1\"}")
                .contentType(MediaType.APPLICATION_JSON));

    assertEquals("face-session-1", client.createFaceSession(IMAGE));
    server.verify();
  }

  @Test
  void anOversizedOrWrongTypePortraitIsReportedAsOurDataBeingWrong() {
    expectTokenMint();
    server
        .expect(once(), requestTo(API + "/api/v1/face"))
        .andRespond(withStatus(HttpStatus.PAYLOAD_TOO_LARGE));

    assertThrows(IllegalArgumentException.class, () -> client.createFaceSession(IMAGE));
  }

  @Test
  void anEmptyPortraitIsRejectedBeforeAnyCallIsMade() {
    assertThrows(IllegalArgumentException.class, () -> client.createFaceSession(new byte[0]));
    server.verify();
  }

  @Test
  void aFaceSessionResponseWithoutASessionIdFails() {
    expectTokenMint();
    server
        .expect(once(), requestTo(API + "/api/v1/face"))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .body("{\"ok\":true}")
                .contentType(MediaType.APPLICATION_JSON));

    assertThrows(IllegalStateException.class, () -> client.createFaceSession(IMAGE));
  }

  @Test
  void theInternalTokenIsReusedAcrossServerToServerCallsRatherThanMintedPerCall() {
    // The opposite of the device-facing rule: our own calls should not mint a token each time.
    expectTokenMint();
    server
        .expect(once(), requestTo(API + "/api/v1/info/img/img-1"))
        .andRespond(withSuccess(IMAGE, MediaType.IMAGE_JPEG));
    server
        .expect(once(), requestTo(API + "/api/v1/info/session-1"))
        .andRespond(withStatus(HttpStatus.NO_CONTENT));

    client.downloadImage("img-1", checksumOf(IMAGE));
    client.purgeSession("session-1");

    // Exactly one token mint was expected; verify() fails if a second was attempted.
    server.verify();
  }
}
