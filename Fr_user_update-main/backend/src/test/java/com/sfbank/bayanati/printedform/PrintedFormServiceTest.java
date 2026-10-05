package com.sfbank.bayanati.printedform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sfbank.bayanati.audit.domain.AuditArtifact;
import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.auth.domain.OperatorAccount;
import com.sfbank.bayanati.auth.domain.OperatorRole;
import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import com.sfbank.bayanati.operator.domain.AccessLevelRequiredException;
import com.sfbank.bayanati.operator.domain.ChannelStateView;
import com.sfbank.bayanati.operator.domain.CustomerDataView;
import com.sfbank.bayanati.operator.domain.OperatorAccessLevel;
import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.domain.ProfileDetail;
import com.sfbank.bayanati.operator.domain.ProfileViewRepository;
import com.sfbank.bayanati.operator.domain.SalaryCertificateState;
import com.sfbank.bayanati.operator.domain.UnknownProfileException;
import com.sfbank.bayanati.printedform.domain.PrintedFormImageSlot;
import com.sfbank.bayanati.printedform.domain.PrintedFormRepository;
import com.sfbank.bayanati.printedform.domain.ProfileNotPrintableException;
import com.sfbank.bayanati.printedform.service.FoDocumentWriter;
import com.sfbank.bayanati.printedform.service.PrintedFormRenderer;
import com.sfbank.bayanati.printedform.service.PrintedFormService;
import com.sfbank.bayanati.reference.domain.ReferenceCatalog;
import com.sfbank.bayanati.reference.domain.ReferenceItemDetail;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The print service's decisions — authorisation, status gating, what the audit records, and that
 * the stored bytes and the streamed bytes are the same bytes.
 *
 * <p>Fakes rather than Mockito, because every one of these collaborators is asked a question AND
 * recorded against: the test needs to read what was stored and what was appended, not merely that a
 * call happened. No Spring context, no database, no clock.
 *
 * <p>The renderer is the real one. It is the only collaborator that is: rendering is pure, it is
 * the step whose failure must abort the store, and a stubbed renderer would let this class claim
 * "the stored bytes are the streamed bytes" about bytes no renderer ever produced.
 */
class PrintedFormServiceTest {

  private static final UUID PROFILE = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final Instant PRINTED_AT = Instant.parse("2026-09-14T08:45:00Z");

  private static final OperatorIdentity OPERATOR =
      new OperatorIdentity("op-1", OperatorAccessLevel.OPERATOR, "operator");
  private static final OperatorIdentity ADMIN =
      new OperatorIdentity("admin-1", OperatorAccessLevel.OPERATOR, "admin");
  private static final OperatorIdentity VIEWER =
      new OperatorIdentity("view-1", OperatorAccessLevel.VIEWER, "viewer");

  /**
   * A REAL UUID, because {@code OperatorIdentity.operatorId()} is one in production — {@code
   * OperatorIdentityFilter} builds it from {@code account.userId().toString()} and AD-002e requires
   * it to stay that way. The footer's name is resolved from it (AD-022 (n)), so a fixture that
   * carried something else could not exercise the lookup at all.
   */
  private static final UUID NAMED_OPERATOR_ID =
      UUID.fromString("33333333-3333-3333-3333-333333333333");

  private static final String NAMED_OPERATOR_USERNAME = "faheem.operator";

  private static final OperatorIdentity NAMED_OPERATOR =
      new OperatorIdentity(NAMED_OPERATOR_ID.toString(), OperatorAccessLevel.OPERATOR, "operator");

  private FakeProfiles profiles;
  private FakeRepository repository;
  private FakeOperatorUsers operatorUsers;
  private RecordingAuditWriter audit;
  private PlatformTransactionManager transactionManager;
  private PrintedFormService service;

  @BeforeEach
  void setUp() {
    profiles = new FakeProfiles();
    repository = new FakeRepository();
    operatorUsers = new FakeOperatorUsers();
    operatorUsers.put(NAMED_OPERATOR_ID, NAMED_OPERATOR_USERNAME);
    audit = new RecordingAuditWriter();
    transactionManager = new DirectTransactionManager();
    service =
        new PrintedFormService(
            profiles,
            repository,
            new FakeCatalog(),
            new PrintedFormRenderer(
                com.sfbank.bayanati.printedform.config.FopFactoryProvider.create(),
                new FoDocumentWriter()),
            audit,
            operatorUsers,
            Clock.fixed(PRINTED_AT, ZoneOffset.UTC),
            transactionManager);
    profiles.put(profile("submitted"));
  }

  // ---- authorisation --------------------------------------------------------------------------

  /**
   * AD-013 and wayfinder ticket 06: a viewer views everything and prints nothing. Enforced here as
   * well as at the security matcher — the matcher does coarse routing, this is the authorisation.
   */
  @Test
  void aViewerMayNotPrintAndNothingIsStoredOrAudited() {
    assertThatThrownBy(() -> service.print(VIEWER, PROFILE, false))
        .isInstanceOf(AccessLevelRequiredException.class);

    assertThat(repository.stored).isEmpty();
    assertThat(audit.events).isEmpty();
  }

  /**
   * An admin holds every operator power, and prints by holding OPERATOR access level — since AD-013
   * the hierarchy lives in the authority mapping, not in a separate level.
   *
   * <p>The role is what the CHAIN has to be able to tell apart, so the payload is asserted rather
   * than only the bytes: {@code actorId} attributes the print to an individual either way, but
   * R-054 lost separation of duties in the back office and {@code actorRole} is what lets the trail
   * answer "which hat were they wearing".
   */
  @Test
  void anAdminMayPrintAndTheEventSaysSo() {
    assertThat(service.print(ADMIN, PROFILE, false).bytes()).isNotEmpty();

    assertThat(audit.events.get(0).actorId()).isEqualTo("admin-1");
    assertThat(audit.events.get(0).payloadJson()).contains("\"actorRole\":\"admin\"");
  }

  /** A viewer may not re-download either: a re-download is a print in every way that matters. */
  @Test
  void aViewerMayNotReDownload() {
    assertThatThrownBy(() -> service.redownload(VIEWER, PROFILE, UUID.randomUUID()))
        .isInstanceOf(AccessLevelRequiredException.class);
    assertThat(audit.events).isEmpty();
  }

  // ---- status gating --------------------------------------------------------------------------

  /** Ticket 05 decision 8: only submitted and approved. */
  @Test
  void onlySubmittedAndApprovedProfilesMayBePrinted() {
    for (String printable : List.of("submitted", "approved")) {
      profiles.put(profile(printable));
      assertThat(service.print(OPERATOR, PROFILE, false).bytes()).isNotEmpty();
    }

    for (String refused : List.of("in_progress", "rejected", "abandoned", "blocked_scan")) {
      profiles.put(profile(refused));
      assertThatThrownBy(() -> service.print(OPERATOR, PROFILE, false))
          .as("status %s", refused)
          .isInstanceOf(ProfileNotPrintableException.class);
    }
  }

  /**
   * A refused status stores NOTHING. The two printable runs above stored two rows; the four refused
   * ones must add none — otherwise a refusal would still mint a stored copy of the densest PII
   * object in the system.
   */
  @Test
  void aRefusedStatusStoresNothing() {
    profiles.put(profile("in_progress"));
    assertThatThrownBy(() -> service.print(OPERATOR, PROFILE, false))
        .isInstanceOf(ProfileNotPrintableException.class);

    assertThat(repository.stored).isEmpty();
    assertThat(audit.events).isEmpty();
  }

  @Test
  void anUnknownProfileIsRefusedBeforeAnythingIsStored() {
    profiles.clear();
    assertThatThrownBy(() -> service.print(OPERATOR, PROFILE, false))
        .isInstanceOf(UnknownProfileException.class);
    assertThat(repository.stored).isEmpty();
  }

  // ---- store-then-stream ------------------------------------------------------------------------

  /**
   * Ticket 05 decisions 4 and 5, and the property the whole endpoint exists for: rendered ONCE, and
   * the bytes stored are the bytes the operator receives. A second render for the store would make
   * the stored file something nobody held — and ticket 03 established the render is not
   * byte-reproducible across runs, so nobody could ever prove otherwise afterwards.
   */
  @Test
  void theStoredBytesAreTheStreamedBytes() {
    PrintedFormService.PrintedForm printed = service.print(OPERATOR, PROFILE, false);

    assertThat(repository.stored).hasSize(1);
    StoredRow row = repository.stored.get(0);
    assertThat(row.bytes()).isEqualTo(printed.bytes());
    assertThat(row.artifactId()).isEqualTo(printed.artifactId());
    assertThat(new String(printed.bytes(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
  }

  /**
   * BL-163, closed by AD-022 (n): the footer names the operator by USERNAME, resolved here from the
   * user id the identity carries.
   *
   * <p>A direct assertion on the value, so it cannot pass against the defect — the footer printed
   * the 36-character user id until 2026-09-18, and this asserts both that the username is on the
   * page and that the id is not.
   *
   * <p>The id is unchanged everywhere else and that is the point of the design: {@link
   * OperatorIdentity} is not widened, so the audit payload this same print writes still carries the
   * UUID. AD-002e requires that, and {@link
   * #onePrintEventNamingTheKindTheVersionsAndTheCertificate} is where the audit side is asserted.
   */
  @Test
  void theFooterNamesTheOperatorByUsernameRatherThanByUserId() {
    PrintedFormService.PrintedForm printed = service.print(NAMED_OPERATOR, PROFILE, false);

    String text = pdfText(printed.bytes());
    assertThat(text).as("the footer names the operator -- AD-022 (n)").contains("faheem.operator");
    assertThat(text)
        .as("and the user id is not on the paper at all -- that is BL-163")
        .doesNotContain(NAMED_OPERATOR_ID.toString());
  }

  /**
   * No active account answers — the operator was disabled between this request being admitted and
   * the render running. The print SUCCEEDS and falls back to the id.
   *
   * <p>Deliberate: failing an operator's print because someone else disabled their account
   * mid-render is worse than printing the identifier the footer carried until today. Asserted so
   * that the fallback is a decision on the record rather than an accident of {@code Optional}.
   */
  @Test
  void aPrintStillSucceedsWhenNoActiveAccountAnswersForTheOperator() {
    OperatorIdentity unknown =
        new OperatorIdentity(
            UUID.fromString("44444444-4444-4444-4444-444444444444").toString(),
            OperatorAccessLevel.OPERATOR,
            "operator");

    PrintedFormService.PrintedForm printed = service.print(unknown, PROFILE, false);

    assertThat(pdfText(printed.bytes()))
        .as("the footer falls back to the id rather than the print failing")
        .contains("44444444-4444-4444-4444-444444444444");
  }

  private static String pdfText(byte[] pdf) {
    try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
      return new org.apache.pdfbox.text.PDFTextStripper().getText(document);
    } catch (java.io.IOException cannotRead) {
      throw new IllegalStateException("the service returned bytes that are not a PDF", cannotRead);
    }
  }

  /**
   * Every print is its own artifact and nothing is superseded (ticket 05 decision 2). Two prints of
   * one profile are two rows with two ids — which is what V0070's missing unique index makes
   * possible at the schema level and what this asserts at the service level.
   */
  @Test
  void aReprintIsANewArtifactRatherThanAReplacement() {
    UUID first = service.print(OPERATOR, PROFILE, false).artifactId();
    UUID second = service.print(OPERATOR, PROFILE, false).artifactId();

    assertThat(first).isNotEqualTo(second);
    assertThat(repository.stored).hasSize(2);
  }

  /**
   * ONE artifact kind, since AD-022 (S9-01) collapsed the two forms.
   *
   * <p>This replaces {@code eachVariantIsStoredUnderItsOwnKind}, which asserted that a print landed
   * under {@code printed_form} or {@code printed_form_attributed} according to the variant. There
   * is one form now, so the property worth pinning is that EVERY print — including a reprint —
   * stores {@code printed_form} and nothing else. It is the in-code half of V0072's narrowed {@code
   * artifact_ref_kind_check}: if this drifts, a print fails at the constraint rather than at
   * start-up.
   */
  @Test
  void everyPrintIsStoredUnderTheOnePrintedFormKind() {
    service.print(OPERATOR, PROFILE, false);
    service.print(OPERATOR, PROFILE, true);

    assertThat(repository.stored.stream().map(StoredRow::kind))
        .containsExactly("printed_form", "printed_form");
  }

  // ---- the audit record
  // ---------------------------------------------------------------------------

  /**
   * ONE event, not two (ticket 09 decision 2): store-then-stream in a single transaction makes
   * "rendered" and "delivered" the same instant. The payload must answer, years later, what was
   * printed — under which kind, against which reference versions, with or without the customer's
   * pay document.
   */
  @Test
  void onePrintEventNamingTheKindTheVersionsAndTheCertificate() {
    service.print(OPERATOR, PROFILE, true);

    assertThat(audit.events).hasSize(1);
    AuditEvent event = audit.events.get(0);
    assertThat(event.eventType()).isEqualTo("profile_form_printed");
    assertThat(event.chainKind()).isEqualTo("profile");
    assertThat(event.chainSubject()).isEqualTo(PROFILE.toString());
    assertThat(event.actorKind()).isEqualTo("operator");
    assertThat(event.actorId()).isEqualTo("op-1");

    assertThat(event.payloadJson())
        // `variant` was DROPPED from the payload by AD-022 (S9-01) rather than pinned to a
        // constant: a member that can only hold one value records nothing. Asserted absent so it
        // cannot be quietly reintroduced as a field the trail's readers would take as meaningful.
        .doesNotContain("\"variant\"")
        .contains("\"kind\":\"printed_form\"")
        .contains("\"attachmentsIncluded\":true")
        .contains("\"salaryCertificateIncluded\":true")
        .contains("\"salaryCertificateArtifactId\":\"" + FakeRepository.CERTIFICATE_ID + "\"")
        .contains("\"provenanceMatrixVersion\":2")
        // EVERY list the form resolves, not only the pinned ones. branch and education_level are
        // pinned by no writer, so they carry the latest: marker rather than being silently absent
        // -- an empty or partial string would read as "no lists used" rather than "resolved
        // against latest". income_source is unpinned in this fixture for the same exercise.
        .contains(
            "\"referenceListVersions\":\"admin_division=1,branch=latest:1,country=1,"
                + "education_level=latest:1,income_source=latest:1,occupation=1\"")
        .contains("\"actorRole\":\"operator\"")
        .contains("\"artifactId\":\"" + repository.stored.get(0).artifactId() + "\"");
  }

  /**
   * The certificate rides the print event as a flag, so an operator who declined it leaves a record
   * saying so. A payload that could not tell the two apart would defeat decision 6's stated purpose
   * — answering how many copies of that document exist.
   */
  @Test
  void decliningTheAttachmentsIsRecordedAsWellAsAcceptingThem() {
    service.print(OPERATOR, PROFILE, false);

    assertThat(audit.events.get(0).payloadJson())
        .contains("\"attachmentsIncluded\":false")
        .contains("\"salaryCertificateIncluded\":false")
        .contains("\"salaryCertificateArtifactId\":null");
    // And the certificate was not even READ, let alone printed.
    assertThat(repository.certificateReads).isZero();
  }

  /**
   * Nothing in a payload may be PII: {@code audit.audit_event.payload_json} is permanently
   * hash-chained and never erasable (V0002:52), unlike {@code audit_artifact.body}, which a lawful
   * purge can reach. Asserted against the fixture's own customer values rather than by inspecting
   * keys, because the risk is a value arriving through a field nobody thought about.
   */
  @Test
  void thePrintPayloadCarriesNoCustomerData() {
    service.print(OPERATOR, PROFILE, true);

    assertThat(audit.events.get(0).payloadJson())
        .doesNotContain("0000009999")
        .doesNotContain("+249912345678")
        .doesNotContain("SFB-000000777")
        .doesNotContain("sample.new@example.invalid");
  }

  /** A re-download is its OWN event type — the whole point of ticket 05 decision 6. */
  @Test
  void aReDownloadIsItsOwnEventTypeAndServesTheStoredBytes() {
    PrintedFormService.PrintedForm printed = service.print(OPERATOR, PROFILE, false);
    audit.events.clear();

    Optional<PrintedFormService.PrintedForm> again =
        service.redownload(OPERATOR, PROFILE, printed.artifactId());

    assertThat(again).isPresent();
    assertThat(again.get().bytes()).isEqualTo(printed.bytes());
    assertThat(audit.events).hasSize(1);
    assertThat(audit.events.get(0).eventType()).isEqualTo("profile_form_redownloaded");
    assertThat(audit.events.get(0).payloadJson())
        .contains("\"artifactId\":\"" + printed.artifactId() + "\"")
        .contains("\"actorRole\":\"operator\"");
  }

  /**
   * An unknown print writes NO event. The event records that a print WAS re-downloaded; a 404
   * served nothing, and recording one would make the trail claim something that did not happen.
   */
  @Test
  void aMissingPrintIsAnEmptyAnswerAndNoEvent() {
    assertThat(service.redownload(OPERATOR, PROFILE, UUID.randomUUID())).isEmpty();
    assertThat(audit.events).isEmpty();
  }

  /**
   * The property ticket 05 decision 5 actually asks for, and the one no earlier test reached:
   * <strong>a stored print that no event records must not survive.</strong>
   *
   * <p>A render failure proves nothing about the transaction — the render happens BEFORE the store,
   * so nothing would be stored with or without a transaction. The audit failure is the only order
   * in which the guarantee is load-bearing: the row is already inserted when the append throws. "An
   * untracked copy is a hole nothing can reconstruct" is the whole reason the decision exists.
   *
   * <p>Asserted through the transaction manager rather than through the repository, because the
   * fake repository's list is not transactional — what is proven is that the failure propagates and
   * the transaction is ROLLED BACK rather than committed, which is what a real datasource then acts
   * on. The integration class proves the same boundary against PostgreSQL for the render path.
   */
  @Test
  void anAuditFailureRollsTheTransactionBackRatherThanReturningAnUnrecordedPrint() {
    audit.failNext = true;

    assertThatThrownBy(() -> service.print(OPERATOR, PROFILE, false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("audit chain unavailable");

    DirectTransactionManager transactions = (DirectTransactionManager) transactionManager;
    assertThat(transactions.rolledBack).as("the print transaction was rolled back").isTrue();
    assertThat(transactions.committed).as("nothing was committed").isFalse();
  }

  // ---- the provenance matrix version
  // --------------------------------------------------------------

  /**
   * V0027/V0029's column has had no writer since it was created. This is it, and the print is the
   * first code in the system that resolves a profile's fields against {@code field-provenance.md}
   * at all.
   *
   * <p>Written ONCE: a reprint after the matrix moves on reads back what the first print pinned,
   * which is what "a reprint in a year says the same thing" asks for.
   */
  @Test
  void thePrintRecordsTheProvenanceMatrixVersionOnceAndReprintsReadItBack() {
    service.print(OPERATOR, PROFILE, false);
    repository.currentMatrixVersion = 3;
    service.print(OPERATOR, PROFILE, false);

    assertThat(repository.recordedMatrixVersion).isEqualTo(2);
    assertThat(audit.events.get(1).payloadJson()).contains("\"provenanceMatrixVersion\":2");
  }

  // ---- fixtures
  // ------------------------------------------------------------------------------------

  private static ProfileDetail profile(String status) {
    return new ProfileDetail(
        PROFILE.toString(),
        "SFB-000000777",
        "16",
        "0000009999",
        status,
        "digital",
        Instant.parse("2026-09-06T09:01:00Z"),
        Instant.parse("2026-09-06T08:00:00Z"),
        Instant.parse("2026-09-06T09:01:00Z"),
        customer(),
        List.<ChannelStateView>of(),
        null,
        null,
        null,
        List.of(),
        List.of(),
        SalaryCertificateState.PRESENT,
        List.of());
  }

  private static CustomerDataView customer() {
    return new CustomerDataView(
        "+249912345678",
        "sample.new@example.invalid",
        "m",
        "single",
        null,
        false,
        null,
        7,
        "25",
        1,
        76_000L,
        "passport",
        "قبيلة تجريبية",
        "SD",
        "SD",
        "KRT",
        null,
        "أم درمان",
        "SD",
        "KRT",
        "OMD",
        null,
        null,
        "أم درمان",
        null,
        null,
        null,
        null,
        "شركة تجريبية",
        "SD",
        null,
        "OMD",
        null,
        null,
        "المهدية",
        "الثورة",
        "8",
        null,
        List.of());
  }

  private record StoredRow(UUID artifactId, String kind, byte[] bytes) {}

  private static final class FakeProfiles implements ProfileViewRepository {
    private final Map<UUID, ProfileDetail> byId = new HashMap<>();

    void put(ProfileDetail detail) {
      byId.put(UUID.fromString(detail.profileId()), detail);
    }

    void clear() {
      byId.clear();
    }

    @Override
    public Optional<ProfileDetail> find(UUID profileId) {
      return Optional.ofNullable(byId.get(profileId));
    }
  }

  private static final class FakeRepository implements PrintedFormRepository {
    static final UUID CERTIFICATE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    final List<StoredRow> stored = new ArrayList<>();
    int currentMatrixVersion = 2;
    Integer recordedMatrixVersion;
    int certificateReads;

    @Override
    public Map<PrintedFormImageSlot, byte[]> images(UUID profileId) {
      return Map.of();
    }

    @Override
    public Optional<StoredArtifact> salaryCertificate(UUID profileId) {
      certificateReads++;
      return Optional.of(
          new StoredArtifact(CERTIFICATE_ID, "image/png", PrintedFormServiceTest.pngSwatch()));
    }

    @Override
    public Map<String, Integer> pinnedReferenceVersions(UUID profileId) {
      return Map.of("occupation", 1, "admin_division", 1, "country", 1);
    }

    /** What app.profile_field_edit holds; settable so a test can print a marked form. */
    private Set<Integer> editedFieldNumbers = Set.of();

    @Override
    public Set<Integer> editedFieldNumbers(UUID profileId) {
      return editedFieldNumbers;
    }

    @Override
    public int recordProvenanceMatrixVersion(UUID profileId) {
      if (recordedMatrixVersion == null) {
        recordedMatrixVersion = currentMatrixVersion;
      }
      return recordedMatrixVersion;
    }

    @Override
    public UUID storePrintedForm(UUID profileId, String kind, byte[] bytes, Instant now) {
      UUID artifactId = UUID.randomUUID();
      stored.add(new StoredRow(artifactId, kind, bytes));
      return artifactId;
    }

    @Override
    public Optional<StoredArtifact> findPrintedForm(UUID profileId, UUID artifactId) {
      return stored.stream()
          .filter(row -> row.artifactId().equals(artifactId))
          .findFirst()
          .map(row -> new StoredArtifact(row.artifactId(), "application/pdf", row.bytes()));
    }
  }

  /** A real PNG, so the renderer has something it can actually decode for the certificate page. */
  static byte[] pngSwatch() {
    java.awt.image.BufferedImage image =
        new java.awt.image.BufferedImage(40, 40, java.awt.image.BufferedImage.TYPE_INT_RGB);
    java.awt.Graphics2D graphics = image.createGraphics();
    graphics.setColor(new java.awt.Color(0xEE, 0xF2, 0xF7));
    graphics.fillRect(0, 0, 40, 40);
    graphics.dispose();
    try {
      java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
      javax.imageio.ImageIO.write(image, "PNG", png);
      return png.toByteArray();
    } catch (java.io.IOException cannotWrite) {
      throw new IllegalStateException(cannotWrite);
    }
  }

  private static final class FakeCatalog implements ReferenceCatalog {
    @Override
    public int currentVersion(String listCode) {
      return 1;
    }

    @Override
    public boolean versionExists(String listCode, int version) {
      return true;
    }

    @Override
    public boolean exists(String listCode, int version, String itemCode) {
      return true;
    }

    @Override
    public Optional<String> parentCode(String listCode, int version, String itemCode) {
      return Optional.empty();
    }

    @Override
    public Optional<ReferenceItemDetail> find(String listCode, int version, String itemCode) {
      return Optional.of(new ReferenceItemDetail("تسمية", "label", null));
    }

    @Override
    public Optional<ReferenceItemDetail> findByAlpha3(String listCode, int version, String alpha3) {
      // Only SDN resolves, so a test can tell a resolved country from a passed-through code.
      return "SDN".equalsIgnoreCase(alpha3)
          ? Optional.of(new ReferenceItemDetail("السودان", "Sudan", "{\"alpha3\":\"SDN\"}"))
          : Optional.empty();
    }

    @Override
    public Optional<String> currentRootItemCode(String listCode) {
      return Optional.empty();
    }
  }

  /**
   * The operator directory, for the footer's name only (AD-022 (n)).
   *
   * <p>Only {@code findActiveById} is implemented, because only that one is on the print path.
   * Everything else throws rather than returning a plausible empty answer — a fake that quietly
   * answers a question the code under test should never ask is how a dependency grows unnoticed.
   */
  private static final class FakeOperatorUsers implements OperatorUserRepository {

    private final Map<UUID, String> usernames = new HashMap<>();

    void put(UUID userId, String username) {
      usernames.put(userId, username);
    }

    @Override
    public Optional<OperatorAccount> findActiveById(UUID userId) {
      return Optional.ofNullable(usernames.get(userId))
          .map(
              username ->
                  new OperatorAccount(
                      userId,
                      username,
                      "اسم العرض",
                      OperatorRole.OPERATOR,
                      "not-a-real-hash",
                      false,
                      true));
    }

    @Override
    public Optional<OperatorAccount> findByUsername(String username) {
      throw new UnsupportedOperationException("not on the print path");
    }

    @Override
    public UUID create(
        String username,
        String displayName,
        OperatorRole role,
        String passwordHash,
        UUID createdBy) {
      throw new UnsupportedOperationException("not on the print path");
    }

    @Override
    public void updatePassword(UUID userId, String passwordHash, Instant changedAt) {
      throw new UnsupportedOperationException("not on the print path");
    }

    @Override
    public void recordSignIn(UUID userId, Instant at) {
      throw new UnsupportedOperationException("not on the print path");
    }

    @Override
    public void ensureOperatorChain(UUID userId) {
      throw new UnsupportedOperationException("not on the print path");
    }
  }

  private static final class RecordingAuditWriter implements AuditEventWriter {
    final List<AuditEvent> events = new ArrayList<>();

    /**
     * Makes the next append throw, standing in for what {@code JdbcAuditEventWriter} does when the
     * chain is unreachable or the append trigger refuses: it fails rather than returning quietly.
     */
    boolean failNext;

    @Override
    public long append(AuditEvent event) {
      if (failNext) {
        failNext = false;
        throw new IllegalStateException("audit chain unavailable");
      }
      events.add(event);
      return events.size();
    }

    @Override
    public long appendWithArtifact(AuditEvent event, AuditArtifact artifact) {
      throw new UnsupportedOperationException("the print writes no audit artifact");
    }
  }

  /**
   * Runs the callback with no real transaction. The service's transactional BOUNDARY is proven
   * against a real database in {@code PrintedFormIntegrationTest}; what this class proves is what
   * happens inside it, and a real transaction manager here would need a real DataSource.
   */
  private static final class DirectTransactionManager implements PlatformTransactionManager {

    boolean committed;
    boolean rolledBack;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      committed = true;
    }

    @Override
    public void rollback(TransactionStatus status) {
      rolledBack = true;
    }
  }
}
