# S9-02 — the back-office rebuild, and BL-157 reversed

**2026-09-16 · S9-02 now ✅ · eight gated commits, all pushed**

`48effeb` BL-156 · `40e0281` the sign-in · `2bdb193` the profile screen · `d1a1e00` reconciliation ·
`910332a` commit proof · `dd07397` BL-155's interim state · `d464784` two rulings ·
`1330eb1` the bidi fix

The remaining half of S9-02: the back office rebuilt to `Design_3/backoffice/approved/`. The
backend half shipped earlier the same day (`624db55`, `e7cea6c`, `03260bb`) and is not revisited
here. Backend untouched this session, so `./mvnw verify -Pdb-integration-test` was not required.

Closed: **BL-156, BL-157, BL-160.** BL-160 was filed and dispositioned the same day — see §10.

---

## 1. The pre-build audit, and the four rulings it produced

Two `@agent-reviewer` passes ran before any edit — one against the artboards and the real code,
one against the plan's own factual claims. Between them they found nine places the brief or the
backlog asserted something the code contradicted. Four changed what gets built and went to the
product owner; five were corrections of fact.

| Question | Ruling |
|---|---|
| The artboard header duplicates `AppShell`'s — two headers, two sign-outs | **The profile screen leaves `AppShell`.** The list screen keeps the old shell. |
| Tile captions and order differ from the shipped `artifactTiles.ts`; BL-159 ruled only the COUNT | **Follow the artboard**, and thread `scanResult.documentType` in for the source line. |
| Fields 25/26 drawn unconditionally, against §2.4 | **Gate on verification.** Phone renders on `sms` OR `whatsapp`; email on `email`. |
| BL-157's premise | **False — reverse it.** See §3. |

**Corrections taken as stated, all verified against the file:**

- **`editableFields` was not on the TypeScript `ProfileDetailResponse`**, though the Java record
  has carried it since `624db55`. The brief asserted the screen could simply read it.
- **Field 7 had no backing TS field.** `identityNumberReturned` exists on the Java view — added
  at BL-132 for exactly this reason — and the TS type stopped short of it. Section 1 is wholly
  Civil Registry, so field 7 must not come from the scan's copy, which is equal only by a guard.
- **`editableFields` is status-gated but deliberately NOT role-gated**
  (`OperatorProfileViewService`: "the set is identical for a viewer and an operator, because it
  describes the PROFILE, not the caller"). A viewer receives a populated list, so the chip must
  gate on `canOperate` client-side or every chip they press 403s.
- **The artboard draws 14 chips; the enum has 19.** The five without one are the non-Sudan
  fallbacks (24, 29, 30, 36, 37), invisible on the artboard because its fixture is a Sudan
  profile. A hardcoded 14 would leave every customer abroad unable to have their address
  corrected, and R-042 records that those are not rare.
- **The `.ant-spin` re-anchoring the brief asked for was a no-op as stated** — `RequireAuth.tsx`
  is not one of the two screens dropping antd. Re-anchored to a `data-testid` anyway, as hardening
  rather than necessity. Stated rather than silently skipped.

---

## 2. BL-156, and why its prescribed fix did not work (commit `48effeb`)

The ticket's diagnosis was right: antd's `message` is a module-level singleton mounting into a
container appended to `<body>`, outside what RTL's `cleanup()` unmounts, so one test's toast is
still in the document when the next queries it.

**The prescribed fix — `message.destroy()` in the setup's `afterEach` — was measured not to
work.** `destroy()` does not remove the node; it starts antd's fade-leave animation, and jsdom
implements no CSS transitions and fires no `transitionend`. The notice settles on
`ant-message-fade-leave-active` and stays for ever. Probed directly: still present synchronously,
after a macrotask, and after 100ms, with one `.ant-message` container still a child of `<body>`.

The fix is `destroy()` **plus** removing the stranded `.ant-message-notice` roots — each does a
different half, the first emptying the singleton's own queue and the second the DOM. Deliberately
not the container: removing that instead was tried first and broke four `ProfileDetailPage` print
tests, because antd caches its holder and the next toast rendered into a detached node.

Detaching nodes React still owns would normally risk a later `removeChild` failure; it cannot
here only because antd's message sets no `motionDeadline`, so the leave motion never completes and
React never unmounts the fiber. Recorded in the file, since an antd minor adding one would surface
as exactly that error. `setup.test.tsx` then makes the flake deterministic rather than leaving it
to be re-measured: one test raises a toast, the next asserts the document it inherits is clean.

---

## 3. BL-157 reversed (commit `2bdb193`)

BL-157 was filed on the premise that **"no alpha-3 to alpha-2 mapping exists anywhere in the
repo"**, and concluded that fields 4 and 48 could only ever show the raw MRZ code where the
artboard draws «السودان». The premise is false, and the second reviewer pass caught it:

```sql
-- V0022__seed_country.sql:254
('SD', 'السودان', 'Sudan', '{"alpha3":"SDN"}'::jsonb),
```

All 249 rows carry it. `ReferenceDocumentItem.extra` is `unknown` on the wire and is passed
through untouched, and `useReferenceList` already returns it — so the browser has had the mapping
all along. `useAlpha3LabelMap` indexes the same server-supplied, version-checked list by alpha-3,
which hardcodes nothing and is what CLAUDE.md's reference-list rule requires.

A miss still falls back to the raw code, and that is not a hedge: an MRZ issuer is an **ICAO**
alpha-3, not an ISO one — `XXA`, `GBD`, `RKS` and `D` among others have no ISO row, and a dash
would destroy information the document actually carried.

**Field 48 was never in BL-157's scope and had the identical defect.** Both closed together.

---

## 4. Review findings and dispositions

`@agent-reviewer` ran on each commit's diff. Four findings were defects a green suite could not
have caught, and all four are worth stating because none is a typo.

**The field grid became three or four columns on a real monitor.** `FieldGrid` used
`repeat(auto-fit, minmax(min(100%, 380px), 1fr))`, which makes `floor(width / 380)` tracks —
three from about 1182px and four at 1920px. The sections order their rows **column-major** to
reproduce the artboard's two columns (section 1 emits `5, 8, 6, 9, 7, 21`), so at three tracks
that interleaving scrambles into an order nobody chose. **jsdom computes no layout**, so all 255
tests passed against it. Pinned to two tracks, and a test now pins the DOM order so a well-meaning
tidy into numeric order fails rather than silently rearranging the screen.

**The income editor could load one value and save it over another.** `incomeOtherText` seeded from
`find((s) => s.otherText !== null)` — the first row carrying any text. V0006's `other_needs_text`
CHECK is one-directional: it requires text on the `OTHER` row and forbids none on the others. So a
non-`OTHER` row carrying text is storable, and the chip would have seeded from it and then written
that value into the `OTHER` row, overwriting what the customer typed. Now found by its code, which
is what `DataEntryService` does on the server for the same reason.

**`formatDateOnly`'s explicit format did nothing.** dayjs ignores a format argument unless
`customParseFormat` is registered, and nothing in `backoffice/src` registered it. Measured on bare
dayjs:

```
dayjs('09/11/1994','YYYY-MM-DD').isValid() -> true, .format('DD/MM/YYYY') -> '11/09/1994'
```

Day and month inverted, `isValid()` true, so the raw-value fallback never fires and nothing shows
the error — the precise failure `field-provenance.md` field 21 says to parse explicitly to avoid.
**And the guard turned out to exist by accident:** antd registers the same plugin on the shared
dayjs singleton (verified: importing `antd/lib/date-picker` flips that call from valid to
invalid). Since AD-021 is in the business of removing antd from this screen, the guard would have
vanished with the last antd import, silently, and the symptom would be a swapped birth date on a
bank record. Declared in the page now.

**An empty sign-in reached the server and wrote to the audit chain.** The antd `Form` that
`LoginPage` replaced carried `rules={[{ required: true }]}` and refused an empty submit
client-side; bare inputs do not. Every failed sign-in makes `AuthFailureAuditListener` append a
`sign_in_failed` event to the hash-chained `system`/`auth` trail, so a stray Enter would have
written permanent noise into it. Guarded in `onSubmit` rather than with the native `required`
attribute, whose validation bubble is in the browser's locale — English on a bank desktop — in an
Arabic-first UI.

**Also fixed, none individually interesting:** the card 48px too wide, the reveal toggle live
during submit, two drifted tones, one mistyped sub-section heading, and section 3's sub-sections
not interleaved for the two-column split.

**Comments corrected rather than left standing:** three claims that overstated what had been
measured — the test file claiming `destroy()` alone was sufficient, an `index.html` comment
asserting both artboards name IBM Plex Sans Condensed (only the profile screen does; the sign-in
names Inter), and a `mockClear()` justified by a reason that was not true of the tests it named.

**Not acted on:** `chrome.tsx` sits at 70% branch coverage, entirely on the unauthenticated arm of
two ternaries that `RequireRole` makes unreachable and a role-label fallback. Recorded rather than
tested for the sake of a number.

---

## 5. Fonts: the artboards' own instruction, deliberately not followed

Both artboards pull IBM Plex Sans Arabic from Google Fonts, and the first cut copied that.
`docs/bank-hosting-specification.md` §5.1 enumerates the external destinations this system needs
and warns that the bank's default-deny egress blocks anything else **silently** — so the screen
would have rendered in `system-ui` with nobody learning the approved typography never loaded. The
faces were already vendored under the OFL for the printed form and are now served from
`backoffice/public/fonts/`. **Condensed is deliberately NOT loaded**: it is not in the repo, and
what the artboards need from it is `tabular-nums`, a CSS property rather than a property of the
face. The cost is the condensed width of Latin numerals.

---

## 6. Which new components have real tests, and which are only rendered

Coverage cannot catch an untested new component (R-009), so, plainly:

| New module | Tests |
|---|---|
| `profiles/editableFields.ts` | **7 real unit tests** — the 19 keys, the five fallbacks, the field numbers, case sensitivity, the prototype-pollution guard, the value ceiling |
| `profiles/detail/FieldRow.tsx` | **14 real tests** — open/seed/trim/save, blank and whitespace refusal, the 200-char paste, cancel, reopen-on-stored-value, server refusal, Enter/Escape, in-flight disabling, label binding |
| `api/reference.ts` → `useAlpha3LabelMap` | **5 real unit tests** — alpha-3 keying, case-insensitivity, malformed `extra`, the ICAO miss, the error path |
| `api/profiles.ts` → `editProfileField` | **4 real tests** — the PATCH shape, the stored value, key escaping, the 409 |
| `profiles/detail/chrome.tsx` | **RENDERED ONLY.** No test file. Its behaviour-bearing part — `ProfileHeader`'s sign-out — has two tests in `ProfileDetailPage.test.tsx` (success and server-failure paths). Everything else is layout, exercised by every page test but asserted by none. |
| `theme/palette.ts` | **RENDERED ONLY.** Constants; no test file. |

`ProfileDetailPage.test.tsx` was rewritten wholesale: 33 old tests against the nine-block layout
became **62**. Every behaviour the old suite pinned survives — the print paths, the action gating,
AD-013's admin parity, AD-022's channel and face-match rulings.

---

## 7. Proofs beyond a passing test

**Revert-restore, one warranted and taken.** The income-row fix is an indirect assertion — a
fixture where the `OTHER` row is the only one with text passes against the bug — so a fixture with
a non-`OTHER` row carrying text first was added, and the fix reverted:

```
× seeds the income editor from the OTHER row, not from whichever row carries text
  Tests  1 failed | 61 passed (62)
```

Exactly the named test failed and no others.

**One guard that CANNOT currently fail, stated rather than claimed.** The `customParseFormat`
regression test passes with the page's own `dayjs.extend` removed, for the reason §4 gives.
Recorded in both the page and the test so the next reader is not misled into thinking it proves
something today; it starts doing real work the day antd leaves this screen.

**Two assertions found inert at review and made real.** `queryByText('ظهر وثيقة الهوية')` and
`queryByText(/في قائمة المراجعة/)` asserted the absence of strings the component under test can
never produce — they exist only in the artboard and the backlog — so both passed trivially and
would have gone on passing if a seventh tile or a queue line were added under any other wording.
The tile check now counts captions; the queue check matches the shape `\d+ من \d+`.

**Two tests that a plausible wrong implementation passes everywhere else and fails here.**
`draws chips on the non-Sudan fallbacks when the server says so` builds an Egypt profile and
asserts chips on 24/29/30/36/37, which the artboard draws none for — a screen built to the
artboard's fourteen fails only this one. And field 7's fixture gives the registry and the scan
DIFFERENT identity numbers, impossible in production, so a screen reading the scan's copy under a
"from the Civil Registry" heading fails: the heading's claim is what is under test, not the value.

---

## 8. Gate output

**Commit 1** — `npm run test:coverage`, `npm run lint`, `npm run build`:

```
 Test Files  22 passed (22)
      Tests  188 passed (188)
Statements   : 97.1% ( 571/588 )
Branches     : 86.84% ( 350/403 )
Functions    : 98.26% ( 170/173 )
Lines        : 98.85% ( 518/524 )
COV_EXIT=0
LINT_EXIT=0
BUILD_EXIT=0
```

**Commit 2** — same three:

```
 Test Files  22 passed (22)
      Tests  197 passed (197)
Statements   : 97.22% ( 595/612 )
Branches     : 87.47% ( 370/423 )
Functions    : 98.33% ( 177/180 )
Lines        : 98.9% ( 541/547 )
COV_EXIT=0
LINT_EXIT=0
BUILD_EXIT=0
```

**Commit 3** — same three:

```
 Test Files  24 passed (24)
      Tests  263 passed (263)
Statements   : 97.73% ( 734/751 )
Branches     : 90.7% ( 488/538 )
Functions    : 98.59% ( 211/214 )
Lines        : 99.11% ( 675/681 )
COV_EXIT=0
LINT_EXIT=0
BUILD_EXIT=0
```

One intermediate run failed and is evidence, so it is pasted: `tsc -b` caught seven
`ArtifactContactSheet` renders my regex had missed, while all 255 tests passed — vitest strips
types, which is exactly why `build` is a separate gate from `test`.

```
src/profiles/ArtifactContactSheet.test.tsx(118,8): error TS2741: Property 'documentType' is missing
... (7 occurrences)
BUILD_EXIT=2
```

Baseline before any edit, on an untouched tree at `c38ce38`: 21 files, 186 tests, green.

---

## 9. What is not done

**Not deployed.** Schema is at V0074 in the repo and V0072 on staging; `docs/road-to-production.md`
governs, and deployment follows S9-03 by the brief's own sequencing. This session changed no
schema.

**Corrected 2026-09-16 (S9-04): the V0072 figure above was wrong.** It was inherited rather
than read — staging was actually at **V0070**, four migrations behind, not two. S9-04 read
`flyway_schema_history` directly and migrated staging to V0074. The claim is left standing
above rather than edited away, because what this row records is how a stale number travelled
through three reports unchallenged. `docs/road-to-production.md` §3.7 now holds the version.

**The list screen still wears the old antd shell** — the accepted cost of the header ruling, not
a consequence of it, so not filed as a defect.

**BL-160 filed**: the customer's DECLARED document type is no longer on the SCREEN or in the
export, and nothing server-side compares it against the scan's. The printed form still prints it
(`PrintedFormAssembler:453`), so the value is not lost — what is gone is any surface showing the
two side by side, which `field-provenance.md` calls "an operator signal". Widens BL-148.

**Seven stale claims in `operator.md` were the reconciliation's real work**, and none was in the
task as given. That document still said per-field editing "has not shipped yet" in seven places,
including a paragraph asserting a live provenance defect that V0073 had closed hours earlier, and
a count of operator write actions that was one short. `docs/components/backoffice-components.md`
was worse: it still mapped the single profile view to `Descriptions`, `Timeline` and `Popconfirm`,
none of which the code now uses. Both are the failure CLAUDE.md names — a document freezing an old
truth stops the next session checking — and both were found by the reviewer, not by me.

**CLAUDE.md was not extended**, being at 248 of its 250-line cap: two candidate lessons — jsdom
computes no layout, and a library's global side effects can supply a guard your own code forgot —
are not worth displacing an existing rule, so they live in §4 instead. Its only edit was a stale
`backoffice/src` file count.

---

## 10. After the review: two rulings and one defect

The four commits after `d1a1e00` came out of showing the work, and are recorded here rather than
left to the git log.

**BL-160 closed without building anything** (`d464784`). Product-owner ruling: no
declared-versus-scanned mismatch flag, and the principle given with it is broader than the item —
**the Civil Registry is the reference wherever it supplies a value.** Field 43 is a case where it
supplies none, so the scan governs, which is what the screen already renders. Filed and closed the
same day, which is the right lifetime for a question a build raised.

**S9-03 is unblocked** (`d464784`). Its one gating question was whether the printed form must keep
the bank paper form's nine-section running order against the approved design's three.
**Ruling: the recently approved design over-rules the old paper order.** Both
`docs/backoffice-redesign.md` §4 and the EXECUTION_PLAN row said S9-03 could not start until this
was answered; both now say it is.

**A real bidi defect, reported from the review page** (`1330eb1`). A phone number could render
`249900000123+`, the plus at the wrong end. `<bdi>` was already in place, so the value was
isolated from the surrounding Arabic — but isolation says only "treat this as a unit", never which
way the unit runs. That was left to `<bdi>`'s default `dir="auto"`, which means "take the
direction of the first STRONG character" — and a phone number, an account number, a reference
number and a date have none: the plus, the digits and the separators are all neutral or weak. The
algorithm falls back to LTR, so it was right by luck rather than by instruction, and one Arabic
character at the front of any value routed through `Num` would have flipped the whole span.

Everything `Num` wraps is Latin or numeric by construction, so `dir="ltr"` is the honest spelling
of the intent; the identity card's reference number got the same. **jsdom does no bidi layout**, so
the attribute is all a test can hold — and the previous assertion checked only that a `<bdi>`
existed, which the defect satisfied. Another instance of §4's blind spot.

**It was the review artifact that surfaced it.** Reproducing the screen without `Num` showed
immediately what `Num` was for — which is an argument for building the visual review, not only the
tests.

**Also corrected** (`dd07397`): `docs/backoffice-redesign.md`'s S9-01 block still said provenance
would stay a constant `digital` "until BL-135 ships per-field editing". It shipped the same day and
BL-155 closed with it.

---

## 11. A review artifact, published

Both screens and the derived-editability rule were published as an interactive page for the
product owner: controls for customer shape, role, status, document and verified channels, so the
rule can be watched behaving rather than read about. Switching to *lives abroad* is the fastest
way to see why a hardcoded fourteen fields would have been wrong.

---

## 12. Commit proof

Captured AFTER the final push, per CLAUDE.md. Each commit was gated and pushed as it was made;
this is the state at the end of the session.

```
$ git push origin main
To https://github.com/Osmantou/Fr_user_update
   d464784..1330eb1  main -> main

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean

$ git log --oneline c38ce38..HEAD
1330eb1 S9-02: pin LTR on Latin/numeric spans instead of relying on the auto heuristic
d464784 S9-02: two product-owner rulings, 2026-09-16
dd07397 S9-02: BL-155's interim state is over, say so where it was recorded
910332a S9-02: the session report's commit proof, captured after the push
d1a1e00 S9-02 commit 4: reconcile every document the rebuild made false, and the report
2bdb193 S9-02 commit 3: the profile screen rebuilt, and per-field editing on screen
40e0281 S9-02 commit 2: the sign-in rebuilt to the approved artboard
48effeb S9-02 commit 1: BL-156, and why its prescribed fix did not work
```

`c38ce38` is the PREVIOUS session's last commit and the range's exclusive base — S9-02's two
sessions each numbered their own commits from one, which is why the full log shows two "commit 4"
subjects. The four commits after `d1a1e00` are §10's; this report was first written at `d1a1e00`
and brought current at the end, rather than left describing half its own session.
