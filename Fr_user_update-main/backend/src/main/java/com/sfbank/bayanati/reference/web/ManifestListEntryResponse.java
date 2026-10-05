package com.sfbank.bayanati.reference.web;

import java.time.Instant;

/**
 * One list's row in the {@code GET /api/v1/reference/manifest} JSON body (AD-002f §5.1). Adds
 * {@code documentPath} to {@code reference.domain.ManifestListEntry} — a URL, which is a web-layer
 * concern the domain record deliberately does not know about.
 */
public record ManifestListEntryResponse(
    String listCode,
    int version,
    int itemCount,
    String contentHash,
    boolean isHierarchical,
    String rootItemCode,
    Integer rootCountryVersion,
    Instant publishedAt,
    String documentPath) {}
