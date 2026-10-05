import * as React from 'react';

/**
 * Inline message tied to the screen it appears on. Never a toast — this journey
 * is completed once, so nothing important is allowed to time out.
 * @startingPoint section="Feedback" subtitle="Info, warn, error and offline banners" viewport="700x260"
 */
export interface BannerProps {
  tone?: 'info' | 'warn' | 'error' | 'success';
  icon?: React.ReactNode;
  children?: React.ReactNode;
}
export declare function Banner(props: BannerProps): JSX.Element;

/** Carries the verbatim offline string. Warn tone — going offline is expected, not a fault. */
export declare function OfflineBanner(): JSX.Element;

export interface TagProps { tone?: 'neutral' | 'accent' | 'ok' | 'error'; children?: React.ReactNode }
export declare function Tag(props: TagProps): JSX.Element;

/** Full-screen end state: framed icon, title, body, optional action. */
export interface TerminalStateProps {
  tone?: 'accent' | 'ok' | 'error' | 'warn';
  icon?: React.ReactNode;
  title?: string;
  body?: string;
  children?: React.ReactNode;
}
export declare function TerminalState(props: TerminalStateProps): JSX.Element;
