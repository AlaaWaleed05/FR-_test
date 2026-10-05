package com.sfbank.bayanati.messaging.airtel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sfbank.bayanati.messaging.domain.DispatchOutcome;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageDispatchResult;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.messaging.domain.Urgency;
import com.sfbank.bayanati.messaging.domain.WhatsAppPayload;
import com.sfbank.bayanati.messaging.domain.WhatsAppTemplateCategory;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The adapter against the captured contract. The endpoint resolves nowhere ({@code .invalid} is
 * reserved), the credentials are not real, and the phone number is not a real handset. The one
 * value that is NOT synthetic is the sender id {@code SFB}: it is the bank's real customer-visible
 * sender, it is not a secret, and it is already recorded in BL-080 — it appears here so the request
 * assertion checks what actually goes on the wire.
 *
 * <p>These are <strong>direct</strong> assertions on the outcome each captured response must
 * produce, so none of them can pass against a broken mapping and none needs a revert-restore proof.
 * The two properties a green mock suite <em>could</em> coexist with a broken implementation on —
 * the timeouts and the credential never reaching a log — are proven separately in {@code
 * AirtelSmsSenderTimeoutTest} and {@code AirtelSmsSenderCredentialLoggingTest}, both revert-restore
 * proven.
 */
class AirtelSmsSenderTest {

  private static final URI ENDPOINT = URI.create("https://sms.invalid/api/html_send_sms/");
  private static final String USERNAME = "test-user";
  private static final String PASSWORD = "test-secret-not-real";
  private static final String SENDER_ID = "SFB";

  private static final String STORED_DESTINATION = "+249912345678";
  private static final String EXPECTED_ON_THE_WIRE = "0912345678";

  /** Arabic, because every SMS this product sends is Arabic (OtpMessageRenderer). */
  private static final String ARABIC_BODY = "رمز التحقق: 123456";

  private static final String CAPTURED_SUCCESS =
      "Status: completed\nTotal Units: 2\n0912345678 -> apiMsgId: 9900001 (units=2)\n";

  private static final String CAPTURED_FAILURE =
      "Status: failed\nTotal Units: 0\n0912345678 -> FAILED: Invalid Sudanese number\n";

  private static final AirtelSmsProperties PROPERTIES =
      new AirtelSmsProperties(
          ENDPOINT, USERNAME, PASSWORD, SENDER_ID, Duration.ofSeconds(5), Duration.ofSeconds(15));

  private RestClient.Builder builder;
  private MockRestServiceServer server;
  private AirtelSmsSender sender;

  @BeforeEach
  void setUp() {
    builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    sender = new AirtelSmsSender(builder.build(), PROPERTIES);
  }

  @Test
  void servesSmsAndNothingElse() {
    assertEquals(java.util.Set.of(MessageChannel.SMS), sender.supportedChannels());
  }

  @Test
  void aMessageForAnotherChannelIsAProgrammingErrorNotAResult() {
    OutboundMessage whatsapp =
        new OutboundMessage(
            UUID.randomUUID(),
            MessageChannel.WHATSAPP,
            STORED_DESTINATION,
            new WhatsAppPayload(
                "otp", "ar", List.of("123456"), WhatsAppTemplateCategory.AUTHENTICATION),
            Urgency.INTERACTIVE,
            "corr-1");

    assertThrows(IllegalArgumentException.class, () -> sender.send(whatsapp));
  }

  @Test
  void theCapturedSuccessResponseIsAnAcceptedDispatchCarryingTheApiMsgId() {
    server
        .expect(method(HttpMethod.GET))
        .andRespond(withSuccess(CAPTURED_SUCCESS, MediaType.TEXT_PLAIN));

    MessageDispatchResult result = sender.send(message());

    assertEquals(DispatchOutcome.ACCEPTED, result.outcome());
    assertEquals("9900001", result.providerMessageId());
    assertEquals("completed", result.providerStatusCode());
    assertEquals(2, result.billedSegments());
    assertEquals(AirtelSmsSender.PROVIDER_ID, result.providerId());
    assertEquals(MessageChannel.SMS.wireValue(), result.channel());
    server.verify();
  }

  @Test
  void theCapturedFailureResponseIsARejectionCarryingAirtelsOwnWords() {
    server
        .expect(method(HttpMethod.GET))
        .andRespond(withSuccess(CAPTURED_FAILURE, MediaType.TEXT_PLAIN));

    MessageDispatchResult result = sender.send(message());

    assertEquals(DispatchOutcome.REJECTED, result.outcome());
    assertEquals("Invalid Sudanese number", result.providerStatusText());
    assertEquals("failed", result.providerStatusCode());
    assertEquals(0, result.billedSegments());
    assertNull(result.providerMessageId());
    server.verify();
  }

  /**
   * The number conversion, asserted on the wire. This is BL-080's trap: send the stored {@code
   * +249…} form and Airtel answers {@code Invalid Sudanese number}, which would be recorded as the
   * customer's number being bad while the real fault is ours.
   *
   * <p>Also asserts the Arabic body is percent-encoded as UTF-8 — the encoding the 2026-09-07
   * capture proved the gateway accepts — and that the sender id and credentials are carried.
   */
  @Test
  void theRequestCarriesTheNationalFormAndAUtf8EncodedArabicBody() {
    URI uri = sender.requestUri(EXPECTED_ON_THE_WIRE, ARABIC_BODY);
    Map<String, String> params = decodeQuery(uri);

    assertEquals(EXPECTED_ON_THE_WIRE, params.get(AirtelSmsSender.PHONE_NUMBER_PARAM));
    assertEquals(ARABIC_BODY, params.get(AirtelSmsSender.MESSAGE_PARAM));
    assertEquals(SENDER_ID, params.get(AirtelSmsSender.SENDER_PARAM));
    assertEquals(USERNAME, params.get(AirtelSmsSender.USERNAME_PARAM));
    assertEquals(PASSWORD, params.get(AirtelSmsSender.PASSWORD_PARAM));

    String rawQuery = uri.getRawQuery();
    assertTrue(
        rawQuery.contains("%D8") || rawQuery.contains("%d8"),
        "Arabic must be percent-encoded as UTF-8, got: " + rawQuery);
    assertTrue(
        !rawQuery.contains("+"),
        "a space must be %20, not the form-encoded '+' — Postman sent %20 in the proven capture");
    assertTrue(!rawQuery.contains(" "), "the query must carry no literal space, got: " + rawQuery);
  }

  /**
   * A destination outside Sudan is refused locally and never sent — {@code server} has no
   * expectation registered, so any HTTP call at all would fail this test.
   */
  @Test
  void aNonSudaneseDestinationIsRejectedWithoutCallingTheGateway() {
    OutboundMessage abroad =
        new OutboundMessage(
            UUID.randomUUID(),
            MessageChannel.SMS,
            "+971501234567",
            new SmsPayload(ARABIC_BODY),
            Urgency.INTERACTIVE,
            "corr-1");

    MessageDispatchResult result = sender.send(abroad);

    assertEquals(DispatchOutcome.REJECTED, result.outcome());
    assertEquals(AirtelSmsSender.DESTINATION_NOT_SUDANESE, result.providerStatusCode());
    assertEquals(0, result.billedSegments());
    server.verify();
  }

  /**
   * <strong>THIS TEST GUARDS AN UNVERIFIED ASSUMPTION.</strong> The wrong-password / authentication
   * failure response has never been captured (road map 0.2, BL-080). An auth failure means the
   * gateway is down for everyone and retrying cannot fix it, so it "should" be PERMANENT_FAILURE —
   * but nothing observed can distinguish it: HTTP status is 200 on both captured paths, so there is
   * no 401/403 to key on, and the only observed failure reason is a per-customer one.
   *
   * <p>So the behaviour asserted here is the deliberate defensive stand-in, not a modelled auth
   * response: any body that is not an observed shape becomes TRANSIENT_FAILURE, logged at ERROR.
   * TRANSIENT and not PERMANENT because PERMANENT is terminal in {@code OutboxState} — one
   * maintenance page misread as an auth failure would permanently kill a customer's OTP.
   *
   * <p><strong>When road map 0.2's real wrong-password capture exists, revisit this test and the
   * mapping it asserts.</strong> If that response turns out to be distinguishable, a
   * PERMANENT_FAILURE branch becomes correct and this expectation must change with it.
   */
  @Test
  void anUnrecognisedBodyIsATransientFailureUnverifiedStandInForTheUncapturedAuthFailure() {
    server
        .expect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "<html><head><title>401 Unauthorized</title></head></html>", MediaType.TEXT_HTML));

    MessageDispatchResult result = sender.send(message());

    assertEquals(DispatchOutcome.TRANSIENT_FAILURE, result.outcome());
    assertEquals(AirtelSmsSender.UNRECOGNISED_RESPONSE, result.providerStatusCode());
    assertNull(result.providerMessageId());
    assertEquals(MessageDispatchResult.SEGMENTS_NOT_APPLICABLE, result.billedSegments());
    server.verify();
  }

  /**
   * A non-200 has never been observed from this gateway, but if one arrives the body will not parse
   * and the fail-closed rule applies — never "no error, therefore delivered".
   */
  @Test
  void aNonSuccessStatusIsNotTreatedAsASend() {
    server
        .expect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("upstream unavailable"));

    MessageDispatchResult result = sender.send(message());

    assertEquals(DispatchOutcome.TRANSIENT_FAILURE, result.outcome());
    assertEquals(AirtelSmsSender.UNRECOGNISED_RESPONSE, result.providerStatusCode());
    server.verify();
  }

  @Test
  void anEmptyBodyIsNotTreatedAsASend() {
    server.expect(method(HttpMethod.GET)).andRespond(withSuccess("", MediaType.TEXT_PLAIN));

    MessageDispatchResult result = sender.send(message());

    assertEquals(DispatchOutcome.TRANSIENT_FAILURE, result.outcome());
    server.verify();
  }

  @Test
  void noResponseAtAllIsATransientFailure() {
    server
        .expect(method(HttpMethod.GET))
        .andRespond(withException(new IOException("connection reset")));

    MessageDispatchResult result = sender.send(message());

    assertEquals(DispatchOutcome.TRANSIENT_FAILURE, result.outcome());
    assertEquals(AirtelSmsSender.NO_RESPONSE, result.providerStatusCode());
    assertEquals(MessageDispatchResult.SEGMENTS_NOT_APPLICABLE, result.billedSegments());
    assertNotNull(result.attemptedAtIso());
    server.verify();
  }

  private static OutboundMessage message() {
    return new OutboundMessage(
        UUID.randomUUID(),
        MessageChannel.SMS,
        STORED_DESTINATION,
        new SmsPayload(ARABIC_BODY),
        Urgency.INTERACTIVE,
        "corr-1");
  }

  private static Map<String, String> decodeQuery(URI uri) {
    Map<String, String> params = new LinkedHashMap<>();
    for (String pair : uri.getRawQuery().split("&")) {
      int equals = pair.indexOf('=');
      params.put(
          URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
    }
    return params;
  }
}
