package com.sfbank.bayanati.reference.domain;

import java.time.Instant;

/**
 * One list's row in {@code GET /api/v1/reference/manifest} (AD-002f §5.1) — the currently published
 * version's metadata, not its items (those are only in the per-list document).
 *
 * @param contentHash lowercase hex SHA-256, i.e. {@code ref.reference_list_version.content_hash}
 * @param rootItemCode the declared cascade root (V0049), {@code null} for a flat list
 * @param rootCountryVersion paired with {@code rootItemCode} — see V0049; {@code null} for a flat
 *     list
 */
public record ManifestListEntry(
    String listCode,
    int version,
    int itemCount,
    String contentHash,
    boolean isHierarchical,
    String rootItemCode,
    Integer rootCountryVersion,
    Instant publishedAt) {}
