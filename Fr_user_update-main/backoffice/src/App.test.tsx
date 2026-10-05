import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import App from './App';

describe('App', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status: 401 })),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('routes an anonymous session to the sign-in screen', async () => {
    render(<App />);
    // Anchored on the submit button, not on a heading. The old anchor was «تسجيل الدخول», which
    // existed only as the antd Card title the S9-02 rebuild removed -- `login.dc.html` contains
    // that string zero times. `getByRole` rather than `getByText('دخول')` because the artboard's
    // footnote «يتطلب تغيير كلمة المرور عند أول دخول» also contains the word.
    await waitFor(() => expect(screen.getByRole('button', { name: 'دخول' })).toBeInTheDocument());
  });
});
