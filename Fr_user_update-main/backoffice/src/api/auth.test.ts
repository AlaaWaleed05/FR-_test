import { afterEach, describe, expect, it, vi } from 'vitest';
import { changePassword, fetchMe, login, logout } from './auth';
import { setUnauthorizedHandler } from './http';

function stubFetch(status: number, body: unknown = null) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response(body === null ? null : JSON.stringify(body), { status })),
  );
}

describe('fetchMe', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('maps 200 to authenticated', async () => {
    stubFetch(200, { username: 'u', displayName: 'د', role: 'viewer', mustChangePassword: false });
    const outcome = await fetchMe();
    expect(outcome).toEqual({
      kind: 'authenticated',
      me: { username: 'u', displayName: 'د', role: 'viewer', mustChangePassword: false },
    });
  });

  it('maps 401 to anonymous', async () => {
    stubFetch(401);
    await expect(fetchMe()).resolves.toEqual({ kind: 'anonymous' });
  });

  it('maps 403 to must-change-password', async () => {
    stubFetch(403);
    await expect(fetchMe()).resolves.toEqual({ kind: 'must-change-password' });
  });

  it('rethrows any other status', async () => {
    stubFetch(500);
    await expect(fetchMe()).rejects.toMatchObject({ status: 500 });
  });
});

describe('login', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('posts form-encoded credentials, not JSON', async () => {
    const fetchMock = vi.fn(
      async () => new Response(JSON.stringify({ mustChangePassword: false, role: 'viewer' }), { status: 200 }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const result = await login('alice', 'secret');

    expect(result).toEqual({ mustChangePassword: false, role: 'viewer' });
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/x-www-form-urlencoded');
    expect(init.body).toBe('username=alice&password=secret');
  });
});

describe('logout and changePassword', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('logout posts to /auth/logout and suppresses the unauthorized handler', async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await logout();

    const [url] = fetchMock.mock.calls[0] as unknown as [string];
    expect(url).toBe('/api/v1/auth/logout');
  });

  it('changePassword posts current and new password as JSON', async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await changePassword('old', 'new');

    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(init.body).toBe(JSON.stringify({ currentPassword: 'old', newPassword: 'new' }));
  });

  it('a wrong-current-password 401 from changePassword does NOT trip the global session-lost handler', async () => {
    // The session is still fully valid on this 401 (auth.web.PasswordChangeController ->
    // IncorrectCurrentPasswordException) -- unlike a stale/expired session, it must not drop the
    // app back to 'anonymous' and bounce the operator to /login before they ever see the error.
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 401 })));
    const unauthorizedHandler = vi.fn();
    setUnauthorizedHandler(unauthorizedHandler);
    try {
      await expect(changePassword('wrong', 'new')).rejects.toMatchObject({ status: 401 });
      expect(unauthorizedHandler).not.toHaveBeenCalled();
    } finally {
      setUnauthorizedHandler(null);
    }
  });
});
