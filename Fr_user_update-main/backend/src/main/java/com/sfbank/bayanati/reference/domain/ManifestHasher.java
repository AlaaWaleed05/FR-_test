package com.sfbank.bayanati.reference.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Computes the manifest's {@code catalogHash} (AD-002f §5.1: {@code "sha256 hex over the list
 * entries below"}) — the manifest's own ETag. Hand-rolled and deterministic, same reasoning as
 * {@link ReferenceDocumentGenerator}: two runs over the same rows must produce identical bytes, and
 * this hashes only the fields identifying "did anything in the catalogue change," not the full
 * manifest JSON (which would need the hash to already exist to be embedded in itself).
 *
 * <p>Pure: no Spring, no database, no clock — a plain JUnit test exercises it. Sorts by {@code
 * listCode} itself rather than trusting caller order, so the hash is stable regardless of what
 * order the SQL query happens to return rows in.
 */
public final class ManifestHasher {

  private ManifestHasher() {}

  public static String catalogHash(List<ManifestListEntry> entries) {
    StringBuilder canonical = new StringBuilder();
    entries.stream()
        .sorted(Comparator.comparing(ManifestListEntry::listCode))
        .forEach(
            entry ->
                canonical
                    .append(entry.listCode())
                    .append('|')
                    .append(entry.version())
                    .append('|')
                    .append(entry.contentHash())
                    .append('|')
                    .append(entry.itemCount())
                    .append('|')
                    .append(entry.isHierarchical())
                    .append('|')
                    .append(entry.rootItemCode() == null ? "" : entry.rootItemCode())
                    .append('|')
                    .append(entry.rootCountryVersion() == null ? "" : entry.rootCountryVersion())
                    .append('\n'));
    byte[] digest = sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest);
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed present on every JDK -- see ReferenceDocumentPublisher's identical
      // comment for the java.security.MessageDigest javadoc citation.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
