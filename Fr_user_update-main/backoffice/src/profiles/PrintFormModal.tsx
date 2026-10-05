import { useState } from 'react';
import { Alert, Checkbox, Modal, Space, Typography } from 'antd';
import type { PrintRequest } from '../api/profiles';

interface PrintFormModalProps {
  submitting: boolean;
  /**
   * Whether this profile carries ANY committed attachment — the five images or the salary
   * certificate. When it does not, the opt-in is not offered at all rather than shown disabled:
   * an option that produces nothing invites an operator to wonder whether the documents exist and
   * they simply cannot reach them (wayfinder ticket 05 decision 9, as widened 2026-09-14). The
   * ordinary case used to be a manually completed profile, which had no artifacts of any kind;
   * AD-022 removed that journey, so it is now a submitted profile whose uploads never landed.
   */
  hasAttachments: boolean;
  onCancel: () => void;
  onSubmit: (request: PrintRequest) => void;
}

/**
 * ONE interaction covering the single remaining print-time question — whether the attachments ride
 * with the form.
 *
 * It used to ask two: which of the two forms, and the attachments. AD-022 (S9-01) removed the
 * variant choice, so the menu of two the artboard at `Design_3/backoffice/Main.dc.html:236` draws
 * is gone and the checkbox is all that remains. Ticket 05 decision 9's rule still governs what is
 * left: "a checkbox in the print menu and a confirm step after it" is admitted, "two sequential
 * dialogs for one print" is not.
 *
 * **The answer resets every time the dialog opens**, and the mechanism is that the parent MOUNTS
 * this component fresh rather than toggling an `open` prop on a component that stays mounted. That
 * is the requirement, not a nicety: "asked every time, defaulting to no. A remembered preference is
 * the failure mode this shape exists to prevent."
 *
 * An earlier version cleared the two answers in antd's `afterClose`. It looked equivalent and was
 * not: `afterClose` runs after the close TRANSITION, which never completes under jsdom, so the
 * reset could not be tested — and a mechanism whose correctness rests on an animation callback
 * firing is the wrong one for a rule this load-bearing. A fresh mount cannot fail to reset.
 *
 * The form is INTERNAL and never handed to the customer (ticket 05 decision 7), which is what the
 * notice at the head of the dialog says — the same words the printed page itself carries.
 */
export default function PrintFormModal({
  submitting,
  hasAttachments,
  onCancel,
  onSubmit,
}: PrintFormModalProps): React.JSX.Element {
  const [includeAttachments, setIncludeAttachments] = useState(false);

  return (
    <Modal
      title="طباعة استمارة تحديث البيانات"
      open
      onOk={() => onSubmit({ includeAttachments })}
      onCancel={onCancel}
      confirmLoading={submitting}
      okText="طباعة"
      cancelText="إلغاء"
    >
      {/* The internal-use notice was HERE and is retired, S9-06, AD-022 (k). It read
          «للاستخدام الداخلي — لا تُسلَّم للعميل» and was believed to implement ticket 05 decision
          7. It did not: decision 7 rules that the form is internal as a matter of PROCESS and
          never asked for those words on any surface. The requirement was introduced by an earlier
          session reading its own gloss back as fact, and the product owner has confirmed it was
          never wanted. What remains is the sentence that states something true and useful to the
          operator about to press print. */}
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="تُحفَظ نسخة من كل طباعة في سجل الملف، وتُقيَّد العملية في سجل التدقيق."
      />

      {hasAttachments && (
        <Checkbox
          checked={includeAttachments}
          onChange={(event) => setIncludeAttachments(event.target.checked)}
        >
          <Space orientation="vertical" size={0}>
            <span>طباعة المرفقات مع الاستمارة</span>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              الصور وشهادة المرتب، كل مرفق في صفحة مستقلة
            </Typography.Text>
          </Space>
        </Checkbox>
      )}
    </Modal>
  );
}
