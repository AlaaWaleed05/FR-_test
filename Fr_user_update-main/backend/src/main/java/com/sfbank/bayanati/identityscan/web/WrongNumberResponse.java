package com.sfbank.bayanati.identityscan.web;

/** {@code blockedUntil} is non-null only when this call also exhausted the stage 8 budget. */
public record WrongNumberResponse(String profileId, String blockedUntil) {}
