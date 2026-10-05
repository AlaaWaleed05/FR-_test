import React from 'react';

/** Navy capture target with corner brackets. Used for document scan and liveness. */
export function CaptureFrame({ shape = 'card', ratio = 1.586, hint, children }) {
  return (
    <div style={{ position: 'relative', aspectRatio: String(ratio), background: 'var(--surface-brand)', display: 'grid', placeItems: 'center', overflow: 'hidden' }}>
      <div style={{ position: 'absolute', inset: 0, background: 'radial-gradient(90% 90% at 50% 30%, rgba(89,128,166,.4) 0%, rgba(11,28,71,0) 70%)' }} />
      {shape === 'card' ? <CardTarget /> : <OvalTarget />}
      <div style={{ position: 'relative', color: 'rgba(255,255,255,.9)', fontSize: 'var(--size-helper)', textAlign: 'center', padding: '0 30px' }}>{children}</div>
      {hint ? <div style={{ position: 'absolute', bottom: '14px', left: 0, right: 0, textAlign: 'center', color: 'rgba(255,255,255,.9)', fontSize: 'var(--size-helper)' }}>{hint}</div> : null}
    </div>
  );
}

function CardTarget() {
  const arm = { position: 'absolute', width: '26px', height: '26px' };
  return (
    <>
      <div style={{ position: 'absolute', inset: '20px', border: '1.5px dashed rgba(255,255,255,.4)' }} />
      <div style={{ position: 'absolute', inset: '20px' }}>
        <i style={{ ...arm, insetInlineStart: '-1px', top: '-1px', borderTop: '3px solid #fff', borderInlineStart: '3px solid #fff' }} />
        <i style={{ ...arm, insetInlineEnd: '-1px', top: '-1px', borderTop: '3px solid #fff', borderInlineEnd: '3px solid #fff' }} />
        <i style={{ ...arm, insetInlineStart: '-1px', bottom: '-1px', borderBottom: '3px solid #fff', borderInlineStart: '3px solid #fff' }} />
        <i style={{ ...arm, insetInlineEnd: '-1px', bottom: '-1px', borderBottom: '3px solid #fff', borderInlineEnd: '3px solid #fff' }} />
      </div>
    </>
  );
}

function OvalTarget() {
  return <div style={{ position: 'relative', width: '134px', height: '172px', border: '1.5px dashed rgba(255,255,255,.55)', borderRadius: '50%' }} />;
}

/** Schematic depiction of a document, for the identity-type picker. */
export function DocumentCard({ kind = 'id', label, selected = false, onClick }) {
  return (
    <label onClick={onClick} style={{
      display: 'flex', flexDirection: 'column', background: 'var(--paper)', cursor: 'pointer',
      border: selected ? '1.5px solid var(--navy-800)' : '1px solid rgba(11,28,71,.25)',
    }}>
      <div style={{ padding: '11px 11px 0' }}>
        <div style={{ aspectRatio: '1.5', background: 'var(--surface-well)', border: '1px solid rgba(11,28,71,.16)', padding: '9px', display: 'flex', flexDirection: kind === 'passport' ? 'column' : 'row', gap: kind === 'passport' ? '7px' : '9px' }}>
          <div style={{ flex: 1, display: 'flex', gap: '9px' }}>
            <div style={{ width: '34%', background: '#d7e0ea', border: '1px solid rgba(11,28,71,.14)' }} />
            <div style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: '5px' }}>
              <div style={{ height: '5px', width: '78%', background: 'rgba(11,28,71,.32)' }} />
              <div style={{ height: '4px', width: '92%', background: 'rgba(11,28,71,.16)' }} />
              <div style={{ height: '4px', width: '60%', background: 'rgba(11,28,71,.16)' }} />
              {kind === 'id' ? <div style={{ height: '10px', width: '24%', marginTop: '5px', background: 'rgba(11,28,71,.1)', border: '1px solid rgba(11,28,71,.18)' }} /> : null}
            </div>
          </div>
          {kind === 'passport' ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '3px' }}>
              <div style={{ height: '5px', background: 'repeating-linear-gradient(90deg, rgba(11,28,71,.32) 0 4px, transparent 4px 7px)' }} />
              <div style={{ height: '5px', background: 'repeating-linear-gradient(90deg, rgba(11,28,71,.32) 0 4px, transparent 4px 7px)' }} />
            </div>
          ) : null}
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '11px 12px 12px' }}>
        <span style={{ width: '20px', height: '20px', flex: '0 0 auto', borderRadius: '50%', border: `1.5px solid ${selected ? 'var(--navy-800)' : 'rgba(11,28,71,.4)'}`, display: 'grid', placeItems: 'center' }}>
          {selected ? <span style={{ width: '10px', height: '10px', borderRadius: '50%', background: 'var(--navy-800)' }} /> : null}
        </span>
        <span style={{ fontSize: 'var(--size-body)', fontWeight: selected ? 'var(--weight-semibold)' : 'var(--weight-regular)' }}>{label}</span>
      </div>
    </label>
  );
}

export function SignaturePad({ signed = true, clearLabel = 'مسح والإعادة', hint = 'وقّع فوق الخط' }) {
  return (
    <div style={{ position: 'relative', aspectRatio: '1.55', background: 'var(--paper)', border: '1px solid var(--line-field)', display: 'grid', placeItems: 'center' }}>
      {signed ? (
        <svg viewBox="0 0 300 120" style={{ width: '80%', height: 'auto' }}>
          <path d="M18 84c26-42 44 16 66-12s30-40 52-14 34 34 58 6 44-18 66-6" fill="none" stroke="var(--navy-800)" strokeWidth="2.6" strokeLinecap="round" />
        </svg>
      ) : null}
      <div style={{ position: 'absolute', bottom: '34px', left: '24px', right: '24px', height: '1px', background: 'var(--line-rule)' }} />
      <div style={{ position: 'absolute', bottom: '10px', insetInlineStart: '14px', fontSize: 'var(--size-label)', color: 'var(--text-subtle)' }}>{hint}</div>
      {signed ? <div style={{ position: 'absolute', bottom: '10px', insetInlineEnd: '14px', fontSize: 'var(--size-caption)', color: 'var(--steel-500)' }}>{clearLabel}</div> : null}
    </div>
  );
}
