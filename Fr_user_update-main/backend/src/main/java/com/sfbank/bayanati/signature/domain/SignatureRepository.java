package com.sfbank.bayanati.signature.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The one way application code reads eligibility for and writes the {@code signature} kind of
 * {@code app.artifact_ref} (V0026). Mirrors {@code identityscan.domain.IdentityScanRepository}'s
 * role for this feature's own narrow slice.
 */
public interface SignatureRepository {

  /** Empty if no {@code app.profile} row exists for this id. */
  Optional<SignatureEligibility> checkEligibility(UUID profileId);

  /**
   * Upserts one {@code app.artifact_ref} row ({@code kind='signature'}, {@code profile_id} set,
   * {@code cycle_id=NULL} — V0026's own shape, captured at stage 11 after liveness, tied to the
   * whole profile rather than to an identity cycle), including the signature bytes themselves
   * (AD-004, S5-06: bytes live in this row's own {@code body} column). Re-submission is allowed
   * (the customer can redraw): {@code ON CONFLICT (profile_id) WHERE kind='signature'} (V0046's
   * partial unique index — plain {@code UNIQUE (cycle_id, kind)} from V0008 does not help here,
   * since Postgres does not dedupe NULL {@code cycle_id}s) replaces the previous attempt in place
   * rather than accumulating one retained-PII row per resubmission.
   */
  void insertSignatureArtifact(
      UUID profileId,
      String contentType,
      long byteSize,
      byte[] sha256,
      byte[] content,
      String storageKey,
      Instant now);
}
