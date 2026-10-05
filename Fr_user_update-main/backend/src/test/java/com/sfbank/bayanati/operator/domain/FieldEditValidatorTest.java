package com.sfbank.bayanati.operator.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * AD-015: "validation and reference lists match the mobile app field for field, so no value can
 * enter that the app could not produce."
 *
 * <p>Plain JUnit: no Spring context, no database, no network, no clock.
 */
class FieldEditValidatorTest {

  @Test
  void trimsTheValueTheWayEveryMobileStageDoes() {
    // stage5_screen.dart stores controller.text.trim() for all five of these; an untrimmed operator
    // edit would put a value in the column the app could not have produced.
    assertEquals("الثورة", FieldEditValidator.normalise(EditableField.HOME_AREA, "  الثورة  "));
  }

  @Test
  void acceptsAValueThatNeedsNoTrimmingUnchanged() {
    assertEquals(
        "الجهاز المركزي للإحصاء",
        FieldEditValidator.normalise(EditableField.EMPLOYER_NAME, "الجهاز المركزي للإحصاء"));
  }

  @Test
  void refusesNull() {
    FieldEditRejectedException thrown =
        assertThrows(
            FieldEditRejectedException.class,
            () -> FieldEditValidator.normalise(EditableField.ETHNICITY, null));
    assertTrue(thrown.getMessage().contains("ETHNICITY"), thrown.getMessage());
  }

  /**
   * Every editable field is mandatory in the mobile journey, so an empty edit always deletes data
   * the journey required. For {@link EditableField#INCOME_OTHER_TEXT} it is also what {@code
   * app.profile_income_source}'s {@code other_needs_text} CHECK would refuse — as a constraint
   * violation and a 500, rather than this 400.
   */
  @Test
  void refusesABlankAndAWhitespaceOnlyValue() {
    assertThrows(
        FieldEditRejectedException.class,
        () -> FieldEditValidator.normalise(EditableField.HOME_CITY, ""));
    // U+00A0 is a Unicode space but NOT Character.isWhitespace, so String.strip() leaves it --
    // the separate isSpaceChar check is what refuses it, matching Dart trim()'s own behaviour.
    assertThrows(
        FieldEditRejectedException.class,
        () -> FieldEditValidator.normalise(EditableField.HOME_CITY, "   "));
    assertThrows(
        FieldEditRejectedException.class,
        () -> FieldEditValidator.normalise(EditableField.HOME_CITY, "  "));
    assertThrows(
        FieldEditRejectedException.class,
        () -> FieldEditValidator.normalise(EditableField.INCOME_OTHER_TEXT, " "));
  }

  @Test
  void acceptsExactlyTheMaximumLengthAndRefusesOneMore() {
    String atLimit = "ا".repeat(FieldEditValidator.MAX_VALUE_LENGTH);
    assertEquals(atLimit, FieldEditValidator.normalise(EditableField.HOME_STREET, atLimit));

    String overLimit = "ا".repeat(FieldEditValidator.MAX_VALUE_LENGTH + 1);
    FieldEditRejectedException thrown =
        assertThrows(
            FieldEditRejectedException.class,
            () -> FieldEditValidator.normalise(EditableField.HOME_STREET, overLimit));
    assertTrue(thrown.getMessage().contains("200"), thrown.getMessage());
  }

  /** The length bound applies AFTER trimming — padding is not content. */
  @Test
  void theLengthBoundIsMeasuredAfterTrimming() {
    String padded = "  " + "ا".repeat(FieldEditValidator.MAX_VALUE_LENGTH) + "  ";

    assertEquals(
        FieldEditValidator.MAX_VALUE_LENGTH,
        FieldEditValidator.normalise(EditableField.HOME_STREET, padded).length());
  }

  /**
   * A newline reaches the CSV export and the printed form, where it changes the SHAPE of a row
   * rather than its content. No mobile form field can produce one.
   */
  @Test
  void refusesControlCharactersIncludingNewlinesAndTabs() {
    // All three are EMBEDDED. A merely TRAILING newline is not a rejection -- strip() removes it
    // and the value is then clean, which is exactly what mobile's own trim() does to it.
    for (String value : new String[] {"الثورة\nالحارة", "الثورة\tالحارة", "الثورة\rالحارة"}) {
      FieldEditRejectedException thrown =
          assertThrows(
              FieldEditRejectedException.class,
              () -> FieldEditValidator.normalise(EditableField.HOME_AREA, value));
      assertTrue(thrown.getMessage().contains("control characters"), thrown.getMessage());
    }
  }

  /**
   * These are Arabic free-text fields and a customer may legitimately type Arabic-Indic digits in a
   * house number. CLAUDE.md's ASCII-digit rule is scoped to PHONE NUMBERS, which AD-022 makes
   * permanently uneditable here — so applying it to a house number would refuse a value the mobile
   * app accepts.
   */
  @Test
  void acceptsArabicIndicDigitsInAHouseNumber() {
    assertEquals("٤٧", FieldEditValidator.normalise(EditableField.HOME_HOUSE_NO, "٤٧"));
  }

  @Test
  void theRejectionNamesTheFieldNumberSoAnOperatorCanFindItOnThePaperForm() {
    FieldEditRejectedException thrown =
        assertThrows(
            FieldEditRejectedException.class,
            () -> FieldEditValidator.normalise(EditableField.HOME_HOUSE_NO, ""));

    assertTrue(thrown.getMessage().contains("field 42"), thrown.getMessage());
  }

  /** Every constant is validated by the same rules — none is exempt. */
  @Test
  void everyEditableFieldIsSubjectToTheSameRules() {
    for (EditableField field : EditableField.values()) {
      assertThrows(
          FieldEditRejectedException.class,
          () -> FieldEditValidator.normalise(field, "  "),
          field.name() + " must refuse a blank");
      assertEquals(
          "قيمة",
          FieldEditValidator.normalise(field, "قيمة"),
          field.name() + " must accept an ordinary value");
    }
  }
}
