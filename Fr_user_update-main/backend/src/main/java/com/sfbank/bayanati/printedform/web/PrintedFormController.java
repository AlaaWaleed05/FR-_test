package com.sfbank.bayanati.printedform.web;

import com.sfbank.bayanati.operator.domain.AccessLevelRequiredException;
import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.domain.UnknownProfileException;
import com.sfbank.bayanati.printedform.domain.ProfileNotPrintableException;
import com.sfbank.bayanati.printedform.service.PrintedFormService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * BL-132: the operator prints استمارة تحديث البيانات.
 *
 * <p>Shaped after {@code operator.web.ProfileImageController}, which is the closest precedent for
 * role-gated byte streaming, and it carries the same headers for the same reasons. {@code
 * Cache-Control: no-store, private} because the browser is a cache CloudFront's {@code /api/*}
 * behaviour knows nothing about, and a print re-served from it is a lost audit event. {@code
 * X-Content-Type-Options: nosniff} pairs with a pinned {@code application/pdf} — pinned by this
 * code, never read off the row, because the column is declared by whoever stored it.
 *
 * <p><strong>A POST, not a GET, and that is not a REST quibble.</strong> Printing WRITES: it stores
 * a new artifact and appends an audit event, in one transaction (ticket 05 decision 5). A GET that
 * an operator could bookmark, a browser could prefetch, or a link could trigger would mint stored
 * PII copies and audit rows on navigation. It also means CSRF applies, which is what {@code
 * SecurityConfiguration}'s {@code csrf(CsrfConfigurer::spa)} already gives every unsafe request on
 * this chain.
 *
 * <p><strong>{@code Content-Disposition: inline}</strong>, deliberately, and this is the opposite
 * of what a "download" would do: {@code attachment} would put an unencrypted copy of the densest
 * PII object in the system into an operator's downloads folder on every print, where nothing
 * expires it. Inline keeps it inside the browser's own PDF viewer, which is where an operator
 * presses their printer's button from. No filename: one would leak the customer's reference number
 * into a file name and into anything reading headers.
 */
@RestController
@RequestMapping("/api/v1/operator/profiles")
public class PrintedFormController {

  private final PrintedFormService printedFormService;

  public PrintedFormController(PrintedFormService printedFormService) {
    this.printedFormService = printedFormService;
  }

  /**
   * Renders, stores and streams one printed form.
   *
   * <p>403 for a viewer, 404 for an unknown profile, 409 for a profile that is neither {@code
   * submitted} nor {@code approved}. There is no longer a 400 arm: AD-022 removed the variant
   * choice, so the request carries nothing that can be invalid. The three are told apart here —
   * unlike the image endpoint, which collapses every absence into one 404 — because none of them
   * leaks anything a caller could walk: the operator already holds this profile's id and can
   * already see its status on their own screen.
   */
  @PostMapping("/{profileId}/print")
  public ResponseEntity<byte[]> print(
      OperatorIdentity identity,
      @PathVariable String profileId,
      @RequestBody PrintRequest request) {

    UUID profile = parseUuid(profileId, "profileId");

    try {
      PrintedFormService.PrintedForm printed =
          printedFormService.print(identity, profile, request.includeAttachments());
      return pdf(printed.bytes())
          // The stored artifact's id, so the back office can name the row this print became
          // without a second round trip. A header rather than the body, because the body IS the
          // PDF. Not a secret: it is only reachable by an operator who just printed it, and the
          // re-download endpoint authorises it again per profile.
          .header("X-Printed-Form-Artifact-Id", printed.artifactId().toString())
          .body(printed.bytes());
    } catch (UnknownProfileException unknown) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, unknown.getMessage());
    } catch (AccessLevelRequiredException forbidden) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, forbidden.getMessage());
    } catch (ProfileNotPrintableException notPrintable) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, notPrintable.getMessage());
    }
  }

  /**
   * One previously stored print, streamed again.
   *
   * <p>A GET, unlike the print above, because this genuinely reads: nothing is rendered and nothing
   * is stored. It is still operator-and-admin only — a re-download is a print in every way that
   * matters (ticket 05 decision 6) — and it still writes its own audit event, which is why the
   * event fires in the service and not here.
   *
   * <p>Every absence is one 404: unknown profile, unknown artifact, an artifact belonging to a
   * different profile, a kind that is not the one printed-form kind, a purged body. Here the
   * collapsing IS needed, because the artifact id is the caller's input and distinguishing the
   * cases would tell them which ids are real.
   */
  @GetMapping("/{profileId}/prints/{artifactId}")
  public ResponseEntity<byte[]> redownload(
      OperatorIdentity identity, @PathVariable String profileId, @PathVariable String artifactId) {

    UUID profile = parseUuid(profileId, "profileId");
    UUID artifact = parseUuid(artifactId, "artifactId");

    try {
      return printedFormService
          .redownload(identity, profile, artifact)
          .map(printed -> pdf(printed.bytes()).body(printed.bytes()))
          .orElseThrow(
              () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "print not available"));
    } catch (AccessLevelRequiredException forbidden) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, forbidden.getMessage());
    }
  }

  /**
   * The headers every printed-form response carries. {@code CacheControl.noStore()} alone emits
   * {@code no-store} only, so {@code cachePrivate()} is not decoration — it is the half that stops
   * a shared cache holding a customer's whole record.
   */
  private static ResponseEntity.BodyBuilder pdf(byte[] bytes) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .contentLength(bytes.length)
        .cacheControl(CacheControl.noStore().cachePrivate())
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
        .header("X-Content-Type-Options", "nosniff");
  }

  private static UUID parseUuid(String value, String name) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException notAUuid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " is not a valid UUID");
    }
  }
}
