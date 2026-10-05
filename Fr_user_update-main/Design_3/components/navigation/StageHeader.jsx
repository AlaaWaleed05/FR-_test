import React from 'react';
import { IconButton } from '../actions/Button.jsx';

export function StageHeader({ step, total = 13, title, onBack, onAbandon, abandonLabel = 'التراجع' }) {
  return (
    <div style={{ flex: '0 0 auto', borderBottom: '1px solid rgba(11,28,71,.12)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '6px', padding: '8px 12px 0' }}>
        {onBack ? (
          <IconButton label="رجوع" onClick={onBack}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M9 18l6-6-6-6" /></svg>
          </IconButton>
        ) : <div style={{ width: '6px' }} />}
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: '2px' }}>
          {step ? <div style={{ fontFamily: 'var(--font-label)', fontSize: 'var(--size-label)', letterSpacing: '0.16em', color: 'var(--text-accent)' }}>STEP {step} / {total}</div> : null}
          <ScreenTitle>{title}</ScreenTitle>
        </div>
        {onAbandon ? (
          <button type="button" onClick={onAbandon} style={{ height: '40px', padding: '0 10px', background: 'transparent', border: 'none', color: 'var(--error)', fontFamily: 'inherit', fontSize: 'var(--size-caption)', fontWeight: 'var(--weight-semibold)', cursor: 'pointer' }}>{abandonLabel}</button>
        ) : null}
      </div>
      {step ? <ProgressBar step={step} total={total} /> : <div style={{ height: '12px' }} />}
    </div>
  );
}

/** Shrinks to fit rather than truncating — mirrors ScreenTitle/BoxFit.scaleDown in the app. */
export function ScreenTitle({ children }) {
  return (
    <div style={{
      fontSize: 'var(--size-screen-title)', fontWeight: 'var(--weight-semibold)',
      lineHeight: 'var(--line-tight)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'clip',
    }}>{children}</div>
  );
}

export function ProgressBar({ step, total = 13 }) {
  const pct = Math.max(0, Math.min(100, Math.round((step / total) * 100)));
  return (
    <div style={{ height: '3px', background: 'var(--steel-100)', margin: '9px var(--screen-gutter) 11px' }}>
      <div style={{ width: `${pct}%`, height: '100%', background: 'var(--navy-800)' }} />
    </div>
  );
}

export function LoadingRule() {
  return (
    <div style={{ width: '120px', height: '1.5px', background: 'rgba(11,28,71,.15)', overflow: 'hidden' }}>
      <div style={{ width: '100%', height: '100%', background: 'var(--navy-800)', animation: 'bayanati-load var(--dur-progress) var(--ease-progress) infinite' }} />
    </div>
  );
}
