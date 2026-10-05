package com.sfbank.bayanati.dataentry.web;

/**
 * Journey Stage 6 (docs/journeys/customer.md) — work address and employer. Same cascade rule as
 * {@link Stage5Request}, minus a house number. The optional salary-certificate attachment has its
 * own endpoint (S4-06, BL-022) — {@code POST /api/v1/salary-certificate} ({@code
 * salarycertificate.web.SalaryCertificateController}) — rather than a field on this record: it
 * gates nothing this stage's own submit needs to know about, and the mobile client captures and
 * uploads it independently of the Stage 6 form fields.
 *
 * <p><strong>That last clause was FALSE when written and became true on 2026-09-12.</strong> The
 * backend endpoint shipped at S4-06, the mobile caller only at S8-14 (BL-105), so for that period
 * this comment asserted an upload path that did not exist — and, being a comment, it is what
 * stopped anyone checking. Verified against {@code
 * mobile/lib/core/dataentry/data_entry_repository.dart}'s {@code uploadSalaryCertificate} on
 * 2026-09-13. Storage model ratified by AD-016: a profile artifact, like the signature.
 *
 * <p><strong>The BYTES still travel on that separate endpoint; one bit of it now travels here
 * (BL-122).</strong> {@code salaryCertificateAttached} is not the upload's outcome — it is the
 * customer's CLAIM that a file was attached at all, which is true from the moment they pick one and
 * independent of whether the upload ever succeeded. That distinction is what makes this record the
 * right carrier despite {@code stage6_screen.dart} sending this request BEFORE its Next-time upload
 * retry: a result sent from here would be stale by construction, whereas a claim cannot be. It also
 * rides Stage 6's offline queue and {@code flushPending} replay, which no separate call would.
 *
 * @param countryListVersion the pinned {@code country} list version (S4-04, AD-002f §5.3); {@code
 *     null} falls back to the server's current version
 * @param adminDivisionListVersion the pinned {@code admin_division} list version, used only when
 *     {@code countryCode} is {@code "SD"}; same fallback
 * @param salaryCertificateAttached whether the customer has a salary certificate attached at Stage
 *     6 (BL-122). <strong>Boxed deliberately.</strong> A primitive {@code boolean} would arrive
 *     {@code false} from any client built before this field existed and silently assert "the
 *     customer declined" — the exact misclassification BL-122 exists to remove. {@code null} means
 *     "this client did not say" and must stay distinguishable from a real {@code false}.
 */
public record Stage6Request(
    String profileId,
    String employer,
    String countryCode,
    String stateCode,
    String stateText,
    String localityCode,
    String localityText,
    String city,
    String area,
    String street,
    String block,
    Integer countryListVersion,
    Integer adminDivisionListVersion,
    Boolean salaryCertificateAttached) {}
