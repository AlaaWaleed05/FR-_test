package com.sfbank.bayanati.messaging.airtel;

import com.sfbank.bayanati.messaging.domain.DispatchOutcome;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageDispatchResult;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The real SMS adapter: one HTTPS {@code GET} to the Airtel Sudan gateway (BL-080). Plumbing —
 * lives outside {@code domain}/{@code service} by the CLAUDE.md package rule, one package per
 * external system so it is its own quarantine boundary, and is selected by {@code
 * fru.messaging.sms.provider=http}.
 *
 * <p>Serves SMS and nothing else. {@code ChannelRoutingMessageSender} refuses at startup if it is
 * mapped to another channel, and {@code MessageSenderConfiguration} refuses {@code http} for a
 * non-SMS channel before that.
 *
 * <p><strong>THE REQUEST CARRIES THE ACCOUNT PASSWORD IN ITS QUERY STRING.</strong> The captured
 * contract is a GET with {@code username} and {@code password} as query parameters, so the request
 * URI is a credential. Three rules follow, and all three are load-bearing rather than stylistic:
 *
 * <ol>
 *   <li><strong>The URI is never logged</strong>, at any level, on any path.
 *   <li><strong>{@link RestClientException#getMessage()} is never called.</strong> For a connect or
 *       read failure the JDK client builds its message from the request URI — password included —
 *       and {@code HttpCoreBankingClient} copies exactly that message into its own exception. Doing
 *       the same here would write the credential into the log and into the audit trail. Only the
 *       exception's simple class name is used.
 *   <li><strong>A raw response body is never stored or logged.</strong> An HTML error or proxy page
 *       commonly echoes the requested URL. {@code providerStatusText} is persisted to {@code
 *       app.notification_outbox} and audited, so an unrecognised body is described by {@link
 *       AirtelSmsResponse#unrecognisedReason()} — our own bounded words — never quoted.
 * </ol>
 *
 * <p>{@code AirtelSmsSenderCredentialLoggingTest} holds these three, and is revert-restore proven.
 *
 * <p><strong>The uncaptured wrong-password response — [UNVERIFIED], and deliberately not guessed.
 * </strong> An authentication failure means the gateway is down for everyone and retrying cannot
 * fix it, so in principle it should be {@link DispatchOutcome#PERMANENT_FAILURE}. It is not
 * implemented that way, because it cannot be detected from anything that has been observed: HTTP
 * status is 200 on both captured paths (BL-080, confirmed), so there is no 401/403 to key on, and
 * the only observed failure reason is {@code Invalid Sudanese number}. Keying a terminal outcome
 * off a guessed reason substring would be worse than not detecting it — {@code PERMANENT_FAILURE}
 * is terminal in {@code OutboxState}, so one maintenance page misread as an auth failure would
 * permanently kill a customer's OTP with no retry. So an unrecognised body is {@link
 * DispatchOutcome#TRANSIENT_FAILURE} <em>logged at ERROR</em>: retries continue, and a real outage
 * is still visible to an operator. <strong>Revisit when road map 0.2's wrong-password capture
 * exists.</strong>
 *
 * <p><strong>Timeouts are not set here</strong> — they live on the {@link RestClient}'s request
 * factory, built by {@code MessageSenderConfiguration}, so tests can bind {@code
 * MockRestServiceServer} to the builder. A {@code MockRestServiceServer} test therefore proves
 * nothing about timeouts; {@code AirtelSmsSenderTimeoutTest} covers that with a real socket.
 * <strong> No retries</strong>: the port forbids them, and the outbox lease is the retry.
 */
public class AirtelSmsSender implements MessageSender {

  /** {@link MessageDispatchResult#providerId()} this adapter reports. */
  public static final String PROVIDER_ID = "airtel";

  /** {@code providerStatusCode} when the response did not match any observed shape. */
  static final String UNRECOGNISED_RESPONSE = "UNRECOGNISED_RESPONSE";

  /** {@code providerStatusCode} when the destination is not a Sudanese number. Never sent. */
  static final String DESTINATION_NOT_SUDANESE = "DESTINATION_NOT_SUDANESE";

  /** {@code providerStatusCode} when no response existed — timeout, refused, DNS, TLS. */
  static final String NO_RESPONSE = "NO_RESPONSE";

  static final String USERNAME_PARAM = "username";
  static final String PASSWORD_PARAM = "password";
  static final String PHONE_NUMBER_PARAM = "phone_number";
  static final String MESSAGE_PARAM = "message";
  static final String SENDER_PARAM = "sender";

  private static final Logger log = LoggerFactory.getLogger(AirtelSmsSender.class);

  private final RestClient restClient;
  private final AirtelSmsProperties properties;

  public AirtelSmsSender(RestClient restClient, AirtelSmsProperties properties) {
    this.restClient = restClient;
    this.properties = properties;
  }

  @Override
  public Set<MessageChannel> supportedChannels() {
    return Set.of(MessageChannel.SMS);
  }

  @Override
  public MessageDispatchResult send(OutboundMessage message) {
    Objects.requireNonNull(message, "message");
    if (message.channel() != MessageChannel.SMS) {
      // A programming error, not a provider rejection -- the port says only these throw.
      throw new IllegalArgumentException(
          "AirtelSmsSender serves sms only but was given " + message.channel().wireValue());
    }
    long startedAt = System.nanoTime();

    Optional<String> nationalForm = AirtelDestination.toNationalForm(message.destination());
    if (nationalForm.isEmpty()) {
      // Never sent. REJECTED is terminal and blames the destination, which is correct: this
      // gateway is a domestic route and the number has no national form it could address.
      // No number in the message -- a destination is PII.
      log.warn("Airtel SMS not attempted: destination is not a Sudanese (+249) number");
      return result(
          message,
          DispatchOutcome.REJECTED,
          null,
          DESTINATION_NOT_SUDANESE,
          "destination is not a Sudanese (+249) number; this gateway is a domestic route",
          0,
          startedAt);
    }
    String numberSent = nationalForm.get();
    String body = ((SmsPayload) message.payload()).body();

    ResponseEntity<byte[]> response;
    try {
      response =
          restClient
              .get()
              .uri(requestUri(numberSent, body))
              .accept(MediaType.TEXT_PLAIN, MediaType.ALL)
              .retrieve()
              .onStatus(status -> true, (request, res) -> {})
              .toEntity(byte[].class);
    } catch (RestClientException noResponse) {
      // NEVER noResponse.getMessage(): it is built from the request URI, which carries the
      // password. The class name alone says timeout vs refused vs TLS well enough to diagnose.
      String exceptionName = noResponse.getClass().getSimpleName();
      log.error("Airtel SMS send got no response: exception={}", exceptionName);
      return result(
          message,
          DispatchOutcome.TRANSIENT_FAILURE,
          null,
          NO_RESPONSE,
          exceptionName,
          MessageDispatchResult.SEGMENTS_NOT_APPLICABLE,
          startedAt);
    }

    byte[] raw = response.getBody() == null ? new byte[0] : response.getBody();
    MediaType contentType = response.getHeaders().getContentType();
    AirtelSmsResponse parsed =
        AirtelSmsResponseParser.parse(new String(raw, StandardCharsets.UTF_8), numberSent);

    return switch (parsed.kind()) {
      case SUCCESS ->
          result(
              message,
              DispatchOutcome.ACCEPTED,
              parsed.apiMsgId(),
              parsed.statusWord(),
              null,
              parsed.totalUnits(),
              startedAt);
      case RECIPIENT_FAILED ->
          result(
              message,
              DispatchOutcome.REJECTED,
              null,
              parsed.statusWord(),
              parsed.failureReason(),
              parsed.totalUnits(),
              startedAt);
      case UNRECOGNISED -> {
        // ERROR, not WARN: this is the only signal an operator gets that the gateway may be
        // down for everyone -- including the uncaptured wrong-password case. Shape only, never
        // the body: an error page can echo the request URI, which carries the password.
        log.error(
            "Airtel SMS response not recognised, treated as a transient failure: reason={}"
                + " httpStatus={} contentType={} responseBytes={}",
            parsed.unrecognisedReason(),
            response.getStatusCode().value(),
            contentType == null ? null : contentType.toString(),
            raw.length);
        yield result(
            message,
            DispatchOutcome.TRANSIENT_FAILURE,
            null,
            UNRECOGNISED_RESPONSE,
            parsed.unrecognisedReason(),
            MessageDispatchResult.SEGMENTS_NOT_APPLICABLE,
            startedAt);
      }
    };
  }

  /**
   * Builds the send URI. Values are percent-encoded as UTF-8 explicitly, with {@code +} rewritten
   * to {@code %20} — {@link URLEncoder} produces the form-encoded {@code +} for a space, and the
   * capture that proved an Arabic body works was taken through Postman, which sends {@code %20}.
   * The encoding is done here rather than through {@code UriComponentsBuilder} so no URI-template
   * semantics are applied to a credential: a password containing {@code {} } would otherwise be
   * read as a placeholder.
   *
   * <p>Package-private so a test can assert the query it produces. <strong>The returned URI is a
   * credential — it must never be logged.</strong>
   */
  URI requestUri(String numberSent, String body) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put(USERNAME_PARAM, properties.username());
    params.put(PASSWORD_PARAM, properties.password());
    params.put(PHONE_NUMBER_PARAM, numberSent);
    params.put(MESSAGE_PARAM, body);
    params.put(SENDER_PARAM, properties.senderId());

    StringJoiner query = new StringJoiner("&");
    params.forEach((name, value) -> query.add(name + "=" + encode(value)));
    return URI.create(properties.endpoint() + "?" + query);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
        .replace("+", "%20");
  }

  private static MessageDispatchResult result(
      OutboundMessage message,
      DispatchOutcome outcome,
      String providerMessageId,
      String providerStatusCode,
      String providerStatusText,
      int billedSegments,
      long startedAt) {
    return new MessageDispatchResult(
        message.messageId().toString(),
        message.channel().wireValue(),
        outcome,
        PROVIDER_ID,
        providerMessageId,
        providerStatusCode,
        providerStatusText,
        billedSegments,
        Instant.now().toString(),
        (System.nanoTime() - startedAt) / 1_000_000L);
  }
}
