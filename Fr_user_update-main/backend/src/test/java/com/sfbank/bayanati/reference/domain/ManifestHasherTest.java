package com.sfbank.bayanati.reference.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ManifestHasher} in isolation — no Spring, no database, no clock. The manifest's own {@code
 * catalogHash} is the manifest endpoint's {@code ETag}, so identical input must always produce
 * identical output and any real change to the entries must change the hash.
 */
class ManifestHasherTest {

  private static ManifestListEntry entry(String listCode, int version, String contentHash) {
    return new ManifestListEntry(
        listCode, version, 10, contentHash, false, null, null, Instant.EPOCH);
  }

  @Test
  void sameEntriesProduceTheSameHashRegardlessOfInputOrder() {
    List<ManifestListEntry> inOrder =
        List.of(entry("admin_division", 1, "aa"), entry("occupation", 1, "bb"));
    List<ManifestListEntry> reversed =
        List.of(entry("occupation", 1, "bb"), entry("admin_division", 1, "aa"));

    assertEquals(ManifestHasher.catalogHash(inOrder), ManifestHasher.catalogHash(reversed));
  }

  @Test
  void aChangedContentHashChangesTheCatalogHash() {
    List<ManifestListEntry> before = List.of(entry("occupation", 1, "aa"));
    List<ManifestListEntry> after = List.of(entry("occupation", 1, "bb"));

    assertNotEquals(ManifestHasher.catalogHash(before), ManifestHasher.catalogHash(after));
  }

  @Test
  void aNewPublishedVersionChangesTheCatalogHash() {
    List<ManifestListEntry> v1 = List.of(entry("occupation", 1, "aa"));
    List<ManifestListEntry> v2 = List.of(entry("occupation", 2, "aa"));

    assertNotEquals(ManifestHasher.catalogHash(v1), ManifestHasher.catalogHash(v2));
  }

  @Test
  void outputIsLowercaseHexSha256() {
    String hash = ManifestHasher.catalogHash(List.of(entry("occupation", 1, "aa")));

    assertEquals(64, hash.length());
    assertEquals(hash, hash.toLowerCase(java.util.Locale.ROOT));
    assertEquals(true, hash.chars().allMatch(c -> Character.digit(c, 16) != -1));
  }
}
