package com.sfbank.bayanati.profile.domain;

/**
 * {@code app.profile_customer_data}'s phone/email as they stood before a Stage 1b re-entry
 * overwrites them — read only so the {@code session_reentered} audit event can record what changed,
 * never surfaced to the app.
 *
 * @param emailAddress {@code null} when the profile has none recorded
 */
public record ContactSnapshot(String phoneNumber, String emailAddress) {}
