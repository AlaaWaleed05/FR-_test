import * as React from 'react';

/**
 * One primary per screen, pinned to the bottom in an ActionBar.
 * @startingPoint section="Actions" subtitle="Primary, secondary, ghost, danger, inverse" viewport="700x220"
 */
export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  /** inverse is for navy grounds only (confirmation screen). */
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger' | 'inverse';
  /** Full width. True by default — mobile actions span the gutter. */
  block?: boolean;
  disabled?: boolean;
  /** Shows a spinner and blocks input. */
  busy?: boolean;
  children?: React.ReactNode;
}
export declare function Button(props: ButtonProps): JSX.Element;

/** Square, borderless on paper; outlined on a navy ground. Never below 44px. */
export interface IconButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  label: string;
  size?: number;
  tone?: 'ink' | 'onBrand';
  children?: React.ReactNode;
}
export declare function IconButton(props: IconButtonProps): JSX.Element;

export interface ActionBarProps { children?: React.ReactNode }
export declare function ActionBar(props: ActionBarProps): JSX.Element;
