package com.sfbank.bayanati.otpverification.web;

public record OtpVerifyRequest(String profileId, String channel, String code) {}
