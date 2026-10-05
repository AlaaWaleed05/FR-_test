package com.sfbank.bayanati.uqudo.stub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.uqudo.domain.ArtifactExpiredException;
import com.sfbank.bayanati.uqudo.domain.ImageUnavailableException;
import com.sfbank.bayanati.uqudo.domain.JwsVerificationException;
import com.sfbank.bayanati.uqudo.domain.ParsedEnrolmentResult;
import com.sfbank.bayanati.uqudo.domain.ParsedFaceResult;
import com.sfbank.bayanati.uqudo.domain.ParsedImage;
import com.sfbank.bayanati.uqudo.domain.ParsedIncompleteFaceResult;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link StubUqudoClient} is R-034's quarantined parser — these tests exercise it against the real
 * JWS shapes S1-02 logged (2026-09-03), rebuilt with synthetic values. Every structural fact
 * asserted here (no top-level {@code documentType}, {@code verifications[]} scores, {@code
 * front}/{@code back} placement, {@code <key>Checksum} naming, the face binding on {@code
 * data.sessionId}) is one the S3-12/S3-13 stub got wrong.
 */
class StubUqudoClientTest {

  private final StubUqudoClient client = new StubUqudoClient();

  // ---- stage 8: enrolment, the real shape ----

  @Test
  void passportRoundTripsFromTheObservedShape() {
    String sessionId = UUID.randomUUID().toString();
    String nonce = UUID.randomUUID().toString();
    String jws =
        client.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT, null, sessionId, nonce, "NID-0003", false);

    ParsedEnrolmentResult parsed =
        client.verifyAndParse(jws, sessionId, nonce, StubUqudoClient.DOCUMENT_TYPE_PASSPORT);

    assertEquals(sessionId, parsed.jti(), "enrolment jti == the session id we minted (S1-02)");
    assertEquals("PASSPORT", parsed.documentType(), "read from data.documents[0].documentType");
    assertNull(parsed.cardVariant(), "cardVariant is SDN_ID-only");
    assertEquals("NID-0003", parsed.identityNumber());
    assertEquals("MRZ-NID-0003", parsed.documentNumber());
    assertEquals(Boolean.TRUE, parsed.mrzVerified());
    assertEquals("SDN", parsed.nationality());
    assertEquals("SDN", parsed.issuingCountry());
    assertEquals("M", parsed.sexOnDocument());
    assertEquals("MOHAMMED AL TAYEB", parsed.nameEnOnDocument(), "front.fullName");
    assertNotNull(parsed.nameArOnDocument(), "front.fullNameArabic");
    assertEquals("KHARTOUM", parsed.placeOfBirthCity());
    assertEquals("KHARTOUM", parsed.placeOfIssue());
    assertNull(parsed.bloodType());
    assertEquals(LocalDate.of(1990, 1, 1), parsed.dateOfBirth());
    assertEquals(LocalDate.of(2020, 1, 1), parsed.dateOfIssue(), "issueDate, not dateOfIssue");
    assertEquals(LocalDate.of(2030, 1, 1), parsed.dateOfExpiry());
    assertEquals(3, parsed.images().size(), "front, front frame, portrait -- backImageId is null");
    assertTrue(parsed.images().stream().noneMatch(i -> i.kind().equals(ParsedImage.DOC_BACK)));
  }

  @Test
  void antiSpoofScoresComeFromVerificationsAndAreRoundedFromDecimals() {
    String sessionId = UUID.randomUUID().toString();
    String nonce = UUID.randomUUID().toString();
    String jws =
        client.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT, null, sessionId, nonce, "NID-0010", false);

    ParsedEnrolmentResult parsed =
        client.verifyAndParse(jws, sessionId, nonce, StubUqudoClient.DOCUMENT_TYPE_PASSPORT);

    // Fabricated as 17.1 / 10.54 / 0.68 (the S1-02 observed values) inside verifications[0].
    assertEquals(17, parsed.idScreenScore());
    assertEquals(11, parsed.idPrintScore());
    assertEquals(1, parsed.idPhotoTamperingScore());
  }

  @Test
  void latestSdnIdCardIsInferredFromFrontBloodTypeAndReadsTheEnglishNameFromTheBack() {
    String sessionId = UUID.randomUUID().toString();
    String nonce = UUID.randomUUID().toString();
    String jws =
        client.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_SDN_ID,
            StubUqudoClient.CARD_VARIANT_LATEST,
            sessionId,
            nonce,
            "NID-0001",
            false);

    ParsedEnrolmentResult parsed =
        client.verifyAndParse(jws, sessionId, nonce, StubUqudoClient.DOCUMENT_TYPE_SDN_ID);

    assertEquals("SDN_ID", parsed.documentType());
    assertEquals(StubUqudoClient.CARD_VARIANT_LATEST, parsed.cardVariant());
    assertEquals("O+", parsed.bloodType(), "front.bloodType is the discriminator");
    assertEquals("MOHAMMED AL TAYEB", parsed.nameEnOnDocument(), "back.fullName (FIB's key)");
    assertNotNull(parsed.nameArOnDocument(), "front.name");
    assertEquals("NID-0001", parsed.identityNumber());
    // The MRZ block sits on the back of the card; these must still be found.
    assertEquals("MRZ-NID-0001", parsed.documentNumber());
    assertEquals("SDN", parsed.nationality());
    assertEquals(Boolean.TRUE, parsed.mrzVerified());
    assertEquals(LocalDate.of(1990, 1, 1), parsed.dateOfBirth(), "front OCR dd/MM/yyyy form");
    assertEquals(LocalDate.of(2020, 1, 1), parsed.dateOfIssue(), "back.issueDate");
    assertEquals(LocalDate.of(2030, 1, 1), parsed.dateOfExpiry(), "back.dateOfExpiryFull");
    assertEquals(5, parsed.images().size(), "front, back, both frames, portrait");
  }

  @Test
  void olderSdnIdCardHasNoEnglishNameOrBloodType() {
    String sessionId = UUID.randomUUID().toString();
    String nonce = UUID.randomUUID().toString();
    String jws =
        client.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_SDN_ID,
            StubUqudoClient.CARD_VARIANT_PREVIOUS,
            sessionId,
            nonce,
            "NID-0002",
            false);

    ParsedEnrolmentResult parsed =
        client.verifyAndParse(jws, sessionId, nonce, StubUqudoClient.DOCUMENT_TYPE_SDN_ID);

    assertEquals(StubUqudoClient.CARD_VARIANT_PREVIOUS, parsed.cardVariant());
    assertNull(parsed.nameEnOnDocument(), "older card carries no English name");
    assertNull(parsed.bloodType(), "older card carries no bloodType");
    assertNotNull(parsed.nameArOnDocument());
  }

  @Test
  void sdnIdEnglishNameIsAlsoAcceptedUnderTheDocumentedBackNameKey() {
    // FIB's production web app reads back.fullName; Uqudo's docs say back.name. Accept either.
    String sessionId = UUID.randomUUID().toString();
    String nonce = UUID.randomUUID().toString();
    Map<String, Object> claims =
        client.enrolmentClaims(
            StubUqudoClient.DOCUMENT_TYPE_SDN_ID,
            StubUqudoClient.CARD_VARIANT_LATEST,
            sessionId,
            nonce,
            "NID-0011",
            false,
            false);
    Map<String, Object> back = back(claims);
    back.put("name", back.remove("fullName"));

    ParsedEnrolmentResult parsed =
        client.verifyAndParse(
            client.sign(claims), sessionId, nonce, StubUqudoClient.DOCUMENT_TYPE_SDN_ID);

    assertEquals("MOHAMMED AL TAYEB", parsed.nameEnOnDocument());
    assertEquals(StubUqudoClient.CARD_VARIANT_LATEST, parsed.cardVariant());
  }

  @Test
  void missingIdentityNumberFailsClosed() {
    // The Civil Registry key -- a scan without it can never be accepted (uqudo-sdk.md).
    String sessionId = UUID.randomUUID().toString();
    String nonce = UUID.randomUUID().toString();
    Map<String, Object> claims =
        client.enrolmentClaims(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT,
            null,
            sessionId,
            nonce,
            "NID-0012",
            false,
            false);
    front(claims).remove("identityNumber");

    JwsVerificationException thrown =
        assertThrows(
            JwsVerificationException.class,
            () ->
                client.verifyAndParse(
                    client.sign(claims), sessionId, nonce, StubUqudoClient.DOCUMENT_TYPE_PASSPORT));
    assertTrue(thrown.getMessage().contains("identityNumber"));
  }

  @Test
  void unknownVendorObjectsAreToleratedAndUnparseableDatesBecomeNull() {
    // deviceAttestation was absent from every real JWS (BL-027) -- but a future tenant may send
    // it, and data.source is already an undocumented extra. Neither may break the parser. Nor may
    // a date spelling the parser has never seen: the S3-12 stub's LocalDate.parse would have been
    // an uncaught 500 on the real 6-character MRZ value.
    String sessionId = UUID.randomUUID().toString();
    String nonce = UUID.randomUUID().toString();
    Map<String, Object> claims =
        client.enrolmentClaims(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT,
            null,
            sessionId,
            nonce,
            "NID-0013",
            false,
            false);
    data(claims).put("deviceAttestation", Map.of("rooted", false, "emulated", false));
    Map<String, Object> front = front(claims);
    front.put("dateOfBirth", "not-a-date");
    front.put("dateOfBirthFormatted", "31.02.1990");
    front.put("dateOfBirthFull", "");
    front.put("dateOfBirthFullFormatted", "1990-13-45");

    ParsedEnrolmentResult parsed =
        client.verifyAndParse(
            client.sign(claims), sessionId, nonce, StubUqudoClient.DOCUMENT_TYPE_PASSPORT);

    assertNull(parsed.dateOfBirth(), "nothing parsed -> null, never a throw");
    assertEquals("NID-0013", parsed.identityNumber());
  }

  @Test
  void sixCharacterMrzDateIsTheLastResortAndResolvesTheCentury() {
    String sessionId = UUID.randomUUID().toString();
    String nonce = UUID.randomUUID().toString();
    Map<String, Object> claims =
        client.enrolmentClaims(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT,
            null,
            sessionId,
            nonce,
            "NID-0014",
            false,
            false);
    Map<String, Object> front = front(claims);
    front.remove("dateOfBirthFormatted");
    front.remove("dateOfBirthFull");
    front.remove("dateOfBirthFullFormatted");
    front.remove("dateOfExpiryFormatted");
    front.remove("dateOfExpiryFull");
    front.remove("dateOfExpiryFullFormatted");
    // Only the MRZ 6-character forms remain: "900101" (birth, past-leaning -> 1990) and
    // "300101" (expiry, future-leaning -> 2030).

    ParsedEnrolmentResult parsed =
        client.verifyAndParse(
            client.sign(claims), sessionId, nonce, StubUqudoClient.DOCUMENT_TYPE_PASSPORT);

    assertEquals(LocalDate.of(1990, 1, 1), parsed.dateOfBirth());
    assertEquals(LocalDate.of(2030, 1, 1), parsed.dateOfExpiry());
  }

  @Test
  void nonScalarValuesReadAsAbsentNeverAsAThrow() {
    // Found under review: Jackson 3's asString() throws JsonNodeException on an object/array
    // node -- unchecked, and neither JwsVerificationException nor ArtifactExpiredException, so it
    // would escape IdentityScanService's catch as an unaudited, uncounted 500. SDN_ID was never
    // scanned at S1-02; a key that turns out to be an object there must read as absent.
    String sessionId = UUID.randomUUID().toString();
    String nonce = UUID.randomUUID().toString();
    Map<String, Object> claims =
        client.enrolmentClaims(
            StubUqudoClient.DOCUMENT_TYPE_SDN_ID,
            StubUqudoClient.CARD_VARIANT_LATEST,
            sessionId,
            nonce,
            "NID-0018",
            false,
            false);
    Map<String, Object> front = front(claims);
    front.put("placeOfBirth", Map.of("ar", "x", "en", "y"));
    front.put("bloodType", List.of("O+"));
    front.put("dateOfBirthFormatted", Map.of("value", "1990-01-01"));
    back(claims).put("fullName", List.of("A", "B"));

    ParsedEnrolmentResult parsed =
        client.verifyAndParse(
            client.sign(claims), sessionId, nonce, StubUqudoClient.DOCUMENT_TYPE_SDN_ID);

    assertNull(parsed.placeOfBirthCity());
    assertNull(parsed.bloodType());
    assertEquals(StubUqudoClient.CARD_VARIANT_PREVIOUS, parsed.cardVariant(), "absent -> previous");
    assertNull(parsed.nameEnOnDocument());
    assertEquals(LocalDate.of(1990, 1, 1), parsed.dateOfBirth(), "next spelling still parses");

    // A required field that is an object is "missing" -- fail closed, typed.
    front.put("identityNumber", Map.of("value", "NID-0018"));
    assertThrows(
        JwsVerificationException.class,
        () ->
            client.verifyAndParse(
                client.sign(claims), sessionId, nonce, StubUqudoClient.DOCUMENT_TYPE_SDN_ID));
  }

  @Test
  void faceFieldsOfTheWrongTypeAreATypedRejectionNotAThrow() {
    // A properly signed face JWS whose face.match is an object and matchLevel a string: the
    // strict parser must reject it as a typed JwsVerificationException (Jackson 3's
    // asBoolean()/asInt() would otherwise throw), the tolerant one reads both as absent.
    String sessionId = UUID.randomUUID().toString();
    Map<String, Object> face = new LinkedHashMap<>();
    face.put("match", Map.of("value", true));
    face.put("matchLevel", "five");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("face", face);
    data.put("sessionId", sessionId);
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", "uqudo-stub");
    claims.put("aud", "fru-user-update-stub");
    claims.put("data", data);
    claims.put("exp", java.time.Instant.now().plusSeconds(600).getEpochSecond());
    claims.put("iat", java.time.Instant.now().getEpochSecond());
    claims.put("jti", UUID.randomUUID().toString());
    String odd = client.sign(claims);

    assertThrows(
        JwsVerificationException.class, () -> client.verifyAndParseFaceSession(odd, sessionId));
    ParsedIncompleteFaceResult tolerant =
        client.verifyAndParseIncompleteFaceSession(odd, sessionId);
    assertNull(tolerant.match());
    assertNull(tolerant.matchLevel());
  }

  @Test
  void missingScoresReadAsNullNotAThrow() {
    String sessionId = UUID.randomUUID().toString();
    String nonce = UUID.randomUUID().toString();
    Map<String, Object> claims =
        client.enrolmentClaims(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT,
            null,
            sessionId,
            nonce,
            "NID-0015",
            false,
            false);
    data(claims).remove("verifications");

    ParsedEnrolmentResult parsed =
        client.verifyAndParse(
            client.sign(claims), sessionId, nonce, StubUqudoClient.DOCUMENT_TYPE_PASSPORT);

    assertNull(parsed.idPrintScore());
    assertNull(parsed.idScreenScore());
    assertNull(parsed.idPhotoTamperingScore());
  }

  @Test
  void jtiMismatchIsRejected() {
    String sessionId = UUID.randomUUID().toString();
    String nonce = UUID.randomUUID().toString();
    String jws =
        client.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT, null, sessionId, nonce, "NID-0004", false);

    assertThrows(
        JwsVerificationException.class,
        () ->
            client.verifyAndParse(
                jws, "a-different-session-id", nonce, StubUqudoClient.DOCUMENT_TYPE_PASSPORT));
  }

  @Test
  void nonceMismatchIsRejected() {
    String sessionId = UUID.randomUUID().toString();
    String nonce = UUID.randomUUID().toString();
    String jws =
        client.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT, null, sessionId, nonce, "NID-0005", false);

    assertThrows(
        JwsVerificationException.class,
        () ->
            client.verifyAndParse(
                jws, sessionId, "a-different-nonce", StubUqudoClient.DOCUMENT_TYPE_PASSPORT));
  }

  @Test
  void documentTypeMismatchOnTheDocumentEntryIsRejected() {
    String sessionId = UUID.randomUUID().toString();
    String nonce = UUID.randomUUID().toString();
    String jws =
        client.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT, null, sessionId, nonce, "NID-0006", false);

    assertThrows(
        JwsVerificationException.class,
        () -> client.verifyAndParse(jws, sessionId, nonce, StubUqudoClient.DOCUMENT_TYPE_SDN_ID));
  }

  @Test
  void payloadWithoutDocumentsIsRejected() {
    String sessionId = UUID.randomUUID().toString();
    String nonce = UUID.randomUUID().toString();
    Map<String, Object> claims =
        client.enrolmentClaims(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT,
            null,
            sessionId,
            nonce,
            "NID-0016",
            false,
            false);
    data(claims).put("documents", List.of());

    assertThrows(
        JwsVerificationException.class,
        () ->
            client.verifyAndParse(
                client.sign(claims), sessionId, nonce, StubUqudoClient.DOCUMENT_TYPE_PASSPORT));
  }

  @Test
  void tamperedPayloadFailsSignatureVerification() {
    String sessionId = UUID.randomUUID().toString();
    String nonce = UUID.randomUUID().toString();
    String tampered =
        client.fabricateTamperedJws(StubUqudoClient.DOCUMENT_TYPE_PASSPORT, sessionId, nonce);

    assertThrows(
        JwsVerificationException.class,
        () ->
            client.verifyAndParse(
                tampered, sessionId, nonce, StubUqudoClient.DOCUMENT_TYPE_PASSPORT));
  }

  @Test
  void expiredJwsThrowsArtifactExpiredNotJwsVerificationFailure() {
    String sessionId = UUID.randomUUID().toString();
    String nonce = UUID.randomUUID().toString();
    String jws =
        client.fabricateExpiredJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT, null, sessionId, nonce, "NID-EXPIRED");

    assertThrows(
        ArtifactExpiredException.class,
        () -> client.verifyAndParse(jws, sessionId, nonce, StubUqudoClient.DOCUMENT_TYPE_PASSPORT));
  }

  @Test
  void malformedJwsIsRejected() {
    assertThrows(
        JwsVerificationException.class,
        () -> client.verifyAndParse("not-a-jws", "s", "n", StubUqudoClient.DOCUMENT_TYPE_PASSPORT));
  }

  @Test
  void rejectionMessagesNameFieldsNeverPayloadValues() {
    // aud is the tenant client id (identity-bearing, CLAUDE.md: never logged); nonce and jti are
    // per-attempt secrets. A JwsVerificationException's message reaches the controller's 400 body
    // and the audit trail, so it may name a FIELD and nothing else.
    String sessionId = UUID.randomUUID().toString();
    String nonce = UUID.randomUUID().toString();
    String jws =
        client.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT, null, sessionId, nonce, "NID-0017", false);

    for (Runnable failing :
        List.<Runnable>of(
            () ->
                client.verifyAndParse(
                    jws, "other-session", nonce, StubUqudoClient.DOCUMENT_TYPE_PASSPORT),
            () ->
                client.verifyAndParse(
                    jws, sessionId, "other-nonce", StubUqudoClient.DOCUMENT_TYPE_PASSPORT),
            () ->
                client.verifyAndParse(
                    jws, sessionId, nonce, StubUqudoClient.DOCUMENT_TYPE_SDN_ID))) {
      JwsVerificationException thrown = assertThrows(JwsVerificationException.class, failing::run);
      String message = thrown.getMessage();
      assertFalse(message.contains(sessionId), message);
      assertFalse(message.contains(nonce), message);
      assertFalse(message.contains("fru-user-update-stub"), "aud never appears: " + message);
      assertFalse(message.contains("NID-0017"), message);
    }
  }

  @Test
  void downloadedImageMatchesItsOwnEmbeddedChecksum() {
    String sessionId = UUID.randomUUID().toString();
    String nonce = UUID.randomUUID().toString();
    String jws =
        client.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_SDN_ID,
            StubUqudoClient.CARD_VARIANT_LATEST,
            sessionId,
            nonce,
            "NID-0007",
            false);
    ParsedEnrolmentResult parsed =
        client.verifyAndParse(jws, sessionId, nonce, StubUqudoClient.DOCUMENT_TYPE_SDN_ID);

    assertEquals(5, parsed.images().size());
    for (ParsedImage image : parsed.images()) {
      assertNotNull(image.checksum(), "<imageKey>Checksum sibling read for " + image.kind());
      byte[] content = client.downloadImage(image.uqudoImageId(), image.checksum());
      assertTrue(content.length > 0);
    }
  }

  @Test
  void imagesExpiredMakesEveryImageUnavailable() {
    String sessionId = UUID.randomUUID().toString();
    String nonce = UUID.randomUUID().toString();
    String jws =
        client.fabricateJws(
            StubUqudoClient.DOCUMENT_TYPE_PASSPORT, null, sessionId, nonce, "NID-0008", true);
    ParsedEnrolmentResult parsed =
        client.verifyAndParse(jws, sessionId, nonce, StubUqudoClient.DOCUMENT_TYPE_PASSPORT);

    List<ParsedImage> images = parsed.images();
    assertTrue(images.size() > 0);
    for (ParsedImage image : images) {
      assertThrows(
          ImageUnavailableException.class,
          () -> client.downloadImage(image.uqudoImageId(), image.checksum()));
    }
  }

  // ---- stage 10: face session ----

  @Test
  void createFaceSessionReturnsAFreshIdEachCall() {
    String first = client.createFaceSession("some-portrait-bytes".getBytes());
    String second = client.createFaceSession("some-portrait-bytes".getBytes());
    assertNotNull(first);
    assertNotNull(second);
    assertTrue(!first.equals(second), "a new Face Session is required every attempt");
  }

  @Test
  void createFaceSessionRejectsEmptyReferenceImage() {
    assertThrows(IllegalArgumentException.class, () -> client.createFaceSession(new byte[0]));
  }

  @Test
  void faceJwsBindsOnDataSessionIdWhileJtiIsADifferentUuid() {
    // S1-02 defect 1: the S3-13 stub validated jti == expectedFaceSessionId. On a real result
    // jti and data.sessionId are different UUIDs (jtiEqualsFaceSessionId: false), so that check
    // would have rejected every genuine face result. Direct assertion: fails against the old
    // binding by construction, since the fabricated jti is never the session id.
    String sessionId = UUID.randomUUID().toString();
    String jws = client.fabricateFaceJws(sessionId, true, 5);

    ParsedFaceResult parsed = client.verifyAndParseFaceSession(jws, sessionId);

    assertEquals(sessionId, parsed.sessionId(), "bound and purged by data.sessionId");
    assertNotEquals(sessionId, parsed.jti(), "jti is Uqudo's own, separate id");
    assertTrue(parsed.match());
    assertEquals(5, parsed.matchLevel());
    assertNull(parsed.faceError(), "face.error observed null on every real result");
    assertNotNull(parsed.auditTrailImageId());
    assertNotNull(parsed.auditTrailChecksum(), "auditTrailImageIdChecksum sibling");
  }

  @Test
  void faceJwsWithMatchFalseIsAStillSuccessfulVerification() {
    // customer.md Stage 10: a signed JWS with match=false is a SUCCESSFUL call, not a rejection.
    String sessionId = UUID.randomUUID().toString();
    String jws = client.fabricateFaceJws(sessionId, false, 2);

    ParsedFaceResult parsed = client.verifyAndParseFaceSession(jws, sessionId);
    assertEquals(false, parsed.match());
    assertEquals(2, parsed.matchLevel());
  }

  @Test
  void faceErrorIsSurfacedWhenPresentAndNeverRequired() {
    String sessionId = UUID.randomUUID().toString();
    String jws = client.fabricateFaceJwsWithError(sessionId, false, 1, "SOME_VENDOR_CODE");

    ParsedFaceResult parsed = client.verifyAndParseFaceSession(jws, sessionId);
    assertEquals("SOME_VENDOR_CODE", parsed.faceError());
  }

  @Test
  void faceSessionIdMismatchIsRejected() {
    String sessionId = UUID.randomUUID().toString();
    String jws = client.fabricateFaceJws(sessionId, true, 5);

    JwsVerificationException thrown =
        assertThrows(
            JwsVerificationException.class,
            () -> client.verifyAndParseFaceSession(jws, "a-different-session-id"));
    assertFalse(thrown.getMessage().contains(sessionId), "no payload value in the message");
  }

  @Test
  void tamperedFaceJwsFailsSignatureVerification() {
    String sessionId = UUID.randomUUID().toString();
    String tampered = client.fabricateTamperedFaceJws(sessionId);

    assertThrows(
        JwsVerificationException.class,
        () -> client.verifyAndParseFaceSession(tampered, sessionId));
  }

  @Test
  void malformedFaceJwsIsRejected() {
    assertThrows(
        JwsVerificationException.class, () -> client.verifyAndParseFaceSession("not-a-jws", "s"));
  }

  @Test
  void faceAuditTrailImageDownloadsAndMatchesItsOwnChecksum() {
    String sessionId = UUID.randomUUID().toString();
    String jws = client.fabricateFaceJws(sessionId, true, 5);
    ParsedFaceResult parsed = client.verifyAndParseFaceSession(jws, sessionId);

    byte[] content = client.downloadImage(parsed.auditTrailImageId(), parsed.auditTrailChecksum());
    assertTrue(content.length > 0);
  }

  @Test
  void expiredFaceJwsThrowsArtifactExpiredNotJwsVerificationFailure() {
    String sessionId = UUID.randomUUID().toString();
    String jws = client.fabricateExpiredFaceJws(sessionId, true, 5);

    assertThrows(
        ArtifactExpiredException.class, () -> client.verifyAndParseFaceSession(jws, sessionId));
  }

  @Test
  void faceAuditTrailImageExpiredIsUnavailable() {
    String sessionId = UUID.randomUUID().toString();
    String jws = client.fabricateFaceJwsWithExpiredImage(sessionId, true, 5);
    ParsedFaceResult parsed = client.verifyAndParseFaceSession(jws, sessionId);

    assertThrows(
        ImageUnavailableException.class,
        () -> client.downloadImage(parsed.auditTrailImageId(), parsed.auditTrailChecksum()));
  }

  // ---- BL-028: the partial artifact from a terminated face session ----

  @Test
  void incompleteFaceJwsWithMatchDetailIsParsed() {
    String sessionId = UUID.randomUUID().toString();
    String jws = client.fabricateIncompleteFaceJws(sessionId, false, 1, "FACE_NO_MATCH");

    ParsedIncompleteFaceResult parsed = client.verifyAndParseIncompleteFaceSession(jws, sessionId);

    assertEquals(sessionId, parsed.sessionId());
    assertNotEquals(sessionId, parsed.jti());
    assertEquals(Boolean.FALSE, parsed.match());
    assertEquals(1, parsed.matchLevel());
    assertEquals("FACE_NO_MATCH", parsed.faceError());
    assertNotNull(parsed.auditTrailImageId());
  }

  @Test
  void incompleteFaceJwsWithoutAFaceObjectDegradesToNulls() {
    // [UNVERIFIED] whether Uqudo's partial artifact carries face at all -- the parser must accept
    // a bare {sessionId} data object and hand back an audit record without match detail.
    String sessionId = UUID.randomUUID().toString();
    String jws = client.fabricateIncompleteFaceJws(sessionId, null, null, null);

    ParsedIncompleteFaceResult parsed = client.verifyAndParseIncompleteFaceSession(jws, sessionId);

    assertEquals(sessionId, parsed.sessionId());
    assertNull(parsed.match());
    assertNull(parsed.matchLevel());
    assertNull(parsed.faceError());
    assertNull(parsed.auditTrailImageId());
  }

  @Test
  void incompleteFaceJwsStillEnforcesTheBindingAndExpiry() {
    String sessionId = UUID.randomUUID().toString();
    String jws = client.fabricateIncompleteFaceJws(sessionId, false, 1, null);

    assertThrows(
        JwsVerificationException.class,
        () -> client.verifyAndParseIncompleteFaceSession(jws, "a-different-session-id"));
    assertThrows(
        ArtifactExpiredException.class,
        () ->
            client.verifyAndParseIncompleteFaceSession(
                client.fabricateExpiredFaceJws(sessionId, true, 5), sessionId));
    // A complete JWS is also a valid "incomplete" one -- same envelope, per Uqudo's own wording.
    ParsedIncompleteFaceResult fromComplete =
        client.verifyAndParseIncompleteFaceSession(
            client.fabricateFaceJws(sessionId, true, 5), sessionId);
    assertEquals(Boolean.TRUE, fromComplete.match());
  }

  // ---- helpers into the mutable claims maps ----

  @SuppressWarnings("unchecked")
  private static Map<String, Object> data(Map<String, Object> claims) {
    return (Map<String, Object>) claims.get("data");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> scan(Map<String, Object> claims) {
    List<Object> documents = (List<Object>) data(claims).get("documents");
    Map<String, Object> document = (Map<String, Object>) documents.get(0);
    return (Map<String, Object>) document.get("scan");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> front(Map<String, Object> claims) {
    return (Map<String, Object>) scan(claims).get("front");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> back(Map<String, Object> claims) {
    Map<String, Object> scan = scan(claims);
    return (Map<String, Object>) scan.computeIfAbsent("back", k -> new LinkedHashMap<>());
  }
}
