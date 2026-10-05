package com.sfbank.bayanati.dataentry.jdbc;

import com.sfbank.bayanati.dataentry.domain.CustomerDataSnapshot;
import com.sfbank.bayanati.dataentry.domain.DataEntryRepository;
import com.sfbank.bayanati.dataentry.domain.IncomeSourceRow;
import com.sfbank.bayanati.dataentry.domain.ProfileLock;
import com.sfbank.bayanati.dataentry.domain.Stage3Fields;
import com.sfbank.bayanati.dataentry.domain.Stage5Fields;
import com.sfbank.bayanati.dataentry.domain.Stage6Fields;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes {@code app.profile_customer_data} and {@code app.profile_income_source} as {@code
 * fru_app}. The single place in the application that knows these two tables' shapes for stages 3-6,
 * mirroring {@code JdbcProfileRepository}'s single-writer discipline.
 */
@Repository
public class JdbcDataEntryRepository implements DataEntryRepository {

  private static final String LOCK_AND_GET_STATUS =
      """
      SELECT p.status, sc.is_terminal
        FROM app.profile p
        JOIN app.status_code sc ON sc.code = p.status
       WHERE p.profile_id = ?::uuid
         FOR UPDATE OF p
      """;

  private static final String CURRENT_CUSTOMER_DATA =
      """
      SELECT sex_declared, ethnicity, country_of_residence_code, marital_status, spouse_name,
             has_children, children_count, education_level, birth_country_code, birth_state_code,
             birth_state_text, birth_city_text, occupation_code, monthly_expenses_sdg,
             home_country_code, home_state_code, home_state_text, home_locality_code,
             home_locality_text, home_city, home_area, home_street, home_block, home_house_no,
             employer_name, work_country_code, work_state_code, work_state_text,
             work_locality_code, work_locality_text, work_city, work_area, work_street, work_block,
             identity_type
        FROM app.profile_customer_data
       WHERE profile_id = ?::uuid
      """;

  private static final String CURRENT_INCOME_SOURCES =
      "SELECT source_code, is_primary, other_text FROM app.profile_income_source"
          + " WHERE profile_id = ?::uuid";

  private static final String UPDATE_STAGE3 =
      """
      UPDATE app.profile_customer_data
         SET sex_declared = ?::text, ethnicity = ?::text,
             country_of_residence_code = ?::text, country_of_residence_version = ?::int,
             marital_status = ?::text, spouse_name = ?::text,
             has_children = ?::boolean, children_count = ?::int,
             education_level = ?::int,
             birth_country_code = ?::text, birth_country_version = ?::int,
             birth_state_code = ?::text, birth_state_text = ?::text, birth_city_text = ?::text,
             admin_div_version = COALESCE(?::int, admin_div_version), updated_at = ?::timestamptz
       WHERE profile_id = ?::uuid
      """;

  private static final String UPDATE_STAGE4_OCCUPATION =
      """
      UPDATE app.profile_customer_data
         SET occupation_code = ?::text, occupation_version = ?::int,
             income_source_version = ?::int,
             monthly_expenses_sdg = ?::bigint,
             updated_at = ?::timestamptz
       WHERE profile_id = ?::uuid
      """;

  private static final String DELETE_INCOME_SOURCES =
      "DELETE FROM app.profile_income_source WHERE profile_id = ?::uuid";

  private static final String INSERT_INCOME_SOURCE =
      """
      INSERT INTO app.profile_income_source (profile_id, source_code, is_primary, other_text)
      VALUES (?::uuid, ?::text, ?::boolean, ?::text)
      """;

  private static final String UPDATE_STAGE5 =
      """
      UPDATE app.profile_customer_data
         SET home_country_code = ?::text, home_country_version = ?::int,
             home_state_code = ?::text, home_state_text = ?::text,
             home_locality_code = ?::text, home_locality_text = ?::text,
             home_city = ?::text, home_area = ?::text, home_street = ?::text,
             home_block = ?::text, home_house_no = ?::text,
             admin_div_version = COALESCE(?::int, admin_div_version), updated_at = ?::timestamptz
       WHERE profile_id = ?::uuid
      """;

  /**
   * {@code salary_certificate_claimed_at} is a full replace like every other column here, with one
   * qualification: only a client that actually SENT the field replaces it (BL-122).
   *
   * <p>{@code true} records the claim and keeps the FIRST timestamp, so re-submitting Stage 6 does
   * not keep moving the moment the customer told us. {@code false} clears it — an explicit "nothing
   * is attached" is a correction, and treating it as one is what lets an AD-008 device-less
   * re-entry fix the record: the new customer's Stage 6 corrects every other column on this row,
   * and this column must not be the single one their submission cannot reach, or a previous
   * person's claim would be reported about them for ever. {@code NULL} — a client built before this
   * field existed — leaves whatever is there alone, because "did not say" is not "said no".
   *
   * <p>An earlier version of this statement was monotonic (a plain {@code COALESCE}, never
   * clearing) and justified that by an out-of-order {@code flushPending} replay erasing a later
   * claim. That justification was wrong and {@code @agent-reviewer} caught it: the replay re-runs
   * {@code submitStage6}, which re-reads the draft, so a queued request carries no stale claim to
   * replay. The real case needing care is the older client, and that is the {@code NULL} arm.
   */
  private static final String UPDATE_STAGE6 =
      """
      UPDATE app.profile_customer_data
         SET employer_name = ?::text, work_country_code = ?::text, work_country_version = ?::int,
             work_state_code = ?::text, work_state_text = ?::text,
             work_locality_code = ?::text, work_locality_text = ?::text,
             work_city = ?::text, work_area = ?::text, work_street = ?::text, work_block = ?::text,
             admin_div_version = COALESCE(?::int, admin_div_version),
             salary_certificate_claimed_at = CASE ?::boolean
               WHEN true THEN COALESCE(salary_certificate_claimed_at, ?::timestamptz)
               WHEN false THEN NULL
               ELSE salary_certificate_claimed_at
             END,
             updated_at = ?::timestamptz
       WHERE profile_id = ?::uuid
      """;

  private static final String UPDATE_STAGE7 =
      """
      UPDATE app.profile_customer_data
         SET identity_type = ?::text, updated_at = ?::timestamptz
       WHERE profile_id = ?::uuid
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcDataEntryRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<ProfileLock> lockAndGetStatus(UUID profileId) {
    List<ProfileLock> rows =
        jdbcTemplate.query(
            LOCK_AND_GET_STATUS,
            (rs, rowNum) -> new ProfileLock(rs.getString("status"), rs.getBoolean("is_terminal")),
            profileId.toString());
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  public CustomerDataSnapshot currentCustomerData(UUID profileId) {
    List<CustomerDataSnapshot> rows =
        jdbcTemplate.query(
            CURRENT_CUSTOMER_DATA,
            (rs, rowNum) ->
                new CustomerDataSnapshot(
                    rs.getString("sex_declared"),
                    rs.getString("ethnicity"),
                    rs.getString("country_of_residence_code"),
                    rs.getString("marital_status"),
                    rs.getString("spouse_name"),
                    (Boolean) rs.getObject("has_children"),
                    (Integer) rs.getObject("children_count"),
                    (Integer) rs.getObject("education_level"),
                    rs.getString("birth_country_code"),
                    rs.getString("birth_state_code"),
                    rs.getString("birth_state_text"),
                    rs.getString("birth_city_text"),
                    rs.getString("occupation_code"),
                    (Long) rs.getObject("monthly_expenses_sdg"),
                    rs.getString("home_country_code"),
                    rs.getString("home_state_code"),
                    rs.getString("home_state_text"),
                    rs.getString("home_locality_code"),
                    rs.getString("home_locality_text"),
                    rs.getString("home_city"),
                    rs.getString("home_area"),
                    rs.getString("home_street"),
                    rs.getString("home_block"),
                    rs.getString("home_house_no"),
                    rs.getString("employer_name"),
                    rs.getString("work_country_code"),
                    rs.getString("work_state_code"),
                    rs.getString("work_state_text"),
                    rs.getString("work_locality_code"),
                    rs.getString("work_locality_text"),
                    rs.getString("work_city"),
                    rs.getString("work_area"),
                    rs.getString("work_street"),
                    rs.getString("work_block"),
                    rs.getString("identity_type")),
            profileId.toString());
    if (rows.isEmpty()) {
      throw new IllegalStateException("no app.profile_customer_data row for profile " + profileId);
    }
    return rows.get(0);
  }

  @Override
  public List<IncomeSourceRow> currentIncomeSources(UUID profileId) {
    return jdbcTemplate.query(
        CURRENT_INCOME_SOURCES,
        (rs, rowNum) ->
            new IncomeSourceRow(
                rs.getString("source_code"),
                rs.getBoolean("is_primary"),
                rs.getString("other_text")),
        profileId.toString());
  }

  @Override
  public void updateStage3(UUID profileId, Stage3Fields fields, Instant now) {
    jdbcTemplate.update(
        UPDATE_STAGE3,
        fields.sexDeclared(),
        fields.ethnicity(),
        fields.countryOfResidenceCode(),
        fields.countryOfResidenceVersion(),
        fields.maritalStatus(),
        fields.spouseName(),
        fields.hasChildren(),
        fields.childrenCount(),
        fields.educationLevel(),
        fields.birthCountryCode(),
        fields.birthCountryVersion(),
        fields.birthStateCode(),
        fields.birthStateText(),
        fields.birthCityText(),
        fields.adminDivVersion(),
        now.toString(),
        profileId.toString());
  }

  @Override
  public void updateStage4Occupation(
      UUID profileId,
      String occupationCode,
      int occupationVersion,
      int incomeSourceVersion,
      long monthlyExpensesSdg,
      Instant now) {
    jdbcTemplate.update(
        UPDATE_STAGE4_OCCUPATION,
        occupationCode,
        occupationVersion,
        incomeSourceVersion,
        monthlyExpensesSdg,
        now.toString(),
        profileId.toString());
  }

  @Override
  public void replaceIncomeSources(UUID profileId, List<IncomeSourceRow> sources) {
    jdbcTemplate.update(DELETE_INCOME_SOURCES, profileId.toString());
    for (IncomeSourceRow source : sources) {
      jdbcTemplate.update(
          INSERT_INCOME_SOURCE,
          profileId.toString(),
          source.sourceCode(),
          source.primary(),
          source.otherText());
    }
  }

  @Override
  public void updateStage5(UUID profileId, Stage5Fields fields, Instant now) {
    jdbcTemplate.update(
        UPDATE_STAGE5,
        fields.homeCountryCode(),
        fields.homeCountryVersion(),
        fields.homeStateCode(),
        fields.homeStateText(),
        fields.homeLocalityCode(),
        fields.homeLocalityText(),
        fields.homeCity(),
        fields.homeArea(),
        fields.homeStreet(),
        fields.homeBlock(),
        fields.homeHouseNo(),
        fields.adminDivVersion(),
        now.toString(),
        profileId.toString());
  }

  @Override
  public void updateStage6(UUID profileId, Stage6Fields fields, Instant now) {
    jdbcTemplate.update(
        UPDATE_STAGE6,
        fields.employerName(),
        fields.workCountryCode(),
        fields.workCountryVersion(),
        fields.workStateCode(),
        fields.workStateText(),
        fields.workLocalityCode(),
        fields.workLocalityText(),
        fields.workCity(),
        fields.workArea(),
        fields.workStreet(),
        fields.workBlock(),
        fields.adminDivVersion(),
        // Bound twice: once to pick the CASE arm (true records, false clears, null leaves alone)
        // and once as the timestamp the `true` arm stores. BL-122.
        fields.salaryCertificateClaimed(),
        now.toString(),
        now.toString(),
        profileId.toString());
  }

  @Override
  public void updateStage7(UUID profileId, String identityType, Instant now) {
    jdbcTemplate.update(UPDATE_STAGE7, identityType, now.toString(), profileId.toString());
  }
}
