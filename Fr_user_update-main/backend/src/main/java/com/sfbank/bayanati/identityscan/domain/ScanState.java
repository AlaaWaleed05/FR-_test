package com.sfbank.bayanati.identityscan.domain;

import java.time.Instant;

/**
 * {@code app.profile}'s status plus the stage-8 retry-budget columns (V0039), read together under
 * one row lock — mirrors {@code dataentry.domain.ProfileLock}'s shape, extended with the counters
 * this feature alone needs.
 *
 * @param scanTokensMinted the profile's lifetime scan-token mint count (V0065, BL-039 Slice B).
 *     Read under the same lock as everything else here because the cap it feeds is checked and
 *     incremented inside the issuance transaction.
 */
public record ScanState(
    String status,
    boolean terminal,
    int scanAttemptsNationalId,
    int scanAttemptsPassport,
    int scanAttemptsTotal,
    Instant scanBlockedUntil,
    String pendingScanSessionId,
    String pendingScanNonce,
    int scanTokensMinted) {

  public int attemptsFor(String appDocumentType) {
    return DocumentTypes.NATIONAL_ID.equals(appDocumentType)
        ? scanAttemptsNationalId
        : scanAttemptsPassport;
  }
}
