import React from 'react';

export function BrandBar({ background = 'var(--surface-brand)', curveFill = 'var(--surface-page)', action = null, rtl = true }) {
  return (
    <div style={{
      flex: '0 0 auto', position: 'relative', background,
      display: 'flex', alignItems: 'center', gap: '10px',
      padding: '10px 16px 19px', overflow: 'hidden',
    }}>
      <div style={{ position: 'absolute', inset: 0, background: rtl ? 'var(--glow-brand-rtl)' : 'var(--glow-brand)' }} />
      <DuneEdge fill={curveFill} rtl={rtl} />
      <Pearl size={34} />
      <img src="/assets/sfb-wordmark-white.png" alt="البنك السوداني الفرنسي"
           style={{ position: 'relative', height: '27px', width: 'auto', display: 'block' }} />
      {action ? <div style={{ position: 'relative', marginInlineStart: 'auto' }}>{action}</div> : null}
    </div>
  );
}

export function Pearl({ size = 34, ring = true }) {
  return (
    <div style={{
      position: 'relative', width: size, height: size, flex: '0 0 auto',
      borderRadius: 'var(--radius-round)', overflow: 'hidden', background: 'var(--paper)',
      boxShadow: ring ? 'var(--ring-pearl)' : 'none',
    }}>
      <img src="/assets/sfb-logo-circle.png" alt="" style={{ width: '100%', height: '100%', display: 'block', objectFit: 'cover' }} />
    </div>
  );
}

export function DuneEdge({ fill = 'var(--surface-page)', height = 17, rtl = true }) {
  const d = rtl
    ? 'M0,2 C78,11 140,4.6 195,6.5 C258,8.7 306,15.7 390,9'
    : 'M390,2 C312,11 250,4.6 195,6.5 C132,8.7 84,15.7 0,9';
  const closed = rtl ? `${d} L390,20 L0,20 Z` : `${d} L0,20 L390,20 Z`;
  return (
    <svg viewBox="0 0 390 20" width="100%" height={height} preserveAspectRatio="none"
         style={{ position: 'absolute', left: 0, bottom: '-1px' }}>
      <path d={closed} fill={fill} />
      <path d={d} fill="none" stroke="var(--steel-500)" strokeOpacity="0.55" strokeWidth="1" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}
