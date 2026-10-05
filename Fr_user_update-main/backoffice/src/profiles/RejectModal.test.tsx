import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RejectModal from './RejectModal';
import type { ReferenceDocument, ReferenceManifest } from '../api/types';

const manifest: ReferenceManifest = {
  catalogHash: 'h',
  generatedAt: '2026-01-01T00:00:00Z',
  verifiableChannels: [],
  lists: [
    {
      listCode: 'rejection_reason',
      version: 1,
      itemCount: 2,
      contentHash: 'c',
      isHierarchical: false,
      rootItemCode: null,
      rootCountryVersion: null,
      publishedAt: '2026-01-01T00:00:00Z',
      documentPath: '',
    },
  ],
};

const document: ReferenceDocument = {
  listCode: 'rejection_reason',
  version: 1,
  itemCount: 2,
  nameAr: 'أسباب الرفض',
  nameEn: 'Rejection reasons',
  isHierarchical: false,
  rootItemCode: null,
  items: [
    {
      itemCode: 'REJ-01',
      parentCode: null,
      labelAr: 'صور غير واضحة',
      labelEn: null,
      searchAr: null,
      searchEn: null,
      sortOrdinal: 1,
      isActive: true,
      extra: { customerMessageAr: 'صور مستنداتك لم تكن واضحة بما يكفي.' },
    },
    {
      itemCode: 'REJ-07',
      parentCode: null,
      labelAr: 'أخرى',
      labelEn: null,
      searchAr: null,
      searchEn: null,
      sortOrdinal: 7,
      isActive: true,
      extra: null,
    },
    // BL-154. ReferenceDocumentPublisher emits is_active=false rows DELIBERATELY -- a client needs
    // the row to resolve a label for a value some profile was already rejected under -- so the
    // withdrawn REJ-03 really does arrive here, and filtering it out is this component's own job.
    {
      itemCode: 'REJ-03',
      parentCode: null,
      labelAr: 'فشل أو عدم وضوح مطابقة الوجه',
      labelEn: null,
      searchAr: null,
      searchEn: null,
      sortOrdinal: 3,
      isActive: false,
      extra: null,
    },
  ],
};

function stubReferenceFetch() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url.includes('/reference/manifest')) return new Response(JSON.stringify(manifest), { status: 200 });
      if (url.includes('/lists/rejection_reason/')) return new Response(JSON.stringify(document), { status: 200 });
      throw new Error(`unexpected fetch: ${url}`);
    }),
  );
}

describe('RejectModal', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('submits the selected reason code with no internal note when none is entered', async () => {
    stubReferenceFetch();
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    render(<RejectModal open submitting={false} onCancel={vi.fn()} onSubmit={onSubmit} />);

    await user.click(screen.getByRole('combobox'));
    await user.click(await screen.findByTitle('REJ-01 — صور غير واضحة'));
    await user.click(screen.getByRole('button', { name: 'رفض' }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith('REJ-01', null));
  });

  /**
   * BL-154. The server refuses a withdrawn code with a 400 (`ReferenceCatalog.exists()` filters
   * `is_active`), so offering it here would put an operator through the whole reject flow only to
   * be refused at submit — for a reason they cannot see the evidence for either, since AD-022
   * ruling 3 removed the face-match display.
   */
  it('does not offer a withdrawn reason, while still offering the live ones', async () => {
    stubReferenceFetch();
    const user = userEvent.setup();
    render(<RejectModal open submitting={false} onCancel={vi.fn()} onSubmit={vi.fn()} />);

    await user.click(screen.getByRole('combobox'));

    expect(await screen.findByTitle('REJ-01 — صور غير واضحة')).toBeInTheDocument();
    expect(screen.getByTitle('REJ-07 — أخرى')).toBeInTheDocument();
    expect(screen.queryByTitle(/REJ-03/)).not.toBeInTheDocument();
    expect(screen.queryByText('فشل أو عدم وضوح مطابقة الوجه')).not.toBeInTheDocument();
  });

  it('shows the derived customer-facing message once a reason with one is selected', async () => {
    stubReferenceFetch();
    const user = userEvent.setup();
    render(<RejectModal open submitting={false} onCancel={vi.fn()} onSubmit={vi.fn()} />);

    await user.click(screen.getByRole('combobox'));
    await user.click(await screen.findByTitle('REJ-01 — صور غير واضحة'));

    expect(await screen.findByText('صور مستنداتك لم تكن واضحة بما يكفي.')).toBeInTheDocument();
  });

  it('refuses to submit REJ-07 with no internal note, and never calls onSubmit', async () => {
    stubReferenceFetch();
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    render(<RejectModal open submitting={false} onCancel={vi.fn()} onSubmit={onSubmit} />);

    await user.click(screen.getByRole('combobox'));
    await user.click(await screen.findByTitle('REJ-07 — أخرى'));
    await user.click(screen.getByRole('button', { name: 'رفض' }));

    expect(await screen.findByText(/يتطلب تفصيلًا داخليًا إلزاميًا/)).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('submits REJ-07 once an internal note is provided', async () => {
    stubReferenceFetch();
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    render(<RejectModal open submitting={false} onCancel={vi.fn()} onSubmit={onSubmit} />);

    await user.click(screen.getByRole('combobox'));
    await user.click(await screen.findByTitle('REJ-07 — أخرى'));
    await user.type(screen.getByPlaceholderText('إلزامي لهذا السبب'), 'تفصيل داخلي');
    await user.click(screen.getByRole('button', { name: 'رفض' }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith('REJ-07', 'تفصيل داخلي'));
  });

  it('refuses to submit with no reason selected at all -- free text alone is never a path', async () => {
    stubReferenceFetch();
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    render(<RejectModal open submitting={false} onCancel={vi.fn()} onSubmit={onSubmit} />);

    await user.click(screen.getByRole('button', { name: 'رفض' }));

    expect(await screen.findByText(/لا يُقبل نص حر بمفرده/)).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('calls onCancel when Cancel is clicked', async () => {
    stubReferenceFetch();
    const onCancel = vi.fn();
    const user = userEvent.setup();
    render(<RejectModal open submitting={false} onCancel={onCancel} onSubmit={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: 'إلغاء' }));

    expect(onCancel).toHaveBeenCalled();
  });
});
