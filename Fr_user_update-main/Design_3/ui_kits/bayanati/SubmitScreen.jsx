import React from 'react';
import { Phone, Body } from './Phone.jsx';
import { BrandBar } from '../../components/brand/BrandBar.jsx';
import { StageHeader } from '../../components/navigation/StageHeader.jsx';
import { Banner } from '../../components/feedback/Banner.jsx';
import { Button, ActionBar } from '../../components/actions/Button.jsx';

const done = <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="var(--ok)" strokeWidth="2" style={{ flex: '0 0 auto' }}><path d="M5 12.5 10 17.5 19 7" /></svg>;
const stages = ['بيانات الحساب', 'قنوات الاتصال', 'البيانات الشخصية والاجتماعية', 'إثبات الهوية', 'التوقيع'];

export function SubmitScreen({ onSubmit, busy = false }) {
  return (
    <Phone>
      <BrandBar />
      <StageHeader step={13} title="إرسال الطلب" />
      <Body gap={16} padding="15px var(--screen-gutter)">
        <div style={{ display: 'flex', flexDirection: 'column', gap: '7px' }}>
          <div style={{ fontSize: '22px', fontWeight: 'var(--weight-semibold)', lineHeight: 1.35 }}>اكتملت جميع الخطوات</div>
          <div style={{ fontSize: 'var(--size-body)', lineHeight: 'var(--line-prose)', color: 'var(--text-muted)' }}>سيتم إرسال بياناتك إلى البنك للمراجعة والاعتماد.</div>
        </div>
        <div style={{ border: '1px solid rgba(11,28,71,.16)' }}>
          {stages.map((s, i) => (
            <div key={s} style={{ display: 'flex', alignItems: 'center', gap: '11px', padding: '13px 14px', borderBottom: i === stages.length - 1 ? 'none' : '1px solid var(--line-soft)' }}>
              {done}<span style={{ flex: 1, fontSize: 'var(--size-body)' }}>{s}</span>
            </div>
          ))}
        </div>
        <Banner tone="warn"><strong>بعد الإرسال لا يمكن التعديل من التطبيق.</strong></Banner>
      </Body>
      <ActionBar><Button busy={busy} onClick={onSubmit}>{busy ? 'جاري الإرسال' : 'إرسال الطلب'}</Button></ActionBar>
    </Phone>
  );
}
