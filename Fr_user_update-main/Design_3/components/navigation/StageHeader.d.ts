import * as React from 'react';

/**
 * Step label, screen title, back and abandon, over the progress rule.
 * @startingPoint section="Navigation" subtitle="Stage header with step progress" viewport="390x120"
 */
export interface StageHeaderProps {
  /** 1-based position in the 13-step journey. Omit on terminal screens. */
  step?: number;
  total?: number;
  title: string;
  onBack?: () => void;
  /** Renders the abandon affordance in the header — never as a bottom button. */
  onAbandon?: () => void;
  abandonLabel?: string;
}
export declare function StageHeader(props: StageHeaderProps): JSX.Element;

export interface ScreenTitleProps { children?: React.ReactNode }
export declare function ScreenTitle(props: ScreenTitleProps): JSX.Element;

export interface ProgressBarProps { step: number; total?: number }
export declare function ProgressBar(props: ProgressBarProps): JSX.Element;

/** The indeterminate hairline used on splash and the resume gate. */
export declare function LoadingRule(): JSX.Element;
