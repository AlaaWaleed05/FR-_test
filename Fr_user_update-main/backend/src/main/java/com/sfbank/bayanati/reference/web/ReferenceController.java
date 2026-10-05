package com.sfbank.bayanati.reference.web;

import com.sfbank.bayanati.reference.domain.ManifestListEntry;
import com.sfbank.bayanati.reference.domain.ReferenceDocumentStore;
import com.sfbank.bayanati.reference.domain.ReferenceManifest;
import com.sfbank.bayanati.reference.domain.StoredDocument;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * AD-002f §5.1/§6.6's two read endpoints. Both are safe, idempotent {@code GET}s carrying no PII —
 * {@code public} caching is correct here and nowhere else in this system (research report §5.6).
 *
 * <p><strong>No authentication</strong> — AD-002e is open and explicitly out of scope for this task
 * (PROJECT_PLAN.md "Open architecture decisions"). Noted on BL-007 rather than blocking here: these
 * are the first endpoints whose response body is large enough to be an unauthenticated bandwidth
 * surface.
 *
 * <p><strong>{@code ETag} is set from the already-stored hash, never computed from the rendered
 * body</strong> — the research report explicitly rejects {@code ShallowEtagHeaderFilter} for
 * exactly this reason (§5.6): the list endpoint's {@code content_hash} exists before rendering, so
 * a conditional request is answered without reading {@code document_json} at all.
 */
@RestController
@RequestMapping("/api/v1/reference")
public class ReferenceController {

  /** R-042's reserved field — see {@link ManifestResponse}'s Javadoc. */
  private static final List<String> VERIFIABLE_CHANNELS = List.of("sms", "whatsapp", "email");

  private final ReferenceDocumentStore documentStore;

  public ReferenceController(ReferenceDocumentStore documentStore) {
    this.documentStore = documentStore;
  }

  @GetMapping("/manifest")
  public ResponseEntity<ManifestResponse> manifest(
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    ReferenceManifest manifest = documentStore.currentManifest();
    String etag = quote(manifest.catalogHash());

    if (matches(ifNoneMatch, etag)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
          .eTag(etag)
          .header(HttpHeaders.CACHE_CONTROL, "no-cache")
          .build();
    }
    return ResponseEntity.ok()
        .eTag(etag)
        .header(HttpHeaders.CACHE_CONTROL, "no-cache")
        .body(toResponse(manifest));
  }

  @GetMapping("/lists/{listCode}/{version}")
  public ResponseEntity<byte[]> list(
      @PathVariable String listCode,
      @PathVariable int version,
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    StoredDocument document =
        documentStore
            .document(listCode, version)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "no published document for " + listCode + "/" + version));
    String etag = quote(document.sha256Hex());

    if (matches(ifNoneMatch, etag)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
          .eTag(etag)
          .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
          .build();
    }
    return ResponseEntity.ok()
        .eTag(etag)
        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
        .contentType(MediaType.APPLICATION_JSON)
        .body(document.documentBytes());
  }

  /**
   * A simple substring check rather than full RFC 9110 §13.1.2 list parsing — {@code If-None-Match}
   * here only ever carries a client's own single previously-cached ETag (there is no multi-
   * representation negotiation on these endpoints), so an exact single value is the only case that
   * matters in practice; a comma-separated list still matches correctly since the target is quoted
   * and distinct from any adjacent entry.
   */
  private static boolean matches(String ifNoneMatch, String etag) {
    return ifNoneMatch != null && ifNoneMatch.contains(etag);
  }

  private static String quote(String value) {
    return "\"" + value + "\"";
  }

  private ManifestResponse toResponse(ReferenceManifest manifest) {
    List<ManifestListEntryResponse> lists =
        manifest.lists().stream().map(this::toResponse).toList();
    return new ManifestResponse(
        manifest.catalogHash(), manifest.generatedAt(), lists, VERIFIABLE_CHANNELS);
  }

  private ManifestListEntryResponse toResponse(ManifestListEntry entry) {
    return new ManifestListEntryResponse(
        entry.listCode(),
        entry.version(),
        entry.itemCount(),
        entry.contentHash(),
        entry.isHierarchical(),
        entry.rootItemCode(),
        entry.rootCountryVersion(),
        entry.publishedAt(),
        "/api/v1/reference/lists/" + entry.listCode() + "/" + entry.version());
  }
}
