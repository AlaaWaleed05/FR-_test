package com.sfbank.bayanati.accountcheck.web;

import com.sfbank.bayanati.accountcheck.domain.AccountCheckContinuation;
import com.sfbank.bayanati.accountcheck.domain.AccountCheckOutcome;

/**
 * What Stage 1a reports back to the app.
 *
 * <p>The raw middleware {@code Response_Code} is not here by design — it belongs to the audit
 * trail, not the handset.
 *
 * @param outcome {@code ACTIVE} or {@code INVALID} ({@code INACTIVE} was removed at S3-02: the
 *     middleware cannot report it; an outage is a 503, not an outcome)
 * @param continuation whether the journey may proceed, must stop, or may be retried. The app maps
 *     this to a screen; the backend does not choose one.
 *     <p><strong>Narrowed for an ACTIVE account, not widened (S3-07).</strong> The journey branches
 *     again on whether a profile already exists: no profile, or an incomplete one, still answers
 *     {@code PROCEED}; a <strong>complete</strong> profile now answers {@code TERMINAL} instead —
 *     no new enum value, so this is not a wire-breaking change. An app that already treats every
 *     ACTIVE account as {@code PROCEED} must be updated to handle ACTIVE+{@code TERMINAL} ("this
 *     account has already been updated")
 * @param requestId the identifier recorded on this attempt's audit event, so an operator can find
 *     the exact record from what the customer was shown
 * @param blockedUntil ISO-8601, non-{@code null} only when {@code continuation == BLOCKED} (S4-06,
 *     BL-021) — the app renders customer.md Stage 0's "blocked until [time]" screen using this
 *     value. See {@code AccountCheckResult}'s Javadoc for what this does and does not disclose to
 *     this endpoint's unauthenticated caller.
 */
public record AccountCheckResponse(
    AccountCheckOutcome outcome,
    AccountCheckContinuation continuation,
    String requestId,
    String blockedUntil) {}
