package com.sfbank.bayanati.reference.domain;

/**
 * One {@code ref.reference_item} row's display fields, for a caller that needs more than a boolean
 * {@link ReferenceCatalog#exists} check — e.g. rendering an internal reason label, or reading the
 * rejection-reason customer-facing message out of {@code extra}.
 *
 * @param extraJson the raw {@code extra} jsonb column as text, or {@code null} if the item carries
 *     none. Left unparsed here — this port stays a plain read, not a JSON schema for every list's
 *     {@code extra} shape, which differs per list (a UI marker for most, a customer-facing message
 *     pair for {@code rejection_reason}).
 */
public record ReferenceItemDetail(String labelAr, String labelEn, String extraJson) {}
