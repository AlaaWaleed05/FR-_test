package com.sfbank.bayanati.uqudo.stub;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.util.Base64URL;
import com.sfbank.bayanati.uqudo.domain.ImageIntegrityException;
import com.sfbank.bayanati.uqudo.domain.ImageUnavailableException;
import com.sfbank.bayanati.uqudo.domain.IssuedAccessToken;
import com.sfbank.bayanati.uqudo.domain.JwsVerificationException;
import com.sfbank.bayanati.uqudo.domain.ParsedEnrolmentResult;
import com.sfbank.bayanati.uqudo.domain.ParsedFaceResult;
import com.sfbank.bayanati.uqudo.domain.ParsedIncompleteFaceResult;
import com.sfbank.bayanati.uqudo.domain.UqudoClient;
import com.sfbank.bayanati.uqudo.domain.UqudoJwsParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stands in for Uqudo eKYC. Selected by {@code fru.uqudo.client=stub}; the real client is {@code
 * uqudo.http.HttpUqudoClient}.
 *
 * <p><strong>The payload parsing is not here.</strong> It is {@link UqudoJwsParser}, in {@code
 * domain}, shared byte for byte with the real client — CLAUDE.md requires the parser stay
 * quarantined in a single class, and two clients that each knew the field names would be two. What
 * this class still owns is the one thing that genuinely differs: the signature. It builds and
 * verifies a genuine compact JWS with HS256 ({@code com.nimbusds:nimbus-jose-jwt}) against a fixed,
 * stub-only secret — never a credential, never configuration. The real one is RS256 against Uqudo's
 * JWKS, header {@code alg} + {@code kid}, no {@code typ}.
 *
 * <p><strong>The {@code fabricate*} methods, {@link #enrolmentClaims} and {@link #sign} are not
 * part of {@link UqudoClient}.</strong> In the real system the app + Uqudo's own SDK produce the
 * JWS on the device; nothing backend-side ever fabricates one. They stand in for that missing
 * device/SDK for test purposes only, and their values are synthetic — built from the redacted S1-02
 * shapes, never from a captured payload. They remain here rather than moving with the parser
 * precisely because they are not production code, and the shapes they build are documented on
 * {@link UqudoJwsParser}.
 */
public class StubUqudoClient implements UqudoClient {

  /** SDN_ID's older card version — no English name, no {@code bloodType} (uqudo-sdk.md). */
  public static final String CARD_VARIANT_PREVIOUS = UqudoJwsParser.CARD_VARIANT_PREVIOUS;

  /** SDN_ID's current card version — adds {@code bloodType} (front) and an English name (back). */
  public static final String CARD_VARIANT_LATEST = UqudoJwsParser.CARD_VARIANT_LATEST;

  public static final String DOCUMENT_TYPE_SDN_ID = UqudoJwsParser.DOCUMENT_TYPE_SDN_ID;
  public static final String DOCUMENT_TYPE_PASSPORT = UqudoJwsParser.DOCUMENT_TYPE_PASSPORT;

  /** Prefix that makes {@link #downloadImage} deterministically report the image as gone. */
  private static final String EXPIRED_IMAGE_PREFIX = "expired-";

  /** Observed at S1-02: enrolment JWS {@code exp - iat}. */
  static final long ENROLMENT_LIFETIME_SECONDS = 7200;

  /** Observed at S1-02: face-session JWS {@code exp - iat}. */
  static final long FACE_LIFETIME_SECONDS = 600;

  /**
   * Observed at S1-02: the tenant's {@code expires_in} on the SDK access token, {@code ~1859 s}
   * against a documented 1800. The SMALLER, documented figure is used here on purpose — a stub that
   * hands out a longer life than production would let a client-side freshness rule pass every test
   * and still be wrong against the real tenant.
   */
  static final long ACCESS_TOKEN_LIFETIME_SECONDS = 1800;

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final byte[] SIGNING_KEY = sha256("fru-user-update-stub-uqudo-key-not-a-secret");

  private static final String ISSUER = "uqudo-stub";
  private static final String AUDIENCE = "fru-user-update-stub";

  /** The shared parser, handed this class's HS256 check as its signature step. */
  private final UqudoJwsParser parser = new UqudoJwsParser(StubUqudoClient::verifiedPayload);

  /**
   * The stub's tokens carry the same {@code ACCESS_TOKEN_LIFETIME_SECONDS} the real tenant returns,
   * so a client-side freshness rule written against {@code expiresAt} behaves identically here and
   * in production. Without a real expiry the stub would let a reuse bug pass every test.
   */
  @Override
  public IssuedAccessToken issueAccessToken() {
    return new IssuedAccessToken(
        "stub-access-token-" + UUID.randomUUID(),
        Instant.now().plusSeconds(ACCESS_TOKEN_LIFETIME_SECONDS));
  }

  // ---- parsing: delegated, so the stub and the real client cannot drift apart ----

  @Override
  public ParsedEnrolmentResult verifyAndParse(
      String jws, String expectedSessionId, String expectedNonce, String expectedDocumentType) {
    return parser.parseEnrolment(jws, expectedSessionId, expectedNonce, expectedDocumentType);
  }

  @Override
  public ParsedFaceResult verifyAndParseFaceSession(String jws, String expectedFaceSessionId) {
    return parser.parseFaceSession(jws, expectedFaceSessionId);
  }

  @Override
  public ParsedIncompleteFaceResult verifyAndParseIncompleteFaceSession(
      String jws, String expectedFaceSessionId) {
    return parser.parseIncompleteFaceSession(jws, expectedFaceSessionId);
  }

  // ---- signature: the only step the real client does differently ----

  /**
   * HS256 against the fixed stub secret, then the payload as JSON. No {@code exp} check and no
   * claim reading happens here — both belong to {@link UqudoJwsParser}, which runs next.
   */
  private static JsonNode verifiedPayload(String jws) {
    JWSObject jwsObject;
    try {
      jwsObject = JWSObject.parse(jws);
      if (!jwsObject.verify(new MACVerifier(SIGNING_KEY))) {
        throw new JwsVerificationException("signature does not verify");
      }
    } catch (JwsVerificationException alreadyTyped) {
      throw alreadyTyped;
    } catch (Exception malformed) {
      // The exception's own message names a parsing problem, never a payload value.
      throw new JwsVerificationException("malformed JWS: " + malformed.getMessage());
    }

    try {
      return MAPPER.readTree(jwsObject.getPayload().toString());
    } catch (Exception notJson) {
      throw new JwsVerificationException("payload is not a JSON document");
    }
  }

  // ---- the other port methods ----

  @Override
  public byte[] downloadImage(String imageId, String expectedChecksum) {
    if (imageId.startsWith(EXPIRED_IMAGE_PREFIX)) {
      throw new ImageUnavailableException(imageId);
    }
    byte[] content = fakeImageBytes(imageId);
    String actualChecksum = "sha256:" + HexFormat.of().formatHex(sha256Bytes(content));
    if (!actualChecksum.equals(expectedChecksum)) {
      throw new ImageIntegrityException(imageId);
    }
    return content;
  }

  @Override
  public void purgeSession(String uqudoSessionId) {
    // No state to purge -- this stub never persists anything server-side beyond the compact JWS
    // string itself, which the caller already holds. A real implementation issues the DELETE here,
    // and gets 204 for any UUID (S1-02) -- so the caller's choice of id is the whole control.
  }

  @Override
  public String createFaceSession(byte[] referenceImageBytes) {
    if (referenceImageBytes == null || referenceImageBytes.length == 0) {
      throw new IllegalArgumentException("referenceImageBytes must not be empty");
    }
    // The stub never actually uploads anything -- Uqudo's own Face Session id is simulated as a
    // fresh UUID per call (the real one is a UUID too), matching "a new Face Session is required
    // every attempt" (the session and its image are deleted after 600s, uqudo-sdk.md).
    return UUID.randomUUID().toString();
  }

  // ---- stand-ins for the device + SDK (tests only) ----

  /**
   * Builds and signs a compact JWS in the real enrolment shape observed at S1-02, with synthetic
   * values.
   *
   * @param cardVariant {@link #CARD_VARIANT_PREVIOUS} or {@link #CARD_VARIANT_LATEST} for {@code
   *     SDN_ID}; ignored for {@code PASSPORT}
   * @param imagesExpired when {@code true}, every image id is prefixed so {@link #downloadImage}
   *     deterministically reports it gone — drives the "verified but images gone" ordering proof
   *     with no configuration wiring
   */
  public String fabricateJws(
      String documentType,
      String cardVariant,
      String sessionId,
      String nonce,
      String identityNumber,
      boolean imagesExpired) {
    return sign(
        enrolmentClaims(
            documentType, cardVariant, sessionId, nonce, identityNumber, imagesExpired, false));
  }

  /**
   * Identical to {@link #fabricateJws(String, String, String, String, String, boolean)} except the
   * JWS's own {@code exp} claim is already in the past — drives R-012/R-021's "ARTIFACT_EXPIRED
   * does not count against the retry budget" proof.
   */
  public String fabricateExpiredJws(
      String documentType,
      String cardVariant,
      String sessionId,
      String nonce,
      String identityNumber) {
    return sign(
        enrolmentClaims(documentType, cardVariant, sessionId, nonce, identityNumber, false, true));
  }

  /**
   * The unsigned enrolment claims, as mutable nested maps, so a test can bend one detail of the
   * shape (drop a field, rename the English-name key, add an unknown object) before {@link #sign}.
   * Structure mirrors the redacted S1-02 shape key for key; every value is synthetic.
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> enrolmentClaims(
      String documentType,
      String cardVariant,
      String sessionId,
      String nonce,
      String identityNumber,
      boolean imagesExpired,
      boolean alreadyExpired) {
    boolean isSdnId = DOCUMENT_TYPE_SDN_ID.equals(documentType);
    boolean latestCard = isSdnId && CARD_VARIANT_LATEST.equals(cardVariant);

    Map<String, Object> front = new LinkedHashMap<>();
    Map<String, Object> back = new LinkedHashMap<>();

    // The MRZ block: on the passport's only side; on the SDN_ID card's back (country tables).
    Map<String, Object> mrz = isSdnId ? back : front;
    mrz.put("secondaryId", "MOHAMMED");
    mrz.put("primaryId", "AL TAYEB");
    mrz.put("dateOfBirth", "900101"); // 6-character MRZ form, observed length 6
    mrz.put("dateOfBirthFormatted", "1990-01-01"); // observed length 10
    mrz.put("dateOfExpiry", "300101");
    mrz.put("dateOfExpiryFormatted", "2030-01-01");
    mrz.put("documentNumber", "MRZ-" + identityNumber);
    mrz.put("nationality", "SDN");
    mrz.put("issuer", "SDN");
    mrz.put("sex", "M");
    mrz.put("documentCode", isSdnId ? "ID" : "P<");
    mrz.put(
        "mrzText",
        "STUB<MRZ<TEXT<<NOT<A<REAL<DOCUMENT<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");
    mrz.put("mrzVerified", true);
    mrz.put("opt1", "");

    front.put("identityNumber", identityNumber);
    if (isSdnId) {
      front.put("name", "محمد الطيب"); // Arabic, front (synthetic sample)
      front.put("dateOfBirth", "01/01/1990"); // front OCR spelling, exercises the dd/MM fallback
      front.put("dateOfBirthFormatted", "1990-01-01");
      front.put("placeOfBirth", "الخرطوم");
      front.put("address", "الخرطوم - حي ستب");
      front.put("occupation", "موظف");
      if (latestCard) {
        front.put("bloodType", "O+");
        back.put("fullName", "MOHAMMED AL TAYEB"); // FIB's key; the docs say back.name
      }
      back.put("placeOfIssue", "الخرطوم");
      back.put("dateOfExpiryFull", "2030-01-01");
      back.put("dateOfExpiryFullFormatted", "2030-01-01");
      back.put("issueDate", "2020-01-01");
      back.put("issueDateFormatted", "2020-01-01");
      mrz.put("opt2", "");
    } else {
      front.put("fullName", "MOHAMMED AL TAYEB");
      front.put("fullNameArabic", "محمد الطيب");
      front.put("dateOfBirthFull", "1990-01-01");
      front.put("dateOfBirthFullFormatted", "1990-01-01");
      front.put("placeOfBirth", "KHARTOUM");
      front.put("issueDate", "2020-01-01");
      front.put("issueDateFormatted", "2020-01-01");
      front.put("dateOfExpiryFull", "2030-01-01");
      front.put("dateOfExpiryFullFormatted", "2030-01-01");
      front.put("passportNumber", "MRZ-" + identityNumber);
      front.put("passportCountryCode", "SDN");
      front.put("passportType", "P");
      front.put("placeOfIssue", "KHARTOUM");
      front.put("gender", "MALE");
      front.put("chipAvailable", true);
    }

    Map<String, Object> scan = new LinkedHashMap<>();
    scan.put("front", front);
    scan.put("back", back);
    putImage(scan, "faceImageId", imagesExpired);
    putImage(scan, "frontImageId", imagesExpired);
    putImage(scan, "frontFrameImageId", imagesExpired);
    if (isSdnId) {
      putImage(scan, "backImageId", imagesExpired);
      putImage(scan, "backFrameImageId", imagesExpired);
    } else {
      scan.put("backImageId", null);
      scan.put("backImageIdChecksum", null);
    }

    Map<String, Object> document = new LinkedHashMap<>();
    document.put("documentType", documentType);
    document.put("scan", scan);
    document.put("reading", null);
    document.put("face", null);
    document.put("lookup", null);

    Map<String, Object> verification = new LinkedHashMap<>();
    verification.put("documentType", documentType);
    verification.put("dataConsistencyCheck", Map.of("enabled", true, "fields", List.of()));
    Map<String, Object> sourceDetection = new LinkedHashMap<>();
    sourceDetection.put("enabled", true);
    sourceDetection.put("allowNonPhysicalDocuments", false);
    sourceDetection.put("selectedResolution", "1920x1080");
    sourceDetection.put("optimalResolution", true);
    verification.put("sourceDetection", sourceDetection);
    // Decimal scores, as observed (17.1 / 10.54 / 0.68 on the real passport) -- all well under
    // the documented rejection thresholds (50 / 50 / 70).
    verification.put("idScreenDetection", Map.of("enabled", true, "score", 17.1));
    verification.put("idPrintDetection", Map.of("enabled", true, "score", 10.54));
    verification.put("idPhotoTamperingDetection", Map.of("enabled", true, "score", 0.68));
    Map<String, Object> mrzChecksum = new LinkedHashMap<>();
    mrzChecksum.put("enabled", true);
    mrzChecksum.put("finalCheckDigit", "0");
    mrzChecksum.put("valid", true);
    mrzChecksum.put("checkDigits", List.of());
    verification.put("mrzChecksum", mrzChecksum);
    verification.put("reading", Map.of("enabled", false));
    verification.put("biometric", Map.of("enabled", false));
    verification.put("lookup", Map.of("enabled", false));

    Map<String, Object> source = new LinkedHashMap<>();
    source.put("sdkType", "MOBILE_SDK");
    source.put("sdkVersion", "3.10.0");
    source.put("sourceIp", "203.0.113.10");
    source.put("devicePlatform", "android");
    source.put("deviceVersion", "9");
    source.put("deviceManufacturer", "STUB");
    source.put("deviceModel", "STUB-01");

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("source", source);
    data.put("nonce", nonce);
    data.put("documents", new ArrayList<>(List.of(document)));
    data.put("verifications", new ArrayList<>(List.of(verification)));

    Instant now = Instant.now();
    Instant exp =
        alreadyExpired ? now.minusSeconds(60) : now.plusSeconds(ENROLMENT_LIFETIME_SECONDS);
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", ISSUER);
    claims.put("aud", AUDIENCE);
    claims.put("data", data);
    claims.put("exp", exp.getEpochSecond());
    claims.put("iat", now.getEpochSecond());
    claims.put("jti", sessionId); // enrolment: jti == our sessionId (observed)
    return claims;
  }

  private static void putImage(Map<String, Object> scan, String idField, boolean expired) {
    String id = (expired ? EXPIRED_IMAGE_PREFIX : "") + idField + "-" + UUID.randomUUID();
    String checksum = "sha256:" + HexFormat.of().formatHex(sha256Bytes(fakeImageBytes(id)));
    scan.put(idField, id);
    scan.put(idField + "Checksum", checksum);
  }

  /**
   * Builds and signs a compact JWS in the real face-session shape observed at S1-02 — stands in for
   * the app + Uqudo SDK's real faceSession() output, for test purposes only.
   *
   * @param sessionId Uqudo's own Face Session id (as {@link #createFaceSession} would have
   *     returned) — becomes {@code data.sessionId}; the {@code jti} is a fresh, different UUID
   * @param match customer.md Stage 10: a signed JWS with {@code match=false} is a SUCCESSFUL call,
   *     not a rejection — the strongest fraud signal the journey produces
   * @param matchLevel 1-5
   */
  public String fabricateFaceJws(
      String sessionId,
      boolean match,
      int matchLevel,
      String auditTrailImageId,
      String auditTrailImageIdChecksum) {
    return sign(
        faceClaims(
            sessionId,
            match,
            matchLevel,
            null,
            auditTrailImageId,
            auditTrailImageIdChecksum,
            false));
  }

  /**
   * Identical to {@link #fabricateFaceJws(String, boolean, int, String, String)} except the JWS's
   * own {@code exp} claim is already in the past — drives the "verified face session JWS but exp
   * has passed" proof, mirroring {@link #fabricateExpiredJws} for stage 8.
   */
  public String fabricateExpiredFaceJws(String sessionId, boolean match, int matchLevel) {
    String imageId = "auditTrailImageId-" + UUID.randomUUID();
    return sign(
        faceClaims(sessionId, match, matchLevel, null, imageId, checksumFor(imageId), true));
  }

  /**
   * A face-session JWS whose {@code auditTrailImageId} is prefixed so {@link #downloadImage}
   * deterministically reports it gone — mirrors {@link #fabricateJws}'s {@code imagesExpired}
   * parameter for the stage-10 "verified but the audit-trail image is unavailable" proof.
   */
  public String fabricateFaceJwsWithExpiredImage(String sessionId, boolean match, int matchLevel) {
    String imageId = EXPIRED_IMAGE_PREFIX + "auditTrailImageId-" + UUID.randomUUID();
    return fabricateFaceJws(sessionId, match, matchLevel, imageId, checksumFor(imageId));
  }

  /**
   * Convenience overload: auto-generates a fresh, checksum-matching {@code auditTrailImageId} — for
   * tests that only care about {@code match}/{@code matchLevel}.
   */
  public String fabricateFaceJws(String sessionId, boolean match, int matchLevel) {
    String imageId = "auditTrailImageId-" + UUID.randomUUID();
    return fabricateFaceJws(sessionId, match, matchLevel, imageId, checksumFor(imageId));
  }

  /**
   * A face-session JWS carrying a populated {@code face.error} — the undocumented vendor field seen
   * {@code null} on every real result; exists so the parser's tolerance of it is exercised.
   */
  public String fabricateFaceJwsWithError(
      String sessionId, boolean match, int matchLevel, String error) {
    String imageId = "auditTrailImageId-" + UUID.randomUUID();
    return sign(
        faceClaims(sessionId, match, matchLevel, error, imageId, checksumFor(imageId), false));
  }

  /**
   * BL-028: the partial artifact a terminated face session returns when {@code
   * returnDataForIncompleteSession()} is on. Uqudo says it is "the same JWS string" as a success;
   * whether {@code face} is present, or carries {@code match:false}, is {@code [UNVERIFIED]} — so
   * this fabricator omits {@code face} entirely when every face argument is {@code null}, and
   * otherwise includes only the non-null ones.
   */
  public String fabricateIncompleteFaceJws(
      String sessionId, Boolean match, Integer matchLevel, String error) {
    Map<String, Object> data = new LinkedHashMap<>();
    if (match != null || matchLevel != null || error != null) {
      Map<String, Object> face = new LinkedHashMap<>();
      if (match != null) {
        face.put("match", match);
      }
      if (matchLevel != null) {
        face.put("matchLevel", matchLevel);
      }
      face.put("error", error);
      face.put("falseAcceptRate", null);
      String imageId = "auditTrailImageId-" + UUID.randomUUID();
      face.put("auditTrailImageId", imageId);
      face.put("auditTrailImageIdChecksum", checksumFor(imageId));
      data.put("face", face);
    }
    data.put("sessionId", sessionId);
    return sign(faceEnvelope(data, false));
  }

  private Map<String, Object> faceClaims(
      String sessionId,
      boolean match,
      int matchLevel,
      String error,
      String auditTrailImageId,
      String auditTrailImageIdChecksum,
      boolean alreadyExpired) {
    Map<String, Object> face = new LinkedHashMap<>();
    face.put("match", match);
    face.put("matchLevel", matchLevel);
    face.put("error", error);
    face.put("falseAcceptRate", null); // undocumented vendor field, observed null
    face.put("auditTrailImageId", auditTrailImageId);
    face.put("auditTrailImageIdChecksum", auditTrailImageIdChecksum);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("face", face);
    data.put("sessionId", sessionId); // the binding (observed)
    return faceEnvelope(data, alreadyExpired);
  }

  private Map<String, Object> faceEnvelope(Map<String, Object> data, boolean alreadyExpired) {
    Instant now = Instant.now();
    Instant exp = alreadyExpired ? now.minusSeconds(60) : now.plusSeconds(FACE_LIFETIME_SECONDS);
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", ISSUER);
    claims.put("aud", AUDIENCE);
    claims.put("data", data);
    claims.put("exp", exp.getEpochSecond());
    claims.put("iat", now.getEpochSecond());
    claims.put("jti", UUID.randomUUID().toString()); // NOT the session id (observed)
    return claims;
  }

  /**
   * Takes a validly-signed face-session JWS and mutates its payload segment without re-signing — a
   * syntactically valid but signature-invalid compact JWS, mirroring {@link #fabricateTamperedJws}
   * for stage 10. The mutated claim is the binding, {@code data.sessionId}.
   */
  public String fabricateTamperedFaceJws(String sessionId) {
    String valid = fabricateFaceJws(sessionId, true, 5, "audit-1", "sha256:deadbeef");
    String[] parts = valid.split("\\.", 3);
    ObjectNode payload = (ObjectNode) MAPPER.readTree(new Base64URL(parts[1]).decodeToString());
    ((ObjectNode) payload.get("data")).put("sessionId", sessionId + "-tampered");
    return parts[0] + "." + reencode(payload) + "." + parts[2];
  }

  /**
   * Takes a validly-signed JWS and mutates its payload segment without re-signing — a syntactically
   * valid but signature-invalid compact JWS, driving customer.md Stage 8's "backend rejects the
   * JWS" proof.
   */
  public String fabricateTamperedJws(String documentType, String sessionId, String nonce) {
    String valid =
        fabricateJws(documentType, CARD_VARIANT_LATEST, sessionId, nonce, "TAMPER", false);
    String[] parts = valid.split("\\.", 3);
    ObjectNode payload = (ObjectNode) MAPPER.readTree(new Base64URL(parts[1]).decodeToString());
    payload.put("jti", sessionId + "-tampered");
    return parts[0] + "." + reencode(payload) + "." + parts[2];
  }

  private static String reencode(ObjectNode payload) {
    String json = MAPPER.writeValueAsString(payload);
    return Base64URL.encode(json.getBytes(StandardCharsets.UTF_8)).toString();
  }

  /** Signs a claims map with the stub key -- the seam {@link #enrolmentClaims} is built for. */
  public String sign(Map<String, Object> claims) {
    try {
      String json = MAPPER.writeValueAsString(claims);
      JWSObject jwsObject = new JWSObject(new JWSHeader(JWSAlgorithm.HS256), new Payload(json));
      jwsObject.sign(new MACSigner(SIGNING_KEY));
      return jwsObject.serialize();
    } catch (Exception signingFailed) {
      throw new IllegalStateException("stub failed to sign a fabricated JWS", signingFailed);
    }
  }

  private static String checksumFor(String imageId) {
    return "sha256:" + HexFormat.of().formatHex(sha256Bytes(fakeImageBytes(imageId)));
  }

  private static byte[] fakeImageBytes(String imageId) {
    return ("fake-image-bytes:" + imageId).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] sha256Bytes(byte[] content) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(content);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static byte[] sha256(String text) {
    return sha256Bytes(text.getBytes(StandardCharsets.UTF_8));
  }
}
