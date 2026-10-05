package com.sfbank.bayanati.operator.jdbc;

import com.sfbank.bayanati.operator.domain.ExportResult;
import com.sfbank.bayanati.operator.domain.ExportRow;
import com.sfbank.bayanati.operator.domain.ProfileListFilter;
import com.sfbank.bayanati.operator.domain.ProfileListPage;
import com.sfbank.bayanati.operator.domain.ProfileListRepository;
import com.sfbank.bayanati.operator.domain.ProfileListResult;
import com.sfbank.bayanati.operator.domain.ProfileListSortField;
import com.sfbank.bayanati.operator.domain.ProfileSummary;
import com.sfbank.bayanati.operator.domain.SortOrder;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The profile list, entirely server-side (AD-006, BL-015). Uses {@link NamedParameterJdbcTemplate}
 * rather than this codebase's usual positional {@code JdbcTemplate} — the one repository in this
 * package whose query is genuinely dynamic (optional filters, one search term referenced many
 * times, a whitelisted sort column) enough that named, reusable parameters materially simplify it;
 * every simpler, fixed-shape query elsewhere keeps the positional style.
 *
 * <p><strong>Two queries, not a {@code count(*) OVER()} window column</strong>: a window count is
 * computed per returned row, so a page requested past the end of the matching set (a stale client
 * page number after the underlying data shrank, or simply the last partial page) returns zero rows
 * and, with it, no count at all — silently reporting {@code total = 0} for a filter that genuinely
 * matches rows elsewhere. A separate {@code COUNT(*)} against the same {@code WHERE} clause has no
 * such failure mode, at the cost of one extra round trip — correctness over a micro-optimisation on
 * a 100,000-row table (PROJECT_PLAN.md Constraints).
 *
 * <p><strong>Search field scope</strong> (operator.md: "any field or combination") — a deliberate,
 * named scope, not literally every column: account number, reference number, phone, email, branch
 * code and label, national/identity number, the on-document name (Arabic + English), the Civil
 * Registry name chain (Arabic paternal chain + English), status label (Arabic + English), and the
 * rejection-reason code/label via the profile's status history. Arabic columns compare through
 * {@code ref.ar_fold} on both sides; everything else through a plain case-insensitive {@code
 * ILIKE}. No new functional indexes: at the stated scale a sequential scan is not a measured
 * problem — add one if it ever becomes one.
 */
@Repository
public class JdbcProfileListRepository implements ProfileListRepository {

  private static final String ENSURE_OPERATOR_CHAIN =
      "SELECT audit.ensure_operator_chain(:operatorId)";

  private static final String SELECT_COLUMNS =
      """
      SELECT p.profile_id, p.account_number, p.branch_code, p.status,
             app.derived_provenance(p.profile_id, p.provenance) AS provenance,
             p.submitted_at, p.created_at,
             COALESCE(NULLIF(trim(concat_ws(' ', rr.name_ar_given, rr.name_ar_father,
                                             rr.name_ar_grandfather, rr.name_ar_great_grandfather)), ''),
                      sr.name_ar_on_document) AS display_name_ar,
             COALESCE(NULLIF(trim(concat_ws(' ', rr.first_names_en, rr.last_name_en)), ''),
                      sr.name_en_on_document) AS display_name_en
      """;

  private static final String COUNT_COLUMN = "SELECT count(*)";

  // The last two LEFT JOINs (latest-rejection code + its label) exist only for
  // EXPORT_SELECT_COLUMNS -- search()/COUNT_COLUMN never select those columns. Kept in this one
  // shared FROM_JOINS rather than a second copy so forExport() reuses the exact same
  // filter-building code as search() (AD-006/BL-015: "reuses that query rather than
  // reimplementing it"). Harmless to search(): LEFT JOINs, no row fan-out (the LATERAL subquery
  // is capped at one row per profile).
  private static final String FROM_JOINS =
      """
      FROM app.profile p
      JOIN app.status_code sc ON sc.code = p.status
      LEFT JOIN app.profile_customer_data pcd ON pcd.profile_id = p.profile_id
      LEFT JOIN LATERAL (
        SELECT cycle_id FROM app.identity_cycle
         WHERE profile_id = p.profile_id ORDER BY seq DESC LIMIT 1
      ) ic ON true
      LEFT JOIN app.scan_result sr ON sr.cycle_id = ic.cycle_id
      LEFT JOIN app.registry_result rr ON rr.cycle_id = ic.cycle_id
      LEFT JOIN ref.reference_item brn
        ON brn.list_code = 'branch' AND brn.item_code = p.branch_code
       AND brn.version = (SELECT rlv.version FROM ref.reference_list_version rlv
                            WHERE rlv.list_code = 'branch' AND rlv.is_current)
      LEFT JOIN LATERAL (
        SELECT reason_code, reason_version FROM app.profile_status_history
         WHERE profile_id = p.profile_id AND to_status = 'rejected'
         ORDER BY seq DESC LIMIT 1
      ) rej ON true
      LEFT JOIN ref.reference_item rri
        ON rri.list_code = 'rejection_reason' AND rri.version = rej.reason_version
       AND rri.item_code = rej.reason_code
      WHERE 1 = 1
      """;

  private static final String EXPORT_SELECT_COLUMNS =
      """
      SELECT p.reference_number, p.account_number, p.branch_code,
             brn.label_en AS branch_label_en, brn.label_ar AS branch_label_ar,
             p.status, sc.label_en AS status_label_en,
             app.derived_provenance(p.profile_id, p.provenance) AS provenance,
             pcd.phone_number, pcd.email_address,
             COALESCE(NULLIF(trim(concat_ws(' ', rr.name_ar_given, rr.name_ar_father,
                                             rr.name_ar_grandfather, rr.name_ar_great_grandfather)), ''),
                      sr.name_ar_on_document) AS name_ar,
             COALESCE(NULLIF(trim(concat_ws(' ', rr.first_names_en, rr.last_name_en)), ''),
                      sr.name_en_on_document) AS name_en,
             sr.identity_number AS national_number,
             p.submitted_at, p.created_at,
             rej.reason_code AS rejection_reason_code, rri.label_en AS rejection_reason_label_en
      """;

  private static final String SEARCH_PREDICATE =
      """
      AND (
        p.account_number ILIKE :searchLike
        OR p.reference_number ILIKE :searchLike
        OR pcd.phone_number ILIKE :searchLike
        OR pcd.email_address ILIKE :searchLike
        OR p.branch_code ILIKE :searchLike
        OR brn.label_en ILIKE :searchLike
        OR (brn.label_ar IS NOT NULL AND ref.ar_fold(brn.label_ar) LIKE '%' || ref.ar_fold(:search) || '%')
        OR sr.identity_number ILIKE :searchLike
        OR sr.name_en_on_document ILIKE :searchLike
        OR (sr.name_ar_on_document IS NOT NULL
            AND ref.ar_fold(sr.name_ar_on_document) LIKE '%' || ref.ar_fold(:search) || '%')
        OR concat_ws(' ', rr.first_names_en, rr.last_name_en) ILIKE :searchLike
        OR ref.ar_fold(concat_ws(' ', rr.name_ar_given, rr.name_ar_father, rr.name_ar_grandfather,
                                  rr.name_ar_great_grandfather)) LIKE '%' || ref.ar_fold(:search) || '%'
        OR sc.label_en ILIKE :searchLike
        OR ref.ar_fold(sc.label_ar) LIKE '%' || ref.ar_fold(:search) || '%'
        OR EXISTS (
             SELECT 1 FROM app.profile_status_history h2
             LEFT JOIN ref.reference_item ri2
               ON ri2.list_code = 'rejection_reason' AND ri2.version = h2.reason_version
              AND ri2.item_code = h2.reason_code
             WHERE h2.profile_id = p.profile_id
               AND (h2.reason_code ILIKE :searchLike
                    OR ri2.label_en ILIKE :searchLike
                    OR (ri2.label_ar IS NOT NULL
                        AND ref.ar_fold(ri2.label_ar) LIKE '%' || ref.ar_fold(:search) || '%'))
           )
      )
      """;

  private final NamedParameterJdbcTemplate jdbcTemplate;

  public JdbcProfileListRepository(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void ensureOperatorChain(String operatorId) {
    jdbcTemplate.query(
        ENSURE_OPERATOR_CHAIN, new MapSqlParameterSource("operatorId", operatorId), rs -> null);
  }

  @Override
  public ProfileListResult search(
      ProfileListFilter filter,
      ProfileListSortField sortField,
      SortOrder sortOrder,
      ProfileListPage page) {
    StringBuilder where = new StringBuilder(FROM_JOINS);
    MapSqlParameterSource params = new MapSqlParameterSource();
    appendFilters(where, params, filter);

    Long total = jdbcTemplate.queryForObject(COUNT_COLUMN + " " + where, params, Long.class);

    StringBuilder dataSql = new StringBuilder(SELECT_COLUMNS).append(' ').append(where);
    // p.profile_id ASC is a tiebreaker, not a sort preference: none of the four whitelisted sort
    // columns is unique (every incomplete profile shares submitted_at IS NULL; branch/status can
    // repeat across the whole table), and ORDER BY on a non-unique key gives PostgreSQL no
    // guarantee that two separate queries (this page, the next) order a tied group the same way --
    // found live, paging through an all-in_progress filter under the default sort could return the
    // same profile on two different pages and never surface another.
    dataSql
        .append(" ORDER BY ")
        .append(sortColumn(sortField))
        .append(' ')
        .append(sortOrder.name())
        .append(" NULLS LAST, p.profile_id ASC");
    dataSql.append(" LIMIT :limit OFFSET :offset");
    params.addValue("limit", page.pageSize());
    params.addValue("offset", page.offset());

    List<ProfileSummary> rows =
        jdbcTemplate.query(
            dataSql.toString(),
            params,
            (rs, rowNum) ->
                new ProfileSummary(
                    rs.getString("profile_id"),
                    rs.getString("account_number"),
                    rs.getString("branch_code"),
                    rs.getString("display_name_ar"),
                    rs.getString("display_name_en"),
                    rs.getString("status"),
                    rs.getString("provenance"),
                    toInstant(rs.getTimestamp("submitted_at")),
                    toInstant(rs.getTimestamp("created_at"))));

    return new ProfileListResult(rows, total == null ? 0L : total);
  }

  @Override
  public ExportResult forExport(ProfileListFilter filter, int rowLimit) {
    StringBuilder where = new StringBuilder(FROM_JOINS);
    MapSqlParameterSource params = new MapSqlParameterSource();
    appendFilters(where, params, filter);

    StringBuilder dataSql = new StringBuilder(EXPORT_SELECT_COLUMNS).append(' ').append(where);
    // Same default sort and tiebreaker as search() -- determinism across a filter matching many
    // submitted_at IS NULL rows, not a client-chosen sort (export takes filters only).
    dataSql.append(" ORDER BY p.submitted_at DESC NULLS LAST, p.profile_id ASC");
    dataSql.append(" LIMIT :limit");
    // One more than rowLimit: if it comes back, more rows matched than the cap allows, without a
    // second COUNT(*) round trip to find out.
    params.addValue("limit", rowLimit + 1);

    List<ExportRow> rows =
        jdbcTemplate.query(dataSql.toString(), params, (rs, rowNum) -> mapExportRow(rs));
    boolean truncated = rows.size() > rowLimit;
    List<ExportRow> capped = truncated ? rows.subList(0, rowLimit) : rows;
    return new ExportResult(List.copyOf(capped), truncated);
  }

  /**
   * Applies every {@link ProfileListFilter} predicate to {@code where}/{@code params} — shared by
   * {@link #search} and {@link #forExport} (AD-006/BL-015: "reuses that query rather than
   * reimplementing it — two filter implementations will disagree").
   */
  private static void appendFilters(
      StringBuilder where, MapSqlParameterSource params, ProfileListFilter filter) {
    if (filter.status() != null) {
      where.append(" AND p.status = :status");
      params.addValue("status", filter.status());
    }
    if (filter.provenance() != null) {
      // BL-135/BL-155: the filter must match what the LIST SHOWS. Filtering the stored column
      // while displaying the derived value would return a row set that contradicts its own
      // provenance chip -- and, since nothing writes the column any more, would return nothing
      // at all for "manual".
      where.append(" AND app.derived_provenance(p.profile_id, p.provenance) = :provenance");
      params.addValue("provenance", filter.provenance());
    }
    if (filter.branchCode() != null) {
      where.append(" AND p.branch_code = :branchCode");
      params.addValue("branchCode", filter.branchCode());
    }
    if (filter.rejectionReasonCode() != null) {
      where.append(
          " AND EXISTS (SELECT 1 FROM app.profile_status_history h3"
              + " WHERE h3.profile_id = p.profile_id AND h3.reason_code = :rejectionReasonCode)");
      params.addValue("rejectionReasonCode", filter.rejectionReasonCode());
    }
    if (filter.submittedFrom() != null) {
      where.append(" AND p.submitted_at >= :submittedFrom");
      params.addValue("submittedFrom", Timestamp.from(filter.submittedFrom()));
    }
    if (filter.submittedTo() != null) {
      where.append(" AND p.submitted_at <= :submittedTo");
      params.addValue("submittedTo", Timestamp.from(filter.submittedTo()));
    }
    if (filter.searchText() != null && !filter.searchText().isBlank()) {
      // The leading space matters: SEARCH_PREDICATE's text block starts immediately with "AND (",
      // no leading whitespace, and without one a preceding filter's own trailing ":paramName"
      // (e.g. ":status") concatenates directly onto it -- NamedParameterJdbcTemplate's parser then
      // reads "AND" as part of the parameter's own name ("statusAND"), found live: "No value
      // supplied for the SQL parameter 'statusAND'" when a status filter and a search term were
      // combined in the same request.
      where.append(' ').append(SEARCH_PREDICATE);
      params.addValue("search", filter.searchText());
      params.addValue("searchLike", "%" + filter.searchText() + "%");
    }
  }

  private ExportRow mapExportRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new ExportRow(
        rs.getString("reference_number"),
        rs.getString("account_number"),
        rs.getString("branch_code"),
        rs.getString("branch_label_en"),
        rs.getString("branch_label_ar"),
        rs.getString("status"),
        rs.getString("status_label_en"),
        rs.getString("provenance"),
        rs.getString("phone_number"),
        rs.getString("email_address"),
        rs.getString("name_ar"),
        rs.getString("name_en"),
        rs.getString("national_number"),
        toInstant(rs.getTimestamp("submitted_at")),
        toInstant(rs.getTimestamp("created_at")),
        rs.getString("rejection_reason_code"),
        rs.getString("rejection_reason_label_en"));
  }

  /** Whitelisted column expressions — never a client-supplied fragment (AD-006). */
  private static String sortColumn(ProfileListSortField sortField) {
    return switch (sortField) {
      case SUBMITTED_AT -> "p.submitted_at";
      case ACCOUNT_NUMBER -> "p.account_number";
      case STATUS -> "p.status";
      case BRANCH -> "p.branch_code";
    };
  }

  private static java.time.Instant toInstant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
