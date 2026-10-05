import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Layout, Menu } from 'antd';
import BrandHeader from './BrandHeader';

/** The back-office shell. All three roles reach the same screens through it -- viewer, operator
 * and, since AD-013 (BL-139), admin. The nav is still undifferentiated because the role-specific
 * powers are ACTIONS on the profile detail page (approve/reject/print, gated by
 * `canOperate`), not separate screens. The one screen that is role-specific, `/admin`, is outside
 * this shell entirely.
 *
 * S9-06: the header is {@link BrandHeader}, the same component the profile screen renders, by
 * product-owner request. What it replaced was an antd dark bar carrying only text -- the list is
 * the one screen still inside this shell and it had no logo, because the approved set
 * (`Design_3/backoffice/approved/`) draws login, the profile screen and the two printed pages,
 * and no artboard for the list. The sider is untouched and deliberately kept. */
export default function AppShell(): React.JSX.Element {
  const navigate = useNavigate();
  const location = useLocation();

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <BrandHeader />
      <Layout>
        <Layout.Sider width={200}>
          <Menu
            mode="inline"
            // Sub-routes like /profiles/:profileId (the single profile view, S6-02) should keep
            // the same nav item highlighted, not appear to leave it.
            selectedKeys={[location.pathname.startsWith('/profiles') ? '/profiles' : location.pathname]}
            items={[{ key: '/profiles', label: 'الملفات' }]}
            onClick={({ key }) => navigate(key)}
            style={{ height: '100%' }}
          />
        </Layout.Sider>
        <Layout.Content style={{ padding: 24 }}>
          <Outlet />
        </Layout.Content>
      </Layout>
    </Layout>
  );
}
