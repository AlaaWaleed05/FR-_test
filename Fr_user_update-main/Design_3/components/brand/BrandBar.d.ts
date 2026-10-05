import * as React from 'react';

/**
 * The brand bar that sits at the top of every journey screen.
 * @startingPoint section="Brand" subtitle="Pearl + wordmark over the dune curve" viewport="390x64"
 */
export interface BrandBarProps {
  /** Bar fill. Use --surface-brand on paper screens, --navy-600 on navy ones. */
  background?: string;
  /** Colour of the screen BELOW the bar — the dune curve is cut out in this colour. */
  curveFill?: string;
  /** Optional trailing action (e.g. an abandon button). */
  action?: React.ReactNode;
  /** RTL mirrors the curve sweep and the glow. Arabic-first, so true by default. */
  rtl?: boolean;
}
export declare function BrandBar(props: BrandBarProps): JSX.Element;

export interface PearlProps { size?: number; ring?: boolean }
export declare function Pearl(props: PearlProps): JSX.Element;

export interface DuneEdgeProps { fill?: string; height?: number; rtl?: boolean }
export declare function DuneEdge(props: DuneEdgeProps): JSX.Element;
