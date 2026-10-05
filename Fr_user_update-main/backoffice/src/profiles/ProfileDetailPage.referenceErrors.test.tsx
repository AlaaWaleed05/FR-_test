import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import ProfileDetailPage from './ProfileDetailPage';
import type { AuthState } from '../auth/AuthContext';
import type { ProfileDetailResponse, ReferenceManifest } from '../api/types';

// A dedicated file, not a case inside ProfileDetailPage.test.tsx: reference.ts memoizes the
// manifest/document promises at module scope, so an earlier test's successful fetch would hide
// the failure this test depends on -- same reasoning as ProfileListPage.referenceErrors.test.tsx,
// which this file otherwise mirrors.

const getProfileMock = vi.hoisted(() => vi.fn());
vi.mock('../api/profiles', () => ({
  getProfile: getProfileMock,
  approveProfile: vi.fn(),
  rejectProfile: vi.fn(),
  // Both were missing. A factory that omits an export the module under test imports fails at
  // IMPORT time, not at assertion time, so the omission is invisible until the page grows an
  // import -- which is exactly what S9-02's `editProfileField` did.
  printProfileForm: vi.fn(),
  editProfileField: vi.fn(),
}));

const mockAuthState = vi.hoisted(() => ({
  current: { status: 'authenticated', username: 'v1', displayName: 'V', role: 'viewer' } as AuthState,
}));
vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ state: mockAuthState.current, login: vi.fn(), logout: vi.fn(), refresh: vi.fn() }),
}));

const WARNING_TEXT = /تعذر تحميل بعض قوائم البيانات المرجعية/;

const LIST_CODES = ['branch', 'occupation', 'admin_division', 'country', 'income_source', 'education_level'];

const manifest: ReferenceManifest = {
  catalogHash: 'h',
  generatedAt: '2026-01-01T00:00:00Z',
  verifiableChannels: [],
  lists: LIST_CODES.map((listCode) => ({
    listCode,
    version: 1,
    itemCount: 0,
    contentHash: 'c',
    isHierarchical: false,
    rootItemCode: null,
    rootCountryVersion: null,
    publishedAt: '2026-01-01T00:00:00Z',
    documentPath: '',
  })),
};

function minimalDetail(): ProfileDetailResponse {
  return {
    profileId: 'p1',
    referenceNumber: 'FRU-000000001',
    branchCode: '16',
    accountNumber: '9000000001',
    status: 'in_progress',
    provenance: 'digital',
    submittedAt: null,
    createdAt: '2026-07-30T00:00:00Z',
    lastActivityAt: '2026-07-30T00:00:00Z',
    customerData: {
      phoneNumber: null,
      emailAddress: null,
      sexDeclared: null,
      maritalStatus: null,
      spouseName: null,
      hasChildren: null,
      childrenCount: null,
      educationLevel: null,
      occupationCode: null,
      occupationVersion: null,
      monthlyExpensesSdg: null,
      identityType: null,
      ethnicity: null,
      countryOfResidenceCode: null,
      birthCountryCode: null,
      birthStateCode: null,
      birthStateText: null,
      birthCityText: null,
      homeCountryCode: null,
      homeStateCode: null,
      homeLocalityCode: null,
      homeStateText: null,
      homeLocalityText: null,
      homeCity: null,
      homeArea: null,
      homeStreet: null,
      homeBlock: null,
      homeHouseNo: null,
      employerName: null,
      workCountryCode: null,
      workStateCode: null,
      workLocalityCode: null,
      workStateText: null,
      workLocalityText: null,
      workCity: null,
      workArea: null,
      workStreet: null,
      workBlock: null,
      incomeSources: [],
    },
    channels: [],
    scanResult: null,
    faceResult: null,
    registryResult: null,
    artifacts: [],
    statusHistory: [],
    salaryCertificateState: 'NOT_REACHED',
    editableFields: [],
  };
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/profiles/p1']}>
      <Routes>
        <Route path="/profiles/:profileId" element={<ProfileDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('ProfileDetailPage reference-list failures', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    getProfileMock.mockReset();
  });

  it('surfaces a warning, with a working retry, when the reference manifest fails to load', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (url.includes('/reference/manifest')) return new Response('boom', { status: 500 });
        throw new Error(`unexpected fetch: ${url}`);
      }),
    );
    getProfileMock.mockResolvedValue(minimalDetail());
    const user = userEvent.setup();

    renderPage();
    await screen.findByText('FRU-000000001');

    expect(await screen.findByText(WARNING_TEXT)).toBeInTheDocument();

    // A retry after the backend recovers must actually succeed -- proves the failure is no
    // longer memoized forever.
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (url.includes('/reference/manifest')) return new Response(JSON.stringify(manifest), { status: 200 });
        const match = /\/lists\/([^/]+)\//.exec(url);
        if (match) {
          return new Response(
            JSON.stringify({
              listCode: match[1],
              version: 1,
              itemCount: 0,
              nameAr: match[1],
              nameEn: match[1],
              isHierarchical: false,
              rootItemCode: null,
              items: [],
            }),
            { status: 200 },
          );
        }
        throw new Error(`unexpected fetch: ${url}`);
      }),
    );
    await user.click(screen.getByRole('button', { name: 'إعادة المحاولة' }));

    await waitFor(() => expect(screen.queryByText(WARNING_TEXT)).not.toBeInTheDocument());
  });
});
