package com.sfbank.bayanati.operator.service;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.audit.domain.CanonicalJson;
import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.domain.OperatorImage;
import com.sfbank.bayanati.operator.domain.OperatorImagePolicy;
import com.sfbank.bayanati.operator.domain.OperatorImageRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * BL-075: one identity image, served to an operator entitled to it — and audited.
 *
 * <p><strong>Viewing an image is its own audit event</strong> (operator.md, and the property
 * R-046's closure was chosen to preserve). The event fires when the image is SEEN, because every
 * view is an origin request: the response carries {@code Cache-Control: no-store, private}, so a
 * browser cannot re-render it from its own cache and quietly skip the origin. That is what makes
 * the trail honest, and it is exactly what a signed URL would have lost by firing at issue time.
 *
 * <p><strong>What the event counts, stated plainly:</strong> FETCHES, not eyeballs (wayfinder
 * ticket 09 decision 1). Scrolling an already-rendered image back into view fires nothing. That
 * limit is accepted openly rather than papered over.
 *
 * <p>Ordering is deliberate. The event is appended BEFORE the bytes are handed back, and nothing
 * here is transactional: {@code AuditEventWriter}'s contract is to fail rather than return quietly,
 * so an audit failure propagates and the caller gets no image. Bytes are never served off the
 * record. A transaction spanning the read and the append would allow precisely that.
 *
 * <p>The chain is the PROFILE chain with the profile as its subject, following what every other
 * profile-scoped operator event already does ({@code OperatorProfileViewService}'s {@code
 * profile_viewed}, {@code OperatorReviewService}) — as opposed to the operator chain the list and
 * export events use. {@code ManualCompletionService} was a third such precedent until AD-022
 * deleted it at S9-01. Wayfinder ticket 09 settled the PAYLOAD, not the chain; this follows the
 * established precedent rather than inventing a third convention. The chain always exists: an
 * artifact implies a profile that reached Stage 8, and the chain is ensured at Stage 1b, so this
 * can never be its first writer.
 */
@Service
public class OperatorImageService {

  static final String CHAIN_KIND = "profile";
  static final String EVENT_PROFILE_IMAGE_VIEWED = "profile_image_viewed";

  private final OperatorImageRepository operatorImageRepository;
  private final AuditEventWriter auditEventWriter;

  public OperatorImageService(
      OperatorImageRepository operatorImageRepository, AuditEventWriter auditEventWriter) {
    this.operatorImageRepository = operatorImageRepository;
    this.auditEventWriter = auditEventWriter;
  }

  /**
   * The artifact's bytes and the content type to serve them under, or empty for every kind of
   * absence — unknown profile, unknown artifact, an artifact belonging to a DIFFERENT profile, a
   * kind outside the allow-list, a non-committed row, or a row with no bytes. One answer for all of
   * them, because distinguishing them tells a caller which artifact ids are real.
   *
   * <p>The served content type comes from {@link OperatorImagePolicy}, never from the row: the
   * column is DECLARED and can lie. A declared type outside that KIND's allow-list is refused
   * rather than downgraded to {@code application/octet-stream}, which in an {@code <img>} is a
   * broken image with extra steps. The allow-list is per kind because {@code salary_certificate}
   * may be a PDF and nothing else may — see {@link OperatorImagePolicy}.
   *
   * <p><strong>Not everything this serves is an image, since S8-24.</strong> {@code
   * salary_certificate} (BL-136) can be {@code application/pdf}, which no {@code <img>} can render;
   * the back office opens it in a modal instead. The class, the controller and the {@code
   * profile_image_viewed} event keep their names anyway: renaming the event type would split one
   * action's audit trail across two names, and rows already carry the existing one.
   *
   * <p>No event is written for a refusal. The event records that an image WAS VIEWED; a 404 served
   * no image, and recording one would make the trail claim something that did not happen.
   *
   * <p><strong>One exception to that invariant, stated rather than hidden:</strong> Spring MVC
   * routes a {@code HEAD} to the same {@code @GetMapping} handler and then discards the body, so an
   * authenticated {@code HEAD} on this route writes a {@code profile_image_viewed} event and
   * delivers zero bytes. Left as-is deliberately: no {@code <img>} ever issues one, the caller must
   * already be an authorised viewer who could simply {@code GET} the image instead, and the
   * alternative — refusing {@code HEAD} with a 405 — trades a faithful-enough record for a broken
   * HTTP verb. Worth knowing before anyone reads an event count as "images actually looked at".
   */
  public Optional<ServedImage> image(OperatorIdentity identity, UUID profileId, UUID artifactId) {
    Optional<OperatorImage> stored = operatorImageRepository.find(profileId, artifactId);
    if (stored.isEmpty()) {
      return Optional.empty();
    }
    OperatorImage image = stored.get();

    Optional<String> servedContentType =
        OperatorImagePolicy.pinContentType(image.kind(), image.contentType());
    if (servedContentType.isEmpty()) {
      return Optional.empty();
    }

    auditEventWriter.append(
        new AuditEvent(
            CHAIN_KIND,
            profileId.toString(),
            EVENT_PROFILE_IMAGE_VIEWED,
            "operator",
            identity.operatorId(),
            profileId,
            null,
            UUID.randomUUID(),
            payload(image.kind(), artifactId)));

    return Optional.of(new ServedImage(servedContentType.get(), image.bytes()));
  }

  /**
   * Ticket 09 decision 1: {@code kind} plus {@code artifactId}, and nothing else. {@code
   * AuditEvent} already carries the profile, the actor and the request id as first-class fields, so
   * repeating them here would duplicate a column. The artifact id is what makes the record
   * unambiguous — {@code app.artifact_ref} is UNIQUE per (cycle, kind), so a profile with several
   * identity cycles has several committed {@code doc_front} rows and the kind alone cannot tell a
   * superseded scan from its replacement. Cycle and checksum are deliberately absent: both are
   * derivable from that row, and storing a copy is state that can drift.
   *
   * <p>Neither value is PII, which matters because {@code payload_json} is permanently hash-chained
   * and never erasable.
   *
   * <p><strong>Deliberately NOT stamped with {@code actorRole}</strong>, unlike every other
   * operator-chain payload (see {@code operator.domain.OperatorAuditPayload}, added at BL-139).
   * Ticket 09 decision 1 fixes this payload at "kind + artifactId and nothing else", and {@code
   * OperatorImageIntegrationTest} asserts the field count to hold it there. That is a product-owner
   * decision about this specific payload, so BL-139 left it alone rather than overriding it in
   * passing. The consequence is real and recorded rather than hidden: an admin's image view and an
   * operator's image view are indistinguishable in the chain. R-054's concern is separation over
   * the BUSINESS action — enter then attest — and every event on that path (approve, reject,
   * export, list search, profile view; manual completion too, until AD-022 removed it) does carry
   * the role, so the gap is confined to a read that {@code actor_id} already attributes to an
   * individual.
   */
  private static String payload(String kind, UUID artifactId) {
    Map<String, Object> members = new LinkedHashMap<>();
    members.put("artifactId", artifactId.toString());
    members.put("kind", kind);
    return CanonicalJson.object(members);
  }

  /** The bytes and the content type actually served. */
  public record ServedImage(String contentType, byte[] bytes) {}
}
