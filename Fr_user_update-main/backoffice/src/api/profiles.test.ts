import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  approveProfile,
  editProfileField,
  getProfile,
  printProfileForm,
  rejectProfile,
  searchProfiles,
} from './profiles';
import { ApiError } from './http';

describe('searchProfiles', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('sends every filter as a query parameter against /operator/profiles', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ rows: [], total: 0 }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await searchProfiles({
      status: 'submitted',
      q: 'خرطوم',
      sortField: 'SUBMITTED_AT',
      sortOrder: 'DESC',
      page: 2,
      pageSize: 10,
    });

    const [url] = fetchMock.mock.calls[0] as unknown as [string];
    expect(url).toContain('/api/v1/operator/profiles?');
    expect(url).toContain('status=submitted');
    expect(url).toContain('sortField=SUBMITTED_AT');
    expect(url).toContain('page=2');
    expect(url).toContain('pageSize=10');
  });

  it('returns the rows and total exactly as received', async () => {
    const payload = { rows: [{ profileId: '1' }], total: 1 };
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(payload), { status: 200 })));

    await expect(searchProfiles({})).resolves.toEqual(payload);
  });
});

describe('getProfile', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('GETs /operator/profiles/{id} and returns the body as-is', async () => {
    const payload = { profileId: 'p1', status: 'submitted' };
    const fetchMock = vi.fn(async () => new Response(JSON.stringify(payload), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(getProfile('p1')).resolves.toEqual(payload);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/api/v1/operator/profiles/p1');
    expect(init.method ?? 'GET').toBe('GET');
  });
});

describe('approveProfile', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('POSTs to /operator/profiles/{id}/approve with no body', async () => {
    const payload = { profileId: 'p1', status: 'approved', alreadyDone: false, notifiedChannels: ['sms'] };
    const fetchMock = vi.fn(async () => new Response(JSON.stringify(payload), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(approveProfile('p1')).resolves.toEqual(payload);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/api/v1/operator/profiles/p1/approve');
    expect(init.method).toBe('POST');
  });
});

describe('rejectProfile', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('POSTs the reason code and internal note to /operator/profiles/{id}/reject', async () => {
    const payload = { profileId: 'p1', status: 'rejected', alreadyDone: false, notifiedChannels: [] };
    const fetchMock = vi.fn(async () => new Response(JSON.stringify(payload), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(rejectProfile('p1', { reasonCode: 'REJ-01', internalNote: null })).resolves.toEqual(payload);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/api/v1/operator/profiles/p1/reject');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({ reasonCode: 'REJ-01', internalNote: null });
  });
});

describe('printProfileForm', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('POSTs both answers together and returns the bytes with the stored artifact id', async () => {
    // A POST, not a GET: printing WRITES -- it stores an artifact and appends an audit event in
    // one transaction. A GET an operator could bookmark, or a browser could prefetch, would mint
    // stored PII copies and audit rows on navigation.
    // Bytes, not a Blob: jsdom's Response stringifies a Blob body to "[object Blob]", which would
    // make a size assertion measure the wrong thing entirely.
    const pdf = new Uint8Array([0x25, 0x50, 0x44, 0x46]);
    const fetchMock = vi.fn(
      async () =>
        new Response(pdf, {
          status: 200,
          headers: {
            'Content-Type': 'application/pdf',
            'X-Printed-Form-Artifact-Id': '9f1c0f66-0000-4000-8000-000000000001',
          },
        }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const printed = await printProfileForm('p-1', { includeAttachments: true });

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/api/v1/operator/profiles/p-1/print');
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body))).toEqual({ includeAttachments: true });
    expect(printed.artifactId).toBe('9f1c0f66-0000-4000-8000-000000000001');
    expect(printed.blob.size).toBe(4);
  });

  it('carries the CSRF token, which an unsafe method on this chain cannot omit', async () => {
    // `SecurityConfiguration`'s `csrf(CsrfConfigurer::spa)` refuses every POST that does not echo
    // the XSRF-TOKEN cookie back as a header, with a bodyless 403 -- so a blob fetch that quietly
    // dropped the header would fail only at runtime, and only for a real session.
    document.cookie = 'XSRF-TOKEN=token-for-the-print';
    const fetchMock = vi.fn(async () => new Response(new Blob([]), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await printProfileForm('p-1', { includeAttachments: false });

    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('token-for-the-print');
    expect(init.credentials).toBe('same-origin');
  });

  it('throws with the status so the page can tell a refusal from a wrong status', async () => {
    // 403 and 409 mean different things to an operator -- "you may not print" versus "this profile
    // may not be printed" -- and a page that could not tell them apart would say the wrong one.
    vi.stubGlobal('fetch', vi.fn(async () => new Response('profile is in_progress', { status: 409 })));

    await expect(
      printProfileForm('p-1', { includeAttachments: false }),
    ).rejects.toMatchObject({ status: 409 });
  });
});

describe('editProfileField', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('PATCHes the field addressed by the URL, with the value as the whole body', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(JSON.stringify({ fieldKey: 'HOME_STREET', value: 'شارع الأربعين' }), { status: 200 }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await editProfileField('p-1', 'HOME_STREET', 'شارع الأربعين');

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    // The FIELD is the path, not the body -- a request can then never carry a field key that
    // disagrees with where it was sent.
    expect(url).toBe('/api/v1/operator/profiles/p-1/fields/HOME_STREET');
    expect(init.method).toBe('PATCH');
    expect(JSON.parse(init.body as string)).toEqual({ value: 'شارع الأربعين' });
  });

  it('returns the value as STORED, not as submitted', async () => {
    // The server trims; a client rendering what was typed would show a value the database does
    // not hold.
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify({ fieldKey: 'HOME_CITY', value: 'بحري' }), { status: 200 })),
    );

    await expect(editProfileField('p-1', 'HOME_CITY', '  بحري  ')).resolves.toEqual({
      fieldKey: 'HOME_CITY',
      value: 'بحري',
    });
  });

  it('escapes the field key rather than interpolating it raw', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ fieldKey: 'x', value: 'y' }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await editProfileField('p-1', 'A/B?c', 'v');

    const [url] = fetchMock.mock.calls[0] as unknown as [string];
    // The key comes from a server-supplied list in practice, so this is defence in depth -- but
    // an unescaped `/` would silently address a different route.
    expect(url).toBe('/api/v1/operator/profiles/p-1/fields/A%2FB%3Fc');
  });

  it('surfaces a 409 as an ApiError the caller can tell apart', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('not editable', { status: 409 })));

    // The screen distinguishes 409 (stale chip, reload) from 400 (bad value, do not reload) and
    // 403 (viewer), so the status has to survive the transport.
    await expect(editProfileField('p-1', 'HOME_AREA', 'v')).rejects.toBeInstanceOf(ApiError);
    await expect(editProfileField('p-1', 'HOME_AREA', 'v')).rejects.toMatchObject({ status: 409 });
  });
});
