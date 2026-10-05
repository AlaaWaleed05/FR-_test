import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert, Button, Card, Form, Input, Typography } from 'antd';
import { useAuth } from './AuthContext';
import { changePassword } from '../api/auth';
import { ApiError } from '../api/http';

interface ChangePasswordFormValues {
  currentPassword: string;
  newPassword: string;
  confirmNewPassword: string;
}

/** Standalone screen, no shell chrome, no navigation menu -- a must-change-password account can
 * reach nothing else (every other endpoint 401s/403s it by authorization, not a controller check;
 * see auth.config.SecurityConfiguration). The only escape hatch is signing out. */
export default function ChangePasswordPage(): React.JSX.Element {
  const { refresh, logout } = useAuth();
  const navigate = useNavigate();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const onFinish = async (values: ChangePasswordFormValues) => {
    if (values.newPassword !== values.confirmNewPassword) {
      setError('كلمتا المرور الجديدتان غير متطابقتين.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await changePassword(values.currentPassword, values.newPassword);
      await refresh();
      navigate('/', { replace: true });
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setError('كلمة المرور الحالية غير صحيحة.');
      } else if (err instanceof ApiError && err.status === 400) {
        setError('كلمة المرور الجديدة غير مقبولة.');
      } else {
        setError('تعذر تغيير كلمة المرور. حاول مرة أخرى.');
      }
    } finally {
      setSubmitting(false);
    }
  };

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
      <Card title="يجب تغيير كلمة المرور" style={{ width: 400 }}>
        <Typography.Paragraph type="secondary">
          هذا الحساب جديد أو أُعيد ضبطه. لا يمكن المتابعة إلى أي شاشة أخرى قبل تعيين كلمة مرور جديدة.
        </Typography.Paragraph>
        {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}
        <Form<ChangePasswordFormValues> layout="vertical" onFinish={onFinish} disabled={submitting}>
          <Form.Item
            name="currentPassword"
            label="كلمة المرور الحالية"
            rules={[{ required: true, message: 'مطلوب' }]}
          >
            <Input.Password autoFocus autoComplete="current-password" />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label="كلمة المرور الجديدة"
            rules={[{ required: true, message: 'مطلوب' }]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item
            name="confirmNewPassword"
            label="تأكيد كلمة المرور الجديدة"
            rules={[{ required: true, message: 'مطلوب' }]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block loading={submitting}>
              تغيير كلمة المرور
            </Button>
          </Form.Item>
          <Button type="link" block onClick={onSignOut} disabled={submitting}>
            تسجيل الخروج
          </Button>
        </Form>
      </Card>
    </div>
  );
}
