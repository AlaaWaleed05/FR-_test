package com.sfbank.bayanati.uqudo.domain;

/**
 * The clean, quarantine-boundary output of {@link UqudoClient#verifyAndParseFaceSession} — mirrors
 * {@link ParsedEnrolmentResult}'s role for stage 8: the only shape stage 10's application code is
 * allowed to depend on (R-034's quarantine discipline, extended to the face-session JWS).
 *
 * <p>Field set mirrors {@code app.face_result} column-for-column (V0008), plus the two identifiers
 * a real face-session JWS carries. There is deliberately no liveness field — Uqudo returns none; a
 * liveness failure never produces a JWS at all (uqudo-sdk.md, AD-002a).
 *
 * <p><strong>Two different ids, observed at S1-02 (2026-09-03):</strong> on a real face-session JWS
 * {@code jti} and {@code data.sessionId} are <em>different</em> UUIDs. {@code data.sessionId} is
 * the Face Session id {@code POST /api/v1/face} returned — the attempt binding and the id {@code
 * DELETE /api/v1/info/{id}} actually purges by (a DELETE by {@code jti} returns 204 and deletes
 * nothing). {@code jti} is the JWS's own replay key ({@code app.face_result.uqudo_jti} UNIQUE).
 * Enrolment JWS differ: there {@code jti} equals the session id the backend minted.
 *
 * @param jti the JWS's own id — the replay/retry key, never the purge id for a face session
 * @param sessionId {@code data.sessionId} — the Face Session id this result is bound to, and the id
 *     to pass to {@link UqudoClient#purgeSession}
 * @param match whether the live face matched the reference portrait
 * @param matchLevel 1-5; the server-side threshold (starting at 3, uqudo-sdk.md) is applied by the
 *     caller, never by the SDK ({@code setMinimumMatchLevel()} must never be called — AD-002a)
 * @param faceError Uqudo's undocumented {@code face.error} — {@code null} on every real result seen
 *     so far; surfaced into the audit payload only, never load-bearing (vendor field, R-034
 *     quarantine: parsed defensively, tolerated absent)
 * @param auditTrailImageId the id to pass to {@link UqudoClient#downloadImage}
 * @param auditTrailChecksum Uqudo's own {@code "sha256:<digest>"} string
 */
public record ParsedFaceResult(
    String jti,
    String sessionId,
    boolean match,
    int matchLevel,
    String faceError,
    String auditTrailImageId,
    String auditTrailChecksum) {}
