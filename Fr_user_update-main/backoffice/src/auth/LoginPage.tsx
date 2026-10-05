import { useId, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from './AuthContext';
import { ApiError } from '../api/http';
import { ARABIC_FONT, PALETTE } from '../theme/palette';

/**
 * Sign-in, rebuilt to `Design_3/backoffice/approved/login.dc.html` (AD-021, S9-02).
 *
 * **antd is dropped in this file**, and that is the decision rather than an accident. The
 * artboard is bare inline-styled HTML -- a split panel with the brand image filling one half
 * whole, 56px controls with leading icons, a 58px primary button -- and "the screen matches
 * `login.dc.html`" is not reachable by retinting `ConfigProvider` tokens. The drop is scoped to
 * this screen and the profile screen; everything else in `backoffice/` keeps antd, including
 * `ChangePasswordPage`, which this screen navigates to.
 *
 * Three things the artboard does NOT draw, kept anyway:
 *
 * - **The error region.** The artboard has no alert area at all, but this screen has to report a
 *   bad password and a failed request, and `LoginPage.test.tsx` asserts both strings. An approved
 *   picture of the happy path is not a decision to delete the unhappy one.
 * - **`autoComplete` and `autoFocus`**, which are behaviour rather than appearance.
 * - **A real `<form>` with a submit button**, so Enter submits. The artboard draws the button as a
 *   styled div; a div does not submit anything.
 *
 * **THE WHOLE CARD MIRRORS, AND THAT IS THE FOURTH DELIBERATE DEVIATION.** `index.html` is
 * `<html dir="rtl">` and direction inherits, so the brand panel lands on the RIGHT, the Arabic
 * label leads its row, and the field icons sit on the trailing edge -- the mirror image of the
 * artboard, which was authored on an LTR canvas with `dir="rtl"` set only on individual Arabic
 * spans. Mirroring is what RTL MEANS, and this is an Arabic-first UI (CLAUDE.md), so the mirror
 * is the correct rendering of that design rather than a departure from it. Forcing the card back
 * to the artboard's literal left-right order would put the Arabic labels on the wrong side of
 * their own inputs. Recorded because it is the most visible difference between the screen and
 * the picture it was built from, and the next reader will otherwise think it a mistake.
 *
 * The labels are TWO elements, English and Arabic, exactly as the artboard's flex row draws them
 * -- and only the Arabic one is bound to the input via `htmlFor`. That is deliberate: this is an
 * Arabic-first UI (CLAUDE.md), so the Arabic is the accessible name and the English is decoration
 * beside it.
 */

interface LocationState {
  from?: { pathname: string };
}

const CONTROL_HEIGHT = 56;

/** The artboard's leading icons, inline rather than from an icon package -- two glyphs do not
 *  earn a dependency, and these are copied from the artboard's own markup. */
function PersonIcon(): React.JSX.Element {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#8b8d98" strokeWidth="1.6" style={{ flexShrink: 0 }} aria-hidden="true">
      <circle cx="12" cy="8.5" r="3.4" />
      <path d="M5.5 20c1.3-3.4 3.7-5.1 6.5-5.1s5.2 1.7 6.5 5.1" />
    </svg>
  );
}

function LockIcon(): React.JSX.Element {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#8b8d98" strokeWidth="1.6" style={{ flexShrink: 0 }} aria-hidden="true">
      <rect x="4.5" y="10.5" width="15" height="10" rx="1.6" />
      <path d="M8.2 10.5V7.8a3.8 3.8 0 0 1 7.6 0v2.7" />
    </svg>
  );
}

/**
 * The artboard draws the password field's eye with a strike through it -- i.e. the "hidden"
 * state, which is where the screen starts. The strike drops when the value is revealed, so the
 * icon reports the CURRENT state rather than the action, matching how the artboard drew it.
 */
function EyeIcon({ revealed }: { revealed: boolean }): React.JSX.Element {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#8b8d98" strokeWidth="1.6" style={{ flexShrink: 0 }} aria-hidden="true">
      <path d="M2 12s3.6-6.5 10-6.5S22 12 22 12s-3.6 6.5-10 6.5S2 12 2 12z" />
      <circle cx="12" cy="12" r="2.6" />
      {!revealed && <path d="M3.5 20.5 20.5 3.5" />}
    </svg>
  );
}

const fieldShellStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  height: CONTROL_HEIGHT,
  border: `1px solid ${PALETTE.BORDER}`,
  borderRadius: 9,
  padding: '0 18px',
  marginBottom: 28,
  // Explicit, not inherited. `index.css` sets `color-scheme: light dark` on the body, so a bare
  // input left to the UA takes a dark field and near-white text in a dark-mode browser -- against
  // this card's fixed #fff and the artboard's light borders. Found at review before it shipped.
  background: '#fff',
};

const bareInputStyle: React.CSSProperties = {
  flexGrow: 1,
  minWidth: 0,
  fontSize: 20,
  fontFamily: 'inherit',
  color: PALETTE.TEXT,
  background: 'transparent',
  border: 'none',
  outline: 'none',
  padding: 0,
};

const labelRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  fontSize: 21,
  margin: '0 0 9px',
};

export default function LoginPage(): React.JSX.Element {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [revealed, setRevealed] = useState(false);
  // useId, not a literal: two ids on one page must not collide, and a literal would if this
  // component were ever rendered twice.
  const usernameId = useId();
  const passwordId = useId();

  const onSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    // The antd `Form` this replaced carried `rules={[{ required: true }]}` on both fields and
    // refused an empty submit client-side. Bare inputs have no such rule, and dropping it was
    // not free: EVERY failed sign-in makes the backend's AuthFailureAuditListener append a
    // `sign_in_failed` event to the hash-chained `system`/`auth` trail, so a stray Enter on an
    // empty form would permanently write noise into an audit chain. Guarded here rather than
    // with the native `required` attribute, whose validation bubble is in the BROWSER's locale
    // -- English on a bank desktop -- in an Arabic-first UI.
    if (username.trim() === '' || password === '') {
      setError('أدخل اسم المستخدم وكلمة المرور.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const result = await login(username, password);
      if (result.mustChangePassword) {
        navigate('/change-password', { replace: true });
      } else {
        const from = (location.state as LocationState | null)?.from?.pathname;
        navigate(from ?? '/', { replace: true });
      }
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setError('اسم المستخدم أو كلمة المرور غير صحيحة.');
      } else {
        setError('تعذر تسجيل الدخول. حاول مرة أخرى.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      style={{
        background: PALETTE.GROUND,
        padding: 24,
        minHeight: '100vh',
        boxSizing: 'border-box',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontFamily: ARABIC_FONT,
        color: PALETTE.TEXT,
      }}
    >
      <div
        style={{
          display: 'grid',
          // 1272, not 1320: the artboard's 1320px frame carries 24px of padding and
          // `box-sizing: border-box`, so the CARD inside it is 1272. `minmax(0, 1fr)` rather
          // than `1fr` so the image column cannot be forced past the card by its own
          // intrinsic width.
          gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1fr)',
          width: '100%',
          maxWidth: 1272,
          minHeight: 712,
          overflow: 'hidden',
          borderRadius: 22,
          background: '#fff',
          boxShadow: '0 18px 45px rgba(25,25,45,0.16)',
        }}
      >
        <div style={{ position: 'relative', overflow: 'hidden', background: PALETTE.PURPLE_DARK }}>
          {/*
            The panel is the whole left half and carries the wordmark itself, so there is no
            separate lockup on this screen. `az-login-panel.jpg` is the CORRECTED asset -- the
            supplied `az-logo.png` carries a garbled Arabic wordmark and must never be used
            (`docs/backoffice-redesign.md` 2.5). Decorative, so the alt is empty rather than a
            description: the words in the image are not information this screen conveys.
          */}
          <img
            src="/az-login-panel.jpg"
            alt=""
            style={{
              position: 'absolute',
              inset: 0,
              width: '100%',
              height: '100%',
              objectFit: 'cover',
              objectPosition: 'center top',
              display: 'block',
            }}
          />
        </div>

        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 60, background: '#fff' }}>
          <form onSubmit={onSubmit} style={{ width: 560, maxWidth: '100%' }}>
            {error && (
              <div
                role="alert"
                style={{
                  border: `1px solid ${PALETTE.RED}`,
                  color: PALETTE.RED,
                  background: 'rgba(228,49,42,0.06)',
                  borderRadius: 9,
                  padding: '12px 16px',
                  marginBottom: 24,
                  fontSize: 17,
                }}
              >
                {error}
              </div>
            )}

            <div style={labelRowStyle}>
              <span style={{ fontWeight: 600 }}>User Name</span>
              <label htmlFor={usernameId} style={{ fontWeight: 500 }} dir="rtl">
                اسم المستخدم
              </label>
            </div>
            <div style={fieldShellStyle}>
              <PersonIcon />
              <input
                id={usernameId}
                style={bareInputStyle}
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                autoFocus
                autoComplete="username"
                disabled={submitting}
              />
            </div>

            <div style={labelRowStyle}>
              <span style={{ fontWeight: 600 }}>Password</span>
              <label htmlFor={passwordId} style={{ fontWeight: 500 }} dir="rtl">
                كلمة المرور
              </label>
            </div>
            <div style={fieldShellStyle}>
              <LockIcon />
              <input
                id={passwordId}
                type={revealed ? 'text' : 'password'}
                style={{
                  ...bareInputStyle,
                  // The artboard tints the dots #4a4b57 and shows the revealed value in body ink.
                  color: revealed ? PALETTE.TEXT : '#4a4b57',
                  letterSpacing: revealed ? undefined : 4,
                }}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
                disabled={submitting}
              />
              <button
                type="button"
                onClick={() => setRevealed((r) => !r)}
                // Disabled in flight like every other control. antd's `Form disabled={submitting}`
                // covered the whole form for free; bare controls each need saying.
                disabled={submitting}
                aria-label={revealed ? 'إخفاء كلمة المرور' : 'إظهار كلمة المرور'}
                style={{
                  background: 'none',
                  border: 'none',
                  padding: 0,
                  cursor: submitting ? 'default' : 'pointer',
                  display: 'flex',
                }}
              >
                <EyeIcon revealed={revealed} />
              </button>
            </div>

            <button
              type="submit"
              disabled={submitting}
              dir="rtl"
              style={{
                width: '100%',
                height: 58,
                borderRadius: 9,
                background: PALETTE.PURPLE,
                color: '#fff',
                fontSize: 24,
                fontWeight: 600,
                fontFamily: 'inherit',
                border: 'none',
                cursor: submitting ? 'default' : 'pointer',
                opacity: submitting ? 0.7 : 1,
              }}
            >
              دخول
            </button>

            {/* Body ink, not a muted tone -- the artboard sets no colour here, so it inherits. */}
            <div dir="rtl" style={{ textAlign: 'center', fontSize: 17, margin: '48px 0 0' }}>
              يتطلب تغيير كلمة المرور عند أول دخول
            </div>
          </form>
        </div>
      </div>
    </div>
  );
}
