package com.sfbank.bayanati.reference.domain;

import java.util.List;

/**
 * One list version's full content, shaped for {@link ReferenceDocumentGenerator} — the source data
 * for the document served at {@code GET /api/v1/reference/lists/{listCode}/{version}} (AD-002f
 * §5.1; that endpoint itself is out of scope this session).
 *
 * @param itemCount {@code ref.reference_list_version.item_count}, carried as its own field rather
 *     than recomputed from {@code items.size()} — the served document states what the publisher
 *     recorded, not what the generator derives, so the two can be compared rather than always
 *     trivially agreeing
 * @param rootItemCode the declared cascade root (V0049), {@code null} for a flat list
 * @param items already ordered by the caller — the generator does not sort; ordering is by {@code
 *     item_code COLLATE "C"}, matching every existing seed migration's canonicalisation order
 */
public record ReferenceListDocumentInput(
    String listCode,
    int version,
    int itemCount,
    String nameAr,
    String nameEn,
    boolean isHierarchical,
    String rootItemCode,
    List<ReferenceDocumentItem> items) {}
