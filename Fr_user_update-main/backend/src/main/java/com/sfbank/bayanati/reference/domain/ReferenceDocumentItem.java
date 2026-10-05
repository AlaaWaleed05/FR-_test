package com.sfbank.bayanati.reference.domain;

/**
 * One {@code ref.reference_item} row, shaped for {@link ReferenceDocumentGenerator} — exactly the
 * per-item field set AD-002f requirement (d) names (PROJECT_PLAN.md), which is already {@code
 * ref.reference_item}'s own column set (V0011 + V0038): no schema change was needed to carry it.
 *
 * @param extraJson the raw {@code extra::text} value already read from Postgres (valid JSON, or
 *     {@code null}), embedded into the generated document verbatim rather than re-parsed — see
 *     {@link ReferenceDocumentGenerator}'s Javadoc for why that is safe here
 */
public record ReferenceDocumentItem(
    String itemCode,
    String parentCode,
    String labelAr,
    String labelEn,
    String searchAr,
    String searchEn,
    int sortOrdinal,
    boolean isActive,
    String extraJson) {}
