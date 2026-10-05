package com.sfbank.bayanati.operator.domain;

import java.util.Optional;
import java.util.UUID;

/** Reads one artifact's bytes back for an operator, and authorises the read while doing it. */
public interface OperatorImageRepository {

  /**
   * The profile's own artifact, or empty.
   *
   * <p><strong>Both ids are load-bearing and neither is a filter added for tidiness.</strong>
   * Keying on {@code artifactRefId} alone would let an operator who may view ANY profile walk
   * artifact ids across other customers — the real vulnerability class on this endpoint, and
   * unrelated to how the URL is addressed. The implementation must therefore prove ownership in the
   * SAME statement that reads the bytes, never in a later {@code if}.
   *
   * <p>Empty covers every absence with one answer, so the caller cannot accidentally leak which one
   * it was: no such artifact, an artifact belonging to a different profile, or a row whose body is
   * NULL (never stored, as AD-004 leaves the capture frames, or purged by {@code
   * app.purge_abandoned_artifacts()}). A CHECKSUM MISMATCH is not an absence and must NOT be folded
   * in here — {@code app.artifact_read()} raises for it, and that exception is meant to reach the
   * client as a 500 rather than be rendered as "no image".
   */
  Optional<OperatorImage> find(UUID profileId, UUID artifactRefId);
}
