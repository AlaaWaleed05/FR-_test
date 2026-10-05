/**
 * The wire vocabulary of per-field editing — BL-135, AD-015, AD-021.
 *
 * Every constant here is a `operator.domain.EditableField` NAME, spelled exactly as the server
 * spells it, because that name is both what `ProfileDetailResponse.editableFields` carries and
 * what the PATCH endpoint takes as its `fieldKey` path variable. The server's `EditableField.parse`
 * is case-SENSITIVE and resolves against its own enum, so a typo here is a 409, never a silent
 * write to the wrong column.
 *
 * **This list is a MIRROR of the Java enum and no gate detects drift** — the same standing hazard
 * `artifactTiles.ts` records about `OperatorImagePolicy.viewableKinds()`. A constant added
 * server-side and not here simply never draws a chip; one added here and not there 409s on use.
 * Whoever adds an editable field must touch both in the same commit. Adding one is an
 * architecture decision in any case (the Java enum's own javadoc says so), not a code change.
 *
 * **Membership is necessary, never sufficient.** Whether a field is editable on a GIVEN profile
 * is derived per profile by the server and arrives in `editableFields`; five of these exist only
 * as the non-Sudan free-text fallback of a cascading list, one only where the Uqudo scan supplied
 * nothing, and one only on a married profile. The screen therefore reads the response and never
 * this list to decide what to offer — this list only says which row a key belongs to.
 */

/**
 * The 19 constants, each mapped to its number on the bank's paper form
 * (`docs/journeys/field-provenance.md`).
 *
 * The numbers are not decoration: the approved screen prints them down the side of every row, so
 * an operator on the phone to a branch can say "field 38" and be understood.
 */
export const EDITABLE_FIELDS = {
  /** Field 10, «الجنس» — the bank's label for ETHNICITY, not sex. Free text, always editable. */
  ETHNICITY: 10,
  /**
   * Fields 13 AND 14 — «اسم الزوج» and «اسم الزوجة». ONE constant because there is one column,
   * `app.profile_customer_data.spouse_name`; which label renders is a function of the customer's
   * sex. Editable only on a married profile.
   */
  SPOUSE_NAME: 13,
  /** Field 23, «مدينة الميلاد». Editable ONLY where the Uqudo scan supplied no `placeOfBirth`. */
  BIRTH_CITY: 23,
  /** Field 24, «ولاية الميلاد» — the non-Sudan free-text fallback only. */
  BIRTH_STATE_TEXT: 24,
  /** Field 27, «جهة العمل». Free text for every occupation. */
  EMPLOYER_NAME: 27,
  /** Field 29, «الولاية» of the work address — non-Sudan fallback only. */
  WORK_STATE_TEXT: 29,
  /** Field 30, «المحافظة» of the work address — non-Sudan fallback only. */
  WORK_LOCALITY_TEXT: 30,
  /** Field 31, «المنطقة» of the work address. */
  WORK_AREA: 31,
  /** Field 32, «المدينة» of the work address. */
  WORK_CITY: 32,
  /** Field 33, «الشارع» of the work address. */
  WORK_STREET: 33,
  /** Field 34, «المربع» of the work address. */
  WORK_BLOCK: 34,
  /** Field 36, «الولاية» of the home address — non-Sudan fallback only. */
  HOME_STATE_TEXT: 36,
  /** Field 37, «المحافظة» of the home address — non-Sudan fallback only. */
  HOME_LOCALITY_TEXT: 37,
  /** Field 38, «المنطقة» of the home address. */
  HOME_AREA: 38,
  /** Field 39, «المدينة» of the home address. */
  HOME_CITY: 39,
  /** Field 40, «الشارع» of the home address. */
  HOME_STREET: 40,
  /** Field 41, «المربع» of the home address. */
  HOME_BLOCK: 41,
  /** Field 42, «رقم المنزل». Free TEXT, not digits — a Sudanese house number is not numeric. */
  HOME_HOUSE_NO: 42,
  /**
   * Field 20's «أخرى» free text, and ONLY that.
   *
   * The approved artboard draws a «تعديل» chip on field 20, but «مصدر الدخل» is a coded
   * multi-select with a primary flag, and AD-021 forbids editing a list-picked value. The one
   * free-text part the customer typed is the `OTHER` row's `other_text`, which exists only when
   * `OTHER` is among the selected codes — the only reading of that chip that does not contradict
   * the rule beside it (product-owner ruling, 2026-09-16). The codes and the primary flag stay
   * read-only.
   */
  INCOME_OTHER_TEXT: 20,
} as const;

export type EditableFieldKey = keyof typeof EDITABLE_FIELDS;

/** Every key, for tests and for exhaustiveness checks. */
export const EDITABLE_FIELD_KEYS = Object.keys(EDITABLE_FIELDS) as EditableFieldKey[];

/**
 * Whether a wire string is one of ours.
 *
 * Used to keep an UNRECOGNISED key out of the UI rather than trusting the response blindly. A
 * server that grew a twentieth constant would otherwise have this screen offer a chip it has no
 * row to attach to — and the honest behaviour is to ignore it, because the field it names is not
 * one this screen draws.
 */
export function isEditableFieldKey(value: string): value is EditableFieldKey {
  return Object.prototype.hasOwnProperty.call(EDITABLE_FIELDS, value);
}

/**
 * The maximum an operator may type into any editable field.
 *
 * Mirrors `operator.domain.FieldEditValidator.MAX_VALUE_LENGTH`. The server is the enforcement —
 * this is here so a too-long value is refused before a round trip, not instead of one. The Java
 * side explains the 200: every one of these columns is an unbounded `text`, so without an
 * application ceiling an authenticated PATCH could store a megabyte.
 */
export const MAX_FIELD_VALUE_LENGTH = 200;
