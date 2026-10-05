package com.sfbank.bayanati.dataentry.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The one way application code writes {@code app.profile_customer_data} and {@code
 * app.profile_income_source} for stages 3-6 (customer.md). Mirrors {@code
 * profile.domain.ProfileRepository}'s shape: everything a caller needs done, nothing about how.
 *
 * <p>Deliberately narrow to what stages 3-6 write — {@code app.profile} itself (status, activity
 * timestamp) stays owned by {@code profile.domain.ProfileRepository}, reused here rather than
 * duplicated.
 */
public interface DataEntryRepository {

  /**
   * Takes a {@code SELECT ... FOR UPDATE} row lock on {@code app.profile} and returns its current
   * status, or empty if {@code profileId} does not exist. Unlike {@code
   * ProfileRepository.lockAndCheckStillEligibleForReentry} (which assumes the profile exists,
   * because its caller already found it via {@code findExisting}), a data-entry submission's {@code
   * profileId} comes straight from the client with nothing checked yet — a missing profile is an
   * ordinary 404, not a "should never happen".
   */
  Optional<ProfileLock> lockAndGetStatus(UUID profileId);

  /** Every stage 3-6 column as it stands before this submission overwrites it. */
  CustomerDataSnapshot currentCustomerData(UUID profileId);

  /** Every {@code app.profile_income_source} row currently on record for this profile. */
  List<IncomeSourceRow> currentIncomeSources(UUID profileId);

  /** Full-replace write of every column {@link Stage3Fields} owns. */
  void updateStage3(UUID profileId, Stage3Fields fields, Instant now);

  /**
   * Full-replace write of {@code occupation_code}/{@code occupation_version}/{@code
   * monthly_expenses_sdg} — the two Stage 4 fields not part of the income-source set (see {@link
   * #replaceIncomeSources}) — plus {@code income_source_version} (V0051, S4-04): {@code
   * profile_income_source} is a multi-row, full-replace table with no per-row version column, so
   * the version the whole set was validated against is recorded here instead, mirroring {@code
   * admin_div_version}'s existing precedent.
   */
  void updateStage4Occupation(
      UUID profileId,
      String occupationCode,
      int occupationVersion,
      int incomeSourceVersion,
      long monthlyExpensesSdg,
      Instant now);

  /**
   * Replaces every {@code app.profile_income_source} row for this profile with {@code sources} —
   * {@code DELETE} then re-insert in one transaction, exactly the replace {@code fru_app}'s {@code
   * DELETE} grant on this table (V0010) was provisioned for. Not a diff: Stage 4 always submits its
   * complete current selection.
   */
  void replaceIncomeSources(UUID profileId, List<IncomeSourceRow> sources);

  /** Full-replace write of every column {@link Stage5Fields} owns. */
  void updateStage5(UUID profileId, Stage5Fields fields, Instant now);

  /** Full-replace write of every column {@link Stage6Fields} owns. */
  void updateStage6(UUID profileId, Stage6Fields fields, Instant now);

  /**
   * Writes {@code app.profile_customer_data.identity_type} — Stage 7's single field (customer.md:
   * "The last stage before Uqudo enters ... Identity document type — selection: passport or
   * national ID").
   */
  void updateStage7(UUID profileId, String identityType, Instant now);
}
