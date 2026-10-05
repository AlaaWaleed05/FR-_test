package com.sfbank.bayanati.operator.domain;

/**
 * One {@code app.artifact_ref} row, metadata only — "Images are not returned as bytes. Return
 * references and metadata" (S4-01 task). That still holds: this view never selects {@code body}.
 * What changed at BL-075 is that the bytes are now REACHABLE, by id, from a separate endpoint —
 * {@code GET /api/v1/operator/profiles/{profileId}/artifacts/{artifactId}} — rather than being
 * unaddressable. R-046 is CLOSED (2026-09-13): the addressing scheme is an ordinary
 * cookie-authenticated GET rendered into an {@code <img>}, not a signed URL and not a blob fetch.
 *
 * @param artifactRefId the row's own id, and the only way to address its bytes. Load-bearing rather
 *     than informational: {@code app.artifact_ref} is UNIQUE per (cycle, kind), so a profile with
 *     several identity cycles has several committed rows of the same kind and {@code kind} alone
 *     cannot tell a superseded scan from the one that replaced it
 * @param label the origin label operator.md requires ("Civil Registry", "Uqudo — passport", "Uqudo
 *     — national ID"), derived from {@code kind} and, for {@code portrait_uqudo}, the scan's
 *     document type
 * @param storageKey DEAD. AD-004's closure narrowed its meaning to "reserved for R-046's still-open
 *     addressing scheme"; that scheme turned out to need no stored key, since the artifact's own id
 *     addresses it. Nothing reads this. The column is deliberately not dropped — that is its own
 *     migration and its own review — so it is recorded as dead here instead, to stop the next
 *     reader inferring a meaning from its presence.
 * @param contentType what the row DECLARES, which can lie — the operator image endpoint pins what
 *     it serves from its own allow-list rather than trusting this value
 * @param sha256Hex the stored checksum, lower-case hex
 */
public record ArtifactRefView(
    String artifactRefId,
    String kind,
    String label,
    String storageKey,
    String contentType,
    long byteSize,
    String sha256Hex) {}
