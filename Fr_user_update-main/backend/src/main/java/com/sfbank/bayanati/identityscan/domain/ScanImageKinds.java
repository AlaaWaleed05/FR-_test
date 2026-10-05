package com.sfbank.bayanati.identityscan.domain;

import com.sfbank.bayanati.uqudo.domain.ParsedImage;
import java.util.List;
import java.util.Set;

/**
 * The artifact kinds Stage 9's review screen may fetch — the whitelist, not a filter applied later.
 * customer.md Stage 9 shows "the Civil Registry data, the document image, and the extracted
 * portrait", and this is that list made explicit.
 *
 * <p>What is deliberately NOT here, and why:
 *
 * <ul>
 *   <li>{@code doc_front_frame}/{@code doc_back_frame} — the raw capture frames. AD-004 stores no
 *       bytes for them at all (docs/components/persistence.md: roughly doubles the volume for no
 *       evidentiary gain), so serving them could only ever return nothing.
 *   <li>{@code face_audit_trail} — Stage 10's liveness evidence. It exists for the operator and the
 *       audit trail, not for the customer, and Stage 9 is before it in any case.
 *   <li>{@code signature} and {@code salary_certificate} — the customer supplied these; they are
 *       not identity evidence to review, and they belong to other stages.
 * </ul>
 *
 * <p>An allowlist rather than a denylist on purpose: a future artifact kind added to {@code
 * app.artifact_ref_kind_check} becomes fetchable only when someone decides it should be, not by
 * default.
 */
public final class ScanImageKinds {

  /** The Civil Registry's own photograph of the citizen, decoded and stored at S3-14. */
  public static final String PORTRAIT_REGISTRY = "portrait_registry";

  private static final Set<String> CUSTOMER_VIEWABLE =
      Set.of(
          ParsedImage.DOC_FRONT,
          ParsedImage.DOC_BACK,
          ParsedImage.PORTRAIT_UQUDO,
          PORTRAIT_REGISTRY);

  private ScanImageKinds() {}

  public static boolean isCustomerViewable(String kind) {
    return kind != null && CUSTOMER_VIEWABLE.contains(kind);
  }

  /** Sorted so the wire order is stable, which keeps the response comparable between calls. */
  public static List<String> customerViewable() {
    return CUSTOMER_VIEWABLE.stream().sorted().toList();
  }
}
