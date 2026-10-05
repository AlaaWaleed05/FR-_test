package com.sfbank.bayanati.identityscan.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The Stage 9 image allowlist — what the review screen may fetch, and what it may never. */
class ScanImageKindsTest {

  @ParameterizedTest
  @ValueSource(strings = {"doc_front", "doc_back", "portrait_uqudo", "portrait_registry"})
  void theFourReviewImagesAreViewable(String kind) {
    assertTrue(ScanImageKinds.isCustomerViewable(kind));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // Never stored with bytes at all (AD-004): serving them could only return nothing.
        "doc_front_frame",
        "doc_back_frame",
        // Operator and audit evidence, or another stage's artifact — not Stage 9 review material.
        "face_audit_trail",
        "signature",
        "salary_certificate",
        // Audit-store kinds, which are not app.artifact_ref rows at all.
        "civil_registry_request",
        "civil_registry_response",
        "uqudo_scan_jws"
      })
  void everythingElseIsRefused(String kind) {
    assertFalse(ScanImageKinds.isCustomerViewable(kind));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "DOC_FRONT", "doc-front", "../doc_front", "doc_front "})
  void nothingIsCoercedIntoMatching(String kind) {
    // Exact match only: no trimming, no case folding, no path-ish tolerance. The kind reaches this
    // from a URL path segment, so anything looser is an invitation.
    assertFalse(ScanImageKinds.isCustomerViewable(kind));
  }

  @Test
  void theViewableListIsStableAndSortedSoTheWireOrderDoesNotChangeBetweenCalls() {
    List<String> kinds = ScanImageKinds.customerViewable();

    assertEquals(List.of("doc_back", "doc_front", "portrait_registry", "portrait_uqudo"), kinds);
    assertEquals(kinds, ScanImageKinds.customerViewable());
  }

  @Test
  void everyListedKindIsAlsoAcceptedByTheGuardSoTheTwoCannotDriftApart() {
    ScanImageKinds.customerViewable()
        .forEach(kind -> assertTrue(ScanImageKinds.isCustomerViewable(kind), kind));
  }
}
