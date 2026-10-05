import { useId, useState } from 'react';
import type { ReactNode } from 'react';
import { INK, PALETTE } from '../../theme/palette';
import { MAX_FIELD_VALUE_LENGTH } from '../editableFields';
import { ROW_LABEL_STYLE, ROW_NUMBER_STYLE, ROW_STYLE, ROW_VALUE_STYLE } from './chrome';

/**
 * One field row on the approved profile screen: its number on the bank's paper form, its Arabic
 * label, its value — and, where the server says the field is editable, the «تعديل» affordance
 * that BL-135's `PATCH .../fields/{fieldKey}` endpoint is behind.
 *
 * **The chip is a courtesy and NEVER the control.** `editableFields` is derived and sent by the
 * server, and the server derives it AGAIN on the write path; a row that draws no chip is
 * presentation, and presentation is not a security boundary. That is why this component happily
 * renders a chip whenever asked and leaves every judgement about whether a field may be edited
 * to its caller and to the server.
 *
 * Two client-side rules mirror `operator.domain.FieldEditValidator` so an obviously bad value is
 * refused without a round trip: non-blank, and at most {@link MAX_FIELD_VALUE_LENGTH}. They are
 * NOT the enforcement — the server refuses the same values, plus control characters, and its
 * answer is what the row reports. Mirroring them here is the same "fast error" pattern
 * `RejectModal` already uses for REJ-07's mandatory note.
 */

export interface FieldRowProps {
  /** The field's number on the bank's paper form. Printed down the side of every row. */
  fieldNumber: number;
  label: string;
  /** The rendered value. A node rather than a string so a caller can wrap Latin text in `Num`. */
  children: ReactNode;
  /**
   * The `EditableField` name to PATCH, or `undefined` for a read-only row.
   *
   * The caller decides this from `detail.editableFields` AND the operator's role — `editableFields`
   * is deliberately not role-gated server-side, so a viewer receives a populated list and would
   * otherwise be shown chips whose PATCH is refused with a 403.
   */
  editKey?: string;
  /**
   * The current value as PLAIN TEXT, seeded into the input when editing opens.
   *
   * Separate from `children` because `children` may carry markup (a `<bdi>`, a joined address)
   * that must never end up inside a text input. Required whenever `editKey` is set.
   */
  editValue?: string | null;
  /**
   * Performs the edit. Resolves when the value is stored; rejects with a message to show in the
   * row. The caller reloads the profile afterwards — an edit changes the derived provenance, and
   * the server re-derives it on the read path.
   */
  onSave?: (fieldKey: string, value: string) => Promise<void>;
}

export default function FieldRow({
  fieldNumber,
  label,
  children,
  editKey,
  editValue,
  onSave,
}: FieldRowProps): React.JSX.Element {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputId = useId();

  const editable = editKey !== undefined && onSave !== undefined;

  const open = () => {
    setDraft(editValue ?? '');
    setError(null);
    setEditing(true);
  };

  const cancel = () => {
    setEditing(false);
    setError(null);
  };

  const save = async () => {
    if (!editKey || !onSave) return;
    const trimmed = draft.trim();
    // Mirrors FieldEditValidator: every editable field is MANDATORY in the mobile journey, so an
    // empty edit is always a deletion of data the journey required -- not a clearing gesture.
    if (trimmed === '') {
      setError('القيمة مطلوبة.');
      return;
    }
    if (trimmed.length > MAX_FIELD_VALUE_LENGTH) {
      setError(`الحد الأقصى ${MAX_FIELD_VALUE_LENGTH} حرفًا.`);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await onSave(editKey, trimmed);
      setEditing(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'تعذر حفظ التعديل.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div style={ROW_STYLE}>
      <span style={ROW_NUMBER_STYLE} aria-hidden="true">
        {fieldNumber}
      </span>
      {/*
        A real <label> bound to the input while editing, and a plain span otherwise. Without the
        binding the input's only accessible name would be its placeholder-less self, and an
        operator on a screen reader would hear "edit text" with no idea which field.
      */}
      {editing ? (
        <label htmlFor={inputId} style={ROW_LABEL_STYLE}>
          {label}
        </label>
      ) : (
        <span style={ROW_LABEL_STYLE}>{label}</span>
      )}

      <div style={{ ...ROW_VALUE_STYLE, display: 'flex', alignItems: 'flex-start', gap: 8, flexWrap: 'wrap' }}>
        {editing ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6, flexGrow: 1, minWidth: 0 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
              <input
                id={inputId}
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                onKeyDown={(e) => {
                  // Enter saves and Escape abandons -- an operator keying a run of fields should
                  // not have to reach for the mouse between each one.
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    void save();
                  } else if (e.key === 'Escape') {
                    e.preventDefault();
                    cancel();
                  }
                }}
                disabled={saving}
                autoFocus
                // DELIBERATELY NO `maxLength`. The attribute would silently TRUNCATE -- an
                // operator pasting a 210-character employer name would get 200 stored with
                // nothing saying so, which on a bank record is worse than a refusal. The check in
                // `save` refuses it instead and says why. Proved: with `maxLength` set, a pasted
                // over-long value reached `onSave` already clipped to 200.
                style={{
                  flexGrow: 1,
                  minWidth: 0,
                  fontFamily: 'inherit',
                  fontSize: 14,
                  // Explicit, because `index.css` sets `color-scheme: light dark` and a bare
                  // input would otherwise take UA dark styling against this white card.
                  color: PALETTE.TEXT,
                  background: '#fff',
                  border: `1px solid ${PALETTE.PURPLE}`,
                  borderRadius: 2,
                  padding: '4px 8px',
                }}
              />
              <button type="button" onClick={() => void save()} disabled={saving} style={savePillStyle(saving)}>
                حفظ
              </button>
              <button type="button" onClick={cancel} disabled={saving} style={cancelPillStyle}>
                إلغاء
              </button>
            </div>
            {error && (
              <span role="alert" style={{ color: PALETTE.RED, fontSize: 12 }}>
                {error}
              </span>
            )}
          </div>
        ) : (
          <>
            <div style={{ flexGrow: 1, minWidth: 0 }}>{children}</div>
            {editable && (
              <button
                type="button"
                onClick={open}
                // The label names the FIELD, not just the action. Four «تعديل» buttons in one
                // sub-section are indistinguishable to a screen reader otherwise.
                aria-label={`تعديل ${label}`}
                style={{
                  fontSize: 11,
                  padding: '2px 8px',
                  border: `1px solid ${PALETTE.PURPLE}`,
                  color: PALETTE.PURPLE,
                  background: INK.TINT,
                  font: 'inherit',
                  fontFamily: 'inherit',
                  cursor: 'pointer',
                  flexShrink: 0,
                  lineHeight: 1.6,
                }}
              >
                تعديل
              </button>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function savePillStyle(saving: boolean): React.CSSProperties {
  return {
    fontSize: 12,
    padding: '4px 12px',
    border: 'none',
    background: PALETTE.PURPLE,
    color: '#fff',
    fontFamily: 'inherit',
    cursor: saving ? 'default' : 'pointer',
    opacity: saving ? 0.7 : 1,
    flexShrink: 0,
  };
}

const cancelPillStyle: React.CSSProperties = {
  fontSize: 12,
  padding: '4px 12px',
  border: `1px solid ${INK.BORDER}`,
  background: '#fff',
  color: PALETTE.TEXT,
  fontFamily: 'inherit',
  cursor: 'pointer',
  flexShrink: 0,
};
