package com.sfbank.bayanati.reference.domain;

import java.util.Optional;

/**
 * Read-only access to what the two reference read endpoints serve (AD-002f §5.1/§6.6) — the
 * manifest and the stored per-list documents. Deliberately separate from {@link ReferenceCatalog}:
 * that port answers per-item validation questions for the data-entry stages; this one answers "what
 * does the wire endpoint return," a different shape of read (whole documents/manifests, not
 * single-item existence checks) with a different caller ({@code reference.web}, not {@code
 * dataentry}).
 */
public interface ReferenceDocumentStore {

  /**
   * Every list currently marked {@code is_current}, with {@link ManifestHasher#catalogHash}
   * computed fresh over them. Mutable — never cached server-side beyond the HTTP {@code
   * ETag}/{@code If-None-Match} cycle (§5.6: {@code Cache-Control: no-cache}, not {@code
   * no-store}).
   */
  ReferenceManifest currentManifest();

  /**
   * The exact bytes stored for {@code (listCode, version)} by the publication step (V0048), or
   * empty if that version was never published (including a version that exists in {@code
   * ref.reference_list_version} but has no matching {@code ref.reference_list_document} row — see
   * {@code docs/components/reference-data.md}'s Open item on a skipped publication step).
   */
  Optional<StoredDocument> document(String listCode, int version);
}
