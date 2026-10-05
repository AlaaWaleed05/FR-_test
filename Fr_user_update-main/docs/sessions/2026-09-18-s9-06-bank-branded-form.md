# S9-06 — the list's header, and AZ branding off the printed form

Three product-owner changes, three commits, each gated, reviewed and pushed before the next was
started.

| Commit | What |
|---|---|
| `ba4db30` | The profile list gets the profile screen's header, shared rather than copied |
| `92b6f15` | The printed form's header carries the bank, not the vendor — AD-022 (h), (i) |
| `beb8099` | The AZ watermark comes off the page — AD-022 (j) |

## The four questions the brief said to ask, and the fifth it did not know about

Answered by the product owner before any code: the internal-use notice is dropped entirely; the
wordmark is cropped to its Arabic line; «بياناتي» uses the splash's tatweel spelling; the back
office stays AZ-branded.

A fifth had to be asked mid-session, because the brief's instruction was not executable. It said to
copy `Amiri-Regular.ttf` from `mobile/`. `mobile/tool/fetch_and_subset_fonts.py:46` refuses that
copy in terms, and for two reasons the brief did not have: mobile's file is a Unicode-range SUBSET
where the backend requires the full upstream face, and wayfinder ticket 08 decision 2 sets the whole
form in IBM Plex Sans Arabic, so a second backend face was a standing decision to overturn rather
than a file to move. The product owner chose Amiri via the generator. Recorded as AD-022 (i).

**A correction to the brief's premise, which changed what needed recording.** Dropping the
internal-use notice does NOT retire ticket 05 decision 7. That decision
(`.scratch/backoffice-remaining/issues/05-printed-artifact-lifecycle.md:137`) rules that the form is
internal and is never handed to the customer — a statement about process. It never required the
notice to be printed; "a document that says so on its face is harder to hand over by accident" was
`PrintedFormDocument`'s own gloss on it. The constraint that actually bound was the artboard, which
is why (h) is a ruling and decision 7 is untouched. The print dialog was, briefly, the last surface carrying those words.
**The product owner then ruled the notice out entirely** — AD-022 (k): it was never a requirement,
but an earlier session's gloss read back as one, and it is now gone from every surface. Decision 7
itself still governs how the form is handled.

## What the pre-build audit changed

The reviewer ran against the brief's claims before any code. Most held. Six did not, and four of
those changed the work:

- **`AdminHomePage` is not inside `AppShell`.** `ProfileListPage` is the only page that is, so
  restyling the shell re-skins exactly one screen — the brief's "probably wanted" side effect does
  not exist.
- **The AZ assets were not orphaned by the code change alone.** `BrandAssetRenderTest` builds its
  OWN FO and loads `az-lockup.png` and `az-watermark.jpg` directly, so deleting either file fails it
  and leaving the test untouched would have kept proving the placement of images nothing places.
- **A sixth test the brief never mentioned**, and the most dangerous one — see below.
- **`backend/src/main/resources/brand/sfb-logo-circle.png` is not the Design_3 original.** Backend is
  192×192 palette+`tRNS` (9,807 B); Design_3 is 1318×1318 RGBA (1,102,699 B). Both square. At the
  header's 9mm that is ~540 dpi, so the committed copy is kept; recorded because "verify it matches"
  was a brief instruction and the answer is that it does not.

## Two guards that were rotting rather than failing

This is the part of the session worth re-reading.

**The per-page header guard.** `PrintedFormRendererTest` proved page 2 carries the full header by
asserting `BANK_NAME_AR` + `DATE_LABEL` on every page. AD-022 (h) turns the bank's name into an
image, so half that assertion leaves the text layer. Neither candidate replacement worked, and both
were measured rather than reasoned about:

- **«التاريخ» is not header-only.** `SyntheticForm.java:173` emits it as a body row in
  «بيانات الاستمارة» — the fixture this very test renders. Measured: it extracts TWICE on page 1,
  once on page 2. On page 1 it was already satisfiable with no header at all, so the comment
  claiming it "appears nowhere but the header" was wrong before this session touched it.
- **«بياناتي» does not extract.** Set in Amiri, it comes back as ten private-use codepoints —
  `E006 E002 E005 E003 E002 E004 E003 E002 E001 E000` — because the embedded subset's ToUnicode CMap
  has no reverse mapping. It renders correctly; it cannot be read back.

**The new anchor is what each page DRAWS.** Read from the page's own content stream, not the shared
`/Resources` dictionary, which is the document's union and cannot tell page 1 from page 2:

```
page 1: [9x9, 19x4, 148x105, 40x29, 24x30, 30x30]
page 2: [9x9, 19x4, 148x105, 40x15]
```

`9x9` is the square roundel at 9mm; `19x4` the wordmark at 4mm. Nothing else on the form draws at
either size — the tiles are 40x29, 24x30, 30x30 and the signature 40x15. **It can still fail:**
delete the header and nothing is drawn; compact it to the roundel alone and `19x4` goes; resize
either mark and the numbers move. Proved by disabling the wordmark draw:

```
Expecting ArrayList:
  ["9x9", "148x105", "40x29", "24x30", "30x30"]
to contain:
  ["9x9", "19x4"]
but could not find the following element(s):
  ["19x4"]
```

Restored, green. This is an indirect assertion standing in for "the header is present", which is the
case CLAUDE.md says needs the revert.

**The cache-leak guard, which the brief missed.**
`noCustomerImageIsLeftInTheSharedFactorysCacheAfterARender` asserts nothing decoded during a render
is still held in FOP's process-wide `SoftMapCache` — the S8-35 PII leak, where a customer's portrait
stays softly reachable long after the print. It asserts on `classpath:brand/az-lockup.png` because,
per its own comment, that is "the only image on the form whose URI a test can name" (a portrait's
carries the render's private nonce). **Once the form stopped loading that URI the assertion was
vacuously true** — green forever, guarding nothing, with no test failing to say so. Re-anchored on
both new brand URIs, which are now a single `HEADER_BRAND_URIS` constant shared with a new test that
asserts the form really loads them, so editing one list and not the other cannot recreate the hole.

## A third guard, added: silent font substitution

`fop.xconf` records that a missing 700 triplet is a silent failure. Amiri is bundled Regular-only by
deliberate choice (a bold Naskh at display size reads as heavy rather than emphatic), so there is no
700 face to register and the «بياناتي» span pins `font-weight="normal"` rather than inheriting.

Nothing proved the face actually loaded. Both failure modes were measured, because they are not
equally dangerous and it would be easy to assume they are:

- **A missing FILE is loud.** Pointing `embed-url` at a name not on the classpath makes
  `FopFactoryProvider`'s resolver throw and the render fail outright. Nothing ships that way.
- **A mismatched TRIPLET is silent.** Renaming the triplet so `font-family="Amiri"` matches nothing
  registered, the render SUCCEEDS — FOP falls back at WARNING severity and `PrintedFormRenderer`
  refuses only ERROR and FATAL. A complete, plausible PDF is produced with the app name in a
  substituted face that has no Arabic, so on the page it is simply absent.

Verified by doing it: the render succeeded and only the new assertion failed.
`theRenderedFormActuallyEmbedsAmiriRatherThanSubstitutingForIt` reads the rendered PDF's own font
list.

## Geometry, measured out of FOP's area tree

The trap the brief paid for: the roundel is SQUARE where the lockup was 410×280, and the old SFB
header needed 34mm because it STACKED a 13mm roundel above the bank's name. Putting the roundel back
without putting the stack back is the whole trick.

| | |
|---|---|
| Header band extent | **17.00 mm**, unchanged — `HEADER_EXTENT` did not move |
| Header band content | **14.30 mm** (2.70 mm spare) |
| Page 1 body extent | 251.00 mm |
| Page 1 flow used | 200.29 mm |
| **Page 1 remaining slack** | **50.71 mm** |
| Pages | **2**, as BL-162 asserts |

At `content-height` 9mm the square roundel occupies 9mm of the 22mm column against the lockup's
~13mm — narrower, not taller. The wordmark cropped to its Arabic line is 617×129, ratio 4.78, so at
4mm it is 19.1mm wide in a cell with ~95mm available.

## Eyes on the page, because a green test cannot see a substituted font

`backend/target/printed-form/approved-form-page-1.png`, inspected at 3× on the header: the bank's
roundel, the title in IBM Plex Sans Arabic, the bank's name in its logo calligraphy, and «بياناتي»
in Amiri — joined Naskh with the tatweel elongation visible, not Times and not disconnected isolated
forms. After commit 3, no watermark. Zero FOP `ERROR`/`FATAL` events and no "Font not found,
substituting" in any render.

## The wordmark is generated, not hand-trimmed

`backend/tool/prepare_brand_assets.py` derives the crop from the source's own alpha channel rather
than hardcoding pixels, and refuses if the source does not split into exactly two ink bands:

```
source sfb-wordmark-navy.png: 631x207, bands [(8, 136), (164, 199)]
crop box (7, 8, 624, 137) -> 617x129, ratio 4.7829
written backend\src\main\resources\brand\sfb-wordmark-ar-navy.png (13,887 bytes)
```

The bank's name is DRAWN, not typeset — setting it in any installed face gives a different shape
from the logo beside it, which is why it left the text layer entirely and why
`PrintedFormDocument.BANK_NAME_AR` went with it.

Amiri came from the generator, not a copy: **385,840 bytes** (full upstream) against mobile's
**342,032** (subset). `PrintedFormDocumentTest` pins the app name's 13 codepoints, because six
tatweels are invisible to anyone reading the file and both spellings render fine.

## Review findings, and their dispositions

Three reviewer passes, one per commit. Everything reported was either fixed or is recorded here.

| Finding | Disposition |
|---|---|
| Commit 1: the shared header rendered in a different FACE in each host — `ARABIC_FONT` is applied by `ScreenShell` on the profile screen and by nothing in the shell | Fixed: the face is set on `BrandHeader`'s own root. **jsdom resolves no fonts and computes no layout, so all 271 green tests were blind to it** |
| Commit 1: `BrandHeader`'s root was a `div`, losing the banner landmark antd's `Layout.Header` gave the list | Fixed: `<header>` |
| Commit 1: a fourth stale comment in `ProfileDetailPage` claiming sign-out lives in `chrome.tsx` "because the shell's is gone" | Fixed |
| Commit 1: the EXECUTION_PLAN row claimed AD-022 rulings that did not yet exist | Fixed: reworded to say each departure records its own ruling in its own commit |
| Commit 2: `docs/backoffice-redesign.md` documented the watermark removal a commit early | Fixed: deferred to commit 3, where it landed |
| Commit 2: AD-022 forward-referenced ruling (j) | Fixed: (h) and (i) with "now four", bumped to five in commit 3 |
| Commit 2: `docs/components/pdf-rendering.md` still stated "Amiri is deliberately not bundled" | Fixed, and given the Regular-only hazard |
| Commit 2: `header(...)`'s javadoc and two body comments still described the AZ lockup and "two text lines" | Fixed |
| Commit 2: **no guard for silent font substitution** | Fixed — see above. The best finding of the session |
| Commit 3: AD-022 still said "TWO FURTHER RULINGS … both on the header" with three appended | Fixed |
| Commit 3: **my `doesNotContain("148x105")` in `BrandAssetRenderTest` was a tautology** — it asserted against that test's own FO literal, which declares no background, while its description claimed a property of the form | Fixed: deleted. The real guard is the inverted test, which runs on writer output |
| Commit 3: `layoutMasterSet`'s javadoc still described an AZ lockup and bank line (commit 2's residue) | Fixed in commit 3 rather than deferred — a live stale comment outranks a commit boundary |
| Commit 3: the S9-03 ticket in `docs/backoffice-redesign.md` still listed "the watermark is present" as acceptance, unmarked | Fixed: superseded marker added |
| Commit 1: `String.format("%.0fx%.0f")` in `imageDraws` uses the default locale, so an Arabic-Indic numbering locale would break the literals | **Not fixed.** Pre-existing, inherited from `BrandAssetRenderTest`, and out of scope. Recorded here |

## Gates

Backend, final run. An earlier run of this same command failed with every integration test erroring
on `Could not find a valid Docker environment` — Docker Desktop stopped mid-session. It was
restarted and the gate re-run, not worked around.

```
[INFO] Results:
[INFO] Tests run: 1325, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Analyzed bundle 'backend' with 375 classes
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 509 files clean - 0 needs changes to be clean, 0 were already clean, 509 were skipped because caching determined they were already clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Analyzed bundle 'backend' with 375 classes
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

Back office, commit 1:

```
 Test Files  24 passed (24)
      Tests  271 passed (271)

Statements   : 97.59% ( 731/749 )
Branches     : 91.28% ( 492/539 )
Functions    : 98.6% ( 212/215 )
Lines        : 99.11% ( 673/679 )
```

`npm run lint` exit 0 (warnings only, all pre-existing); `npm run build` exit 0.

Mobile, because commit 2 touches `mobile/tool/fetch_and_subset_fonts.py` and CLAUDE.md's rule is
per tier touched — `flutter analyze` → `No issues found!`; `flutter test` → `All tests passed!`
(654). The generator's mobile outputs regenerate byte-identically; git reports them unmodified.

## Left standing, deliberately

`brand/az-lockup.png` and `brand/az-watermark.jpg` are both now referenced by no code. Both KEPT —
S9-03's precedent, that removing a committed brand asset is a decision about what the bank's
resources hold, not a tidy-up. The back office still uses its own `public/az-lockup.png`.

The `fox:` extension namespace went with the watermark, its only user. Two pieces of knowledge were
kept rather than deleted with it, both as past-tense notes on code that still exists: that
`XMLStreamWriter` here is non-repairing (a prefix must be BOUND with `setPrefix` and DECLARED with
`writeNamespace`, or `writeAttribute` throws and the render dies), and S9-03's three-spellings
measurement of how to size a region background at all.

**The artboards still draw all three things this session removed.** That is why AD-022 (h), (i) and
(j) exist and why the "(e) and (f) are the only two deliberate departures" sentence was amended in
place rather than replaced. Whether `Design_3/` is redrawn is the product owner's call, explicitly
out of scope.

Not touched: BL-163, BL-164, BL-165, and staging.

## Commit proof, captured after the push

```
To https://github.com/Osmantou/Fr_user_update
   92b6f15..beb8099  main -> main
=== PROOF, captured AFTER the push ===
On branch main
Your branch is up to date with 'origin/main'.
```

## Deployed to staging, at the product owner's request

Out of the original scope; asked for after the three commits landed, so the form could be reviewed.
Also in this deploy: AD-022 (k) — the internal-use notice retired from its last surface, the back
office's print dialog, and the AZ-screen/bank-paper split recorded as deliberate.

Order per CLAUDE.md's S5-01 rule: validate → migrate if owed → image → bundle → invalidation.

**No migration was owed, and that was checked rather than quoted** — the trap S9-04 paid for. The
repository holds 74 migrations, `git log` shows no migration file touched since S9-05's deploy, and
S9-05 read staging's own `flyway_schema_history` at 74. Code-only.

**The shipped jar is the gated artifact, proved rather than assumed.** The Dockerfile copies
`target/backend-0.0.1-SNAPSHOT.jar` rather than building inside the image, and no backend source
changed after the final gate. Verified by streaming the jar's entries — never by extracting it,
since Windows case collisions silently drop entries and make any grep over the tree a false
negative:

```
identical=521 differing=0 not-on-disk=0

OK  brand/sfb-wordmark-ar-navy.png            13,887 B
OK  brand/sfb-logo-circle.png                  9,807 B
OK  fonts/Amiri-Regular.ttf                  385,840 B
OK  fonts/OFL-Amiri.txt                        4,389 B
OK  fop/fop.xconf                              5,747 B

fop.xconf registers Amiri triplet: True
FoDocumentWriter references the wordmark: True
FoDocumentWriter references az-watermark : False
FoDocumentWriter references az-lockup    : False
```

**The task definition was derived from the running one, changing only the image.** Revision 15 was
built from revision 15's predecessor programmatically and then diffed against it with the images
normalised, refusing to register if anything else moved — 13 secret references and 9 environment
entries carried verbatim rather than retyped:

```
old image: .../fru-staging-backend:0.0.1-20260916t2002
new image: .../fru-staging-backend:0.0.1-20260918t1514
only the image differs from revision 14: confirmed
```

Read back from ECS rather than probed over HTTP, which proves nothing under the
`/api/v1/operator/**` catch-all:

```
taskDef:    fru-staging-backend:15
lastStatus: RUNNING
image:      722160255191.dkr.ecr.eu-central-1.amazonaws.com/fru-staging-backend:0.0.1-20260918t1514
digest:     sha256:47983c727709484b291278cd57bcb1073a43aeb19f09d1c9def05eb0e4be7ac8
pushed:     sha256:47983c727709484b291278cd57bcb1073a43aeb19f09d1c9def05eb0e4be7ac8
```

Started clean: `Tomcat started on port 8080`, `Started BackendApplication in 16.542 seconds`, no
ERROR, no exception, and no "Font not found, substituting" — which is the event that would have
meant «بياناتي» printing blank in the deployed image.

The bundle likewise proved by hash rather than by the filename CloudFront advertises, uploaded
assets-first then `index.html` so no viewer could fetch an index pointing at a bundle not yet
present, and with the bucket's existing cache conventions preserved (`immutable` for hashed assets,
`no-cache` for the HTML):

```
index-H-K7ND01.js -> index-B8986zjT.js
served sha256: c39fcf5cce7e93baedf6bed098eb52b3e5cbbb1fc92c000545d0200f3b5c8b48
built  sha256: c39fcf5cce7e93baedf6bed098eb52b3e5cbbb1fc92c000545d0200f3b5c8b48
IDENTICAL
```

Invalidation `IF32KP8LM6H5OKWCWB8XCD2P6J`, `/*`, completed.

**Two things this deploy did NOT do, both deliberate.** The superseded bundle
`assets/index-H-K7ND01.js` is still in the bucket — the delete was refused by this environment's
permission rules, and it is unreferenced and immutable-cached, so it costs nothing and makes a
rollback a one-line `index.html` change. And **no operator account was minted.** S9-05 created
`admin2` and left the credential in a session-scoped scratchpad that no longer exists; printing on
staging needs a credential from the product owner rather than a second account created to avoid
asking.

Expect to see on staging, all known and none of them this session's work: BL-165 (the admin display
name reads «مدير», truncated at creation — now more visible, since the list page has a header that
shows it), BL-163 (the printed footer names the operator by UUID and wraps) and BL-164 (both spouse
rows print).
