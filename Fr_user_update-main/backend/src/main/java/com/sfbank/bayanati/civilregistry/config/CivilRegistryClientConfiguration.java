package com.sfbank.bayanati.civilregistry.config;

import com.sfbank.bayanati.civilregistry.domain.CivilRegistryClient;
import com.sfbank.bayanati.civilregistry.http.CivilRegistryHttpProperties;
import com.sfbank.bayanati.civilregistry.http.HttpCivilRegistryClient;
import com.sfbank.bayanati.civilregistry.stub.StubCivilRegistryClient;
import com.sfbank.bayanati.civilregistry.stub.StubCivilRegistryProperties;
import java.net.http.HttpClient;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Selects the {@link CivilRegistryClient} implementation from configuration — exact mirror of
 * {@code CoreBankingClientConfiguration}: one property, no default, one bean, chosen once at
 * startup. {@code fru.civil-registry.client=stub} for local development and every test, {@code
 * =http} for the bank's real {@code GetCRSData} service (AD-002b).
 *
 * <p>There is deliberately no default: a backend that silently ran a customer's identity lookup
 * against a stub would be a serious and invisible defect, so anything other than a recognised value
 * fails at startup naming the property, what was found, and what is accepted.
 *
 * <p><strong>Timeouts are built here, explicitly</strong>, for the same reason S3-02 stated: on
 * this classpath {@code RestClient.builder().build()} yields a {@code JdkClientHttpRequestFactory}
 * over a default {@code java.net.http.HttpClient} with no connect and no read timeout. The connect
 * timeout goes on the {@code HttpClient}; the read timeout on the factory; HTTP/1.1 pinned (the
 * registry is GlassFish 4.1 on Java 1.8, HTTP/1.1 only); redirects never followed.
 */
@Configuration
@EnableConfigurationProperties({
  StubCivilRegistryProperties.class,
  CivilRegistryHttpProperties.class
})
public class CivilRegistryClientConfiguration {

  public static final String CLIENT_PROPERTY = "fru.civil-registry.client";

  public static final String STUB = "stub";

  /** The real service call. Needs {@code fru.civil-registry.http.endpoint}. */
  public static final String HTTP = "http";

  private static final List<String> ACCEPTED = List.of(STUB, HTTP);

  @Bean
  public StubCivilRegistryClient stubCivilRegistryClient(StubCivilRegistryProperties properties) {
    // Always constructed: integration tests autowire this bean directly to call
    // overrideOutcome(), the same mutable-control shape as StubUqudoClient's fabricate methods.
    return new StubCivilRegistryClient(properties);
  }

  @Bean
  public CivilRegistryClient civilRegistryClient(
      Environment environment,
      StubCivilRegistryClient stubCivilRegistryClient,
      CivilRegistryHttpProperties httpProperties) {
    return select(
        environment.getProperty(CLIENT_PROPERTY), stubCivilRegistryClient, httpProperties);
  }

  /**
   * Package-private and static so the selection can be tested for every input — recognised,
   * missing, empty and unrecognised — without a Spring context.
   */
  static CivilRegistryClient select(
      String selection,
      StubCivilRegistryClient stubCivilRegistryClient,
      CivilRegistryHttpProperties httpProperties) {
    String normalised = selection == null ? "" : selection.trim();
    return switch (normalised) {
      case STUB -> stubCivilRegistryClient;
      case HTTP ->
          new HttpCivilRegistryClient(restClient(httpProperties), requireEndpoint(httpProperties));
      default -> throw new IllegalStateException(rejectionMessage(selection));
    };
  }

  /**
   * The {@link RestClient} the real adapter runs on, with both timeouts set. Package-private and
   * static so the timeout wiring itself can be exercised by a test against a real socket — a {@code
   * MockRestServiceServer} replaces the request factory and therefore cannot prove it.
   */
  static RestClient restClient(CivilRegistryHttpProperties properties) {
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

  private static CivilRegistryHttpProperties requireEndpoint(
      CivilRegistryHttpProperties properties) {
    if (properties == null || properties.endpoint() == null) {
      throw new IllegalStateException(
          CLIENT_PROPERTY
              + " is '"
              + HTTP
              + "' but fru.civil-registry.http.endpoint is not set. The registry URL is"
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
        + ". There is no default: a backend running a real Civil Registry lookup against a stub"
        + " must fail loudly, not quietly. Set it to 'stub' for local development and testing, or"
        + " to 'http' in any environment that must reach the bank's Civil Registry service.";
  }
}
