import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import ProfileDetailPage from './ProfileDetailPage';
import type { AuthState } from '../auth/AuthContext';
import type { ProfileDetailResponse, ReferenceDocument, ReferenceManifest } from '../api/types';
import { ApiError } from '../api/http';

/**
 * The single profile view, REBUILT AT S9-02 to the approved three-section artboard.
 *
 * These tests were rewritten wholesale rather than patched: the old suite asserted a nine-block
 * antd `Descriptions` layout that no longer exists. What survives is the BEHAVIOUR each old test
 * pinned — the print paths, the action gating, AD-022's channel and face-match rulings — plus the
 * per-field editing that is new here.
 */

const getProfileMock = vi.hoisted(() => vi.fn());
const approveProfileMock = vi.hoisted(() => vi.fn());
const rejectProfileMock = vi.hoisted(() => vi.fn());
const printProfileFormMock = vi.hoisted(() => vi.fn());
const editProfileFieldMock = vi.hoisted(() => vi.fn());

vi.mock('../api/profiles', () => ({
  getProfile: getProfileMock,
  approveProfile: approveProfileMock,
  rejectProfile: rejectProfileMock,
  printProfileForm: printProfileFormMock,
  editProfileField: editProfileFieldMock,
}));

const mockAuthState = vi.hoisted(() => ({
  current: { status: 'authenticated', username: 'op1', displayName: 'Op', role: 'operator' } as AuthState,
}));
const logoutMock = vi.hoisted(() => vi.fn());
vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ state: mockAuthState.current, login: vi.fn(), logout: logoutMock, refresh: vi.fn() }),
}));

const LIST_ITEMS: Record<string, { itemCode: string; labelAr: string; extra?: unknown }[]> = {
  branch: [{ itemCode: '16', labelAr: 'فرع الخرطوم' }],
  occupation: [{ itemCode: '5', labelAr: 'معلم' }],
  admin_division: [
    { itemCode: 'KH', labelAr: 'ولاية الخرطوم' },
    { itemCode: 'KH-1', labelAr: 'محلية بحري' },
  ],
  // `extra.alpha3` is what resolves the two MRZ country fields (4 and 48). Seeded here exactly as
  // V0022 seeds it, because that is the mapping BL-157 was filed for want of.
  country: [
    { itemCode: 'SD', labelAr: 'السودان', extra: { alpha3: 'SDN' } },
    { itemCode: 'EG', labelAr: 'مصر', extra: { alpha3: 'EGY' } },
  ],
  income_source: [
    { itemCode: 'salary', labelAr: 'راتب' },
    { itemCode: 'OTHER', labelAr: 'أخرى' },
  ],
  education_level: [{ itemCode: '3', labelAr: 'ثانوي' }],
  rejection_reason: [
    { itemCode: 'REJ-01', labelAr: 'صور غير واضحة' },
    { itemCode: 'REJ-07', labelAr: 'أخرى' },
  ],
};

function manifestFor(): ReferenceManifest {
  return {
    catalogHash: 'h',
    generatedAt: '2026-01-01T00:00:00Z',
    verifiableChannels: [],
    lists: Object.keys(LIST_ITEMS).map((listCode) => ({
      listCode,
      version: 1,
      itemCount: LIST_ITEMS[listCode].length,
      contentHash: 'c',
      isHierarchical: false,
      rootItemCode: null,
      rootCountryVersion: null,
      publishedAt: '2026-01-01T00:00:00Z',
      documentPath: '',
    })),
  };
}

function documentFor(listCode: string): ReferenceDocument {
  return {
    listCode,
    version: 1,
    itemCount: LIST_ITEMS[listCode].length,
    nameAr: listCode,
    nameEn: listCode,
    isHierarchical: false,
    rootItemCode: null,
    items: LIST_ITEMS[listCode].map((i) => ({
      itemCode: i.itemCode,
      parentCode: null,
      labelAr: i.labelAr,
      labelEn: null,
      searchAr: null,
      searchEn: null,
      sortOrdinal: 1,
      isActive: true,
      extra: i.extra ?? (i.itemCode === 'REJ-01' ? { customerMessageAr: 'رسالة العميل' } : null),
    })),
  };
}

function stubReferenceFetch() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url.includes('/reference/manifest')) return new Response(JSON.stringify(manifestFor()), { status: 200 });
      const match = /\/lists\/([^/]+)\//.exec(url);
      if (match) return new Response(JSON.stringify(documentFor(match[1])), { status: 200 });
      throw new Error(`unexpected fetch: ${url}`);
    }),
  );
}

/** The 14 keys the approved artboard draws a chip for — a Sudan-resident married profile. */
const SUDAN_EDITABLE = [
  'ETHNICITY',
  'SPOUSE_NAME',
  'BIRTH_CITY',
  'EMPLOYER_NAME',
  'WORK_AREA',
  'WORK_CITY',
  'WORK_STREET',
  'WORK_BLOCK',
  'HOME_AREA',
  'HOME_CITY',
  'HOME_STREET',
  'HOME_BLOCK',
  'HOME_HOUSE_NO',
  'INCOME_OTHER_TEXT',
];

function fullDetail(overrides: Partial<ProfileDetailResponse> = {}): ProfileDetailResponse {
  return {
    profileId: 'p1',
    referenceNumber: 'FRU-000000001',
    branchCode: '16',
    accountNumber: '9000000001',
    status: 'submitted',
    provenance: 'digital',
    submittedAt: '2026-08-01T00:00:00Z',
    createdAt: '2026-07-30T00:00:00Z',
    lastActivityAt: '2026-08-01T00:00:00Z',
    editableFields: [...SUDAN_EDITABLE],
    customerData: {
      phoneNumber: '+249912345678',
      emailAddress: 'customer@example.com',
      sexDeclared: 'f',
      maritalStatus: 'married',
      spouseName: 'زوج الاختبار',
      hasChildren: true,
      childrenCount: 2,
      educationLevel: 3,
      occupationCode: '5',
      occupationVersion: 1,
      monthlyExpensesSdg: 50000,
      identityType: 'national_id',
      ethnicity: 'شايقية',
      countryOfResidenceCode: 'SD',
      birthCountryCode: 'SD',
      birthStateCode: 'KH',
      birthStateText: null,
      birthCityText: null,
      homeCountryCode: 'SD',
      homeStateCode: 'KH',
      homeLocalityCode: 'KH-1',
      homeStateText: null,
      homeLocalityText: null,
      homeCity: 'الخرطوم',
      homeArea: 'المنطقة أ',
      homeStreet: 'شارع 1',
      homeBlock: 'حلة 2',
      homeHouseNo: '10',
      employerName: 'شركة الاختبار',
      workCountryCode: 'SD',
      workStateCode: 'KH',
      workLocalityCode: 'KH-1',
      workStateText: null,
      workLocalityText: null,
      workCity: 'الخرطوم',
      workArea: 'منطقة العمل',
      workStreet: 'شارع العمل',
      workBlock: 'حلة العمل',
      incomeSources: [
        { sourceCode: 'salary', isPrimary: true, otherText: null },
        { sourceCode: 'OTHER', isPrimary: false, otherText: 'إيجار عقار' },
      ],
    },
    channels: [
      { channel: 'sms', state: 'verified', verifiedAt: '2026-07-31T00:00:00Z' },
      { channel: 'whatsapp', state: 'declined', verifiedAt: null },
      { channel: 'email', state: 'verified', verifiedAt: '2026-07-31T00:00:00Z' },
    ],
    scanResult: {
      documentType: 'SDN_ID',
      cardVariant: null,
      identityNumber: '1234567890',
      documentNumber: 'B0000011',
      mrzVerified: true,
      nationality: 'SDN',
      sexOnDocument: 'F',
      dateOfBirth: '1994-11-09',
      dateOfIssue: '2019-05-12',
      dateOfExpiry: '2029-05-12',
      placeOfIssue: 'أم درمان',
      issuingCountry: 'SDN',
      nameArOnDocument: 'اسم المستند',
      nameEnOnDocument: 'Document Name',
      bloodType: 'O+',
      birthCity: null,
      receivedAt: '2026-08-01T00:00:00Z',
    },
    faceResult: { match: true, matchLevel: 4, thresholdApplied: 3, passed: true, receivedAt: '2026-08-01T00:05:00Z' },
    registryResult: {
      state: 'matched',
      nameArGiven: 'آلاء',
      nameArFather: 'وليد',
      nameArGrandfather: 'الأمين',
      nameArGreatGrandfather: 'محمد',
      nameArMother: 'سعاد',
      nameArMotherFather: 'عبدالرحمن',
      nameArMotherGrandfather: 'الطيب',
      nameArMotherGreatGrandfather: 'إبراهيم',
      firstNamesEn: 'ALAA WALEED',
      lastNameEn: 'MOHAMED',
      sexRegistry: 'f',
      dateOfBirth: '1994-11-09',
      rawAddressAr: 'عنوان السجل المدني الخام',
      identityNumberReturned: '000-0000-0011',
    },
    artifacts: [
      { artifactRefId: '11111111-1111-4111-8111-111111111111', kind: 'portrait_registry', label: 'Civil Registry', storageKey: null, contentType: 'image/jpeg', byteSize: 1000, sha256Hex: 'a'.repeat(64) },
      { artifactRefId: '22222222-2222-4222-8222-222222222222', kind: 'portrait_uqudo', label: 'Uqudo', storageKey: null, contentType: 'image/jpeg', byteSize: 2000, sha256Hex: 'b'.repeat(64) },
      { artifactRefId: '33333333-3333-4333-8333-333333333333', kind: 'signature', label: 'Signature', storageKey: null, contentType: 'image/png', byteSize: 300, sha256Hex: 'c'.repeat(64) },
    ],
    statusHistory: [
      { seq: 1, fromStatus: null, toStatus: 'in_progress', occurredAt: '2026-07-30T00:00:00Z', actorKind: 'customer', actorId: null, reasonCode: null, reasonLabelAr: null, reasonLabelEn: null, internalNote: null, isManualCompletion: false },
      { seq: 2, fromStatus: 'in_progress', toStatus: 'submitted', occurredAt: '2026-08-01T00:00:00Z', actorKind: 'customer', actorId: null, reasonCode: null, reasonLabelAr: null, reasonLabelEn: null, internalNote: null, isManualCompletion: false },
    ],
    salaryCertificateState: 'PRESENT',
    ...overrides,
  };
}

function minimalDetail(overrides: Partial<ProfileDetailResponse> = {}): ProfileDetailResponse {
  const base = fullDetail();
  return {
    ...base,
    status: 'in_progress',
    scanResult: null,
    faceResult: null,
    registryResult: null,
    artifacts: [],
    statusHistory: [],
    editableFields: [],
    channels: [],
    // A profile with no artifacts cannot also hold a certificate.
    salaryCertificateState: 'NOT_REACHED',
    customerData: {
      ...base.customerData,
      spouseName: null,
      ethnicity: null,
      homeCity: null,
      homeArea: null,
      homeStreet: null,
      homeBlock: null,
      homeHouseNo: null,
      employerName: null,
      workCity: null,
      workArea: null,
      workStreet: null,
      workBlock: null,
      incomeSources: [],
    },
    ...overrides,
  };
}

function renderPage(profileId = 'p1') {
  return render(
    <MemoryRouter initialEntries={[`/profiles/${profileId}`]}>
      <Routes>
        <Route path="/profiles/:profileId" element={<ProfileDetailPage />} />
        <Route path="/profiles" element={<div>profile-list-screen</div>} />
        <Route path="/login" element={<div>login-screen</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

/** The reference number is the screen's readiness signal — it renders only once detail arrives. */
async function awaitLoaded() {
  return screen.findByText('FRU-000000001');
}

/** The row for one field number, so an assertion can be scoped to it. */
function row(fieldNumber: number): HTMLElement {
  const numberCell = screen.getByText(String(fieldNumber), { selector: 'span[aria-hidden="true"]' });
  return numberCell.parentElement as HTMLElement;
}

describe('ProfileDetailPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    getProfileMock.mockReset();
    approveProfileMock.mockReset();
    rejectProfileMock.mockReset();
    printProfileFormMock.mockReset();
    editProfileFieldMock.mockReset();
    logoutMock.mockReset();
    mockAuthState.current = { status: 'authenticated', username: 'op1', displayName: 'Op', role: 'operator' };
  });

  // --- Loading, failure, chrome ------------------------------------------------------------

  it('shows an error with a retry button on failure, and retry reloads', async () => {
    stubReferenceFetch();
    getProfileMock.mockRejectedValueOnce(new ApiError(404, 'not found')).mockResolvedValueOnce(fullDetail());
    const user = userEvent.setup();

    renderPage();

    expect(await screen.findByText('تعذر تحميل الملف (404)')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'إعادة المحاولة' }));
    await awaitLoaded();
  });

  it('renders the approved header, the identity card and its badges', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());

    renderPage();
    await awaitLoaded();

    expect(screen.getByText('AZ Omni eKYC')).toBeInTheDocument();
    expect(screen.getByText('تحديث بيانات العملاء — البنك السوداني الفرنسي')).toBeInTheDocument();
    expect(screen.getByText('Op (مشغّل)')).toBeInTheDocument();
    // The customer name is ASSEMBLED from the registry's four paternal parts -- the detail
    // response carries no display name at all. Twice over: the identity card and field 5.
    expect(screen.getAllByText('آلاء وليد الأمين محمد')).toHaveLength(2);
    // Twice as well: the identity-card badge and field 2.
    expect(screen.getAllByText('فرع الخرطوم')).toHaveLength(2);
    expect(screen.getByText('رقمي')).toBeInTheDocument();
    expect(screen.getByText('مُقدَّم')).toBeInTheDocument();
  });

  it('does not claim a queue position the backend has no concept of (BL-158)', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());

    renderPage();
    await awaitLoaded();

    // The artboard draws «الملف ٣ من ١٠ في قائمة المراجعة». There is no ordinal on the wire and
    // router state would be wrong the moment the profile is opened directly.
    //
    // Asserted on the SHAPE rather than on that exact wording: a query for a string that exists
    // nowhere in the repo passes trivially and would go on passing if a queue line were added
    // under any other phrasing. The fixture is profile p1 of an unknown total, so any «N من M»
    // the header could draw would have to match this.
    expect(screen.queryByText(/\d+\s*من\s*\d+/)).not.toBeInTheDocument();
    expect(screen.queryByText(/قائمة المراجعة/)).not.toBeInTheDocument();
  });

  it('signs out from its own header, since the shell that used to carry it is gone', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    logoutMock.mockResolvedValueOnce(undefined);
    const user = userEvent.setup();

    renderPage();
    await awaitLoaded();

    await user.click(screen.getByRole('button', { name: 'تسجيل الخروج' }));
    await waitFor(() => expect(logoutMock).toHaveBeenCalled());
    expect(await screen.findByText('login-screen')).toBeInTheDocument();
  });

  it('still navigates to /login when the server-side sign-out itself fails', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    logoutMock.mockRejectedValueOnce(new Error('network'));
    const user = userEvent.setup();

    renderPage();
    await awaitLoaded();

    // logout() clears local state FIRST and can still reject afterwards. Navigating only on
    // success would strand an operator who is already anonymous locally.
    await user.click(screen.getByRole('button', { name: 'تسجيل الخروج' }));
    expect(await screen.findByText('login-screen')).toBeInTheDocument();
  });

  it('navigates back to the profile list, the only way out now the sider is gone', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    const user = userEvent.setup();

    renderPage();
    await awaitLoaded();

    await user.click(screen.getByRole('button', { name: /رجوع إلى القائمة/ }));
    expect(await screen.findByText('profile-list-screen')).toBeInTheDocument();
  });

  // --- Section 1: the Civil Registry --------------------------------------------------------

  it('joins the registry name parts into the single lines the artboard draws', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());

    renderPage();
    await awaitLoaded();

    expect(within(row(5)).getByText('آلاء وليد الأمين محمد')).toBeInTheDocument();
    expect(within(row(8)).getByText('سعاد عبدالرحمن الطيب إبراهيم')).toBeInTheDocument();
    expect(within(row(6)).getByText('ALAA WALEED MOHAMED')).toBeInTheDocument();
  });

  it('takes field 7 from the REGISTRY, never from the scan copy', async () => {
    stubReferenceFetch();
    // The two differ here on purpose. They cannot differ in production -- the client treats a
    // mismatch as not_found -- but rendering the scan's copy under a "from the Civil Registry"
    // heading would make that heading true only by coincidence, which is why BL-132 added the
    // registry's own column. A screen reading `scanResult.identityNumber` passes every other
    // test in this file and fails this one.
    getProfileMock.mockResolvedValue(fullDetail());

    renderPage();
    await awaitLoaded();

    expect(within(row(7)).getByText('000-0000-0011')).toBeInTheDocument();
    expect(within(row(7)).queryByText('1234567890')).not.toBeInTheDocument();
  });

  it('renders a date-only wire value without inventing a midnight', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());

    renderPage();
    await awaitLoaded();

    // The registry DOB is a LocalDate, "1994-11-09". The old helper appended HH:mm and would
    // have rendered `09/11/1994 00:00` -- a time the bank never recorded.
    expect(within(row(21)).getByText('09/11/1994')).toBeInTheDocument();
    expect(within(row(21)).queryByText(/00:00/)).not.toBeInTheDocument();
  });

  it('refuses a day-first date rather than silently reading it month-first', async () => {
    stubReferenceFetch();
    const base = fullDetail();
    getProfileMock.mockResolvedValue(
      fullDetail({ registryResult: { ...base.registryResult!, dateOfBirth: '09/11/1994' } }),
    );

    renderPage();
    await awaitLoaded();

    // The registry natively returns DD/MM/YYYY and field-provenance field 21 says to parse
    // explicitly, never with a locale default. dayjs IGNORES a format argument unless
    // `customParseFormat` is registered -- without it this exact value parses as VALID and
    // renders `11/09/1994`, day and month swapped, with isValid() true so nothing shows the
    // error.
    //
    // HONEST LIMIT OF THIS TEST: it cannot currently fail. antd registers the same plugin on the
    // shared dayjs singleton, and this file imports components that pull antd in, so the guard
    // holds today even with ProfileDetailPage's own `dayjs.extend` removed -- verified by
    // reverting that line and watching all 62 tests still pass. It is kept because the page
    // declares the plugin itself, and the day antd leaves this screen entirely this assertion
    // starts doing real work. The page's own comment records the same thing.
    expect(within(row(21)).queryByText('11/09/1994')).not.toBeInTheDocument();
    expect(within(row(21)).getByText('09/11/1994')).toBeInTheDocument();
  });

  it('offers no edit affordance anywhere in the registry section', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());

    renderPage();
    await awaitLoaded();

    for (const fieldNumber of [5, 6, 7, 8, 9, 21]) {
      expect(within(row(fieldNumber)).queryByRole('button', { name: /تعديل/ })).not.toBeInTheDocument();
    }
  });

  // --- Section 2: the document and the liveness check ---------------------------------------

  it('keeps the liveness success line while showing no face-match result at all', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());

    renderPage();
    await awaitLoaded();

    expect(screen.getByText('التحقق الحي — ناجح')).toBeInTheDocument();
    // AD-022 ruling 3: the match is still run, still stored, still audited -- and never shown.
    // `faceResult` is read ONLY as a completed-ness predicate.
    expect(screen.queryByText(/مطابقة الوجه/)).not.toBeInTheDocument();
    expect(screen.queryByText(/مستوى المطابقة/)).not.toBeInTheDocument();
    // The SCORES themselves -- matchLevel 4 and thresholdApplied 3 in the fixture. Ignoring
    // aria-hidden nodes is essential: every row prints its own field number, so a bare digit
    // query would match field 4's label column and mean nothing at all.
    const visibleDigit = (text: string) =>
      screen.queryByText(text, { ignore: 'script, style, [aria-hidden="true"]' });
    expect(visibleDigit('4')).not.toBeInTheDocument();
    expect(visibleDigit('3')).not.toBeInTheDocument();
  });

  it('shows blocked_liveness as a distinct failure, not a generic one', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail({ status: 'blocked_liveness' }));

    renderPage();
    await awaitLoaded();

    expect(screen.getByText('التحقق الحي — فشل')).toBeInTheDocument();
  });

  it('does not claim a liveness pass on a profile that never reached the stage', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail({ faceResult: null }));

    renderPage();
    await awaitLoaded();

    // The artboard draws «ناجح» flat. Rendering that unconditionally would assert a pass that
    // did not happen -- a profile completed manually before AD-022 is `submitted` with no
    // faceResult at all.
    expect(screen.getByText('التحقق الحي — لم يتم بعد')).toBeInTheDocument();
    expect(screen.queryByText('التحقق الحي — ناجح')).not.toBeInTheDocument();
  });

  it('renders the MRZ check as a tri-state, not a boolean', async () => {
    stubReferenceFetch();
    getProfileMock
      .mockResolvedValueOnce(fullDetail())
      .mockResolvedValueOnce(fullDetail({ scanResult: { ...fullDetail().scanResult!, mrzVerified: false } }))
      .mockResolvedValueOnce(fullDetail({ scanResult: { ...fullDetail().scanResult!, mrzVerified: null } }));

    const first = renderPage();
    expect(await screen.findByText('تحقق MRZ — صحيح')).toBeInTheDocument();
    first.unmount();

    const second = renderPage();
    expect(await screen.findByText('تحقق MRZ — غير صحيح')).toBeInTheDocument();
    second.unmount();

    renderPage();
    expect(await screen.findByText('تحقق MRZ — لم يتم بعد')).toBeInTheDocument();
  });

  it('renders the contact sheet against real image bytes, addressed by artifactRefId', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());

    renderPage();
    await awaitLoaded();

    const srcs = Array.from(document.querySelectorAll('img')).map((i) => i.getAttribute('src'));
    expect(srcs).toContain('/api/v1/operator/profiles/p1/artifacts/11111111-1111-4111-8111-111111111111');
    expect(srcs).toContain('/api/v1/operator/profiles/p1/artifacts/33333333-3333-4333-8333-333333333333');
  });

  it('draws six artifact tiles, never a seventh for doc_back (BL-159)', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());

    renderPage();
    await awaitLoaded();

    // The artboard specifies seven. `OperatorImagePolicy` refuses doc_back, so a seventh tile
    // would render a broken image against a 404.
    //
    // COUNTED, not searched for by name. A query for «ظهر وثيقة الهوية» matches nothing in the
    // repo and so passes whatever the screen renders; counting the tiles fails if a seventh is
    // added under any wording at all. Each tile carries exactly one source line, and those six
    // strings are the six sources.
    // Counted over the CAPTION line, not the source line: «البطاقة القومية» is also field 43's
    // value, so counting sources would have counted seven and did.
    const captions = ['الصورة الشخصية', 'وجه وثيقة الهوية', 'صورة التحقق الحي', 'التوقيع', 'شهادة المرتب'];
    const tileCount = captions.reduce((total, caption) => total + screen.queryAllByText(caption).length, 0);
    expect(tileCount).toBe(6);
    expect(screen.getAllByText('الصورة الشخصية')).toHaveLength(2);
  });

  it('resolves the two MRZ alpha-3 country fields to names (BL-157)', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());

    renderPage();
    await awaitLoaded();

    // Field 4 (nationality) and field 48 (issuing country) are ICAO alpha-3 while the country
    // list is keyed alpha-2. The mapping is in that list's own `extra.alpha3`, so no list is
    // hardcoded -- which is what let BL-157 close rather than ship the raw code.
    expect(within(row(4)).getByText('السودان')).toBeInTheDocument();
    expect(within(row(48)).getByText('السودان')).toBeInTheDocument();
  });

  it('shows an ICAO code that has no ISO row as received, rather than as a dash', async () => {
    stubReferenceFetch();
    const base = fullDetail();
    getProfileMock.mockResolvedValue(
      fullDetail({ scanResult: { ...base.scanResult!, nationality: 'XXA', issuingCountry: 'GBD' } }),
    );

    renderPage();
    await awaitLoaded();

    // XXA (stateless) and GBD (British overseas) are real MRZ values with no ISO 3166 row.
    // Showing a dash would destroy information the document actually carried.
    expect(within(row(4)).getByText('XXA')).toBeInTheDocument();
    expect(within(row(48)).getByText('GBD')).toBeInTheDocument();
  });

  // --- Section 3: what the customer submitted ------------------------------------------------

  it('renders ONLY verified channels — a declined one is absent, not greyed', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());

    renderPage();
    await awaitLoaded();

    // sms is verified so the phone shows; whatsapp is declined and email verified.
    expect(within(row(25)).getByText('+249912345678')).toBeInTheDocument();
    expect(within(row(26)).getByText('customer@example.com')).toBeInTheDocument();
  });

  it('hides the phone when NEITHER channel behind it is verified', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(
      fullDetail({
        channels: [
          { channel: 'sms', state: 'unverified', verifiedAt: null },
          { channel: 'whatsapp', state: 'declined', verifiedAt: null },
          { channel: 'email', state: 'verified', verifiedAt: '2026-07-31T00:00:00Z' },
        ],
      }),
    );

    renderPage();
    await awaitLoaded();

    // AD-022 ruling 4. The number is still ON THE WIRE in customerData -- this is a display
    // filter, and showing it would show an operator a number nobody proved the customer holds.
    expect(screen.queryByText('+249912345678')).not.toBeInTheDocument();
    expect(screen.getByText('customer@example.com')).toBeInTheDocument();
  });

  it('shows the phone when only WhatsApp is verified, since one number backs two channels', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(
      fullDetail({
        channels: [
          { channel: 'sms', state: 'unverified', verifiedAt: null },
          { channel: 'whatsapp', state: 'verified', verifiedAt: '2026-07-31T00:00:00Z' },
          { channel: 'email', state: 'unverified', verifiedAt: null },
        ],
      }),
    );

    renderPage();
    await awaitLoaded();

    expect(screen.getByText('+249912345678')).toBeInTheDocument();
    expect(screen.queryByText('customer@example.com')).not.toBeInTheDocument();
  });

  it('says so plainly when no channel is verified at all', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail({ channels: [] }));

    renderPage();
    await awaitLoaded();

    expect(screen.getByText('لا توجد قنوات اتصال موثّقة لهذا الملف.')).toBeInTheDocument();
  });

  it('labels the spouse field by the customer sex, covering field 14', async () => {
    stubReferenceFetch();
    getProfileMock
      .mockResolvedValueOnce(fullDetail())
      .mockResolvedValueOnce(
        fullDetail({
          // BOTH sources, and that is the point of this edit rather than an oversight corrected.
          // This override used to set `sexDeclared` alone, on a fixture whose `sexRegistry` is
          // 'f' -- so it asserted that a customer the REGISTRY calls female gets «اسم الزوجة».
          // The printed form, handed the same profile, says «اسم الزوج»: it has always resolved
          // sex registry-first (PrintedFormAssembler.effectiveSex). The test was pinning the
          // divergence BL-161 exists to remove. A male customer is male in both places.
          customerData: { ...fullDetail().customerData, sexDeclared: 'm' },
          registryResult: { ...fullDetail().registryResult!, sexRegistry: 'm' },
        }),
      );

    const first = renderPage();
    // A woman's spouse is a husband: field 13.
    expect(await screen.findByText('اسم الزوج')).toBeInTheDocument();
    first.unmount();

    renderPage();
    // Field 14, «اسم الزوجة», which the redesign brief's table omitted entirely -- the artboard
    // fixture is a married woman, so the gap was invisible there.
    expect(await screen.findByText('اسم الزوجة')).toBeInTheDocument();
  });

  // BL-161, and the case no fixture covered before it. The registry is the reference wherever it
  // supplies a value (AD-021, and the ruling that closed BL-160), so a profile whose registry and
  // declared sex disagree must follow the registry -- on the screen exactly as on the printed
  // form. Both dependent rows are asserted together, because the defect this guards against is
  // them disagreeing with EACH OTHER: «اسم الزوج» over «متزوج» on one sheet of paper.
  it('follows the registry over the declared sex, for the spouse label and the marital state alike', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(
      fullDetail({
        customerData: { ...fullDetail().customerData, sexDeclared: 'm', maritalStatus: 'married' },
        registryResult: { ...fullDetail().registryResult!, sexRegistry: 'f' },
      }),
    );

    renderPage();
    await awaitLoaded();

    expect(screen.getByText('اسم الزوج')).toBeInTheDocument();
    expect(screen.getByText('متزوجة')).toBeInTheDocument();
    expect(screen.queryByText('متزوج')).not.toBeInTheDocument();
  });

  it('lets the SCAN supersede the customer text for birth city', async () => {
    stubReferenceFetch();
    const base = fullDetail();
    getProfileMock.mockResolvedValue(
      fullDetail({
        customerData: { ...base.customerData, birthCityText: 'ما كتبه العميل' },
        scanResult: { ...base.scanResult!, birthCity: 'أم درمان' },
      }),
    );

    renderPage();
    await awaitLoaded();

    // customer.md Stage 3: "Uqudo's value supersedes theirs when the scan lands". Rendering
    // birthCityText alone would show a value the profile does not mean.
    expect(within(row(23)).getByText('أم درمان')).toBeInTheDocument();
    expect(within(row(23)).queryByText('ما كتبه العميل')).not.toBeInTheDocument();
  });

  it('renders the whole income selection while offering to edit only its «أخرى» text', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    const user = userEvent.setup();

    renderPage();
    await awaitLoaded();

    expect(within(row(20)).getByText('راتب — أساسي · أخرى · إيجار عقار')).toBeInTheDocument();

    // The chip reaches `other_text` and nothing else: the codes and the primary flag are a
    // list-picked multi-select, and AD-021 forbids editing those.
    await user.click(within(row(20)).getByRole('button', { name: /تعديل/ }));
    expect(screen.getByLabelText('مصدر الدخل')).toHaveValue('إيجار عقار');
  });

  it('seeds the income editor from the OTHER row, not from whichever row carries text', async () => {
    stubReferenceFetch();
    const base = fullDetail();
    getProfileMock.mockResolvedValue(
      fullDetail({
        customerData: {
          ...base.customerData,
          incomeSources: [
            // A non-OTHER row carrying text FIRST. V0006's `other_needs_text` CHECK is
            // one-directional -- it requires text on the OTHER row and forbids none elsewhere --
            // so this shape is storable. A seed that took "the first row with any text" would
            // put «راتب شهري» in the editor and then write it into the OTHER row on save,
            // overwriting what the customer actually typed.
            { sourceCode: 'salary', isPrimary: true, otherText: 'راتب شهري' },
            { sourceCode: 'OTHER', isPrimary: false, otherText: 'إيجار عقار' },
          ],
        },
      }),
    );
    const user = userEvent.setup();

    renderPage();
    await awaitLoaded();

    await user.click(within(row(20)).getByRole('button', { name: /تعديل/ }));
    expect(screen.getByLabelText('مصدر الدخل')).toHaveValue('إيجار عقار');
  });

  // --- Per-field editing --------------------------------------------------------------------

  it('draws a chip for every key the server sent, and for no other field', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());

    renderPage();
    await awaitLoaded();

    // The 14 the artboard draws.
    for (const fieldNumber of [10, 13, 20, 23, 27, 31, 32, 33, 34, 38, 39, 40, 41, 42]) {
      expect(within(row(fieldNumber)).getByRole('button', { name: /تعديل/ })).toBeInTheDocument();
    }
    // And none on a list-picked or sourced field. 16 and 19 are digits and NOT editable
    // (BL-152); 11, 12, 17, 18, 22, 28 and 35 are list-picked; 4 is from the scan.
    for (const fieldNumber of [2, 3, 4, 11, 12, 16, 17, 18, 19, 22, 25, 26, 28, 35]) {
      expect(within(row(fieldNumber)).queryByRole('button', { name: /تعديل/ })).not.toBeInTheDocument();
    }
  });

  it('draws chips on the non-Sudan fallbacks when the server says so', async () => {
    stubReferenceFetch();
    const base = fullDetail();
    getProfileMock.mockResolvedValue(
      fullDetail({
        editableFields: [...SUDAN_EDITABLE, 'HOME_STATE_TEXT', 'HOME_LOCALITY_TEXT', 'WORK_STATE_TEXT', 'WORK_LOCALITY_TEXT', 'BIRTH_STATE_TEXT'],
        customerData: {
          ...base.customerData,
          homeCountryCode: 'EG',
          homeStateCode: null,
          homeLocalityCode: null,
          homeStateText: 'القاهرة',
          homeLocalityText: 'مدينة نصر',
        },
      }),
    );

    renderPage();
    await awaitLoaded();

    // The artboard draws NO chip on 24/29/30/36/37, because its fixture is a Sudan profile where
    // those are list-picked. A screen that hardcoded the artboard's fourteen would leave every
    // customer abroad unable to have their address corrected.
    for (const fieldNumber of [24, 29, 30, 36, 37]) {
      expect(within(row(fieldNumber)).getByRole('button', { name: /تعديل/ })).toBeInTheDocument();
    }
  });

  it('shows a VIEWER no chips at all, though the server sends them the same list', async () => {
    stubReferenceFetch();
    mockAuthState.current = { status: 'authenticated', username: 'v1', displayName: 'V', role: 'viewer' };
    getProfileMock.mockResolvedValue(fullDetail());

    renderPage();
    await awaitLoaded();

    // `editableFields` is deliberately NOT role-gated server-side -- it describes the profile,
    // not the caller -- so the fixture's populated list is exactly what a viewer receives. Every
    // chip drawn here would 403 on press.
    expect(screen.queryByRole('button', { name: /تعديل/ })).not.toBeInTheDocument();
  });

  it('draws no chips when the status closes editing, whatever the list says', async () => {
    stubReferenceFetch();
    // The server empties the list for an approved profile; this asserts the screen honours it
    // rather than deriving editability of its own.
    getProfileMock.mockResolvedValue(fullDetail({ status: 'approved', editableFields: [] }));

    renderPage();
    await awaitLoaded();

    expect(screen.queryByRole('button', { name: /تعديل/ })).not.toBeInTheDocument();
  });

  it('ignores a key this client does not know', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail({ editableFields: ['HOME_AREA', 'SOME_FUTURE_FIELD'] }));

    renderPage();
    await awaitLoaded();

    expect(within(row(38)).getByRole('button', { name: /تعديل/ })).toBeInTheDocument();
    // One chip, not two: an unknown key has no row to attach to and is dropped rather than guessed.
    expect(screen.getAllByRole('button', { name: /تعديل/ })).toHaveLength(1);
  });

  it('PATCHes one field and reloads the profile afterwards', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    editProfileFieldMock.mockResolvedValue({ fieldKey: 'HOME_STREET', value: 'شارع الأربعين' });
    const user = userEvent.setup();

    renderPage();
    await awaitLoaded();

    await user.click(within(row(40)).getByRole('button', { name: /تعديل/ }));
    const input = screen.getByLabelText('الشارع');
    await user.clear(input);
    await user.type(input, 'شارع الأربعين');
    await user.click(screen.getByRole('button', { name: 'حفظ' }));

    await waitFor(() => expect(editProfileFieldMock).toHaveBeenCalledWith('p1', 'HOME_STREET', 'شارع الأربعين'));
    // Reloaded, not patched locally: an edit changes the DERIVED provenance, and that derivation
    // happens on the read path. A screen that updated its own copy would go on saying «رقمي».
    await waitFor(() => expect(getProfileMock).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('حُفظ التعديل.')).toBeInTheDocument();
  });

  it('reloads and says so when the server refuses a stale chip with a 409', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    editProfileFieldMock.mockRejectedValue(new ApiError(409, 'not editable'));
    const user = userEvent.setup();

    renderPage();
    await awaitLoaded();

    await user.click(within(row(38)).getByRole('button', { name: /تعديل/ }));
    await user.click(screen.getByRole('button', { name: 'حفظ' }));

    // The chip is a courtesy and the server re-derives on the write path, so a 409 means this
    // screen is out of date -- it must not go on offering an edit that was just refused.
    await waitFor(() => expect(getProfileMock).toHaveBeenCalledTimes(2));
    expect(await screen.findByText(/لم يعد هذا الحقل قابلاً للتعديل/)).toBeInTheDocument();
  });

  it('does not reload when the edit is merely a bad value', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    editProfileFieldMock.mockRejectedValue(new ApiError(400, 'bad value'));
    const user = userEvent.setup();

    renderPage();
    await awaitLoaded();

    await user.click(within(row(38)).getByRole('button', { name: /تعديل/ }));
    await user.click(screen.getByRole('button', { name: 'حفظ' }));

    expect(await screen.findByText('القيمة غير مقبولة.')).toBeInTheDocument();
    // Nothing changed server-side, so reloading would only discard what the operator typed.
    expect(getProfileMock).toHaveBeenCalledTimes(1);
  });

  it('tells a viewer they lack the permission, rather than a generic failure', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    editProfileFieldMock.mockRejectedValue(new ApiError(403, 'forbidden'));
    const user = userEvent.setup();

    renderPage();
    await awaitLoaded();

    await user.click(within(row(38)).getByRole('button', { name: /تعديل/ }));
    await user.click(screen.getByRole('button', { name: 'حفظ' }));

    // Reachable despite the role gate on the chip: a session can lose its level between load and
    // save, and the server is the enforcement either way.
    expect(await screen.findByText('لا تملك صلاحية تعديل الحقول.')).toBeInTheDocument();
    expect(getProfileMock).toHaveBeenCalledTimes(1);
  });

  it('reports a dropped connection without claiming to know what happened', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    editProfileFieldMock.mockRejectedValue(new TypeError('Failed to fetch'));
    const user = userEvent.setup();

    renderPage();
    await awaitLoaded();

    await user.click(within(row(38)).getByRole('button', { name: /تعديل/ }));
    await user.click(screen.getByRole('button', { name: 'حفظ' }));

    // Not an ApiError, so there is no status to branch on -- the message must not imply one.
    expect(await screen.findByText('تعذر حفظ التعديل.')).toBeInTheDocument();
  });

  it('lets the operator dismiss a notice', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    approveProfileMock.mockResolvedValueOnce({ profileId: 'p1', status: 'approved', alreadyDone: true, notifiedChannels: [] });
    const user = userEvent.setup();

    renderPage();
    await awaitLoaded();

    await user.click(screen.getByRole('button', { name: 'اعتماد' }));
    expect(await screen.findByText('الملف معتمد بالفعل.')).toBeInTheDocument();

    // The notice lives in the tree rather than in antd's message singleton, so it persists until
    // dismissed instead of fading -- which means it needs a way out.
    await user.click(screen.getByRole('button', { name: 'إغلاق' }));
    expect(screen.queryByText('الملف معتمد بالفعل.')).not.toBeInTheDocument();
  });

  it('shows why a profile was rejected, with the code and the internal note', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(
      fullDetail({
        status: 'rejected',
        statusHistory: [
          {
            seq: 3,
            fromStatus: 'submitted',
            toStatus: 'rejected',
            occurredAt: '2026-08-02T09:00:00Z',
            actorKind: 'operator',
            actorId: 'op1',
            reasonCode: 'REJ-01',
            reasonLabelAr: 'صور غير واضحة',
            reasonLabelEn: 'Unclear images',
            internalNote: 'الصورة الأمامية غير مقروءة',
            isManualCompletion: false,
          },
        ],
      }),
    );

    renderPage();
    await awaitLoaded();

    // The reason is the whole point of keeping the history: an operator re-reviewing a rejected
    // profile has to see why it was rejected the first time.
    expect(screen.getByText(/صور غير واضحة/)).toBeInTheDocument();
    expect(screen.getByText('REJ-01')).toBeInTheDocument();
    expect(screen.getByText(/الصورة الأمامية غير مقروءة/)).toBeInTheDocument();
    // The ACTOR line, scoped: «مشغّل» is also the signed-in operator's own role in the header.
    expect(screen.getByText(/مشغّل \(op1\)/)).toBeInTheDocument();
  });

  it('says a customer has no children rather than showing a blank', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(
      fullDetail({ customerData: { ...fullDetail().customerData, hasChildren: false, childrenCount: null } }),
    );

    renderPage();
    await awaitLoaded();

    expect(within(row(15)).getByText('لا')).toBeInTheDocument();
  });

  // --- Provenance, history, minimal profiles -------------------------------------------------

  it('shows the manual-provenance banner when provenance is manual', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail({ provenance: 'manual' }));

    renderPage();
    await awaitLoaded();

    expect(screen.getByText('ملف يدوي — أُدخل أو عُدِّل أحد حقوله من قبل مشغّل.')).toBeInTheDocument();
    expect(screen.getByText('يدوي')).toBeInTheDocument();
  });

  it('keeps the status history the artboard does not draw, and marks a manual completion', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(
      fullDetail({
        statusHistory: [
          { seq: 1, fromStatus: 'in_progress', toStatus: 'submitted', occurredAt: '2026-08-01T00:00:00Z', actorKind: 'operator', actorId: 'op9', reasonCode: null, reasonLabelAr: null, reasonLabelEn: null, internalNote: null, isManualCompletion: true },
        ],
      }),
    );

    renderPage();
    await awaitLoaded();

    // operator.md still requires the full history. `is_manual_completion` is WRITE-NEVER,
    // READ-STILL: AD-022 deleted manual completion but kept the column so profiles completed
    // before the ruling stay distinguishable in their own history.
    expect(screen.getByText('سجل الحالة')).toBeInTheDocument();
    expect(screen.getByText(/تغيّرت الحالة من «قيد التنفيذ» إلى «مُقدَّم»/)).toBeInTheDocument();
    expect(screen.getByText('إكمال يدوي')).toBeInTheDocument();
  });

  it('renders a minimal, mostly-null profile without crashing', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(minimalDetail());

    renderPage();
    await awaitLoaded();

    expect(screen.getByText('التحقق الحي — لم يتم بعد')).toBeInTheDocument();
    expect(screen.getByText('لا توجد قنوات اتصال موثّقة لهذا الملف.')).toBeInTheDocument();
    // An in_progress profile is neither reviewable nor printable, and carries no editable set.
    expect(screen.queryByRole('button', { name: 'اعتماد' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /تعديل/ })).not.toBeInTheDocument();
  });

  it('isolates Latin-script values from bidi reordering', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());

    renderPage();
    await awaitLoaded();

    // A leading `+` or an all-digit string has no strong-direction character to anchor it, and
    // the browser reorders it visually -- «+249912345678» renders as «249912345678+».
    //
    // The DIRECTION is asserted, not just the isolation. `<bdi>` alone defaults to dir="auto",
    // which means "take the first STRONG character's direction" -- and these values have none, so
    // it falls back to LTR and happens to be right. One Arabic character at the front of any
    // value routed through `Num` flips the whole span and puts the plus back at the wrong end.
    // jsdom does no bidi layout, so the attribute is the only thing a test can hold.
    const phone = within(row(25)).getByText('+249912345678');
    expect(phone.closest('bdi')).toHaveAttribute('dir', 'ltr');
    expect(within(row(7)).getByText('000-0000-0011').closest('bdi')).toHaveAttribute('dir', 'ltr');
    // The reference number is the one Latin string outside `Num`, and it needs the same.
    expect(screen.getByText('FRU-000000001').closest('bdi')).toHaveAttribute('dir', 'ltr');
  });

  // --- Actions ------------------------------------------------------------------------------

  it('hides every action button from a viewer, regardless of status', async () => {
    stubReferenceFetch();
    mockAuthState.current = { status: 'authenticated', username: 'v1', displayName: 'V', role: 'viewer' };
    getProfileMock.mockResolvedValue(fullDetail());

    renderPage();
    await awaitLoaded();

    expect(screen.queryByRole('button', { name: 'اعتماد' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'رفض' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'طباعة' })).not.toBeInTheDocument();
  });

  it('shows every action to an admin, exactly as to an operator (AD-013)', async () => {
    stubReferenceFetch();
    mockAuthState.current = { status: 'authenticated', username: 'a1', displayName: 'A', role: 'admin' };
    getProfileMock.mockResolvedValue(fullDetail());

    renderPage();
    await awaitLoaded();

    expect(screen.getByRole('button', { name: 'اعتماد' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'رفض' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'طباعة' })).toBeInTheDocument();
  });

  it('offers an operator no action at all on a status that is neither reviewable nor printable', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail({ status: 'abandoned' }));

    renderPage();
    await awaitLoaded();

    expect(screen.queryByRole('button', { name: 'اعتماد' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'رفض' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'طباعة' })).not.toBeInTheDocument();
  });

  it('shows Approve and withholds Reject for a rejected profile (re-approval)', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail({ status: 'rejected' }));

    renderPage();
    await awaitLoaded();

    expect(screen.getByRole('button', { name: 'اعتماد' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'رفض' })).not.toBeInTheDocument();
  });

  it('approves and reloads, telling the operator the customer will be notified', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    approveProfileMock.mockResolvedValueOnce({ profileId: 'p1', status: 'approved', alreadyDone: false, notifiedChannels: ['sms'] });
    const user = userEvent.setup();

    renderPage();
    await awaitLoaded();

    await user.click(screen.getByRole('button', { name: 'اعتماد' }));

    await waitFor(() => expect(approveProfileMock).toHaveBeenCalledWith('p1'));
    await waitFor(() => expect(getProfileMock).toHaveBeenCalledTimes(2));
    expect(await screen.findByText(/تم اعتماد الملف/)).toBeInTheDocument();
  });

  it('still reloads on an idempotent alreadyDone approve outcome', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    approveProfileMock.mockResolvedValueOnce({ profileId: 'p1', status: 'approved', alreadyDone: true, notifiedChannels: [] });
    const user = userEvent.setup();

    renderPage();
    await awaitLoaded();

    await user.click(screen.getByRole('button', { name: 'اعتماد' }));

    expect(await screen.findByText('الملف معتمد بالفعل.')).toBeInTheDocument();
    await waitFor(() => expect(getProfileMock).toHaveBeenCalledTimes(2));
  });

  it('does not reload when approveProfile fails', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    approveProfileMock.mockRejectedValueOnce(new ApiError(409, 'conflict'));
    const user = userEvent.setup();

    renderPage();
    await awaitLoaded();

    await user.click(screen.getByRole('button', { name: 'اعتماد' }));

    expect(await screen.findByText('تعذر اعتماد الملف (409)')).toBeInTheDocument();
    expect(getProfileMock).toHaveBeenCalledTimes(1);
  });

  it('rejects through the modal with a valid reason code, then closes and reloads', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    rejectProfileMock.mockResolvedValueOnce({ profileId: 'p1', status: 'rejected', alreadyDone: false, notifiedChannels: ['sms'] });
    const user = userEvent.setup();

    renderPage();
    await awaitLoaded();

    await user.click(screen.getByRole('button', { name: 'رفض' }));
    await user.click(screen.getByRole('combobox'));
    await user.click(await screen.findByTitle('REJ-01 — صور غير واضحة'));
    // Two «رفض» buttons exist -- the action bar's and the modal's OK; the modal's is last.
    const rejectButtons = screen.getAllByRole('button', { name: 'رفض' });
    await user.click(rejectButtons[rejectButtons.length - 1]);

    await waitFor(() => expect(rejectProfileMock).toHaveBeenCalledWith('p1', { reasonCode: 'REJ-01', internalNote: null }));
    await waitFor(() => expect(getProfileMock).toHaveBeenCalledTimes(2));
  });

  it('does not reload and does not close the reject modal when rejectProfile fails', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    rejectProfileMock.mockRejectedValueOnce(new ApiError(500, 'boom'));
    const user = userEvent.setup();

    renderPage();
    await awaitLoaded();

    await user.click(screen.getByRole('button', { name: 'رفض' }));
    await user.click(screen.getByRole('combobox'));
    await user.click(await screen.findByTitle('REJ-01 — صور غير واضحة'));
    const rejectButtons = screen.getAllByRole('button', { name: 'رفض' });
    await user.click(rejectButtons[rejectButtons.length - 1]);

    expect(await screen.findByText('تعذر رفض الملف (500)')).toBeInTheDocument();
    expect(getProfileMock).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  // --- Printing ------------------------------------------------------------------------------

  it('offers print on a submitted profile and posts both answers as one request', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    printProfileFormMock.mockResolvedValue({
      blob: new Blob([new Uint8Array([0x25, 0x50, 0x44, 0x46])], { type: 'application/pdf' }),
      artifactId: 'aaaaaaaa-0000-4000-8000-000000000001',
    });
    const openSpy = vi.fn(() => ({ opener: {} }) as unknown as Window);
    const revokeSpy = vi.fn();
    vi.stubGlobal('open', openSpy);
    vi.stubGlobal('URL', { ...URL, createObjectURL: () => 'blob:print', revokeObjectURL: revokeSpy });
    const user = userEvent.setup();

    renderPage();
    await user.click(await screen.findByRole('button', { name: 'طباعة' }));

    // ONE interaction, both questions (ticket 05 decision 9). Scoped to the dialog: the action
    // bar's own print button carries the same word.
    const dialog = within(await screen.findByRole('dialog'));
    await user.click(dialog.getByRole('checkbox', { name: /طباعة المرفقات مع الاستمارة/ }));
    await user.click(dialog.getByRole('button', { name: 'طباعة' }));

    await waitFor(() => expect(printProfileFormMock).toHaveBeenCalledWith('p1', { includeAttachments: true }));
    // NO `noopener`, asserted rather than left to a comment. The HTML standard returns null from
    // window.open whenever that token is present, whether or not the tab opened, so passing it
    // sends EVERY successful print down the popup-blocked path -- minting another stored copy of
    // the customer's whole record each time.
    await waitFor(() => expect(openSpy).toHaveBeenCalledWith('blob:print', '_blank'));
    // And the URL survives long enough for the tab to load it. Revoking in the same tick is the
    // other half of that defect, and an assertion on open() alone would miss it.
    expect(revokeSpy).not.toHaveBeenCalled();
    expect(await screen.findByText('تمت الطباعة وحُفظت نسخة في سجل الملف')).toBeInTheDocument();
  });

  it('says the copy WAS saved when the browser blocks the new window', async () => {
    // The artifact is stored and the audit event written BEFORE the bytes are streamed, so a
    // blocked popup means the print happened and the operator simply cannot see it. Telling them
    // it failed would invite a reprint that stores a second copy of the whole record.
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    printProfileFormMock.mockResolvedValue({ blob: new Blob([]), artifactId: 'a-1' });
    const revokeSpy = vi.fn();
    vi.stubGlobal('open', vi.fn(() => null));
    vi.stubGlobal('URL', { ...URL, createObjectURL: () => 'blob:print', revokeObjectURL: revokeSpy });
    const user = userEvent.setup();

    renderPage();
    await user.click(await screen.findByRole('button', { name: 'طباعة' }));
    const dialog = within(await screen.findByRole('dialog'));
    await user.click(dialog.getByRole('button', { name: 'طباعة' }));

    expect(await screen.findByText(/تمت الطباعة وحُفظت نسخة، لكن المتصفح منع فتح النافذة/)).toBeInTheDocument();
    expect(revokeSpy).toHaveBeenCalled();
  });

  it('does not claim nothing was stored when it cannot know', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    printProfileFormMock.mockRejectedValue(new ApiError(500, 'boom'));
    const user = userEvent.setup();

    renderPage();
    await user.click(await screen.findByRole('button', { name: 'طباعة' }));
    const dialog = within(await screen.findByRole('dialog'));
    await user.click(dialog.getByRole('button', { name: 'طباعة' }));

    expect(await screen.findByText(/قد تكون نسخة قد حُفظت في سجل الملف/)).toBeInTheDocument();
  });

  it('tells a refused print apart from a wrong status, and says nothing was stored', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    printProfileFormMock.mockRejectedValueOnce(new ApiError(403, 'forbidden'));
    const user = userEvent.setup();

    renderPage();
    await user.click(await screen.findByRole('button', { name: 'طباعة' }));
    let dialog = within(await screen.findByRole('dialog'));
    await user.click(dialog.getByRole('button', { name: 'طباعة' }));
    expect(await screen.findByText(/تحقّق من صلاحيتك/)).toBeInTheDocument();

    printProfileFormMock.mockRejectedValueOnce(new ApiError(409, 'conflict'));
    dialog = within(await screen.findByRole('dialog'));
    // Wait for the first attempt to settle: `setPrinting(false)` runs in a `finally`, and
    // clicking while the button is still loading races it. Matched loosely because antd renames
    // the button "loading طباعة" for the duration, so an exact-name query matches nothing at all
    // while it is in flight -- which is a timeout, not a failed assertion.
    await waitFor(() => expect(dialog.getByRole('button', { name: /طباعة/ })).toBeEnabled());
    await user.click(dialog.getByRole('button', { name: /طباعة/ }));
    expect(await screen.findByText(/الطباعة متاحة للملفات المقدَّمة والمعتمدة فقط/)).toBeInTheDocument();
  });

  it('re-asks from the defaults the second time the dialog is opened on one page', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());
    printProfileFormMock.mockRejectedValue(new ApiError(500, 'boom'));
    const user = userEvent.setup();

    renderPage();
    await user.click(await screen.findByRole('button', { name: 'طباعة' }));
    let dialog = within(await screen.findByRole('dialog'));
    await user.click(dialog.getByRole('checkbox', { name: /طباعة المرفقات مع الاستمارة/ }));
    expect(dialog.getByRole('checkbox', { name: /طباعة المرفقات مع الاستمارة/ })).toBeChecked();
    await user.click(dialog.getByRole('button', { name: 'إلغاء' }));

    await user.click(screen.getByRole('button', { name: 'طباعة' }));
    dialog = within(await screen.findByRole('dialog'));
    // "Asked every time, defaulting to no" -- an operator who once opted in must not go on
    // printing customers' documents indefinitely without deciding to again.
    expect(dialog.getByRole('checkbox', { name: /طباعة المرفقات مع الاستمارة/ })).not.toBeChecked();
  });

  it('does not offer the attachments opt-in when the profile carries no artifacts', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail({ artifacts: [], salaryCertificateState: 'NOT_REACHED' }));
    const user = userEvent.setup();

    renderPage();
    await user.click(await screen.findByRole('button', { name: 'طباعة' }));

    const dialog = within(await screen.findByRole('dialog'));
    expect(dialog.queryByRole('checkbox', { name: /طباعة المرفقات مع الاستمارة/ })).not.toBeInTheDocument();
  });

  it('does not offer print on a profile that is neither submitted nor approved', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail({ status: 'rejected' }));

    renderPage();
    await awaitLoaded();

    expect(screen.queryByRole('button', { name: 'طباعة' })).not.toBeInTheDocument();
  });

  it('emits each section column-major, which is what makes the two-column grid match the artboard', async () => {
    stubReferenceFetch();
    getProfileMock.mockResolvedValue(fullDetail());

    renderPage();
    await awaitLoaded();

    // `FieldGrid` lays rows out row-major across exactly two tracks, so DOM order `a b c d`
    // renders as columns `[a, c]` / `[b, d]`. The artboard's section 1 columns are
    // [5, 6, 7] / [8, 9, 21], which is DOM order 5, 8, 6, 9, 7, 21. Pinned here because the
    // ordering looks arbitrary in the source and a well-meaning tidy into numeric order would
    // silently rearrange the screen -- jsdom computes no layout, so nothing else would catch it.
    const numbers = Array.from(document.querySelectorAll('span[aria-hidden="true"]')).map((el) => el.textContent);
    expect(numbers.slice(0, 6)).toEqual(['5', '8', '6', '9', '7', '21']);
    expect(numbers.slice(6, 12)).toEqual(['43', '46', '44', '47', '45', '48']);
  });

  it('offers print to an admin on a status where review is closed (AD-013)', async () => {
    stubReferenceFetch();
    mockAuthState.current = { status: 'authenticated', username: 'a1', displayName: 'A', role: 'admin' };
    getProfileMock.mockResolvedValue(fullDetail({ status: 'approved', editableFields: [] }));

    renderPage();
    await awaitLoaded();

    expect(screen.getByRole('button', { name: 'طباعة' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'رفض' })).not.toBeInTheDocument();
  });
});
