import { describe, expect, it } from 'vitest';
import {
  EDITABLE_FIELDS,
  EDITABLE_FIELD_KEYS,
  MAX_FIELD_VALUE_LENGTH,
  isEditableFieldKey,
} from './editableFields';

/**
 * These tests pin the CLIENT's mirror of `operator.domain.EditableField`. Nothing here reaches the
 * Java enum and no gate in any tier compares the two — the drift warning on the module itself is
 * the whole protection. What these tests CAN do is stop the mirror changing silently.
 */
describe('editableFields', () => {
  it('pins all nineteen keys, spelled as the server spells them', () => {
    // Nineteen, not the often-quoted fourteen. The fourteen is the count for ONE shape of
    // profile -- Sudan-resident, Sudan-born, Sudan-employed, married, «أخرى» income, no scanned
    // birth city -- and it is the count the approved artboard happens to draw chips for. The
    // five extra are the non-Sudan free-text fallbacks, and a customer abroad needs them.
    expect(EDITABLE_FIELD_KEYS).toHaveLength(19);
    expect([...EDITABLE_FIELD_KEYS].sort()).toEqual(
      [
        'BIRTH_CITY',
        'BIRTH_STATE_TEXT',
        'EMPLOYER_NAME',
        'ETHNICITY',
        'HOME_AREA',
        'HOME_BLOCK',
        'HOME_CITY',
        'HOME_HOUSE_NO',
        'HOME_LOCALITY_TEXT',
        'HOME_STATE_TEXT',
        'HOME_STREET',
        'INCOME_OTHER_TEXT',
        'SPOUSE_NAME',
        'WORK_AREA',
        'WORK_BLOCK',
        'WORK_CITY',
        'WORK_LOCALITY_TEXT',
        'WORK_STATE_TEXT',
        'WORK_STREET',
      ].sort(),
    );
  });

  it('carries the five non-Sudan fallbacks the artboard draws no chip for', () => {
    // Drawn as read-only rows on the artboard because its fixture is a Sudan profile. A screen
    // that hardcoded the artboard's fourteen chips would leave every customer abroad unable to
    // have their address corrected -- and R-042 records that those are not rare.
    for (const key of ['BIRTH_STATE_TEXT', 'WORK_STATE_TEXT', 'WORK_LOCALITY_TEXT', 'HOME_STATE_TEXT', 'HOME_LOCALITY_TEXT'] as const) {
      expect(isEditableFieldKey(key)).toBe(true);
    }
  });

  it('maps every key to its number on the bank paper form', () => {
    expect(EDITABLE_FIELDS.ETHNICITY).toBe(10);
    expect(EDITABLE_FIELDS.SPOUSE_NAME).toBe(13);
    expect(EDITABLE_FIELDS.INCOME_OTHER_TEXT).toBe(20);
    expect(EDITABLE_FIELDS.BIRTH_CITY).toBe(23);
    expect(EDITABLE_FIELDS.HOME_HOUSE_NO).toBe(42);
  });

  it('gives every key a distinct field number, except the one column with two labels', () => {
    const numbers = EDITABLE_FIELD_KEYS.map((key) => EDITABLE_FIELDS[key]);
    // SPOUSE_NAME is fields 13 AND 14 under one constant, because `spouse_name` is one column
    // and which label renders is a function of the customer's sex. Every other number is unique,
    // and a duplicate would mean two constants fighting over one row on the screen.
    expect(new Set(numbers).size).toBe(numbers.length);
  });

  it('refuses a key the server did not send, rather than guessing at it', () => {
    expect(isEditableFieldKey('PHONE_NUMBER')).toBe(false);
    expect(isEditableFieldKey('')).toBe(false);
    // Case-SENSITIVE, matching `EditableField.parse` server-side. Accepting a variant here would
    // send a spelling the server refuses, and put it in an audit payload on the way.
    expect(isEditableFieldKey('ethnicity')).toBe(false);
    expect(isEditableFieldKey('ETHNICITY')).toBe(true);
  });

  it('does not mistake an inherited Object property for a field key', () => {
    // `hasOwnProperty` via Object.prototype rather than `key in EDITABLE_FIELDS`: `'toString' in
    // obj` is true for every object, so the naive check would have let 'toString' and
    // 'constructor' through as field keys and PATCHed them.
    expect(isEditableFieldKey('toString')).toBe(false);
    expect(isEditableFieldKey('constructor')).toBe(false);
    expect(isEditableFieldKey('hasOwnProperty')).toBe(false);
  });

  it('mirrors the server value ceiling', () => {
    // FieldEditValidator.MAX_VALUE_LENGTH. The server is the enforcement; this exists so a
    // too-long value is refused before a round trip, not instead of one.
    expect(MAX_FIELD_VALUE_LENGTH).toBe(200);
  });
});
