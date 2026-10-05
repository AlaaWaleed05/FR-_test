package com.sfbank.bayanati.reference.domain;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/v1/reference/manifest}'s content (AD-002f §5.1) — mutable, revalidated on every
 * request ({@code Cache-Control: no-cache}), unlike the immutable per-list documents.
 *
 * @param catalogHash {@link ManifestHasher#catalogHash}'s output over {@code lists} — the
 *     manifest's own ETag, computed fresh per request rather than stored (there is no "manifest
 *     document" table; only per-list documents are stored, per V0048)
 */
public record ReferenceManifest(
    String catalogHash, Instant generatedAt, List<ManifestListEntry> lists) {}
