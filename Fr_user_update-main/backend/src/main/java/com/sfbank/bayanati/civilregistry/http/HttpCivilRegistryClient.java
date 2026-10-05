package com.sfbank.bayanati.civilregistry.http;

import com.sfbank.bayanati.civilregistry.domain.CivilRegistryClient;
import com.sfbank.bayanati.civilregistry.domain.RegistryExchange;
import com.sfbank.bayanati.civilregistry.domain.RegistryFieldNormaliser;
import com.sfbank.bayanati.civilregistry.domain.RegistryLookup;
import com.sfbank.bayanati.civilregistry.domain.RegistryLookupResult;
import com.sfbank.bayanati.civilregistry.domain.RegistryUnreachableException;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real Civil Registry adapter: one HTTPS/JSON {@code POST} to the bank's {@code GetCRSData}
 * service (AD-002b). Plumbing — lives outside {@code domain}/{@code service} by the CLAUDE.md
 * package rule, and is selected by {@code fru.civil-registry.client=http}.
 *
 * <p>Built on S3-02's {@code HttpCoreBankingClient} pattern, unchanged where the two services agree
 * (same host family, same TLS chain, same classpath) and different only where this service is:
 *
 * <ul>
 *   <li>The request key is {@code NID}, sent as a JSON <em>string</em>. The service binds it as a
 *       string: {@code 0} and {@code 00000000000} are two different records (five authorised tests,
 *       2026-09-04), so leading zeros are load-bearing and must survive the wire.
 *   <li>A non-2xx is a <em>normal</em> answer, not an error: the service's not-found response is an
 *       HTTP 400 {@code text/html} GlassFish page. So the status handler is disabled ({@code
 *       onStatus(s -> true, ...)}), the body is read as {@code byte[]} whatever the status (kept
 *       byte-identical for the {@code civil_registry_response} audit artifact), and the
 *       classification below routes every non-success shape to {@code not_found} without an ERROR
 *       log.
 *   <li>{@code IDENTITY_NUMBER} is compared with the number sent — read from the found record, not
 *       echoed (Q2) — as a JSON string, exact after {@code strip()}, with no other normalisation. A
 *       JSON number is rejected rather than coerced, for the leading-zero reason above.
 *   <li>Every other field is optional and stored as received (Q12); {@code BIRTH_DATE} and {@code
 *       GENDER} degrade to null per Q11 ({@link RegistryFieldNormaliser}); {@code PHOTOGRAPH} is
 *       decoded here so an undecodable value degrades to "no portrait" instead of a 500 downstream.
 *   <li>No authentication, no cookie, no client certificate. None is sent.
 * </ul>
 *
 * <p>This class never maps to a journey state. It returns the classification and its reason; {@code
 * IdentityScanService} decides that {@code found()} is {@code ok} and everything else {@code
 * not_found}, and that the exception is {@code unreachable}.
 *
 * <p><strong>Timeouts are not set here</strong> — they live on the {@link RestClient}'s request
 * factory, built by {@code CivilRegistryClientConfiguration}; this class takes a finished client so
 * tests can bind {@code MockRestServiceServer} to the builder. A {@code MockRestServiceServer} test
 * therefore proves nothing about timeouts; {@code HttpCivilRegistryClientTimeoutTest} covers that
 * with a real socket. <strong>No retries</strong>: the journey's own Stage 9 retry, with its {@code
 * attempts} counter, is the retry — a silent one here would double the worst case to the mobile
 * client's whole receive timeout.
 *
 * <p><strong>Logging never carries a value from the request or the response</strong> — not the
 * national number, not a name, not the photograph. Reason code, HTTP status, content type, sizes
 * and a whitespace-only-difference flag are the whole vocabulary.
 */
public class HttpCivilRegistryClient implements CivilRegistryClient {

  static final String REQUEST_FIELD = "NID";
  static final String IDENTITY_NUMBER_FIELD = "IDENTITY_NUMBER";

  private static final Logger log = LoggerFactory.getLogger(HttpCivilRegistryClient.class);

  /**
   * Characters {@link String#strip()} does not remove but which a near-miss might carry: the
   * no-break spaces and the bidi marks. Used only to compute the diagnostic flag in the failure
   * log, never to widen the accept rule.
   */
  private static final Set<Integer> INVISIBLE_NEAR_MISS =
      Set.of(0x00A0, 0x2007, 0x202F, 0x200E, 0x200F);

  private final RestClient restClient;
  private final CivilRegistryHttpProperties properties;
  private final JsonMapper json = JsonMapper.builder().build();

  public HttpCivilRegistryClient(RestClient restClient, CivilRegistryHttpProperties properties) {
    this.restClient = restClient;
    this.properties = properties;
  }

  @Override
  public RegistryLookup lookup(String identityNumber) {
    // S7-12: the number is canonicalised to its digits HERE, at the boundary, and nowhere else.
    // A passport prints it grouped (NNN-NNNN-NNNN) and Uqudo transcribes the document faithfully,
    // but the registry matches NID on the bare digits -- so the verbatim form always came back as
    // its 400 not-found page. app.scan_result keeps the document's own form; only the wire is
    // canonical. Null (a number with no digit at all) becomes the empty string, which the service
    // answers with the same 400 it always has -- unchanged behaviour for that case, and Map.of
    // rejects a null value anyway.
    String submittedDigits = RegistryFieldNormaliser.digitsOnly(identityNumber);
    byte[] requestBody =
        json.writeValueAsBytes(
            Map.of(REQUEST_FIELD, submittedDigits == null ? "" : submittedDigits));

    ResponseEntity<byte[]> response;
    try {
      response =
          restClient
              .post()
              .uri(properties.endpoint())
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON)
              .body(requestBody)
              .retrieve()
              .onStatus(status -> true, (request, res) -> {})
              .toEntity(byte[].class);
    } catch (RestClientException noResponse) {
      // ResourceAccessException (connect/read timeout, refused, DNS, TLS) and anything else the
      // client raised before a response existed. The request bytes are still worth auditing.
      throw new RegistryUnreachableException(
          "no response from the Civil Registry: " + noResponse.getMessage(),
          new RegistryExchange(requestBody, null, null, 0),
          noResponse);
    }

    byte[] responseBody = response.getBody() == null ? new byte[0] : response.getBody();
    MediaType contentType = response.getHeaders().getContentType();
    RegistryExchange exchange =
        new RegistryExchange(
            requestBody,
            responseBody,
            contentType == null ? null : contentType.toString(),
            response.getStatusCode().value());

    // AD-002b's classification, in order; the first rule that fires wins.
    if (!response.getStatusCode().is2xxSuccessful()) {
      return notFound(RegistryLookup.NON_SUCCESS_STATUS, null, exchange, identityNumber, null);
    }
    if (responseBody.length == 0) {
      return notFound(RegistryLookup.EMPTY_BODY, null, exchange, identityNumber, null);
    }
    JsonNode root;
    try {
      root = json.readTree(responseBody);
    } catch (JacksonException notJson) {
      return notFound(RegistryLookup.NON_JSON_BODY, null, exchange, identityNumber, null);
    }
    if (!root.isObject()) {
      return notFound(RegistryLookup.NOT_AN_OBJECT, null, exchange, identityNumber, null);
    }
    JsonNode identityNode = root.path(IDENTITY_NUMBER_FIELD);
    if (identityNode.isMissingNode() || identityNode.isNull()) {
      return notFound(RegistryLookup.IDENTITY_NUMBER_ABSENT, null, exchange, identityNumber, null);
    }
    if (!identityNode.isString()) {
      return notFound(
          RegistryLookup.IDENTITY_NUMBER_NOT_STRING, null, exchange, identityNumber, null);
    }
    String returned = RegistryFieldNormaliser.strip(identityNode.stringValue());
    if (returned == null) {
      return notFound(RegistryLookup.IDENTITY_NUMBER_EMPTY, null, exchange, identityNumber, null);
    }
    // Compare the registry's answer against WHAT WE ACTUALLY ASKED -- submittedDigits, the exact
    // string in the request body -- not against the document's grouped form. Comparing against the
    // grouped form would fire BL-030, the failure mode once judged the worst in the system, on
    // every successful passport lookup: fixing the request alone would have replaced a not-found
    // with a mismatch.
    //
    // THE CANONICALISATION IS DELIBERATELY ASYMMETRIC, and an earlier draft of this fix got it
    // wrong. Digits are extracted from what we SEND, because we know its provenance -- a document
    // that prints separators. Nothing is extracted from what comes BACK: `returned` keeps the
    // whitespace-only strip, so a returned "…1x" stays a mismatch instead of folding onto "…1".
    // Applying digitsOnly to both sides reinstates prefix matching, which is precisely what this
    // guard exists to refuse; nearMissesThatAreNotWhitespaceAreMismatches caught it.
    //
    // If the service ever answers in grouped form -- never observed -- this reads as a mismatch and
    // fails closed, logging both lengths. That is the correct default for a guard about identity.
    if (!returned.equals(submittedDigits)) {
      return notFound(
          RegistryLookup.IDENTITY_NUMBER_MISMATCH,
          returned,
          exchange,
          identityNumber,
          identityNode.stringValue());
    }
    return RegistryLookup.matched(record(root, returned), exchange);
  }

  private static RegistryLookup notFound(
      String reason,
      String identityNumberReturned,
      RegistryExchange exchange,
      String submittedRaw,
      String returnedRaw) {
    if (RegistryLookup.IDENTITY_NUMBER_MISMATCH.equals(reason)) {
      // BL-030: the failure mode once judged the worst in the system. Lengths and a whitespace
      // diagnostic only -- never the values.
      log.warn(
          "Civil Registry lookup not found: reason={} httpStatus={} contentType={} responseBytes={}"
              + " submittedLength={} returnedLength={} differsOnlyByWhitespace={}",
          reason,
          exchange.httpStatus(),
          exchange.responseMediaType(),
          exchange.responseBody().length,
          submittedRaw.length(),
          returnedRaw.length(),
          differsOnlyByWhitespace(submittedRaw, returnedRaw));
    } else {
      // The 400 HTML page is the service's routine not-found answer: informational, not noise.
      log.info(
          "Civil Registry lookup not found: reason={} httpStatus={} contentType={} responseBytes={}",
          reason,
          exchange.httpStatus(),
          exchange.responseMediaType(),
          exchange.responseBody().length);
    }
    return RegistryLookup.notFound(reason, identityNumberReturned, exchange);
  }

  /** True when the two values are equal once every whitespace-like code point is removed. */
  static boolean differsOnlyByWhitespace(String a, String b) {
    return removeInvisible(a).equals(removeInvisible(b));
  }

  private static String removeInvisible(String value) {
    StringBuilder kept = new StringBuilder(value.length());
    value
        .codePoints()
        .filter(cp -> !Character.isWhitespace(cp) && !INVISIBLE_NEAR_MISS.contains(cp))
        .forEach(kept::appendCodePoint);
    return kept.toString();
  }

  private static RegistryLookupResult record(JsonNode root, String identityNumber) {
    return new RegistryLookupResult(
        identityNumber,
        text(root, "NAME"),
        text(root, "FATHER_NAME"),
        text(root, "GRAND_FATHER_NAME"),
        text(root, "GRE_GRA_FATHER_NAME"),
        text(root, "MOTHER_NAME"),
        text(root, "MOT_FATHER_NAME"),
        text(root, "MOT_GRA_FATHER_NAME"),
        text(root, "MOT_GRE_GRA_FATHER_NAME"),
        text(root, "FIRST_NAMES"),
        text(root, "LAST_NAME"),
        RegistryFieldNormaliser.normaliseGender(text(root, "GENDER")),
        RegistryFieldNormaliser.parseBirthDate(text(root, "BIRTH_DATE")),
        text(root, "ADDRESS"),
        photograph(root));
  }

  /** A string field, stripped, blank collapsed to null; any non-string node is null. */
  private static String text(JsonNode root, String field) {
    JsonNode node = root.path(field);
    return node.isString() ? RegistryFieldNormaliser.strip(node.stringValue()) : null;
  }

  /**
   * {@code PHOTOGRAPH} decoded once, here. The MIME decoder ignores line separators and any other
   * character outside the base64 alphabet, so a wrapped body still decodes; anything it still
   * cannot decode degrades to "no portrait" rather than a 500 -- the classification stays {@code
   * matched}, and the raw value remains in the response artifact.
   */
  private static byte[] photograph(JsonNode root) {
    String encoded = text(root, "PHOTOGRAPH");
    if (encoded == null) {
      log.warn("Civil Registry record carries no PHOTOGRAPH; treated as absent, lookup still ok");
      return null;
    }
    try {
      return Base64.getMimeDecoder().decode(encoded);
    } catch (IllegalArgumentException undecodable) {
      log.warn(
          "Civil Registry PHOTOGRAPH is not decodable base64 (length {}); stored as absent, lookup"
              + " still ok",
          encoded.length());
      return null;
    }
  }
}
