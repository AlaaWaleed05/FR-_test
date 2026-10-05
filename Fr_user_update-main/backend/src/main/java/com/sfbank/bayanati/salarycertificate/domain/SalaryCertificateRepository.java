package com.sfbank.bayanati.salarycertificate.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The one way application code reads eligibility for and writes the {@code salary_certificate} kind
 * of {@code app.artifact_ref} (V0008/V0026). Mirrors {@code signature.domain.SignatureRepository}'s
 * role for this feature's own narrow slice.
 */
public interface SalaryCertificateRepository {

  /** Empty if no {@code app.profile} row exists for this id; otherwise its terminal-ness. */
  Optional<Boolean> checkTerminal(UUID profileId);

  /**
   * Upserts one {@code app.artifact_ref} row ({@code kind='salary_certificate'}, {@code profile_id}
   * set, {@code cycle_id=NULL} — V0008's own shape, "for the salary certificate, which has no
   * cycle"), including the certificate bytes themselves (AD-004, S5-06: bytes live in this row's
   * own {@code body} column). Re-upload is allowed (customer.md Stage 6 imposes no limit on
   * retrying): {@code ON CONFLICT (profile_id) WHERE kind='salary_certificate'} (V0059, mirroring
   * V0046's signature precedent — plain {@code UNIQUE (cycle_id, kind)} from V0008 does not help
   * here, since Postgres does not dedupe NULL {@code cycle_id}s) replaces the previous attempt in
   * place rather than accumulating one retained-PII row per upload.
   */
  void upsertSalaryCertificateArtifact(
      UUID profileId,
      String contentType,
      long byteSize,
      byte[] sha256,
      byte[] content,
      String storageKey,
      Instant now);
}
