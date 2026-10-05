package com.sfbank.bayanati.profile.domain;

import java.util.UUID;

/**
 * What {@link ProfileRepository#findExisting} found for one account number — at most one row, per
 * {@code app.profile}'s {@code profile_one_per_account} UNIQUE constraint ({@code UNIQUE
 * (account_number)} since V0061, BL-032; V0005 declared it over the (branch, account) pair).
 *
 * @param profileId the existing profile's id
 * @param status the current {@code app.profile.status} value
 * @param terminal {@code app.status_code.is_terminal} for that status — {@code true} for exactly
 *     {@code submitted}, {@code approved}, {@code rejected} and {@code
 *     terminated_registry_mismatch} (operator.md, "Terminal for the customer"). Read from the
 *     database rather than re-derived in Java, so there is exactly one place this set can drift.
 */
public record ExistingProfile(UUID profileId, String status, boolean terminal) {}
