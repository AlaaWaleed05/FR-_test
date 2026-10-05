import React from 'react';
import { Tag } from '../feedback/Banner.jsx';

export function ReviewList({ children }) {
  return <div style={{ border: '1px solid rgba(11,28,71,.16)' }}>{children}</div>;
}

export function ReviewRow({ label, value, ltr = false, provenance, last = false }) {
  return (
    <div style={{ display: 'flex', gap: '12px', alignItems: 'flex-start', padding: '9px 14px', borderBottom: last ? 'none' : '1px solid var(--line-soft)' }}>
      <div style={{ width: '96px', flex: '0 0 auto', fontSize: 'var(--size-helper)', color: 'var(--text-muted)' }}>{label}</div>
      <div dir={ltr ? 'ltr' : undefined} style={{ flex: 1, fontSize: '15px', fontWeight: 'var(--weight-semibold)', lineHeight: 1.55, ...(ltr ? { textAlign: 'right', fontVariantNumeric: 'tabular-nums' } : null) }}>{value}</div>
      {provenance ? <Tag tone={provenance === 'السجل المدني' ? 'accent' : 'neutral'}>{provenance}</Tag> : null}
    </div>
  );
}

export function ChannelRow({ icon, name, destination, status, statusTone = 'muted', last = false }) {
  const tone = { ok: 'var(--ok)', error: 'var(--error)', accent: 'var(--steel-500)', muted: 'var(--text-muted)' }[statusTone];
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: '12px', padding: '15px 16px', borderBottom: last ? 'none' : '1px solid var(--line-soft)' }}>
      <span style={{ flex: '0 0 auto', color: 'var(--steel-500)', display: 'grid' }}>{icon}</span>
      <div style={{ flex: 1 }}>
        <div style={{ fontSize: '15px', fontWeight: 'var(--weight-semibold)' }}>{name}</div>
        {destination ? <div dir="ltr" style={{ fontSize: 'var(--size-helper)', color: 'var(--text-muted)', textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>{destination}</div> : null}
      </div>
      {status ? <span style={{ fontSize: 'var(--size-caption)', fontWeight: 'var(--weight-semibold)', color: tone }}>{status}</span> : null}
    </div>
  );
}

/** The identity number, shown big so it can be compared against the physical document. */
export function IdentityPlate({ label = 'الرقم الوطني', value, note }) {
  return (
    <div style={{ border: '1.5px solid var(--navy-800)', padding: '16px', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '6px' }}>
      <div style={{ fontSize: 'var(--size-helper)', color: 'var(--text-muted)' }}>{label}</div>
      <div dir="ltr" style={{ fontSize: '30px', fontWeight: 'var(--weight-semibold)', fontVariantNumeric: 'tabular-nums', letterSpacing: '0.04em' }}>{value}</div>
      {note ? <div style={{ fontSize: 'var(--size-caption)', color: 'var(--text-muted)', textAlign: 'center', lineHeight: 'var(--line-body)' }}>{note}</div> : null}
    </div>
  );
}

export function ReferencePlate({ label = 'الرقم المرجعي', value, onBrand = false }) {
  const line = onBrand ? 'rgba(255,255,255,.35)' : 'var(--line-field)';
  const muted = onBrand ? 'var(--text-on-brand-muted)' : 'var(--text-muted)';
  return (
    <div style={{ border: `1px solid ${line}`, padding: '14px 24px', display: 'flex', flexDirection: 'column', gap: '5px' }}>
      <div style={{ fontSize: 'var(--size-caption)', color: muted }}>{label}</div>
      <div dir="ltr" style={{ fontSize: '24px', fontWeight: 'var(--weight-semibold)', fontVariantNumeric: 'tabular-nums', letterSpacing: '0.03em' }}>{value}</div>
    </div>
  );
}
