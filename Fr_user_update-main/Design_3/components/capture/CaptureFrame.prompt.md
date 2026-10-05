The identity stages: document scan, liveness, signature.

```jsx
<CaptureFrame shape="card">ضع البطاقة داخل الإطار</CaptureFrame>
<DocumentCard kind="passport" label="جواز السفر" />
```

- The oval in `CaptureFrame shape="oval"` is the only oval in the system — it is a camera affordance, not a style.
- `DocumentCard` is schematic on purpose. Do not substitute artwork of a real Sudanese ID or passport.
- The scan itself is handled by the Uqudo SDK; these frames are the app's own before/after surfaces.
