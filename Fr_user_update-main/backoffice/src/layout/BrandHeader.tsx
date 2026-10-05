import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ARABIC_FONT, INK, NUMERIC_FONT, PALETTE } from '../theme/palette';

/**
 * Arabic role labels, lifted from V0057's own `label_ar` column so the header names a role
 * exactly as the database does.
 *
 * `admin` was absent until BL-139 let an admin into the shell -- the lookup below falls back to
 * the raw role, so the omission rendered the ASCII string "admin" in an Arabic-first RTL header
 * rather than failing in any way a test would notice. `AppShell.test.tsx` asserts the Arabic
 * label AND the absence of the raw string, which is what keeps that defect from returning.
 */
const ROLE_LABEL_AR: Record<string, string> = {
  viewer: 'مطّلع',
  operator: 'مشغّل',
  admin: 'مدير النظام',
};

/**
 * The back office's one header: the AZ lockup, the product line, the operator's identity and
 * sign-out, and the two-colour rule beneath. `Design_3/backoffice/approved/profile-screen.dc.html`.
 *
 * **Shared between `AppShell` and the profile screen, which do not share a layout.** The profile
 * screen sits OUTSIDE `AppShell` (product-owner ruling, 2026-09-16) so that the artboard's header
 * is the only header there; the list screen reaches the same markup through the shell. Before
 * S9-06 the two were duplicated on the reasoning that the shell was on its way out of the profile
 * screen's life -- true of the LAYOUT, and now the wrong conclusion for the HEADER, which the
 * product owner has asked to be the same on both.
 *
 * **This is not purely presentational, and it has to be.** Escaping the shell's layout also
 * escapes its sign-out, so sign-out lives here, including the catch-then-navigate: `logout()`
 * clears local state first but can still reject afterwards if the server call failed, and
 * navigating only on success would strand an operator who is already anonymous locally.
 *
 * The rule is red then purple, 2:7. The grey `#ABADAC` of the old letterhead is gone and the
 * purple takes its width -- `docs/backoffice-redesign.md` §1.
 *
 * antd is deliberately absent: AD-021 rebuilds this chrome to a bare inline-styled artboard.
 */
export default function BrandHeader(): React.JSX.Element {
  const { state, logout } = useAuth();
  const navigate = useNavigate();

  const onSignOut = async () => {
    try {
      await logout();
    } catch {
      // Local state is already 'anonymous' by this point; a server-side failure is not
      // actionable from here, and must not become an unhandled rejection.
    } finally {
      navigate('/login', { replace: true });
    }
  };

  const displayName = state.status === 'authenticated' ? state.displayName : '';
  const roleLabel = state.status === 'authenticated' ? (ROLE_LABEL_AR[state.role] ?? state.role) : '';

  return (
    // `<header>` rather than a div: inside `AppShell` this replaced an antd `Layout.Header`, which
    // renders a real <header>, and dropping to a div would have cost the list page its banner
    // landmark. The profile screen gains one it never had.
    //
    // ARABIC_FONT is set HERE, on the component's own root, not on either host. The profile screen
    // gets the face from `ScreenShell` (chrome.tsx) and the shell has no equivalent wrapper --
    // `index.css` leaves `body` on system-ui -- so without this the SAME markup renders in two
    // different faces depending on which screen you are looking at. jsdom resolves no fonts and
    // computes no layout, so no test in this tier can see that divergence. Found under review.
    <header style={{ background: '#fff', fontFamily: ARABIC_FONT }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '14px 20px', gap: 16, flexWrap: 'wrap' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          {/* Decorative: the product name sits beside it in text, so a screen reader that also
              announced the image would say it twice. */}
          <img src="/az-lockup.png" alt="" style={{ height: 42, display: 'block' }} />
          <div style={{ display: 'flex', flexDirection: 'column', lineHeight: 1.25 }}>
            <span style={{ fontFamily: NUMERIC_FONT, fontSize: 18, fontWeight: 600, color: PALETTE.TEXT }}>AZ Omni eKYC</span>
            <span style={{ fontSize: 12, color: INK.MUTED }}>تحديث بيانات العملاء — البنك السوداني الفرنسي</span>
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, fontSize: 13, color: INK.MUTED }}>
          <span style={{ color: PALETTE.TEXT }}>
            {displayName} ({roleLabel})
          </span>
          <button
            type="button"
            onClick={onSignOut}
            style={{
              background: 'none',
              border: 'none',
              padding: 0,
              font: 'inherit',
              textDecoration: 'underline',
              color: PALETTE.PURPLE,
              cursor: 'pointer',
            }}
          >
            تسجيل الخروج
          </button>
        </div>
      </div>
      <div style={{ display: 'flex', height: 3 }}>
        <div style={{ flex: 2, background: PALETTE.RED }} />
        <div style={{ flex: 7, background: PALETTE.PURPLE }} />
      </div>
    </header>
  );
}
