package com.sfbank.bayanati.uqudo.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * The clean, quarantine-boundary output of {@link UqudoClient#verifyAndParse} — the shape every
 * other class in this codebase is allowed to depend on. R-034: no other code may depend on the raw
 * JWS's own field names or on any field {@code docs/components/uqudo-sdk.md} marks [UNVERIFIED] —
 * only this record's field names, which are ours.
 *
 * <p>Field set mirrors {@code app.scan_result} column-for-column (V0008/V0024), since that table
 * was built ahead of this task specifically to hold it. Rewritten against the real enrolment JWS at
 * R-034 (2026-09-04): the field NAMES below are ours; how each is found in the payload (front/back
 * side, {@code verifications[]} for the scores, date-format fallbacks) is the parser's business
 * alone.
 *
 * @param jti Uqudo's session id — on an enrolment JWS this equals the session id the backend minted
 *     (observed at S1-02), so it is also the value passed to {@code DELETE /api/v1/info/{id}}
 * @param documentType {@code "SDN_ID"} or {@code "PASSPORT"}, as requested and echoed in {@code
 *     data.documents[0].documentType}
 * @param cardVariant SDN_ID only: {@code "previous"} or {@code "latest"}, INFERRED from whether the
 *     front OCR produced a {@code bloodType} (uqudo-sdk.md: no discriminator is documented, and a
 *     latest card whose blood-type line failed OCR classifies as previous — harmless for
 *     provenance, never a recorded fact about the document); {@code null} for a passport
 * @param identityNumber the Civil Registry lookup key — never {@code documentNumber}; the parser
 *     fails closed when it is absent
 * @param sexOnDocument the document's own sex field — distinct from the customer's stage-3
 *     declaration and from the Civil Registry's authoritative value
 * @param idPrintScore Uqudo reports these as decimals (17.1 / 10.54 / 0.68 observed at S1-02);
 *     rounded to the nearest integer at this boundary because {@code app.scan_result} holds {@code
 *     smallint} and the documented rejection thresholds (50 / 50 / 70) need no finer resolution
 */
public record ParsedEnrolmentResult(
    String jti,
    String documentType,
    String cardVariant,
    String identityNumber,
    String documentNumber,
    Boolean mrzVerified,
    String nationality,
    String sexOnDocument,
    LocalDate dateOfBirth,
    LocalDate dateOfIssue,
    LocalDate dateOfExpiry,
    String placeOfIssue,
    String issuingCountry,
    String nameArOnDocument,
    String nameEnOnDocument,
    String bloodType,
    String placeOfBirthCity,
    Integer idPrintScore,
    Integer idScreenScore,
    Integer idPhotoTamperingScore,
    List<ParsedImage> images) {}
