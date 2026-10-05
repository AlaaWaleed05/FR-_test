package com.sfbank.bayanati.spike;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.net.URI;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Parses and RS256-verifies a Uqudo JWS against the live JWKS (fetched fresh on every call so key
 * rotation between T+0 and T+24h is observable), and produces the redacted <em>shape</em> of a
 * payload -- keys, JSON types and string lengths, with values kept only for a fixed whitelist of
 * non-identifying claims. The shape is the only artifact of the payload that may be quoted in a
 * committed report; the payload itself stays under {@code target/}.
 */
class UqudoSpikeJws {

  /** Claims whose values are structural, never identity data, and therefore kept in the shape. */
  private static final Set<String> VALUE_WHITELIST =
      Set.of(
          // NOT "aud": observed live at S1-02 to be the tenant client id, which CLAUDE.md forbids
          // in any log or report. It is redacted like every other identity-bearing string.
          "iss",
          "exp",
          "iat",
          "alg",
          "kid",
          "typ",
          "documentType",
          "match",
          "matchLevel",
          "mrzVerified",
          "sessionId",
          "nonce");

  record Verification(
      boolean signatureValid, String kid, boolean kidFoundInJwks, int jwksKeyCount, String error) {
    Map<String, Object> summary() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("signatureValid", signatureValid);
      m.put("kid", kid);
      m.put("kidFoundInJwks", kidFoundInJwks);
      m.put("jwksKeyCount", jwksKeyCount);
      m.put("verifyError", error);
      return m;
    }
  }

  /** One {@code *ImageId} reference found anywhere in a payload, with its checksum sibling. */
  record ImageRef(String path, String key, String imageId, String checksumKey, String checksum) {}

  private final String jwksUrl;

  UqudoSpikeJws(String jwksUrl) {
    this.jwksUrl = jwksUrl;
  }

  JWSObject parse(String jws) throws ParseException {
    return JWSObject.parse(jws);
  }

  Map<String, Object> header(JWSObject jws) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("alg", String.valueOf(jws.getHeader().getAlgorithm()));
    m.put("kid", jws.getHeader().getKeyID());
    m.put("typ", jws.getHeader().getType() == null ? null : jws.getHeader().getType().toString());
    return m;
  }

  JsonNode payload(JWSObject jws) {
    return UqudoSpikeStore.MAPPER.readTree(jws.getPayload().toString());
  }

  Verification verify(JWSObject jws) {
    String kid = jws.getHeader().getKeyID();
    try {
      JWKSet set = JWKSet.load(URI.create(jwksUrl).toURL());
      JWK key = kid == null ? null : set.getKeyByKeyId(kid);
      if (!(key instanceof RSAKey rsa)) {
        return new Verification(false, kid, false, set.size(), "kid not found or not RSA");
      }
      boolean ok = jws.verify(new RSASSAVerifier(rsa.toRSAPublicKey()));
      return new Verification(ok, kid, true, set.size(), null);
    } catch (IOException | ParseException | JOSEException e) {
      return new Verification(false, kid, false, -1, e.getClass().getSimpleName() + ": " + e);
    }
  }

  /** Redacted structure: keys and types survive, identity-bearing values do not. */
  Object shape(String key, JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isObject()) {
      Map<String, Object> m = new LinkedHashMap<>();
      node.properties().forEach(e -> m.put(e.getKey(), shape(e.getKey(), e.getValue())));
      return m;
    }
    if (node.isArray()) {
      List<Object> list = new ArrayList<>();
      for (JsonNode child : node) {
        list.add(shape(key, child));
      }
      return list;
    }
    if (node.isString()) {
      if (VALUE_WHITELIST.contains(key)) {
        return node.asString();
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("type", "string");
      m.put("length", node.asString().length());
      return m;
    }
    if (node.isNumber()) {
      return node.numberValue();
    }
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    return String.valueOf(node.getNodeType());
  }

  List<ImageRef> findImages(JsonNode root) {
    List<ImageRef> out = new ArrayList<>();
    walkImages("$", root, out);
    return out;
  }

  private void walkImages(String path, JsonNode node, List<ImageRef> out) {
    if (node.isObject()) {
      node.properties()
          .forEach(
              e -> {
                String k = e.getKey();
                JsonNode v = e.getValue();
                if (k.endsWith("ImageId") && v.isString()) {
                  String stem = k.substring(0, k.length() - "Id".length());
                  String checksumKey = null;
                  for (String candidate : List.of(k + "Checksum", stem + "Checksum")) {
                    if (node.hasNonNull(candidate)) {
                      checksumKey = candidate;
                      break;
                    }
                  }
                  out.add(
                      new ImageRef(
                          path + "." + k,
                          k,
                          v.asString(),
                          checksumKey,
                          checksumKey == null ? null : node.path(checksumKey).asString()));
                }
                walkImages(path + "." + k, v, out);
              });
    } else if (node.isArray()) {
      int i = 0;
      for (JsonNode child : node) {
        walkImages(path + "[" + i++ + "]", child, out);
      }
    }
  }

  /** String leaves over 1 KB -- the only way an inlined base64 image would show up. */
  List<Map<String, Object>> largeStrings(JsonNode root) {
    List<Map<String, Object>> out = new ArrayList<>();
    walkLarge("$", root, out);
    return out;
  }

  private void walkLarge(String path, JsonNode node, List<Map<String, Object>> out) {
    if (node.isObject()) {
      node.properties().forEach(e -> walkLarge(path + "." + e.getKey(), e.getValue(), out));
    } else if (node.isArray()) {
      int i = 0;
      for (JsonNode child : node) {
        walkLarge(path + "[" + i++ + "]", child, out);
      }
    } else if (node.isString() && node.asString().length() > 1024) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("path", path);
      m.put("length", node.asString().length());
      m.put("base64ImageMagic", base64ImageMagic(node.asString()));
      out.add(m);
    }
  }

  private static String base64ImageMagic(String s) {
    try {
      byte[] b = Base64.getDecoder().decode(s.substring(0, Math.min(s.length(), 64)));
      if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
        return "jpeg";
      }
      if (b.length >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
        return "png";
      }
      return "none";
    } catch (IllegalArgumentException e) {
      return "not-base64";
    }
  }

  /** The first {@code deviceAttestation} object anywhere in the payload, as a shape. */
  Object deviceAttestationShape(JsonNode root) {
    JsonNode found = find(root, "deviceAttestation");
    return found == null ? "absent" : shape("deviceAttestation", found);
  }

  static JsonNode find(JsonNode node, String key) {
    if (node == null) {
      return null;
    }
    if (node.isObject()) {
      if (node.has(key)) {
        return node.get(key);
      }
      for (Map.Entry<String, JsonNode> e : node.properties()) {
        JsonNode r = find(e.getValue(), key);
        if (r != null) {
          return r;
        }
      }
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        JsonNode r = find(child, key);
        if (r != null) {
          return r;
        }
      }
    }
    return null;
  }
}
