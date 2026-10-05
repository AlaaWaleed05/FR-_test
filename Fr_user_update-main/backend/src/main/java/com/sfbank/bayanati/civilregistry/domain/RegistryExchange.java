package com.sfbank.bayanati.civilregistry.domain;

/**
 * The raw bytes of one Civil Registry HTTP exchange, kept so {@code IdentityScanService} can store
 * the {@code civil_registry_request} / {@code civil_registry_response} audit artifacts
 * byte-identical (PROJECT_PLAN.md Architecture: the raw Civil Registry response is a retained
 * artifact; BL-030).
 *
 * <p>Deliberately a twin of {@code corebanking.domain.RawExchange}, not a reuse of it: CLAUDE.md's
 * quarantine rule keeps each external system's package free of the others' types, so the four
 * fields are repeated here rather than imported across the boundary.
 *
 * <p>Null from the stub — there is no real exchange to preserve, and inventing one would put a
 * fabricated "raw response" into the audit trail.
 *
 * @param requestBody the exact JSON request bytes sent, never re-encoded
 * @param responseBody the exact response bytes received; may be empty but not null when a response
 *     arrived; null when nothing came back (no response at all)
 * @param responseMediaType the response {@code Content-Type} as received (e.g. {@code
 *     application/json}, or {@code text/html} for the GlassFish 400 page), or null if none
 * @param httpStatus the HTTP status code of the response, or 0 when nothing came back
 */
public record RegistryExchange(
    byte[] requestBody, byte[] responseBody, String responseMediaType, int httpStatus) {}
