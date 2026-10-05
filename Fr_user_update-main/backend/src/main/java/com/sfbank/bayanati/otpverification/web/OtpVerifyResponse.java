package com.sfbank.bayanati.otpverification.web;

/**
 * @param sessionBlockedUntilIso {@code null} unless the phone-verification session lock is active
 */
public record OtpVerifyResponse(
    String channel, String outcome, String state, String sessionBlockedUntilIso) {}
