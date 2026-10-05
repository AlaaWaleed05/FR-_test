The header every journey screen wears, directly under the BrandBar.

```jsx
<StageHeader step={5} title="المهنة والدخل" onBack={goBack} />
```

- The journey is 13 steps: 1a account, 1b channels, 2 verification, then stages 3–12. The `/final-stages` gate is not a step — the customer never really sees it.
- Terminal screens (already-updated, session complete, scan blocked) pass no `step`, so no progress rule renders.
- The progress indicator is a PROPOSAL — the Flutter app has none today.
