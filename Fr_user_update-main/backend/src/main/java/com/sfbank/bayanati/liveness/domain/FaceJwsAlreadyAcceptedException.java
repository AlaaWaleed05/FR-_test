package com.sfbank.bayanati.liveness.domain;

/**
 * {@code app.face_result.uqudo_jti}'s UNIQUE constraint (V0008) — the global replay guard for a
 * face-session JWS, mirroring {@code identityscan.domain.JwsAlreadyAcceptedException} for stage 8.
 * Reachable only across two different identity cycles claiming the same {@code jti}, since a retry
 * against the SAME cycle upserts (see {@code LivenessRepository#upsertFaceResult}).
 */
public class FaceJwsAlreadyAcceptedException extends RuntimeException {

  public FaceJwsAlreadyAcceptedException(String message) {
    super(message);
  }
}
