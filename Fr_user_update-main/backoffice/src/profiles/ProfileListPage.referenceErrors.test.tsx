import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import ProfileListPage from './ProfileListPage';
import type { ProfileListResponse, ReferenceManifest } from '../api/types';

// A dedicated file, not a case inside ProfileListPage.test.tsx: reference.ts memoizes the
// manifest/document promises at module scope, and every test in that file already resolves that
// cache successfully -- sharing a file would let an earlier test's cache silently hide the 500
// this test depends on. A separate file gets its own fresh module registry from Vitest for free,
// with no vi.resetModules()/dynamic-import gymnastics needed (an earlier attempt at that within
// the shared file produced a duplicate-React-instance failure in the tests that ran after it).

const searchProfilesMock = vi.hoisted(() => vi.fn());

vi.mock('../api/profiles', () => ({
  searchProfiles: searchProfilesMock,
}));

const WARNING_TEXT =
  'تعذر تحميل قوائم الفروع أو أسباب الرفض. القائمة نفسها لا تزال تعمل، لكن هذه المرشّحات معطّلة مؤقتًا.';

const manifest: ReferenceManifest = {
  catalogHash: 'h',
  generatedAt: '2026-01-01T00:00:00Z',
  verifiableChannels: [],
  lists: [
    {
      listCode: 'branch',
      version: 1,
      itemCount: 1,
      contentHash: 'c',
      isHierarchical: false,
      rootItemCode: null,
      rootCountryVersion: null,
      publishedAt: '2026-01-01T00:00:00Z',
      documentPath: '',
    },
    {
      listCode: 'rejection_reason',
      version: 1,
      itemCount: 1,
      contentHash: 'c',
      isHierarchical: false,
      rootItemCode: null,
      rootCountryVersion: null,
      publishedAt: '2026-01-01T00:00:00Z',
      documentPath: '',
    },
  ],
};

function emptyPage(): ProfileListResponse {
  return { rows: [], total: 0 };
}

describe('ProfileListPage reference-list failures', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    searchProfilesMock.mockReset();
  });

  it('surfaces a warning, with a working retry, when the branch/rejection-reason reference lists fail to load', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (url.includes('/reference/manifest')) return new Response('boom', { status: 500 });
        throw new Error(`unexpected fetch: ${url}`);
      }),
    );
    searchProfilesMock.mockResolvedValue(emptyPage());
    const user = userEvent.setup();

    render(<MemoryRouter><ProfileListPage /></MemoryRouter>);

    expect(await screen.findByText(WARNING_TEXT)).toBeInTheDocument();

    // A retry after the backend recovers must actually succeed -- proves the failure is no
    // longer memoized forever (the bug this behaviour was added to close).
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (url.includes('/reference/manifest')) return new Response(JSON.stringify(manifest), { status: 200 });
        if (url.includes('/lists/branch/')) {
          return new Response(
            JSON.stringify({
              listCode: 'branch',
              version: 1,
              itemCount: 1,
              nameAr: 'الفروع',
              nameEn: 'Branches',
              isHierarchical: false,
              rootItemCode: null,
              items: [
                { itemCode: '16', parentCode: null, labelAr: 'الخرطوم', labelEn: null, searchAr: null, searchEn: null, sortOrdinal: 1, isActive: true, extra: null },
              ],
            }),
            { status: 200 },
          );
        }
        if (url.includes('/lists/rejection_reason/')) {
          return new Response(
            JSON.stringify({
              listCode: 'rejection_reason',
              version: 1,
              itemCount: 1,
              nameAr: 'أسباب الرفض',
              nameEn: 'Rejection reasons',
              isHierarchical: false,
              rootItemCode: null,
              items: [
                { itemCode: 'REJ-01', parentCode: null, labelAr: 'صور غير واضحة', labelEn: null, searchAr: null, searchEn: null, sortOrdinal: 1, isActive: true, extra: null },
              ],
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
