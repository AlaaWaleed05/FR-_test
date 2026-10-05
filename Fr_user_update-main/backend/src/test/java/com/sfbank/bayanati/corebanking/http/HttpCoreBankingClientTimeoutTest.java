package com.sfbank.bayanati.corebanking.http;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.corebanking.config.CoreBankingClientConfigurationTestAccess;
import com.sfbank.bayanati.corebanking.domain.CoreBankingCheckResult;
import com.sfbank.bayanati.corebanking.domain.CoreBankingUnavailableException;
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
import org.springframework.web.client.ResourceAccessException;

/**
 * Proves the read timeout is actually wired onto the {@code RestClient} the configuration builds.
 *
 * <p>This is the one thing {@link HttpCoreBankingClientTest} structurally cannot prove: {@code
 * MockRestServiceServer} works by replacing the request factory — the very object the timeouts live
 * on — so a green mock suite would coexist happily with a client that hangs forever on a wedged
 * middleware (the default {@code RestClient.builder().build()} on this classpath has no timeouts at
 * all). Hence a real socket: the JDK's own {@code com.sun.net.httpserver} on port 0, whose handler
 * holds the response past the configured read timeout.
 *
 * <p>Revert-restore proven at S3-02: with {@code factory.setReadTimeout(...)} removed from {@code
 * CoreBankingClientConfiguration.restClient}, {@link #aResponseSlowerThanTheReadTimeoutIsAnOutage}
 * hangs until the handler releases (~3 s) and then PASSES the body through — i.e. it fails on the
 * assertion, not by timing out. See the session report.
 */
class HttpCoreBankingClientTimeoutTest {

  private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
  private static final Duration HANDLER_HOLD = Duration.ofSeconds(3);

  private HttpServer server;
  private final CountDownLatch release = new CountDownLatch(1);

  @BeforeEach
  void startSlowServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/CheckAccount",
        exchange -> {
          try {
            // Hold the whole response — headers included — past the client's read timeout.
            release.await(HANDLER_HOLD.toMillis(), TimeUnit.MILLISECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] body =
              "{\"Response_Code\":1,\"Response_Message\":\"Account Found\"}"
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

  private HttpCoreBankingClient clientWithReadTimeout(Duration readTimeout) {
    CoreBankingHttpProperties properties =
        new CoreBankingHttpProperties(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/CheckAccount"),
            Duration.ofSeconds(2),
            readTimeout);
    return new HttpCoreBankingClient(
        CoreBankingClientConfigurationTestAccess.restClient(properties), properties);
  }

  @Test
  void aResponseSlowerThanTheReadTimeoutIsAnOutage() {
    long started = System.nanoTime();

    CoreBankingUnavailableException thrown =
        assertThrows(
            CoreBankingUnavailableException.class,
            () -> clientWithReadTimeout(READ_TIMEOUT).check("0000000001"));

    long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
    assertInstanceOf(ResourceAccessException.class, thrown.getCause());
    assertTrue(
        elapsedMillis < HANDLER_HOLD.toMillis(),
        "the client must give up at the read timeout, not wait for the server: " + elapsedMillis);
  }

  @Test
  void aResponseWithinTheReadTimeoutSucceedsSoTheTimeoutIsNotJustAlwaysFiring() {
    // Same server, released immediately: proves the previous test's failure is the timeout, not a
    // broken server or a client that cannot talk plain HTTP to it.
    release.countDown();

    CoreBankingCheckResult result =
        clientWithReadTimeout(Duration.ofSeconds(5)).check("0000000001");

    assertTrue(result.code() == 1, "expected the held-then-released body to be read");
  }
}
