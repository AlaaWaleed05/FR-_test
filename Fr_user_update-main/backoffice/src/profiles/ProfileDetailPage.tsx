import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import dayjs from 'dayjs';
// Without this plugin dayjs IGNORES a format argument entirely and falls back to guessing, so
// `formatDateOnly`'s explicit format below would be decorative. Measured on bare dayjs:
// `dayjs('09/11/1994', 'YYYY-MM-DD')` parses as VALID and renders `11/09/1994` -- day and month
// inverted, with `isValid()` true, so the raw-value fallback never fires and nothing shows the
// error. field-provenance.md field 21 asks for exactly this ("parse explicitly, never with a
// locale default").
//
// **THIS LINE IS CURRENTLY REDUNDANT, AND IS KEPT ANYWAY.** antd registers the same plugin on
// the shared dayjs singleton (measured: importing `antd/lib/date-picker` flips that same call
// from valid to invalid), so today the guard exists whether or not this file asks for it. That
// is an ACCIDENT of another library's import graph, and AD-021 is in the business of removing
// antd from this screen -- the day the last antd import goes, so does the guard, silently, and
// the symptom is a birth date with its day and month swapped on a bank record. Declaring it here
// makes the behaviour this file's own.
//
// The consequence for testing, stated rather than left to be discovered: the regression test in
// ProfileDetailPage.test.tsx cannot be proved by reverting this line while antd is still in the
// bundle. It passes either way today.
import customParseFormat from 'dayjs/plugin/customParseFormat';

dayjs.extend(customParseFormat);
import { approveProfile, editProfileField, getProfile, printProfileForm, rejectProfile } from '../api/profiles';
import type { PrintRequest } from '../api/profiles';
import { useAlpha3LabelMap, useReferenceLabelMap } from '../api/reference';
import { ApiError } from '../api/http';
import { useAuth } from '../auth/AuthContext';
import { canOperate } from '../auth/capabilities';
import type { ProfileDetailResponse, ProfileStatus, RegistryResultView } from '../api/types';
import { PROVENANCE_LABELS_AR, STATUS_LABELS_AR } from './statusLabels';
import { ACTOR_KIND_LABELS_AR, documentTypeLabel, isFemale, maritalStatusLabel, sexLabel } from './detailLabels';
import RejectModal from './RejectModal';
import PrintFormModal from './PrintFormModal';
import ArtifactContactSheet from './ArtifactContactSheet';
import { VIEWABLE_KINDS } from './artifactTiles';
import { isEditableFieldKey } from './editableFields';
import type { EditableFieldKey } from './editableFields';
import FieldRow from './detail/FieldRow';
import {
  Badge,
  FieldGrid,
  Num,
  ProfileHeader,
  ProfileIdentityCard,
  ScreenShell,
  SectionCard,
  SubSection,
} from './detail/chrome';
import { INK, PALETTE } from '../theme/palette';

/**
 * The single profile view, REBUILT AT S9-02 to
 * `Design_3/backoffice/approved/profile-screen.dc.html` (AD-021, AD-022).
 *
 * Three sections replace the old nine `Descriptions` blocks, and the split is the point rather
 * than the styling: section 1 is the Civil Registry, section 2 is the identity document and the
 * liveness check, section 3 is what the customer typed — and only section 3 carries any «تعديل»
 * affordance. An operator can tell what they may touch by which part of the screen it is in.
 *
 * **antd is dropped in this file.** `RejectModal`, `PrintFormModal` and `ArtifactContactSheet`'s
 * image preview keep it — they are separate components, and what AD-021 displaces is the CHROME
 * (Descriptions, Card, Layout, Divider, Tag, Timeline), not a dialog or a lightbox with no bare
 * equivalent.
 *
 * **This screen sits OUTSIDE `AppShell`** (product-owner ruling, 2026-09-16) so the artboard's
 * own header is the only header. Two consequences the next reader should not have to rediscover:
 * sign-out reaches this screen through `layout/BrandHeader` — shared with the shell since S9-06,
 * but rendered here directly rather than by any layout — and «رجوع إلى القائمة» below is still
 * the ONLY way back to the list, the sider's «الملفات» having gone with the shell.
 *
 * Deviations from the artboard, all deliberate and all recorded:
 *
 * - **Six artifact tiles, not seven** (BL-159). `doc_back` is refused server-side by
 *   `OperatorImagePolicy.viewableKinds()`, so a seventh tile would render a broken image.
 * - **No queue position** in the header (BL-158). No backend concept exists.
 * - **The status history is KEPT**, below section 3, though the artboard draws none. Nothing in
 *   AD-021, AD-022 or `docs/backoffice-redesign.md` removes it and `operator.md` still requires
 *   it ("The back office shows the full status history of a profile, not only where it stands").
 *   Deleting it because an artboard omitted it would be a silent removal of a specified feature.
 * - **Fields 25 and 26 render only when their channel is VERIFIED** (AD-022 ruling 4). The
 *   artboard draws them unconditionally; `field-provenance.md` and the redesign brief both say
 *   otherwise, and they are the later and more specific authority.
 *
 * Four things the OLD screen showed that the artboard does not, and where each went:
 *
 * - **`submittedAt`, `createdAt`, `lastActivityAt`.** The artboard's identity card carries no
 *   timestamps. `submittedAt` survives in substance, because the status history below renders
 *   every transition with its time and the move into `submitted` is one of them. `createdAt` and
 *   `lastActivityAt` genuinely go; neither gates an operator decision, and the list screen still
 *   sorts and filters on them.
 * - **`customerData.identityType`** (field 43's customer-declared half). The screen now shows
 *   only the SCAN's document type. `field-provenance.md` calls a disagreement between the two "an
 *   operator signal", and nothing server-side cross-checks them — so that signal is no longer
 *   observable anywhere. Filed as BL-160 rather than reinstated, because the artboard's section 2
 *   is specified as the document's own data and a customer-declared value does not belong in it.
 * - **`registryResult.rawAddressAr`.** Specified away: `docs/backoffice-redesign.md` §3 says the
 *   registry's own address is not shown.
 * - **The scan fields outside 43-48** — `cardVariant`, `bloodType`, `sexOnDocument`,
 *   `nameArOnDocument`, `nameEnOnDocument`, `identityNumber`, `receivedAt`. Section 2's field list
 *   is exactly 43-48 by the same specification.
 */

// Read from the actual backend service (operator.service.OperatorProfileViewService), not guessed.
const APPROVE_ELIGIBLE = new Set<ProfileStatus>(['submitted', 'rejected']);
const REJECT_ELIGIBLE = new Set<ProfileStatus>(['submitted']);
/** Only `submitted` and `approved` may be printed (wayfinder ticket 05 decision 8). */
const PRINT_ELIGIBLE = new Set<ProfileStatus>(['submitted', 'approved']);

const NOTIFIED_AFTER_ACTION = 'سيصل إشعار للعميل عبر القنوات التي تم التحقق منها.';

const DASH = '—';

/** `DD/MM/YYYY HH:mm` for an Instant. NEVER ISO on an operator screen (D4.2). */
function formatInstant(value: string | null): React.ReactNode {
  return value ? <Num>{dayjs(value).format('DD/MM/YYYY HH:mm')}</Num> : DASH;
}

/**
 * `DD/MM/YYYY` for a wire `LocalDate` — `"1994-11-09"`.
 *
 * Separate from {@link formatInstant} and not a nicety. The registry's date of birth and the
 * scan's issue/expiry dates are Java `LocalDate`s, so the old `formatDate` would have rendered
 * them `09/11/1994 00:00` — a midnight that is not in the data and that an operator would read
 * as a time the bank recorded. `orDashLtr`, the other old helper, left them as raw ISO.
 *
 * Parsed with an explicit format rather than letting dayjs guess -- see the `customParseFormat`
 * import, without which the format argument does nothing at all. The value reaching THIS tier has
 * already been normalised to ISO by the backend, and pinning the format keeps it that way: a
 * `DD/MM/YYYY` value arriving here is REFUSED and shown raw rather than silently read month-first.
 */
function formatDateOnly(value: string | null | undefined): React.ReactNode {
  if (!value) return DASH;
  const parsed = dayjs(value, 'YYYY-MM-DD');
  return parsed.isValid() ? <Num>{parsed.format('DD/MM/YYYY')}</Num> : <Num>{value}</Num>;
}

function orDash(value: string | number | null | undefined): React.ReactNode {
  if (value === null || value === undefined || value === '') return DASH;
  return String(value);
}

/** Latin-script or numeric values, isolated from bidi reordering. */
function orDashNum(value: string | number | null | undefined): React.ReactNode {
  if (value === null || value === undefined || value === '') return DASH;
  return <Num>{String(value)}</Num>;
}

function resolveLabel(map: Map<string, string>, code: string | null | undefined): string | null {
  if (!code) return null;
  return map.get(code) ?? code;
}

/**
 * Joins the registry's name PARTS into the single line the artboard draws.
 *
 * The registry returns four paternal parts (given, father, grandfather, great-grandfather) and
 * four maternal ones; `field-provenance.md` fields 5 and 8 record that both chains become profile
 * fields. The artboard shows one «الاسم الكامل بالعربي» and one «اسم الأم», so they are joined
 * with a space — the ordinary way an Arabic full name is written — and absent parts are simply
 * skipped rather than leaving a double space.
 */
function joinNameParts(...parts: (string | null | undefined)[]): string {
  const present = parts.filter((p): p is string => !!p && p.trim() !== '');
  return present.length > 0 ? present.join(' ') : '';
}

function registryArabicName(registry: RegistryResultView | null): string {
  if (!registry) return '';
  return joinNameParts(
    registry.nameArGiven,
    registry.nameArFather,
    registry.nameArGrandfather,
    registry.nameArGreatGrandfather,
  );
}

function registryMotherName(registry: RegistryResultView | null): string {
  if (!registry) return '';
  return joinNameParts(
    registry.nameArMother,
    registry.nameArMotherFather,
    registry.nameArMotherGrandfather,
    registry.nameArMotherGreatGrandfather,
  );
}

function apiErrorMessage(err: unknown, fallback: string): string {
  return err instanceof ApiError ? `${fallback} (${err.status})` : fallback;
}

/** What the screen is currently telling the operator. Replaces antd's `message` singleton. */
interface Notice {
  kind: 'success' | 'error' | 'warning';
  text: string;
}

export default function ProfileDetailPage(): React.JSX.Element {
  const { profileId } = useParams<{ profileId: string }>();
  const navigate = useNavigate();
  const { state } = useAuth();
  // AD-013 (BL-139): operator OR admin, not operator alone -- an admin holds every operator
  // power. These are presentation guards; the backend authorises each action again on the
  // request itself, so a hidden button is a courtesy and never the control.
  const mayAct = state.status === 'authenticated' && canOperate(state.role);

  const [detail, setDetail] = useState<ProfileDetailResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState(false);
  const [rejectOpen, setRejectOpen] = useState(false);
  const [printOpen, setPrintOpen] = useState(false);
  const [printing, setPrinting] = useState(false);
  /**
   * The screen's own notification, rendered INSIDE the React tree.
   *
   * Replaces antd's `message` singleton, which mounted toasts into a container appended to
   * `<body>` — outside the tree, therefore outside what RTL's `cleanup()` unmounts, which is the
   * whole of BL-156. A notice held in component state cannot leak into another test, and cannot
   * outlive the screen that raised it.
   */
  const [notice, setNotice] = useState<Notice | null>(null);

  
  const occupation = useReferenceLabelMap('occupation');
  const adminDivision = useReferenceLabelMap('admin_division');
  const country = useReferenceLabelMap('country');
  const incomeSource = useReferenceLabelMap('income_source');
  const educationLevel = useReferenceLabelMap('education_level');
  // Fields 4 and 48 are MRZ alpha-3 codes; every other country field is alpha-2. Same list, a
  // second index over its `extra.alpha3` -- see `useAlpha3LabelMap` on why this closed BL-157.
  const alpha3 = useAlpha3LabelMap();
  const referenceLists = [occupation, adminDivision, country, incomeSource, educationLevel, alpha3];
  const referenceListsError = referenceLists.some((list) => list.error);
  const retryReferenceLists = () => referenceLists.forEach((list) => list.retry());

  const load = useCallback(() => {
    if (!profileId) return;
    setLoading(true);
    setError(null);
    getProfile(profileId)
      .then(setDetail)
      .catch((err: unknown) => setError(apiErrorMessage(err, 'تعذر تحميل الملف')))
      .finally(() => setLoading(false));
  }, [profileId]);

  useEffect(() => {
    load();
  }, [load]);

  /**
   * AD-022 ruling 4. Derived from the wire's own `state` rather than from `verifiedAt`: the two
   * are separate columns (V0007), so filtering on the timestamp would be inferring the state from
   * a proxy.
   */
  const verifiedChannels = useMemo(
    () => new Set((detail?.channels ?? []).filter((c) => c.state === 'verified').map((c) => c.channel)),
    [detail],
  );

  /**
   * The keys this screen will actually draw a chip for.
   *
   * Two filters over what the server sent, and both matter:
   *
   * - **`canOperate`.** `editableFields` is status-gated server-side but DELIBERATELY not
   *   role-gated — `OperatorProfileViewService` says so in as many words, because the set
   *   describes the profile rather than the caller. A viewer therefore receives a populated
   *   list, and without this every chip they pressed would 403.
   * - **`isEditableFieldKey`.** A key this client does not know has no row to attach to, so it
   *   is dropped rather than guessed at.
   */
  const editableKeys = useMemo(() => {
    if (!detail || !mayAct) return new Set<EditableFieldKey>();
    return new Set(detail.editableFields.filter(isEditableFieldKey));
  }, [detail, mayAct]);

  /**
   * Stores one field, then RELOADS.
   *
   * The reload is not laziness about patching local state. An edit changes the profile's derived
   * provenance (`app.derived_provenance` resolves "an operator has keyed at least one field"), and
   * in principle what else is editable; both are derived on the READ path, so the only way to
   * show the truth is to ask for it. `FieldEditResponse` deliberately carries just the stored
   * value rather than a whole refreshed profile.
   */
  const handleFieldSave = useCallback(
    async (fieldKey: string, value: string) => {
      if (!profileId) return;
      try {
        await editProfileField(profileId, fieldKey, value);
      } catch (err) {
        const status = err instanceof ApiError ? err.status : 0;
        if (status === 409) {
          // The server re-derives editability on the write path, so a 409 means the chip was
          // stale -- the profile was approved, or the field stopped being editable, since this
          // screen loaded. Reload so the operator sees the truth rather than a screen still
          // offering the edit that was just refused.
          load();
          throw new Error('لم يعد هذا الحقل قابلاً للتعديل — أُعيد تحميل الملف.');
        }
        if (status === 403) {
          throw new Error('لا تملك صلاحية تعديل الحقول.');
        }
        if (status === 400) {
          throw new Error('القيمة غير مقبولة.');
        }
        throw new Error('تعذر حفظ التعديل.');
      }
      setNotice({ kind: 'success', text: 'حُفظ التعديل.' });
      load();
    },
    [profileId, load],
  );

  /** The props a FieldRow needs to offer editing, or nothing at all if this field is read-only. */
  const editProps = useCallback(
    (key: EditableFieldKey, currentValue: string | null | undefined) =>
      editableKeys.has(key)
        ? { editKey: key, editValue: currentValue ?? '', onSave: handleFieldSave }
        : {},
    [editableKeys, handleFieldSave],
  );

  const handleApprove = () => {
    if (!profileId) return;
    setActionLoading(true);
    approveProfile(profileId)
      .then((res) => {
        setNotice({
          kind: 'success',
          text: res.alreadyDone ? 'الملف معتمد بالفعل.' : `تم اعتماد الملف. ${NOTIFIED_AFTER_ACTION}`,
        });
        load();
      })
      .catch((err: unknown) => setNotice({ kind: 'error', text: apiErrorMessage(err, 'تعذر اعتماد الملف') }))
      .finally(() => setActionLoading(false));
  };

  const handleReject = (reasonCode: string, internalNote: string | null) => {
    if (!profileId) return;
    setActionLoading(true);
    rejectProfile(profileId, { reasonCode, internalNote })
      .then((res) => {
        setRejectOpen(false);
        setNotice({
          kind: 'success',
          text: res.alreadyDone ? 'الملف مرفوض بالفعل.' : `تم رفض الملف. ${NOTIFIED_AFTER_ACTION}`,
        });
        load();
      })
      .catch((err: unknown) => setNotice({ kind: 'error', text: apiErrorMessage(err, 'تعذر رفض الملف') }))
      .finally(() => setActionLoading(false));
  };

  /**
   * Prints, then opens what came back.
   *
   * The response IS the document — one render, stored server-side in the same transaction and
   * streamed here, so what the operator opens is byte-identical to what the bank filed. Shown in
   * a new tab rather than downloaded: `Content-Disposition: inline` is deliberate server-side,
   * because an `attachment` would leave an unencrypted copy of the densest PII object in the
   * system in an operator's downloads folder on every print.
   *
   * **NO `noopener` IN THE FEATURE STRING, and that is load-bearing.** The HTML standard's
   * window-open steps end with "if noopener is true, then return null" — the return value is null
   * whenever the token is present, whether or not the tab opened. An earlier version passed it and
   * read the result to detect a popup blocker, so EVERY successful print took the blocked branch,
   * minting another stored copy of the customer's whole record per attempt. The opener is severed
   * on the handle instead; the target is a same-origin `blob:` URL in any case.
   */
  const handlePrint = async (request: PrintRequest) => {
    if (!profileId) return;
    setPrinting(true);
    try {
      const printed = await printProfileForm(profileId, request);
      const url = URL.createObjectURL(printed.blob);
      const opened = window.open(url, '_blank');
      if (opened) {
        opened.opener = null;
        // A minute, not immediately: revoking now races the tab that was just opened. Never
        // revoking holds the whole PDF in this page's memory until a reload.
        window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
        setNotice({ kind: 'success', text: 'تمت الطباعة وحُفظت نسخة في سجل الملف' });
      } else {
        URL.revokeObjectURL(url);
        setNotice({
          kind: 'warning',
          text: 'تمت الطباعة وحُفظت نسخة، لكن المتصفح منع فتح النافذة — اسمح بالنوافذ المنبثقة ثم أعد المحاولة',
        });
      }
      setPrintOpen(false);
    } catch (err) {
      const status = err instanceof ApiError ? err.status : 0;
      if (status === 403) {
        // 403 is also what a stale CSRF token produces, with no body to tell them apart. Worded
        // so it covers both rather than telling an operator with a timed-out session that they
        // lack a permission they have.
        setNotice({ kind: 'error', text: 'تعذّرت الطباعة — تحقّق من صلاحيتك، وإذا طالت الجلسة فسجّل الخروج والدخول ثم أعد المحاولة' });
      } else if (status === 409) {
        setNotice({ kind: 'error', text: 'لا يمكن طباعة ملف بهذه الحالة — الطباعة متاحة للملفات المقدَّمة والمعتمدة فقط' });
      } else if (status === 400 || status === 404) {
        setNotice({ kind: 'error', text: 'تعذّرت الطباعة — لم تُحفَظ أي نسخة ولم يُسلَّم أي ملف' });
      } else {
        // Everything else -- a dropped connection, a gateway timeout, a 500 -- and the honest
        // answer is that this tier CANNOT KNOW whether a copy was stored. The server stores the
        // artifact and writes the audit event BEFORE it streams the bytes (ticket 05 decision 5),
        // so a failure after that point leaves a print on the record that never reached anyone.
        setNotice({ kind: 'error', text: 'تعذّرت الطباعة — قد تكون نسخة قد حُفظت في سجل الملف، فراجع السجل قبل إعادة المحاولة' });
      }
    } finally {
      setPrinting(false);
    }
  };

  if (!profileId) {
    return (
      <ScreenShell>
        <Banner kind="error" text="معرّف الملف غير صالح" />
      </ScreenShell>
    );
  }
  if (loading) {
    return (
      <ScreenShell>
        <div data-testid="profile-loading" style={{ padding: 48, textAlign: 'center', color: INK.MUTED }}>
          جارٍ التحميل…
        </div>
      </ScreenShell>
    );
  }
  if (error) {
    return (
      <ScreenShell>
        <Banner kind="error" text={error} action={<PlainButton onClick={load}>إعادة المحاولة</PlainButton>} />
      </ScreenShell>
    );
  }
  if (!detail) return <ScreenShell>{null}</ScreenShell>;

  const cd = detail.customerData;
  const scan = detail.scanResult;
  const registry = detail.registryResult;

  // Which sex the two agreeing rows in section 3 follow -- field 12's state and fields 13/14's
  // spouse label. Derived ONCE so they cannot disagree with each other, and by the same rule the
  // printed form uses (registry first, then declared). See `isFemale`; this is BL-161.
  const female = isFemale(registry?.sexRegistry ?? null, cd.sexDeclared);

  /**
   * Whether the attachments opt-in is offered at all. The attachments are exactly the six kinds
   * the contact sheet renders, so `VIEWABLE_KINDS` is the list rather than a second copy of it.
   */
  const hasAttachments = detail.artifacts.some((artifact) =>
    (VIEWABLE_KINDS as readonly string[]).includes(artifact.kind),
  );

  /**
   * The name on the identity card.
   *
   * `ProfileDetailResponse` carries no display name — `displayNameAr` exists only on the LIST
   * response — so it is assembled from the registry's own parts, falling back to the English
   * pair and then to a dash. Not carried as router state from the list, which would be wrong the
   * moment the profile is opened directly or the list is re-sorted.
   */
  const customerName =
    registryArabicName(registry) ||
    joinNameParts(registry?.firstNamesEn, registry?.lastNameEn) ||
    DASH;

  /**
   * Field 23, «مدينة الميلاد» — the scan SUPERSEDES the customer's text.
   *
   * `customer.md` Stage 3: "Uqudo's value supersedes theirs when the scan lands", and
   * `EditableFieldPolicy` opens this field for editing only where the scan supplied nothing. The
   * display has to agree: rendering `birthCityText` alone would show a value the profile does not
   * mean, and an edit would write a column nothing displays.
   */
  const birthCityDisplayed = scan?.birthCity ?? cd.birthCityText;

  const incomeSourceText =
    cd.incomeSources.length === 0
      ? DASH
      : cd.incomeSources
          .map((s) => {
            const label = resolveLabel(incomeSource.map, s.sourceCode) ?? s.sourceCode;
            const primary = s.isPrimary ? ' — أساسي' : '';
            const other = s.otherText ? ` · ${s.otherText}` : '';
            return `${label}${primary}${other}`;
          })
          .join(' · ');

  /**
   * The `OTHER` row's free text, which is the only part of field 20 an operator may key.
   *
   * Found BY ITS CODE, not by "the first row that happens to carry text". V0006's
   * `other_needs_text` CHECK is one-directional -- it requires text ON the OTHER row and forbids
   * nothing on the others -- so another row carrying text is possible, and seeding from it would
   * put that value in the editor and then write it into the OTHER row on save. `DataEntryService`
   * filters on the code for the same reason.
   */
  const incomeOtherText = cd.incomeSources.find((s) => s.sourceCode === 'OTHER')?.otherText ?? '';

  return (
    <ScreenShell>
      <ProfileHeader />
      <ProfileIdentityCard
        referenceNumber={detail.referenceNumber}
        customerName={customerName}
        badges={
          <>
            <Badge>
              الحساب <Num>{detail.accountNumber}</Num>
            </Badge>
            
            <Badge background={detail.provenance === 'manual' ? PALETTE.PURPLE : PALETTE.SUCCESS} color="#fff">
              {PROVENANCE_LABELS_AR[detail.provenance]}
            </Badge>
            <Badge background={PALETTE.PURPLE} color="#fff">
              {STATUS_LABELS_AR[detail.status]}
            </Badge>
          </>
        }
      />

      <div style={{ margin: '0 20px', paddingBottom: 24 }}>
        {notice && (
          <div style={{ marginBottom: 14 }}>
            <Banner kind={notice.kind} text={notice.text} action={<PlainButton onClick={() => setNotice(null)}>إغلاق</PlainButton>} />
          </div>
        )}

        {referenceListsError && (
          <div style={{ marginBottom: 14 }}>
            <Banner
              kind="warning"
              text="تعذر تحميل بعض قوائم البيانات المرجعية — قد تظهر بعض الحقول كرمز بدل الاسم. البيانات نفسها سليمة."
              action={<PlainButton onClick={retryReferenceLists}>إعادة المحاولة</PlainButton>}
            />
          </div>
        )}

        {detail.provenance === 'manual' && (
          <div style={{ marginBottom: 14 }}>
            <Banner
              kind="warning"
              text="ملف يدوي — أُدخل أو عُدِّل أحد حقوله من قبل مشغّل."
            />
          </div>
        )}

        {/* ---- Section 1: the Civil Registry, wholly read-only ------------------------------ */}
        <SectionCard heading="١ — بيانات الهوية" note="من السجل المدني — غير قابلة للتعديل">
          <FieldGrid>
            <FieldRow fieldNumber={5} label="الاسم الكامل بالعربي">
              {orDash(registryArabicName(registry))}
            </FieldRow>
            <FieldRow fieldNumber={8} label="اسم الأم">
              {orDash(registryMotherName(registry))}
            </FieldRow>
            <FieldRow fieldNumber={6} label="الاسم الكامل بالانجليزي">
              {orDashNum(joinNameParts(registry?.firstNamesEn, registry?.lastNameEn))}
            </FieldRow>
            <FieldRow fieldNumber={9} label="النوع">
              {sexLabel(registry?.sexRegistry ?? null)}
            </FieldRow>
            {/* Field 7 from the REGISTRY's own returned value (V0062, BL-132), never from the
                scan's copy -- the two are equal on a successful lookup, but printing the scan's
                under a "from the Civil Registry" heading would make that claim true only by
                coincidence. */}
            <FieldRow fieldNumber={7} label="الرقم الوطني">
              {orDashNum(registry?.identityNumberReturned)}
            </FieldRow>
            <FieldRow fieldNumber={21} label="تاريخ الميلاد">
              {formatDateOnly(registry?.dateOfBirth)}
            </FieldRow>
          </FieldGrid>
        </SectionCard>

        {/* ---- Section 2: the document and the liveness check, read-only -------------------- */}
        <SectionCard heading="٢ — التحقق من الهوية" note="من وثيقة الهوية والتحقق الحي — غير قابلة للتعديل">
          <ArtifactContactSheet
            profileId={detail.profileId}
            artifacts={detail.artifacts}
            salaryCertificateState={detail.salaryCertificateState}
            documentType={scan?.documentType ?? null}
          />

          {/*
            The two verification lines, and the ONLY two AD-022 ruling 3 permits.

            Both are derived from PRESENCE, never from a score. `faceResult`'s values -- match,
            matchLevel, thresholdApplied, passed -- must never be rendered; what is read here is
            only that a face JWS arrived at all, which is the one signal saying the customer got
            through Stage 10. The artboard draws «ناجح» flat, which would assert a liveness pass
            on a profile that never reached the stage; there are three states, not one.
          */}
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, padding: '12px 16px', borderBottom: `1px solid ${INK.ROW_RULE}` }}>
            <VerificationChip
              text={
                detail.status === 'blocked_liveness'
                  ? 'التحقق الحي — فشل'
                  : detail.faceResult
                    ? 'التحقق الحي — ناجح'
                    : 'التحقق الحي — لم يتم بعد'
              }
              tone={detail.status === 'blocked_liveness' ? 'bad' : detail.faceResult ? 'good' : 'neutral'}
            />
            <VerificationChip
              text={
                scan?.mrzVerified === null || scan === null
                  ? 'تحقق MRZ — لم يتم بعد'
                  : scan.mrzVerified
                    ? 'تحقق MRZ — صحيح'
                    : 'تحقق MRZ — غير صحيح'
              }
              tone={scan?.mrzVerified === true ? 'neutral' : scan?.mrzVerified === false ? 'bad' : 'neutral'}
            />
          </div>

          <FieldGrid>
            <FieldRow fieldNumber={43} label="نوع الهوية">
              {documentTypeLabel(scan?.documentType ?? null)}
            </FieldRow>
            <FieldRow fieldNumber={46} label="مكان الإصدار">
              {orDash(scan?.placeOfIssue)}
            </FieldRow>
            <FieldRow fieldNumber={44} label="رقم الهوية">
              {orDashNum(scan?.documentNumber)}
            </FieldRow>
            <FieldRow fieldNumber={47} label="تاريخ الصلاحية">
              {formatDateOnly(scan?.dateOfExpiry)}
            </FieldRow>
            <FieldRow fieldNumber={45} label="تاريخ الإصدار">
              {formatDateOnly(scan?.dateOfIssue)}
            </FieldRow>
            {/* Field 48 is an MRZ alpha-3, like field 4 -- resolved through the country list's
                own `extra.alpha3`, falling back to the raw code for an ICAO value with no ISO
                row (XXA, GBD, RKS). See `useAlpha3LabelMap`; this is what closed BL-157. */}
            <FieldRow fieldNumber={48} label="بلد الإصدار">
              {scan?.issuingCountry ? (alpha3.map.get(scan.issuingCountry.toUpperCase()) ?? <Num>{scan.issuingCountry}</Num>) : DASH}
            </FieldRow>
          </FieldGrid>
        </SectionCard>

        {/* ---- Section 3: what the customer submitted -------------------------------------- */}
        <SectionCard heading="٣ — البيانات المُقدَّمة من العميل" note="نص حر قابل للتعديل">
          <SubSection heading="الحساب والفرع">
            <FieldGrid>
              <FieldRow fieldNumber={3} label="رقم الحساب البنكي">
                {orDashNum(detail.accountNumber)}
              </FieldRow>
              
            </FieldGrid>
          </SubSection>

          {/*
            AD-022 ruling 4: fields 25 and 26 render ONLY where the channel is VERIFIED. A
            declined or unverified channel is absent -- not greyed, not tagged, absent.

            The PHONE backs TWO channels: a customer may verify by SMS, by WhatsApp, or both, and
            either proves they hold the number. The email backs one.
          */}
          <SubSection heading="قنوات الاتصال">
            <FieldGrid>
              {(verifiedChannels.has('sms') || verifiedChannels.has('whatsapp')) && (
                <FieldRow fieldNumber={25} label="التلفون">
                  {orDashNum(cd.phoneNumber)}
                </FieldRow>
              )}
              {verifiedChannels.has('email') && (
                <FieldRow fieldNumber={26} label="البريد الالكتروني">
                  {orDashNum(cd.emailAddress)}
                </FieldRow>
              )}
            </FieldGrid>
            {verifiedChannels.size === 0 && (
              // Reachable: profiles completed manually before AD-022 reached `submitted` with no
              // channel verification at all.
              <div style={{ padding: '9px 16px', fontSize: 13, color: INK.MUTED, borderBottom: `1px solid ${INK.ROW_RULE}` }}>
                لا توجد قنوات اتصال موثّقة لهذا الملف.
              </div>
            )}
          </SubSection>

          <SubSection heading="البيانات الشخصية والاجتماعية">
            <FieldGrid>
              {/* Field 4 is the MRZ nationality, an alpha-3 -- see field 48 above. */}
              <FieldRow fieldNumber={4} label="الجنسية">
                {scan?.nationality ? (alpha3.map.get(scan.nationality.toUpperCase()) ?? <Num>{scan.nationality}</Num>) : DASH}
              </FieldRow>
              {/* Field 10, «الجنس» -- the bank's label for ETHNICITY, not sex. */}
              <FieldRow fieldNumber={10} label="الجنس" {...editProps('ETHNICITY', cd.ethnicity)}>
                {orDash(cd.ethnicity)}
              </FieldRow>
              {/* Fields 16 and 19 are digits, not free text, and are NOT editable -- BL-152,
                  product-owner ruling 2026-09-16. The artboard draws no chip on either. */}
              <FieldRow fieldNumber={16} label="عدد الأطفال">
                {orDashNum(cd.childrenCount)}
              </FieldRow>
              <FieldRow fieldNumber={11} label="المواطنة">
                {orDash(resolveLabel(country.map, cd.countryOfResidenceCode))}
              </FieldRow>
              <FieldRow fieldNumber={17} label="مستوي التعليم">
                {cd.educationLevel === null ? DASH : orDash(resolveLabel(educationLevel.map, String(cd.educationLevel)))}
              </FieldRow>
              <FieldRow fieldNumber={12} label="الحالة الاجتماعية">
                {maritalStatusLabel(cd.maritalStatus, female)}
              </FieldRow>
              <FieldRow fieldNumber={22} label="بلد الميلاد">
                {orDash(resolveLabel(country.map, cd.birthCountryCode))}
              </FieldRow>
              {/*
                Fields 13 and 14 are ONE column, `spouse_name`; which label renders is a function
                of the customer's own sex, because a woman's spouse is a husband. Field 14
                «اسم الزوجة» was missing from the redesign brief's table entirely -- the artboard's
                fixture is a married woman, so the gap was invisible there.
              */}
              <FieldRow
                fieldNumber={female ? 13 : 14}
                label={female ? 'اسم الزوج' : 'اسم الزوجة'}
                {...editProps('SPOUSE_NAME', cd.spouseName)}
              >
                {orDash(cd.spouseName)}
              </FieldRow>
              {/* Field 24 is list-picked when the birth country is Sudan and free text otherwise;
                  the server decides which by sending the key or not. */}
              <FieldRow
                fieldNumber={24}
                label="ولاية الميلاد"
                {...editProps('BIRTH_STATE_TEXT', cd.birthStateText)}
              >
                {orDash(resolveLabel(adminDivision.map, cd.birthStateCode) ?? cd.birthStateText)}
              </FieldRow>
              <FieldRow fieldNumber={15} label="له أطفال">
                {cd.hasChildren === null ? DASH : cd.hasChildren ? 'نعم' : 'لا'}
              </FieldRow>
              <FieldRow fieldNumber={23} label="مدينة الميلاد" {...editProps('BIRTH_CITY', cd.birthCityText)}>
                {orDash(birthCityDisplayed)}
              </FieldRow>
            </FieldGrid>
          </SubSection>

          <SubSection heading="المهنة والدخل">
            <FieldGrid>
              <FieldRow fieldNumber={18} label="المهنة">
                {orDash(resolveLabel(occupation.map, cd.occupationCode))}
              </FieldRow>
              {/*
                Field 20's chip reaches ONLY the «أخرى» free text (product-owner ruling). The
                codes and the primary flag are a list-picked multi-select and AD-021 forbids
                editing those -- it is what filtering and export are built on. The row therefore
                DISPLAYS the whole selection and EDITS only `other_text`; the server sends the key
                only when `OTHER` is among the codes.
              */}
              <FieldRow fieldNumber={20} label="مصدر الدخل" {...editProps('INCOME_OTHER_TEXT', incomeOtherText)}>
                {incomeSourceText}
              </FieldRow>
              <FieldRow fieldNumber={19} label="النفقات الشهرية">
                {cd.monthlyExpensesSdg === null ? DASH : <><Num>{cd.monthlyExpensesSdg}</Num> ج.س</>}
              </FieldRow>
            </FieldGrid>
          </SubSection>

          <SubSection heading="عنوان السكن">
            <FieldGrid>
              <FieldRow fieldNumber={35} label="البلد">
                {orDash(resolveLabel(country.map, cd.homeCountryCode))}
              </FieldRow>
              <FieldRow fieldNumber={36} label="الولاية" {...editProps('HOME_STATE_TEXT', cd.homeStateText)}>
                {orDash(resolveLabel(adminDivision.map, cd.homeStateCode) ?? cd.homeStateText)}
              </FieldRow>
              <FieldRow fieldNumber={37} label="المحافظة" {...editProps('HOME_LOCALITY_TEXT', cd.homeLocalityText)}>
                {orDash(resolveLabel(adminDivision.map, cd.homeLocalityCode) ?? cd.homeLocalityText)}
              </FieldRow>
              <FieldRow fieldNumber={38} label="المنطقة" {...editProps('HOME_AREA', cd.homeArea)}>
                {orDash(cd.homeArea)}
              </FieldRow>
              <FieldRow fieldNumber={39} label="المدينة" {...editProps('HOME_CITY', cd.homeCity)}>
                {orDash(cd.homeCity)}
              </FieldRow>
              <FieldRow fieldNumber={40} label="الشارع" {...editProps('HOME_STREET', cd.homeStreet)}>
                {orDash(cd.homeStreet)}
              </FieldRow>
              <FieldRow fieldNumber={41} label="المربع" {...editProps('HOME_BLOCK', cd.homeBlock)}>
                {orDash(cd.homeBlock)}
              </FieldRow>
              <FieldRow fieldNumber={42} label="رقم المنزل" {...editProps('HOME_HOUSE_NO', cd.homeHouseNo)}>
                {orDash(cd.homeHouseNo)}
              </FieldRow>
            </FieldGrid>
          </SubSection>

          <SubSection heading="جهة العمل عنوانه">
            <FieldGrid>
              <FieldRow fieldNumber={27} label="جهة العمل" {...editProps('EMPLOYER_NAME', cd.employerName)}>
                {orDash(cd.employerName)}
              </FieldRow>
              <FieldRow fieldNumber={28} label="البلد">
                {orDash(resolveLabel(country.map, cd.workCountryCode))}
              </FieldRow>
              <FieldRow fieldNumber={29} label="الولاية" {...editProps('WORK_STATE_TEXT', cd.workStateText)}>
                {orDash(resolveLabel(adminDivision.map, cd.workStateCode) ?? cd.workStateText)}
              </FieldRow>
              <FieldRow fieldNumber={30} label="المحافظة" {...editProps('WORK_LOCALITY_TEXT', cd.workLocalityText)}>
                {orDash(resolveLabel(adminDivision.map, cd.workLocalityCode) ?? cd.workLocalityText)}
              </FieldRow>
              <FieldRow fieldNumber={31} label="المنطقة" {...editProps('WORK_AREA', cd.workArea)}>
                {orDash(cd.workArea)}
              </FieldRow>
              <FieldRow fieldNumber={32} label="المدينة" {...editProps('WORK_CITY', cd.workCity)}>
                {orDash(cd.workCity)}
              </FieldRow>
              <FieldRow fieldNumber={33} label="الشارع" {...editProps('WORK_STREET', cd.workStreet)}>
                {orDash(cd.workStreet)}
              </FieldRow>
              <FieldRow fieldNumber={34} label="المربع" {...editProps('WORK_BLOCK', cd.workBlock)}>
                {orDash(cd.workBlock)}
              </FieldRow>
            </FieldGrid>
          </SubSection>
        </SectionCard>

        {/* ---- The action bar. NO «إكمال يدوي» -- AD-022 removed manual completion. -------- */}
        <div style={{ background: '#fff', border: `1px solid ${INK.BORDER}`, padding: '18px 16px', marginBottom: 14 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 12, flexWrap: 'wrap' }}>
            {mayAct && APPROVE_ELIGIBLE.has(detail.status) && (
              <button type="button" onClick={handleApprove} disabled={actionLoading} style={primaryActionStyle(actionLoading)}>
                اعتماد
              </button>
            )}
            {mayAct && REJECT_ELIGIBLE.has(detail.status) && (
              <button type="button" onClick={() => setRejectOpen(true)} disabled={actionLoading} style={rejectActionStyle}>
                رفض
              </button>
            )}
            {mayAct && PRINT_ELIGIBLE.has(detail.status) && (
              <button type="button" onClick={() => setPrintOpen(true)} disabled={printing} style={printActionStyle}>
                طباعة
              </button>
            )}
          </div>
          {mayAct && (APPROVE_ELIGIBLE.has(detail.status) || REJECT_ELIGIBLE.has(detail.status)) && (
            <div style={{ textAlign: 'center', fontSize: 12, color: INK.MUTED, marginTop: 10 }}>
              سيصل إشعار للعميل عبر القنوات المتحقَّق منها
            </div>
          )}
        </div>

        {/*
          THE STATUS HISTORY IS KEPT, though the artboard draws none. `operator.md` still
          specifies it ("the full status history of a profile, not only where it stands"), and no
          ruling removed it. The «إكمال يدوي» tag is WRITE-NEVER, READ-STILL: AD-022 deleted
          manual completion, but `is_manual_completion` is deliberately kept so profiles completed
          before the ruling stay distinguishable in their own history. Do not remove it as dead
          code because the action that produced it is gone.
        */}
        <SectionCard heading="سجل الحالة" note="لا يُعدَّل — سجل تدقيق">
          <div>
            {detail.statusHistory.map((h) => (
              <div key={h.seq} style={{ padding: '10px 16px', borderBottom: `1px solid ${INK.ROW_RULE}` }}>
                <div style={{ fontSize: 14, color: PALETTE.TEXT }}>
                  {h.fromStatus
                    ? `تغيّرت الحالة من «${STATUS_LABELS_AR[h.fromStatus as ProfileStatus] ?? h.fromStatus}» إلى «${STATUS_LABELS_AR[h.toStatus as ProfileStatus] ?? h.toStatus}»`
                    : `بدأت الحالة: ${STATUS_LABELS_AR[h.toStatus as ProfileStatus] ?? h.toStatus}`}
                  {h.isManualCompletion && (
                    <span style={{ marginInlineStart: 8, fontSize: 11, padding: '2px 8px', background: INK.TINT, color: PALETTE.PURPLE }}>
                      إكمال يدوي
                    </span>
                  )}
                </div>
                <div style={{ fontSize: 12, color: INK.MUTED, marginTop: 2 }}>
                  {formatInstant(h.occurredAt)} — {ACTOR_KIND_LABELS_AR[h.actorKind] ?? h.actorKind}
                  {h.actorId ? ` (${h.actorId})` : ''}
                </div>
                {h.reasonLabelAr && (
                  <div style={{ fontSize: 13, marginTop: 2 }}>
                    السبب: {h.reasonLabelAr} (<Num>{h.reasonCode}</Num>)
                  </div>
                )}
                {h.internalNote && <div style={{ fontSize: 12, color: INK.MUTED, marginTop: 2 }}>ملاحظة داخلية: {h.internalNote}</div>}
              </div>
            ))}
          </div>
        </SectionCard>

        {/* The ONLY way back to the list now that the screen sits outside AppShell and the
            sider's «الملفات» went with it. */}
        <PlainButton onClick={() => navigate('/profiles')}>→ رجوع إلى القائمة</PlainButton>
      </div>

      <RejectModal open={rejectOpen} submitting={actionLoading} onCancel={() => setRejectOpen(false)} onSubmit={handleReject} />
      {/* Mounted only while open, which is what makes both answers reset for every print --
          "asked every time, defaulting to no" (ticket 05 decision 9). PrintFormModal holds the
          two choices in its own state, so an unmount is the reset. */}
      {printOpen && (
        <PrintFormModal
          submitting={printing}
          hasAttachments={hasAttachments}
          onCancel={() => setPrintOpen(false)}
          onSubmit={handlePrint}
        />
      )}
    </ScreenShell>
  );
}

/**
 * The screen's message region — an error, a warning or a success, rendered in the React tree.
 *
 * `role="alert"` so a failure is announced rather than only coloured. Deliberately not antd's
 * `message`: that mounts outside the tree, which is what BL-156 is.
 */
function Banner({
  kind,
  text,
  action,
}: {
  kind: 'success' | 'error' | 'warning';
  text: string;
  action?: React.ReactNode;
}): React.JSX.Element {
  const colour = kind === 'error' ? PALETTE.RED : kind === 'success' ? PALETTE.SUCCESS : PALETTE.PURPLE;
  return (
    <div
      role="alert"
      style={{
        border: `1px solid ${colour}`,
        color: colour,
        background: '#fff',
        padding: '10px 14px',
        margin: '14px 20px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 12,
        fontSize: 13,
      }}
    >
      <span>{text}</span>
      {action}
    </div>
  );
}

/** A chip on section 2's verification line. `tone` never encodes a face-MATCH result. */
function VerificationChip({ text, tone }: { text: string; tone: 'good' | 'bad' | 'neutral' }): React.JSX.Element {
  const colour = tone === 'good' ? PALETTE.SUCCESS : tone === 'bad' ? PALETTE.RED : INK.MUTED;
  return (
    <span style={{ fontSize: 12, padding: '4px 11px', border: `1px solid ${colour}`, color: colour, fontWeight: tone === 'neutral' ? 400 : 600 }}>
      {text}
    </span>
  );
}

function PlainButton({ onClick, children }: { onClick: () => void; children: React.ReactNode }): React.JSX.Element {
  return (
    <button
      type="button"
      onClick={onClick}
      style={{
        fontSize: 13,
        padding: '6px 14px',
        border: `1px solid ${INK.BORDER}`,
        background: '#fff',
        color: PALETTE.TEXT,
        fontFamily: 'inherit',
        cursor: 'pointer',
      }}
    >
      {children}
    </button>
  );
}

/** The action bar's 54px primary, per the artboard. */
function primaryActionStyle(busy: boolean): React.CSSProperties {
  return {
    minWidth: 160,
    height: 54,
    border: 'none',
    background: PALETTE.PURPLE,
    color: '#fff',
    fontSize: 18,
    fontWeight: 600,
    fontFamily: 'inherit',
    cursor: busy ? 'default' : 'pointer',
    opacity: busy ? 0.7 : 1,
  };
}

const rejectActionStyle: React.CSSProperties = {
  minWidth: 160,
  height: 54,
  border: `1px solid ${PALETTE.RED}`,
  background: '#fff',
  color: PALETTE.RED,
  fontSize: 18,
  fontWeight: 600,
  fontFamily: 'inherit',
  cursor: 'pointer',
};

/** «طباعة» is smaller and at the end of the same row — the artboard's own hierarchy. */
const printActionStyle: React.CSSProperties = {
  height: 38,
  padding: '0 18px',
  border: `1px solid ${INK.BORDER}`,
  background: '#fff',
  color: PALETTE.TEXT,
  fontSize: 14,
  fontFamily: 'inherit',
  cursor: 'pointer',
  alignSelf: 'center',
};
