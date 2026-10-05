import React from 'react';
import { Phone } from './Phone.jsx';
import { ReferencePlate } from '../../components/data/ReviewList.jsx';
import { Button } from '../../components/actions/Button.jsx';

/** The second and last navy moment in the whole app. */
export function ConfirmationScreen({ onFinish, reference = 'FRU-2026-004182' }) {
  return (
    <Phone background="var(--surface-brand)" color="var(--text-on-brand)">
      <div style={{ position: 'absolute', inset: 0, background: 'var(--glow-hero)' }} />
      <svg viewBox="0 0 390 60" width="100%" height="58" preserveAspectRatio="none" style={{ position: 'absolute', left: 0, bottom: 0 }}>
        <path d="M0,30 C88,64 134,20 197,10 C252,1 314,40 390,-4 L390,60 L0,60 Z" fill="var(--navy-700)" opacity=".85" />
      </svg>
      <div style={{ position: 'relative', flex: 1, padding: '0 24px', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: '16px', textAlign: 'center' }}>
        <div style={{ width: '74px', height: '74px', borderRadius: '50%', border: '1.5px solid rgba(255,255,255,.5)', display: 'grid', placeItems: 'center' }}>
          <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12.5 10 17.5 19 7" /></svg>
        </div>
        <div style={{ fontSize: '25px', fontWeight: 'var(--weight-semibold)', lineHeight: 1.45 }}>تم إرسال طلبك إلى البنك للمراجعة والاعتماد</div>
        <div style={{ fontSize: '15px', color: 'var(--text-on-brand-muted)', lineHeight: 'var(--line-body)' }}>لم يتم اعتماد التحديث بعد. سيتم إشعارك بالنتيجة.</div>
        <ReferencePlate value={reference} onBrand />
        <div style={{ fontSize: 'var(--size-helper)', color: 'var(--text-on-brand-muted)', lineHeight: 'var(--line-prose)', maxWidth: '34ch' }}>
          احتفظ بهذا الرقم، فهو مرجعك الوحيد عند مراجعة الفرع للاستفسار عن طلبك.
        </div>
      </div>
      <div style={{ position: 'relative', flex: '0 0 auto', padding: '14px 20px 26px' }}>
        <Button variant="inverse" onClick={onFinish}>إنهاء</Button>
      </div>
    </Phone>
  );
}
