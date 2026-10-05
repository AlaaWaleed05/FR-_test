Brand presence for a journey screen — the pearl mark, the Arabic/Latin wordmark, and the dune curve that separates the bar from the screen below.

```jsx
<BrandBar curveFill="var(--surface-page)" />
```

- `curveFill` must match the background of whatever sits under the bar, or the curve will not read as a cut-out.
- On a navy screen set `background="var(--navy-600)"` so the bar separates from the field.
- `Pearl` and `DuneEdge` are exported separately: `Pearl` for the launch hero (size 112, ring off), `DuneEdge` anywhere a navy block meets paper.
- Never place a BrandBar on the launch screen — the pearl is already the hero there.
