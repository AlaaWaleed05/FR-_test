import { useMemo, useState } from 'react';
import { Alert, Form, Input, Modal, Select, Typography } from 'antd';
import { useReferenceList } from '../api/reference';

const REJ_07 = 'REJ-07';

interface RejectFormValues {
  reasonCode: string;
  internalNote?: string;
}

interface RejectModalProps {
  open: boolean;
  submitting: boolean;
  onCancel: () => void;
  onSubmit: (reasonCode: string, internalNote: string | null) => void;
}

/**
 * operator.md "Review — approve or reject": a reason code from the fixed list is the only
 * submittable path — free text alone is never accepted, only a code selection is (the internal
 * note is supplementary, mandatory only for REJ-07). The reason list is fetched from
 * `rejection_reason` reference data (CLAUDE.md: never hardcoded), the same source
 * `ProfileListPage`'s filter already uses. A plain `Select`, not `showSearch`, on a 7-item list —
 * avoids adding a second instance of the already-filed BL-014 unfolded-search gap.
 */
export default function RejectModal({ open, submitting, onCancel, onSubmit }: RejectModalProps): React.JSX.Element {
  const [form] = Form.useForm<RejectFormValues>();
  const [selectedCode, setSelectedCode] = useState<string | undefined>(undefined);
  const reasons = useReferenceList('rejection_reason');
  /**
   * WITHDRAWN REASONS ARE NOT OFFERED (BL-154). The published reference document deliberately
   * carries `is_active = false` items — a client needs the row to resolve a label for a value some
   * profile was already rejected under — so filtering them out is this picker's own job. Without
   * it an operator would still see REJ-03 «فشل أو عدم وضوح مطابقة الوجه» in this dropdown and get
   * a 400 on submit, because `ReferenceCatalog.exists()` filters the same flag server-side.
   *
   * `ProfileListPage`'s reason FILTER deliberately does NOT do this, and the asymmetry is the
   * point: that control searches rejections that already happened, and hiding a withdrawn code
   * there would make every profile rejected under it unfindable.
   */
  const choosableReasons = useMemo(
    () => reasons.items.filter((item) => item.isActive),
    [reasons.items],
  );

  const selectedItem = useMemo(
    () => reasons.items.find((item) => item.itemCode === selectedCode),
    [reasons.items, selectedCode],
  );
  const customerMessageAr = useMemo(() => {
    const extra = selectedItem?.extra;
    if (extra && typeof extra === 'object' && 'customerMessageAr' in extra) {
      const value = (extra as { customerMessageAr?: unknown }).customerMessageAr;
      return typeof value === 'string' ? value : null;
    }
    return null;
  }, [selectedItem]);

  const handleOk = () => {
    form
      .validateFields()
      .then((values) => onSubmit(values.reasonCode, values.internalNote?.trim() || null))
      .catch(() => {
        // antd's own validation surfaces the field errors; nothing extra to do here.
      });
  };

  const afterClose = () => {
    form.resetFields();
    setSelectedCode(undefined);
  };

  return (
    <Modal
      title="رفض الملف"
      open={open}
      onOk={handleOk}
      onCancel={onCancel}
      afterClose={afterClose}
      confirmLoading={submitting}
      okText="رفض"
      cancelText="إلغاء"
      okButtonProps={{ danger: true }}
    >
      <Form form={form} layout="vertical">
        <Form.Item
          name="reasonCode"
          label="سبب الرفض"
          rules={[{ required: true, message: 'اختيار سبب من القائمة إلزامي — لا يُقبل نص حر بمفرده' }]}
        >
          <Select
            placeholder="اختر السبب"
            loading={reasons.loading}
            options={choosableReasons.map((item) => ({ value: item.itemCode, label: `${item.itemCode} — ${item.labelAr}` }))}
            onChange={(value: string) => setSelectedCode(value)}
          />
        </Form.Item>
        {customerMessageAr && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            title="الرسالة التي سيتلقاها العميل"
            description={<Typography.Text>{customerMessageAr}</Typography.Text>}
          />
        )}
        <Form.Item
          name="internalNote"
          label="تفصيل داخلي (لا يظهر للعميل)"
          rules={[
            {
              validator: (_rule, value: string | undefined) => {
                if (selectedCode === REJ_07 && !value?.trim()) {
                  return Promise.reject(new Error(`${REJ_07} يتطلب تفصيلًا داخليًا إلزاميًا`));
                }
                return Promise.resolve();
              },
            },
          ]}
        >
          <Input.TextArea rows={3} placeholder={selectedCode === REJ_07 ? 'إلزامي لهذا السبب' : 'اختياري'} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
