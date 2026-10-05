package com.sfbank.bayanati.operator.web;

import com.sfbank.bayanati.operator.domain.ArtifactRefView;
import com.sfbank.bayanati.operator.domain.CustomerDataView;
import com.sfbank.bayanati.operator.domain.FaceResultView;
import com.sfbank.bayanati.operator.domain.ProfileDetail;
import com.sfbank.bayanati.operator.domain.RegistryResultView;
import com.sfbank.bayanati.operator.domain.SalaryCertificateState;
import com.sfbank.bayanati.operator.domain.ScanResultView;
import com.sfbank.bayanati.operator.domain.StatusHistoryEntryView;
import java.time.Instant;
import java.util.List;

/**
 * operator.md's "single profile view". Every nested view type except {@link ChannelStateResponse}
 * (which resolves {@code MessageChannel}/{@code ChannelState} to their wire values, matching this
 * codebase's existing convention — see {@code submission.web.SubmissionResponse}) is a plain data
 * projection with no enum or domain-internal typing left to translate, so it is exposed as-is
 * rather than mirrored field-by-field into a second, driftable type.
 *
 * <p>{@code editableFields} carries {@code operator.domain.EditableField} NAMES — the same strings
 * the per-field edit endpoint takes as its {@code fieldKey} path variable, so the browser sends
 * back exactly what it was given and no second spelling can enter. It is DERIVED PER PROFILE
 * (BL-135, AD-015, AD-021): the same profile shape does not produce the same set, because a
 * non-Sudan address makes state and locality free text while a Sudan one leaves them list-picked.
 */
public record ProfileDetailResponse(
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
    List<ChannelStateResponse> channels,
    ScanResultView scanResult,
    FaceResultView faceResult,
    RegistryResultView registryResult,
    List<ArtifactRefView> artifacts,
    List<StatusHistoryEntryView> statusHistory,
    SalaryCertificateState salaryCertificateState,
    List<String> editableFields) {

  public static ProfileDetailResponse from(ProfileDetail detail) {
    return new ProfileDetailResponse(
        detail.profileId(),
        detail.referenceNumber(),
        detail.branchCode(),
        detail.accountNumber(),
        detail.status(),
        detail.provenance(),
        detail.submittedAt(),
        detail.createdAt(),
        detail.lastActivityAt(),
        detail.customerData(),
        detail.channels().stream().map(ChannelStateResponse::from).toList(),
        detail.scanResult(),
        detail.faceResult(),
        detail.registryResult(),
        detail.artifacts(),
        detail.statusHistory(),
        detail.salaryCertificateState(),
        detail.editableFields());
  }
}
