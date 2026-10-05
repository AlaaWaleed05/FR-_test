import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import AdminHomePage from './AdminHomePage';
import type { AuthState } from '../auth/AuthContext';

const logoutMock = vi.hoisted(() => vi.fn());
const mockState = vi.hoisted(() => ({
  current: { status: 'authenticated', username: 'admin1', displayName: 'مدير الاختبار', role: 'admin' } as AuthState,
}));

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ logout: logoutMock, login: vi.fn(), refresh: vi.fn(), state: mockState.current }),
}));

describe('AdminHomePage', () => {
  it('greets the admin by display name and offers a way through to the profiles screen', () => {
    // INVERTED at BL-139. This test asserted the page "never mentions the profiles screen", which
    // pinned AD-002e's model: admin was a dead end. AD-013 made admin a superuser, so a page that
    // still hid the profile screens would be telling an admin something untrue about their own
    // account.
    mockState.current = { status: 'authenticated', username: 'admin1', displayName: 'مدير الاختبار', role: 'admin' };
    render(
      <MemoryRouter>
        <AdminHomePage />
      </MemoryRouter>,
    );

    expect(screen.getByText(/مدير الاختبار/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'الذهاب إلى الملفات' })).toBeInTheDocument();
  });

  it('navigates to /profiles when the admin takes that route', async () => {
    mockState.current = { status: 'authenticated', username: 'admin1', displayName: 'مدير الاختبار', role: 'admin' };
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/admin']}>
        <Routes>
          <Route path="/admin" element={<AdminHomePage />} />
          <Route path="/profiles" element={<div>profiles-screen</div>} />
        </Routes>
      </MemoryRouter>,
    );

    await user.click(screen.getByRole('button', { name: 'الذهاب إلى الملفات' }));

    await waitFor(() => expect(screen.getByText('profiles-screen')).toBeInTheDocument());
  });

  it('never crashes if rendered with no resolved identity yet (defensive fallback)', () => {
    mockState.current = { status: 'loading' };
    render(
      <MemoryRouter>
        <AdminHomePage />
      </MemoryRouter>,
    );
    expect(screen.getByRole('button', { name: 'تسجيل الخروج' })).toBeInTheDocument();
  });

  it('signs out and returns to /login', async () => {
    mockState.current = { status: 'authenticated', username: 'admin1', displayName: 'مدير الاختبار', role: 'admin' };
    logoutMock.mockResolvedValueOnce(undefined);
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/admin']}>
        <Routes>
          <Route path="/admin" element={<AdminHomePage />} />
          <Route path="/login" element={<div>login-screen</div>} />
        </Routes>
      </MemoryRouter>,
    );

    await user.click(screen.getByRole('button', { name: 'تسجيل الخروج' }));

    await waitFor(() => expect(logoutMock).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByText('login-screen')).toBeInTheDocument());
  });

  it('still returns to /login even when the sign-out call itself fails', async () => {
    mockState.current = { status: 'authenticated', username: 'admin1', displayName: 'مدير الاختبار', role: 'admin' };
    logoutMock.mockRejectedValueOnce(new Error('network down'));
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/admin']}>
        <Routes>
          <Route path="/admin" element={<AdminHomePage />} />
          <Route path="/login" element={<div>login-screen</div>} />
        </Routes>
      </MemoryRouter>,
    );

    await user.click(screen.getByRole('button', { name: 'تسجيل الخروج' }));

    await waitFor(() => expect(screen.getByText('login-screen')).toBeInTheDocument());
  });
});
