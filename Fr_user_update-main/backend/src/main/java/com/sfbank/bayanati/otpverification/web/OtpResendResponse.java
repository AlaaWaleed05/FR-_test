package com.sfbank.bayanati.otpverification.web;

/**
 * @param secondsUntilAllowed only meaningful when {@code outcome} is {@code TOO_SOON}
 */
public record OtpResendResponse(
    String channel, String outcome, String maskedDestination, long secondsUntilAllowed) {}
