import { useNavigate } from 'react-router-dom';
import { Button, Card, Space, Typography } from 'antd';
import { useAuth } from '../auth/AuthContext';

/**
 * Where an admin account lands. AD-013 (2026-09-13, BL-139) made the role ladder a hierarchy with
 * admin on top, so this page is no longer a dead end: an admin holds every operator power and may
 * walk straight through to the profile screens from here.
 *
 * <p>Until BL-139 this page said the opposite -- "admin accounts do not reach the back-office
 * profile screens" -- and that was true: `RequireRole` excluded admin from `/profiles` and the
 * backend 403'd every `/api/v1/operator/**` request. Both halves changed together.
 *
 * <p>It stays the admin's landing route because user management (wayfinder ticket 07, BL-023) is
 * the one power an operator does NOT inherit, and this is where that screen will live. It is a
 * signpost today, not a feature.
 */
export default function AdminHomePage(): React.JSX.Element {
  const { state, logout } = useAuth();
  const navigate = useNavigate();
  const displayName = state.status === 'authenticated' ? state.displayName : '';

  const onSignOut = async () => {
    // logout() always clears local state first (see AuthContext), but can still reject after
    // that if the server call itself failed -- navigate regardless, not only on success, and
    // catch (not just `finally`) so a failed sign-out never becomes an unhandled rejection.
    try {
      await logout();
    } catch {
      // local state is already 'anonymous' by this point; the server-side failure is not
      // actionable from here.
    } finally {
      navigate('/login', { replace: true });
    }
  };

  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}>
      <Card title={`مرحبًا ${displayName}`} style={{ width: 420 }}>
        <Typography.Paragraph>
          بصفتك مدير النظام، لديك كل صلاحيات المشغّل: يمكنك عرض الملفات واعتمادها ورفضها وإكمالها
          يدويًا.
        </Typography.Paragraph>
        <Typography.Paragraph type="secondary">
          تُدار حسابات المشغّلين والمطّلعين حاليًا عبر أداة سطر الأوامر، وليس من هذه الواجهة.
        </Typography.Paragraph>
        <Space>
          <Button type="primary" onClick={() => navigate('/profiles')}>
            الذهاب إلى الملفات
          </Button>
          <Button onClick={onSignOut}>تسجيل الخروج</Button>
        </Space>
      </Card>
    </div>
  );
}
