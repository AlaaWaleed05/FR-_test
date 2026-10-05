package com.sfbank.bayanati.operator.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What the operator image endpoint is allowed to serve — two allow-lists, both deliberately closed
 * rather than open, and the second one scoped PER KIND rather than shared.
 *
 * <p><strong>Kinds</strong> are wayfinder ticket 02's five plus the salary certificate: the
 * identity document front, the portrait Uqudo extracted from it, the Civil Registry's own
 * photograph, the liveness audit trail, the signature, and the customer's income evidence.
 * Everything else 404s. Two exclusions are worth naming because neither is an oversight:
 *
 * <ul>
 *   <li>{@code doc_front_frame}/{@code doc_back_frame} carry NO bytes — AD-004 deliberately stores
 *       none — yet they declare a {@code content_type} and a {@code byte_size} (one was measured
 *       live at 1.7 MB with a NULL body). Offering them would guarantee a broken fetch on the
 *       largest row in the list, which is exactly what BL-075 requirement (a) exists to prevent.
 *   <li>{@code doc_back} DOES carry bytes and is still excluded. That is ticket 02's explicit
 *       decision ("the set is the set"), not an omission.
 * </ul>
 *
 * <p><strong>{@code salary_certificate} was admitted at S8-24 by product-owner decision</strong>,
 * closing BL-136. It had been excluded by an arithmetic slip rather than a ruling: ticket 02's own
 * premise counted six byte-carrying kinds when there are seven, so the seventh was never weighed.
 * It is deliberately LAST in every ordering the client renders — identity evidence first, income
 * evidence after — because it answers a different question from the other five.
 *
 * <p><strong>Content types</strong> are pinned from an allow-list rather than echoed from the
 * column, because {@code app.artifact_ref.content_type} is DECLARED by whoever stored the row and
 * can lie — the civil-registry stub stores a 32-byte ASCII string under {@code image/jpeg}. The
 * allow-list is the WHOLE control: the response also carries {@code X-Content-Type-Options:
 * nosniff}, so sniffing the bytes to second-guess the column would contradict the very header that
 * makes pinning meaningful. A declared type outside the list is refused, never downgraded to {@code
 * application/octet-stream}, which in an {@code <img>} is a broken image with extra steps.
 *
 * <p><strong>The type allow-list is per kind, and that is a security property rather than
 * tidiness.</strong> Only {@code salary_certificate} may be a PDF, because only it can be uploaded
 * as one ({@code SalaryCertificateService.ALLOWED_CONTENT_TYPES}). A single shared list containing
 * {@code application/pdf} would let ANY artifact be served as a PDF the moment its {@code
 * content_type} column said so — and the whole reason this class exists is that the column cannot
 * be trusted. The five identity kinds are images, are only ever written as images, and stay
 * refusable to anything else.
 *
 * <p>Pure policy: no Spring context, no database, no clock, no network.
 */
public final class OperatorImagePolicy {

  private static final String SALARY_CERTIFICATE = "salary_certificate";

  /**
   * Every type this system stores for the five identity kinds: Uqudo and the Civil Registry return
   * JPEG, a drawn signature is a PNG and an uploaded one a JPEG (S5-08 measured both).
   */
  private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png");

  /**
   * The salary certificate's own three, mirroring {@code
   * salarycertificate.service.SalaryCertificateService.ALLOWED_CONTENT_TYPES} — the customer picks
   * the file, and {@code salary_certificate_field.dart} offers {@code jpg}, {@code jpeg}, {@code
   * png} and {@code pdf}, storing a picked PDF byte-identical. A payslip is at least as likely to
   * be a PDF as a photograph, so refusing PDFs here would 404 the commonest real certificate.
   */
  private static final Set<String> SALARY_CERTIFICATE_TYPES =
      Set.of("image/jpeg", "image/png", "application/pdf");

  /** Ticket 02's five, plus BL-136's seventh kind. */
  private static final Set<String> VIEWABLE_KINDS =
      Set.of(
          "doc_front",
          "portrait_uqudo",
          "portrait_registry",
          "face_audit_trail",
          "signature",
          SALARY_CERTIFICATE);

  private OperatorImagePolicy() {}

  /** Whether an operator may fetch an artifact of this kind at all. */
  public static boolean isViewableKind(String kind) {
    return kind != null && VIEWABLE_KINDS.contains(kind);
  }

  /**
   * The same six, for a read path that wants to apply the allow-list in SQL rather than refuse a
   * row it has already paid to fetch. Exposed so the allow-list keeps ONE definition: a repository
   * that re-listed the kinds in its own {@code WHERE} would be a second source of truth, and the
   * two would drift the first time a kind is added.
   */
  public static Set<String> viewableKinds() {
    return VIEWABLE_KINDS;
  }

  /**
   * The content type to SERVE, given the artifact's kind and what its row declares. Matching is
   * case-insensitive and ignores any parameters ({@code image/jpeg; charset=x}), but the value
   * returned is always this class's own canonical spelling — never the column's — so a stored type
   * can influence which of a few fixed strings is chosen and nothing else.
   *
   * <p>The kind selects WHICH allow-list applies. {@code application/pdf} is reachable only through
   * {@code salary_certificate}; every other kind is images or nothing.
   *
   * @return empty when the kind is not viewable, or the declared type is absent, unparseable, or
   *     outside that kind's allow-list
   */
  public static Optional<String> pinContentType(String kind, String declaredContentType) {
    if (!isViewableKind(kind) || declaredContentType == null) {
      return Optional.empty();
    }
    Set<String> allowed = SALARY_CERTIFICATE.equals(kind) ? SALARY_CERTIFICATE_TYPES : IMAGE_TYPES;
    String withoutParameters = declaredContentType.split(";", 2)[0].strip();
    String normalised = withoutParameters.toLowerCase(Locale.ROOT);
    return allowed.contains(normalised) ? Optional.of(normalised) : Optional.empty();
  }
}
