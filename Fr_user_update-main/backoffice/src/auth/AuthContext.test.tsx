import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { AuthProvider, useAuth } from './AuthContext';

function stubFetchSequence(responses: Array<{ status: number; body?: unknown }>) {
  const fetchMock = vi.fn();
  for (const { status, body } of responses) {
    fetchMock.mockImplementationOnce(async () => new Response(body ? JSON.stringify(body) : null, { status }));
  }
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function Probe(): React.JSX.Element {
  const { state } = useAuth();
  if (state.status === 'authenticated') {
    return <div>authenticated:{state.role}:{state.displayName}</div>;
  }
  return <div>{state.status}</div>;
}

describe('AuthProvider', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('starts loading, then resolves to anonymous on a 401 /me', async () => {
    stubFetchSequence([{ status: 401 }]);
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByText('anonymous')).toBeInTheDocument());
  });

  it('resolves to must-change-password on a 403 /me', async () => {
    stubFetchSequence([{ status: 403 }]);
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByText('must-change-password')).toBeInTheDocument());
  });

  it('resolves to authenticated on a 200 /me', async () => {
    stubFetchSequence([
      { status: 200, body: { username: 'u', displayName: 'ديزي', role: 'viewer', mustChangePassword: false } },
    ]);
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByText('authenticated:viewer:ديزي')).toBeInTheDocument());
  });

  it('a non-401/403 /me failure resolves to an explicit error state, not a permanent spinner', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('boom', { status: 500 })));
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByText('error')).toBeInTheDocument());
  });

  it('refresh() from the error state can recover to authenticated', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('boom', { status: 500 })));
    let contextValue!: ReturnType<typeof useAuth>;
    function Capture(): React.JSX.Element {
      contextValue = useAuth();
      return <Probe />;
    }
    render(
      <AuthProvider>
        <Capture />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByText('error')).toBeInTheDocument());

    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(JSON.stringify({ username: 'u', displayName: 'د', role: 'viewer', mustChangePassword: false }), {
            status: 200,
          }),
      ),
    );
    await act(async () => {
      await contextValue.refresh();
    });

    await waitFor(() => expect(screen.getByText('authenticated:viewer:د')).toBeInTheDocument());
  });

  it('logout() still clears local state to anonymous even when the server call fails', async () => {
    stubFetchSequence([
      { status: 200, body: { username: 'u', displayName: 'د', role: 'viewer', mustChangePassword: false } },
    ]);
    let contextValue!: ReturnType<typeof useAuth>;
    function Capture(): React.JSX.Element {
      contextValue = useAuth();
      return <Probe />;
    }
    render(
      <AuthProvider>
        <Capture />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByText(/^authenticated/)).toBeInTheDocument());

    vi.stubGlobal('fetch', vi.fn(async () => new Response('boom', { status: 500 })));
    await act(async () => {
      await expect(contextValue.logout()).rejects.toBeTruthy();
    });

    await waitFor(() => expect(screen.getByText('anonymous')).toBeInTheDocument());
  });

  it('a 401 discovered by ANY later call drops the session back to anonymous', async () => {
    const fetchMock = stubFetchSequence([
      { status: 200, body: { username: 'u', displayName: 'د', role: 'viewer', mustChangePassword: false } },
    ]);
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByText(/^authenticated/)).toBeInTheDocument());

    fetchMock.mockImplementationOnce(async () => new Response(null, { status: 401 }));
    const { apiFetch } = await import('../api/http');
    await act(async () => {
      await expect(apiFetch('/operator/profiles')).rejects.toBeTruthy();
    });

    await waitFor(() => expect(screen.getByText('anonymous')).toBeInTheDocument());
  });

  it('login() with mustChangePassword:true sets the must-change-password state directly', async () => {
    stubFetchSequence([{ status: 401 }]);
    let contextValue!: ReturnType<typeof useAuth>;
    function Capture(): React.JSX.Element {
      contextValue = useAuth();
      return <Probe />;
    }
    render(
      <AuthProvider>
        <Capture />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByText('anonymous')).toBeInTheDocument());

    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify({ mustChangePassword: true, role: 'viewer' }), { status: 200 })),
    );
    await act(async () => {
      await contextValue.login('newbie', 'temp');
    });

    await waitFor(() => expect(screen.getByText('must-change-password')).toBeInTheDocument());
  });

  it('login() without mustChangePassword re-probes /me to pick up the full profile', async () => {
    stubFetchSequence([{ status: 401 }]);
    let contextValue!: ReturnType<typeof useAuth>;
    function Capture(): React.JSX.Element {
      contextValue = useAuth();
      return <Probe />;
    }
    render(
      <AuthProvider>
        <Capture />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByText('anonymous')).toBeInTheDocument());

    const fetchMock = vi.fn();
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ mustChangePassword: false, role: 'operator' }), { status: 200 }));
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ username: 'op', displayName: 'مشغّل', role: 'operator', mustChangePassword: false }), { status: 200 }),
    );
    vi.stubGlobal('fetch', fetchMock);
    await act(async () => {
      await contextValue.login('op', 'secret');
    });

    await waitFor(() => expect(screen.getByText('authenticated:operator:مشغّل')).toBeInTheDocument());
  });

  it('logout() clears the session back to anonymous', async () => {
    stubFetchSequence([
      { status: 200, body: { username: 'u', displayName: 'د', role: 'viewer', mustChangePassword: false } },
    ]);
    let contextValue!: ReturnType<typeof useAuth>;
    function Capture(): React.JSX.Element {
      contextValue = useAuth();
      return <Probe />;
    }
    render(
      <AuthProvider>
        <Capture />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByText(/^authenticated/)).toBeInTheDocument());

    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);
    await act(async () => {
      await contextValue.logout();
    });

    await waitFor(() => expect(screen.getByText('anonymous')).toBeInTheDocument());
    // POST /auth/logout expires the XSRF-TOKEN cookie server-side with no replacement (verified
    // live via curl); with nothing else in between, the very next unsafe request -- typically a
    // login attempt on the screen this transitions to -- would have no CSRF token to send and get
    // a bare 403 instead of the real auth outcome. Found live via Playwright: sign out, then sign
    // in as a different account, POST /auth/login -> 403. logout() must re-prime the cookie with
    // a follow-up GET before it resolves.
    const urls = fetchMock.mock.calls.map((call) => (call as unknown as [string])[0]);
    expect(urls).toContain('/api/v1/auth/logout');
    expect(urls).toContain('/api/v1/auth/me');
  });

  it('useAuth throws outside an AuthProvider', () => {
    function Orphan(): React.JSX.Element {
      useAuth();
      return <div />;
    }
    expect(() => render(<Orphan />)).toThrow('useAuth must be used within an AuthProvider');
  });
});
