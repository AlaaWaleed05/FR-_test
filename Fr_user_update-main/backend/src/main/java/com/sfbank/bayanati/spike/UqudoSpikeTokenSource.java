package com.sfbank.bayanati.spike;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Mints Uqudo client-credential access tokens ({@code POST {authUrl}/oauth/token}, form-encoded)
 * and caches one until 60 s before expiry -- a T+2h recheck must not reuse a dead 1800 s token. The
 * token value is never logged; only its length, expiry and mint latency are.
 */
class UqudoSpikeTokenSource {

  record Token(String value, Instant mintedAt, long expiresIn, String jti, long latencyMs) {
    Instant expiresAt() {
      return mintedAt.plusSeconds(expiresIn);
    }
  }

  private final UqudoSpikeProperties properties;
  private final RestClient http;
  private final UqudoSpikeStore store;
  private Token cached;

  UqudoSpikeTokenSource(UqudoSpikeProperties properties, RestClient http, UqudoSpikeStore store) {
    this.properties = properties;
    this.http = http;
    this.store = store;
  }

  synchronized Token current() {
    if (cached == null || Instant.now().isAfter(cached.expiresAt().minus(Duration.ofSeconds(60)))) {
      cached = mint();
    }
    return cached;
  }

  synchronized Token mint() {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", properties.clientId());
    form.add("client_secret", properties.clientSecret());
    long started = System.nanoTime();
    JsonNode body =
        http.post()
            .uri(properties.authUrl() + "/oauth/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(JsonNode.class);
    long latencyMs = (System.nanoTime() - started) / 1_000_000;
    Token token =
        new Token(
            body.path("access_token").asString(),
            Instant.now(),
            body.path("expires_in").asLong(),
            body.path("jti").asString(null),
            latencyMs);
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("expiresIn", token.expiresIn());
    fields.put("tokenType", body.path("token_type").asString(null));
    fields.put("scope", body.path("scope").asString(null));
    fields.put("tokenLength", token.value().length());
    fields.put("latencyMs", latencyMs);
    store.log("token", null, fields);
    cached = token;
    return token;
  }
}
