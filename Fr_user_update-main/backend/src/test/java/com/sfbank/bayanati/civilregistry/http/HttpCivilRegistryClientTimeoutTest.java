package com.sfbank.bayanati.civilregistry.http;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.civilregistry.config.CivilRegistryClientConfigurationTestAccess;
import com.sfbank.bayanati.civilregistry.domain.RegistryLookup;
import com.sfbank.bayanati.civilregistry.domain.RegistryUnreachableException;
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
 * Proves the read timeout is actually wired onto the {@code RestClient} the configuration builds —
 * the one thing {@link HttpCivilRegistryClientTest} structurally cannot prove, because {@code
 * MockRestServiceServer} replaces the request factory the timeouts live on. A real socket instead:
 * the JDK's own {@code com.sun.net.httpserver} on port 0, whose handler holds the response past the
 * configured read timeout. Same shape as S3-02's {@code HttpCoreBankingClientTimeoutTest}.
 *
 * <p>Revert-restore proven at the AD-002b build session: with {@code factory.setReadTimeout(...)}
 * removed from {@code CivilRegistryClientConfiguration.restClient}, {@link
 * #aResponseSlowerThanTheReadTimeoutIsUnreachable} waits the handler out and then finds the record
 * — it fails on the assertion, not by timing out. See the session report.
 */
class HttpCivilRegistryClientTimeoutTest {

  private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
  private static final Duration HANDLER_HOLD = Duration.ofSeconds(3);
  private static final String NID = "00000000001";

  private HttpServer server;
  private final CountDownLatch release = new CountDownLatch(1);

  @BeforeEach
  void startSlowServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/GetCRSData",
        exchange -> {
          try {
            // Hold the whole response — headers included — past the client's read timeout.
            release.await(HANDLER_HOLD.toMillis(), TimeUnit.MILLISECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] body =
              ("{\"IDENTITY_NUMBER\":\"" + NID + "\",\"NAME\":\"x\"}")
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

  private HttpCivilRegistryClient clientWithReadTimeout(Duration readTimeout) {
    CivilRegistryHttpProperties properties =
        new CivilRegistryHttpProperties(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/GetCRSData"),
            Duration.ofSeconds(2),
            readTimeout);
    return new HttpCivilRegistryClient(
        CivilRegistryClientConfigurationTestAccess.restClient(properties), properties);
  }

  @Test
  void aResponseSlowerThanTheReadTimeoutIsUnreachable() {
    long started = System.nanoTime();

    RegistryUnreachableException thrown =
        assertThrows(
            RegistryUnreachableException.class,
            () -> clientWithReadTimeout(READ_TIMEOUT).lookup(NID));

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

    RegistryLookup lookup = clientWithReadTimeout(Duration.ofSeconds(5)).lookup(NID);

    assertTrue(lookup.found(), "expected the held-then-released record to be read");
  }
}
