package com.sfbank.bayanati.liveness.domain;

/**
 * The {@code faceSessionId} the request supplied does not match {@code
 * app.profile.pending_face_session_id} — the id this profile's most recent {@code
 * issueFaceSessionToken} call actually issued (V0044). Mirrors {@code
 * identityscan.domain.InvalidScanSessionException}'s replay-guard reasoning: validated against what
 * the backend recorded, never trusted from the request alone.
 */
public class InvalidFaceSessionException extends RuntimeException {

  public InvalidFaceSessionException(String message) {
    super(message);
  }
}
