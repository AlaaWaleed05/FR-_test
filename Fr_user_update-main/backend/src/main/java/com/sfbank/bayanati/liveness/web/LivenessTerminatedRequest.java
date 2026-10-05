package com.sfbank.bayanati.liveness.web;

/**
 * Covers both of customer.md Stage 10's no-JWS outcomes: an explicit cancel ({@code
 * sdkErrorCode="USER_CANCEL"}) and the SDK exhausting its own internal liveness retries ({@code
 * sdkErrorCode="SESSION_INVALIDATED_FACE_RECOGNITION_TOO_MANY_ATTEMPTS"}) — both recorded as the
 * same {@code liveness_attempt_terminated} event type, distinguished by this field.
 *
 * @param partialJws BL-028, optional: with {@code returnDataForIncompleteSession()} enabled on the
 *     face-session builder, a terminated session's error object carries a signed partial JWS in its
 *     {@code data} field. The app forwards it here untouched (never decoded on the device — the
 *     same server-side-only rule as every other JWS); the backend verifies it with the quarantined
 *     parser and stores it as an audit artifact. Absent or blank when the SDK returned none.
 */
public record LivenessTerminatedRequest(
    String profileId, String faceSessionId, String sdkErrorCode, String partialJws) {}
