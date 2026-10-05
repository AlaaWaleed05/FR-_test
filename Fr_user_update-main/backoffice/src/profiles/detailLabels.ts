/**
 * Fixed domain dictionaries for the single profile view — CHECK-constrained columns
 * (V0006/V0008/V0023) and structural wire enums, not reference data (CLAUDE.md's "never
 * hardcoded" rule names occupations/branches/administrative divisions/income sources/rejection
 * reasons specifically — these are the same category as `statusLabels.ts`'s own status/provenance
 * dicts, not a list an operator would ever need server-versioned).
 */
import type { ChannelStateWire, MessageChannelWire } from '../api/types';

export const CHANNEL_LABELS_AR: Record<MessageChannelWire, string> = {
  sms: 'رسالة نصية',
  whatsapp: 'واتساب',
  email: 'بريد إلكتروني',
};

export const CHANNEL_STATE_LABELS_AR: Record<ChannelStateWire, string> = {
  verified: 'موثّق',
  declined: 'مرفوض',
  unverified: 'غير موثّق',
};

// CHANNEL_STATE_COLORS was removed by AD-022 (S9-01). It mapped all three channel states to Tag
// colours, and with only VERIFIED channels rendered its other two entries became unreachable and
// its one remaining entry a constant. `git show a102ff2~1` has it if a surface ever needs to draw
// the full set again.

export const ACTOR_KIND_LABELS_AR: Record<string, string> = {
  operator: 'مشغّل',
  customer: 'عميل',
  system: 'نظام',
};

/**
 * `app.scan_result.document_type` — Uqudo's own vocabulary (V0008's comment), not translated.
 *
 * BL-151, fixed at S9-02: `SDN_ID` read «بطاقة وطنية» while every other tier says
 * «البطاقة القومية» — the mobile app (`stage7_screen.dart`, `stage8_screen.dart`), the printed
 * form (`FieldOrigin.NATIONAL_ID`), `customer.md:653`'s explicit ruling, and the approved
 * artboard. The back office was the only outlier, so an operator read one term on screen while
 * the customer read another in the app and a third sat on the form they both sign.
 */
export const DOCUMENT_TYPE_LABELS_AR: Record<string, string> = {
  PASSPORT: 'جواز سفر',
  SDN_ID: 'البطاقة القومية',
};

export function documentTypeLabel(documentType: string | null): string {
  if (!documentType) return '—';
  return DOCUMENT_TYPE_LABELS_AR[documentType] ?? documentType;
}

/** `app.profile_customer_data.sex_declared` / `.sex_registry` — 'm'/'f' (V0006/V0023). */
export const SEX_LABELS_AR: Record<string, string> = { m: 'ذكر', f: 'أنثى' };

/**
 * `scanResult.sexOnDocument` is unconstrained free text from Uqudo (V0008), unlike
 * `sex_declared`/`sex_registry`'s CHECK-bound 'm'/'f' -- callers lowercase before passing in to
 * cover 'M'/'F', but the fallback below must return the ORIGINAL value, not the lowercased one,
 * so an unrecognised value is shown as received rather than silently case-mutated. Found under
 * review.
 */
export function sexLabel(sex: string | null): string {
  if (!sex) return '—';
  return SEX_LABELS_AR[sex.toLowerCase()] ?? sex;
}

/** `app.profile_customer_data.marital_status` (V0006), masculine. */
export const MARITAL_STATUS_LABELS_AR: Record<string, string> = {
  single: 'أعزب',
  married: 'متزوج',
  divorced: 'مطلّق',
  widowed: 'أرمل',
};

/** The same four states in feminine agreement. Mirrors the form's `MARITAL_STATUS_FEMININE`. */
export const MARITAL_STATUS_LABELS_AR_FEMININE: Record<string, string> = {
  single: 'عزباء',
  married: 'متزوجة',
  divorced: 'مطلّقة',
  widowed: 'أرملة',
};

/**
 * BL-161. The screen said «متزوج» for a married woman where the printed form says «متزوجة».
 *
 * <p>The form gained the agreement at S9-03 by product-owner ruling (AD-022 ruling (f)) — a bank
 * document addressing a woman as «متزوج» is a visible defect on a sheet she may be handed — and
 * the approved SCREEN artboard shows the feminine form too. So the back office was the stale tier,
 * not the form.
 *
 * @param female whether the customer is female, so the state agrees with her. Unknown sex takes
 *   the masculine form, which is the language's own unmarked default rather than a guess — the
 *   same fallback the form makes.
 */
export function maritalStatusLabel(value: string | null, female = false): string {
  if (!value) return '—';
  const labels = female ? MARITAL_STATUS_LABELS_AR_FEMININE : MARITAL_STATUS_LABELS_AR;
  return labels[value] ?? value;
}

/**
 * Which sex the screen agrees with: the Civil Registry's, falling back to what the customer
 * declared.
 *
 * <p>ONE derivation, exported, because two rows depend on it and they must not disagree. The
 * marital-status row and the spouse-name row sit three lines apart in section 3, and the spouse
 * row was reading `sexDeclared` alone while the printed form has always used the registry first
 * (`PrintedFormAssembler.effectiveSex`). A woman whose registry sex and declared sex differed
 * would have been shown «اسم الزوج» above «متزوج» on the same screen — each row right by its own
 * rule and the pair incoherent. Aligning both on the form's rule is what makes screen and paper
 * say the same thing about the same customer.
 */
export function isFemale(sexRegistry: string | null, sexDeclared: string | null): boolean {
  const effective = sexRegistry?.trim() || sexDeclared;
  return effective?.toLowerCase() === 'f';
}

/** `app.profile_customer_data.identity_type` (V0006). */
export const IDENTITY_TYPE_LABELS_AR: Record<string, string> = {
  passport: 'جواز سفر',
  national_id: 'البطاقة القومية',
};

export function identityTypeLabel(value: string | null): string {
  if (!value) return '—';
  return IDENTITY_TYPE_LABELS_AR[value] ?? value;
}
