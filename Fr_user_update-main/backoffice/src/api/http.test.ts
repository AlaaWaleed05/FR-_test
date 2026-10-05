import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiFetch, ApiError, setUnauthorizedHandler } from './http';

describe('apiFetch', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
    setUnauthorizedHandler(null);
  });

  it('sends a plain GET with no body and no CSRF header', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ ok: true }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    const result = await apiFetch<{ ok: boolean }>('/whatever');

    expect(result).toEqual({ ok: true });
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(init.method).toBe('GET');
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBeUndefined();
  });

  it('attaches the X-XSRF-TOKEN header from the cookie on an unsafe method', async () => {
    document.cookie = 'XSRF-TOKEN=abc123';
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await apiFetch('/thing', { method: 'POST', body: { a: 1 } });

    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('abc123');
    expect(init.body).toBe(JSON.stringify({ a: 1 }));
  });

  it('sends formBody as application/x-www-form-urlencoded, not JSON', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({}), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await apiFetch('/auth/login', { method: 'POST', formBody: { username: 'a', password: 'b' } });

    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/x-www-form-urlencoded');
    expect(init.body).toBe('username=a&password=b');
  });

  it('builds a query string, omitting undefined/null/empty values', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({}), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await apiFetch('/search', { query: { a: 1, b: undefined, c: null, d: '', e: 'x' } });

    const [url] = fetchMock.mock.calls[0] as unknown as [string];
    expect(url).toBe('/api/v1/search?a=1&e=x');
  });

  it('throws ApiError with the response status on a non-2xx response', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('nope', { status: 400 })));

    await expect(apiFetch('/fails')).rejects.toMatchObject({ status: 400 });
  });

  it('invokes the unauthorized handler on a 401, unless suppressed', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 401 })));
    const handler = vi.fn();
    setUnauthorizedHandler(handler);

    await expect(apiFetch('/secure')).rejects.toBeInstanceOf(ApiError);
    expect(handler).toHaveBeenCalledTimes(1);

    handler.mockClear();
    await expect(apiFetch('/secure', { suppressUnauthorizedHandler: true })).rejects.toBeInstanceOf(ApiError);
    expect(handler).not.toHaveBeenCalled();
  });

  it('returns undefined for a 204 response without attempting to parse a body', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 204 })));

    await expect(apiFetch('/no-content')).resolves.toBeUndefined();
  });
});
