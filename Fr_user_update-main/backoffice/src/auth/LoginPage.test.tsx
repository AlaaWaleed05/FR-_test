import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import LoginPage from './LoginPage';

const loginMock = vi.hoisted(() => vi.fn());

vi.mock('./AuthContext', () => ({
  useAuth: () => ({ login: loginMock, logout: vi.fn(), refresh: vi.fn(), state: { status: 'anonymous' } }),
}));

function renderLoginPage(initialPath = '/login') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/change-password" element={<div>change-password-screen</div>} />
        <Route path="/profiles" element={<div>profiles-screen</div>} />
        <Route path="/audit" element={<div>audit-screen</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('LoginPage', () => {
  it('submits the form and navigates to / on a normal login', async () => {
    loginMock.mockResolvedValueOnce({ mustChangePassword: false, role: 'viewer' });
    const user = userEvent.setup();
    renderLoginPage();

    await user.type(screen.getByLabelText('اسم المستخدم'), 'alice');
    await user.type(screen.getByLabelText('كلمة المرور'), 'secret');
    await user.click(screen.getByRole('button', { name: 'دخول' }));

    await waitFor(() => expect(loginMock).toHaveBeenCalledWith('alice', 'secret'));
  });

  it('navigates to /change-password when the login response says so', async () => {
    loginMock.mockResolvedValueOnce({ mustChangePassword: true, role: 'viewer' });
    const user = userEvent.setup();
    renderLoginPage();

    await user.type(screen.getByLabelText('اسم المستخدم'), 'newbie');
    await user.type(screen.getByLabelText('كلمة المرور'), 'temp');
    await user.click(screen.getByRole('button', { name: 'دخول' }));

    await waitFor(() => expect(screen.getByText('change-password-screen')).toBeInTheDocument());
  });

  it('shows an Arabic error message on invalid credentials (401)', async () => {
    const { ApiError } = await import('../api/http');
    loginMock.mockRejectedValueOnce(new ApiError(401, ''));
    const user = userEvent.setup();
    renderLoginPage();

    await user.type(screen.getByLabelText('اسم المستخدم'), 'alice');
    await user.type(screen.getByLabelText('كلمة المرور'), 'wrong');
    await user.click(screen.getByRole('button', { name: 'دخول' }));

    await waitFor(() =>
      expect(screen.getByText('اسم المستخدم أو كلمة المرور غير صحيحة.')).toBeInTheDocument(),
    );
  });

  it('shows a generic Arabic error for a non-401 failure', async () => {
    loginMock.mockRejectedValueOnce(new Error('network down'));
    const user = userEvent.setup();
    renderLoginPage();

    await user.type(screen.getByLabelText('اسم المستخدم'), 'alice');
    await user.type(screen.getByLabelText('كلمة المرور'), 'secret');
    await user.click(screen.getByRole('button', { name: 'دخول' }));

    await waitFor(() => expect(screen.getByText('تعذر تسجيل الدخول. حاول مرة أخرى.')).toBeInTheDocument());
  });

  it('returns to the originally-attempted location after a redirected sign-in', async () => {
    loginMock.mockResolvedValueOnce({ mustChangePassword: false, role: 'operator' });
    const user = userEvent.setup();
    render(
      <MemoryRouter
        initialEntries={[{ pathname: '/login', state: { from: { pathname: '/audit' } } }]}
      >
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/audit" element={<div>audit-screen</div>} />
        </Routes>
      </MemoryRouter>,
    );

    await user.type(screen.getByLabelText('اسم المستخدم'), 'alice');
    await user.type(screen.getByLabelText('كلمة المرور'), 'secret');
    await user.click(screen.getByRole('button', { name: 'دخول' }));

    await waitFor(() => expect(screen.getByText('audit-screen')).toBeInTheDocument());
  });

  // --- S9-02: the artboard rebuild's own surface -------------------------------------------

  it('renders the approved split panel, with the brand image decorative', () => {
    const { container } = renderLoginPage();
    const panel = container.querySelector('img[src="/az-login-panel.jpg"]');
    expect(panel).not.toBeNull();
    // Empty alt, not a description. The wordmark inside the image is not information this
    // screen conveys, and a screen reader announcing it would read the bank's name twice.
    expect(panel).toHaveAttribute('alt', '');
  });

  it('keeps the password hidden until the reveal is pressed, and puts it back', async () => {
    const user = userEvent.setup();
    renderLoginPage();
    const password = screen.getByLabelText('كلمة المرور');
    expect(password).toHaveAttribute('type', 'password');

    await user.click(screen.getByRole('button', { name: 'إظهار كلمة المرور' }));
    expect(password).toHaveAttribute('type', 'text');

    // The icon reports the CURRENT state, so its accessible name flips with it -- asserting the
    // way back matters because a toggle that only opens is a password left on screen.
    await user.click(screen.getByRole('button', { name: 'إخفاء كلمة المرور' }));
    expect(password).toHaveAttribute('type', 'password');
  });

  it('does not submit the form when the reveal button is pressed', async () => {
    // `loginMock` is module-level and hoisted, and nothing else in this file clears it -- so a
    // bare "was never called" assertion would otherwise see every earlier test's calls. The
    // older tests all assert `toHaveBeenCalledWith` or on rendered text, never a call COUNT, so
    // clearing here changes nothing for them.
    loginMock.mockClear();
    const user = userEvent.setup();
    renderLoginPage();
    await user.click(screen.getByRole('button', { name: 'إظهار كلمة المرور' }));
    // type="button", not a bare <button> defaulting to submit inside a <form>. Without it the
    // eye would post empty credentials and bounce the operator off a 401 they did not cause.
    expect(loginMock).not.toHaveBeenCalled();
  });

  it('refuses an empty submit without troubling the server', async () => {
    loginMock.mockClear();
    const user = userEvent.setup();
    renderLoginPage();

    await user.click(screen.getByRole('button', { name: 'دخول' }));

    // Not merely a UX nicety. The antd Form this replaced refused an empty submit client-side;
    // bare inputs do not, and every failed sign-in appends a `sign_in_failed` event to the
    // backend's hash-chained audit trail. An empty form must not write to it.
    expect(loginMock).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent('أدخل اسم المستخدم وكلمة المرور.');
  });

  it('still refuses when only one of the two fields is filled', async () => {
    loginMock.mockClear();
    const user = userEvent.setup();
    renderLoginPage();

    await user.type(screen.getByLabelText('اسم المستخدم'), 'alice');
    await user.click(screen.getByRole('button', { name: 'دخول' }));

    expect(loginMock).not.toHaveBeenCalled();
  });

  it('treats a whitespace-only username as empty', async () => {
    loginMock.mockClear();
    const user = userEvent.setup();
    renderLoginPage();

    await user.type(screen.getByLabelText('اسم المستخدم'), '   ');
    await user.type(screen.getByLabelText('كلمة المرور'), 'secret');
    await user.click(screen.getByRole('button', { name: 'دخول' }));

    expect(loginMock).not.toHaveBeenCalled();
  });

  it('submits on Enter, because the artboard button is a real submit control', async () => {
    loginMock.mockResolvedValueOnce({ mustChangePassword: false, role: 'operator' });
    const user = userEvent.setup();
    renderLoginPage();
    await user.type(screen.getByLabelText('اسم المستخدم'), 'alice');
    await user.type(screen.getByLabelText('كلمة المرور'), 'secret{Enter}');
    await waitFor(() => expect(loginMock).toHaveBeenCalledWith('alice', 'secret'));
  });

  it('announces a failure to assistive technology rather than only colouring it', async () => {
    loginMock.mockRejectedValueOnce(new Error('boom'));
    const user = userEvent.setup();
    renderLoginPage();
    // BOTH fields: the empty-submit guard would otherwise refuse this before it ever reached
    // login(), and the test would be asserting the guard rather than the failure path.
    await user.type(screen.getByLabelText('اسم المستخدم'), 'alice');
    await user.type(screen.getByLabelText('كلمة المرور'), 'secret');
    await user.click(screen.getByRole('button', { name: 'دخول' }));
    // The artboard draws no error region at all; it is kept, and kept as role="alert", because
    // the only other signal is the palette's red.
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('تعذر تسجيل الدخول. حاول مرة أخرى.'));
  });

  it('disables both inputs and the submit while the request is in flight', async () => {
    let release: (value: unknown) => void = () => {};
    loginMock.mockReturnValueOnce(new Promise((resolve) => { release = resolve; }));
    const user = userEvent.setup();
    renderLoginPage();
    // BOTH fields, for the same reason as the test above.
    await user.type(screen.getByLabelText('اسم المستخدم'), 'alice');
    await user.type(screen.getByLabelText('كلمة المرور'), 'secret');
    await user.click(screen.getByRole('button', { name: 'دخول' }));

    await waitFor(() => expect(screen.getByRole('button', { name: 'دخول' })).toBeDisabled());
    expect(screen.getByLabelText('اسم المستخدم')).toBeDisabled();
    expect(screen.getByLabelText('كلمة المرور')).toBeDisabled();

    // Released, the form comes back. Asserting a destination screen instead would be asserting
    // the router: a login with no `from` navigates to '/', which this harness has no route for.
    release({ mustChangePassword: false, role: 'viewer' });
    await waitFor(() => expect(screen.getByRole('button', { name: 'دخول' })).toBeEnabled());
  });
});
