package com.sfbank.bayanati.otpverification.web;

/**
 * @param correctedEmailAddress non-{@code null} only when the customer corrected a mistyped email
 *     address before tapping resend (customer.md Stage 2 "Corrections", S4-06/BL-012). Only valid
 *     alongside {@code channel="email"} — changing the phone number is out of scope here; the
 *     journey routes that back to Stage 1b instead.
 *     <p><strong>Write scope of this unauthenticated endpoint (S4-06, BL-012):</strong> {@code POST
 *     /api/v1/otp/resend} is keyed only by {@code profileId}, with no session or channel ownership
 *     proof beyond possessing that id — the same "possession of {@code profileId} = control of that
 *     profile" posture stages 3-6's data-entry endpoints already operate under for
 *     name/address/national-number writes. Before this field existed, a holder of a {@code
 *     profileId} could only trigger a code to the address already on file; this field lets them
 *     overwrite {@code app.profile_customer_data.email_address} itself, redirecting every later
 *     journey email (the submission notice, status transitions) to an address of their choosing.
 *     Accepted under the same posture as the rest of this endpoint family, not a new exposure class
 *     — {@code profileId} is a random UUID never guessable from an account number, and every
 *     correction is permanently audited (see {@code email_address_corrected}).
 */
public record OtpResendRequest(String profileId, String channel, String correctedEmailAddress) {}
