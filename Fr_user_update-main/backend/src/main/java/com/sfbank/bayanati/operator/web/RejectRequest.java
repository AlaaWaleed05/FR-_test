package com.sfbank.bayanati.operator.web;

/**
 * @param internalNote optional except for {@code REJ-07}, which mandates it (V0009 CHECK,
 *     re-validated in the service for a clean 400).
 */
public record RejectRequest(String reasonCode, String internalNote) {}
