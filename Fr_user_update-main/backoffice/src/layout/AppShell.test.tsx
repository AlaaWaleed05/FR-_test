import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import AppShell from './AppShell';
import type { AuthState } from '../auth/AuthContext';

const logoutMock = vi.hoisted(() => vi.fn());
const mockState = vi.hoisted(() => ({
  current: { status: 'authenticated', username: 'op1', displayName: 'مشغّل الاختبار', role: 'operator' } as AuthState,
}));

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ logout: logoutMock, login: vi.fn(), refresh: vi.fn(), state: mockState.current }),
}));

function renderShell(initialPath = '/profiles') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/profiles" element={<AppShell />}>
          <Route index element={<div>page-content</div>} />
          <Route path="other" element={<div>other-content</div>} />
        </Route>
        <Route path="/login" element={<div>login-screen</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('AppShell', () => {
  it('shows the signed-in display name and role, and the profiles nav item', () => {
    mockState.current = { status: 'authenticated', username: 'op1', displayName: 'مشغّل الاختبار', role: 'operator' };
    renderShell();

    expect(screen.getByText(/مشغّل الاختبار/)).toBeInTheDocument();
    expect(screen.getByText('الملفات')).toBeInTheDocument();
    expect(screen.getByText('page-content')).toBeInTheDocument();
  });

  it('shows the viewer role label too', () => {
    mockState.current = { status: 'authenticated', username: 'v1', displayName: 'مطّلع الاختبار', role: 'viewer' };
    renderShell();
    expect(screen.getByText(/مطّلع الاختبار/)).toBeInTheDocument();
  });

  it('labels an admin in Arabic, not as the raw role string', () => {
    // BL-139 let an admin into this shell for the first time. ROLE_LABEL_AR had no admin entry and
    // the lookup falls back to `?? state.role`, so this rendered the ASCII word "admin" inside an
    // Arabic-first RTL header -- a defect no existing test could catch, because no admin had ever
    // reached the shell to render it.
    mockState.current = { status: 'authenticated', username: 'admin1', displayName: 'مدير الاختبار', role: 'admin' };
    renderShell();
    expect(screen.getByText(/مدير النظام/)).toBeInTheDocument();
    expect(screen.queryByText(/\(admin\)/)).not.toBeInTheDocument();
  });

  it('never crashes if rendered with no resolved identity yet (defensive fallback)', () => {
    mockState.current = { status: 'loading' };
    renderShell();
    expect(screen.getByText('page-content')).toBeInTheDocument();
  });

  it('signs out and returns to /login', async () => {
    mockState.current = { status: 'authenticated', username: 'op1', displayName: 'مشغّل الاختبار', role: 'operator' };
    logoutMock.mockResolvedValueOnce(undefined);
    const user = userEvent.setup();
    renderShell();

    await user.click(screen.getByText('تسجيل الخروج'));

    await waitFor(() => expect(logoutMock).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByText('login-screen')).toBeInTheDocument());
  });

  it('still returns to /login even when the sign-out call itself fails', async () => {
    mockState.current = { status: 'authenticated', username: 'op1', displayName: 'مشغّل الاختبار', role: 'operator' };
    logoutMock.mockRejectedValueOnce(new Error('network down'));
    const user = userEvent.setup();
    renderShell();

    await user.click(screen.getByText('تسجيل الخروج'));

    await waitFor(() => expect(screen.getByText('login-screen')).toBeInTheDocument());
  });

  it('navigates via the "الملفات" nav item', async () => {
    mockState.current = { status: 'authenticated', username: 'op1', displayName: 'مشغّل الاختبار', role: 'operator' };
    const user = userEvent.setup();
    // Start somewhere else under the shell so the click has to actually move the route --
    // starting at /profiles itself (the previous version of this test) could pass even against
    // the inert menu item this behaviour was added to fix. Found under review.
    renderShell('/profiles/other');
    expect(screen.getByText('other-content')).toBeInTheDocument();

    await user.click(screen.getByText('الملفات'));

    expect(screen.getByText('page-content')).toBeInTheDocument();
    expect(screen.queryByText('other-content')).not.toBeInTheDocument();
  });
});
