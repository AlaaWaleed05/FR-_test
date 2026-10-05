package com.sfbank.bayanati.uqudo.http;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link HttpUqudoClient}, bound from {@code fru.uqudo.http.*}.
 *
 * <p><strong>Nothing here has a host or credential default.</strong> R-007 and CLAUDE.md: the
 * tenant identifier and every endpoint are configuration, never hardcoded and never baked into a
 * build, because the tenant changes before production — we currently run on FIB's borrowed tenant
 * (R-001). A default in a committed properties file is exactly the thing that rule forbids, so an
 * {@code http} deployment that did not state these fails at startup naming the missing one. Only
 * the timeouts and the JWKS cache window carry defaults: omitting a timeout is the dangerous case,
 * not a wrong value.
 *
 * <p>The credentials live only in the gitignored local config (CLAUDE.md), never in the repository,
 * a log or a session report.
 *
 * @param authUrl the authorisation host, e.g. {@code https://auth.uqudo.io/api}. {@code
 *     /oauth/token} is appended.
 * @param apiBase the API host, e.g. {@code https://id.uqudo.io}. Portal-supplied per tenant.
 * @param jwksUrl the JWKS document, e.g. {@code https://id.uqudo.io/api/.well-known/jwks.json}.
 * @param clientId the tenant client id. Also the expected {@code aud} on every JWS — observed at
 *     S1-02 to be exactly the client id, which is why there is no separate audience property.
 * @param clientSecret the tenant client secret. Never logged, never echoed, never in a report.
 * @param issuer the expected {@code iss}. Observed {@code https://id.uqudo.io} at S1-02 on FIB's
 *     tenant — stated as configuration rather than defaulted for the same reason as the hosts.
 * @param connectTimeout TCP+TLS handshake budget. Default 5 s, matching the Civil Registry and core
 *     banking adapters.
 * @param readTimeout response budget once connected. Default 15 s. The image download is the
 *     largest body (a document JPEG); 15 s stays inside the mobile client's 30 s receive timeout
 *     ({@code mobile/lib/core/network/dio_provider.dart}) so the backend never outlives the
 *     customer's socket.
 * @param jwksCacheDuration how long a fetched JWKS is reused. Default 15 minutes, the vendor's own
 *     guidance (docs/components/uqudo-sdk.md "JWS verification"). Never per-request.
 */
@ConfigurationProperties(prefix = "fru.uqudo.http")
public record UqudoHttpProperties(
    URI authUrl,
    URI apiBase,
    URI jwksUrl,
    String clientId,
    String clientSecret,
    String issuer,
    Duration connectTimeout,
    Duration readTimeout,
    Duration jwksCacheDuration) {

  public UqudoHttpProperties {
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
    readTimeout = readTimeout == null ? Duration.ofSeconds(15) : readTimeout;
    jwksCacheDuration = jwksCacheDuration == null ? Duration.ofMinutes(15) : jwksCacheDuration;
  }

  /** {@code POST {authUrl}/oauth/token} — the client-credentials endpoint. */
  public URI tokenEndpoint() {
    return resolve(authUrl, "/oauth/token");
  }

  /** {@code GET {apiBase}/api/v1/info/img/{id}} — one image download. */
  public URI imageEndpoint(String imageId) {
    return resolve(apiBase, "/api/v1/info/img/" + imageId);
  }

  /** {@code DELETE {apiBase}/api/v1/info/{sessionId}} — the deliberate privacy purge (R-001). */
  public URI sessionEndpoint(String uqudoSessionId) {
    return resolve(apiBase, "/api/v1/info/" + uqudoSessionId);
  }

  /** {@code POST {apiBase}/api/v1/face} — Face Session creation. */
  public URI faceSessionEndpoint() {
    return resolve(apiBase, "/api/v1/face");
  }

  /**
   * Appends a path to a configured base. String concatenation, not {@link URI#resolve(String)}:
   * resolve() would discard a base that carries its own path (the auth host is {@code
   * https://auth.uqudo.io/api}, and resolving {@code /oauth/token} against it would drop {@code
   * /api} and call the wrong host silently).
   */
  private static URI resolve(URI base, String path) {
    String text = base.toString();
    return URI.create(
        text.endsWith("/") ? text.substring(0, text.length() - 1) + path : text + path);
  }
}
