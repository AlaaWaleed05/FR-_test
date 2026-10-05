package com.sfbank.bayanati.operator.domain;

import java.time.Instant;
import java.util.List;

/**
 * Everything operator.md's "single profile view" section lists. {@code scanResult}/{@code
 * faceResult}/{@code registryResult} are {@code null} until the profile reaches the corresponding
 * stage — "incomplete profiles are visible" applies here too, not only to the list.
 *
 * <p>Carried a per-operator {@code canApprove} boolean (BL-013) until AD-013 removed the four-eyes
 * rule on 2026-09-13. Nothing computes approve-eligibility for the client any more: the back office
 * gates the button on the operator's own role and the profile's status, and {@code
 * OperatorReviewService#requireOperatorLevel} is the server-side enforcement.
 *
 * <p>{@code salaryCertificateState} is resolved here rather than in the browser (BL-122), because
 * the back office is not the place to infer a customer's state from raw columns — the same rule
 * AD-002a and R-052 state for the mobile client. It is informative only and gates nothing.
 *
 * <p>{@code editableFields} (BL-135, AD-015) is the same idea applied to editing: WHICH fields an
 * operator may key is DERIVED PER PROFILE by {@code EditableFieldPolicy} — a Sudan address has no
 * free-text state to edit, a scanned birth city supersedes the customer's own — and the browser is
 * told the answer rather than re-deriving it. It arrives EMPTY from the repository and is filled in
 * by {@code OperatorProfileViewService}: the derivation is business logic and belongs nowhere near
 * a {@code jdbc} package. It is presentation only; {@code FieldEditService} re-derives the same set
 * on the write path, because a missing «تعديل» chip is never the control.
 */
public record ProfileDetail(
    String profileId,
    String referenceNumber,
    String branchCode,
    String accountNumber,
    String status,
    String provenance,
    Instant submittedAt,
    Instant createdAt,
    Instant lastActivityAt,
    CustomerDataView customerData,
    List<ChannelStateView> channels,
    ScanResultView scanResult,
    FaceResultView faceResult,
    RegistryResultView registryResult,
    List<ArtifactRefView> artifacts,
    List<StatusHistoryEntryView> statusHistory,
    SalaryCertificateState salaryCertificateState,
    List<String> editableFields) {

  /** Returns a copy carrying the derived editable set — see this record's Javadoc. */
  public ProfileDetail withEditableFields(List<String> editableFields) {
    return new ProfileDetail(
        profileId,
        referenceNumber,
        branchCode,
        accountNumber,
        status,
        provenance,
        submittedAt,
        createdAt,
        lastActivityAt,
        customerData,
        channels,
        scanResult,
        faceResult,
        registryResult,
        artifacts,
        statusHistory,
        salaryCertificateState,
        editableFields);
  }
}
