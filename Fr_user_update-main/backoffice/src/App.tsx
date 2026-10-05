import { Navigate, Route, BrowserRouter, Routes } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import { RequireAnonymous, RequireMustChangePassword, RequireRole, RootRedirect } from './auth/RequireAuth';
import { PROFILE_SCREEN_ROLES } from './auth/capabilities';
import LoginPage from './auth/LoginPage';
import ChangePasswordPage from './auth/ChangePasswordPage';
import AppShell from './layout/AppShell';
import AdminHomePage from './layout/AdminHomePage';
import ProfileListPage from './profiles/ProfileListPage';
import ProfileDetailPage from './profiles/ProfileDetailPage';

function App(): React.JSX.Element {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<RootRedirect />} />
          <Route
            path="/login"
            element={
              <RequireAnonymous>
                <LoginPage />
              </RequireAnonymous>
            }
          />
          <Route
            path="/change-password"
            element={
              <RequireMustChangePassword>
                <ChangePasswordPage />
              </RequireMustChangePassword>
            }
          />
          <Route
            element={
              /* AD-013 (BL-139): admin joins the profile screens. Admin is the TOP of the ladder,
                 not a role outside it, so excluding it here was what kept an admin off the very
                 screens AD-013 says they may use. The list comes from auth/capabilities so it
                 cannot drift from canViewProfiles. */
              <RequireRole roles={PROFILE_SCREEN_ROLES}>
                <AppShell />
              </RequireRole>
            }
          >
            <Route path="/profiles" element={<ProfileListPage />} />
          </Route>
          {/*
            THE SINGLE PROFILE SCREEN SITS OUTSIDE `AppShell` (product-owner ruling, 2026-09-16).

            AD-021's approved artboard draws the screen with its OWN header -- the AZ lockup, the
            operator's role, a sign-out link and the two-colour rule -- and no sider. Rendered
            inside the shell it would show two headers and two sign-outs.

            Since S9-06 both screens render the SAME header component (`layout/BrandHeader`), by
            product-owner request, so they no longer look different above the rule -- but the
            layouts stay separate and this ruling stands. The shell still owns the sider, which
            the profile screen must not have.

            Same guard, same roles: escaping the LAYOUT must not escape the AUTHORISATION, which
            is why `RequireRole` is repeated here rather than relied on from the branch above.
          */}
          <Route
            path="/profiles/:profileId"
            element={
              <RequireRole roles={PROFILE_SCREEN_ROLES}>
                <ProfileDetailPage />
              </RequireRole>
            }
          />
          <Route
            path="/admin"
            element={
              <RequireRole roles={['admin']}>
                <AdminHomePage />
              </RequireRole>
            }
          />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  );
}

export default App;
