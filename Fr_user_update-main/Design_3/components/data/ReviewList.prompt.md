Read-only data display: the registry review, the channel list, and the two number plates.

```jsx
<ReviewList>
  <ReviewRow label="الاسم" value="محمد الأمين عبدالله" provenance="السجل المدني" />
  <ReviewRow label="تاريخ الميلاد" value="14/03/1990" ltr last />
</ReviewList>
```

- Every reviewed value carries its provenance. Values from the civil registry or the document scan are shown, never edited.
- `IdentityPlate` exists so the customer can compare the number against the card in their hand — keep it large and LTR.
- `ReferencePlate` is the customer's only handle on their request afterwards; set `onBrand` on the navy confirmation.
