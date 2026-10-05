package com.sfbank.bayanati.reference.domain;

import java.time.Instant;

/**
 * One row of {@code ref.reference_list_document} (V0048) — the exact bytes served at {@code GET
 * /api/v1/reference/lists/{listCode}/{version}}, already generated and hashed by {@link
 * ReferenceDocumentGenerator} at publication time. Served verbatim, never reconstructed per
 * request.
 *
 * @param sha256Hex lowercase hex SHA-256 of {@code documentBytes} — identical to {@code
 *     ref.reference_list_version.content_hash} once published (proven by {@code
 *     ReferenceDocumentPublisherIntegrationTest})
 */
public record StoredDocument(byte[] documentBytes, String sha256Hex, Instant publishedAt) {}
