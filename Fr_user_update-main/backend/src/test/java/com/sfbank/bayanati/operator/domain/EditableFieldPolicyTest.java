package com.sfbank.bayanati.operator.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * BL-135 / AD-015 / AD-021. The rule under test is <em>derived per profile, never a hardcoded
 * list</em> — so most of these cases are two profiles that differ in exactly one stored value and
 * must produce different sets. A test suite that only asserted one profile's set would pass against
 * a constant.
 *
 * <p>Plain JUnit: no Spring context, no database, no network, no clock (CLAUDE.md's rule for a
 * {@code domain} package).
 */
class EditableFieldPolicyTest {

  private static final String SUDAN = "SD";

  /** The eleven fields that are free text for every customer, whatever they answered. */
  private static final Set<EditableField> ALWAYS =
      Set.of(
          EditableField.ETHNICITY,
          EditableField.EMPLOYER_NAME,
          EditableField.HOME_AREA,
          EditableField.HOME_CITY,
          EditableField.HOME_STREET,
          EditableField.HOME_BLOCK,
          EditableField.HOME_HOUSE_NO,
          EditableField.WORK_AREA,
          EditableField.WORK_CITY,
          EditableField.WORK_STREET,
          EditableField.WORK_BLOCK);

  // ---------------------------------------------------------------- the brief's canonical profile

  /**
   * {@code docs/backoffice-redesign.md} §3 says "14 fields are editable". That count holds for
   * exactly one shape of profile, and this is it — Sudan-resident, Sudan-born, Sudan-employed,
   * married, with an «أخرى» income source and no Uqudo birth city. Every other case below departs
   * from it in one value, which is the point: the number is an outcome, not a specification.
   */
  @Test
  void theBriefsFourteenFieldsAreTheSudanResidentMarriedProfile() {
    Set<EditableField> editable = EditableFieldPolicy.editableFor(sudanMarriedWithOther(), SUDAN);

    assertEquals(14, editable.size(), editable.toString());
    assertTrue(editable.containsAll(ALWAYS), editable.toString());
    assertTrue(editable.contains(EditableField.SPOUSE_NAME));
    assertTrue(editable.contains(EditableField.BIRTH_CITY));
    assertTrue(editable.contains(EditableField.INCOME_OTHER_TEXT));
  }

  /** The bound R-054 relies on: no list-picked value and no identity field is ever in the set. */
  @Test
  void noSudanProfileExposesACascadingListsFreeTextFallback() {
    Set<EditableField> editable = EditableFieldPolicy.editableFor(sudanMarriedWithOther(), SUDAN);

    assertFalse(editable.contains(EditableField.HOME_STATE_TEXT), editable.toString());
    assertFalse(editable.contains(EditableField.HOME_LOCALITY_TEXT), editable.toString());
    assertFalse(editable.contains(EditableField.WORK_STATE_TEXT), editable.toString());
    assertFalse(editable.contains(EditableField.WORK_LOCALITY_TEXT), editable.toString());
    assertFalse(editable.contains(EditableField.BIRTH_STATE_TEXT), editable.toString());
  }

  // ------------------------------------------------------------------ derived: the three cascades

  /**
   * customer.md Stage 5: "Selecting a country other than Sudan leaves state and locality with no
   * dataset behind them. Both fall back to free text." R-042's note records that customers abroad
   * are not rare, which is why a static list would be wrong rather than merely imprecise.
   */
  @Test
  void aHomeAddressOutsideSudanMakesStateAndLocalityEditable() {
    EditabilityFacts abroad = facts("married", SUDAN, "EG", SUDAN, true, null);

    Set<EditableField> editable = EditableFieldPolicy.editableFor(abroad, SUDAN);

    assertTrue(editable.contains(EditableField.HOME_STATE_TEXT), editable.toString());
    assertTrue(editable.contains(EditableField.HOME_LOCALITY_TEXT), editable.toString());
    // The OTHER two hierarchies are untouched -- the derivation is per hierarchy, not per profile.
    assertFalse(editable.contains(EditableField.WORK_STATE_TEXT), editable.toString());
    assertFalse(editable.contains(EditableField.BIRTH_STATE_TEXT), editable.toString());
    assertEquals(16, editable.size(), editable.toString());
  }

  /**
   * Fields 29 and 30. NOT named by the brief's §3 table, which states the derived rule for 36/37
   * alone — extended by product-owner default because {@code
   * DataEntryService.resolveAddressCascade} is literally the same method for both hierarchies.
   */
  @Test
  void aWorkAddressOutsideSudanMakesItsOwnStateAndLocalityEditable() {
    EditabilityFacts abroad = facts("married", SUDAN, SUDAN, "AE", true, null);

    Set<EditableField> editable = EditableFieldPolicy.editableFor(abroad, SUDAN);

    assertTrue(editable.contains(EditableField.WORK_STATE_TEXT), editable.toString());
    assertTrue(editable.contains(EditableField.WORK_LOCALITY_TEXT), editable.toString());
    assertFalse(editable.contains(EditableField.HOME_STATE_TEXT), editable.toString());
  }

  /**
   * Field 24. customer.md Stage 3: "When birth country is Sudan, selects from the Sudan state list;
   * otherwise free text. Same non-Sudan fallback pattern as the address hierarchy."
   */
  @Test
  void aBirthCountryOutsideSudanMakesBirthStateEditable() {
    EditabilityFacts bornAbroad = facts("married", "EG", SUDAN, SUDAN, true, null);

    Set<EditableField> editable = EditableFieldPolicy.editableFor(bornAbroad, SUDAN);

    assertTrue(editable.contains(EditableField.BIRTH_STATE_TEXT), editable.toString());
    assertFalse(editable.contains(EditableField.HOME_STATE_TEXT), editable.toString());
  }

  /**
   * The three hierarchies compose. A customer born, living and working abroad exposes all five
   * fallbacks at once — 19 fields, which is every constant the enum has.
   */
  @Test
  void allThreeHierarchiesAbroadExposesEveryFallbackAtOnce() {
    EditabilityFacts whollyAbroad = facts("married", "EG", "EG", "AE", true, null);

    Set<EditableField> editable = EditableFieldPolicy.editableFor(whollyAbroad, SUDAN);

    assertEquals(EditableField.values().length, editable.size(), editable.toString());
    assertEquals(19, editable.size(), editable.toString());
  }

  /**
   * An unanswered country is not "abroad". Treating null as free text would open the fallback
   * fields on a profile that never reached the stage.
   */
  @Test
  void anUnansweredCountryExposesNoFallback() {
    EditabilityFacts noCountries = facts("married", null, null, null, true, null);

    Set<EditableField> editable = EditableFieldPolicy.editableFor(noCountries, SUDAN);

    assertFalse(editable.contains(EditableField.HOME_STATE_TEXT), editable.toString());
    assertFalse(editable.contains(EditableField.WORK_STATE_TEXT), editable.toString());
    assertFalse(editable.contains(EditableField.BIRTH_STATE_TEXT), editable.toString());
  }

  // -------------------------------------------------------------------- derived: fields 13 and 14

  /**
   * customer.md Stage 3's conditional-field table, and {@code
   * DataEntryService.validateMaritalStatus}, which REFUSES {@code spouseName} on any status but
   * married. Offering the edit elsewhere would let an operator create a value the app cannot.
   */
  @Test
  void spouseNameIsEditableOnlyWhenMarried() {
    for (String status : List.of("single", "divorced", "widowed")) {
      Set<EditableField> editable =
          EditableFieldPolicy.editableFor(facts(status, SUDAN, SUDAN, SUDAN, true, null), SUDAN);
      assertFalse(editable.contains(EditableField.SPOUSE_NAME), status + " -> " + editable);
    }
    assertTrue(
        EditableFieldPolicy.editableFor(facts("married", SUDAN, SUDAN, SUDAN, true, null), SUDAN)
            .contains(EditableField.SPOUSE_NAME));
  }

  // ------------------------------------------------------------------------- derived: field 23

  /**
   * Product-owner ruling 2026-09-16, closing BL-135's third open edge. Where the scan returned a
   * place of birth, THAT is what field 23 means (field-provenance.md: "S2 {@code placeOfBirth},
   * else free text"), so editing {@code birth_city_text} would write a column nothing displays.
   */
  @Test
  void birthCityIsEditableOnlyWhenTheScanSuppliedNoPlaceOfBirth() {
    Set<EditableField> scanned =
        EditableFieldPolicy.editableFor(
            facts("married", SUDAN, SUDAN, SUDAN, true, "أم درمان"), SUDAN);
    assertFalse(scanned.contains(EditableField.BIRTH_CITY), scanned.toString());

    Set<EditableField> unscanned =
        EditableFieldPolicy.editableFor(facts("married", SUDAN, SUDAN, SUDAN, true, null), SUDAN);
    assertTrue(unscanned.contains(EditableField.BIRTH_CITY), unscanned.toString());
  }

  /**
   * A blank is an absent value, not a supplied one — Uqudo returning "" must not lock the field.
   */
  @Test
  void aBlankScannedBirthCityCountsAsAbsent() {
    Set<EditableField> editable =
        EditableFieldPolicy.editableFor(facts("married", SUDAN, SUDAN, SUDAN, true, "   "), SUDAN);

    assertTrue(editable.contains(EditableField.BIRTH_CITY), editable.toString());
  }

  // ------------------------------------------------------------------------- derived: field 20

  /**
   * AD-021 forbids editing a list-picked value, so the «تعديل» chip the artboard draws on field 20
   * can only mean its «أخرى» free text — which exists only when {@code OTHER} is selected.
   */
  @Test
  void theOtherIncomeTextIsEditableOnlyWhenAnOtherRowExists() {
    Set<EditableField> without =
        EditableFieldPolicy.editableFor(facts("married", SUDAN, SUDAN, SUDAN, false, null), SUDAN);
    assertFalse(without.contains(EditableField.INCOME_OTHER_TEXT), without.toString());

    Set<EditableField> with =
        EditableFieldPolicy.editableFor(facts("married", SUDAN, SUDAN, SUDAN, true, null), SUDAN);
    assertTrue(with.contains(EditableField.INCOME_OTHER_TEXT), with.toString());
  }

  // ------------------------------------------------------------------------------- edge cases

  /** A profile that never reached stage 3 has no editable field at all, and is not an error. */
  @Test
  void aProfileWithNoCustomerDataRowHasNothingEditable() {
    EditabilityFacts none = new EditabilityFacts(false, null, null, null, null, false, null);

    assertTrue(EditableFieldPolicy.editableFor(none, SUDAN).isEmpty());
  }

  @Test
  void nullFactsYieldAnEmptySetRatherThanThrowing() {
    assertTrue(EditableFieldPolicy.editableFor((EditabilityFacts) null, SUDAN).isEmpty());
  }

  // ----------------------------------------------------------------------------- the adapter

  /**
   * {@link EditabilityFacts#from} is the ONE adapter between the read path's loaded {@code
   * ProfileDetail} and this rule. If it drifted, the chip the browser draws and the field the write
   * path accepts would disagree.
   */
  @Test
  void theAdapterReadsTheSameSixFactsOffALoadedProfile() {
    CustomerDataView customerData = customerDataView("married", "EG", SUDAN, SUDAN, true);
    ScanResultView scanResult = scanResultWithBirthCity("Cairo");

    EditabilityFacts adapted = EditabilityFacts.from(customerData, scanResult);

    assertTrue(adapted.customerDataPresent());
    assertEquals("married", adapted.maritalStatus());
    assertEquals("EG", adapted.birthCountryCode());
    assertEquals(SUDAN, adapted.homeCountryCode());
    assertEquals(SUDAN, adapted.workCountryCode());
    assertTrue(adapted.hasOtherIncomeSource());
    assertEquals("Cairo", adapted.scanBirthCity());

    // And the whole way through: same answer as the facts-shaped call.
    assertEquals(
        EditableFieldPolicy.editableFor(adapted, SUDAN),
        EditableFieldPolicy.editableFor(customerData, scanResult, SUDAN));
  }

  @Test
  void theAdapterTreatsAMissingCustomerDataRowAsNotPresent() {
    EditabilityFacts adapted = EditabilityFacts.from(null, null);

    assertFalse(adapted.customerDataPresent());
    assertTrue(EditableFieldPolicy.editableFor(adapted, SUDAN).isEmpty());
  }

  // ------------------------------------------------------------------------------- fixtures

  private static EditabilityFacts sudanMarriedWithOther() {
    return facts("married", SUDAN, SUDAN, SUDAN, true, null);
  }

  private static EditabilityFacts facts(
      String maritalStatus,
      String birthCountry,
      String homeCountry,
      String workCountry,
      boolean hasOtherIncome,
      String scanBirthCity) {
    return new EditabilityFacts(
        true, maritalStatus, birthCountry, homeCountry, workCountry, hasOtherIncome, scanBirthCity);
  }

  private static CustomerDataView customerDataView(
      String maritalStatus,
      String birthCountry,
      String homeCountry,
      String workCountry,
      boolean hasOtherIncome) {
    List<IncomeSourceView> income =
        hasOtherIncome
            ? List.of(new IncomeSourceView("OTHER", true, "منحة"))
            : List.of(new IncomeSourceView("SALARY", true, null));
    return new CustomerDataView(
        "+249900000001",
        "a@example.invalid",
        "f",
        maritalStatus,
        "وليد",
        Boolean.TRUE,
        2,
        6,
        "12",
        1,
        45000L,
        "national_id",
        "شايقية",
        SUDAN,
        birthCountry,
        "11",
        null,
        "أم درمان",
        homeCountry,
        "11",
        "111",
        null,
        null,
        "أم درمان",
        "الثورة",
        "الأربعين",
        "12",
        "47",
        "الجهاز المركزي",
        workCountry,
        "11",
        "111",
        null,
        null,
        "الخرطوم",
        "المقرن",
        "النيل",
        "3",
        income);
  }

  private static ScanResultView scanResultWithBirthCity(String birthCity) {
    return new ScanResultView(
        "PASSPORT",
        null,
        "000-0000-0011",
        "B0000011",
        Boolean.TRUE,
        "SDN",
        "F",
        LocalDate.parse("1994-11-09"),
        LocalDate.parse("2019-05-12"),
        LocalDate.parse("2029-05-12"),
        "أم درمان",
        "SDN",
        "آلاء",
        "ALAA",
        null,
        birthCity,
        Instant.parse("2026-09-16T00:00:00Z"));
  }
}
