import * as React from 'react';

/**
 * Camera targets and document depictions for the identity stages.
 * @startingPoint section="Capture" subtitle="Scan target, document cards, signature pad" viewport="700x300"
 */
export interface CaptureFrameProps {
  /** 'card' draws corner brackets; 'oval' is the liveness target. */
  shape?: 'card' | 'oval';
  ratio?: number;
  hint?: string;
  children?: React.ReactNode;
}
export declare function CaptureFrame(props: CaptureFrameProps): JSX.Element;

/**
 * Schematic depiction of an identity document — photo box, data lines, and
 * (for a passport) two MRZ rows. Deliberately abstract: no state emblem, no
 * real document artwork.
 */
export interface DocumentCardProps {
  kind?: 'id' | 'passport';
  label: string;
  selected?: boolean;
  onClick?: () => void;
}
export declare function DocumentCard(props: DocumentCardProps): JSX.Element;

export interface SignaturePadProps { signed?: boolean; clearLabel?: string; hint?: string }
export declare function SignaturePad(props: SignaturePadProps): JSX.Element;
