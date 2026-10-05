package com.sfbank.bayanati.corebanking.config;

import com.sfbank.bayanati.corebanking.domain.CoreBankingClient;
import com.sfbank.bayanati.corebanking.http.CoreBankingHttpProperties;
import com.sfbank.bayanati.corebanking.http.HttpCoreBankingClient;
import com.sfbank.bayanati.corebanking.stub.StubCoreBankingClient;
import com.sfbank.bayanati.corebanking.stub.StubCoreBankingProperties;
import java.net.http.HttpClient;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Selects the {@link CoreBankingClient} implementation from configuration.
 *
 * <p>The choice is made <strong>once, at startup, from one property</strong>: {@code
 * fru.core-banking.client=stub} for local development and every test, {@code =http} for the real
 * middleware call (S3-02, re-scoped by AD-007). The switch below runs exactly once per application
 * and produces exactly one bean.
 *
 * <p><strong>This is not the {@code if (mock)} branch the delivery model forbids.</strong> That
 * rule is about the call path: no code that serves a request may ask whether it is talking to a
 * stub. Nothing does — {@code AccountCheckService} holds a {@code CoreBankingClient} and cannot
 * tell which one it holds, and {@link StubCoreBankingClient} does not know a real implementation
 * exists. Selection has to happen somewhere; doing it in one startup factory keeps it in one
 * readable place instead of spread across conditional annotations.
 *
 * <p><strong>There is deliberately no default.</strong> A backend that silently ran a bank's
 * account check against a stub would be a serious defect and an invisible one, so anything other
 * than a recognised value — missing, empty, or a typo like {@code stubb} — fails at startup with a
 * message naming the property, what was found, and what is accepted.
 *
 * <p><strong>Timeouts are built here, explicitly.</strong> On this classpath (no Apache/Jetty/Netty
 * client, no Boot http-client autoconfiguration) {@code RestClient.builder().build()} yields a
 * {@code JdkClientHttpRequestFactory} over a default {@code java.net.http.HttpClient} with <em>no
 * connect timeout and no read timeout</em> — a wedged middleware would park a request thread
 * forever. The connect timeout goes on the {@code HttpClient} (the factory has no setter for it);
 * the read timeout goes on the factory. HTTP/1.1 is pinned because every observed response was
 * HTTP/1.1 and there is nothing to gain from an ALPN negotiation; redirects are never followed.
 */
@Configuration
@EnableConfigurationProperties({StubCoreBankingProperties.class, CoreBankingHttpProperties.class})
public class CoreBankingClientConfiguration {

  /** The property that selects the implementation. */
  public static final String CLIENT_PROPERTY = "fru.core-banking.client";

  /** The stub, for local development and every test. */
  public static final String STUB = "stub";

  /** The real middleware call. Needs {@code fru.core-banking.http.endpoint}. */
  public static final String HTTP = "http";

  private static final List<String> ACCEPTED = List.of(STUB, HTTP);

  @Bean
  public CoreBankingClient coreBankingClient(
      Environment environment,
      StubCoreBankingProperties stubProperties,
      CoreBankingHttpProperties httpProperties) {
    return select(environment.getProperty(CLIENT_PROPERTY), stubProperties, httpProperties);
  }

  /**
   * Package-private and static so the selection can be tested for every input — recognised,
   * missing, empty and unrecognised — without a Spring context.
   */
  static CoreBankingClient select(
      String selection,
      StubCoreBankingProperties stubProperties,
      CoreBankingHttpProperties httpProperties) {
    String normalised = selection == null ? "" : selection.trim();
    return switch (normalised) {
      case STUB -> new StubCoreBankingClient(stubProperties);
      case HTTP ->
          new HttpCoreBankingClient(restClient(httpProperties), requireEndpoint(httpProperties));
      default -> throw new IllegalStateException(rejectionMessage(selection));
    };
  }

  /**
   * The {@link RestClient} the real adapter runs on, with both timeouts set. Package-private and
   * static so the timeout wiring itself can be exercised by a test against a real socket — a {@code
   * MockRestServiceServer} replaces the request factory and therefore cannot prove it.
   */
  static RestClient restClient(CoreBankingHttpProperties properties) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(properties.readTimeout());
    return RestClient.builder().requestFactory(factory).build();
  }

  private static CoreBankingHttpProperties requireEndpoint(CoreBankingHttpProperties properties) {
    if (properties == null || properties.endpoint() == null) {
      throw new IllegalStateException(
          CLIENT_PROPERTY
              + " is '"
              + HTTP
              + "' but fru.core-banking.http.endpoint is not set. The middleware URL is"
              + " configuration, never a default: state it in this environment's configuration.");
    }
    return properties;
  }

  private static String rejectionMessage(String selection) {
    String found = selection == null ? "not set" : "'" + selection + "'";
    return CLIENT_PROPERTY
        + " is "
        + found
        + "; accepted values are "
        + ACCEPTED
        + ". There is no default: a backend running a real account check against a stub must fail"
        + " loudly, not quietly. Set it to 'stub' for local development and testing, or to 'http'"
        + " in any environment that must reach the bank's core banking middleware.";
  }
}
