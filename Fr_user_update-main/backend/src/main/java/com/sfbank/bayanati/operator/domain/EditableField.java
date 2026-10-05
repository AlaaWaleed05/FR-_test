package com.sfbank.bayanati.operator.domain;

import java.util.Optional;

/**
 * Every field an operator may ever key, and nothing else — AD-015 as narrowed twice.
 *
 * <p><strong>The first narrowing (2026-09-14)</strong> cut the editable set to the fields whose
 * Source is S3 in {@code docs/journeys/field-provenance.md}. The 6 Civil Registry fields, the 7
 * Uqudo fields and the system-generated form date are never editable by anyone. <strong>The second
 * (AD-021)</strong> cut it further to the FREE-TEXT subset of those: a list-picked value is never
 * editable, because editing a coded value breaks the codes filtering and export are built on.
 *
 * <p>Together those bounds are what R-054 relies on — <em>no edit can invent a scan, a face match
 * or a registry record</em> — so this enum is a closed allow-list and every addition to it is an
 * architecture decision, not a code change.
 *
 * <p><strong>Membership here is necessary, never sufficient.</strong> Whether a given field is
 * editable on a given PROFILE is derived per profile by {@link EditableFieldPolicy}: five of these
 * constants exist only as the non-Sudan free-text fallback of a cascading list, one only where the
 * Uqudo scan supplied nothing, and two only on a married profile. A hardcoded list would be wrong
 * for every customer abroad, and R-042's own note records that those are not rare.
 *
 * <p>Rows 49-54 of field-provenance.md (signature, salary certificate, identity documents, the
 * portraits, the document images, the liveness audit image) are ARTIFACTS rather than fields and
 * are out of scope for field editing — stated here rather than left to be inferred, which is one of
 * the four edges BL-135 asked to have settled inside the build.
 */
public enum EditableField {

  /**
   * Field 10, «الجنس» — the bank's label for ETHNICITY, not sex (field-provenance.md's "Two form
   * labels the bank uses differently than a literal reading suggests"). Free text supplied by no
   * source, mandatory, always editable.
   */
  ETHNICITY(10, Target.CUSTOMER_DATA, "ethnicity"),

  /**
   * Fields 13 and 14 — «اسم الزوج» and «اسم الزوجة». ONE constant, because there is one column:
   * field-provenance.md lists them as two numbered rows, but {@code
   * app.profile_customer_data.spouse_name} (V0006) is a single field and which label renders is a
   * function of the customer's sex. The number recorded is 13; 14 is the same column under the
   * other label. Editable only on a married profile — {@code
   * DataEntryService.validateMaritalStatus} refuses {@code spouseName} on any other marital status,
   * so the column is null there and an edit would be creating a value the customer journey forbids.
   */
  SPOUSE_NAME(13, Target.CUSTOMER_DATA, "spouse_name"),

  /**
   * Field 23, «مدينة الميلاد». Editable ONLY where the Uqudo scan supplied no {@code placeOfBirth}
   * — product-owner ruling, 2026-09-16, closing the third of BL-135's four open edges. The field's
   * Source is "S2 {@code placeOfBirth}, else free text", and customer.md Stage 3 records that
   * "Uqudo's value supersedes theirs when the scan lands". Making it unconditionally editable would
   * both widen the Uqudo read-only bound and let an operator type into a field whose displayed
   * value comes from somewhere else.
   */
  BIRTH_CITY(23, Target.CUSTOMER_DATA, "birth_city_text"),

  /**
   * Field 24, «ولاية الميلاد» — the non-Sudan fallback only. customer.md Stage 3: "When birth
   * country is Sudan, selects from the Sudan state list; otherwise free text. Same non-Sudan
   * fallback pattern as the address hierarchy". NOT named by the redesign brief's §3 table, which
   * states the derived rule for fields 36/37 alone; extended here because the column structure and
   * the journey rule are identical (product-owner default, 2026-09-16).
   */
  BIRTH_STATE_TEXT(24, Target.CUSTOMER_DATA, "birth_state_text"),

  /** Field 27, «جهة العمل». Free text for every occupation (customer.md Stage 6). */
  EMPLOYER_NAME(27, Target.CUSTOMER_DATA, "employer_name"),

  /**
   * Field 29, «الولاية» of the work address — non-Sudan fallback only. See {@link
   * #BIRTH_STATE_TEXT}.
   */
  WORK_STATE_TEXT(29, Target.CUSTOMER_DATA, "work_state_text"),

  /** Field 30, «المحافظة» of the work address — non-Sudan fallback only. */
  WORK_LOCALITY_TEXT(30, Target.CUSTOMER_DATA, "work_locality_text"),

  /** Field 31, «المنطقة» of the work address. */
  WORK_AREA(31, Target.CUSTOMER_DATA, "work_area"),

  /** Field 32, «المدينة» of the work address. */
  WORK_CITY(32, Target.CUSTOMER_DATA, "work_city"),

  /** Field 33, «الشارع» of the work address. */
  WORK_STREET(33, Target.CUSTOMER_DATA, "work_street"),

  /** Field 34, «المربع» of the work address. */
  WORK_BLOCK(34, Target.CUSTOMER_DATA, "work_block"),

  /** Field 36, «الولاية» of the home address — non-Sudan fallback only. */
  HOME_STATE_TEXT(36, Target.CUSTOMER_DATA, "home_state_text"),

  /** Field 37, «المحافظة» of the home address — non-Sudan fallback only. */
  HOME_LOCALITY_TEXT(37, Target.CUSTOMER_DATA, "home_locality_text"),

  /** Field 38, «المنطقة» of the home address. */
  HOME_AREA(38, Target.CUSTOMER_DATA, "home_area"),

  /** Field 39, «المدينة» of the home address. */
  HOME_CITY(39, Target.CUSTOMER_DATA, "home_city"),

  /** Field 40, «الشارع» of the home address. */
  HOME_STREET(40, Target.CUSTOMER_DATA, "home_street"),

  /** Field 41, «المربع» of the home address. */
  HOME_BLOCK(41, Target.CUSTOMER_DATA, "home_block"),

  /** Field 42, «رقم المنزل». Free text, not digits — a Sudanese house number is not numeric. */
  HOME_HOUSE_NO(42, Target.CUSTOMER_DATA, "home_house_no"),

  /**
   * Field 20's «أخرى» free text, and ONLY that — product-owner ruling, 2026-09-16.
   *
   * <p>The approved artboard draws a «تعديل» chip on field 20, but «مصدر الدخل» is a coded
   * multi-select with a primary flag ({@code app.profile_income_source}, V0006), and AD-021 forbids
   * editing a list-picked value. The one free-text part the customer typed is {@code other_text},
   * which exists only when {@code OTHER} is among the selected codes — so this is the only reading
   * of that chip that does not contradict the rule it sits beside. The codes themselves and the
   * primary flag stay read-only.
   *
   * <p>The only constant whose target is not {@code app.profile_customer_data}: it writes the
   * {@code OTHER} row of {@code app.profile_income_source}, whose {@code other_needs_text} CHECK
   * makes blanking it a constraint violation — which is why {@link FieldEditValidator} refuses a
   * blank for every field rather than leaving it to the database.
   */
  INCOME_OTHER_TEXT(20, Target.INCOME_SOURCE_OTHER, "other_text");

  /** Which table the value lives in. */
  public enum Target {
    /** {@code app.profile_customer_data}, keyed by {@code profile_id} alone. */
    CUSTOMER_DATA,
    /** {@code app.profile_income_source}, the row whose {@code source_code} is {@code OTHER}. */
    INCOME_SOURCE_OTHER
  }

  private final int fieldNumber;
  private final Target target;
  private final String column;

  EditableField(int fieldNumber, Target target, String column) {
    this.fieldNumber = fieldNumber;
    this.target = target;
    this.column = column;
  }

  /**
   * This field's number on the bank's paper form, per {@code docs/journeys/field-provenance.md}.
   */
  public int fieldNumber() {
    return fieldNumber;
  }

  public Target target() {
    return target;
  }

  /**
   * The column this field writes.
   *
   * <p>Callers interpolate this into SQL, so it must never come from request input — {@link #parse}
   * is the only way in, and it resolves against this enum's own constants. A column name cannot be
   * a bind parameter in any JDBC driver, which is exactly why the parse step is the control rather
   * than a convenience.
   */
  public String column() {
    return column;
  }

  /**
   * Resolves a wire {@code fieldKey} to a constant.
   *
   * <p>Empty rather than throwing: an unknown key is ordinary client input on a PATCH path and
   * becomes a 400, not a 500. Exact match on the enum name, deliberately case-SENSITIVE — the wire
   * contract is the enum name as {@link #name()} spells it, and accepting variants would invite a
   * second spelling into audit payloads.
   */
  public static Optional<EditableField> parse(String fieldKey) {
    if (fieldKey == null) {
      return Optional.empty();
    }
    for (EditableField field : values()) {
      if (field.name().equals(fieldKey)) {
        return Optional.of(field);
      }
    }
    return Optional.empty();
  }
}
