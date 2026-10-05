package com.sfbank.bayanati.uqudo.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.sfbank.bayanati.uqudo.domain.UqudoClient;
import com.sfbank.bayanati.uqudo.domain.UqudoJwsParser;
import com.sfbank.bayanati.uqudo.http.HttpUqudoClient;
import com.sfbank.bayanati.uqudo.http.UqudoHttpProperties;
import com.sfbank.bayanati.uqudo.http.UqudoJwksSignatureVerifier;
import com.sfbank.bayanati.uqudo.stub.StubUqudoClient;
import java.net.MalformedURLException;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Selects the {@link UqudoClient} implementation from configuration — exact mirror of {@code
 * CoreBankingClientConfiguration} and {@code CivilRegistryClientConfiguration}: one property, no
 * default, one bean, chosen once at startup. {@code fru.uqudo.client=stub} for local development
 * and every test, {@code =http} for the real Uqudo tenant.
 *
 * <p>There is deliberately no default: an unset or unrecognised value fails startup naming the
 * property, what was found, and what is accepted, rather than silently running against a stub.
 *
 * <p><strong>Timeouts and the JWKS cache are built here, explicitly.</strong> On this classpath
 * {@code RestClient.builder().build()} yields a {@code JdkClientHttpRequestFactory} over a default
 * {@code java.net.http.HttpClient} with no connect and no read timeout: the connect timeout goes on
 * the {@code HttpClient}, the read timeout on the factory, and redirects are never followed. The
 * JWKS source is caching, refresh-ahead and rate-limited so an unknown {@code kid} costs at most
 * one fetch and a verification never fetches per request (docs/components/uqudo-sdk.md).
 */
@Configuration
@EnableConfigurationProperties(UqudoHttpProperties.class)
public class UqudoClientConfiguration {

  public static final String CLIENT_PROPERTY = "fru.uqudo.client";

  public static final String STUB = "stub";

  /** The real Uqudo tenant. Needs the whole {@code fru.uqudo.http.*} block. */
  public static final String HTTP = "http";

  private static final List<String> ACCEPTED = List.of(STUB, HTTP);

  /** How long a JWKS fetch may take before the cached copy is preferred. */
  private static final long JWKS_REFRESH_TIMEOUT_MS = 5_000;

  /**
   * Minimum gap between two outbound JWKS fetches — the rate limit the vendor guidance asks for.
   */
  private static final long JWKS_MIN_FETCH_INTERVAL_MS = 30_000;

  @Bean
  public StubUqudoClient stubUqudoClient() {
    // Always constructed: the stub also exposes fabricateJws()/fabricateTamperedJws() that
    // integration tests autowire directly (mirrors StubMessageSender.recordedSends() being
    // autowired for inspection) regardless of which implementation ends up serving UqudoClient.
    return new StubUqudoClient();
  }

  @Bean
  public UqudoClient uqudoClient(
      Environment environment,
      StubUqudoClient stubUqudoClient,
      UqudoHttpProperties httpProperties) {
    return select(environment.getProperty(CLIENT_PROPERTY), stubUqudoClient, httpProperties);
  }

  /**
   * Package-private and static so the selection can be tested for every input — recognised,
   * missing, empty and unrecognised — without a Spring context.
   */
  static UqudoClient select(
      String selection, StubUqudoClient stubUqudoClient, UqudoHttpProperties httpProperties) {
    String normalised = selection == null ? "" : selection.trim();
    return switch (normalised) {
      case STUB -> stubUqudoClient;
      case HTTP -> httpUqudoClient(requireFullyConfigured(httpProperties));
      default -> throw new IllegalStateException(rejectionMessage(selection));
    };
  }

  /**
   * The real client, wired to the shared parser. The parser is constructed here rather than by the
   * client so the signature step is visibly the only thing that differs from the stub.
   */
  static HttpUqudoClient httpUqudoClient(UqudoHttpProperties properties) {
    UqudoJwsParser parser =
        new UqudoJwsParser(
            new UqudoJwksSignatureVerifier(
                jwkSource(properties), properties.issuer(), properties.clientId()));
    return new HttpUqudoClient(restClient(properties), properties, parser);
  }

  /**
   * The {@link RestClient} the real adapter runs on, with both timeouts set. Package-private and
   * static so the timeout wiring itself can be exercised by a test against a real socket — a {@code
   * MockRestServiceServer} replaces the request factory and therefore cannot prove it.
   */
  static RestClient restClient(UqudoHttpProperties properties) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(properties.readTimeout());
    return RestClient.builder().requestFactory(factory).build();
  }

  /**
   * Caching, refresh-ahead, rate-limited JWKS retrieval — never a fetch per verification. The
   * refresh-ahead pass is unscheduled on purpose: a scheduled one would start a background thread
   * this configuration does not own and would have to shut down.
   */
  static JWKSource<SecurityContext> jwkSource(UqudoHttpProperties properties) {
    long timeToLiveMs = properties.jwksCacheDuration().toMillis();
    try {
      return JWKSourceBuilder.create(
              properties.jwksUrl().toURL(),
              new DefaultResourceRetriever(
                  (int) properties.connectTimeout().toMillis(),
                  (int) properties.readTimeout().toMillis()))
          .cache(timeToLiveMs, JWKS_REFRESH_TIMEOUT_MS)
          .refreshAheadCache(timeToLiveMs / 3, false)
          .rateLimited(JWKS_MIN_FETCH_INTERVAL_MS)
          .build();
    } catch (MalformedURLException notAUrl) {
      throw new IllegalStateException(
          "fru.uqudo.http.jwks-url is not a URL the JWKS retriever can open", notAUrl);
    }
  }

  /**
   * Every {@code fru.uqudo.http.*} value the real client needs, checked together so a
   * half-configured environment names all of its gaps at once rather than one per restart. The
   * secret is checked for presence and never echoed.
   */
  private static UqudoHttpProperties requireFullyConfigured(UqudoHttpProperties properties) {
    List<String> missing = new ArrayList<>();
    if (properties == null) {
      missing.add("the whole fru.uqudo.http block");
    } else {
      if (properties.authUrl() == null) {
        missing.add("fru.uqudo.http.auth-url");
      }
      if (properties.apiBase() == null) {
        missing.add("fru.uqudo.http.api-base");
      }
      if (properties.jwksUrl() == null) {
        missing.add("fru.uqudo.http.jwks-url");
      }
      if (isBlank(properties.clientId())) {
        missing.add("fru.uqudo.http.client-id");
      }
      if (isBlank(properties.clientSecret())) {
        missing.add("fru.uqudo.http.client-secret");
      }
      if (isBlank(properties.issuer())) {
        missing.add("fru.uqudo.http.issuer");
      }
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          CLIENT_PROPERTY
              + " is '"
              + HTTP
              + "' but "
              + String.join(", ", missing)
              + (missing.size() == 1 ? " is" : " are")
              + " not set. The Uqudo tenant, its endpoints and its credentials are configuration,"
              + " never a default and never baked into a build (R-007): state them in this"
              + " environment's configuration, with the credentials supplied only from the local"
              + " gitignored config.");
    }
    return properties;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String rejectionMessage(String selection) {
    String found = selection == null ? "not set" : "'" + selection + "'";
    return CLIENT_PROPERTY
        + " is "
        + found
        + "; accepted values are "
        + ACCEPTED
        + ". There is no default: a backend running a real identity scan against a stub must fail"
        + " loudly, not quietly. Set it to 'stub' for local development and testing, or to 'http'"
        + " in any environment that must reach the real Uqudo tenant.";
  }
}
