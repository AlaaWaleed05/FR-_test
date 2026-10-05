package com.sfbank.bayanati.uqudo.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * <strong>The quarantined parser</strong> (R-034, CLAUDE.md): the only place in the codebase that
 * knows a Uqudo JWS payload's field names. Everything downstream depends only on {@link
 * ParsedEnrolmentResult}/{@link ParsedFaceResult}/{@link ParsedIncompleteFaceResult}.
 *
 * <p>Signature checking is deliberately NOT here — it is the one thing that differs between the
 * stub (HS256, fixed secret) and the real client (RS256 against Uqudo's JWKS), and it arrives as a
 * {@link JwsSignatureVerifier}. Everything after the signature is identical for both, so both share
 * this class and neither repeats a field name. Extracted from {@code StubUqudoClient} without
 * behaviour change when the real client was built; {@code StubUqudoClientTest} is the regression
 * proof of that extraction.
 *
 * <p><strong>Written at R-034 (2026-09-04) against the real JWS shapes S1-02 logged</strong> on
 * FIB's tenant (docs/sessions/2026-09-03-s1-02-device-spike.md "Redacted shapes",
 * docs/components/uqudo-sdk.md "S1-02 observed") — the S3-12/S3-13 version had been written from
 * the field pages alone and was wrong in every structural assumption it marked {@code
 * [UNVERIFIED]}. What the real payloads settled, and this class encodes:
 *
 * <ul>
 *   <li>Enrolment: {@code {iss, aud, iat, exp = iat+7200, jti, data}}; {@code jti} equals the
 *       session id the backend minted; {@code data = {source, nonce, documents[],
 *       verifications[]}}. There is no top-level {@code documentType} — it is {@code
 *       documents[i].documentType}.
 *   <li>{@code documents[i].scan} carries the OCR fields under {@code front}/{@code back} per the
 *       country field tables, not in a flat {@code scan} object, alongside the image id and
 *       checksum keys; checksum keys are {@code <imageKey>Checksum}; images are references, never
 *       base64 ({@code backImageId: null} for a passport).
 *   <li>Anti-spoof scores are decimals in {@code verifications[i].idScreenDetection.score} / {@code
 *       idPrintDetection.score} / {@code idPhotoTamperingDetection.score}, not inside {@code scan}.
 *   <li>Dates come in several spellings per key ({@code dateOfBirth} is the 6-character MRZ form,
 *       {@code dateOfBirthFormatted}/{@code dateOfBirthFull}/{@code dateOfBirthFullFormatted} are
 *       10 characters; the exact separator is not known from the redacted shape) — parsed through
 *       an ordered fallback that never throws, because a date gates nothing (customer.md Stage 9)
 *       and the S3-12 {@code LocalDate.parse} would have been an uncaught 500 on a real JWS.
 *   <li>Face session: {@code {iss, aud, iat, exp = iat+600, jti, data: {face, sessionId[, nonce]}}}
 *       — <strong>the binding is {@code data.sessionId}; {@code jti} is a different UUID.</strong>
 *       {@code face = {match, matchLevel, error, falseAcceptRate, auditTrailImageId,
 *       auditTrailImageIdChecksum}}; {@code error}/{@code falseAcceptRate} are undocumented vendor
 *       fields, read defensively and never required.
 *   <li>{@code data.source} (device info) and {@code deviceAttestation} (absent from every real JWS
 *       on this tenant — BL-027) are tolerated present or absent and never read.
 *   <li>{@code aud} is the tenant client id — identity-bearing. This class never reads it and no
 *       exception message ever carries a payload value, only a field name. ({@code iss}, {@code
 *       aud} and {@code iat} ARE checked for a real JWS — by the RS256 verifier, before this class
 *       sees the payload, since only the real client has a configured tenant to check against.)
 * </ul>
 *
 * <p>SDN_ID was not scanned at S1-02 (no card); its parsing follows the country field tables
 * (front/back placement) and FIB's production web integration, per the session report's
 * evidence-based closure: the English name is {@code back.fullName} (FIB) or {@code back.name}
 * (docs) — both accepted; {@code nameEn} does not exist; the card version is inferred from {@code
 * front.bloodType}; {@code identityNumber} fails closed.
 */
public class UqudoJwsParser {

  /** SDN_ID's older card version — no English name, no {@code bloodType} (uqudo-sdk.md). */
  public static final String CARD_VARIANT_PREVIOUS = "previous";

  /** SDN_ID's current card version — adds {@code bloodType} (front) and an English name (back). */
  public static final String CARD_VARIANT_LATEST = "latest";

  public static final String DOCUMENT_TYPE_SDN_ID = "SDN_ID";
  public static final String DOCUMENT_TYPE_PASSPORT = "PASSPORT";

  /**
   * The 10-character spellings a real payload might use — the redacted shape gives lengths only, so
   * every plausible separator order is tried. ISO first: it is what {@code *Formatted}/{@code
   * *Full} most plausibly are, and the only one that round-trips unambiguously.
   */
  private static final List<DateTimeFormatter> FULL_YEAR_PATTERNS =
      List.of(
          DateTimeFormatter.ISO_LOCAL_DATE,
          DateTimeFormatter.ofPattern("dd/MM/uuuu"),
          DateTimeFormatter.ofPattern("dd-MM-uuuu"),
          DateTimeFormatter.ofPattern("uuuu/MM/dd"),
          DateTimeFormatter.ofPattern("uuuuMMdd"));

  private final JwsSignatureVerifier signatureVerifier;
  private final Clock clock;

  public UqudoJwsParser(JwsSignatureVerifier signatureVerifier) {
    this(signatureVerifier, Clock.systemUTC());
  }

  /**
   * The clock is injected because this is a {@code domain} package: CLAUDE.md's architecture rule
   * says a plain JUnit test must be able to exercise it with no Spring context, no database, no
   * network and no clock. Two things here read time — the {@code exp} check, and the window a
   * 6-character MRZ two-digit year resolves into — and both are behaviour worth being able to pin
   * in a test rather than wait for.
   */
  public UqudoJwsParser(JwsSignatureVerifier signatureVerifier, Clock clock) {
    this.signatureVerifier = signatureVerifier;
    this.clock = clock;
  }

  // ---- enrolment ----

  /** Backs {@link UqudoClient#verifyAndParse}; see that method for the contract. */
  public ParsedEnrolmentResult parseEnrolment(
      String jws, String expectedSessionId, String expectedNonce, String expectedDocumentType) {
    JsonNode root = verifiedPayload(jws);

    // Observed at S1-02: jti == the sessionId we minted (jtiEqualsSessionId: true).
    String jti = requiredText(root, "jti");
    if (!jti.equals(expectedSessionId)) {
      throw new JwsVerificationException("jti does not match the session id this attempt issued");
    }
    JsonNode data = root.path("data");
    String nonce = optionalText(data, "nonce");
    if (nonce == null || !nonce.equals(expectedNonce)) {
      throw new JwsVerificationException("nonce does not match what this attempt issued");
    }

    JsonNode documents = data.path("documents");
    if (!documents.isArray() || documents.isEmpty()) {
      throw new JwsVerificationException("payload carries no documents");
    }
    JsonNode document = documents.get(0);
    // No top-level documentType exists on a real JWS; it lives on the document entry.
    String documentType = requiredText(document, "documentType");
    if (!documentType.equals(expectedDocumentType)) {
      throw new JwsVerificationException("documentType does not match what was requested");
    }

    JsonNode scan = document.path("scan");
    JsonNode front = scan.path("front");
    JsonNode back = scan.path("back");
    boolean isSdnId = DOCUMENT_TYPE_SDN_ID.equals(documentType);

    // The Civil Registry key -- fail closed. A side mix-up cannot silently substitute
    // documentNumber (different side, different name), so the failure mode is a missing key, never
    // a wrong lookup (uqudo-sdk.md "SDN_ID closed by evidence").
    String identityNumber = firstText(front, back, "identityNumber");
    if (identityNumber == null || identityNumber.isBlank()) {
      throw new JwsVerificationException("payload is missing required field 'identityNumber'");
    }

    // Names: passport carries fullName (English) + fullNameArabic on the front; SDN_ID carries
    // name (Arabic) on the front and, on the latest card only, the English name on the back --
    // FIB's production web app reads back.fullName, the docs say back.name: accept either.
    String nameAr = isSdnId ? optionalText(front, "name") : optionalText(front, "fullNameArabic");
    String nameEn =
        isSdnId
            ? firstNonNull(optionalText(back, "fullName"), optionalText(back, "name"))
            : optionalText(front, "fullName");
    // Card-version discriminator: front.bloodType (latest card only). Inferred, never a fact.
    String bloodType = optionalText(front, "bloodType");
    String cardVariant =
        isSdnId ? (bloodType != null ? CARD_VARIANT_LATEST : CARD_VARIANT_PREVIOUS) : null;

    JsonNode verification = verificationFor(data, documentType);

    return new ParsedEnrolmentResult(
        jti,
        documentType,
        cardVariant,
        identityNumber,
        firstText(front, back, "documentNumber"),
        firstBoolean(front, back, "mrzVerified"),
        firstText(front, back, "nationality"),
        firstText(front, back, "sex"),
        date(front, back, "dateOfBirth", false),
        firstNonNull(
            date(front, back, "issueDate", false), date(front, back, "dateOfIssue", false)),
        date(front, back, "dateOfExpiry", true),
        firstText(front, back, "placeOfIssue"),
        firstText(front, back, "issuer"),
        nameAr,
        nameEn,
        bloodType,
        firstText(front, back, "placeOfBirth"),
        score(verification, "idPrintDetection"),
        score(verification, "idScreenDetection"),
        score(verification, "idPhotoTamperingDetection"),
        images(scan));
  }

  /**
   * The {@code verifications[]} entry for this document — matched on {@code documentType}, falling
   * back to the first entry, falling back to a missing node (every score then reads as {@code
   * null}, never a throw: the scores are operator signals, not acceptance gates).
   */
  private static JsonNode verificationFor(JsonNode data, String documentType) {
    JsonNode verifications = data.path("verifications");
    if (!verifications.isArray() || verifications.isEmpty()) {
      return MissingNode.getInstance();
    }
    for (JsonNode candidate : verifications) {
      if (documentType.equals(optionalText(candidate, "documentType"))) {
        return candidate;
      }
    }
    return verifications.get(0);
  }

  /** Decimal on the wire (17.1 observed); rounded here -- see ParsedEnrolmentResult's Javadoc. */
  private static Integer score(JsonNode verification, String detectionKey) {
    JsonNode score = verification.path(detectionKey).path("score");
    return score.isNumber() ? (int) Math.round(score.asDouble()) : null;
  }

  private static List<ParsedImage> images(JsonNode scan) {
    List<ParsedImage> images = new ArrayList<>();
    addImageIfPresent(images, scan, ParsedImage.DOC_FRONT, "frontImageId");
    addImageIfPresent(images, scan, ParsedImage.DOC_BACK, "backImageId");
    addImageIfPresent(images, scan, ParsedImage.DOC_FRONT_FRAME, "frontFrameImageId");
    addImageIfPresent(images, scan, ParsedImage.DOC_BACK_FRAME, "backFrameImageId");
    addImageIfPresent(images, scan, ParsedImage.PORTRAIT_UQUDO, "faceImageId");
    return List.copyOf(images);
  }

  /**
   * Checksum key naming observed at S1-02: {@code <imageKey>Checksum}, e.g. faceImageIdChecksum.
   */
  private static void addImageIfPresent(
      List<ParsedImage> images, JsonNode scan, String kind, String idField) {
    if (scan.hasNonNull(idField)) {
      images.add(
          new ParsedImage(
              kind, scan.path(idField).asString(), optionalText(scan, idField + "Checksum")));
    }
  }

  // ---- face session ----

  /** Backs {@link UqudoClient#verifyAndParseFaceSession}; see that method for the contract. */
  public ParsedFaceResult parseFaceSession(String jws, String expectedFaceSessionId) {
    FaceClaims claims = boundFaceClaims(jws, expectedFaceSessionId);
    JsonNode face = claims.face();
    // Type-guarded, not just null-guarded: Jackson 3's asBoolean()/asInt() throw on a non-scalar
    // node, and that would escape as a 500 instead of a counted rejection (found under review).
    if (!face.path("match").isBoolean() || !face.path("matchLevel").isNumber()) {
      throw new JwsVerificationException("payload is missing required 'face' fields");
    }
    return new ParsedFaceResult(
        claims.jti(),
        claims.sessionId(),
        face.path("match").asBoolean(),
        face.path("matchLevel").asInt(),
        vendorText(face, "error"),
        optionalText(face, "auditTrailImageId"),
        optionalText(face, "auditTrailImageIdChecksum"));
  }

  /**
   * Backs {@link UqudoClient#verifyAndParseIncompleteFaceSession}; see that method for the
   * contract.
   */
  public ParsedIncompleteFaceResult parseIncompleteFaceSession(
      String jws, String expectedFaceSessionId) {
    FaceClaims claims = boundFaceClaims(jws, expectedFaceSessionId);
    JsonNode face = claims.face();
    JsonNode match = face.path("match");
    JsonNode matchLevel = face.path("matchLevel");
    return new ParsedIncompleteFaceResult(
        claims.jti(),
        claims.sessionId(),
        match.isBoolean() ? match.asBoolean() : null,
        matchLevel.isNumber() ? matchLevel.asInt() : null,
        vendorText(face, "error"),
        optionalText(face, "auditTrailImageId"),
        optionalText(face, "auditTrailImageIdChecksum"));
  }

  private record FaceClaims(String jti, String sessionId, JsonNode face) {}

  /**
   * Signature, {@code exp}, then the binding: {@code data.sessionId == expectedFaceSessionId}.
   * Observed at S1-02 -- {@code jti} is a separate UUID on a real face result, so binding on it
   * (the S3-13 stub) would reject every genuine result. {@code data.nonce}, when the builder set
   * one, is echoed; the backend mints none for face sessions today, so it is neither read nor
   * required.
   */
  private FaceClaims boundFaceClaims(String jws, String expectedFaceSessionId) {
    JsonNode root = verifiedPayload(jws);
    String jti = requiredText(root, "jti");
    JsonNode data = root.path("data");
    String sessionId = optionalText(data, "sessionId");
    if (sessionId == null || !sessionId.equals(expectedFaceSessionId)) {
      throw new JwsVerificationException(
          "data.sessionId does not match the Face Session id this attempt issued");
    }
    return new FaceClaims(jti, sessionId, data.path("face"));
  }

  // ---- shared: signature (delegated), then exp ----

  /** Signature via the injected verifier, then {@code exp} — what both parsers check first. */
  private JsonNode verifiedPayload(String jws) {
    JsonNode root = signatureVerifier.verifiedPayload(jws);
    if (!root.path("exp").isNumber()) {
      throw new JwsVerificationException("payload is missing required numeric field 'exp'");
    }
    if (Instant.ofEpochSecond(root.path("exp").asLong()).isBefore(Instant.now(clock))) {
      // Distinct exception type -- R-012/R-021's ARTIFACT_EXPIRED does not count against the
      // retry budget, unlike every other verification failure. Load-bearing: at S1-02 a JWS
      // re-presented one second after exp still verified against the JWKS.
      throw new ArtifactExpiredException("exp has passed");
    }
    return root;
  }

  // ---- field readers ----
  //
  // Every read is type-guarded, not merely null-guarded. Jackson 3's asString()/asBoolean()/
  // asInt() throw JsonNodeException on an object or array node, and that unchecked exception is
  // neither JwsVerificationException nor ArtifactExpiredException -- it would escape the callers'
  // catch blocks as an unaudited, uncounted 500. SDN_ID was never scanned at S1-02, so a key that
  // turns out to be an object there is exactly the kind of surprise this must absorb (found under
  // review). A non-scalar reads as "absent": null for an optional field, a rejection for a
  // required one.

  private static String requiredText(JsonNode node, String field) {
    String value = optionalText(node, field);
    if (value == null) {
      throw new JwsVerificationException("payload is missing required field '" + field + "'");
    }
    return value;
  }

  private static String optionalText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isValueNode() && !value.isNull() ? value.asString() : null;
  }

  /**
   * An undocumented vendor field: a string if it is one, its JSON text if Uqudo ever makes it an
   * object or number, {@code null} if absent -- never a throw.
   */
  private static String vendorText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isNull() || value.isMissingNode()) {
      return null;
    }
    return value.isString() ? value.asString() : value.toString();
  }

  private static String firstText(JsonNode front, JsonNode back, String field) {
    return firstNonNull(optionalText(front, field), optionalText(back, field));
  }

  private static Boolean firstBoolean(JsonNode front, JsonNode back, String field) {
    for (JsonNode side : List.of(front, back)) {
      JsonNode value = side.path(field);
      if (value.isBoolean()) {
        return value.asBoolean();
      }
    }
    return null;
  }

  private static <T> T firstNonNull(T first, T second) {
    return first != null ? first : second;
  }

  /**
   * The ordered date fallback: for base key {@code K}, the four spellings observed at S1-02 in
   * preference order ({@code KFullFormatted}, {@code KFormatted}, {@code KFull}, {@code K}), each
   * looked up front then back, each value tried against every known pattern. The first that parses
   * wins; nothing parses -> {@code null}. Never throws: a date gates nothing (customer.md Stage 9).
   *
   * @param futureLeaning how a 6-character MRZ year resolves: birth/issue dates lie in the past
   *     (two-digit years map into the last 99 years), expiry dates mostly ahead (the window is
   *     shifted 20 years back, 79 forward) -- only reached when no 10-character spelling parsed
   */
  private LocalDate date(JsonNode front, JsonNode back, String baseKey, boolean futureLeaning) {
    for (String key :
        List.of(baseKey + "FullFormatted", baseKey + "Formatted", baseKey + "Full", baseKey)) {
      for (JsonNode side : List.of(front, back)) {
        LocalDate parsed = parseDate(optionalText(side, key), futureLeaning);
        if (parsed != null) {
          return parsed;
        }
      }
    }
    return null;
  }

  private LocalDate parseDate(String value, boolean futureLeaning) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String text = value.trim();
    for (DateTimeFormatter pattern : FULL_YEAR_PATTERNS) {
      try {
        return LocalDate.parse(text, pattern);
      } catch (DateTimeParseException tryNext) {
        // fall through
      }
    }
    if (text.length() == 6) {
      int baseYear = Year.now(clock).getValue() - (futureLeaning ? 20 : 99);
      DateTimeFormatter mrz =
          new DateTimeFormatterBuilder()
              .appendValueReduced(ChronoField.YEAR, 2, 2, baseYear)
              .appendPattern("MMdd")
              .toFormatter();
      try {
        return LocalDate.parse(text, mrz);
      } catch (DateTimeParseException unparseable) {
        return null;
      }
    }
    return null;
  }
}
