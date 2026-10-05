package com.sfbank.bayanati.contactchannels.web;

/**
 * @param channel the wire value ({@code sms}/{@code whatsapp}/{@code email})
 * @param state {@code unverified} (challenged) or {@code declined} — never {@code verified} at this
 *     stage
 * @param maskedDestination e.g. {@code "•••• 4821"} or {@code "a•••@gmail.com"} — never the OTP
 *     code, and never the raw destination
 */
public record ChannelInfo(String channel, String state, String maskedDestination) {}
