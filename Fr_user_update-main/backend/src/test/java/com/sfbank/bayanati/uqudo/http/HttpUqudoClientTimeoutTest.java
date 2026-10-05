package com.sfbank.bayanati.uqudo.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.uqudo.config.UqudoClientConfigurationTestAccess;
import com.sfbank.bayanati.uqudo.domain.UqudoJwsParser;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the read timeout is actually wired onto the {@code RestClient} the configuration builds —
 * the one thing {@link HttpUqudoClientTest} structurally cannot prove, because {@code
 * MockRestServiceServer} replaces the request factory the timeouts live on. A real socket instead:
 * the JDK's own {@code com.sun.net.httpserver} on port 0, whose handler holds the response past the
 * configured read timeout. Same shape as S3-02's and S3-14's timeout tests.
 *
 * <p>The token endpoint is the one exercised, because every other call mints a token first — so a
 * timeout here is the timeout that would strand all of them.
 */
class HttpUqudoClientTimeoutTest {

  private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
  private static final Duration HANDLER_HOLD = Duration.ofSeconds(3);

  private HttpServer server;
  private final CountDownLatch release = new CountDownLatch(1);

  @BeforeEach
  void startSlowServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/oauth/token",
        exchange -> {
          try {
            // Hold the whole response — headers included — past the client's read timeout.
            release.await(HANDLER_HOLD.toMillis(), TimeUnit.MILLISECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] body =
              "{\"access_token\":\"fake-token-not-real\",\"expires_in\":1800}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
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

  private HttpUqudoClient clientWithReadTimeout(Duration readTimeout) {
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    UqudoHttpProperties properties =
        new UqudoHttpProperties(
            URI.create(base + "/api"),
            URI.create(base),
            URI.create(base + "/api/.well-known/jwks.json"),
            "test-client-id-not-real",
            "test-client-secret-not-real",
            base,
            Duration.ofSeconds(2),
            readTimeout,
            Duration.ofMinutes(15));
    return new HttpUqudoClient(
        UqudoClientConfigurationTestAccess.restClient(properties),
        properties,
        new UqudoJwsParser(jws -> null));
  }

  @Test
  void aResponseSlowerThanTheReadTimeoutGivesUpRatherThanWaitingForTheServer() {
    long started = System.nanoTime();

    assertThrows(
        IllegalStateException.class, () -> clientWithReadTimeout(READ_TIMEOUT).issueAccessToken());

    long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
    assertTrue(
        elapsedMillis < HANDLER_HOLD.toMillis(),
        "the client must give up at the read timeout, not wait for the server: " + elapsedMillis);
  }

  @Test
  void aResponseWithinTheReadTimeoutSucceedsSoTheTimeoutIsNotJustAlwaysFiring() {
    // Same server, released immediately: proves the previous test's failure is the timeout, not a
    // broken server or a client that cannot talk plain HTTP to it.
    release.countDown();

    assertEquals(
        "fake-token-not-real",
        clientWithReadTimeout(Duration.ofSeconds(5)).issueAccessToken().value());
  }
}
