package com.sfbank.bayanati.identityscan.domain;

/**
 * One stored identity artifact, read back for display: its kind, the content type it was stored
 * with, and the bytes themselves — checksum-verified on the way out by {@code app.artifact_read()}
 * (V0054), which raises rather than returning bytes that no longer match what was stored.
 *
 * <p>Bytes are stored byte-identical to what Uqudo or the Civil Registry supplied and are never
 * re-encoded, so {@code contentType} is the one recorded at write time rather than anything guessed
 * on read.
 */
public record ScanArtifact(String kind, String contentType, byte[] bytes) {}
