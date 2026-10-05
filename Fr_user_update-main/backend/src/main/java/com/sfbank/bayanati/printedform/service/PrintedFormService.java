package com.sfbank.bayanati.printedform.service;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.audit.domain.CanonicalJson;
import com.sfbank.bayanati.auth.domain.OperatorAccount;
import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import com.sfbank.bayanati.operator.domain.AccessLevelRequiredException;
import com.sfbank.bayanati.operator.domain.OperatorAccessLevel;
import com.sfbank.bayanati.operator.domain.OperatorAuditPayload;
import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.domain.ProfileDetail;
import com.sfbank.bayanati.operator.domain.ProfileViewRepository;
import com.sfbank.bayanati.operator.domain.ScanResultView;
import com.sfbank.bayanati.operator.domain.UnknownProfileException;
import com.sfbank.bayanati.printedform.domain.PrintedFormDocument;
import com.sfbank.bayanati.printedform.domain.PrintedFormRepository;
import com.sfbank.bayanati.printedform.domain.PrintedFormVocabulary;
import com.sfbank.bayanati.printedform.domain.ProfileNotPrintableException;
import com.sfbank.bayanati.printedform.domain.ReferenceLabels;
import com.sfbank.bayanati.reference.domain.ReferenceCatalog;
import com.sfbank.bayanati.reference.domain.ReferenceItemDetail;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BL-132's endpoint half: one operator, one profile, one printed form — stored and streamed.
 *
 * <p><strong>Store first, then stream, in ONE transaction</strong> (ticket 05 decision 5). A failed
 * store means the operator gets no file. The reason is not the one the ticket's own question gave —
 * the printed form is INTERNAL and never handed to the customer, so "a print the customer received
 * that the bank has no record of" describes something that cannot happen. The real failure mode
 * survives the correction and is worse: <em>a copy of the densest PII object in the system exists
 * on someone's disk and the bank has no record that it was ever produced.</em> With four-eyes gone
 * (R-054) that trail is the only control there is.
 *
 * <p>The render runs INSIDE the transaction, not before it. That costs a held connection for the
 * length of a FOP pass, and it buys the property the decision actually asks for: the images the
 * form carries, the row that stores it and the event that records it are one consistent read. The
 * bytes reach the controller only after the commit returns.
 *
 * <p><strong>Operator and admin only; viewer refused</strong> (AD-013, ticket 06: a viewer views
 * everything and prints nothing). Enforced here rather than only at the security matcher, because
 * the matcher's job is coarse routing and this is the authorisation.
 */
@Service
public class PrintedFormService {

  static final String CHAIN_KIND = "profile";

  /**
   * One event at render, not two. Ticket 09 decision 2: store-then-stream in a single transaction
   * makes "rendered" and "delivered" the same instant, so two events would record a distinction
   * that does not exist.
   */
  static final String EVENT_FORM_PRINTED = "profile_form_printed";

  /**
   * A re-download is its OWN event type (ticket 05 decision 6): a log that cannot tell a
   * re-download from the original print cannot answer how many copies of that document exist.
   */
  static final String EVENT_FORM_REDOWNLOADED = "profile_form_redownloaded";

  static final String PDF = "application/pdf";

  /** Ticket 05 decision 8. Manual completion also lands on {@code submitted}. */
  private static final Set<String> PRINTABLE_STATUSES = Set.of("submitted", "approved");

  /**
   * Every reference list the form resolves a code against. Named here rather than derived from what
   * the profile happened to pin, so the audit payload can say which version EVERY list resolved at
   * — including the two nothing pins.
   */
  private static final List<String> PRINTED_LIST_CODES =
      List.of(
          PrintedFormVocabulary.LIST_ADMIN_DIVISION,
          PrintedFormVocabulary.LIST_BRANCH,
          PrintedFormVocabulary.LIST_COUNTRY,
          PrintedFormVocabulary.LIST_EDUCATION_LEVEL,
          PrintedFormVocabulary.LIST_INCOME_SOURCE,
          PrintedFormVocabulary.LIST_OCCUPATION);

  /**
   * Africa/Khartoum. The form is read by a branch officer in Sudan, and a submission timestamp
   * rendered in UTC would be an hour or two off every conversation they have about it. A constant
   * rather than configuration: this system has exactly one market (CLAUDE.md), and a configurable
   * display zone is a setting nobody would ever set correctly twice.
   */
  private static final ZoneId ZONE = ZoneId.of("Africa/Khartoum");

  private final ProfileViewRepository profileViewRepository;
  private final PrintedFormRepository printedFormRepository;
  private final ReferenceCatalog referenceCatalog;
  private final PrintedFormRenderer printedFormRenderer;
  private final AuditEventWriter auditEventWriter;
  private final OperatorUserRepository operatorUserRepository;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  public PrintedFormService(
      ProfileViewRepository profileViewRepository,
      PrintedFormRepository printedFormRepository,
      ReferenceCatalog referenceCatalog,
      PrintedFormRenderer printedFormRenderer,
      AuditEventWriter auditEventWriter,
      OperatorUserRepository operatorUserRepository,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.profileViewRepository = profileViewRepository;
    this.printedFormRepository = printedFormRepository;
    this.referenceCatalog = referenceCatalog;
    this.printedFormRenderer = printedFormRenderer;
    this.auditEventWriter = auditEventWriter;
    this.operatorUserRepository = operatorUserRepository;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * The name the FOOTER prints, resolved from the user id the identity carries. AD-022 (n), closing
   * BL-163.
   *
   * <p><strong>The USERNAME, and deliberately not the display name.</strong> The display name is
   * operator-supplied, so printing it puts untrusted text on a bank document; BL-165 is live proof
   * that it can also be wrong. A username is system-assigned and ASCII, which is the second reason
   * — {@code FoDocumentWriter.latin} wraps this value in an LTR bidi override, and an Arabic
   * display name rendered through that would not lay out correctly.
   *
   * <p><strong>{@link OperatorIdentity} is NOT widened to carry it.</strong> {@code
   * OperatorAccount}'s own javadoc requires the UUID to remain the only identifier {@code
   * operatorId()} ever holds (AD-002e): {@code actor_id} goes into append-only audit rows, so a
   * renameable identifier there would silently re-attribute past actions. The paper needs a name
   * and the trail needs an id, and those are different requirements — resolving here gives each
   * what it needs and leaves all seven audit payload sites untouched.
   *
   * <p>Falls back to the id itself in two cases, neither expected in production. First, no active
   * account answers: {@code findActiveById} filters on {@code is_enabled}, so the only way to miss
   * is an account disabled between this request being admitted and the render running — and failing
   * an operator's print because someone else disabled their account mid-render is worse than
   * printing the identifier the footer carried until today. Second, the id does not parse as a
   * UUID: {@code OperatorIdentityFilter} is the only construction site in main and always passes
   * {@code account.userId().toString()}, so this is unreachable through the application, but a
   * print is the wrong place to discover otherwise.
   */
  private String printedByName(OperatorIdentity identity) {
    UUID userId;
    try {
      userId = UUID.fromString(identity.operatorId());
    } catch (IllegalArgumentException notAUuid) {
      return identity.operatorId();
    }
    return operatorUserRepository
        .findActiveById(userId)
        .map(OperatorAccount::username)
        .orElse(identity.operatorId());
  }

  /**
   * Renders, stores and returns one printed form.
   *
   * @param includeAttachments the operator's answer to the one print-time question, asked every
   *     time and defaulting to no. Never remembered: an operator who once opted in would otherwise
   *     go on printing customers' documents indefinitely without deciding to again.
   * @throws AccessLevelRequiredException a viewer
   * @throws UnknownProfileException no such profile
   * @throws ProfileNotPrintableException a profile that is neither submitted nor approved
   */
  public PrintedForm print(OperatorIdentity identity, UUID profileId, boolean includeAttachments) {

    requireOperatorLevel(identity, "print");

    return transactionTemplate.execute(
        status -> {
          ProfileDetail profile =
              profileViewRepository
                  .find(profileId)
                  .orElseThrow(
                      () -> new UnknownProfileException("no profile with id " + profileId));
          requirePrintable(profile);

          Map<String, Integer> referenceVersions =
              printedFormRepository.pinnedReferenceVersions(profileId);

          Optional<PrintedFormRepository.StoredArtifact> certificate =
              includeAttachments
                  ? printedFormRepository.salaryCertificate(profileId)
                  : Optional.empty();

          Instant printedAt = clock.instant();
          PrintedFormDocument document =
              PrintedFormAssembler.assemble(
                  new PrintedFormSources(
                      profile,
                      pinnedLabels(referenceVersions),
                      printedFormRepository.images(profileId),
                      certificate
                          .map(
                              stored ->
                                  new PrintedFormDocument.SalaryCertificate(
                                      stored.bytes(), stored.contentType()))
                          .orElse(null),
                      includeAttachments,
                      // The footer names the operator, and AD-022 (n) says by USERNAME. This was
                      // identity.operatorId() -- a 36-character UUID that overflowed the footer's
                      // middle third and wrapped it onto a second line (BL-163). See
                      // printedByName: the audit trail still gets the id, untouched.
                      printedByName(identity),
                      printedFormRepository.editedFieldNumbers(profileId),
                      countryNamesForScan(profile, referenceVersions),
                      printedAt,
                      ZONE));

          // Render once. The same bytes are stored and streamed -- a browser-side render, or a
          // second render for the store, would make the stored file something nobody held
          // (ticket 05 decision 4).
          byte[] pdf = printedFormRenderer.render(document);

          // Written here rather than before the render, found at review. The INSERT takes a row
          // lock on this profile, and two concurrent first prints of the same profile would
          // otherwise serialise across the whole FOP pass and PDFBox merge -- a pooled connection
          // held for the length of a render rather than for the length of an insert. No semantic
          // change: it is still inside the same transaction, still before the store, and still
          // read back so a reprint reports what the first print pinned.
          int matrixVersion = printedFormRepository.recordProvenanceMatrixVersion(profileId);

          UUID artifactId =
              printedFormRepository.storePrintedForm(
                  profileId, PrintedFormRepository.PRINTED_FORM_KIND, pdf, printedAt);

          auditEventWriter.append(
              new AuditEvent(
                  CHAIN_KIND,
                  profileId.toString(),
                  EVENT_FORM_PRINTED,
                  "operator",
                  identity.operatorId(),
                  profileId,
                  null,
                  UUID.randomUUID(),
                  printPayload(
                      identity,
                      artifactId,
                      includeAttachments,
                      certificate.map(PrintedFormRepository.StoredArtifact::artifactId),
                      matrixVersion,
                      resolvedReferenceVersions(referenceVersions))));

          return new PrintedForm(artifactId, pdf);
        });
  }

  /**
   * One previously stored print, streamed again and recorded as its own event.
   *
   * <p>No transaction and no store: nothing is written but the event, and {@code AuditEventWriter}
   * fails rather than returning quietly, so an audit failure propagates and the caller gets no
   * file. Bytes are never served off the record — the same ordering {@code OperatorImageService}
   * states for the image endpoint, and for the same reason.
   */
  public Optional<PrintedForm> redownload(
      OperatorIdentity identity, UUID profileId, UUID artifactId) {

    requireOperatorLevel(identity, "re-download a print");

    Optional<PrintedFormRepository.StoredArtifact> stored =
        printedFormRepository.findPrintedForm(profileId, artifactId);
    if (stored.isEmpty()) {
      return Optional.empty();
    }

    auditEventWriter.append(
        new AuditEvent(
            CHAIN_KIND,
            profileId.toString(),
            EVENT_FORM_REDOWNLOADED,
            "operator",
            identity.operatorId(),
            profileId,
            null,
            UUID.randomUUID(),
            CanonicalJson.object(
                OperatorAuditPayload.withActorRole(
                    identity, Map.of("artifactId", artifactId.toString())))));

    return Optional.of(new PrintedForm(artifactId, stored.get().bytes()));
  }

  /**
   * Ticket 09 decision 2, plus ticket 05's recommendation on the certificate, which this build
   * takes: ONE event carrying whether the certificate was included and its artifact id, rather than
   * an event of its own. Decision 6's stated purpose is answering "how many copies of that document
   * exist", and a certificate leaving the building unrecorded defeats it as squarely as an
   * unrecorded form would.
   *
   * <p>Nothing here is PII, which is the constraint that matters: {@code payload_json} is
   * permanently hash-chained and never erasable (V0002:52), so a customer's name or national number
   * placed here could not be reached by any retention rule. Ids, versions, flags and a role.
   *
   * <p>The reference versions are flattened into one sorted string because {@code CanonicalJson}
   * takes scalars only — no nested objects, no arrays. Sorted so two prints of the same profile
   * produce byte-identical payloads, which is what makes the chain's hashes comparable.
   */
  private static String printPayload(
      OperatorIdentity identity,
      UUID artifactId,
      boolean includeAttachments,
      Optional<UUID> certificateId,
      int provenanceMatrixVersion,
      Map<String, String> referenceVersions) {

    Map<String, Object> members = new LinkedHashMap<>();
    members.put("artifactId", artifactId.toString());
    // `variant` was a member here until AD-022 (S9-01) collapsed the two forms into one. It is
    // DROPPED rather than pinned to a constant: a payload member that can only ever hold one value
    // records nothing. Events written before that date keep their own `variant` member -- the
    // chain is append-only and past payloads are never rewritten -- so a reader of the trail can
    // still tell which form a historical print was.
    members.put("kind", PrintedFormRepository.PRINTED_FORM_KIND);
    members.put("attachmentsIncluded", includeAttachments);
    members.put("salaryCertificateIncluded", certificateId.isPresent());
    members.put("salaryCertificateArtifactId", certificateId.map(UUID::toString).orElse(null));
    members.put("provenanceMatrixVersion", provenanceMatrixVersion);
    members.put("referenceListVersions", flatten(referenceVersions));
    return CanonicalJson.object(OperatorAuditPayload.withActorRole(identity, members));
  }

  /**
   * Every reference list this form resolved, and the version each resolved AT — pinned ones as the
   * profile recorded them, unpinned ones marked so the record does not claim a pin it does not
   * have.
   *
   * <p>Found at review, and it matters because the payload is the permanent record. Ticket 09
   * decision 2 asks for "the reference-list versions … so a reprint years later can be checked
   * against what the original claimed", and flattening only the PINNED map said nothing at all
   * about {@code branch} (field 2) and {@code education_level} (field 17) — the two lists no writer
   * pins — while a profile with no pinned rows produced an empty string that reads as "no lists
   * used" rather than "every list resolved against latest".
   *
   * <p>The {@code ~} suffix is what keeps the two apart: {@code branch=1~} says "version 1, and
   * that was the current version at print time, not a version this profile pinned".
   */
  private Map<String, String> resolvedReferenceVersions(Map<String, Integer> pinned) {
    Map<String, String> resolved = new TreeMap<>();
    for (String listCode : PRINTED_LIST_CODES) {
      Integer version = pinned.get(listCode);
      resolved.put(
          listCode,
          version != null
              ? String.valueOf(version)
              : "latest:" + referenceCatalog.currentVersion(listCode));
    }
    return resolved;
  }

  private static String flatten(Map<String, String> versions) {
    StringBuilder out = new StringBuilder();
    new TreeMap<>(versions)
        .forEach(
            (listCode, version) -> {
              if (out.length() > 0) {
                out.append(',');
              }
              out.append(listCode).append('=').append(version);
            });
    return out.toString();
  }

  /**
   * Reference labels, each pinned to the version THIS PROFILE used.
   *
   * <p>A list the profile did not pin falls back to the current version. Two do so today — {@code
   * branch} and {@code education_level} — because no writer records them per profile; neither has
   * ever had a second version, so the fallback resolves identically, and the honest statement is
   * that this is a latent gap rather than a live defect. Anything narrower would mean printing «غير
   * متاح» over a branch the bank knows perfectly well.
   */
  private ReferenceLabels pinnedLabels(Map<String, Integer> pinnedVersions) {
    return (listCode, itemCode) -> {
      Integer version = pinnedVersions.get(listCode);
      int resolved = version != null ? version : referenceCatalog.currentVersion(listCode);
      return referenceCatalog.find(listCode, resolved, itemCode).map(ReferenceItemDetail::labelAr);
    };
  }

  /**
   * The Arabic country names for the at-most-two alpha-3 codes this profile's scan carries: field 4
   * (nationality) and field 48 (issuing country).
   *
   * <p>Resolved HERE rather than in the assembler, because the assembler may not reach a database.
   * Both codes come off the MRZ as ISO alpha-3 («SDN») while the country list is keyed on alpha-2
   * («SD»), so an ordinary lookup finds nothing; V0022 seeds the alpha-3 into each row's {@code
   * extra}, which is what BL-157 was closed by discovering.
   *
   * <p>Pinned to the profile's own list version wherever it has one, as {@link #pinnedLabels} is,
   * so a reprint says what the first print said. The fallback to the CURRENT version is resolved
   * lazily, because it throws where a list has none.
   *
   * <p>A code with no row is simply left out and the form prints the raw code. ICAO issues document
   * codes that have no ISO country at all -- {@code XXA} for a stateless person's travel document,
   * {@code GBD} for a British overseas citizen -- so an unresolved code is an ordinary case here
   * and printing «غير متاح» over it would be a false statement about the customer.
   */
  private Map<String, String> countryNamesForScan(
      ProfileDetail profile, Map<String, Integer> pinnedVersions) {
    ScanResultView scan = profile.scanResult();
    if (scan == null) {
      return Map.of();
    }
    Map<String, String> names = new HashMap<>();
    Integer version = pinnedVersions.get(PrintedFormVocabulary.LIST_COUNTRY);
    for (String code : new String[] {scan.nationality(), scan.issuingCountry()}) {
      if (code == null || code.isBlank() || names.containsKey(code)) {
        continue;
      }
      // Resolved LAZILY, and only once. currentVersion THROWS when a list has no published
      // version, so asking for it before there is a code to resolve would abort a print over a
      // question the print never needed answered -- pinnedLabels is a lambda and gets this for
      // free, which is why this loop has to arrange it deliberately.
      if (version == null) {
        version = referenceCatalog.currentVersion(PrintedFormVocabulary.LIST_COUNTRY);
      }
      referenceCatalog
          .findByAlpha3(PrintedFormVocabulary.LIST_COUNTRY, version, code)
          .map(ReferenceItemDetail::labelAr)
          .ifPresent(label -> names.put(code, label));
    }
    return names;
  }

  private static void requireOperatorLevel(OperatorIdentity identity, String action) {
    if (identity.accessLevel() != OperatorAccessLevel.OPERATOR) {
      throw new AccessLevelRequiredException(
          "operator "
              + identity.operatorId()
              + " has access level "
              + identity.accessLevel()
              + ", which may not "
              + action);
    }
  }

  private static void requirePrintable(ProfileDetail profile) {
    if (!PRINTABLE_STATUSES.contains(profile.status())) {
      throw new ProfileNotPrintableException(
          "profile "
              + profile.profileId()
              + " has status "
              + profile.status()
              + "; only submitted and approved profiles may be printed");
    }
  }

  /** The stored artifact's id and the bytes the operator receives — the same bytes. */
  public record PrintedForm(UUID artifactId, byte[] bytes) {}
}
