package com.sfbank.bayanati.auth.web;

/** {@code GET /api/v1/auth/me} response body. */
public record MeResponse(
    String username, String displayName, String role, boolean mustChangePassword) {}
