package com.sfbank.bayanati.signature.service;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.audit.domain.CanonicalJson;
import com.sfbank.bayanati.profile.domain.ProfileRepository;
import com.sfbank.bayanati.signature.domain.LivenessNotCompleteException;
import com.sfbank.bayanati.signature.domain.ProfileNotEditableException;
import com.sfbank.bayanati.signature.domain.SignatureEligibility;
import com.sfbank.bayanati.signature.domain.SignatureRejectedException;
import com.sfbank.bayanati.signature.domain.SignatureRepository;
import com.sfbank.bayanati.signature.domain.UnknownProfileException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Journey Stage 11 — signature (docs/journeys/customer.md). Mandatory, two capture routes ({@code
 * "drawn"}/{@code "uploaded"}), both producing a file stored byte-identical in {@code
 * app.artifact_ref.body} (AD-004). There is no skip.
 *
 * <p><strong>The size/format limits below are an operational placeholder, not a resolved
 * policy.</strong> customer.md's own Policy values section states plainly: "except the signature
 * file size and format limits at Stage 11, which remain unset." No product-owner or bank spec
 * exists. 5&nbsp;MB / PNG-or-JPEG-only is chosen here so the endpoint has *some* bound rather than
 * none — smaller than the general 10&nbsp;MB/JPEG-PNG-PDF attachment table, which is Stage 6's
 * salary-certificate policy, not this one, and PDF is excluded because a signature is captured as
 * an image, never scanned as a document. This does not resolve the open {@code [POLICY: ...]}
 * marker.
 */
@Service
public class SignatureService {

  static final String CHAIN_KIND = "profile";
  static final String EVENT_SIGNATURE_CAPTURED = "signature_captured";
  static final String EVENT_SIGNATURE_REJECTED = "signature_rejected";

  static final long MAX_BYTES = 5L * 1024 * 1024;
  static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/png", "image/jpeg");
  static final Set<String> ALLOWED_CAPTURE_METHODS = Set.of("drawn", "uploaded");

  private final SignatureRepository signatureRepository;
  private final ProfileRepository profileRepository;
  private final AuditEventWriter auditEventWriter;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  public SignatureService(
      SignatureRepository signatureRepository,
      ProfileRepository profileRepository,
      AuditEventWriter auditEventWriter,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.signatureRepository = signatureRepository;
    this.profileRepository = profileRepository;
    this.auditEventWriter = auditEventWriter;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public void submitSignature(
      UUID profileId, String captureMethod, String contentType, byte[] content) {
    UUID requestId = UUID.randomUUID();
    Instant now = clock.instant();

    if (!ALLOWED_CAPTURE_METHODS.contains(captureMethod)) {
      throw new SignatureRejectedException("captureMethod must be 'drawn' or 'uploaded'");
    }
    if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
      throw new SignatureRejectedException("contentType must be image/png or image/jpeg");
    }
    if (content == null || content.length == 0) {
      throw new SignatureRejectedException("signature content must not be empty");
    }
    if (content.length > MAX_BYTES) {
      throw new SignatureRejectedException("signature content exceeds " + MAX_BYTES + " bytes");
    }

    SignatureEligibility eligibility =
        signatureRepository
            .checkEligibility(profileId)
            .orElseThrow(() -> new UnknownProfileException("no profile with id " + profileId));
    if (eligibility.terminal()) {
      auditRejection(profileId, requestId, "profile_already_complete");
      throw new ProfileNotEditableException(
          "profile " + profileId + " has already reached a terminal status");
    }
    if (!eligibility.facePassed()) {
      auditRejection(profileId, requestId, "liveness_not_complete");
      throw new LivenessNotCompleteException(
          "profile " + profileId + " has not yet passed stage 10 liveness/face-match");
    }

    byte[] sha256 = sha256(content);
    transactionTemplate.executeWithoutResult(
        status -> {
          signatureRepository.insertSignatureArtifact(
              profileId, contentType, content.length, sha256, content, opaqueStorageKey(), now);

          // Never the image bytes in the audit payload -- same never-inline-large-artifacts
          // discipline as stage 8's raw JWS storage.
          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("captureMethod", captureMethod);
          payload.put("contentType", contentType);
          payload.put("byteSize", (long) content.length);
          auditEventWriter.append(
              new AuditEvent(
                  CHAIN_KIND,
                  profileId.toString(),
                  EVENT_SIGNATURE_CAPTURED,
                  "customer",
                  null,
                  profileId,
                  profileId,
                  requestId,
                  CanonicalJson.object(payload)));
          profileRepository.touchLastActivity(profileId, now);
        });
  }

  private void auditRejection(UUID profileId, UUID requestId, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("reason", reason);
    auditEventWriter.append(
        new AuditEvent(
            CHAIN_KIND,
            profileId.toString(),
            EVENT_SIGNATURE_REJECTED,
            "customer",
            null,
            profileId,
            profileId,
            requestId,
            CanonicalJson.object(payload)));
  }

  private static String opaqueStorageKey() {
    // AD-004 is closed (S5-06): bytes live in this row's own body column. This id is DEAD, not
    // reserved: R-046 closed at BL-075 (2026-09-13) and its addressing scheme needs no stored key
    // -- the operator image endpoint addresses an artifact by its own artifact_ref_id. Still
    // written because the column is NOT NULL; deliberately not dropped, which is its own migration.
    return "artifact:" + UUID.randomUUID();
  }

  private static byte[] sha256(byte[] content) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(content);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
