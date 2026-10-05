# Component: Back-office UI components

Status: integrated (S6-01: shell, auth, `Table`/`Select`/`DatePicker.RangePicker`; S6-02:
`Descriptions`/`Timeline`/`Image.PreviewGroup`/`Modal`/`Popconfirm` for the single profile view) ·
**PARTLY SUPERSEDED BY AD-021 AT S9-02 (2026-09-16): antd is dropped from the sign-in and the
single profile screen, which are now built to `Design_3/backoffice/approved/`. The rows below
marked S9-02 no longer describe the running code.** ·
Last verified: 2026-09-16
Source: docs/sessions/2026-08-30-research-client-components.md;
docs/sessions/2026-09-03-s6-01-backoffice-foundation.md;
docs/sessions/2026-09-03-s6-02-profile-view.md

antd **6.6.1**, MIT [OBSERVED backoffice/node_modules/antd/package.json].
React 19.2.8 · Vite 8 · `<ConfigProvider direction="rtl" locale={ar_EG}>` already wired
[OBSERVED backoffice/src/main.tsx]. DatePicker is dayjs-based (`dayjs ^1.11.11` is a direct
antd dependency) — no date-adapter needed.

## Mapping
| Need | Component | Not covered |
|---|---|---|
| Profile list | `Table` (+`Tag` for status/provenance) | Cross-field search; all filtering/sorting must be server-side |
| Date-range filter | `DatePicker.RangePicker` | **RTL unverified — see open items** |
| Single-profile data | ~~`Descriptions`~~ **none — bespoke** | **S9-02: GONE.** AD-021's approved artboard is bare inline-styled HTML and "the screen matches `profile-screen.dc.html`" is not reachable by retinting `ConfigProvider` tokens. Nine `Descriptions` blocks became three sections built from `profiles/detail/chrome.tsx` and `profiles/detail/FieldRow.tsx`. No `Descriptions` usage remains anywhere in `src/` |
| Status history | ~~`Timeline`~~ **none — bespoke** | **S9-02: GONE**, same reason. The history itself is KEPT (the artboard omits it, `operator.md` still requires it) and renders as plain rows inside a section card |
| Images | `Image` + `Image.PreviewGroup` | **Wired, BL-075 closed at S8-23; a sixth tile added at S8-24 (BL-136).** `profiles/ArtifactContactSheet.tsx` renders ticket 02's Variant B contact sheet — six tiles, Arabic captions, **S9-02: restyled to the approved palette, six columns at `repeat(auto-fit, minmax(min(100%, 150px), 1fr))` and 104px media, with a SECOND caption line naming each image's source — which needs `scanResult.documentType` threaded in, so a passport customer is not told the bank holds their national ID. Previously `repeat(auto-fill, minmax(190px, 1fr))`.** The `Image`/`Modal` pair SURVIVES the antd drop: what AD-021 displaces is the chrome, not a lightbox with no bare-HTML equivalent, one `PreviewGroup` as the enlarge modal, and no attachments table. `src` is `GET /api/v1/operator/profiles/{id}/artifacts/{artifactId}` (R-046 CLOSED — the `<img>`-carries-no-header premise never applied, since the back office is same-origin and uses a session cookie). Only the six viewable kinds may be linked; `profiles/artifactTiles.ts` owns that list. **The sixth is not always an image:** a `salary_certificate` may be `application/pdf`, which gets a document tile and an antd `Modal` holding an `<iframe>` rather than an `Image` — a new tab would put the artifact id in browser history, which ticket 01 forbids |
| Approve / reject | `Modal` + `Form` + `Select` (~~+ `Popconfirm`~~) | **S9-02: `Popconfirm` GONE** — the approved action bar is two 54px buttons with the notification line beneath, and no confirm step; `RejectModal` and `PrintFormModal` keep antd, being separate components rather than the screen. Gated on the operator's own role and the profile's status. The server-computed `canApprove` this row used to name was removed with the four-eyes rule — AD-013, S8-27 |
| Roles | — | **No permission model in antd.** Bespoke |
| Export trigger | `Button` + `Dropdown` | File generation is server-side (POI) |
| Dashboard (BL-001) | `Statistic`, `Progress` | **No charts.** Re-research when scheduled |

## RTL — verified from installed source
antd v6's `@rc-component/table ~1.11.1` normalises `fixed: 'left'|true → 'start'` and
`'right' → 'end'` [OBSERVED es/hooks/useColumns/index.js:43] and applies
`insetInlineStart`/`insetInlineEnd` [OBSERVED es/Cell/index.js:98,103]. **The antd v5 RTL
fixed-column inversion (#52942, closed and labelled for 6.x) is fixed in the version
installed.** Any v5-era snippet advising `fixed: 'right'` for RTL is now wrong.

Still open upstream: `Menu` subMenu RTL positioning (#47488), `Table` responsive column
title in RTL (#32679). `DatePicker` panel order + missing RangePicker arrow (#49664) closed
with no documented fix version — **[UNVERIFIED against 6.6.1]**.

## `Image` — the derivative/original pattern, verified
`preview?: boolean | PreviewConfig`, and `PreviewConfig` carries its **own** `src`
[OBSERVED @rc-component/image/es/Preview/index.d.ts:53]:

    <Image src={derivativeUrl}
           preview={{ src: fullResolutionUrl, onOpenChange: recordImageViewAudit }} />

`onOpenChange` is the hook for operator.md's separate "document image viewed" audit event.
v6 deprecates `visible`/`onVisibleChange`/`toolbarRender` → `open`/`onOpenChange`/`actionsRender`.

## `Table` — the standing rule
**Ant Design does no filtering and no sorting.** Declare `sorter: true` (boolean, never a
comparator), supply `filters` for menu options with controlled `filteredValue`, never write
`onFilter`, and funnel everything through `onChange` to the server. Three independent
reasons: antd filters client-side over the current page only; `ref.ar_fold` and
`sort_ordinal` have no browser equivalent and `localeCompare('ar')` will disagree; and
operator.md requires "search and filter executed" as an audit event, which a client-side
filter cannot produce. `virtual` exists but is not for us — server pagination means one page
in the client.

`column.filterSearch` searches the **filter menu's own labels**, not records, and does no
Arabic folding. Where that matters, pass it a predicate — the type allows it
(`filterSearch?: FilterSearchType<ColumnFilterItem>` [OBSERVED es/table/interface.d.ts:123])
— using the TypeScript `ar_fold` port.

## Export — server-side, not npm
`backend/` + Apache POI **5.5.1** (2025-11-30, Apache-2.0) via SXSSF streaming, for both
XLSX and CSV. `dhatim/fastexcel` 0.20.2 is a lighter substitute that changes no contract.
**Rejected:** npm `xlsx` frozen at 0.18.5 on the registry while SheetJS ships current builds
elsewhere; `exceljs` 4.4.0 last released 2024-10-19 with 659 open issues / 143 open PRs
[OBSERVED registry.npmjs.org + github.com]. Deciding reason is not licence but architecture:
the list is server-paginated, so the browser does not hold the 10,000 rows, and the export
audit event (operator, filters, row count, fields) is server-side truth.
**CSV must carry a UTF-8 BOM** or Excel on Windows renders Arabic as mojibake
[UNVERIFIED against the operators' actual Excel — OQ-008].

## Open items
- [x] Render `DatePicker.RangePicker` under `direction="rtl"` and look at it — **done at S6-01**,
      live in a real browser (Playwright, not just a unit test). Renders correctly: two-panel
      calendar, weekday names genuinely Arabic, no clipping/overlap. Panel order is the earlier
      month on the right (closer to the "start" input) and the later month on the left — RTL-
      consistent, not the v5 LTR-mirrored bug the component card's own note above already closed.
      Month/year headers render in English (`dayjs` default), same as the profile list's own
      `submittedAt` column formatting — an existing, consistent choice, not a new gap.
- [x] Verify `Timeline` `mode` and `Image.PreviewGroup` arrows under RTL — **done at S6-02**, live
      in a real browser (Playwright) against the single-profile view. `Timeline` at its default
      mode (no `mode` prop set) already RTL-adapts correctly: the dot/connector line renders on the
      right of each entry's text, matching reading direction, with no LTR-mirrored artifact — the
      type system's `ItemPosition` already exposing logical `'start'`/`'end'` alongside physical
      `'left'`/`'right'` was the signal this would hold, confirmed rather than assumed.
      `Image.PreviewGroup`'s prev/next switch buttons are RTL-consistent in function: opening the
      DOM-first image (which renders on the visual right under the RTL flex row) correctly disables
      "prev" and enables "next", and the enabled "next" control sits on the LEFT of the screen —
      the direction you actually advance toward in RTL, same "physically consistent, not
      LTR-mirrored" pattern the `DatePicker.RangePicker` row above already found. The chevron glyph
      itself is not flipped (a `>`-shaped icon on the left, pointing away from the direction it
      advances) — cosmetic, not a functional RTL defect, left as-is.
- [x] **New finding, not a pre-existing open item**: dates and phone numbers rendered as plain text
      inside an RTL context get visually reordered by the browser's bidi algorithm — an all-digit
      string with no strong-direction (letter) character to anchor it has none to resist the
      surrounding RTL paragraph. Found live via this session's own Playwright screenshots:
      `dayjs().format('YYYY-MM-DD HH:mm')` rendered as `"11:40 2026-09-01"` (time first), and
      `"+249912340001"` rendered as `"249912340001+"` (sign at the visual end). Closed by wrapping
      both in `<bdi>` (native HTML bidi-isolation, no CSS/JS needed) in `ProfileDetailPage` and
      `ProfileListPage`'s `submittedAt` column — the latter carried the identical defect since
      S6-01, just never screenshotted at pixel level before. Genuinely Arabic text is unaffected
      and untouched (`<bdi>` only changes behavior for content with no strong-direction anchor).
- [ ] TypeScript `ar_fold` port + shared golden-vector fixture (third implementation) — still not
      built. **New, narrower instance found at S6-01**: the profile list's branch and
      rejection-reason filter `Select`s use antd's own `showSearch`/`optionFilterProp`, a plain
      substring match with no Arabic folding, over small bounded lists (25 branches, 7 rejection
      reasons) — an operator typing "المعموره" (ه) into that filter's search box will not match
      the stored "المعمورة" (ة). Distinct from the main free-text list search (`q`), which is
      fully server-side and already `ref.ar_fold`-correct. Left as a disclosed, accepted gap
      given the lists' small size — closing it properly is exactly the TypeScript port this row
      already calls for, not a one-off fix.
- [x] Choose routing / data fetching / auth-state libraries — **settled at S6-01**:
      `react-router-dom` 7.18.3 (MIT) for routing; native `fetch` for data fetching (no client
      library — AD-006's assemble-vs-build reasoning didn't earn one for one list endpoint plus
      two small reference lists); React Context (`AuthContext`) for auth state, no state-
      management library.
- [ ] BL-001 charts: re-research `@ant-design/plots` 2.6.8 vs `recharts` 3.10.1 when scheduled;
      neither inherits CSS `direction` — budget a day for RTL chart configuration
