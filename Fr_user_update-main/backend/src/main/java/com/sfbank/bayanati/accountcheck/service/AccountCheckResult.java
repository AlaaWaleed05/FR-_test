package com.sfbank.bayanati.accountcheck.service;

import com.sfbank.bayanati.accountcheck.domain.AccountCheckContinuation;
import com.sfbank.bayanati.accountcheck.domain.AccountCheckOutcome;
import java.time.Instant;
import java.util.UUID;

/**
 * What Stage 1a produced, as the rest of the backend sees it.
 *
 * <p>The raw middleware {@code Response_Code} is deliberately not here. It is recorded in the audit
 * trail, where a misuse investigation reads it, and nowhere else — the handset has no use for it
 * and no business knowing it.
 *
 * @param outcome active or invalid
 * @param continuation what the journey does next. Computed by {@link AccountCheckService}, not read
 *     from {@code outcome.continuation()} — an ACTIVE account's default continuation ({@code
 *     PROCEED}) is overridden to {@code TERMINAL} when the profile-existence check (S3-07) finds a
 *     complete profile already on record, or to {@code BLOCKED} (S4-06, BL-021) when it finds an
 *     incomplete profile whose phone channels are currently under Stage 2's escalating OTP lock.
 * @param requestId correlates this check with its audit event, so support can find the exact row
 *     from what the customer was shown
 * @param blockedUntil non-{@code null} only when {@code continuation == BLOCKED} — when the current
 *     phone lock lifts, so the app can render customer.md Stage 0's "blocked until [time]" screen.
 *     <p><strong>What this exposes to this endpoint's unauthenticated caller, and why it's accepted
 *     (S4-06, BL-021):</strong> a prober who already knows an account is {@code ACTIVE} (already
 *     exposed today via {@code outcome}) and has an incomplete profile (already inferable today:
 *     {@code PROCEED} vs {@code TERMINAL} already distinguishes "no completed profile" from
 *     "completed profile") additionally learns that its phone channels are currently under Stage
 *     2's escalating OTP lock, and exactly when it lifts. No PII is added — no name, phone number
 *     or email, only a timestamp. Nor is it new in kind: the identical {@code phone_lock_until}
 *     value is already returned by the equally unauthenticated {@code POST
 *     /api/v1/contact-channels} via {@code SessionTemporarilyBlockedException} on a re-entry
 *     attempt (S3-08). Reaching this state at all requires having already driven the account
 *     through 5 wrong OTP attempts per phone channel — an attacker probing for it either caused the
 *     lock themselves (no new disclosure) or is confirming a third party's session is currently
 *     guessable-until timestamped, accepted under the same "enumeration is an accepted non-concern"
 *     posture BL-007/R-038 already establish for this endpoint.
 */
public record AccountCheckResult(
    AccountCheckOutcome outcome,
    AccountCheckContinuation continuation,
    UUID requestId,
    Instant blockedUntil) {}
