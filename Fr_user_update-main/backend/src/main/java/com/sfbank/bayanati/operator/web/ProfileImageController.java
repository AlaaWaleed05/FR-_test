package com.sfbank.bayanati.operator.web;

import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.service.OperatorImageService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * BL-075 — the operator's view of one stored artifact, one per request.
 *
 * <p>Five of the six kinds are identity images and go straight into an {@code <img>}. The sixth,
 * {@code salary_certificate} (BL-136, admitted at S8-24), may be {@code application/pdf}, which no
 * {@code <img>} can render — the back office opens that one in a modal instead. {@code
 * Content-Disposition: inline} is what makes that work, and it is deliberate for a PDF too: an
 * {@code attachment} would put an unencrypted copy of a customer's pay document in an operator's
 * downloads folder, while inline keeps it inside the browser's own sandboxed viewer. {@code
 * X-Content-Type-Options: nosniff} still pins what the browser may treat it as.
 *
 * <p><strong>Why this is an ordinary cookie-authenticated GET</strong> (R-046, closed 2026-09-13 by
 * refuting its own premise): the risk row was built on "an {@code <img>} cannot carry an {@code
 * Authorization} header", which is true and irrelevant here — the back office has never used
 * Authorization headers, it uses a {@code JSESSIONID} session cookie, and S7-08 made the back
 * office same-origin with the API. A same-origin {@code <img src="/api/v1/operator/…">} sends that
 * cookie by itself, so no signed URL, no blob plumbing and no new key material are needed. It also
 * keeps the property R-046 actually cared about: every view is an origin request, so the audit
 * event fires at VIEW time rather than at issue time.
 *
 * <p><strong>No new security matcher is needed and none was added.</strong> This route matches none
 * of {@code SecurityConfiguration}'s specific {@code hasRole("OPERATOR")} rules (three POSTs and
 * {@code GET /profiles/export}), so it falls through to the {@code /api/v1/operator/**} → {@code
 * hasRole("VIEWER")} catch-all. That is the intended outcome, not an accident: wayfinder ticket
 * 06's ladder has a viewer view everything and print or edit nothing, and {@code
 * OperatorAccessLevel} has asserted "including images" since S4-01.
 *
 * <p>The artifact id travels in the {@code <img src>}, never in the SPA's address bar, so it stays
 * out of browser history and out of any referrer.
 */
@RestController
@RequestMapping("/api/v1/operator/profiles")
public class ProfileImageController {

  private final OperatorImageService operatorImageService;

  public ProfileImageController(OperatorImageService operatorImageService) {
    this.operatorImageService = operatorImageService;
  }

  /**
   * One stored artifact: an identity image, or the salary certificate.
   *
   * <p><strong>Every absence is the same 404</strong> — an unknown profile, an unknown artifact, an
   * artifact belonging to a DIFFERENT profile, a kind outside the six, a non-committed row, a row
   * whose body was never stored or has been purged, a declared content type outside that kind's
   * allow-list. Telling them apart would let an operator who may view one customer walk artifact
   * ids across others and learn which ids are real, which is the vulnerability class this endpoint
   * is written against. Same posture as the customer-facing Stage 9 image endpoint.
   *
   * <p>A CHECKSUM MISMATCH is deliberately NOT one of them. {@code app.artifact_read()} raises
   * rather than returning bytes that have changed underneath, and that exception propagates as a
   * 500. Something present and wrong is not the same as something absent, and rendering it as "no
   * image" would hide a corrupted identity document behind a missing-image icon.
   *
   * <p><strong>Headers.</strong> {@code Cache-Control: no-store, private} is the requirement that
   * turns a correct decision into a broken one if missed: CloudFront caches nothing on {@code
   * /api/*}, but the browser is a separate cache, and an {@code <img>} re-rendered from it never
   * reaches the origin — a silently lost audit event. Note {@code CacheControl.noStore()} alone
   * emits {@code no-store} only, so {@code cachePrivate()} is not decoration. {@code
   * Content-Disposition: inline} carries no filename, which would leak the kind to anything reading
   * headers. {@code X-Content-Type-Options: nosniff} pairs with the pinned content type: the served
   * type comes from an allow-list rather than from the row, because {@code content_type} is
   * declared by whoever stored it and can lie.
   */
  @GetMapping("/{profileId}/artifacts/{artifactId}")
  public ResponseEntity<byte[]> image(
      OperatorIdentity identity, @PathVariable String profileId, @PathVariable String artifactId) {

    UUID profile = parseUuid(profileId, "profileId");
    UUID artifact = parseUuid(artifactId, "artifactId");

    return operatorImageService
        .image(identity, profile, artifact)
        .map(
            served ->
                ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(served.contentType()))
                    .cacheControl(CacheControl.noStore().cachePrivate())
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .header("X-Content-Type-Options", "nosniff")
                    .body(served.bytes()))
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "image not available"));
  }

  private static UUID parseUuid(String value, String name) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException notAUuid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " is not a valid UUID");
    }
  }
}
