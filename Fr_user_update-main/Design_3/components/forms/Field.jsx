import React from 'react';

export function Field({ label, helper, error, children }) {
  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--field-gap)' }}>
      {label ? (
        <span style={{ fontSize: 'var(--size-helper)', color: error ? 'var(--error)' : 'var(--text-label)' }}>{label}</span>
      ) : null}
      {children}
      {error ? (
        <span style={{ display: 'flex', alignItems: 'flex-start', gap: '6px', fontSize: 'var(--size-helper)', color: 'var(--error)', lineHeight: 1.55 }}>
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ flex: '0 0 auto', marginTop: '3px' }}>
            <circle cx="12" cy="12" r="9" /><path d="M12 7v6" /><path d="M12 17h.01" />
          </svg>
          {error}
        </span>
      ) : helper ? (
        <span style={{ fontSize: 'var(--size-helper)', color: 'var(--text-muted)' }}>{helper}</span>
      ) : null}
    </label>
  );
}

export function TextInput({ value, ltr = false, invalid = false, focused = false, ...rest }) {
  return (
    <input
      type="text"
      defaultValue={value}
      style={{
        minWidth: 0, boxSizing: 'border-box', height: 'var(--control-h)',
        border: `${invalid || focused ? '1.5px' : '1px'} solid ${invalid ? 'var(--error)' : focused ? 'var(--steel-500)' : 'var(--line-field)'}`,
        outline: focused ? '2px solid var(--focus-ring)' : 'none',
        background: 'var(--paper)', padding: '0 14px',
        fontFamily: 'inherit', fontSize: 'var(--size-body)', color: 'var(--text-body)',
        ...(ltr ? { direction: 'ltr', textAlign: 'right' } : null),
      }}
      {...rest}
    />
  );
}

export function PickerField({ value, placeholder, onClick }) {
  const empty = !value;
  return (
    <button type="button" onClick={onClick} style={{
      height: 'var(--control-h)', border: '1px solid var(--line-field)', background: 'var(--paper)',
      padding: '0 14px', fontFamily: 'inherit', fontSize: 'var(--size-body)',
      color: empty ? 'var(--text-subtle)' : 'var(--text-body)',
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      cursor: 'pointer', textAlign: 'start',
    }}>
      <span>{empty ? placeholder : value}</span>
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="var(--steel-500)" strokeWidth="1.5"><path d="M6 9l6 6 6-6" /></svg>
    </button>
  );
}

export function PhoneField({ prefix = '+249', value }) {
  return (
    <div style={{ display: 'flex', border: '1px solid var(--line-field)', background: 'var(--paper)', height: 'var(--control-h)', alignItems: 'center' }}>
      <div dir="ltr" style={{ padding: '0 12px', borderInlineEnd: '1px solid var(--line-rule)', fontSize: 'var(--size-body)', color: 'var(--text-muted)', height: '100%', display: 'flex', alignItems: 'center' }}>{prefix}</div>
      <input type="text" defaultValue={value} style={{ flex: 1, minWidth: 0, border: 'none', outline: 'none', background: 'transparent', padding: '0 12px', fontFamily: 'inherit', fontSize: 'var(--size-body)', color: 'var(--text-body)', direction: 'ltr', textAlign: 'right', height: '100%' }} />
    </div>
  );
}

export function OtpInput({ length = 6, value = '' }) {
  return (
    <div dir="ltr" style={{ display: 'grid', gridTemplateColumns: `repeat(${length}, 1fr)`, gap: 'var(--space-2)' }}>
      {Array.from({ length }).map((_, i) => {
        const ch = value[i];
        const active = i === value.length;
        return (
          <div key={i} style={{
            height: 'var(--otp-h)',
            border: `${active ? '1.5px solid var(--steel-500)' : '1px solid var(--line-field)'}`,
            display: 'grid', placeItems: 'center', fontSize: '22px', fontWeight: 'var(--weight-semibold)',
          }}>{ch || ''}</div>
        );
      })}
    </div>
  );
}

export function Checkbox({ checked = false, children }) {
  return (
    <label style={{ display: 'flex', gap: '11px', alignItems: 'flex-start', cursor: 'pointer' }}>
      <span style={{
        width: '22px', height: '22px', flex: '0 0 auto',
        border: `1.5px solid ${checked ? 'var(--navy-800)' : 'rgba(11,28,71,.4)'}`,
        background: checked ? 'var(--navy-800)' : 'var(--paper)',
        display: 'grid', placeItems: 'center', marginTop: '2px',
      }}>
        {checked ? <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12.5 10 17.5 19 7" /></svg> : null}
      </span>
      <span style={{ fontSize: '15px', lineHeight: 'var(--line-body)' }}>{children}</span>
    </label>
  );
}

export function Radio({ checked = false, children }) {
  return (
    <label style={{ display: 'flex', alignItems: 'center', gap: '9px', cursor: 'pointer' }}>
      <span style={{
        width: '20px', height: '20px', flex: '0 0 auto', borderRadius: 'var(--radius-round)',
        border: `1.5px solid ${checked ? 'var(--navy-800)' : 'rgba(11,28,71,.4)'}`,
        display: 'grid', placeItems: 'center',
      }}>
        {checked ? <span style={{ width: '10px', height: '10px', borderRadius: 'var(--radius-round)', background: 'var(--navy-800)' }} /> : null}
      </span>
      <span style={{ fontSize: 'var(--size-body)' }}>{children}</span>
    </label>
  );
}

export function SegmentedControl({ options = [], value }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: `repeat(${options.length}, 1fr)`, border: '1px solid var(--line-field)' }}>
      {options.map((o) => {
        const on = o === value;
        return (
          <div key={o} style={{
            height: '50px', display: 'grid', placeItems: 'center',
            background: on ? 'var(--navy-800)' : 'var(--paper)',
            color: on ? 'var(--text-on-brand)' : 'var(--text-body)',
            fontSize: '15px', fontWeight: on ? 'var(--weight-semibold)' : 'var(--weight-regular)',
          }}>{o}</div>
        );
      })}
    </div>
  );
}
