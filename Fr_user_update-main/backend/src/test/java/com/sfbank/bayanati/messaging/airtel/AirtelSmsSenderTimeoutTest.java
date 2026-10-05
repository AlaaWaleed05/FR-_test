package com.sfbank.bayanati.messaging.airtel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sfbank.bayanati.messaging.config.MessageSenderConfigurationTestAccess;
import com.sfbank.bayanati.messaging.domain.DispatchOutcome;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageDispatchResult;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.messaging.domain.SmsPayload;
import com.sfbank.bayanati.messaging.domain.Urgency;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the read timeout is actually wired onto the {@code RestClient} the configuration builds.
 *
 * <p>This is the one thing {@code AirtelSmsSenderTest} structurally cannot prove: {@code
 * MockRestServiceServer} works by replacing the request factory — the very object the timeouts live
 * on — so a green mock suite would coexist happily with an adapter that hangs forever on a wedged
 * gateway. On this classpath {@code RestClient.builder().build()} has no timeouts at all. That
 * matters more here than for the core banking adapter: an INTERACTIVE OTP send runs on the request
 * thread with a customer watching, and the port requires an implementation to bound its own latency
 * and return TRANSIENT_FAILURE.
 *
 * <p>Hence a real socket: the JDK's own {@code com.sun.net.httpserver} on port 0, whose handler
 * holds the response past the configured read timeout and then returns a <em>valid captured success
 * body</em>.
 *
 * <p><strong>Revert-restore proven at build time (see the session report):</strong> with {@code
 * factory.setReadTimeout(...)} removed from {@code MessageSenderConfiguration.restClient}, the
 * handler releases after ~3 s, the adapter parses that success body, and the result becomes {@code
 * ACCEPTED} with an {@code apiMsgId} — so the test fails on its outcome assertion after ~5 s rather
 * than by timing out. The handler returns a <em>valid</em> body deliberately: had it returned
 * garbage, the broken build would still have produced a transient failure and the test would have
 * passed against it for the wrong reason.
 */
class AirtelSmsSenderTimeoutTest {

  private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
  private static final Duration HANDLER_HOLD = Duration.ofSeconds(3);
  private static final String PATH = "/api/html_send_sms/";

  private HttpServer server;
  private final CountDownLatch release = new CountDownLatch(1);

  @BeforeEach
  void startSlowServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        PATH,
        exchange -> {
          try {
            // Hold the whole response -- headers included -- past the client's read timeout.
            release.await(HANDLER_HOLD.toMillis(), TimeUnit.MILLISECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          byte[] body =
              "Status: completed\nTotal Units: 2\n0912345678 -> apiMsgId: 9900001 (units=2)\n"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    release.countDown();
    server.stop(0);
  }

  @Test
  void aGatewaySlowerThanTheReadTimeoutIsATransientFailure() {
    AirtelSmsProperties properties =
        new AirtelSmsProperties(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + PATH),
            "test-user",
            "test-secret-not-real",
            "SFB",
            Duration.ofSeconds(5),
            READ_TIMEOUT);
    AirtelSmsSender sender =
        new AirtelSmsSender(
            MessageSenderConfigurationTestAccess.restClient(properties), properties);

    MessageDispatchResult result =
        sender.send(
            new OutboundMessage(
                UUID.randomUUID(),
                MessageChannel.SMS,
                "+249912345678",
                new SmsPayload("رمز التحقق: 123456"),
                Urgency.INTERACTIVE,
                "corr-1"));

    assertEquals(DispatchOutcome.TRANSIENT_FAILURE, result.outcome());
    assertEquals(
        AirtelSmsSender.NO_RESPONSE,
        result.providerStatusCode(),
        "a read timeout must be NO_RESPONSE, not a parsed body — see this test's revert-restore note");
    assertNull(result.providerMessageId());
  }
}
