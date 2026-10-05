import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import ChangePasswordPage from './ChangePasswordPage';

const refreshMock = vi.hoisted(() => vi.fn());
const logoutMock = vi.hoisted(() => vi.fn());

vi.mock('./AuthContext', () => ({
  useAuth: () => ({ refresh: refreshMock, logout: logoutMock, login: vi.fn(), state: { status: 'must-change-password' } }),
}));

vi.mock('../api/auth', async () => {
  const actual = await vi.importActual<typeof import('../api/auth')>('../api/auth');
  return { ...actual, changePassword: vi.fn() };
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/change-password']}>
      <Routes>
        <Route path="/change-password" element={<ChangePasswordPage />} />
        <Route path="/" element={<div>root-screen</div>} />
        <Route path="/login" element={<div>login-screen</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('ChangePasswordPage', () => {
  it('rejects a mismatched confirmation without calling the API', async () => {
    const { changePassword } = await import('../api/auth');
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText('كلمة المرور الحالية'), 'old');
    await user.type(screen.getByLabelText('كلمة المرور الجديدة'), 'newpass1');
    await user.type(screen.getByLabelText('تأكيد كلمة المرور الجديدة'), 'newpass2');
    await user.click(screen.getByRole('button', { name: 'تغيير كلمة المرور' }));

    expect(await screen.findByText('كلمتا المرور الجديدتان غير متطابقتين.')).toBeInTheDocument();
    expect(changePassword).not.toHaveBeenCalled();
  });

  it('changes the password, refreshes the session, and navigates to /', async () => {
    const { changePassword } = await import('../api/auth');
    vi.mocked(changePassword).mockResolvedValueOnce(undefined);
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText('كلمة المرور الحالية'), 'old');
    await user.type(screen.getByLabelText('كلمة المرور الجديدة'), 'newpass1');
    await user.type(screen.getByLabelText('تأكيد كلمة المرور الجديدة'), 'newpass1');
    await user.click(screen.getByRole('button', { name: 'تغيير كلمة المرور' }));

    await waitFor(() => expect(changePassword).toHaveBeenCalledWith('old', 'newpass1'));
    await waitFor(() => expect(refreshMock).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByText('root-screen')).toBeInTheDocument());
  });

  it('shows an Arabic error when the current password is wrong (401)', async () => {
    const { changePassword } = await import('../api/auth');
    const { ApiError } = await import('../api/http');
    vi.mocked(changePassword).mockRejectedValueOnce(new ApiError(401, ''));
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText('كلمة المرور الحالية'), 'wrong');
    await user.type(screen.getByLabelText('كلمة المرور الجديدة'), 'newpass1');
    await user.type(screen.getByLabelText('تأكيد كلمة المرور الجديدة'), 'newpass1');
    await user.click(screen.getByRole('button', { name: 'تغيير كلمة المرور' }));

    expect(await screen.findByText('كلمة المرور الحالية غير صحيحة.')).toBeInTheDocument();
  });

  it('shows an Arabic error when the new password is rejected (400)', async () => {
    const { changePassword } = await import('../api/auth');
    const { ApiError } = await import('../api/http');
    vi.mocked(changePassword).mockRejectedValueOnce(new ApiError(400, ''));
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText('كلمة المرور الحالية'), 'old');
    await user.type(screen.getByLabelText('كلمة المرور الجديدة'), 'toolongtoolong');
    await user.type(screen.getByLabelText('تأكيد كلمة المرور الجديدة'), 'toolongtoolong');
    await user.click(screen.getByRole('button', { name: 'تغيير كلمة المرور' }));

    expect(await screen.findByText('كلمة المرور الجديدة غير مقبولة.')).toBeInTheDocument();
  });

  it('shows a generic Arabic error for any other failure', async () => {
    const { changePassword } = await import('../api/auth');
    vi.mocked(changePassword).mockRejectedValueOnce(new Error('network down'));
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText('كلمة المرور الحالية'), 'old');
    await user.type(screen.getByLabelText('كلمة المرور الجديدة'), 'newpass1');
    await user.type(screen.getByLabelText('تأكيد كلمة المرور الجديدة'), 'newpass1');
    await user.click(screen.getByRole('button', { name: 'تغيير كلمة المرور' }));

    expect(await screen.findByText('تعذر تغيير كلمة المرور. حاول مرة أخرى.')).toBeInTheDocument();
  });

  it('signs out via the escape hatch', async () => {
    logoutMock.mockResolvedValueOnce(undefined);
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: 'تسجيل الخروج' }));

    await waitFor(() => expect(logoutMock).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByText('login-screen')).toBeInTheDocument());
  });

  it('still returns to /login even when the sign-out call itself fails', async () => {
    logoutMock.mockRejectedValueOnce(new Error('network down'));
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: 'تسجيل الخروج' }));

    await waitFor(() => expect(screen.getByText('login-screen')).toBeInTheDocument());
  });
});
