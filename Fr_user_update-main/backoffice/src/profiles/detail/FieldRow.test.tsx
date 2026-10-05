import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import FieldRow from './FieldRow';

/**
 * The per-field editing affordance — BL-135's client half.
 *
 * `FieldRow` is deliberately dumb about WHETHER a field may be edited: the server derives that
 * and sends it, and derives it again on the write path. These tests are about what the row does
 * once its caller has decided, and about the two client-side rules that mirror
 * `FieldEditValidator` so an obviously bad value costs no round trip.
 */
describe('FieldRow', () => {
  it('renders its number, label and value, and no chip when the field is read-only', () => {
    render(
      <FieldRow fieldNumber={7} label="الرقم الوطني">
        000-0000-0011
      </FieldRow>,
    );

    expect(screen.getByText('الرقم الوطني')).toBeInTheDocument();
    expect(screen.getByText('000-0000-0011')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /تعديل/ })).not.toBeInTheDocument();
  });

  it('draws no chip when a key is given but no handler, and vice versa', () => {
    // Both halves are required to edit. A row with one and not the other would offer an
    // affordance that could not do anything.
    const { rerender } = render(
      <FieldRow fieldNumber={38} label="المنطقة" editKey="HOME_AREA" editValue="الثورة">
        الثورة
      </FieldRow>,
    );
    expect(screen.queryByRole('button', { name: /تعديل/ })).not.toBeInTheDocument();

    rerender(
      <FieldRow fieldNumber={38} label="المنطقة" onSave={vi.fn()} editValue="الثورة">
        الثورة
      </FieldRow>,
    );
    expect(screen.queryByRole('button', { name: /تعديل/ })).not.toBeInTheDocument();
  });

  it('names the FIELD in the chip, not just the action', async () => {
    render(
      <FieldRow fieldNumber={40} label="الشارع" editKey="HOME_STREET" editValue="شارع الأربعين" onSave={vi.fn()}>
        شارع الأربعين
      </FieldRow>,
    );

    // Four «تعديل» chips in one sub-section are indistinguishable to a screen reader otherwise.
    expect(screen.getByRole('button', { name: 'تعديل الشارع' })).toBeInTheDocument();
  });

  it('seeds the editor with the current value and saves the trimmed result', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <FieldRow fieldNumber={39} label="المدينة" editKey="HOME_CITY" editValue="أم درمان" onSave={onSave}>
        أم درمان
      </FieldRow>,
    );

    await user.click(screen.getByRole('button', { name: 'تعديل المدينة' }));
    const input = screen.getByLabelText('المدينة');
    expect(input).toHaveValue('أم درمان');

    await user.clear(input);
    await user.type(input, '  بحري  ');
    await user.click(screen.getByRole('button', { name: 'حفظ' }));

    // Trimmed, matching FieldEditValidator: mobile stores `controller.text.trim()` for every one
    // of these fields, so an untrimmed operator edit would put a value in the column the app
    // could not have produced.
    await waitFor(() => expect(onSave).toHaveBeenCalledWith('HOME_CITY', 'بحري'));
  });

  it('closes the editor on a successful save', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <FieldRow fieldNumber={41} label="المربع" editKey="HOME_BLOCK" editValue="12" onSave={onSave}>
        12
      </FieldRow>,
    );

    await user.click(screen.getByRole('button', { name: 'تعديل المربع' }));
    await user.click(screen.getByRole('button', { name: 'حفظ' }));

    await waitFor(() => expect(screen.queryByRole('button', { name: 'حفظ' })).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'تعديل المربع' })).toBeInTheDocument();
  });

  it('refuses a blank value without calling the server', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <FieldRow fieldNumber={10} label="الجنس" editKey="ETHNICITY" editValue="شايقية" onSave={onSave}>
        شايقية
      </FieldRow>,
    );

    await user.click(screen.getByRole('button', { name: 'تعديل الجنس' }));
    await user.clear(screen.getByLabelText('الجنس'));
    await user.click(screen.getByRole('button', { name: 'حفظ' }));

    // Every editable field is MANDATORY in the mobile journey, so an empty edit is always a
    // deletion of data the journey required -- not a clearing gesture.
    expect(onSave).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent('القيمة مطلوبة.');
  });

  it('treats whitespace as blank, as the server does', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <FieldRow fieldNumber={10} label="الجنس" editKey="ETHNICITY" editValue="شايقية" onSave={onSave}>
        شايقية
      </FieldRow>,
    );

    await user.click(screen.getByRole('button', { name: 'تعديل الجنس' }));
    await user.clear(screen.getByLabelText('الجنس'));
    await user.type(screen.getByLabelText('الجنس'), '   ');
    await user.click(screen.getByRole('button', { name: 'حفظ' }));

    expect(onSave).not.toHaveBeenCalled();
  });

  it('abandons the edit on cancel, keeping the original value', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <FieldRow fieldNumber={42} label="رقم المنزل" editKey="HOME_HOUSE_NO" editValue="47" onSave={onSave}>
        47
      </FieldRow>,
    );

    await user.click(screen.getByRole('button', { name: 'تعديل رقم المنزل' }));
    await user.clear(screen.getByLabelText('رقم المنزل'));
    await user.type(screen.getByLabelText('رقم المنزل'), '99');
    await user.click(screen.getByRole('button', { name: 'إلغاء' }));

    expect(onSave).not.toHaveBeenCalled();
    expect(screen.getByText('47')).toBeInTheDocument();
  });

  it('reopens on the STORED value, not on the abandoned draft', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <FieldRow fieldNumber={42} label="رقم المنزل" editKey="HOME_HOUSE_NO" editValue="47" onSave={onSave}>
        47
      </FieldRow>,
    );

    await user.click(screen.getByRole('button', { name: 'تعديل رقم المنزل' }));
    await user.clear(screen.getByLabelText('رقم المنزل'));
    await user.type(screen.getByLabelText('رقم المنزل'), '99');
    await user.click(screen.getByRole('button', { name: 'إلغاء' }));
    await user.click(screen.getByRole('button', { name: 'تعديل رقم المنزل' }));

    // A draft that survived a cancel would put an operator one keystroke from storing a value
    // they had just decided against.
    expect(screen.getByLabelText('رقم المنزل')).toHaveValue('47');
  });

  it('shows the server message and keeps the editor open when the save is refused', async () => {
    const onSave = vi.fn().mockRejectedValue(new Error('لم يعد هذا الحقل قابلاً للتعديل — أُعيد تحميل الملف.'));
    const user = userEvent.setup();
    render(
      <FieldRow fieldNumber={38} label="المنطقة" editKey="HOME_AREA" editValue="الثورة" onSave={onSave}>
        الثورة
      </FieldRow>,
    );

    await user.click(screen.getByRole('button', { name: 'تعديل المنطقة' }));
    await user.click(screen.getByRole('button', { name: 'حفظ' }));

    // The editor STAYS open: closing it would discard what the operator typed and leave them
    // re-keying a value that may well have been fine.
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('لم يعد هذا الحقل قابلاً للتعديل'),
    );
    expect(screen.getByRole('button', { name: 'حفظ' })).toBeInTheDocument();
  });

  it('saves on Enter and abandons on Escape', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <FieldRow fieldNumber={33} label="الشارع" editKey="WORK_STREET" editValue="شارع النيل" onSave={onSave}>
        شارع النيل
      </FieldRow>,
    );

    // An operator keying a run of fields should not have to reach for the mouse between each.
    await user.click(screen.getByRole('button', { name: 'تعديل الشارع' }));
    await user.type(screen.getByLabelText('الشارع'), '{Enter}');
    await waitFor(() => expect(onSave).toHaveBeenCalledWith('WORK_STREET', 'شارع النيل'));

    await user.click(screen.getByRole('button', { name: 'تعديل الشارع' }));
    await user.type(screen.getByLabelText('الشارع'), 'x{Escape}');
    expect(screen.queryByRole('button', { name: 'حفظ' })).not.toBeInTheDocument();
    expect(onSave).toHaveBeenCalledTimes(1);
  });

  it('refuses a value past the server ceiling, without a round trip', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <FieldRow fieldNumber={27} label="جهة العمل" editKey="EMPLOYER_NAME" editValue="أ" onSave={onSave}>
        أ
      </FieldRow>,
    );

    await user.click(screen.getByRole('button', { name: 'تعديل جهة العمل' }));
    const input = screen.getByLabelText('جهة العمل');
    // Pasted rather than typed, because a paste is how an over-long value actually arrives.
    // There is no `maxLength` on the input by design -- it would truncate silently -- so this
    // check is the only thing between a 201-character value and the server.
    await user.clear(input);
    await user.paste('ب'.repeat(201));
    await user.click(screen.getByRole('button', { name: 'حفظ' }));

    expect(onSave).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent('200');
  });

  it('disables both controls while the save is in flight', async () => {
    let release: () => void = () => {};
    const onSave = vi.fn().mockReturnValue(new Promise<void>((resolve) => { release = resolve; }));
    const user = userEvent.setup();
    render(
      <FieldRow fieldNumber={32} label="المدينة" editKey="WORK_CITY" editValue="الخرطوم" onSave={onSave}>
        الخرطوم
      </FieldRow>,
    );

    await user.click(screen.getByRole('button', { name: 'تعديل المدينة' }));
    await user.click(screen.getByRole('button', { name: 'حفظ' }));

    await waitFor(() => expect(screen.getByRole('button', { name: 'حفظ' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'إلغاء' })).toBeDisabled();
    expect(screen.getByLabelText('المدينة')).toBeDisabled();

    release();
    await waitFor(() => expect(screen.queryByRole('button', { name: 'حفظ' })).not.toBeInTheDocument());
  });

  it('binds the label to the input while editing', async () => {
    const user = userEvent.setup();
    render(
      <FieldRow fieldNumber={31} label="المنطقة" editKey="WORK_AREA" editValue="المقرن" onSave={vi.fn()}>
        المقرن
      </FieldRow>,
    );

    await user.click(screen.getByRole('button', { name: 'تعديل المنطقة' }));
    // Without the binding the input's accessible name would be empty and an operator on a screen
    // reader would hear "edit text" with no idea which field they were in.
    expect(screen.getByLabelText('المنطقة')).toHaveAttribute('id');
  });
});
