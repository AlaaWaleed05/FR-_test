import React from 'react';
import { Phone, Body } from './Phone.jsx';
import { BrandBar } from '../../components/brand/BrandBar.jsx';
import { StageHeader } from '../../components/navigation/StageHeader.jsx';
import { DocumentCard } from '../../components/capture/CaptureFrame.jsx';
import { Button, ActionBar } from '../../components/actions/Button.jsx';

export function IdentityTypeScreen({ onNext, onBack }) {
  const [kind, setKind] = React.useState('id');
  return (
    <Phone>
      <BrandBar />
      <StageHeader step={8} title="نوع وثيقة الهوية" onBack={onBack} />
      <Body gap={20} padding="13px var(--screen-gutter)">
        <div style={{ fontSize: 'var(--size-body)', lineHeight: 'var(--line-prose)', color: 'rgba(11,28,71,.8)' }}>
          اختر الوثيقة التي ستستخدمها لإثبات هويتك. سيتم مسحها ضوئيًا في الخطوة التالية، ولن تحتاج إلى إدخال أي من بياناتها يدويًا.
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(0,1fr)', gap: '12px' }}>
          <DocumentCard kind="id" label="الرقم الوطني" selected={kind === 'id'} onClick={() => setKind('id')} />
          <DocumentCard kind="passport" label="جواز السفر" selected={kind === 'passport'} onClick={() => setKind('passport')} />
        </div>
      </Body>
      <ActionBar>
        <Button variant="secondary" block={false} onClick={onBack}>السابق</Button>
        <Button onClick={onNext}>التالي</Button>
      </ActionBar>
    </Phone>
  );
}
