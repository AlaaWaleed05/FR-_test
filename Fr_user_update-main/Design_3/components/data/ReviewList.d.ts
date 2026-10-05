import * as React from 'react';

/**
 * Hairline-ruled list for reviewing data before submission.
 * @startingPoint section="Data" subtitle="Review rows, channels, identity plate" viewport="700x300"
 */
export interface ReviewListProps { children?: React.ReactNode }
export declare function ReviewList(props: ReviewListProps): JSX.Element;

export interface ReviewRowProps {
  label: string;
  value: React.ReactNode;
  /** LTR-isolate the value — numbers, dates, Latin names. */
  ltr?: boolean;
  /** Where the value came from: 'السجل المدني' | 'المسح — …' | 'إدخال العميل'. */
  provenance?: string;
  last?: boolean;
}
export declare function ReviewRow(props: ReviewRowProps): JSX.Element;

export interface ChannelRowProps {
  icon?: React.ReactNode;
  name: string;
  /** Masked destination, e.g. "+249 91 ••• 5678". */
  destination?: string;
  status?: string;
  statusTone?: 'ok' | 'error' | 'accent' | 'muted';
  last?: boolean;
}
export declare function ChannelRow(props: ChannelRowProps): JSX.Element;

export interface IdentityPlateProps { label?: string; value: string; note?: string }
export declare function IdentityPlate(props: IdentityPlateProps): JSX.Element;

export interface ReferencePlateProps { label?: string; value: string; onBrand?: boolean }
export declare function ReferencePlate(props: ReferencePlateProps): JSX.Element;
