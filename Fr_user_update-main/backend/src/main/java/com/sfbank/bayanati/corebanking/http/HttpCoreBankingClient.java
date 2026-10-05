package com.sfbank.bayanati.corebanking.http;

import com.sfbank.bayanati.corebanking.domain.CoreBankingCheckResult;
import com.sfbank.bayanati.corebanking.domain.CoreBankingClient;
import com.sfbank.bayanati.corebanking.domain.CoreBankingUnavailableException;
import com.sfbank.bayanati.corebanking.domain.RawExchange;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real core-banking adapter: one HTTPS/JSON {@code POST} to the bank middleware's {@code
 * CheckAccount} endpoint (AD-007). Plumbing — lives outside {@code domain}/{@code service} by the
 * CLAUDE.md package rule, and is selected by {@code fru.core-banking.client=http}.
 *
 * <p><strong>What was observed live (S1-04, 2026-09-04) and what this class relies on:</strong>
 *
 * <ul>
 *   <li>Every outcome comes back as HTTP 200 — the result is in the body, never the status. So the
 *       status handler is disabled ({@code onStatus(s -> true, ...)}) and the body is read whatever
 *       the status; a non-200 is captured and audited, not thrown away.
 *   <li>{@code Response_Code} arrived as a JSON number; the product owner's transcribed example
 *       showed a string. Both are accepted. Parsing branches explicitly on {@code isNumber()} /
 *       {@code isString()} — <strong>never {@code asInt()}</strong>, which in Jackson 3 coerces a
 *       null or missing node to {@code 0}, and {@code 0} is now the real "Account not Found" code:
 *       a malformed response would fail <em>open</em> as "not found".
 *   <li>The body is retrieved as {@code byte[]}, not bound to a record, so the audit artifact is
 *       byte-identical (never re-encoded) and a non-JSON body — the sibling Civil Registry service
 *       on the same GlassFish host returns {@code text/html} error pages — is captured rather than
 *       exploding inside a message converter.
 *   <li>No authentication, no cookie, no client certificate. None is sent.
 * </ul>
 *
 * <p>This class never maps to a journey outcome. It returns the raw code and message; {@code
 * AccountCheckService} decides what {@code 1}, {@code 0}, {@code -1} or anything else means.
 *
 * <p><strong>Timeouts are not set here.</strong> They live on the {@link RestClient}'s request
 * factory, built by {@code CoreBankingClientConfiguration} — this class takes a finished client so
 * tests can bind {@code MockRestServiceServer} to the builder. That also means a {@code
 * MockRestServiceServer} test proves nothing about timeouts (it replaces the factory they live on);
 * {@code HttpCoreBankingClientTimeoutTest} covers that with a real socket.
 */
public class HttpCoreBankingClient implements CoreBankingClient {

  static final String REQUEST_FIELD = "Account";
  static final String CODE_FIELD = "Response_Code";
  static final String MESSAGE_FIELD = "Response_Message";

  private final RestClient restClient;
  private final CoreBankingHttpProperties properties;
  private final JsonMapper json = JsonMapper.builder().build();

  public HttpCoreBankingClient(RestClient restClient, CoreBankingHttpProperties properties) {
    this.restClient = restClient;
    this.properties = properties;
  }

  @Override
  public CoreBankingCheckResult check(String accountNumber) {
    byte[] requestBody = json.writeValueAsBytes(Map.of(REQUEST_FIELD, accountNumber));

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
      throw new CoreBankingUnavailableException(
          "no response from the core banking middleware: " + noResponse.getMessage(),
          new RawExchange(requestBody, null, null, 0),
          noResponse);
    }

    byte[] responseBody = response.getBody() == null ? new byte[0] : response.getBody();
    MediaType contentType = response.getHeaders().getContentType();
    RawExchange exchange =
        new RawExchange(
            requestBody,
            responseBody,
            contentType == null ? null : contentType.toString(),
            response.getStatusCode().value());

    if (responseBody.length == 0) {
      throw new CoreBankingUnavailableException(
          "empty response body from the core banking middleware (HTTP "
              + exchange.httpStatus()
              + ")",
          exchange);
    }

    JsonNode root;
    try {
      root = json.readTree(responseBody);
    } catch (JacksonException notJson) {
      throw new CoreBankingUnavailableException(
          "non-JSON response body from the core banking middleware (HTTP "
              + exchange.httpStatus()
              + ", "
              + exchange.responseMediaType()
              + ")",
          exchange,
          notJson);
    }

    int code = parseCode(root.path(CODE_FIELD), exchange);
    JsonNode messageNode = root.path(MESSAGE_FIELD);
    String message = messageNode.isString() ? messageNode.stringValue() : null;
    return new CoreBankingCheckResult(code, message, exchange);
  }

  /**
   * Accepts a JSON integer or a string holding one. Fails closed — an unavailable answer, never a
   * default code — on absent, null, boolean, object, array, fractional or non-numeric-string
   * values.
   */
  private static int parseCode(JsonNode code, RawExchange exchange) {
    if (code.isIntegralNumber()) {
      long value;
      try {
        value = code.longValue();
      } catch (JacksonException beyondLong) {
        // A BigIntegerNode: longValue() itself throws for anything outside 64 bits (found by
        // @agent-reviewer, S3-02) -- must still be an outage with the bytes kept, never a 500.
        throw unreadableCode("out of int range: " + code, exchange);
      }
      if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
        throw unreadableCode("out of int range: " + value, exchange);
      }
      return (int) value;
    }
    if (code.isString()) {
      try {
        return Integer.parseInt(code.stringValue().trim());
      } catch (NumberFormatException notANumber) {
        throw unreadableCode("non-numeric string", exchange);
      }
    }
    if (code.isMissingNode() || code.isNull()) {
      throw unreadableCode("absent or null", exchange);
    }
    throw unreadableCode("unexpected JSON type " + code.getNodeType(), exchange);
  }

  private static CoreBankingUnavailableException unreadableCode(String why, RawExchange exchange) {
    return new CoreBankingUnavailableException(
        CODE_FIELD + " is unreadable in the core banking response: " + why, exchange);
  }
}
