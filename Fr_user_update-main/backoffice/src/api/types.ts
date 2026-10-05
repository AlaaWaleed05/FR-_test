/** Wire DTOs, kept in sync with the backend `operator.web`/`auth.web`/`reference.web` records. */

export type OperatorRole = 'viewer' | 'operator' | 'admin';

export interface MeResponse {
  username: string;
  displayName: string;
  role: OperatorRole;
  mustChangePassword: boolean;
}

export interface LoginResponse {
  mustChangePassword: boolean;
  role: OperatorRole;
}

export type ProfileStatus =
  | 'in_progress'
  | 'awaiting_registry'
  | 'blocked_scan'
  | 'blocked_liveness'
  | 'abandoned'
  | 'submitted'
  | 'approved'
  | 'rejected'
  | 'terminated_registry_mismatch';

export type ProfileProvenance = 'digital' | 'manual';

export interface ProfileSummaryResponse {
  profileId: string;
  accountNumber: string;
  
  displayNameAr: string | null;
  displayNameEn: string | null;
  status: ProfileStatus;
  provenance: ProfileProvenance;
  submittedAt: string | null;
  createdAt: string;
}

export interface ProfileListResponse {
  rows: ProfileSummaryResponse[];
  total: number;
}

export type ProfileListSortField = 'SUBMITTED_AT' | 'ACCOUNT_NUMBER' | 'STATUS' ;
export type SortOrder = 'ASC' | 'DESC';

export interface ProfileListQuery {
  status?: string;
  provenance?: string;
 
  rejectionReasonCode?: string;
  submittedFrom?: string;
  submittedTo?: string;
  q?: string;
  sortField?: ProfileListSortField;
  sortOrder?: SortOrder;
  page?: number;
  pageSize?: number;
}

export interface ManifestListEntry {
  listCode: string;
  version: number;
  itemCount: number;
  contentHash: string;
  isHierarchical: boolean;
  rootItemCode: string | null;
  rootCountryVersion: number | null;
  publishedAt: string;
  documentPath: string;
}

export interface ReferenceManifest {
  catalogHash: string;
  generatedAt: string;
  lists: ManifestListEntry[];
  verifiableChannels: string[];
}

export interface ReferenceDocumentItem {
  itemCode: string;
  parentCode: string | null;
  labelAr: string;
  labelEn: string | null;
  searchAr: string | null;
  searchEn: string | null;
  sortOrdinal: number;
  isActive: boolean;
  extra: unknown;
}

export interface ReferenceDocument {
  listCode: string;
  version: number;
  itemCount: number;
  nameAr: string;
  nameEn: string;
  isHierarchical: boolean;
  rootItemCode: string | null;
  items: ReferenceDocumentItem[];
}

/** `operator.web.ChannelStateResponse`/`operator.domain.ChannelStateView`. */
export type MessageChannelWire = 'sms' | 'whatsapp' | 'email';
export type ChannelStateWire = 'verified' | 'declined' | 'unverified';

export interface ChannelStateResponse {
  channel: MessageChannelWire;
  state: ChannelStateWire;
  verifiedAt: string | null;
}

/** `operator.domain.IncomeSourceView`. */
export interface IncomeSourceView {
  sourceCode: string;
  isPrimary: boolean;
  otherText: string | null;
}

/**
 * `operator.domain.CustomerDataView`. Reference-coded fields (occupationCode, the *_state_code /
 * *_locality_code address fields, countries) are resolved to labels client-side -- see
 * `useReferenceLabelMap` in `api/reference.ts`. The `*_state_text`/`*_locality_text` siblings are
 * the non-Sudan free-text fallback (V0006/V0025) and are never reference-coded.
 */
export interface CustomerDataView {
  phoneNumber: string | null;
  emailAddress: string | null;
  sexDeclared: string | null;
  maritalStatus: string | null;
  spouseName: string | null;
  hasChildren: boolean | null;
  childrenCount: number | null;
  educationLevel: number | null;
  occupationCode: string | null;
  occupationVersion: number | null;
  monthlyExpensesSdg: number | null;
  identityType: string | null;
  ethnicity: string | null;
  countryOfResidenceCode: string | null;
  birthCountryCode: string | null;
  birthStateCode: string | null;
  birthStateText: string | null;
  birthCityText: string | null;
  homeCountryCode: string | null;
  homeStateCode: string | null;
  homeLocalityCode: string | null;
  homeStateText: string | null;
  homeLocalityText: string | null;
  homeCity: string | null;
  homeArea: string | null;
  homeStreet: string | null;
  homeBlock: string | null;
  homeHouseNo: string | null;
  employerName: string | null;
  workCountryCode: string | null;
  workStateCode: string | null;
  workLocalityCode: string | null;
  workStateText: string | null;
  workLocalityText: string | null;
  workCity: string | null;
  workArea: string | null;
  workStreet: string | null;
  workBlock: string | null;
  incomeSources: IncomeSourceView[];
}

/** `operator.domain.ScanResultView`. `null` until stage 8 completes. */
export interface ScanResultView {
  documentType: string;
  cardVariant: string | null;
  identityNumber: string | null;
  documentNumber: string | null;
  mrzVerified: boolean | null;
  nationality: string | null;
  sexOnDocument: string | null;
  dateOfBirth: string | null;
  dateOfIssue: string | null;
  dateOfExpiry: string | null;
  placeOfIssue: string | null;
  issuingCountry: string | null;
  nameArOnDocument: string | null;
  nameEnOnDocument: string | null;
  bloodType: string | null;
  birthCity: string | null;
  receivedAt: string;
}

/**
 * `operator.domain.FaceResultView` -- face-MATCH only. Liveness itself carries no score (AD-002a):
 * this record's mere presence (a JWS was issued) is the liveness signal, shown as its own section
 * in the UI, never folded into `passed`, which is the face-match accept/reject decision.
 */
export interface FaceResultView {
  match: boolean;
  matchLevel: number;
  thresholdApplied: number;
  passed: boolean;
  receivedAt: string;
}

/** `operator.domain.RegistryResultView`. `null` until the Civil Registry lookup succeeds. */
export interface RegistryResultView {
  state: string;
  nameArGiven: string | null;
  nameArFather: string | null;
  nameArGrandfather: string | null;
  nameArGreatGrandfather: string | null;
  nameArMother: string | null;
  nameArMotherFather: string | null;
  nameArMotherGrandfather: string | null;
  nameArMotherGreatGrandfather: string | null;
  firstNamesEn: string | null;
  lastNameEn: string | null;
  sexRegistry: string | null;
  dateOfBirth: string | null;
  rawAddressAr: string | null;
  /**
   * Field 7, «الرقم الوطني», as the REGISTRY returned it (V0062, BL-132) -- not the scan's copy.
   *
   * The two are provably equal on a successful lookup, because `HttpCivilRegistryClient` treats
   * any difference as `not_found` (AD-002b). Rendering `scanResult.identityNumber` under a
   * "from the Civil Registry" heading would nonetheless make that heading's provenance claim
   * true only by coincidence, and false the day the guard is relaxed -- which is the reason
   * this field exists at all. Section 1 of the profile screen reads it.
   */
  identityNumberReturned: string | null;
}

/**
 * `operator.domain.ArtifactRefView`. `label` is a system-origin tag the backend computes
 * ("Civil Registry" / "Uqudo — passport" / "Uqudo — national ID" / ...) rather than translatable
 * customer-facing copy. It is NO LONGER RENDERED on any screen: BL-075's contact sheet captions its
 * tiles in Arabic from `artifactTiles.ts` instead, and the metadata attachments table that used to
 * show `label` is gone with ticket 02's Variant B.
 *
 * Carries no image BYTES, and never will -- the bytes are fetched separately. BL-075's endpoint,
 * `GET /api/v1/operator/profiles/{profileId}/artifacts/{artifactId}`, is a same-origin
 * cookie-authenticated GET rendered straight into an `<img>` (R-046 is CLOSED: no signed URL, no
 * blob fetch), and `artifactRefId` below is what addresses it. `ArtifactContactSheet` calls it.
 *
 * This listing is NOT filtered to the kinds that endpoint will serve -- it still returns rows for
 * `doc_back` and both byte-less capture frames, which 404. See `profiles/artifactTiles.ts` for the
 * six that may be linked and why. (`salary_certificate` was a fourth refused row until S8-24, when
 * BL-136 admitted it; the listing's lack of a kind filter is unchanged either way.)
 */
export interface ArtifactRefView {
  /**
   * The artifact's own id, and the only way to address its bytes. Not merely informational: a
   * profile with several identity cycles has several committed rows of the same `kind`, so `kind`
   * alone cannot tell a superseded scan from the one that replaced it.
   */
  artifactRefId: string;
  kind: string;
  label: string;
  storageKey: string | null;
  contentType: string;
  byteSize: number;
  sha256Hex: string;
}

/** `operator.domain.StatusHistoryEntryView`. */
export interface StatusHistoryEntryView {
  seq: number;
  fromStatus: string | null;
  toStatus: string;
  occurredAt: string;
  actorKind: string;
  actorId: string | null;
  reasonCode: string | null;
  reasonLabelAr: string | null;
  reasonLabelEn: string | null;
  internalNote: string | null;
  /**
   * WRITE-NEVER, READ-STILL since AD-022 (S9-01). Manual completion is deleted, but
   * `app.profile_status_history.is_manual_completion` (V0009, V0067) is deliberately kept so
   * profiles completed before the ruling stay distinguishable in their own history. Always `false`
   * on anything recorded after 2026-09-16.
   */
  isManualCompletion: boolean;
}

/**
 * `operator.domain.SalaryCertificateState` (BL-122). Which of four things happened to the optional
 * salary certificate, resolved on the SERVER rather than inferred here from raw columns — the same
 * rule AD-002a and R-052 state for the mobile client.
 *
 * Before this existed, `DECLINED` and `ATTACH_FAILED` were the same absence: no artifact row either
 * way, so an operator could not tell a customer who chose not to attach one from a customer whose
 * upload never arrived.
 *
 * **Informative only.** Product-owner ruling, 2026-09-13: an operator does nothing differently
 * about any of these values. The certificate is optional and gates nothing, and `ATTACH_FAILED` is
 * not a rejection reason — it is context for the person reading the profile, nothing more.
 */
export type SalaryCertificateState = 'PRESENT' | 'ATTACH_FAILED' | 'DECLINED' | 'NOT_REACHED';

/**
 * `operator.web.ProfileDetailResponse`. Carried a server-computed `canApprove` (BL-013) until
 * AD-013 removed the four-eyes rule on 2026-09-13. The Approve button is now gated on the
 * operator's own role and the profile's status alone; the server remains the enforcement.
 */
export interface ProfileDetailResponse {
  profileId: string;
  referenceNumber: string;
  
  accountNumber: string;
  status: ProfileStatus;
  provenance: ProfileProvenance;
  submittedAt: string | null;
  createdAt: string;
  lastActivityAt: string;
  customerData: CustomerDataView;
  channels: ChannelStateResponse[];
  scanResult: ScanResultView | null;
  faceResult: FaceResultView | null;
  registryResult: RegistryResultView | null;
  artifacts: ArtifactRefView[];
  statusHistory: StatusHistoryEntryView[];
  salaryCertificateState: SalaryCertificateState;
  /**
   * Which fields this profile currently exposes for per-field editing (BL-135, AD-015, AD-021).
   * Carries `operator.domain.EditableField` NAMES -- the same strings the PATCH endpoint takes
   * as its `fieldKey` path variable, so the browser sends back exactly what it was given and no
   * second spelling can enter. See `profiles/editableFields.ts`.
   *
   * DERIVED PER PROFILE, never a fixed list: a non-Sudan address makes state and locality free
   * text while a Sudan one leaves them list-picked, so two profiles of the same status can
   * carry different sets. The server also STATUS-gates it -- anything but `submitted` or
   * `rejected` comes back empty.
   *
   * **Not role-gated, deliberately** (`OperatorProfileViewService`: "the set is identical for a
   * viewer and an operator, because it describes the PROFILE, not the caller"). A viewer
   * therefore receives a populated list, and the screen must gate the «تعديل» affordance on
   * `canOperate` itself or it would draw chips whose PATCH is refused with a 403.
   */
  editableFields: string[];
}

/** `operator.web.ReviewResponse` (approve/reject). */
export interface ReviewResponse {
  profileId: string;
  status: ProfileStatus;
  alreadyDone: boolean;
  notifiedChannels: MessageChannelWire[];
}

/**
 * `operator.web.RejectRequest`. `internalNote` is mandatory only for REJ-07 (server-enforced,
 * `RejectModal` mirrors the check client-side for a fast error).
 */
export interface RejectRequest {
  reasonCode: string;
  internalNote: string | null;
}

/**
 * `operator.web.FieldEditRequest`. One field, one value -- the field itself is the URL's path
 * variable, so a request can never carry a field key that disagrees with where it was sent.
 */
export interface FieldEditRequest {
  value: string;
}

/**
 * `operator.web.FieldEditResponse`.
 *
 * Deliberately NOT a whole refreshed profile: the client reloads after an edit so the server
 * re-derives `editableFields` and the provenance, and a write endpoint returning a full detail
 * would mint a second copy of the densest PII object in the system on every keystroke-sized
 * change.
 *
 * `value` is the value **as STORED, after trimming** -- not as submitted. A screen that renders
 * what the operator typed rather than this would show a value the database does not hold.
 */
export interface FieldEditResponse {
  fieldKey: string;
  value: string;
}
