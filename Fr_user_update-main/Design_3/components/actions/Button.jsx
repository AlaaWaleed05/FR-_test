import React from 'react';

const base = {
  fontFamily: 'inherit', fontSize: 'var(--size-body)', fontWeight: 'var(--weight-semibold)',
  cursor: 'pointer', borderRadius: 'var(--radius)', display: 'flex',
  alignItems: 'center', justifyContent: 'center', gap: '10px',
};

export function Button({ variant = 'primary', block = true, disabled = false, busy = false, children, ...rest }) {
  const v = {
    primary:   { background: 'var(--navy-800)', color: 'var(--text-on-brand)', border: 'none' },
    secondary: { background: 'transparent', color: 'var(--text-body)', border: '1.4px solid var(--navy-800)' },
    ghost:     { background: 'transparent', color: 'var(--text-body)', border: 'none' },
    danger:    { background: 'transparent', color: 'var(--error)', border: '1.4px solid var(--error)' },
    inverse:   { background: 'var(--paper)', color: 'var(--navy-800)', border: 'none' },
  }[variant];
  return (
    <button type="button" disabled={disabled || busy} style={{
      ...base, ...v,
      height: 'var(--action-h)',
      width: block ? '100%' : 'auto',
      padding: block ? 0 : '0 26px',
      opacity: disabled ? 0.45 : 1,
    }} {...rest}>
      {busy ? <Spinner /> : null}
      {children}
    </button>
  );
}

export function IconButton({ label, size = 44, tone = 'ink', children, ...rest }) {
  const color = tone === 'onBrand' ? 'var(--text-on-brand)' : 'var(--text-body)';
  const border = tone === 'onBrand' ? '1.3px solid rgba(255,255,255,.85)' : 'none';
  return (
    <button type="button" aria-label={label} style={{
      width: size, height: size, minWidth: 'var(--tap-min)', minHeight: 'var(--tap-min)',
      display: 'grid', placeItems: 'center', background: 'transparent',
      border, color, cursor: 'pointer', padding: 0,
    }} {...rest}>{children}</button>
  );
}

export function ActionBar({ children }) {
  return (
    <div style={{
      flex: '0 0 auto', padding: '14px var(--screen-gutter) 22px',
      borderTop: '1px solid var(--line-soft)', display: 'flex', gap: '10px',
    }}>{children}</div>
  );
}

function Spinner() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
      <circle cx="12" cy="12" r="9" strokeOpacity="0.35" />
      <path d="M21 12a9 9 0 0 0-9-9" />
    </svg>
  );
}
