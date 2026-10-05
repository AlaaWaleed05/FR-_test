import React from 'react';
import { Phone, Body } from './Phone.jsx';
import { BrandBar } from '../../components/brand/BrandBar.jsx';
import { StageHeader } from '../../components/navigation/StageHeader.jsx';
import { Field, PhoneField, TextInput, Checkbox } from '../../components/forms/Field.jsx';
import { Button, ActionBar } from '../../components/actions/Button.jsx';
import { Banner } from '../../components/feedback/Banner.jsx';

const sms = <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="var(--steel-500)" strokeWidth="1.5" style={{ flex: '0 0 auto' }}><path d="M4 5h16v11H8l-4 4z" /></svg>;
const wa = <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="var(--steel-500)" strokeWidth="1.5" style={{ flex: '0 0 auto' }}><path d="M21 12a9 9 0 1 1-3.6-7.2L21 3l-1.2 4.2A9 9 0 0 1 21 12z" /></svg>;

export function ContactChannelsScreen({ onNext, onBack }) {
  const [smsOn, setSms] = React.useState(true);
  const [waOn, setWa] = React.useState(true);
  const [emailOn, setEmail] = React.useState(false);
  return (
    <Phone>
      <BrandBar />
      <StageHeader step={2} title="اختيار قنوات الاتصال" onBack={onBack} onAbandon={() => {}} />
      <Body gap={18} padding="15px var(--screen-gutter)">
        <div style={{ fontSize: 'var(--size-body)', lineHeight: 'var(--line-prose)', color: 'rgba(11,28,71,.8)' }}>
          اختر قنوات الاتصال التي تود اعتمادها في التواصل مع البنك
        </div>
        <Field label="رقم الهاتف"><PhoneField value="91 234 5678" /></Field>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
          <span style={{ fontSize: 'var(--size-helper)', color: 'var(--text-label)' }}>اختر وسيلة واحدة على الأقل لاستلام الرمز</span>
          <div onClick={() => setSms(!smsOn)} style={{ border: '1px solid rgba(11,28,71,.2)', padding: '14px', display: 'flex', alignItems: 'center', gap: '12px', cursor: 'pointer' }}>
            <Checkbox checked={smsOn}>{null}</Checkbox>{sms}<span style={{ fontSize: 'var(--size-body)' }}>الرسائل النصية</span>
          </div>
          <div onClick={() => setWa(!waOn)} style={{ border: '1px solid rgba(11,28,71,.2)', padding: '14px', display: 'flex', alignItems: 'center', gap: '12px', cursor: 'pointer' }}>
            <Checkbox checked={waOn}>{null}</Checkbox>{wa}<span style={{ fontSize: 'var(--size-body)' }}>واتساب</span>
          </div>
        </div>
        <Field label="البريد الإلكتروني (اختياري)"><TextInput value="mohamed@example.com" ltr /></Field>
        <div onClick={() => setEmail(!emailOn)} style={{ cursor: 'pointer' }}>
          <Checkbox checked={emailOn}>حفظ البريد الإلكتروني كوسيلة تواصل</Checkbox>
        </div>
        {!emailOn ? <Banner tone="warn">لن يتم حفظ البريد الإلكتروني.</Banner> : null}
      </Body>
      <ActionBar><Button disabled={!smsOn && !waOn} onClick={onNext}>التالي</Button></ActionBar>
    </Phone>
  );
}
