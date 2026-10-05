Actions. Square corners, 54px tall, full-width by default.

```jsx
<ActionBar>
  <Button variant="secondary" block={false}>السابق</Button>
  <Button>التالي</Button>
</ActionBar>
```

- Exactly one `primary` per screen.
- Abandoning a session is never a bottom action — it lives in the header as a small `ghost`/`danger` so muscle memory can't hit it.
- `inverse` exists only for the confirmation screen's navy field.
