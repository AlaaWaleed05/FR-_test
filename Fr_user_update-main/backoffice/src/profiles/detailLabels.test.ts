import { describe, expect, it } from 'vitest';
import { documentTypeLabel, identityTypeLabel, isFemale, maritalStatusLabel, sexLabel } from './detailLabels';

describe('documentTypeLabel', () => {
  it('resolves the two known Uqudo document types', () => {
    expect(documentTypeLabel('PASSPORT')).toBe('جواز سفر');
    expect(documentTypeLabel('SDN_ID')).toBe('البطاقة القومية');
  });

  it('falls back to the raw value for an unrecognised type, and a dash for null', () => {
    expect(documentTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(documentTypeLabel(null)).toBe('—');
  });
});

describe('sexLabel', () => {
  it('resolves m/f and falls back for anything else', () => {
    expect(sexLabel('m')).toBe('ذكر');
    expect(sexLabel('f')).toBe('أنثى');
    expect(sexLabel('x')).toBe('x');
    expect(sexLabel(null)).toBe('—');
  });

  it('resolves uppercase Uqudo scan values without case-mutating the fallback', () => {
    // scanResult.sexOnDocument is unconstrained free text (V0008), unlike sex_declared/
    // sex_registry's CHECK-bound lowercase 'm'/'f' -- 'M'/'F' must still resolve, and an
    // unrecognised value must come back exactly as received, not lowercased.
    expect(sexLabel('M')).toBe('ذكر');
    expect(sexLabel('F')).toBe('أنثى');
    expect(sexLabel('MALE')).toBe('MALE');
  });
});

describe('maritalStatusLabel', () => {
  it('resolves all four CHECK-constrained values in the masculine', () => {
    expect(maritalStatusLabel('single')).toBe('أعزب');
    expect(maritalStatusLabel('married')).toBe('متزوج');
    expect(maritalStatusLabel('divorced')).toBe('مطلّق');
    expect(maritalStatusLabel('widowed')).toBe('أرمل');
    expect(maritalStatusLabel(null)).toBe('—');
  });

  // BL-161. The printed form gained this at S9-03 by product-owner ruling (AD-022 ruling (f)) and
  // the approved screen artboard shows the feminine form too, so the back office was the stale
  // tier. All four states, not just «متزوجة» -- a map with one corrected entry would pass a test
  // that only checked the married case and still say «أعزب» to a single woman.
  it('agrees with a female customer in all four states', () => {
    expect(maritalStatusLabel('single', true)).toBe('عزباء');
    expect(maritalStatusLabel('married', true)).toBe('متزوجة');
    expect(maritalStatusLabel('divorced', true)).toBe('مطلّقة');
    expect(maritalStatusLabel('widowed', true)).toBe('أرملة');
  });

  it('takes the masculine for an unknown sex, which is the unmarked default', () => {
    expect(maritalStatusLabel('married')).toBe('متزوج');
    expect(maritalStatusLabel('married', false)).toBe('متزوج');
  });

  it('still falls back to the raw value on an unrecognised state, in either agreement', () => {
    expect(maritalStatusLabel('separated')).toBe('separated');
    expect(maritalStatusLabel('separated', true)).toBe('separated');
  });
});

describe('isFemale', () => {
  // The rule the printed form uses (PrintedFormAssembler.effectiveSex): the registry first,
  // because it is the reference wherever it supplies a value, then what the customer declared.
  it('prefers the registry over the declared value', () => {
    expect(isFemale('f', 'm')).toBe(true);
    expect(isFemale('m', 'f')).toBe(false);
  });

  it('falls back to the declared value when the registry has none', () => {
    expect(isFemale(null, 'f')).toBe(true);
    expect(isFemale('', 'f')).toBe(true);
    expect(isFemale('   ', 'f')).toBe(true);
  });

  it('is false when neither source says anything, so the masculine default applies', () => {
    expect(isFemale(null, null)).toBe(false);
  });

  // sex_registry and sex_declared are CHECK-bound to 'm'/'f', but a stored value may be cased
  // either way and the form lowercases before comparing.
  it('matches case-insensitively', () => {
    expect(isFemale('F', null)).toBe(true);
    expect(isFemale(null, 'F')).toBe(true);
  });
});

describe('identityTypeLabel', () => {
  it('resolves both CHECK-constrained values', () => {
    expect(identityTypeLabel('passport')).toBe('جواز سفر');
    expect(identityTypeLabel('national_id')).toBe('البطاقة القومية');
    expect(identityTypeLabel(null)).toBe('—');
  });
});
