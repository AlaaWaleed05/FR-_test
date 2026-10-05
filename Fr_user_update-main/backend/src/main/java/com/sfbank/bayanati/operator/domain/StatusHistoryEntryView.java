package com.sfbank.bayanati.operator.domain;

import java.time.Instant;

/**
 * One {@code app.profile_status_history} row, with the rejection-reason code (if any) resolved to
 * its labels — "the operator sees the real code" (operator.md). {@code reasonLabelAr}/{@code
 * reasonLabelEn} are {@code null} whenever {@code reasonCode} is.
 */
public record StatusHistoryEntryView(
    int seq,
    String fromStatus,
    String toStatus,
    Instant occurredAt,
    String actorKind,
    String actorId,
    String reasonCode,
    String reasonLabelAr,
    String reasonLabelEn,
    String internalNote,
    boolean isManualCompletion) {}
