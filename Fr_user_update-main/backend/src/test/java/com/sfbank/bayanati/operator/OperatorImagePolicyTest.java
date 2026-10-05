package com.sfbank.bayanati.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sfbank.bayanati.operator.domain.OperatorImagePolicy;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** BL-075's two allow-lists. Plain JUnit — no Spring context, no database, no clock, no network. */
class OperatorImagePolicyTest {

  @Test
  void theFiveIdentityKindsWayfinderTicket02ChoseAreViewable() {
    assertTrue(OperatorImagePolicy.isViewableKind("doc_front"));
    assertTrue(OperatorImagePolicy.isViewableKind("portrait_uqudo"));
    assertTrue(OperatorImagePolicy.isViewableKind("portrait_registry"));
    assertTrue(OperatorImagePolicy.isViewableKind("face_audit_trail"));
    assertTrue(OperatorImagePolicy.isViewableKind("signature"));
  }

  /**
   * The size is asserted so that admitting a kind cannot happen by accident — it takes an edit here
   * as well as one to the set. Six since S8-24: ticket 02's five, plus BL-136's salary certificate.
   */
  @Test
  void nothingElseIsViewable() {
    assertEquals(6, OperatorImagePolicy.viewableKinds().size());
    assertEquals(
        Set.of(
            "doc_front",
            "portrait_uqudo",
            "portrait_registry",
            "face_audit_trail",
            "signature",
            "salary_certificate"),
        OperatorImagePolicy.viewableKinds());
  }

  /**
   * AD-004 deliberately stores no bytes for the capture frames, yet they declare a content type and
   * a byte size — one was measured live at 1.7 MB with a NULL body, the largest row in the operator
   * attachments table. Offering them would guarantee a broken fetch on the most prominent row.
   */
  @Test
  void theBytelessCaptureFramesAreRefusedByKindNotMerelyByAbsentBytes() {
    assertFalse(OperatorImagePolicy.isViewableKind("doc_front_frame"));
    assertFalse(OperatorImagePolicy.isViewableKind("doc_back_frame"));
  }

  /**
   * {@code doc_back} carries real bytes and is still refused. That is ticket 02's explicit decision
   * ("the set is the set") rather than an omission, which is why it needs its own case: the
   * byte-less frames above prove nothing about a kind that has bytes and is refused anyway.
   */
  @Test
  void docBackCarriesBytesAndIsStillRefusedByDecision() {
    assertFalse(OperatorImagePolicy.isViewableKind("doc_back"));
  }

  /**
   * BL-136, closed at S8-24 by product-owner decision. This kind was refused until then, and NOT by
   * decision: ticket 02's own premise counted six byte-carrying kinds when there are seven, so the
   * seventh was never weighed. With the metadata attachments table gone since S8-23, refusing it
   * left the one document bearing on income invisible to an operator in every surface the system
   * has.
   */
  @Test
  void theSalaryCertificateIsViewable() {
    assertTrue(OperatorImagePolicy.isViewableKind("salary_certificate"));
  }

  @Test
  void anUnknownOrNullKindIsRefusedRatherThanThrowing() {
    assertFalse(OperatorImagePolicy.isViewableKind("portrait"));
    assertFalse(OperatorImagePolicy.isViewableKind(""));
    assertFalse(OperatorImagePolicy.isViewableKind(null));
  }

  @Test
  void theKindAllowListCannotBeMutatedByACaller() {
    Set<String> kinds = OperatorImagePolicy.viewableKinds();
    assertThrows(UnsupportedOperationException.class, () -> kinds.add("doc_back"));
  }

  @Test
  void theTwoStoredImageTypesArePinned() {
    assertEquals(
        Optional.of("image/jpeg"), OperatorImagePolicy.pinContentType("doc_front", "image/jpeg"));
    assertEquals(
        Optional.of("image/png"), OperatorImagePolicy.pinContentType("signature", "image/png"));
  }

  @Test
  void caseAndParametersAreNormalisedAndTheServedValueIsAlwaysThisClassesOwnSpelling() {
    assertEquals(
        Optional.of("image/jpeg"), OperatorImagePolicy.pinContentType("doc_front", "IMAGE/JPEG"));
    assertEquals(
        Optional.of("image/jpeg"),
        OperatorImagePolicy.pinContentType("doc_front", "image/jpeg; charset=utf-8"));
    assertEquals(
        Optional.of("image/png"), OperatorImagePolicy.pinContentType("signature", "  image/png  "));
    assertEquals(
        Optional.of("application/pdf"),
        OperatorImagePolicy.pinContentType("salary_certificate", "APPLICATION/PDF"));
  }

  /**
   * The type allow-list is scoped PER KIND, and this is the case that makes it worth the extra
   * parameter. Only {@code salary_certificate} can be uploaded as a PDF ({@code
   * SalaryCertificateService.ALLOWED_CONTENT_TYPES}), so only it may be served as one. A single
   * shared list containing {@code application/pdf} would let ANY artifact be served as a PDF the
   * moment its {@code content_type} column said so — and this class exists precisely because that
   * column is declared by whoever wrote the row and cannot be trusted.
   */
  @Test
  void onlyTheSalaryCertificateMayBeAPdf() {
    assertEquals(
        Optional.of("application/pdf"),
        OperatorImagePolicy.pinContentType("salary_certificate", "application/pdf"));

    for (String imageOnlyKind :
        new String[] {
          "doc_front", "portrait_uqudo", "portrait_registry", "face_audit_trail", "signature"
        }) {
      assertEquals(
          Optional.empty(),
          OperatorImagePolicy.pinContentType(imageOnlyKind, "application/pdf"),
          imageOnlyKind + " must never be served as a PDF, whatever its column declares");
    }
  }

  /** A certificate is still allowed to be a photograph -- the mobile picker offers both. */
  @Test
  void theSalaryCertificateMayAlsoBeAnImage() {
    assertEquals(
        Optional.of("image/jpeg"),
        OperatorImagePolicy.pinContentType("salary_certificate", "image/jpeg"));
    assertEquals(
        Optional.of("image/png"),
        OperatorImagePolicy.pinContentType("salary_certificate", "image/png"));
  }

  /**
   * A kind outside the allow-list pins nothing, whatever it declares.
   *
   * <p>Deliberately redundant with the repository's SQL filter, but NOT independent of it: the SQL
   * binds {@code viewableKinds()}, so both gates read the one set and adding a kind opens both with
   * a single edit. The redundancy buys defence against a future read path that forgets the filter,
   * and it saves de-TOASTing a body that would be thrown away — not a second decision. The gate
   * that genuinely takes a second, separate edit is the per-kind TYPE list above.
   */
  @Test
  void aRefusedKindPinsNothingEvenWithAPerfectlyGoodType() {
    assertEquals(Optional.empty(), OperatorImagePolicy.pinContentType("doc_back", "image/jpeg"));
    assertEquals(
        Optional.empty(), OperatorImagePolicy.pinContentType("doc_front_frame", "image/jpeg"));
    assertEquals(Optional.empty(), OperatorImagePolicy.pinContentType(null, "image/jpeg"));
  }

  /**
   * A declared type outside the list is REFUSED, not downgraded to {@code
   * application/octet-stream}. This endpoint renders into an {@code <img>}, where an octet-stream
   * is a broken image with extra steps — and the customer-facing Stage 9 endpoint's fallback
   * behaviour, which is right for a Flutter client rendering from bytes, would be wrong here.
   */
  @Test
  void anythingOutsideTheListIsRefusedNotDowngraded() {
    assertEquals(
        Optional.empty(), OperatorImagePolicy.pinContentType("doc_front", "application/pdf"));
    assertEquals(Optional.empty(), OperatorImagePolicy.pinContentType("doc_front", "text/html"));
    assertEquals(
        Optional.empty(), OperatorImagePolicy.pinContentType("doc_front", "image/svg+xml"));
    assertEquals(
        Optional.empty(),
        OperatorImagePolicy.pinContentType("doc_front", "application/octet-stream"));
    assertEquals(Optional.empty(), OperatorImagePolicy.pinContentType("doc_front", ""));
    assertEquals(Optional.empty(), OperatorImagePolicy.pinContentType("doc_front", null));
    // Not even the certificate takes anything beyond its own three.
    assertEquals(
        Optional.empty(), OperatorImagePolicy.pinContentType("salary_certificate", "text/html"));
  }

  /**
   * The allow-list is the WHOLE control, and this is the case that proves the scope of what it does
   * NOT do. Under {@code fru.civil-registry.client=stub}, {@code portrait_registry} is a 32-byte
   * ASCII string stored under a declared {@code image/jpeg}. The policy still pins {@code
   * image/jpeg}: it governs the DECLARED type, never the bytes. Sniffing them to second- guess the
   * column would contradict the {@code X-Content-Type-Options: nosniff} header that makes pinning
   * meaningful in the first place, so the browser renders a broken image and the operator sees that
   * something is wrong — which is the honest outcome for a stub.
   */
  @Test
  void aDeclaredTypeThatTheBytesDoNotMatchIsStillPinnedBecauseSniffingIsOutOfScope() {
    assertEquals(
        Optional.of("image/jpeg"),
        OperatorImagePolicy.pinContentType("portrait_registry", "image/jpeg"));
  }
}
