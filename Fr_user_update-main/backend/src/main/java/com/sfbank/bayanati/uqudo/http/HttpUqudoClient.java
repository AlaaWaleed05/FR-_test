package com.sfbank.bayanati.uqudo.http;

import com.sfbank.bayanati.uqudo.domain.ImageIntegrityException;
import com.sfbank.bayanati.uqudo.domain.ImageUnavailableException;
import com.sfbank.bayanati.uqudo.domain.IssuedAccessToken;
import com.sfbank.bayanati.uqudo.domain.ParsedEnrolmentResult;
import com.sfbank.bayanati.uqudo.domain.ParsedFaceResult;
import com.sfbank.bayanati.uqudo.domain.ParsedIncompleteFaceResult;
import com.sfbank.bayanati.uqudo.domain.UqudoClient;
import com.sfbank.bayanati.uqudo.domain.UqudoJwsParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * The real Uqudo adapter, selected by {@code fru.uqudo.client=http}. Plumbing — lives outside
 * {@code domain}/{@code service} by the CLAUDE.md package rule.
 *
 * <p><strong>Not written from documentation.</strong> Every call here was exercised against the
 * live tenant at S1-02 (2026-09-03) from the throwaway {@code spike} package, and this class keeps
 * the findings that cost something to learn: the multipart build that works on a WebMVC-only
 * classpath, the image {@code 404}, the purge that answers {@code 204} for any UUID at all.
 *
 * <p>What it does <em>not</em> own is parsing: {@link UqudoJwsParser} is the quarantined parser and
 * is shared with {@code StubUqudoClient}, so the two cannot drift. This class supplies the parser's
 * signature step ({@link UqudoJwksSignatureVerifier}) and the plain HTTPS calls around it.
 *
 * <p><strong>Timeouts are not set here</strong> — they live on the {@link RestClient}'s request
 * factory, built by {@code UqudoClientConfiguration}, so a {@code MockRestServiceServer} test can
 * bind to the builder and a separate real-socket test covers the timeouts. <strong>No
 * retries</strong>: the journey's own Stage 8 and 9 retries are the retry.
 *
 * <p><strong>Nothing here is ever logged with a value.</strong> Not the access token, not the
 * client secret, not a raw JWS, not image bytes, not an image id. Status codes, sizes and a
 * decision are the whole vocabulary.
 */
public class HttpUqudoClient implements UqudoClient {

  /** Re-mint an internal token this long before it expires, so a call never races the clock. */
  static final Duration INTERNAL_TOKEN_HEADROOM = Duration.ofSeconds(60);

  // Statuses are compared as integers, not as HttpStatus constants. Spring carries two constants
  // for 413 (the current CONTENT_TOO_LARGE and the deprecated PAYLOAD_TOO_LARGE), so which one
  // HttpStatus.resolve(413) hands back is not something this class should depend on -- an identity
  // comparison against the wrong one silently stops matching. Caught by the 413 test.
  private static final int NOT_FOUND = 404;
  private static final int GONE = 410;
  private static final int CONTENT_TOO_LARGE = 413;
  private static final int UNSUPPORTED_MEDIA_TYPE = 415;

  private static final Logger log = LoggerFactory.getLogger(HttpUqudoClient.class);

  private final RestClient restClient;
  private final UqudoHttpProperties properties;
  private final UqudoJwsParser parser;

  /** The adapter's own bearer token for image/purge/face calls. Never handed to a device. */
  private AccessToken internalToken;

  public HttpUqudoClient(
      RestClient restClient, UqudoHttpProperties properties, UqudoJwsParser parser) {
    this.restClient = restClient;
    this.properties = properties;
    this.parser = parser;
  }

  // ---- token ----

  /**
   * Minted fresh on every call, deliberately never served from the internal cache.
   *
   * <p>This token leaves the backend: it is handed to the handset at the moment the customer taps
   * to scan (customer.md Stage 7, "No Uqudo token is requested here"). Serving a cached one would
   * reintroduce exactly the staleness that decision exists to prevent — a device could receive a
   * token with seconds left and fail the scan in a way that looks like the app breaking. A cached
   * token is fine for our own server-to-server calls, which retry cheaply and observably; it is not
   * fine for the one that crosses to a device we cannot see.
   */
  @Override
  public IssuedAccessToken issueAccessToken() {
    AccessToken minted = mintToken();
    // BL-114(a): expiresAt was already computed from the response's expires_in and discarded here.
    return new IssuedAccessToken(minted.value(), minted.expiresAt());
  }

  private synchronized String internalBearer() {
    if (internalToken == null || internalToken.isDueForRenewal()) {
      internalToken = mintToken();
    }
    return internalToken.value();
  }

  private AccessToken mintToken() {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", properties.clientId());
    form.add("client_secret", properties.clientSecret());

    JsonNode body;
    try {
      body =
          restClient
              .post()
              .uri(properties.tokenEndpoint())
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(JsonNode.class);
    } catch (Exception tokenFailed) {
      // Never echo the response body: a failed client-credentials call can quote what was sent.
      throw new IllegalStateException(
          "could not mint a Uqudo access token: " + tokenFailed.getClass().getSimpleName());
    }
    if (body == null || !body.path("access_token").isString()) {
      throw new IllegalStateException("the Uqudo token response carried no access_token");
    }
    long expiresIn = body.path("expires_in").isNumber() ? body.path("expires_in").asLong() : 0;
    return new AccessToken(
        body.path("access_token").asString(),
        Instant.now().plusSeconds(expiresIn > 0 ? expiresIn : INTERNAL_TOKEN_HEADROOM.toSeconds()));
  }

  private record AccessToken(String value, Instant expiresAt) {
    boolean isDueForRenewal() {
      return Instant.now().isAfter(expiresAt.minus(INTERNAL_TOKEN_HEADROOM));
    }
  }

  // ---- parsing: delegated, so the real client and the stub cannot drift apart ----

  @Override
  public ParsedEnrolmentResult verifyAndParse(
      String jws, String expectedSessionId, String expectedNonce, String expectedDocumentType) {
    return parser.parseEnrolment(jws, expectedSessionId, expectedNonce, expectedDocumentType);
  }

  @Override
  public ParsedFaceResult verifyAndParseFaceSession(String jws, String expectedFaceSessionId) {
    return parser.parseFaceSession(jws, expectedFaceSessionId);
  }

  @Override
  public ParsedIncompleteFaceResult verifyAndParseIncompleteFaceSession(
      String jws, String expectedFaceSessionId) {
    return parser.parseIncompleteFaceSession(jws, expectedFaceSessionId);
  }

  // ---- images ----

  /**
   * {@code GET /api/v1/info/img/{id}}. The status handler is disabled so a {@code 404} — the
   * routine "images are gone" answer, observed at S1-02 exactly two hours after {@code iat} — is
   * read as an outcome rather than thrown as a client error.
   *
   * <p><strong>Every way of not getting the bytes ends as {@link
   * ImageUnavailableException}</strong> — the retention {@code 404}, an unexpected status ({@code
   * 401} on a revoked token, {@code 429}, any {@code 5xx}), and a transport failure alike. That is
   * the port's declared vocabulary ({@link UqudoClient#downloadImage}) and, more importantly, the
   * only one the caller turns into a defined journey outcome: {@code IdentityScanService} audits it
   * as {@code scan_images_unavailable} and does <em>not</em> count it against the retry budget.
   * Letting a {@code RestClientException} escape instead would give the customer an opaque 500 on a
   * scan whose JWS had already verified, with no audit event at all — the one exit in this stage
   * that leaves no trace. The event name is coarse by design; the exception message carries the
   * actual cause into the audit payload's {@code reason}, so nothing diagnostic is lost.
   */
  @Override
  public byte[] downloadImage(String imageId, String expectedChecksum) {
    ResponseEntity<byte[]> response;
    try {
      response =
          restClient
              .get()
              .uri(properties.imageEndpoint(imageId))
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + internalBearer())
              .retrieve()
              .onStatus(status -> true, (request, res) -> {})
              .toEntity(byte[].class);
    } catch (RuntimeException unreachable) {
      // Connect/read timeout, refused, DNS, TLS — and a failed token mint, which is the same
      // situation from the journey's point of view: we cannot fetch this image right now.
      log.warn("Uqudo image download did not complete: {}", unreachable.getClass().getSimpleName());
      throw new ImageUnavailableException(
          imageId, "could not be fetched: " + unreachable.getClass().getSimpleName());
    }

    int status = response.getStatusCode().value();
    if (status == NOT_FOUND || status == GONE) {
      // Retention has expired. R-012/R-021: this does not count against the retry budget.
      throw new ImageUnavailableException(imageId);
    }
    if (!response.getStatusCode().is2xxSuccessful()) {
      log.warn("Uqudo returned HTTP {} for an image download", status);
      throw new ImageUnavailableException(imageId, "Uqudo returned HTTP " + status);
    }

    byte[] body = response.getBody() == null ? new byte[0] : response.getBody();
    if (!checksumMatches(body, expectedChecksum)) {
      // "a mismatch is a hard failure" (uqudo-sdk.md). A missing checksum is one too: an image we
      // cannot prove intact is not an image we store as evidence.
      log.warn("Uqudo image failed checksum verification on download ({} bytes)", body.length);
      throw new ImageIntegrityException(imageId);
    }
    return body;
  }

  /**
   * Compares Uqudo's {@code "sha256:<digest>"} against the bytes actually received. Both sides are
   * reduced to a lower-case hex digest first — the {@code sha256:} prefix is optional and the case
   * of a hex digest carries no meaning, so neither is allowed to cause a false mismatch. Nothing
   * else is normalised, and an absent expectation fails rather than passes.
   */
  private static boolean checksumMatches(byte[] body, String expectedChecksum) {
    if (expectedChecksum == null || expectedChecksum.isBlank()) {
      return false;
    }
    String expected = expectedChecksum.trim().toLowerCase(Locale.ROOT);
    if (expected.startsWith("sha256:")) {
      expected = expected.substring("sha256:".length());
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII),
        sha256Hex(body).getBytes(StandardCharsets.US_ASCII));
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  // ---- session purge ----

  /**
   * {@code DELETE /api/v1/info/{id}}. Observed at S1-02: this answers {@code 204} for ANY UUID,
   * whether or not it purged anything — so the caller's choice of id is the entire control, and a
   * {@code 204} is not evidence that something was deleted. See {@link UqudoClient#purgeSession}
   * for which id to pass.
   *
   * <p><strong>This method never throws.</strong> It runs after the accept transaction has already
   * committed, so an exception here cannot roll the acceptance back — it can only turn a scan
   * submission that genuinely succeeded into a 500 for the customer, whose retry would then hit
   * {@code JwsAlreadyAcceptedException}. A failed purge is a privacy problem to act on (R-001: we
   * run on FIB's borrowed tenant), not a reason to tell the customer their scan failed, so every
   * outcome that is not a success is an ERROR log and nothing more. The blanket catch is deliberate
   * and covers the token mint as well as the DELETE.
   */
  @Override
  public void purgeSession(String uqudoSessionId) {
    try {
      ResponseEntity<Void> response =
          restClient
              .delete()
              .uri(properties.sessionEndpoint(uqudoSessionId))
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + internalBearer())
              .retrieve()
              .onStatus(status -> true, (request, res) -> {})
              .toBodilessEntity();

      if (!response.getStatusCode().is2xxSuccessful()) {
        log.error(
            "Uqudo session purge returned HTTP {} — a customer's data may still be held on the"
                + " Uqudo tenant",
            response.getStatusCode().value());
      }
    } catch (RuntimeException purgeFailed) {
      log.error(
          "Uqudo session purge did not complete ({}) — a customer's data may still be held on the"
              + " Uqudo tenant",
          purgeFailed.getClass().getSimpleName());
    }
  }

  // ---- face session ----

  /**
   * {@code POST /api/v1/face}, {@code multipart/form-data}, part name {@code idPhoto}.
   *
   * <p>Built with a plain {@link LinkedMultiValueMap} rather than {@code MultipartBodyBuilder}: the
   * latter references Reactive Streams, which is absent from this WebMVC-only classpath. That is
   * not a preference — it was a {@code ClassNotFoundException: org.reactivestreams.Publisher} found
   * live at S1-02.
   */
  @Override
  public String createFaceSession(byte[] referenceImageBytes) {
    if (referenceImageBytes == null || referenceImageBytes.length == 0) {
      throw new IllegalArgumentException("referenceImageBytes must not be empty");
    }

    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add(
        "idPhoto",
        new ByteArrayResource(referenceImageBytes) {
          @Override
          public String getFilename() {
            return "portrait.jpg";
          }
        });

    ResponseEntity<JsonNode> response =
        restClient
            .post()
            .uri(properties.faceSessionEndpoint())
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + internalBearer())
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(form)
            .retrieve()
            .onStatus(status -> true, (request, res) -> {})
            .toEntity(JsonNode.class);

    int status = response.getStatusCode().value();
    if (status == CONTENT_TOO_LARGE || status == UNSUPPORTED_MEDIA_TYPE) {
      // Both are about the portrait we uploaded, which came from our own stored artifact — so this
      // is our data being wrong, not the customer's. Named precisely so it is not mistaken for a
      // liveness outcome.
      throw new IllegalArgumentException(
          "Uqudo rejected the reference portrait with HTTP "
              + response.getStatusCode().value()
              + " (limits: 5 MB, JPEG or PNG); bytes="
              + referenceImageBytes.length);
    }
    if (!response.getStatusCode().is2xxSuccessful()) {
      throw new IllegalStateException(
          "Uqudo returned HTTP " + response.getStatusCode().value() + " creating a Face Session");
    }

    JsonNode body = response.getBody();
    if (body == null || !body.path("sessionId").isString()) {
      throw new IllegalStateException("the Uqudo Face Session response carried no sessionId");
    }
    return body.path("sessionId").asString();
  }
}
