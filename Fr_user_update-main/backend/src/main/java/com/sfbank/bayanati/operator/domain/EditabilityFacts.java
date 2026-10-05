package com.sfbank.bayanati.operator.domain;

import java.util.List;

/**
 * The six facts about a profile that decide which fields are editable on it — the whole input to
 * {@link EditableFieldPolicy}.
 *
 * <p><strong>Why this exists rather than passing {@link CustomerDataView} everywhere.</strong> The
 * two callers reach editability from opposite directions. The READ path already holds a full {@code
 * ProfileDetail}, so it has {@link CustomerDataView} and {@link ScanResultView} in hand. The WRITE
 * path must not: loading a full detail there would run {@code OperatorProfileViewService}, which
 * writes a {@code profile_viewed} audit event — so every field edit would also record a view that
 * never happened. It instead reads these six values with one narrow query.
 *
 * <p>Two shapes feeding one rule is how derivations drift, so {@link #from} is the only adapter and
 * the policy has exactly one implementation to test.
 *
 * @param customerDataPresent whether the profile has an {@code app.profile_customer_data} row at
 *     all — false on a profile that never reached stage 3, which makes every field uneditable
 * @param maritalStatus decides fields 13/14 (V0006's CHECK set)
 * @param birthCountryCode decides field 24's free-text fallback
 * @param homeCountryCode decides fields 36 and 37
 * @param workCountryCode decides fields 29 and 30
 * @param hasOtherIncomeSource whether an {@code OTHER} row exists to carry field 20's «أخرى» text
 * @param scanBirthCity Uqudo's {@code placeOfBirth}; when present it supersedes the customer's own
 *     answer for field 23, which is what makes that field uneditable
 */
public record EditabilityFacts(
    boolean customerDataPresent,
    String maritalStatus,
    String birthCountryCode,
    String homeCountryCode,
    String workCountryCode,
    boolean hasOtherIncomeSource,
    String scanBirthCity) {

  private static final String OTHER_INCOME_CODE = "OTHER";

  /** The read path's adapter: the same six facts, taken off a loaded {@code ProfileDetail}. */
  public static EditabilityFacts from(CustomerDataView customerData, ScanResultView scanResult) {
    if (customerData == null) {
      return new EditabilityFacts(false, null, null, null, null, false, null);
    }
    return new EditabilityFacts(
        true,
        customerData.maritalStatus(),
        customerData.birthCountryCode(),
        customerData.homeCountryCode(),
        customerData.workCountryCode(),
        hasOther(customerData.incomeSources()),
        scanResult == null ? null : scanResult.birthCity());
  }

  private static boolean hasOther(List<IncomeSourceView> incomeSources) {
    return incomeSources != null
        && incomeSources.stream().anyMatch(s -> OTHER_INCOME_CODE.equals(s.sourceCode()));
  }
}
