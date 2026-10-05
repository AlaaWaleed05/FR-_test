import * as React from 'react';

/**
 * Label + control + helper/error, stacked. The label turns red and the error
 * replaces the helper — the two never show at once.
 * @startingPoint section="Forms" subtitle="Labelled field with helper and error states" viewport="700x260"
 */
export interface FieldProps {
  label?: string;
  helper?: string;
  /** When set, the label and boundary go to --error and this replaces the helper. */
  error?: string;
  children?: React.ReactNode;
}
export declare function Field(props: FieldProps): JSX.Element;

export interface TextInputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  value?: string;
  /** Isolate the value LTR — account numbers, phone numbers, dates, amounts. */
  ltr?: boolean;
  invalid?: boolean;
  focused?: boolean;
}
export declare function TextInput(props: TextInputProps): JSX.Element;

/** Opens a full-screen reference picker. Never a native select. */
export interface PickerFieldProps { value?: string; placeholder?: string; onClick?: () => void }
export declare function PickerField(props: PickerFieldProps): JSX.Element;

export interface PhoneFieldProps { prefix?: string; value?: string }
export declare function PhoneField(props: PhoneFieldProps): JSX.Element;

export interface OtpInputProps { length?: number; value?: string }
export declare function OtpInput(props: OtpInputProps): JSX.Element;

export interface CheckboxProps { checked?: boolean; children?: React.ReactNode }
export declare function Checkbox(props: CheckboxProps): JSX.Element;

export interface RadioProps { checked?: boolean; children?: React.ReactNode }
export declare function Radio(props: RadioProps): JSX.Element;

/** Two or three mutually exclusive short options. Longer lists use PickerField. */
export interface SegmentedControlProps { options?: string[]; value?: string }
export declare function SegmentedControl(props: SegmentedControlProps): JSX.Element;
