package com.sfbank.bayanati.operator.jdbc;

import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.operator.domain.ArtifactRefView;
import com.sfbank.bayanati.operator.domain.ChannelStateView;
import com.sfbank.bayanati.operator.domain.CustomerDataView;
import com.sfbank.bayanati.operator.domain.FaceResultView;
import com.sfbank.bayanati.operator.domain.IncomeSourceView;
import com.sfbank.bayanati.operator.domain.ProfileDetail;
import com.sfbank.bayanati.operator.domain.ProfileViewRepository;
import com.sfbank.bayanati.operator.domain.RegistryResultView;
import com.sfbank.bayanati.operator.domain.SalaryCertificateState;
import com.sfbank.bayanati.operator.domain.ScanResultView;
import com.sfbank.bayanati.operator.domain.StatusHistoryEntryView;
import com.sfbank.bayanati.profile.domain.ChannelState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads everything operator.md's "single profile view" lists, as {@code fru_app}. One query for the
 * 1:1 rows (the profile itself, its customer data, and its most recent identity cycle's
 * scan/face/registry results), and one query each for the 1:many rows (income sources, channels,
 * artifacts, status history) — a single mega-join across all of these would multiply rows across
 * every 1:many relation at once.
 *
 * <p>"Most recent identity cycle" (by {@code seq}), not only {@code state = 'active'}: a profile
 * whose cycle was superseded by a device-less-resume rescan should still show whatever it last had,
 * per the same "incomplete profiles are visible" principle the list applies (see {@code
 * ProfileListRepository}).
 */
@Repository
public class JdbcProfileViewRepository implements ProfileViewRepository {

  private static final String PROFILE_ROW =
      """
      SELECT p.profile_id, p.reference_number, p.branch_code, p.account_number, p.status,
             app.derived_provenance(p.profile_id, p.provenance) AS provenance,
             p.submitted_at, p.created_at, p.last_activity_at,
             pcd.phone_number, pcd.email_address, pcd.sex_declared, pcd.marital_status, pcd.spouse_name,
             pcd.has_children, pcd.children_count, pcd.education_level, pcd.occupation_code,
             pcd.occupation_version, pcd.monthly_expenses_sdg, pcd.identity_type, pcd.ethnicity,
             pcd.country_of_residence_code, pcd.birth_country_code, pcd.birth_state_code,
             pcd.birth_state_text, pcd.birth_city_text,
             pcd.home_country_code, pcd.home_state_code, pcd.home_locality_code,
             pcd.home_state_text, pcd.home_locality_text, pcd.home_city, pcd.home_area,
             pcd.home_street, pcd.home_block, pcd.home_house_no,
             pcd.employer_name, pcd.work_country_code, pcd.work_state_code, pcd.work_locality_code,
             pcd.work_state_text, pcd.work_locality_text, pcd.work_city, pcd.work_area,
             pcd.work_street, pcd.work_block, pcd.salary_certificate_claimed_at,
             -- BL-122. Mirrors the ARTIFACTS query's own predicate: profile-keyed (a certificate
             -- has no cycle, V0008) and state = 'committed', so PRESENT always agrees with whether
             -- the contact sheet rendered a tile. EXISTS rather than a join -- the artifact list is
             -- fetched separately and this only needs the yes/no.
             --
             -- That agreement is all the state filter buys, and the fall-through is where a purged
             -- row is read: with the claim still standing it answers ATTACH_FAILED, which is a
             -- false statement about a certificate the bank received and deleted under retention.
             -- Narrow (V0055 needs 90 days abandoned) and filed as BL-142 rather than papered over.
             EXISTS (SELECT 1 FROM app.artifact_ref sc
                      WHERE sc.profile_id = p.profile_id
                        AND sc.kind = 'salary_certificate'
                        AND sc.state = 'committed') AS has_salary_certificate,
             sr.document_type, sr.card_variant, sr.identity_number, sr.document_number, sr.mrz_verified,
             sr.nationality, sr.sex_on_document, sr.date_of_birth AS scan_date_of_birth,
             sr.date_of_issue, sr.date_of_expiry, sr.place_of_issue, sr.issuing_country,
             sr.name_ar_on_document, sr.name_en_on_document, sr.blood_type, sr.birth_city,
             sr.received_at AS scan_received_at,
             fr.match, fr.match_level, fr.threshold_applied, fr.passed, fr.received_at AS face_received_at,
             rr.state AS registry_state, rr.name_ar_given, rr.name_ar_father, rr.name_ar_grandfather,
             rr.name_ar_great_grandfather, rr.name_ar_mother, rr.name_ar_mother_father,
             rr.name_ar_mother_grandfather, rr.name_ar_mother_great_grandfather,
             rr.first_names_en, rr.last_name_en, rr.sex_registry,
             rr.date_of_birth AS registry_date_of_birth, rr.raw_address_ar,
             rr.identity_number_returned
        FROM app.profile p
        LEFT JOIN app.profile_customer_data pcd ON pcd.profile_id = p.profile_id
        LEFT JOIN LATERAL (
          SELECT cycle_id FROM app.identity_cycle
           WHERE profile_id = p.profile_id ORDER BY seq DESC LIMIT 1
        ) ic ON true
        LEFT JOIN app.scan_result sr ON sr.cycle_id = ic.cycle_id
        LEFT JOIN app.face_result fr ON fr.cycle_id = ic.cycle_id
        LEFT JOIN app.registry_result rr ON rr.cycle_id = ic.cycle_id
       WHERE p.profile_id = ?::uuid
      """;

  private static final String INCOME_SOURCES =
      """
      SELECT source_code, is_primary, other_text FROM app.profile_income_source
       WHERE profile_id = ?::uuid ORDER BY source_code
      """;

  private static final String CHANNELS =
      """
      SELECT channel, state, verified_at FROM app.profile_channel
       WHERE profile_id = ?::uuid ORDER BY channel
      """;

  // state = 'committed' excludes 'staged' (an in-flight upload the stage-8 chain never
  // promoted, V0008's comment: "a sweeper (not built this session) reclaims anything left
  // staged" -- still true, no writer emits 'staged' today) and, since S5-06,
  // 'purged' -- app.purge_abandoned_artifacts() now genuinely sets this on a real 90-day-
  // abandoned profile's rows, so this filter is load-bearing: without it, a purged row (its
  // body already NULLed) would still surface here with a storage_key/sha256 that no longer
  // resolves to any bytes.
  private static final String ARTIFACTS =
      """
      SELECT ar.artifact_ref_id, ar.kind, ar.storage_key, ar.content_type, ar.byte_size, ar.sha256,
             sr2.document_type
        FROM app.artifact_ref ar
        LEFT JOIN app.scan_result sr2 ON sr2.cycle_id = ar.cycle_id
       WHERE (ar.profile_id = ?::uuid
          OR ar.cycle_id IN (SELECT cycle_id FROM app.identity_cycle WHERE profile_id = ?::uuid))
         AND ar.state = 'committed'
       ORDER BY ar.created_at
      """;

  private static final String STATUS_HISTORY =
      """
      SELECT h.seq, h.from_status, h.to_status, h.occurred_at, h.actor_kind, h.actor_id,
             h.reason_code, h.internal_note, h.is_manual_completion,
             ri.label_ar AS reason_label_ar, ri.label_en AS reason_label_en
        FROM app.profile_status_history h
        LEFT JOIN ref.reference_item ri
          ON ri.list_code = 'rejection_reason' AND ri.version = h.reason_version AND ri.item_code = h.reason_code
       WHERE h.profile_id = ?::uuid
       ORDER BY h.seq
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcProfileViewRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<ProfileDetail> find(UUID profileId) {
    List<ProfileDetail> rows =
        jdbcTemplate.query(PROFILE_ROW, (rs, rowNum) -> mapProfileRow(rs), profileId.toString());
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    ProfileDetail base = rows.get(0);

    List<IncomeSourceView> incomeSources =
        jdbcTemplate.query(
            INCOME_SOURCES,
            (rs, n) ->
                new IncomeSourceView(
                    rs.getString("source_code"),
                    rs.getBoolean("is_primary"),
                    rs.getString("other_text")),
            profileId.toString());
    CustomerDataView customerDataWithIncome = withIncomeSources(base.customerData(), incomeSources);

    List<ChannelStateView> channels =
        jdbcTemplate.query(
            CHANNELS,
            (rs, n) ->
                new ChannelStateView(
                    MessageChannel.fromWireValue(rs.getString("channel")),
                    ChannelState.fromWireValue(rs.getString("state")),
                    toInstant(rs, "verified_at")),
            profileId.toString());

    List<ArtifactRefView> artifacts =
        jdbcTemplate.query(
            ARTIFACTS, (rs, n) -> mapArtifact(rs), profileId.toString(), profileId.toString());

    List<StatusHistoryEntryView> statusHistory =
        jdbcTemplate.query(STATUS_HISTORY, (rs, n) -> mapHistoryEntry(rs), profileId.toString());

    return Optional.of(
        new ProfileDetail(
            base.profileId(),
            base.referenceNumber(),
            base.branchCode(),
            base.accountNumber(),
            base.status(),
            base.provenance(),
            base.submittedAt(),
            base.createdAt(),
            base.lastActivityAt(),
            customerDataWithIncome,
            channels,
            base.scanResult(),
            base.faceResult(),
            base.registryResult(),
            artifacts,
            statusHistory,
            base.salaryCertificateState(),
            // Derived by OperatorProfileViewService, not here: EditableFieldPolicy is business
            // logic and a jdbc package is plumbing (CLAUDE.md's package rule).
            List.of()));
  }

  private ProfileDetail mapProfileRow(ResultSet rs) throws SQLException {
    // A profile always has an app.profile_customer_data row from Stage 1b onward (S3-06) -- the
    // LEFT JOIN exists only so a profile row is still returned if that were ever not true, not
    // because this column set is expected to be absent.
    CustomerDataView customerData = mapCustomerData(rs);

    boolean hasScan = rs.getString("document_type") != null;
    ScanResultView scanResult = hasScan ? mapScanResult(rs) : null;

    Boolean match = rs.getObject("match", Boolean.class);
    FaceResultView faceResult =
        match == null
            ? null
            : new FaceResultView(
                match,
                rs.getObject("match_level", Integer.class),
                rs.getObject("threshold_applied", Integer.class),
                rs.getBoolean("passed"),
                toInstant(rs, "face_received_at"));

    String registryState = rs.getString("registry_state");
    RegistryResultView registryResult =
        registryState == null ? null : mapRegistryResult(rs, registryState);

    return new ProfileDetail(
        rs.getString("profile_id"),
        rs.getString("reference_number"),
        rs.getString("branch_code"),
        rs.getString("account_number"),
        rs.getString("status"),
        rs.getString("provenance"),
        toInstant(rs, "submitted_at"),
        toInstant(rs, "created_at"),
        toInstant(rs, "last_activity_at"),
        customerData,
        List.of(),
        scanResult,
        faceResult,
        registryResult,
        List.of(),
        List.of(),
        // BL-122. Resolved from this row alone -- the committed-artifact EXISTS, the claim, and
        // whether Stage 6 ever arrived (employer_name) -- so it needs none of the three list
        // queries that `find` runs separately and is carried through them unchanged.
        SalaryCertificateState.resolve(
            rs.getBoolean("has_salary_certificate"),
            toInstant(rs, "salary_certificate_claimed_at") != null,
            rs.getString("employer_name") != null),
        List.of());
  }

  private CustomerDataView mapCustomerData(ResultSet rs) throws SQLException {
    return new CustomerDataView(
        rs.getString("phone_number"),
        rs.getString("email_address"),
        rs.getString("sex_declared"),
        rs.getString("marital_status"),
        rs.getString("spouse_name"),
        rs.getObject("has_children", Boolean.class),
        rs.getObject("children_count", Integer.class),
        rs.getObject("education_level", Integer.class),
        rs.getString("occupation_code"),
        rs.getObject("occupation_version", Integer.class),
        rs.getObject("monthly_expenses_sdg", Long.class),
        rs.getString("identity_type"),
        rs.getString("ethnicity"),
        rs.getString("country_of_residence_code"),
        rs.getString("birth_country_code"),
        rs.getString("birth_state_code"),
        rs.getString("birth_state_text"),
        rs.getString("birth_city_text"),
        rs.getString("home_country_code"),
        rs.getString("home_state_code"),
        rs.getString("home_locality_code"),
        rs.getString("home_state_text"),
        rs.getString("home_locality_text"),
        rs.getString("home_city"),
        rs.getString("home_area"),
        rs.getString("home_street"),
        rs.getString("home_block"),
        rs.getString("home_house_no"),
        rs.getString("employer_name"),
        rs.getString("work_country_code"),
        rs.getString("work_state_code"),
        rs.getString("work_locality_code"),
        rs.getString("work_state_text"),
        rs.getString("work_locality_text"),
        rs.getString("work_city"),
        rs.getString("work_area"),
        rs.getString("work_street"),
        rs.getString("work_block"),
        List.of());
  }

  private static CustomerDataView withIncomeSources(
      CustomerDataView base, List<IncomeSourceView> incomeSources) {
    return new CustomerDataView(
        base.phoneNumber(),
        base.emailAddress(),
        base.sexDeclared(),
        base.maritalStatus(),
        base.spouseName(),
        base.hasChildren(),
        base.childrenCount(),
        base.educationLevel(),
        base.occupationCode(),
        base.occupationVersion(),
        base.monthlyExpensesSdg(),
        base.identityType(),
        base.ethnicity(),
        base.countryOfResidenceCode(),
        base.birthCountryCode(),
        base.birthStateCode(),
        base.birthStateText(),
        base.birthCityText(),
        base.homeCountryCode(),
        base.homeStateCode(),
        base.homeLocalityCode(),
        base.homeStateText(),
        base.homeLocalityText(),
        base.homeCity(),
        base.homeArea(),
        base.homeStreet(),
        base.homeBlock(),
        base.homeHouseNo(),
        base.employerName(),
        base.workCountryCode(),
        base.workStateCode(),
        base.workLocalityCode(),
        base.workStateText(),
        base.workLocalityText(),
        base.workCity(),
        base.workArea(),
        base.workStreet(),
        base.workBlock(),
        incomeSources);
  }

  private ScanResultView mapScanResult(ResultSet rs) throws SQLException {
    return new ScanResultView(
        rs.getString("document_type"),
        rs.getString("card_variant"),
        rs.getString("identity_number"),
        rs.getString("document_number"),
        rs.getObject("mrz_verified", Boolean.class),
        rs.getString("nationality"),
        rs.getString("sex_on_document"),
        toLocalDate(rs, "scan_date_of_birth"),
        toLocalDate(rs, "date_of_issue"),
        toLocalDate(rs, "date_of_expiry"),
        rs.getString("place_of_issue"),
        rs.getString("issuing_country"),
        rs.getString("name_ar_on_document"),
        rs.getString("name_en_on_document"),
        rs.getString("blood_type"),
        rs.getString("birth_city"),
        toInstant(rs, "scan_received_at"));
  }

  private RegistryResultView mapRegistryResult(ResultSet rs, String state) throws SQLException {
    return new RegistryResultView(
        state,
        rs.getString("name_ar_given"),
        rs.getString("name_ar_father"),
        rs.getString("name_ar_grandfather"),
        rs.getString("name_ar_great_grandfather"),
        rs.getString("name_ar_mother"),
        rs.getString("name_ar_mother_father"),
        rs.getString("name_ar_mother_grandfather"),
        rs.getString("name_ar_mother_great_grandfather"),
        rs.getString("first_names_en"),
        rs.getString("last_name_en"),
        rs.getString("sex_registry"),
        toLocalDate(rs, "registry_date_of_birth"),
        rs.getString("raw_address_ar"),
        rs.getString("identity_number_returned"));
  }

  private ArtifactRefView mapArtifact(ResultSet rs) throws SQLException {
    String kind = rs.getString("kind");
    String documentType = rs.getString("document_type");
    return new ArtifactRefView(
        rs.getString("artifact_ref_id"),
        kind,
        labelFor(kind, documentType),
        rs.getString("storage_key"),
        rs.getString("content_type"),
        rs.getLong("byte_size"),
        HexFormat.of().formatHex(rs.getBytes("sha256")));
  }

  /** operator.md: "each labelled with its origin". */
  private static String labelFor(String kind, String documentType) {
    return switch (kind) {
      case "portrait_registry" -> "Civil Registry";
      // app.scan_result.document_type stores Uqudo's own vocabulary ("PASSPORT"/"SDN_ID", V0008's
      // comment), not identityscan.domain.DocumentTypes' app-level "passport"/"national_id".
      case "portrait_uqudo" ->
          "Uqudo — " + ("PASSPORT".equals(documentType) ? "passport" : "national ID");
      case "face_audit_trail" -> "Uqudo — liveness audit trail";
      case "signature" -> "Signature";
      case "salary_certificate" -> "Salary certificate";
      case "doc_front" -> "Uqudo — document front";
      case "doc_back" -> "Uqudo — document back";
      case "doc_front_frame" -> "Uqudo — document front (video frame)";
      case "doc_back_frame" -> "Uqudo — document back (video frame)";
      default -> kind;
    };
  }

  private StatusHistoryEntryView mapHistoryEntry(ResultSet rs) throws SQLException {
    return new StatusHistoryEntryView(
        rs.getInt("seq"),
        rs.getString("from_status"),
        rs.getString("to_status"),
        toInstant(rs, "occurred_at"),
        rs.getString("actor_kind"),
        rs.getString("actor_id"),
        rs.getString("reason_code"),
        rs.getString("reason_label_ar"),
        rs.getString("reason_label_en"),
        rs.getString("internal_note"),
        rs.getBoolean("is_manual_completion"));
  }

  private static java.time.Instant toInstant(ResultSet rs, String column) throws SQLException {
    java.sql.Timestamp ts = rs.getTimestamp(column);
    return ts == null ? null : ts.toInstant();
  }

  private static java.time.LocalDate toLocalDate(ResultSet rs, String column) throws SQLException {
    java.sql.Date date = rs.getDate(column);
    return date == null ? null : date.toLocalDate();
  }
}
