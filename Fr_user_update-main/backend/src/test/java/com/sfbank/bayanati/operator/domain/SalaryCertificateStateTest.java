package com.sfbank.bayanati.operator.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** BL-122 — the four answers an operator can be given about a missing salary certificate. */
class SalaryCertificateStateTest {

  @Test
  void committedArtifactIsPresent() {
    assertEquals(SalaryCertificateState.PRESENT, SalaryCertificateState.resolve(true, false, true));
  }

  @Test
  void claimWithNoArtifactIsAttachFailed() {
    assertEquals(
        SalaryCertificateState.ATTACH_FAILED, SalaryCertificateState.resolve(false, true, true));
  }

  @Test
  void stage6ArrivedWithNoClaimAndNoArtifactIsDeclined() {
    assertEquals(
        SalaryCertificateState.DECLINED, SalaryCertificateState.resolve(false, false, true));
  }

  @Test
  void noStage6IsNotReached() {
    assertEquals(
        SalaryCertificateState.NOT_REACHED, SalaryCertificateState.resolve(false, false, false));
  }

  /**
   * The distinction this whole backlog item exists for, stated as one assertion: the two profiles
   * differ in nothing an operator could otherwise see — no artifact either way — and must still
   * resolve to different answers.
   */
  @Test
  void declinedAndAttachFailedAreDistinguishableWithNoArtifactInEitherCase() {
    assertEquals(
        SalaryCertificateState.DECLINED, SalaryCertificateState.resolve(false, false, true));
    assertEquals(
        SalaryCertificateState.ATTACH_FAILED, SalaryCertificateState.resolve(false, true, true));
  }

  /**
   * A present artifact wins over a standing claim. This is what lets a certificate that arrives
   * after Stage 6 — customer.md's "the attachment lands whenever it can" — resolve itself, with no
   * second write needed to retire the claim.
   */
  @Test
  void artifactWinsOverAStandingClaim() {
    assertEquals(SalaryCertificateState.PRESENT, SalaryCertificateState.resolve(true, true, true));
  }

  /**
   * A claim with no Stage 6 data still reads as ATTACH_FAILED rather than NOT_REACHED: the claim
   * only exists because Stage 6 wrote it, so it is positive evidence the customer was asked, and it
   * outranks the weaker employer_name inference.
   */
  @Test
  void aClaimOutranksTheAbsenceOfStage6Data() {
    assertEquals(
        SalaryCertificateState.ATTACH_FAILED, SalaryCertificateState.resolve(false, true, false));
  }

  /**
   * A manually-completed profile still carries the CUSTOMER's decline — the regression test for a
   * version of {@code resolve} that got this wrong.
   *
   * <p>{@code app.profile.provenance = 'manual'} sounds like AD-015's per-field operator entry and
   * is not. Until S9-01 its only writer was {@code JdbcManualCompletionRepository}'s
   * manual-completion UPDATE, which marked a CUSTOMER-started profile an operator finished over the
   * counter; AD-022 deleted that writer, and BL-135 (S9-02) did not restore it — provenance is now
   * DERIVED by {@code app.derived_provenance()} (V0073), so the stored column stays legacy-only for
   * good and never gains the per-field meaning it never had. An earlier draft branched on it and
   * answered NOT_REACHED here, putting "has not reached this stage yet" on a submitted profile — a
   * claim about a future that never arrives — on exactly the profiles an operator reviews most.
   * Found by {@code @agent-reviewer}. {@code resolve} no longer takes provenance at all, so this
   * pins the behaviour rather than the parameter.
   */
  @Test
  void manualCompletionDoesNotTurnAGenuineDeclineIntoNotReached() {
    assertEquals(
        SalaryCertificateState.DECLINED, SalaryCertificateState.resolve(false, false, true));
  }
}
