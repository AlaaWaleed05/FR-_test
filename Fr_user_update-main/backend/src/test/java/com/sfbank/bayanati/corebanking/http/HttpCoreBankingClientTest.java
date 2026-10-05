package com.sfbank.bayanati.corebanking.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sfbank.bayanati.corebanking.domain.CoreBankingCheckResult;
import com.sfbank.bayanati.corebanking.domain.CoreBankingUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The adapter against every response shape observed live at S1-04 or decided at OQ-025, plus the
 * malformed shapes it must fail closed on. {@code MockRestServiceServer} replaces the request
 * factory, so nothing here proves the timeouts — {@link HttpCoreBankingClientTimeoutTest} does.
 *
 * <p>Fabricated account values only (CLAUDE.md hard rule).
 */
class HttpCoreBankingClientTest {

  private static final String ENDPOINT =
      "https://middleware.invalid:9494/OMNI_PH3/resources/bankRoutes/CheckAccount";
  private static final String ACCOUNT = "0000000001";

  private MockRestServiceServer server;
  private HttpCoreBankingClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    client =
        new HttpCoreBankingClient(
            builder.build(),
            new CoreBankingHttpProperties(
                URI.create(ENDPOINT), Duration.ofSeconds(5), Duration.ofSeconds(10)));
  }

  private void respondWith(String body) {
    server
        .expect(once(), requestTo(ENDPOINT))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
  }

  @Test
  void theRequestIsAPlainJsonPostOfTheAccountNumberAlone() {
    // OQ-024: no branch. S1-04: no authentication of any kind.
    server
        .expect(once(), requestTo(ENDPOINT))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.Account").value(ACCOUNT))
        .andExpect(jsonPath("$.Branch").doesNotExist())
        .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
        .andExpect(headerDoesNotExist(HttpHeaders.COOKIE))
        .andRespond(
            withSuccess(
                "{\"Response_Code\":1,\"Response_Message\":\"Account Found\"}",
                MediaType.APPLICATION_JSON));

    client.check(ACCOUNT);

    server.verify();
  }

  @Test
  void aJsonNumberCodeIsReturnedVerbatimWithItsMessage() {
    // The shape observed live: codes arrive as JSON numbers.
    respondWith("{\"Response_Code\":1,\"Response_Message\":\"Account Found\"}");

    CoreBankingCheckResult result = client.check(ACCOUNT);

    assertEquals(CoreBankingCheckResult.FOUND, result.code());
    assertEquals("Account Found", result.message());
  }

  @Test
  void aJsonStringCodeIsAcceptedToo() {
    // The product owner's transcribed example carried "1" as a string; the live calls carried
    // numbers. Both must work (PROJECT_PLAN.md Constraints: "number or string").
    respondWith("{\"Response_Code\":\"1\",\"Response_Message\":\"Account Found\"}");

    assertEquals(CoreBankingCheckResult.FOUND, client.check(ACCOUNT).code());
  }

  @Test
  void notFoundIsCodeZero() {
    respondWith("{\"Response_Code\":0,\"Response_Message\":\"Account not Found\"}");

    CoreBankingCheckResult result = client.check("00000000");

    assertEquals(CoreBankingCheckResult.NOT_FOUND, result.code());
    assertEquals("Account not Found", result.message());
  }

  @Test
  void theMiddlewaresOwnSystemErrorIsReturnedAsMinusOneNotThrownHere() {
    // Observed live for an empty account value. The adapter reports it verbatim; the SERVICE
    // decides
    // that -1 is an outage, so the raw reply can be audited first.
    respondWith("{\"Response_Code\":-1,\"Response_Message\":\"System Error\"}");

    CoreBankingCheckResult result = client.check(ACCOUNT);

    assertEquals(CoreBankingCheckResult.SYSTEM_ERROR, result.code());
    assertEquals("System Error", result.message());
  }

  @Test
  void anUnrecognisedButParseableCodeIsReturnedVerbatimForTheMappingLayerToRejectAsUnmapped() {
    respondWith("{\"Response_Code\":7,\"Response_Message\":\"?\"}");

    assertEquals(7, client.check(ACCOUNT).code());
  }

  @Test
  void theRawExchangeIsByteIdenticalForTheAuditArtifacts() {
    String body = "{\"Response_Code\":0,\"Response_Message\":\"Account not Found\"}";
    respondWith(body);

    CoreBankingCheckResult result = client.check("00000000");

    assertNotNull(result.exchange());
    assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), result.exchange().responseBody());
    assertEquals(
        "{\"Account\":\"00000000\"}",
        new String(result.exchange().requestBody(), StandardCharsets.UTF_8));
    assertEquals(200, result.exchange().httpStatus());
    assertTrue(result.exchange().responseMediaType().startsWith("application/json"));
  }

  @Test
  void aMissingMessageIsNullNotAFailure() {
    respondWith("{\"Response_Code\":1}");

    CoreBankingCheckResult result = client.check(ACCOUNT);

    assertEquals(1, result.code());
    assertNull(result.message());
  }

  @Test
  void aNonJsonBodyFailsClosedAndKeepsTheBytesForTheAudit() {
    // The sibling Civil Registry service on the same GlassFish host returns text/html error pages
    // (OQ-023). Never assume application/json.
    String html = "<html><body>HTTP Status 400 - Bad Request</body></html>";
    server
        .expect(once(), requestTo(ENDPOINT))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_HTML).body(html));

    CoreBankingUnavailableException thrown =
        assertThrows(CoreBankingUnavailableException.class, () -> client.check(ACCOUNT));

    assertNotNull(thrown.exchange());
    assertEquals(400, thrown.exchange().httpStatus());
    assertArrayEquals(html.getBytes(StandardCharsets.UTF_8), thrown.exchange().responseBody());
    assertTrue(thrown.exchange().responseMediaType().startsWith("text/html"));
  }

  @Test
  void aNon200StatusWithAJsonBodyIsStillReadNotThrownAway() {
    // Every observed reply was 200, but the status is never the outcome. A 500 carrying a
    // parseable body still yields its code, so the audit sees what the middleware actually said.
    server
        .expect(once(), requestTo(ENDPOINT))
        .andRespond(
            withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"Response_Code\":-1,\"Response_Message\":\"System Error\"}"));

    CoreBankingCheckResult result = client.check(ACCOUNT);

    assertEquals(-1, result.code());
    assertEquals(500, result.exchange().httpStatus());
  }

  @Test
  void anEmptyBodyFailsClosed() {
    server.expect(once(), requestTo(ENDPOINT)).andRespond(withSuccess());

    CoreBankingUnavailableException thrown =
        assertThrows(CoreBankingUnavailableException.class, () -> client.check(ACCOUNT));

    assertNotNull(thrown.exchange());
    assertEquals(0, thrown.exchange().responseBody().length);
  }

  /**
   * The trap this class exists to avoid: Jackson 3's {@code asInt()} coerces a null or missing
   * {@code Response_Code} to {@code 0} — which is now the real "Account not Found" code. Every one
   * of these must be an outage, never a not-found.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"Response_Message\":\"Account not Found\"}",
        "{\"Response_Code\":null,\"Response_Message\":\"x\"}",
        "{\"Response_Code\":true}",
        "{\"Response_Code\":\"found\"}",
        "{\"Response_Code\":\"\"}",
        "{\"Response_Code\":1.5}",
        "{\"Response_Code\":{\"value\":1}}",
        "{\"Response_Code\":[1]}",
        "{\"Response_Code\":99999999999}",
        "{\"Response_Code\":99999999999999999999}",
        "{\"Response_Code\":\"99999999999999999999\"}",
        "[1]",
        "\"1\""
      })
  void anAbsentNullOrNonIntegerCodeFailsClosedNeverAsNotFound(String body) {
    respondWith(body);

    CoreBankingUnavailableException thrown =
        assertThrows(CoreBankingUnavailableException.class, () -> client.check(ACCOUNT));

    assertTrue(thrown.getMessage().contains("Response_Code"), thrown.getMessage());
    assertNotNull(thrown.exchange(), "the unreadable reply is still evidence");
  }

  @Test
  void noResponseAtAllFailsClosedWithTheRequestBytesButNoResponseBytes() {
    server
        .expect(once(), requestTo(ENDPOINT))
        .andRespond(withException(new IOException("connection reset")));

    CoreBankingUnavailableException thrown =
        assertThrows(CoreBankingUnavailableException.class, () -> client.check(ACCOUNT));

    assertNotNull(thrown.getCause());
    assertNotNull(thrown.exchange(), "what was sent is still worth auditing");
    assertEquals(
        "{\"Account\":\"" + ACCOUNT + "\"}",
        new String(thrown.exchange().requestBody(), StandardCharsets.UTF_8));
    assertNull(thrown.exchange().responseBody());
  }
}
