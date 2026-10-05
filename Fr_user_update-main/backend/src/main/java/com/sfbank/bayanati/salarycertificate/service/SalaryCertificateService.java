package com.sfbank.bayanati.salarycertificate.service;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.audit.domain.CanonicalJson;
import com.sfbank.bayanati.profile.domain.ProfileRepository;
import com.sfbank.bayanati.salarycertificate.domain.ProfileNotEditableException;
import com.sfbank.bayanati.salarycertificate.domain.SalaryCertificateRejectedException;
import com.sfbank.bayanati.salarycertificate.domain.SalaryCertificateRepository;
import com.sfbank.bayanati.salarycertificate.domain.UnknownProfileException;
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
 * Journey Stage 6 — the optional salary/income certificate (docs/journeys/customer.md, BL-022).
 * Camera capture or file selection, stored byte-identical in {@code app.artifact_ref.body}
 * (AD-004), never validated beyond size/format, and gates nothing: no status, no completion, no
 * operator action ever depends on it, and this class never rejects a submission for its absence
 * (there is no such check anywhere in {@code SubmissionService}, by construction).
 *
 * <p>Size/format limits are customer.md's own settled Policy value ("Attachment limits — 10 MB max;
 * JPEG, PNG or PDF"), not invented here — unlike {@code signature.service.SignatureService}'s
 * limits, which are an explicitly-stated placeholder for an unset policy marker.
 */
@Service
public class SalaryCertificateService {

  static final String CHAIN_KIND = "profile";
  static final String EVENT_CERTIFICATE_UPLOADED = "salary_certificate_uploaded";
  static final String EVENT_CERTIFICATE_REJECTED = "salary_certificate_rejected";

  static final long MAX_BYTES = 10L * 1024 * 1024;
  static final Set<String> ALLOWED_CONTENT_TYPES =
      Set.of("image/png", "image/jpeg", "application/pdf");

  private final SalaryCertificateRepository salaryCertificateRepository;
  private final ProfileRepository profileRepository;
  private final AuditEventWriter auditEventWriter;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  public SalaryCertificateService(
      SalaryCertificateRepository salaryCertificateRepository,
      ProfileRepository profileRepository,
      AuditEventWriter auditEventWriter,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.salaryCertificateRepository = salaryCertificateRepository;
    this.profileRepository = profileRepository;
    this.auditEventWriter = auditEventWriter;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public void submitCertificate(UUID profileId, String contentType, byte[] content) {
    UUID requestId = UUID.randomUUID();
    Instant now = clock.instant();

    if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
      throw new SalaryCertificateRejectedException(
          "contentType must be image/png, image/jpeg or application/pdf");
    }
    if (content == null || content.length == 0) {
      throw new SalaryCertificateRejectedException("certificate content must not be empty");
    }
    if (content.length > MAX_BYTES) {
      throw new SalaryCertificateRejectedException(
          "certificate content exceeds " + MAX_BYTES + " bytes");
    }

    boolean terminal =
        salaryCertificateRepository
            .checkTerminal(profileId)
            .orElseThrow(() -> new UnknownProfileException("no profile with id " + profileId));
    if (terminal) {
      auditRejection(profileId, requestId, "profile_already_complete");
      throw new ProfileNotEditableException(
          "profile " + profileId + " has already reached a terminal status");
    }

    byte[] sha256 = sha256(content);
    transactionTemplate.executeWithoutResult(
        status -> {
          salaryCertificateRepository.upsertSalaryCertificateArtifact(
              profileId, contentType, content.length, sha256, content, opaqueStorageKey(), now);

          // Never the certificate bytes in the audit payload -- same discipline as the signature
          // and the raw Uqudo JWS.
          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("contentType", contentType);
          payload.put("byteSize", (long) content.length);
          auditEventWriter.append(
              new AuditEvent(
                  CHAIN_KIND,
                  profileId.toString(),
                  EVENT_CERTIFICATE_UPLOADED,
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
            EVENT_CERTIFICATE_REJECTED,
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
