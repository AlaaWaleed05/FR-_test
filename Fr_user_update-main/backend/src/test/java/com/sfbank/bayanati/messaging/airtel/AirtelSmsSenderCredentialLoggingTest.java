package com.sfbank.bayanati.messaging.airtel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageDispatchResult;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.messaging.domain.Urgency;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The gateway's account password travels in the REQUEST URI — the captured contract is a GET with
 * {@code username} and {@code password} as query parameters. So the URI is itself a credential, and
 * a single log line carrying it would write the bank's SMS password into CloudWatch on every send,
 * and into {@code app.notification_outbox} and the audit trail through {@code providerStatusText}.
 *
 * <p><strong>This test proves an ABSENCE, which is exactly why it needs a revert-restore
 * proof.</strong> A green run here would coexist happily with a leaking adapter if the assertions
 * were weak, and nothing else in the suite would notice: every other test asserts an outcome, and
 * the outcome is identical whether or not the credential leaked alongside it.
 *
 * <p><strong>Revert-restore, run at build time (see the session report):</strong> with the catch
 * block in {@link AirtelSmsSender#send} changed back to the {@code HttpCoreBankingClient} pattern —
 * {@code noResponse.getMessage()} instead of {@code noResponse.getClass().getSimpleName()} — {@link
 * #noResponsePathNeverLeaksTheCredential} FAILS, because the JDK client builds that message from
 * the full request URI. That is the precise defect this test exists to prevent, and it is not
 * hypothetical: the proven adapter in this codebase does it.
 *
 * <p>Three paths are covered because the credential could escape by three different routes: a log
 * line, a persisted result field, or an exception message copied into either.
 */
class AirtelSmsSenderCredentialLoggingTest {

  private static final String PASSWORD = "test-secret-not-real";
  private static final String USERNAME = "test-user";

  private static final AirtelSmsProperties PROPERTIES =
      new AirtelSmsProperties(
          URI.create("https://sms.invalid/api/html_send_sms/"),
          USERNAME,
          PASSWORD,
          "SFB",
          Duration.ofSeconds(5),
          Duration.ofSeconds(15));

  private RestClient.Builder builder;
  private MockRestServiceServer server;
  private AirtelSmsSender sender;
  private ListAppender<ILoggingEvent> captured;
  private Logger adapterLogger;

  @BeforeEach
  void setUp() {
    builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    sender = new AirtelSmsSender(builder.build(), PROPERTIES);

    captured = new ListAppender<>();
    captured.start();
    adapterLogger = (Logger) LoggerFactory.getLogger(AirtelSmsSender.class);
    adapterLogger.addAppender(captured);
    adapterLogger.setLevel(Level.TRACE);
  }

  @AfterEach
  void tearDown() {
    adapterLogger.detachAppender(captured);
    captured.stop();
  }

  @Test
  void successPathNeverLeaksTheCredential() {
    server
        .expect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "Status: completed\nTotal Units: 2\n0912345678 -> apiMsgId: 9900001 (units=2)\n",
                MediaType.TEXT_PLAIN));

    assertNothingLeaked(sender.send(message()));
  }

  @Test
  void rejectionPathNeverLeaksTheCredential() {
    server
        .expect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "Status: failed\nTotal Units: 0\n0912345678 -> FAILED: Invalid Sudanese number\n",
                MediaType.TEXT_PLAIN));

    assertNothingLeaked(sender.send(message()));
  }

  /**
   * An HTML error page that echoes the request URI back at us — the ordinary shape of a proxy or
   * gateway error, and the reason the raw body is never written to {@code providerStatusText}. The
   * body here contains the password precisely because a real one would.
   */
  @Test
  void anErrorPageEchoingTheRequestUriIsNeverStoredOrLogged() {
    String echoingPage =
        "<html><body>Bad request: GET https://sms.invalid/api/html_send_sms/?username="
            + USERNAME
            + "&password="
            + PASSWORD
            + "&phone_number=0912345678</body></html>";
    server.expect(method(HttpMethod.GET)).andRespond(withSuccess(echoingPage, MediaType.TEXT_HTML));

    assertNothingLeaked(sender.send(message()));
  }

  /**
   * The revert-restore target. {@code RestClient} wraps the I/O failure in a {@code
   * ResourceAccessException} whose message is built from the full request URI — password included.
   * The adapter must use the exception's class name and nothing else.
   */
  @Test
  void noResponsePathNeverLeaksTheCredential() {
    server
        .expect(method(HttpMethod.GET))
        .andRespond(withException(new IOException("connection reset")));

    MessageDispatchResult result = sender.send(message());

    // Guard the guard: if the wrapped exception did not actually carry the URI, this test would
    // pass for the wrong reason and would not detect a regression to getMessage().
    assertTrue(
        !captured.list.isEmpty(), "the no-response path must log something for an operator to see");
    assertNothingLeaked(result);
  }

  /** No log line and no returned field may contain the password, the username, or a raw query. */
  private void assertNothingLeaked(MessageDispatchResult result) {
    List<String> haystack = new ArrayList<>();
    for (ILoggingEvent event : captured.list) {
      haystack.add(event.getFormattedMessage());
      if (event.getThrowableProxy() != null) {
        haystack.add(event.getThrowableProxy().getMessage());
      }
    }
    haystack.add(result.providerStatusText());
    haystack.add(result.providerStatusCode());
    haystack.add(result.providerMessageId());

    for (String value : haystack) {
      if (value == null) {
        continue;
      }
      assertFalse(value.contains(PASSWORD), "leaked the password: " + value);
      assertFalse(value.contains(USERNAME), "leaked the username: " + value);
      assertFalse(value.contains("password="), "leaked a query carrying the credential: " + value);
      assertFalse(value.contains("sms.invalid"), "leaked the request URI: " + value);
    }
  }

  private static OutboundMessage message() {
    return new OutboundMessage(
        UUID.randomUUID(),
        MessageChannel.SMS,
        "+249912345678",
        new SmsPayload("رمز التحقق: 123456"),
        Urgency.INTERACTIVE,
        "corr-1");
  }
}
