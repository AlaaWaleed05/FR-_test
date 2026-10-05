package com.sfbank.bayanati.spike;

import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * S1-02 spike endpoints. Every call appends what it observed to {@code spike.log}; raw material
 * (JWS, payloads, images) goes to per-session files under {@code target/uqudo-spike/}. See
 * docs/sessions/2026-09-03-s1-02-device-spike.md for what each measurement reads from here.
 */
@RestController
@Profile("uqudo-spike")
@RequestMapping("/api/v1/spike/uqudo")
class UqudoSpikeController {

  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final UqudoSpikeStore store;
  private final UqudoSpikeTokenSource tokens;
  private final UqudoSpikeHttp http;
  private final UqudoSpikeJws jws;
  private final UqudoSpikeProperties properties;

  UqudoSpikeController(
      UqudoSpikeStore store,
      UqudoSpikeTokenSource tokens,
      UqudoSpikeHttp http,
      UqudoSpikeJws jws,
      UqudoSpikeProperties properties) {
    this.store = store;
    this.tokens = tokens;
    this.http = http;
    this.jws = jws;
    this.properties = properties;
  }

  @GetMapping("/ping")
  Map<String, Object> ping(@RequestParam(defaultValue = "false") boolean mint) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ok", true);
    out.put("serverTime", Instant.now().toString());
    if (mint) {
      UqudoSpikeTokenSource.Token t = tokens.mint();
      out.put("tokenMinted", true);
      out.put("expiresIn", t.expiresIn());
      out.put("tokenLength", t.value().length());
      out.put("mintLatencyMs", t.latencyMs());
      try {
        JWKSet set = JWKSet.load(URI.create(properties.jwksUrl()).toURL());
        out.put("jwksKeyCount", set.size());
        out.put("kids", set.getKeys().stream().map(JWK::getKeyID).toList());
      } catch (Exception e) {
        out.put("jwksError", e.toString());
      }
    }
    store.log("ping", null, out);
    return out;
  }

  @PostMapping("/session")
  Map<String, Object> session(@RequestBody JsonNode body) {
    String sessionId = UUID.randomUUID().toString();
    byte[] nonceBytes = new byte[16];
    RANDOM.nextBytes(nonceBytes);
    String nonce = HexFormat.of().formatHex(nonceBytes);
    UqudoSpikeTokenSource.Token t = tokens.mint();
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("sessionId", sessionId);
    meta.put("nonce", nonce);
    meta.put("documentType", body.path("documentType").asString(null));
    meta.put("tokenMintedAt", t.mintedAt().toString());
    meta.put("tokenExpiresIn", t.expiresIn());
    meta.put("createdAt", Instant.now().toString());
    store.writeJson(store.sessionDir(sessionId).resolve("session.json"), meta);
    store.log("session", sessionId, meta);
    Map<String, Object> out = new LinkedHashMap<>(meta);
    out.put("accessToken", t.value());
    return out;
  }

  @PostMapping("/enrolment")
  Map<String, Object> enrolment(@RequestBody JsonNode body) throws ParseException {
    String sessionId = body.path("sessionId").asString();
    Path dir = store.sessionDir(sessionId);
    JsonNode meta = store.readJson(dir.resolve("session.json")).orElseThrow();
    String raw = body.path("jws").asString();
    store.write(dir.resolve("enrolment.jws"), raw);
    Map<String, Object> out = analyse("enrolment", sessionId, dir, raw, body, meta);
    // Images: download every reference now (T+0), keep the portrait for the face session.
    JsonNode payload = jws.payload(jws.parse(raw));
    List<Map<String, Object>> images = new ArrayList<>();
    boolean portraitStored = false;
    for (UqudoSpikeJws.ImageRef ref : jws.findImages(payload)) {
      UqudoSpikeHttp.Fetched f = http.getImage(ref.imageId());
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("path", ref.path());
      m.put("key", ref.key());
      m.put("checksumKeyName", ref.checksumKey());
      m.putAll(f.summary());
      m.put("checksumMatches", ref.checksum() != null && ref.checksum().equals(f.sha256()));
      if (f.status() == 200 && f.bytes() > 0) {
        store.writeBytes(dir.resolve(ref.key() + ".jpg"), f.body());
        if ("faceImageId".equals(ref.key())) {
          store.writeBytes(dir.resolve("portrait.jpg"), f.body());
          portraitStored = true;
        }
      }
      images.add(m);
    }
    out.put("images", images);
    out.put("portraitStored", portraitStored);
    out.put(
        "faceImageIdPresent", images.stream().anyMatch(i -> "faceImageId".equals(i.get("key"))));
    // Remember the jti for purge/recheck lookups.
    Map<String, Object> updated = new LinkedHashMap<>();
    meta.properties()
        .forEach(
            e ->
                updated.put(
                    e.getKey(), UqudoSpikeStore.MAPPER.convertValue(e.getValue(), Object.class)));
    updated.put("jti", out.get("jti"));
    updated.put("iat", out.get("iat"));
    updated.put("images", images);
    store.writeJson(dir.resolve("session.json"), updated);
    store.log("enrolment", sessionId, out);
    if (!portraitStored) {
      out.put(
          "warning",
          "PORTRAIT NOT STORED -- retry POST /enrolment/{sessionId}/portrait inside the 30-minute window");
    }
    return out;
  }

  @PostMapping("/enrolment/{sessionId}/portrait")
  Map<String, Object> portraitRetry(@PathVariable String sessionId) throws ParseException {
    Path dir = store.sessionDir(sessionId);
    String raw = store.read(dir.resolve("enrolment.jws")).orElseThrow();
    Map<String, Object> out = new LinkedHashMap<>();
    for (UqudoSpikeJws.ImageRef ref : jws.findImages(jws.payload(jws.parse(raw)))) {
      if ("faceImageId".equals(ref.key())) {
        UqudoSpikeHttp.Fetched f = http.getImage(ref.imageId());
        out.putAll(f.summary());
        if (f.status() == 200 && f.bytes() > 0) {
          store.writeBytes(dir.resolve("portrait.jpg"), f.body());
          out.put("portraitStored", true);
        }
      }
    }
    store.log("portrait-retry", sessionId, out);
    return out;
  }

  @PostMapping("/face-session")
  ResponseEntity<Map<String, Object>> faceSession(@RequestBody JsonNode body) {
    String enrolmentSessionId = body.path("enrolmentSessionId").asString();
    boolean withNonce = body.path("withNonce").asBoolean(false);
    Path dir = store.sessionDir(enrolmentSessionId);
    byte[] portrait = store.readBytes(dir.resolve("portrait.jpg")).orElse(null);
    Map<String, Object> out = new LinkedHashMap<>();
    if (portrait == null) {
      out.put("error", "no stored portrait for " + enrolmentSessionId);
      store.log("face-session", enrolmentSessionId, out);
      return ResponseEntity.status(409).body(out);
    }
    UqudoSpikeTokenSource.Token t = tokens.mint();
    Map<String, Object> created = http.createFaceSession(portrait);
    out.put("enrolmentSessionId", enrolmentSessionId);
    out.put("uqudoStatus", created.get("status"));
    out.put("uqudoBody", created.get("body"));
    out.put("faceSessionCreatedAt", Instant.now().toString());
    out.put("portraitBytes", portrait.length);
    out.put("tokenMintedAt", t.mintedAt().toString());
    String faceSessionId = null;
    try {
      faceSessionId =
          UqudoSpikeStore.MAPPER
              .readTree(String.valueOf(created.get("body")))
              .path("sessionId")
              .asString(null);
    } catch (RuntimeException ignored) {
      // body was not JSON; status/body already recorded
    }
    out.put("faceSessionId", faceSessionId);
    String nonce = null;
    if (withNonce) {
      byte[] nonceBytes = new byte[16];
      RANDOM.nextBytes(nonceBytes);
      nonce = HexFormat.of().formatHex(nonceBytes);
    }
    out.put("nonce", nonce);
    if (faceSessionId != null) {
      store.writeJson(store.faceDir(faceSessionId).resolve("session.json"), out);
    }
    store.log("face-session", enrolmentSessionId, out);
    Map<String, Object> resp = new LinkedHashMap<>(out);
    resp.put("accessToken", t.value());
    return ResponseEntity.ok(resp);
  }

  @PostMapping("/face-result")
  Map<String, Object> faceResult(@RequestBody JsonNode body) throws ParseException {
    String faceSessionId = body.path("faceSessionId").asString();
    Path dir = store.faceDir(faceSessionId);
    JsonNode meta =
        store
            .readJson(dir.resolve("session.json"))
            .orElse(UqudoSpikeStore.MAPPER.createObjectNode());
    String raw = body.path("jws").asString();
    store.write(dir.resolve("face.jws"), raw);
    Map<String, Object> out = analyse("face-result", faceSessionId, dir, raw, body, meta);
    JsonNode payload = jws.payload(jws.parse(raw));
    JsonNode face = UqudoSpikeJws.find(payload, "face");
    out.put("facePresent", face != null);
    out.put("match", face == null ? null : face.path("match").asBoolean());
    out.put("matchLevel", face == null ? null : face.path("matchLevel").asInt());
    out.put("jtiEqualsFaceSessionId", faceSessionId.equals(out.get("jti")));
    out.put("runLabel", body.path("runLabel").asString(null));
    out.put("minimumMatchLevelSet", body.path("minimumMatchLevelSet").asInt(-1));
    out.put("maxAttempts", body.path("maxAttempts").asInt(-1));
    String createdAt = meta.path("faceSessionCreatedAt").asString(null);
    out.put("faceSessionCreatedAt", createdAt);
    List<Map<String, Object>> images = new ArrayList<>();
    for (UqudoSpikeJws.ImageRef ref : jws.findImages(payload)) {
      UqudoSpikeHttp.Fetched f = http.getImage(ref.imageId());
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("path", ref.path());
      m.put("key", ref.key());
      m.put("checksumKeyName", ref.checksumKey());
      m.putAll(f.summary());
      m.put("checksumMatches", ref.checksum() != null && ref.checksum().equals(f.sha256()));
      if (f.status() == 200 && f.bytes() > 0) {
        store.writeBytes(dir.resolve(ref.key() + ".jpg"), f.body());
      }
      images.add(m);
    }
    out.put("images", images);
    Map<String, Object> updated = new LinkedHashMap<>();
    meta.properties()
        .forEach(
            e ->
                updated.put(
                    e.getKey(), UqudoSpikeStore.MAPPER.convertValue(e.getValue(), Object.class)));
    updated.put("jti", out.get("jti"));
    updated.put("iat", out.get("iat"));
    updated.put("images", images);
    store.writeJson(dir.resolve("session.json"), updated);
    store.log("face-result", faceSessionId, out);
    return out;
  }

  @PostMapping("/error")
  Map<String, Object> error(@RequestBody JsonNode body) {
    String sessionId = body.path("sessionId").asString(null);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("context", body.path("context").asString(null));
    out.put("runLabel", body.path("runLabel").asString(null));
    out.put("minimumMatchLevelSet", body.path("minimumMatchLevelSet").asInt(-1));
    out.put("elapsedMs", body.path("elapsedMs").asLong(-1));
    out.put("tapAt", body.path("tapAt").asLong(-1));
    out.put("returnAt", body.path("returnAt").asLong(-1));
    JsonNode code = body.path("platformExceptionCode");
    JsonNode parsed = null;
    if (code.isString()) {
      try {
        parsed = UqudoSpikeStore.MAPPER.readTree(code.asString());
      } catch (RuntimeException e) {
        out.put("codeRaw", code.asString());
      }
    } else if (code.isObject()) {
      parsed = code;
    }
    if (parsed != null) {
      out.put("code", parsed.path("code").asString(null));
      out.put("task", parsed.path("task").asString(null));
      out.put("message", parsed.path("message").asString(null));
      JsonNode data = parsed.path("data");
      out.put("dataPresent", !data.isMissingNode() && !data.isNull());
      out.put("dataLength", data.isString() ? data.asString().length() : null);
      out.put("dataShape", jws.shape("data", data));
      if (sessionId != null) {
        store.writeJson(
            store.sessionDir(sessionId).resolve("error-" + Instant.now().toEpochMilli() + ".json"),
            UqudoSpikeStore.MAPPER.convertValue(parsed, Object.class));
      }
    }
    JsonNode trace = body.path("traceEvents");
    out.put("traceEventCount", trace.isArray() ? trace.size() : 0);
    out.put("traceEvents", UqudoSpikeStore.MAPPER.convertValue(trace, Object.class));
    store.log("error", sessionId, out);
    return out;
  }

  @PostMapping("/client-log")
  Map<String, Object> clientLog(@RequestBody JsonNode body) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("clientTs", body.path("ts").asString(null));
    out.put("line", body.path("line").asString(null));
    store.log("client-log", body.path("sessionId").asString(null), out);
    return Map.of("ok", true);
  }

  @PostMapping("/recheck/{sessionId}")
  Map<String, Object> recheck(
      @PathVariable String sessionId, @RequestParam(defaultValue = "enrolment") String kind)
      throws ParseException {
    Path dir = "face".equals(kind) ? store.faceDir(sessionId) : store.sessionDir(sessionId);
    String raw =
        store.read(dir.resolve("face".equals(kind) ? "face.jws" : "enrolment.jws")).orElseThrow();
    JWSObject parsed = jws.parse(raw);
    JsonNode payload = jws.payload(parsed);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("kind", kind);
    out.put("now", Instant.now().toString());
    long iat = payload.path("iat").asLong(0);
    long exp = payload.path("exp").asLong(0);
    out.put("iat", iat);
    out.put("exp", exp);
    out.put("minutesSinceIat", iat == 0 ? null : (Instant.now().getEpochSecond() - iat) / 60.0);
    out.put("expExpired", exp != 0 && Instant.now().getEpochSecond() > exp);
    out.putAll(jws.verify(parsed).summary());
    List<Map<String, Object>> images = new ArrayList<>();
    for (UqudoSpikeJws.ImageRef ref : jws.findImages(payload)) {
      UqudoSpikeHttp.Fetched f = http.getImage(ref.imageId());
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("key", ref.key());
      m.putAll(f.summary());
      m.put("checksumMatches", ref.checksum() != null && ref.checksum().equals(f.sha256()));
      images.add(m);
    }
    out.put("images", images);
    store.log("recheck", sessionId, out);
    return out;
  }

  @DeleteMapping("/purge/{jti}")
  Map<String, Object> purge(@PathVariable String jti) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("jti", jti);
    out.put("jtiIsUuid", UUID_PATTERN.matcher(jti).matches());
    Map<String, Object> result = http.purge(jti);
    out.put("uqudoStatus", result.get("status"));
    out.put("uqudoBody", result.get("body"));
    Optional<Path> dir = store.findByJti(jti);
    out.put("knownSession", dir.map(Path::getFileName).map(Object::toString).orElse(null));
    if (dir.isPresent()) {
      JsonNode meta = store.readJson(dir.get().resolve("session.json")).orElseThrow();
      long iat = meta.path("iat").asLong(0);
      out.put("minutesSinceIat", iat == 0 ? null : (Instant.now().getEpochSecond() - iat) / 60.0);
      List<Map<String, Object>> after = new ArrayList<>();
      for (JsonNode img : meta.path("images")) {
        String key = img.path("key").asString(null);
        String path = img.path("path").asString("");
        String id = imageIdFor(dir.get(), key, path);
        if (id != null) {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("key", key);
          m.putAll(http.getImage(id).summary());
          after.add(m);
        }
      }
      out.put("imagesAfterPurge", after);
    }
    store.log("purge", jti, out);
    return out;
  }

  private String imageIdFor(Path dir, String key, String path) {
    try {
      String raw =
          store
              .read(dir.resolve("enrolment.jws"))
              .or(() -> store.read(dir.resolve("face.jws")))
              .orElse(null);
      if (raw == null || key == null) {
        return null;
      }
      for (UqudoSpikeJws.ImageRef ref : jws.findImages(jws.payload(jws.parse(raw)))) {
        if (ref.key().equals(key) && ref.path().equals(path)) {
          return ref.imageId();
        }
      }
    } catch (ParseException ignored) {
      // fall through
    }
    return null;
  }

  /** Common JWS analysis for enrolment and face results: header, verification, claims, shape. */
  private Map<String, Object> analyse(
      String endpoint, String sessionId, Path dir, String raw, JsonNode body, JsonNode meta)
      throws ParseException {
    JWSObject parsed = jws.parse(raw);
    JsonNode payload = jws.payload(parsed);
    store.write(dir.resolve(endpoint + ".payload.json"), payload.toPrettyString());
    Object shape = jws.shape("$", payload);
    store.writeJson(dir.resolve(endpoint + ".shape.json"), shape);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("receivedAt", Instant.now().toString());
    out.put("jwsLength", raw.length());
    out.put("header", jws.header(parsed));
    out.putAll(jws.verify(parsed).summary());
    String jti = payload.path("jti").asString(null);
    long iat = payload.path("iat").asLong(0);
    long exp = payload.path("exp").asLong(0);
    out.put("iss", payload.path("iss").asString(null));
    // aud is the tenant client id (observed live) -- length only, never the value (CLAUDE.md).
    out.put(
        "audLength",
        payload.path("aud").isString() ? payload.path("aud").asString().length() : null);
    out.put("jti", jti);
    out.put("jtiIsUuid", jti != null && UUID_PATTERN.matcher(jti).matches());
    out.put("jtiEqualsSessionId", sessionId.equals(jti));
    out.put("iat", iat == 0 ? null : iat);
    out.put("exp", exp == 0 ? null : exp);
    out.put("hasExp", payload.has("exp"));
    out.put("expMinusIat", (iat == 0 || exp == 0) ? null : exp - iat);
    long tapAt = body.path("tapAt").asLong(0);
    out.put("tapAt", tapAt == 0 ? null : tapAt);
    out.put("firstTraceAt", body.path("firstTraceAt").asLong(0));
    out.put("returnAt", body.path("returnAt").asLong(0));
    out.put("elapsedMs", body.path("elapsedMs").asLong(-1));
    out.put("iatMinusTapAtSeconds", (iat == 0 || tapAt == 0) ? null : iat - tapAt / 1000);
    out.put("receivedAtMinusIatSeconds", iat == 0 ? null : Instant.now().getEpochSecond() - iat);
    String expectedNonce =
        body.path("expectedNonce").isString()
            ? body.path("expectedNonce").asString()
            : meta.path("nonce").asString(null);
    JsonNode nonceNode = UqudoSpikeJws.find(payload, "nonce");
    out.put("nonceExpected", expectedNonce != null);
    out.put(
        "nonceEchoed",
        expectedNonce != null && nonceNode != null && expectedNonce.equals(nonceNode.asString()));
    out.put("documentTypeClaim", payload.path("documentType").asString(null));
    out.put("dataKeys", keysOf(payload.path("data")));
    out.put("topLevelKeys", keysOf(payload));
    out.put("largeStrings", jws.largeStrings(payload));
    out.put("deviceAttestationShape", jws.deviceAttestationShape(payload));
    JsonNode trace = body.path("traceEvents");
    out.put("traceEventCount", trace.isArray() ? trace.size() : 0);
    out.put("traceEvents", UqudoSpikeStore.MAPPER.convertValue(trace, Object.class));
    return out;
  }

  private static List<String> keysOf(JsonNode node) {
    List<String> keys = new ArrayList<>();
    if (node != null && node.isObject()) {
      node.properties().forEach(e -> keys.add(e.getKey()));
    }
    return keys;
  }
}
