The form layer. Every control is square, hairline-bounded, and at least 50px tall.

```jsx
<Field label="رقم الحساب" helper="كما يظهر في دفتر الحساب">
  <TextInput value="0012 4417 8890" ltr />
</Field>
```

- `ltr` on any numeric value — account numbers, phones, dates, amounts. Arabic-Indic digits may be TYPED; everything displayed is Latin, tabular and direction-isolated.
- `PickerField` for reference lists (branch, occupation, country/state/locality) — it opens a searchable full-screen picker, never a dropdown.
- `SegmentedControl` only for 2–3 short options; anything longer is a PickerField.
- Error text belongs on the field, not in a toast — the failing field also takes focus.
