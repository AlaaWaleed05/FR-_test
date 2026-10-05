package com.sfbank.bayanati.auth.web;

/** {@code POST /api/v1/auth/password} request body. */
public record PasswordChangeRequest(String currentPassword, String newPassword) {}
