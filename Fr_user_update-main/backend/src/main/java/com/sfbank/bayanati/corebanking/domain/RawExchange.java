package com.sfbank.bayanati.corebanking.domain;

/**
 * The raw bytes of one core-banking HTTP exchange, kept so {@code AccountCheckService} can store
 * the {@code omni_check_request} / {@code omni_check_response} audit artifacts byte-identical
 * (PROJECT_PLAN.md Architecture: "the core-banking {@code CheckAccount} request/response" is a
 * retained raw artifact).
 *
 * <p>Null from the stub — there is no real exchange to preserve, and inventing one would put a
 * fabricated "raw response" into the audit trail (the reason S3-01 deliberately wrote no
 * artifacts).
 *
 * @param requestBody the exact JSON request bytes sent, never re-encoded
 * @param responseBody the exact response bytes received; may be empty but not null when an exchange
 *     happened
 * @param responseMediaType the response {@code Content-Type} as received (e.g. {@code
 *     application/json}, or {@code text/html} for a GlassFish error page), or null if none
 * @param httpStatus the HTTP status code of the response
 */
public record RawExchange(
    byte[] requestBody, byte[] responseBody, String responseMediaType, int httpStatus) {}
