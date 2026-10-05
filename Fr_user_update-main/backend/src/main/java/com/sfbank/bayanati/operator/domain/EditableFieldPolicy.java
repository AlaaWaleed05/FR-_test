package com.sfbank.bayanati.operator.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Which of {@link EditableField}'s constants are editable <em>on this profile</em>.
 *
 * <p><strong>Editability is DERIVED PER PROFILE, never a hardcoded field list</strong> — AD-021 and
 * {@code docs/backoffice-redesign.md} §3. A static list is wrong for every customer whose address,
 * workplace or birthplace is outside Sudan, because customer.md Stage 5 makes state and locality
 * fall back to FREE TEXT there while they are list-picked on a Sudan profile. R-042's own note
 * records that customers abroad are not a rare case. The brief's "14 fields" is therefore a count
 * for one shape of profile — Sudan-resident, Sudan-born, Sudan-employed, married, with an «أخرى»
 * income source and no Uqudo birth city — and not a specification.
 *
 * <p><strong>This is the authority, and the server applies it twice.</strong> {@code
 * FieldEditService} calls it again on the write path rather than trusting the {@code
 * editableFields} list it sent to the browser: a missing «تعديل» chip is presentation, and
 * presentation is never the control.
 *
 * <p>Pure policy: no Spring context, no database, no network, no clock. {@code sudanCode} is passed
 * in rather than resolved here, so this class takes no dependency on {@code ReferenceCatalog} — the
 * caller reads {@code currentRootItemCode("admin_division")}, the declared cascade root V0049's FK
 * and trigger guard stable across versions (R-045).
 */
public final class EditableFieldPolicy {

  private static final String MARRIED = "married";

  private EditableFieldPolicy() {}

  /** Convenience for the read path, which already holds a loaded {@code ProfileDetail}. */
  public static Set<EditableField> editableFor(
      CustomerDataView customerData, ScanResultView scanResult, String sudanCode) {
    return editableFor(EditabilityFacts.from(customerData, scanResult), sudanCode);
  }

  /**
   * @param facts the six values that decide the set — see {@link EditabilityFacts}
   * @param sudanCode {@code admin_division}'s declared root item code: the one country whose states
   *     and localities are list-picked. Every other country falls back to free text.
   */
  public static Set<EditableField> editableFor(EditabilityFacts facts, String sudanCode) {
    EnumSet<EditableField> editable = EnumSet.noneOf(EditableField.class);
    if (facts == null || !facts.customerDataPresent()) {
      return editable;
    }

    // Unconditionally free text: no source supplies them and no list constrains them.
    editable.add(EditableField.ETHNICITY);
    editable.add(EditableField.EMPLOYER_NAME);
    editable.add(EditableField.HOME_AREA);
    editable.add(EditableField.HOME_CITY);
    editable.add(EditableField.HOME_STREET);
    editable.add(EditableField.HOME_BLOCK);
    editable.add(EditableField.HOME_HOUSE_NO);
    editable.add(EditableField.WORK_AREA);
    editable.add(EditableField.WORK_CITY);
    editable.add(EditableField.WORK_STREET);
    editable.add(EditableField.WORK_BLOCK);

    // Fields 13/14. customer.md Stage 3's conditional-field table asks for a spouse name only when
    // married, and DataEntryService.validateMaritalStatus REFUSES spouseName on any other status --
    // so offering the edit elsewhere would let an operator create a value the customer journey
    // cannot produce, which is what AD-015's "no value can enter that the app could not produce"
    // bound forbids.
    if (MARRIED.equals(facts.maritalStatus())) {
      editable.add(EditableField.SPOUSE_NAME);
    }

    // Field 23. Editable only where Uqudo supplied nothing -- product-owner ruling 2026-09-16,
    // closing BL-135's third open edge. Where the scan DID return a place of birth, that value is
    // what the field means (field-provenance.md: "S2 placeOfBirth, else free text"), and typing
    // into birth_city_text would edit a column the screen is not showing.
    if (isBlank(facts.scanBirthCity())) {
      editable.add(EditableField.BIRTH_CITY);
    }

    // Field 20's «أخرى» text, and only when an OTHER row exists to carry it. app.profile_income_
    // source's other_needs_text CHECK ties the two together, so offering the edit without the row
    // would offer an edit that cannot be stored.
    if (facts.hasOtherIncomeSource()) {
      editable.add(EditableField.INCOME_OTHER_TEXT);
    }

    // The three cascading hierarchies. Each is list-picked on a Sudan profile and free text
    // elsewhere -- customer.md Stage 3 ("Same non-Sudan fallback pattern as the address hierarchy")
    // and Stage 5 ("Both fall back to free text"). Derived from the COUNTRY rather than from which
    // column happens to be populated: the country is the decision the journey actually took, and
    // DataEntryService.resolveAddressCascade/resolveBirthState branch on exactly this comparison.
    if (isFreeTextCountry(facts.birthCountryCode(), sudanCode)) {
      editable.add(EditableField.BIRTH_STATE_TEXT);
    }
    if (isFreeTextCountry(facts.homeCountryCode(), sudanCode)) {
      editable.add(EditableField.HOME_STATE_TEXT);
      editable.add(EditableField.HOME_LOCALITY_TEXT);
    }
    if (isFreeTextCountry(facts.workCountryCode(), sudanCode)) {
      editable.add(EditableField.WORK_STATE_TEXT);
      editable.add(EditableField.WORK_LOCALITY_TEXT);
    }

    return editable;
  }

  /**
   * A country whose administrative divisions are free text.
   *
   * <p>A {@code null} country is NOT free text. An unanswered country means the journey never
   * reached the stage, so there is no free-text column to edit — and treating "unknown" as "abroad"
   * would open the fallback fields on an incomplete profile.
   */
  private static boolean isFreeTextCountry(String countryCode, String sudanCode) {
    return countryCode != null && !countryCode.equals(sudanCode);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
