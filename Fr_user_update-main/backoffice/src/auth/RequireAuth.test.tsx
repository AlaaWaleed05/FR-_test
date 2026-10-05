import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { RequireAnonymous, RequireMustChangePassword, RequireRole, RootRedirect } from './RequireAuth';
import type { AuthState } from './AuthContext';

const mockState = vi.hoisted(() => ({ current: { status: 'loading' } as AuthState }));
const refreshMock = vi.hoisted(() => vi.fn());

vi.mock('./AuthContext', async () => {
  const actual = await vi.importActual<typeof import('./AuthContext')>('./AuthContext');
  return {
    ...actual,
    useAuth: () => ({ state: mockState.current, login: vi.fn(), logout: vi.fn(), refresh: refreshMock }),
  };
});

function renderAt(path: string, element: React.JSX.Element) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path={path} element={element} />
        <Route path="/login" element={<div>login-screen</div>} />
        <Route path="/change-password" element={<div>change-password-screen</div>} />
        <Route path="/" element={<div>root</div>} />
        <Route path="/profiles" element={<div>profiles-screen</div>} />
        <Route path="/admin" element={<div>admin-screen</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('RequireRole', () => {
  it('shows a loading spinner while the session is still resolving', () => {
    mockState.current = { status: 'loading' };
    const { container } = renderAt('/profiles', <RequireRole roles={['viewer']}>protected</RequireRole>);
    expect(container.querySelector('[data-testid="session-loading"]')).not.toBeNull();
    expect(screen.queryByText('protected')).not.toBeInTheDocument();
  });

  it('shows the session-error screen (not a blank/loading page) on a /me failure', () => {
    mockState.current = { status: 'error', message: 'network down' };
    renderAt('/profiles', <RequireRole roles={['viewer']}>protected</RequireRole>);
    expect(screen.getByText('تعذر التحقق من حالة الجلسة')).toBeInTheDocument();
    expect(screen.getByText('network down')).toBeInTheDocument();
  });

  it('the session-error screen\'s retry button calls refresh()', async () => {
    mockState.current = { status: 'error', message: 'network down' };
    const user = userEvent.setup();
    renderAt('/profiles', <RequireRole roles={['viewer']}>protected</RequireRole>);

    await user.click(screen.getByRole('button', { name: 'إعادة المحاولة' }));

    expect(refreshMock).toHaveBeenCalled();
  });

  it('redirects an anonymous session to /login', () => {
    mockState.current = { status: 'anonymous' };
    renderAt('/profiles', <RequireRole roles={['viewer', 'operator']}>protected</RequireRole>);
    expect(screen.getByText('login-screen')).toBeInTheDocument();
  });

  it('redirects a must-change-password session to /change-password', () => {
    mockState.current = { status: 'must-change-password' };
    renderAt('/profiles', <RequireRole roles={['viewer', 'operator']}>protected</RequireRole>);
    expect(screen.getByText('change-password-screen')).toBeInTheDocument();
  });

  it('redirects an authenticated session with the wrong role to /', () => {
    // Guards the /admin route, which is the one place a role is still excluded. This case used to
    // use admin against roles={['viewer','operator']}, but AD-013 (BL-139) admitted admin to the
    // profile screens, so that pairing no longer describes anything the app does.
    mockState.current = { status: 'authenticated', username: 'a', displayName: 'a', role: 'viewer' };
    renderAt('/admin', <RequireRole roles={['admin']}>protected</RequireRole>);
    expect(screen.getByText('root')).toBeInTheDocument();
  });

  it('admits an admin to the profile screens (AD-013)', () => {
    mockState.current = { status: 'authenticated', username: 'a', displayName: 'a', role: 'admin' };
    renderAt('/profiles', <RequireRole roles={['viewer', 'operator', 'admin']}>protected content</RequireRole>);
    expect(screen.getByText('protected content')).toBeInTheDocument();
  });

  it('renders children when the role matches', () => {
    mockState.current = { status: 'authenticated', username: 'a', displayName: 'a', role: 'viewer' };
    renderAt('/profiles', <RequireRole roles={['viewer', 'operator']}>protected content</RequireRole>);
    expect(screen.getByText('protected content')).toBeInTheDocument();
  });
});

describe('RequireAnonymous', () => {
  it('lets an anonymous session see the wrapped content', () => {
    mockState.current = { status: 'anonymous' };
    renderAt('/login', <RequireAnonymous>login form</RequireAnonymous>);
    expect(screen.getByText('login form')).toBeInTheDocument();
  });

  it('redirects an already-authenticated session away from /login', () => {
    mockState.current = { status: 'authenticated', username: 'a', displayName: 'a', role: 'viewer' };
    renderAt('/login', <RequireAnonymous>login form</RequireAnonymous>);
    expect(screen.getByText('root')).toBeInTheDocument();
  });

  it('shows a loading spinner while the session is still resolving', () => {
    mockState.current = { status: 'loading' };
    const { container } = renderAt('/login', <RequireAnonymous>login form</RequireAnonymous>);
    expect(container.querySelector('[data-testid="session-loading"]')).not.toBeNull();
  });

  it('redirects a must-change-password session to /change-password', () => {
    mockState.current = { status: 'must-change-password' };
    renderAt('/login', <RequireAnonymous>login form</RequireAnonymous>);
    expect(screen.getByText('change-password-screen')).toBeInTheDocument();
  });

  it('shows the session-error screen on a /me failure', () => {
    mockState.current = { status: 'error', message: 'network down' };
    renderAt('/login', <RequireAnonymous>login form</RequireAnonymous>);
    expect(screen.getByText('تعذر التحقق من حالة الجلسة')).toBeInTheDocument();
  });
});

describe('RequireMustChangePassword', () => {
  it('lets a must-change-password session see the wrapped content', () => {
    mockState.current = { status: 'must-change-password' };
    renderAt('/change-password', <RequireMustChangePassword>form</RequireMustChangePassword>);
    expect(screen.getByText('form')).toBeInTheDocument();
  });

  it('redirects an anonymous session to /login', () => {
    mockState.current = { status: 'anonymous' };
    renderAt('/change-password', <RequireMustChangePassword>form</RequireMustChangePassword>);
    expect(screen.getByText('login-screen')).toBeInTheDocument();
  });

  it('shows a loading spinner while the session is still resolving', () => {
    mockState.current = { status: 'loading' };
    const { container } = renderAt('/change-password', <RequireMustChangePassword>form</RequireMustChangePassword>);
    expect(container.querySelector('[data-testid="session-loading"]')).not.toBeNull();
  });

  it('redirects an already-authenticated session away, having nothing left to change', () => {
    mockState.current = { status: 'authenticated', username: 'a', displayName: 'a', role: 'viewer' };
    renderAt('/change-password', <RequireMustChangePassword>form</RequireMustChangePassword>);
    expect(screen.getByText('root')).toBeInTheDocument();
  });

  it('shows the session-error screen on a /me failure', () => {
    mockState.current = { status: 'error', message: 'network down' };
    renderAt('/change-password', <RequireMustChangePassword>form</RequireMustChangePassword>);
    expect(screen.getByText('تعذر التحقق من حالة الجلسة')).toBeInTheDocument();
  });
});

describe('RootRedirect', () => {
  it('sends an admin to /admin', () => {
    mockState.current = { status: 'authenticated', username: 'a', displayName: 'a', role: 'admin' };
    renderAt('/', <RootRedirect />);
    expect(screen.getByText('admin-screen')).toBeInTheDocument();
  });

  it('sends a viewer to /profiles', () => {
    mockState.current = { status: 'authenticated', username: 'a', displayName: 'a', role: 'viewer' };
    renderAt('/', <RootRedirect />);
    expect(screen.getByText('profiles-screen')).toBeInTheDocument();
  });

  it('sends an anonymous session to /login', () => {
    mockState.current = { status: 'anonymous' };
    renderAt('/', <RootRedirect />);
    expect(screen.getByText('login-screen')).toBeInTheDocument();
  });

  it('sends a must-change-password session to /change-password', () => {
    mockState.current = { status: 'must-change-password' };
    renderAt('/', <RootRedirect />);
    expect(screen.getByText('change-password-screen')).toBeInTheDocument();
  });

  it('shows a loading spinner while the session is still resolving', () => {
    mockState.current = { status: 'loading' };
    const { container } = renderAt('/', <RootRedirect />);
    expect(container.querySelector('[data-testid="session-loading"]')).not.toBeNull();
  });

  it('shows the session-error screen on a /me failure', () => {
    mockState.current = { status: 'error', message: 'network down' };
    renderAt('/', <RootRedirect />);
    expect(screen.getByText('تعذر التحقق من حالة الجلسة')).toBeInTheDocument();
  });
});
