import React from 'react';

const tones = {
  info:    { fill: 'rgba(89,128,166,.08)',  line: 'rgba(89,128,166,.45)',  ink: 'var(--text-body)', stroke: 'var(--navy-800)' },
  warn:    { fill: 'rgba(138,106,31,.08)',  line: 'rgba(138,106,31,.45)',  ink: 'var(--text-body)', stroke: 'var(--warn)' },
  error:   { fill: 'rgba(156,47,47,.06)',   line: 'rgba(156,47,47,.45)',   ink: 'var(--text-body)', stroke: 'var(--error)' },
  success: { fill: 'rgba(47,125,93,.08)',   line: 'rgba(47,125,93,.45)',   ink: 'var(--text-body)', stroke: 'var(--ok)' },
};

export function Banner({ tone = 'info', icon = null, children }) {
  const t = tones[tone];
  return (
    <div style={{ display: 'flex', gap: '11px', alignItems: 'flex-start', border: `1px solid ${t.line}`, background: t.fill, padding: '13px' }}>
      <span style={{ flex: '0 0 auto', marginTop: '2px', color: t.stroke, display: 'grid' }}>{icon || <InfoGlyph />}</span>
      <div style={{ fontSize: 'var(--size-helper)', lineHeight: 'var(--line-body)', color: t.ink }}>{children}</div>
    </div>
  );
}

/** Verbatim string from offline_banner.dart. Pinned under the header, never an error tone. */
export function OfflineBanner() {
  return (
    <div style={{ flex: '0 0 auto', display: 'flex', gap: '10px', alignItems: 'flex-start', background: 'rgba(138,106,31,.1)', borderBottom: '1px solid rgba(138,106,31,.4)', padding: '12px var(--screen-gutter)' }}>
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="var(--warn)" strokeWidth="1.5" style={{ flex: '0 0 auto', marginTop: '2px' }}>
        <path d="M3 12a9 9 0 0 1 9-9" /><path d="M12 21a9 9 0 0 0 9-9" /><path d="M4 4l16 16" />
      </svg>
      <div style={{ fontSize: 'var(--size-helper)', lineHeight: 'var(--line-body)' }}>
        لا يوجد اتصال بالإنترنت. يتم عرض بياناتك المحفوظة، وبعض الإجراءات غير متاحة حتى يعود الاتصال.
      </div>
    </div>
  );
}

export function Tag({ tone = 'neutral', children }) {
  const map = {
    neutral: { line: 'var(--line-rule)', ink: 'var(--text-muted)', fill: 'transparent' },
    accent:  { line: 'rgba(89,128,166,.5)', ink: 'var(--steel-500)', fill: 'transparent' },
    ok:      { line: 'rgba(47,125,93,.5)', ink: 'var(--ok)', fill: 'rgba(47,125,93,.08)' },
    error:   { line: 'rgba(156,47,47,.5)', ink: 'var(--error)', fill: 'rgba(156,47,47,.07)' },
  }[tone];
  return (
    <span style={{ border: `1px solid ${map.line}`, background: map.fill, color: map.ink, fontSize: 'var(--size-label)', padding: '4px 8px', whiteSpace: 'nowrap' }}>{children}</span>
  );
}

export function TerminalState({ tone = 'accent', icon, title, body, children }) {
  const stroke = { accent: 'var(--steel-500)', ok: 'var(--ok)', error: 'var(--error)', warn: 'var(--warn)' }[tone];
  return (
    <div style={{ flex: 1, padding: '0 24px', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: '16px', textAlign: 'center' }}>
      <div style={{ width: '62px', height: '62px', border: `1.5px solid ${stroke}`, display: 'grid', placeItems: 'center', color: stroke }}>{icon}</div>
      {title ? <div style={{ fontSize: '20px', fontWeight: 'var(--weight-semibold)' }}>{title}</div> : null}
      {body ? <div style={{ fontSize: 'var(--size-body)', lineHeight: 'var(--line-prose)', color: 'var(--text-muted)', maxWidth: '32ch' }}>{body}</div> : null}
      {children}
    </div>
  );
}

function InfoGlyph() {
  return <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"><circle cx="12" cy="12" r="9" /><path d="M12 11v5" /><path d="M12 8h.01" /></svg>;
}
