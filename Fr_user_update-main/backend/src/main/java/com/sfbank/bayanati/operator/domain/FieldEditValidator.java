package com.sfbank.bayanati.operator.domain;

/**
 * What an operator may store in an editable field — AD-015's "validation and reference lists match
 * the mobile app field for field, so no value can enter that the app could not produce".
 *
 * <p><strong>These rules are NEW CODE, not a reuse of {@code DataEntryService}, and the difference
 * is worth stating because it looks like duplication.</strong> Two reasons it cannot be reuse:
 *
 * <ul>
 *   <li>{@code DataEntryService} validates a WHOLE STAGE. Its rules are cross-field — spouse name
 *       only when married, children count only when {@code hasChildren}, state and locality
 *       required together by country, exactly one primary income source — and a single-field edit
 *       has no second field to check against. {@link EditableFieldPolicy} is where those couplings
 *       are honoured instead: a field the cross-field rules would forbid is never editable in the
 *       first place, so the edit path never has to re-derive them.
 *   <li>The non-blank rule <strong>does not exist on the backend at all</strong>. Mobile enforces
 *       it on every one of these fields ({@code stage3_screen.dart}, {@code stage5_screen.dart},
 *       {@code stage6_screen.dart} all trim and refuse empty), but {@code
 *       DataEntryService.submitStage5}/{@code submitStage6} pass city, area, street, block and
 *       house number straight through with no check, and {@code submitStage3} never validates
 *       {@code ethnicity} or {@code birthCityText}. Copying the backend's stage validators would
 *       therefore have copied a GAP. Matching MOBILE is what AD-015 asks for, and that is what this
 *       does.
 * </ul>
 *
 * <p>Pure policy: no Spring context, no database, no network, no clock.
 */
public final class FieldEditValidator {

  /**
   * An application-level ceiling with no mobile counterpart, and deliberately so.
   *
   * <p>Every one of these columns is an unbounded {@code text} (V0006, V0025), so the database
   * accepts a megabyte. On the phone the bound is physical — a customer types into a form field on
   * a handset — but this is an authenticated HTTP {@code PATCH}, where nothing stops a megabyte
   * except a rule like this one. Same reasoning as {@code DataEntryService.MAX_CHILDREN_COUNT},
   * which exists because {@code children_count} is a {@code smallint} with no CHECK: without an
   * application bound the failure is a raw driver error at the {@code UPDATE} instead of a friendly
   * 400.
   *
   * <p>200 is chosen against the longest real value any of these fields carries — a Sudanese
   * employer name or a four-part street description — with room to spare, and it is not a value any
   * legitimate edit approaches.
   */
  public static final int MAX_VALUE_LENGTH = 200;

  private FieldEditValidator() {}

  /**
   * Normalises and checks one edit, returning the value to store.
   *
   * <p>Trimming is part of the contract rather than a courtesy: mobile stores {@code
   * controller.text.trim()} for every one of these fields, so an untrimmed operator edit would put
   * a value in the column that the app could not have produced.
   *
   * @throws FieldEditRejectedException if the value is absent, blank after trimming, over {@link
   *     #MAX_VALUE_LENGTH}, or carries a control character
   */
  public static String normalise(EditableField field, String rawValue) {
    if (rawValue == null) {
      throw new FieldEditRejectedException(
          "a value is required for " + field.name() + " (field " + field.fieldNumber() + ")");
    }
    // strip(), not trim(): trim() only removes characters at or below U+0020, so a value padded
    // with U+2028 or U+2007 would survive it. Dart's own String.trim() -- which every mobile
    // stage calls before storing -- uses the Unicode White_Space property, and strip() is the
    // closer of the two Java methods to it.
    String value = rawValue.strip();
    // isSpaceChar catches the one class strip() still leaves behind: a NO-BREAK SPACE (U+00A0)
    // and its relatives are Unicode space characters but are NOT Character.isWhitespace, so a
    // value of nothing but non-breaking spaces survives strip() and would be stored as a
    // blank-LOOKING mandatory field. Dart's trim() removes them; without this, mobile and the
    // edit endpoint would disagree about what counts as empty.
    if (value.isEmpty() || value.codePoints().allMatch(Character::isSpaceChar)) {
      // Every editable field is mandatory in the mobile journey -- there is no editable field a
      // customer could leave empty, so an empty edit is always a deletion of data the journey
      // required. It is also what app.profile_income_source's other_needs_text CHECK would reject
      // for INCOME_OTHER_TEXT, as a constraint violation rather than a 400.
      throw new FieldEditRejectedException(
          "a value is required for " + field.name() + " (field " + field.fieldNumber() + ")");
    }
    if (value.length() > MAX_VALUE_LENGTH) {
      throw new FieldEditRejectedException(
          "value for "
              + field.name()
              + " (field "
              + field.fieldNumber()
              + ") exceeds "
              + MAX_VALUE_LENGTH
              + " characters");
    }
    if (containsControlCharacter(value)) {
      // A newline or a tab in a single-line free-text field is not something the mobile form can
      // produce, and it reaches the printed form (S9-03) and the CSV export, where an embedded
      // newline changes the shape of a row rather than its content.
      throw new FieldEditRejectedException(
          "value for " + field.name() + " must not contain control characters");
    }
    return value;
  }

  /**
   * Control characters as Java classifies them, which covers CR, LF, TAB and the C1 range.
   *
   * <p>Deliberately NOT a check on script or digit shape: these are Arabic free-text fields and a
   * customer may legitimately type Arabic-Indic digits in a house number. CLAUDE.md's ASCII-digit
   * rule is scoped to phone numbers, which are not editable here.
   */
  private static boolean containsControlCharacter(String value) {
    return value.codePoints().anyMatch(Character::isISOControl);
  }
}
