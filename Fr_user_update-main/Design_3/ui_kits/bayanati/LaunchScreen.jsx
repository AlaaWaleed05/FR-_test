import React from 'react';
import { Phone } from './Phone.jsx';
import { Pearl } from '../../components/brand/BrandBar.jsx';
import { LoadingRule } from '../../components/navigation/StageHeader.jsx';

/** Splash. No action — it advances on its own once bootstrap resolves. */
export function LaunchScreen() {
  return (
    <Phone>
      <svg viewBox="0 0 390 560" width="390" height="560" preserveAspectRatio="none" style={{ position: 'absolute', left: 0, top: 0, display: 'block' }}>
        <defs>
          <linearGradient id="kitSky" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#0b1c47" /><stop offset="62%" stopColor="#0b1c47" /><stop offset="100%" stopColor="#132a5e" />
          </linearGradient>
          <radialGradient id="kitGlow" cx="50%" cy="85%" r="52%">
            <stop offset="0%" stopColor="#5980a6" stopOpacity=".5" /><stop offset="100%" stopColor="#5980a6" stopOpacity="0" />
          </radialGradient>
        </defs>
        <path d="M0,0 H390 V424 C335,424 274.3,418.4 248.6,449 A70 70 0 0 1 141.4,449 C115.7,418.4 55,424 0,424 Z" fill="url(#kitSky)" />
        <path d="M0,0 H390 V424 C335,424 274.3,418.4 248.6,449 A70 70 0 0 1 141.4,449 C115.7,418.4 55,424 0,424 Z" fill="url(#kitGlow)" />
        <path d="M0,442 C52,442 104,430 130.7,458 A84 84 0 0 0 259.3,458 C286,430 338,442 390,442" fill="none" stroke="#5980a6" strokeOpacity=".45" strokeWidth="1.2" />
        <path d="M0,458 C48,458 88,442 115.3,470.9 A104 104 0 0 0 274.7,470.9 C301.7,442 342,458 390,458" fill="none" stroke="#5980a6" strokeOpacity=".22" strokeWidth="1" />
      </svg>

      <div style={{ position: 'absolute', left: 0, right: 0, top: '404px', display: 'flex', justifyContent: 'center' }}>
        <div style={{ position: 'relative', transform: 'translateY(-50%)', display: 'grid', placeItems: 'center', width: '156px', height: '156px' }}>
          <div style={{ position: 'absolute', width: '156px', height: '156px', borderRadius: '50%', background: 'radial-gradient(circle, rgba(255,255,255,.3) 0%, rgba(255,255,255,0) 68%)', animation: 'bayanati-halo var(--dur-halo) var(--ease-loop) infinite' }} />
          <div style={{ position: 'absolute', width: '140px', height: '140px', borderRadius: '50%', border: '1px solid rgba(89,128,166,.55)' }} />
          <Pearl size={112} ring={false} />
        </div>
      </div>

      <div style={{ position: 'absolute', left: 0, right: 0, top: '512px', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '14px' }}>
        <div style={{ fontFamily: 'var(--font-display)', fontSize: 'var(--size-display)', lineHeight: 1, color: 'var(--text-body)' }}>بـيـانـاتـي</div>
        <div style={{ width: '46px', height: '1px', background: 'var(--line-strong)' }} />
        <div style={{ fontFamily: 'var(--font-display)', fontSize: '22px', color: 'var(--text-body)' }}>لؤلؤة المصارف</div>
        <div style={{ marginTop: '10px', fontSize: 'var(--size-body)', color: 'var(--text-muted)' }}>خطوات بسيطة لتحديث بياناتك</div>
      </div>

      <div style={{ position: 'absolute', left: 0, right: 0, bottom: '74px', display: 'flex', justifyContent: 'center' }}>
        <img src="/assets/sfb-wordmark-navy.png" alt="البنك السوداني الفرنسي" style={{ width: '200px', height: 'auto', display: 'block', opacity: 0.8 }} />
      </div>
      <div style={{ position: 'absolute', left: 0, right: 0, bottom: '44px', display: 'flex', justifyContent: 'center' }}><LoadingRule /></div>
    </Phone>
  );
}
