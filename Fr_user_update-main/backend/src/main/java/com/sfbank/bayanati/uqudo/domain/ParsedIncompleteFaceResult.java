package com.sfbank.bayanati.uqudo.domain;

/**
 * The quarantine-boundary output of {@link UqudoClient#verifyAndParseIncompleteFaceSession} — the
 * partial artifact a face session terminated by the SDK hands back when {@code
 * FaceSessionConfigurationBuilder.returnDataForIncompleteSession()} is enabled (BL-028, product
 * decision 2026-09-04).
 *
 * <p>Unlike {@link ParsedFaceResult}, nothing beyond the signature, {@code exp} and the {@code
 * data.sessionId} binding is required: Uqudo documents that the partial data "will contain the same
 * JWS string that is returned in a successful scenario" but nowhere states whether its {@code face}
 * object is present, or carries {@code match:false} — {@code [UNVERIFIED]}, closable only by a real
 * two-person test. So every {@code face} field here is nullable and the caller degrades to "an
 * audited artifact without match detail" when they are absent.
 *
 * @param jti the JWS's own id
 * @param sessionId {@code data.sessionId} — bound to the Face Session this attempt issued
 * @param match {@code face.match} if present, else {@code null}
 * @param matchLevel {@code face.matchLevel} if present, else {@code null}
 * @param faceError {@code face.error} if present, else {@code null}
 * @param auditTrailImageId {@code face.auditTrailImageId} if present, else {@code null}
 * @param auditTrailChecksum its {@code <key>Checksum} sibling if present, else {@code null}
 */
public record ParsedIncompleteFaceResult(
    String jti,
    String sessionId,
    Boolean match,
    Integer matchLevel,
    String faceError,
    String auditTrailImageId,
    String auditTrailChecksum) {}
