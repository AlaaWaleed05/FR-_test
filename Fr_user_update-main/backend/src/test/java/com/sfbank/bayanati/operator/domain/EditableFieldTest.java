package com.sfbank.bayanati.operator.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The closed allow-list itself. AD-015 as narrowed twice (2026-09-14, then AD-021) makes membership
 * here an architecture decision rather than a code change, so these assertions exist to make an
 * accidental addition fail loudly.
 */
class EditableFieldTest {

  /**
   * The Civil Registry's six (5, 6, 7, 8, 9, 21), Uqudo's seven (4, 23's scanned case, 44, 45, 46,
   * 47, 48), the system-generated form date (1), and every list-picked customer field. R-054's
   * bound is that no edit can invent a scan, a face match or a registry record — which holds only
   * while none of these numbers appears.
   */
  @Test
  void noIdentityFieldAndNoListPickedFieldIsEditable() {
    Set<Integer> editableNumbers =
        Arrays.stream(EditableField.values())
            .map(EditableField::fieldNumber)
            .collect(Collectors.toSet());

    // Civil Registry (S1) + the system date.
    for (int forbidden : new int[] {1, 5, 6, 7, 8, 9, 21}) {
      assertFalse(
          editableNumbers.contains(forbidden),
          "field " + forbidden + " is Civil-Registry or system sourced and must never be editable");
    }
    // Uqudo (S2).
    for (int forbidden : new int[] {4, 44, 45, 46, 47, 48}) {
      assertFalse(
          editableNumbers.contains(forbidden),
          "field " + forbidden + " is Uqudo-sourced and must never be editable");
    }
    // Customer-entered but LIST-PICKED, which AD-021 forbids editing: account number and branch
    // (3, 2), contact channels (25, 26 -- AD-022 ruling 4), country of residence (11), marital
    // status (12), the has-children gate (15), children count (16), education level (17),
    // occupation (18), monthly expenses (19), birth country (22), the three address countries
    // (28, 35) and the identity type (43).
    for (int forbidden : new int[] {2, 3, 11, 12, 15, 16, 17, 18, 19, 22, 25, 26, 28, 35, 43}) {
      assertFalse(
          editableNumbers.contains(forbidden),
          "field " + forbidden + " is list-picked or read-only and must never be editable");
    }
  }

  /**
   * BL-152, answered by the product owner 2026-09-16. Called out separately from the sweep above
   * because these two are the ones that were genuinely open: typed by the customer, but digits
   * rather than free text.
   */
  @Test
  void theDigitsOnlyFieldsAreNotEditable() {
    Set<Integer> editableNumbers =
        Arrays.stream(EditableField.values())
            .map(EditableField::fieldNumber)
            .collect(Collectors.toSet());

    assertFalse(editableNumbers.contains(16), "field 16, number of children (BL-152)");
    assertFalse(editableNumbers.contains(19), "field 19, monthly expenses (BL-152)");
  }

  /** Nineteen: the brief's fourteen plus the five free-text fallbacks of the three cascades. */
  @Test
  void theAllowListHasNineteenConstants() {
    assertEquals(19, EditableField.values().length);
  }

  @Test
  void parseResolvesAKnownKeyAndRefusesEverythingElse() {
    assertEquals(EditableField.HOME_AREA, EditableField.parse("HOME_AREA").orElseThrow());

    assertTrue(EditableField.parse("PHONE_NUMBER").isEmpty(), "not an editable field");
    assertTrue(EditableField.parse("").isEmpty());
    assertTrue(EditableField.parse(null).isEmpty());
  }

  /**
   * Case-sensitive on purpose. The wire contract is the enum name exactly; accepting {@code
   * home_area} too would let a second spelling into {@code app.profile_field_edit.field_key} and
   * into audit payloads that can never be amended.
   */
  @Test
  void parseIsCaseSensitive() {
    assertTrue(EditableField.parse("home_area").isEmpty());
    assertTrue(EditableField.parse("Home_Area").isEmpty());
  }

  /**
   * {@code column()} is interpolated into SQL by {@code JdbcFieldEditRepository}. That is safe only
   * while every value is a compile-time constant with no SQL syntax in it — this is the assertion
   * that keeps it true.
   */
  @Test
  void everyColumnIsAPlainIdentifier() {
    for (EditableField field : EditableField.values()) {
      assertTrue(
          field.column().matches("[a-z][a-z0-9_]*"),
          field.name() + " has a column that is not a bare identifier: " + field.column());
    }
  }

  /** Two constants writing the same column would make the audit trail ambiguous about which. */
  @Test
  void noTwoFieldsShareATargetAndColumn() {
    Set<String> seen = new HashSet<>();
    for (EditableField field : EditableField.values()) {
      assertTrue(
          seen.add(field.target() + "." + field.column()),
          field.name() + " duplicates a target/column already claimed");
    }
  }

  /**
   * Fields 13 and 14 share one column, so the enum carries one constant for them and records 13.
   * Asserted rather than left implicit because a reader counting field-provenance.md's rows against
   * this enum will otherwise think 14 was forgotten.
   */
  @Test
  void spouseNameIsOneConstantCarryingFieldThirteen() {
    assertEquals(13, EditableField.SPOUSE_NAME.fieldNumber());
    assertEquals("spouse_name", EditableField.SPOUSE_NAME.column());
  }

  /** The one constant that does not live on {@code app.profile_customer_data}. */
  @Test
  void onlyTheOtherIncomeTextTargetsTheIncomeSourceTable() {
    for (EditableField field : EditableField.values()) {
      EditableField.Target expected =
          field == EditableField.INCOME_OTHER_TEXT
              ? EditableField.Target.INCOME_SOURCE_OTHER
              : EditableField.Target.CUSTOMER_DATA;
      assertEquals(expected, field.target(), field.name());
    }
  }
}
