import type { ProfileProvenance, ProfileStatus } from '../api/types';

/**
 * Status and provenance are fixed domain enums, not reference data (CLAUDE.md's "never hardcoded"
 * rule names occupations/branches/administrative divisions/income sources/rejection reasons
 * specifically -- these nine statuses and two provenance values are structural, the same category
 * as the backend's own fixed `ProfileListSortField`/`OperatorAccessLevel` enums). Arabic text
 * matches `app.status_code`'s own seeded labels (V0005) verbatim, for consistency with anything
 * the backend itself ever renders.
 */
export const STATUS_LABELS_AR: Record<ProfileStatus, string> = {
  in_progress: 'قيد التنفيذ',
  awaiting_registry: 'بانتظار السجل المدني',
  blocked_scan: 'محظور مؤقتًا - المسح الضوئي',
  blocked_liveness: 'محظور مؤقتًا - التحقق الحي',
  abandoned: 'متروك',
  submitted: 'مُقدَّم',
  approved: 'معتمد',
  rejected: 'مرفوض',
  terminated_registry_mismatch: 'منتهي - تعارض مع السجل المدني',
};

export const STATUS_COLORS: Record<ProfileStatus, string> = {
  in_progress: 'blue',
  awaiting_registry: 'gold',
  blocked_scan: 'orange',
  blocked_liveness: 'orange',
  abandoned: 'default',
  submitted: 'processing',
  approved: 'success',
  rejected: 'error',
  terminated_registry_mismatch: 'volcano',
};

export const PROVENANCE_LABELS_AR: Record<ProfileProvenance, string> = {
  digital: 'رقمي',
  manual: 'يدوي',
};

export const PROVENANCE_COLORS: Record<ProfileProvenance, string> = {
  digital: 'blue',
  manual: 'purple',
};

export const STATUS_OPTIONS: { value: ProfileStatus; label: string }[] = (
  Object.keys(STATUS_LABELS_AR) as ProfileStatus[]
).map((value) => ({ value, label: STATUS_LABELS_AR[value] }));

export const PROVENANCE_OPTIONS: { value: ProfileProvenance; label: string }[] = (
  Object.keys(PROVENANCE_LABELS_AR) as ProfileProvenance[]
).map((value) => ({ value, label: PROVENANCE_LABELS_AR[value] }));
