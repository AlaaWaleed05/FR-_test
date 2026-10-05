import { apiFetch, apiFetchBlob } from './http';
import type {
  FieldEditRequest,
  FieldEditResponse,
  ProfileDetailResponse,
  ProfileListQuery,
  ProfileListResponse,
  RejectRequest,
  ReviewResponse,
} from './types';

/**
 * All filtering, sorting, searching and pagination happens server-side
 * (`operator.web.ProfileListController`) — this function only shapes the query string. See
 * `docs/components/backoffice-components.md`'s "Table — the standing rule".
 */
export async function searchProfiles(query: ProfileListQuery): Promise<ProfileListResponse> {
  return apiFetch<ProfileListResponse>('/operator/profiles', { query: query as Record<string, unknown> });
}

/** `operator.web.ProfileController#view` — also fires the backend's `profile_viewed` audit event. */
export async function getProfile(profileId: string): Promise<ProfileDetailResponse> {
  return apiFetch<ProfileDetailResponse>(`/operator/profiles/${profileId}`);
}

/** `operator.web.ReviewController#approve`. */
export async function approveProfile(profileId: string): Promise<ReviewResponse> {
  return apiFetch<ReviewResponse>(`/operator/profiles/${profileId}/approve`, { method: 'POST' });
}

/** `operator.web.ReviewController#reject`. */
export async function rejectProfile(profileId: string, request: RejectRequest): Promise<ReviewResponse> {
  return apiFetch<ReviewResponse>(`/operator/profiles/${profileId}/reject`, { method: 'POST', body: request });
}

/**
 * `operator.web.FieldEditController#edit` -- BL-135 / AD-015's per-field operator edit.
 *
 * PATCH, and one field per request: the URL addresses the field, so every edit is its own
 * transaction, its own audit event and its own `app.profile_field_edit` row. `apiFetch` already
 * echoes the CSRF token for PATCH (`http.ts`'s UNSAFE_METHODS), so nothing extra is needed here.
 *
 * `fieldKey` must be an `EditableField` NAME exactly as the server spells it -- the parse is
 * case-SENSITIVE server-side. Callers pass a value straight out of `detail.editableFields` or
 * out of `profiles/editableFields.ts`, never a hand-typed string.
 *
 * The caller is expected to RELOAD the profile afterwards rather than patch its own copy: an
 * edit can change the derived provenance and, in principle, what else is editable, and both are
 * re-derived server-side on the read path.
 *
 * @throws ApiError 400 a malformed value, 403 a viewer, 404 an unknown profile, 409 a field
 *     this profile does not expose or a status that forbids editing.
 */
export async function editProfileField(
  profileId: string,
  fieldKey: string,
  value: string,
): Promise<FieldEditResponse> {
  const body: FieldEditRequest = { value };
  return apiFetch<FieldEditResponse>(
    `/operator/profiles/${profileId}/fields/${encodeURIComponent(fieldKey)}`,
    { method: 'PATCH', body },
  );
}

export interface PrintRequest {
  /**
   * The five images and the salary certificate, appended to the same PDF. Asked every time and
   * defaulting to `false` — never a remembered setting, because an operator who once opted in
   * would otherwise go on printing customers' documents indefinitely without deciding to again
   * (wayfinder ticket 05 decision 9).
   */
  includeAttachments: boolean;
}

export interface PrintedForm {
  blob: Blob;
  /** `app.artifact_ref.artifact_ref_id` of the row this print became, off the response header. */
  artifactId: string | null;
}

/**
 * `printedform.web.PrintedFormController#print`.
 *
 * A POST because printing WRITES: the server stores a new artifact and appends an audit event in
 * one transaction, and the bytes it returns are the bytes it stored. A GET an operator could
 * bookmark, or a browser could prefetch, would mint stored PII copies on navigation.
 */
export async function printProfileForm(profileId: string, request: PrintRequest): Promise<PrintedForm> {
  const { blob, headers } = await apiFetchBlob(`/operator/profiles/${profileId}/print`, {
    method: 'POST',
    body: request,
  });
  return { blob, artifactId: headers.get('X-Printed-Form-Artifact-Id') };
}
