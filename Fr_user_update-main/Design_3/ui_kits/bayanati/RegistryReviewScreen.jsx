import React from 'react';
import { Phone, Body } from './Phone.jsx';
import { BrandBar } from '../../components/brand/BrandBar.jsx';
import { StageHeader } from '../../components/navigation/StageHeader.jsx';
import { ReviewList, ReviewRow, IdentityPlate } from '../../components/data/ReviewList.jsx';
import { Button } from '../../components/actions/Button.jsx';

export function RegistryReviewScreen({ onNext }) {
  return (
    <Phone>
      <BrandBar />
      <StageHeader step={10} title="مراجعة نتائج المسح" />
      <Body gap={12} padding="13px var(--screen-gutter)">
        <IdentityPlate value="12345678901" note="قارن هذا الرقم بالرقم المدوّن في وثيقتك قبل المتابعة." />
        <ReviewList>
          <ReviewRow label="الاسم" value="محمد الأمين عبدالله حسن" />
          <ReviewRow label="اسم الأم" value="فاطمة إبراهيم علي" />
          <ReviewRow label="الاسم بالإنجليزية" value="MOHAMED ALAMIN ABDALLA" ltr />
          <ReviewRow label="النوع" value="ذكر" />
          <ReviewRow label="تاريخ الميلاد" value="14/03/1990" ltr />
          <ReviewRow label="العنوان" value="أم درمان — الثورة — مربع 22" last />
        </ReviewList>
      </Body>
      <div style={{ flex: '0 0 auto', padding: '12px var(--screen-gutter) 20px', borderTop: '1px solid var(--line-soft)', display: 'flex', flexDirection: 'column', gap: '8px' }}>
        <Button onClick={onNext}>البيانات صحيحة، متابعة</Button>
        <Button variant="ghost" style={{ height: '42px', fontSize: 'var(--size-helper)' }}>الرقم الوطني غير صحيح</Button>
        <Button variant="ghost" style={{ height: '42px', fontSize: 'var(--size-helper)' }}>الرقم صحيح لكن بياناتي غير صحيحة</Button>
      </div>
    </Phone>
  );
}
