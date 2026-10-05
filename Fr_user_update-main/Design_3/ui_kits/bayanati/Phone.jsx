import React from 'react';

/** The 390×844 journey canvas. Header slot, scrolling body, pinned action bar. */
export function Phone({ children, background = 'var(--surface-page)', color }) {
  return (
    <div dir="rtl" style={{
      width: 'var(--screen-w)', height: 'var(--screen-h)', background, color,
      overflow: 'hidden', display: 'flex', flexDirection: 'column',
      fontFamily: 'var(--font-body)', position: 'relative',
    }}>{children}</div>
  );
}

export function Body({ children, gap = 16, padding = '14px var(--screen-gutter)' }) {
  return <div style={{ flex: 1, overflow: 'hidden', padding, display: 'flex', flexDirection: 'column', gap }}>{children}</div>;
}
