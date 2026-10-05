package com.sfbank.bayanati.spike;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/** Raw Uqudo HTTP calls. Every method returns the status rather than throwing on 4xx/5xx. */
class UqudoSpikeHttp {

  /** One image fetch, exactly what was observed. */
  record Fetched(
      int status, String contentType, int bytes, String sha256, String date, byte[] body) {
    Map<String, Object> summary() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("status", status);
      m.put("contentType", contentType);
      m.put("bytes", bytes);
      m.put("sha256", sha256);
      m.put("responseDate", date);
      return m;
    }
  }

  private final UqudoSpikeProperties properties;
  private final RestClient http;
  private final UqudoSpikeTokenSource tokens;

  UqudoSpikeHttp(UqudoSpikeProperties properties, RestClient http, UqudoSpikeTokenSource tokens) {
    this.properties = properties;
    this.http = http;
    this.tokens = tokens;
  }

  Fetched getImage(String imageId) {
    ResponseEntity<byte[]> r =
        http.get()
            .uri(properties.apiBase() + "/api/v1/info/img/" + imageId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.current().value())
            .retrieve()
            .onStatus(s -> true, (req, res) -> {})
            .toEntity(byte[].class);
    byte[] body = r.getBody() == null ? new byte[0] : r.getBody();
    return new Fetched(
        r.getStatusCode().value(),
        String.valueOf(r.getHeaders().getContentType()),
        body.length,
        body.length == 0 ? null : "sha256:" + sha256Hex(body),
        r.getHeaders().getFirst(HttpHeaders.DATE),
        body);
  }

  /**
   * {@code POST /api/v1/face} multipart {@code idPhoto}; returns status + raw body text. Built with
   * a plain {@link LinkedMultiValueMap} rather than {@code MultipartBodyBuilder}: the latter
   * references Reactive Streams, absent from a WebMVC-only classpath (found live at S1-02 -- {@code
   * ClassNotFoundException: org.reactivestreams.Publisher}).
   */
  Map<String, Object> createFaceSession(byte[] portrait) {
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add(
        "idPhoto",
        new ByteArrayResource(portrait) {
          @Override
          public String getFilename() {
            return "portrait.jpg";
          }
        });
    ResponseEntity<String> r =
        http.post()
            .uri(properties.apiBase() + "/api/v1/face")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.current().value())
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(form)
            .retrieve()
            .onStatus(s -> true, (req, res) -> {})
            .toEntity(String.class);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("status", r.getStatusCode().value());
    m.put("body", r.getBody());
    m.put("responseDate", r.getHeaders().getFirst(HttpHeaders.DATE));
    return m;
  }

  /** {@code DELETE /api/v1/info/{jti}}; returns status + body text. */
  Map<String, Object> purge(String jti) {
    ResponseEntity<String> r =
        http.delete()
            .uri(properties.apiBase() + "/api/v1/info/" + jti)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.current().value())
            .retrieve()
            .onStatus(s -> true, (req, res) -> {})
            .toEntity(String.class);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("status", r.getStatusCode().value());
    m.put("body", r.getBody());
    return m;
  }

  static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String sha256Hex(String s) {
    return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
  }
}
