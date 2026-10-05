import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import dayjs from 'dayjs';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import ProfileListPage from './ProfileListPage';
import type { ProfileListResponse, ReferenceDocument, ReferenceManifest } from '../api/types';

const searchProfilesMock = vi.hoisted(() => vi.fn());

vi.mock('../api/profiles', () => ({
  searchProfiles: searchProfilesMock,
}));

const branchManifest: ReferenceManifest = {
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

const branchDocument: ReferenceDocument = {
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
};

const rejectionDocument: ReferenceDocument = {
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
};

function emptyPage(): ProfileListResponse {
  return { rows: [], total: 0 };
}

function stubReferenceFetch() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url.includes('/reference/manifest')) return new Response(JSON.stringify(branchManifest), { status: 200 });
      if (url.includes('/lists/branch/')) return new Response(JSON.stringify(branchDocument), { status: 200 });
      if (url.includes('/lists/rejection_reason/')) return new Response(JSON.stringify(rejectionDocument), { status: 200 });
      throw new Error(`unexpected fetch: ${url}`);
    }),
  );
}

describe('ProfileListPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    searchProfilesMock.mockReset();
  });

  it('renders rows from the server response with resolved status/provenance/branch labels', async () => {
    stubReferenceFetch();
    searchProfilesMock.mockResolvedValueOnce({
      rows: [
        {
          profileId: 'p1',
          accountNumber: '9000000001',
          branchCode: '16',
          displayNameAr: null,
          displayNameEn: null,
          status: 'submitted',
          provenance: 'manual',
          submittedAt: '2026-08-01T00:00:00Z',
          createdAt: '2026-07-30T00:00:00Z',
        },
      ],
      total: 1,
    });

    render(<MemoryRouter><ProfileListPage /></MemoryRouter>);

    await waitFor(() => expect(screen.getByText('9000000001')).toBeInTheDocument());
    expect(screen.getByText('الخرطوم')).toBeInTheDocument();
    expect(screen.getByText('مُقدَّم')).toBeInTheDocument();
    expect(screen.getByText('يدوي')).toBeInTheDocument();
    expect(screen.getByText('الإجمالي: 1')).toBeInTheDocument();
  });

  it('shows a dash for an incomplete profile with no name and no submittedAt', async () => {
    stubReferenceFetch();
    searchProfilesMock.mockResolvedValueOnce({
      rows: [
        {
          profileId: 'p2',
          accountNumber: '9000000002',
          branchCode: '16',
          displayNameAr: null,
          displayNameEn: null,
          status: 'in_progress',
          provenance: 'digital',
          submittedAt: null,
          createdAt: '2026-07-30T00:00:00Z',
        },
      ],
      total: 1,
    });

    render(<MemoryRouter><ProfileListPage /></MemoryRouter>);

    await waitFor(() => expect(screen.getByText('9000000002')).toBeInTheDocument());
    const dashes = screen.getAllByText('—');
    expect(dashes.length).toBeGreaterThanOrEqual(2); // name column + submittedAt column
  });

  it('sends the default sort (SUBMITTED_AT, DESC) on first load', async () => {
    stubReferenceFetch();
    searchProfilesMock.mockResolvedValueOnce(emptyPage());

    render(<MemoryRouter><ProfileListPage /></MemoryRouter>);

    await waitFor(() => expect(searchProfilesMock).toHaveBeenCalled());
    expect(searchProfilesMock).toHaveBeenCalledWith(
      expect.objectContaining({ sortField: 'SUBMITTED_AT', sortOrder: 'DESC', page: 1, pageSize: 10 }),
    );
  });

  it('re-queries the server when a filter changes, resetting to page 1', async () => {
    stubReferenceFetch();
    searchProfilesMock.mockResolvedValue(emptyPage());
    const user = userEvent.setup();

    render(<MemoryRouter><ProfileListPage /></MemoryRouter>);
    await waitFor(() => expect(searchProfilesMock).toHaveBeenCalledTimes(1));

    await user.click(screen.getAllByRole('combobox')[0]);
    await user.click(await screen.findByTitle('مُقدَّم'));

    await waitFor(() => expect(searchProfilesMock).toHaveBeenCalledTimes(2));
    expect(searchProfilesMock).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'submitted', page: 1 }));
  });

  it('debounces the free-text search box before querying the server', async () => {
    stubReferenceFetch();
    searchProfilesMock.mockResolvedValue(emptyPage());
    const user = userEvent.setup();

    render(<MemoryRouter><ProfileListPage /></MemoryRouter>);
    await waitFor(() => expect(searchProfilesMock).toHaveBeenCalledTimes(1));

    await user.type(screen.getByPlaceholderText('بحث في جميع الحقول'), 'test');

    // Not yet -- still within the debounce window.
    expect(searchProfilesMock).toHaveBeenCalledTimes(1);

    await waitFor(() => expect(searchProfilesMock).toHaveBeenCalledTimes(2), { timeout: 2000 });
    expect(searchProfilesMock).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'test' }));
  });

  it('re-queries when the branch filter changes', async () => {
    stubReferenceFetch();
    searchProfilesMock.mockResolvedValue(emptyPage());
    const user = userEvent.setup();

    render(<MemoryRouter><ProfileListPage /></MemoryRouter>);
    await waitFor(() => expect(searchProfilesMock).toHaveBeenCalledTimes(1));

    await user.click(screen.getAllByRole('combobox')[2]);
    await user.click(await screen.findByTitle('الخرطوم'));

    await waitFor(() => expect(searchProfilesMock).toHaveBeenCalledTimes(2));
    expect(searchProfilesMock).toHaveBeenLastCalledWith(expect.objectContaining({ branchCode: '16' }));
  });

  it('re-queries when the provenance filter changes', async () => {
    stubReferenceFetch();
    searchProfilesMock.mockResolvedValue(emptyPage());
    const user = userEvent.setup();

    render(<MemoryRouter><ProfileListPage /></MemoryRouter>);
    await waitFor(() => expect(searchProfilesMock).toHaveBeenCalledTimes(1));

    await user.click(screen.getAllByRole('combobox')[1]);
    await user.click(await screen.findByTitle('يدوي'));

    await waitFor(() => expect(searchProfilesMock).toHaveBeenCalledTimes(2));
    expect(searchProfilesMock).toHaveBeenLastCalledWith(expect.objectContaining({ provenance: 'manual', page: 1 }));
  });

  it('re-queries with submittedFrom/submittedTo when the date range changes, resetting to page 1', async () => {
    stubReferenceFetch();
    searchProfilesMock.mockResolvedValue(emptyPage());
    const user = userEvent.setup();

    const { container } = render(<MemoryRouter><ProfileListPage /></MemoryRouter>);
    await waitFor(() => expect(searchProfilesMock).toHaveBeenCalledTimes(1));

    const [startInput, endInput] = container.querySelectorAll<HTMLInputElement>('.ant-picker-input input');
    await user.click(startInput);
    await user.type(startInput, '2026-08-01{Enter}');
    await user.type(endInput, '2026-08-31{Enter}');

    // startOf('day')/endOf('day') resolve in the local timezone, same as the component under
    // test -- computed here rather than hardcoded as UTC, which this machine's timezone is not.
    await waitFor(() =>
      expect(searchProfilesMock).toHaveBeenLastCalledWith(
        expect.objectContaining({
          submittedFrom: dayjs('2026-08-01').startOf('day').toISOString(),
          submittedTo: dayjs('2026-08-31').endOf('day').toISOString(),
          page: 1,
        }),
      ),
    );
  });

  it('resets to page 1 once the debounced search text actually lands', async () => {
    stubReferenceFetch();
    searchProfilesMock.mockResolvedValue({ rows: [], total: 25 });
    const user = userEvent.setup();

    render(<MemoryRouter><ProfileListPage /></MemoryRouter>);
    await waitFor(() => expect(searchProfilesMock).toHaveBeenCalledTimes(1));

    await user.click(screen.getByTitle('2'));
    await waitFor(() => expect(searchProfilesMock).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 })));

    await user.type(screen.getByPlaceholderText('بحث في جميع الحقول'), 'test');

    await waitFor(() =>
      expect(searchProfilesMock).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'test', page: 1 })),
      { timeout: 2000 },
    );
  });

  it('re-queries when the rejection-reason filter changes', async () => {
    stubReferenceFetch();
    searchProfilesMock.mockResolvedValue(emptyPage());
    const user = userEvent.setup();

    render(<MemoryRouter><ProfileListPage /></MemoryRouter>);
    await waitFor(() => expect(searchProfilesMock).toHaveBeenCalledTimes(1));

    await user.click(screen.getAllByRole('combobox')[3]);
    await user.click(await screen.findByTitle('صور غير واضحة'));

    await waitFor(() => expect(searchProfilesMock).toHaveBeenCalledTimes(2));
    expect(searchProfilesMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ rejectionReasonCode: 'REJ-01' }),
    );
  });

  it('re-queries immediately (bypassing the debounce) when Enter is pressed in the search box', async () => {
    stubReferenceFetch();
    searchProfilesMock.mockResolvedValue(emptyPage());
    const user = userEvent.setup();

    render(<MemoryRouter><ProfileListPage /></MemoryRouter>);
    await waitFor(() => expect(searchProfilesMock).toHaveBeenCalledTimes(1));

    await user.type(screen.getByPlaceholderText('بحث في جميع الحقول'), 'خرطوم{Enter}');

    await waitFor(() => expect(searchProfilesMock).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'خرطوم' })));
  });

  it('re-sorts when a sortable column header is clicked', async () => {
    stubReferenceFetch();
    searchProfilesMock.mockResolvedValue(emptyPage());

    render(<MemoryRouter><ProfileListPage /></MemoryRouter>);
    await waitFor(() => expect(searchProfilesMock).toHaveBeenCalledTimes(1));

    // antd's Table wraps the header in an internal pointer-events:none rule under jsdom's
    // limited getComputedStyle -- fireEvent bypasses that CSS check, unlike userEvent.click.
    const header = screen.getByText('رقم الحساب').closest('th');
    if (!header) throw new Error('sortable column header <th> not found');
    fireEvent.click(header);

    await waitFor(() =>
      expect(searchProfilesMock).toHaveBeenLastCalledWith(
        expect.objectContaining({ sortField: 'ACCOUNT_NUMBER' }),
      ),
    );
  });

  it('requests the next page when pagination advances', async () => {
    stubReferenceFetch();
    searchProfilesMock.mockResolvedValue({ rows: [], total: 25 });
    const user = userEvent.setup();

    render(<MemoryRouter><ProfileListPage /></MemoryRouter>);
    await waitFor(() => expect(searchProfilesMock).toHaveBeenCalledTimes(1));

    await user.click(screen.getByTitle('2'));

    await waitFor(() => expect(searchProfilesMock).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 })));
  });

  it('shows an error message when the server call fails', async () => {
    stubReferenceFetch();
    const { ApiError } = await import('../api/http');
    searchProfilesMock.mockRejectedValueOnce(new ApiError(500, 'boom'));

    render(<MemoryRouter><ProfileListPage /></MemoryRouter>);

    await waitFor(() => expect(screen.getByText('تعذر تحميل القائمة (500)')).toBeInTheDocument());
  });

  it('navigates to the single profile view when a row is clicked', async () => {
    stubReferenceFetch();
    searchProfilesMock.mockResolvedValueOnce({
      rows: [
        {
          profileId: 'p-clicked',
          accountNumber: '9000000009',
          branchCode: '16',
          displayNameAr: null,
          displayNameEn: null,
          status: 'submitted',
          provenance: 'digital',
          submittedAt: null,
          createdAt: '2026-07-30T00:00:00Z',
        },
      ],
      total: 1,
    });
    const user = userEvent.setup();

    render(
      <MemoryRouter initialEntries={['/profiles']}>
        <Routes>
          <Route path="/profiles" element={<ProfileListPage />} />
          <Route path="/profiles/:profileId" element={<div>detail-screen</div>} />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText('9000000009')).toBeInTheDocument());
    await user.click(screen.getByText('9000000009'));

    expect(await screen.findByText('detail-screen')).toBeInTheDocument();
  });
});
