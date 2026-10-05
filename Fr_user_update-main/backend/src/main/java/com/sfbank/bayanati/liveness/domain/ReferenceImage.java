package com.sfbank.bayanati.liveness.domain;

import java.util.UUID;

/**
 * The accepted identity cycle's portrait bytes, read (checksum-verified, via {@code
 * app.artifact_read()}) from {@code app.artifact_ref} by {@code issueFaceSessionToken} to build a
 * fresh Uqudo Face Session. AD-004 closed at S5-06 — see {@code LivenessRepository
 * #currentAcceptedCycleReferenceImage}.
 */
public record ReferenceImage(UUID cycleId, byte[] imageBytes) {}
