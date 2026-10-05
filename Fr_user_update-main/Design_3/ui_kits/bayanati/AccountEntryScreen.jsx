import React from 'react';
import { Phone, Body } from './Phone.jsx';
import { BrandBar } from '../../components/brand/BrandBar.jsx';
import { StageHeader } from '../../components/navigation/StageHeader.jsx';
import { Field, TextInput, PickerField } from '../../components/forms/Field.jsx';
import { Button, ActionBar } from '../../components/actions/Button.jsx';
import { OfflineBanner } from '../../components/feedback/Banner.jsx';

export function AccountEntryScreen({ onNext, error, offline = false }) {
  return (
    <Phone>
      <BrandBar />
      <StageHeader step={1} title="بيانات الحساب" />
      {offline ? <OfflineBanner /> : null}
      <Body gap={20} padding="16px var(--screen-gutter)">
        <div style={{ fontSize: 'var(--size-body)', lineHeight: 'var(--line-prose)', color: 'rgba(11,28,71,.8)' }}>
          أدخل رقم حسابك والفرع الذي فتحت فيه الحساب للبدء.
        </div>
        <Field label="رقم الحساب" error={error}>
          <TextInput value={error ? '0012 4417 0000' : '0012 4417 8890'} ltr invalid={!!error} />
        </Field>
        <Field label="الفرع"><PickerField value="فرع الخرطوم — الشارع الأول" /></Field>
      </Body>
      <ActionBar><Button disabled={!!error} onClick={onNext}>التالي</Button></ActionBar>
    </Phone>
  );
}
