# S9-07 — cleanup and reconciliation after S9-06

Five commits, each gated, reviewed and pushed before the next was started. Four were the brief's;
the fifth was not, and is the most important thing in this report.

| Commit | What |
|---|---|
| `786df63` | The documents and the artboards stop contradicting the code |
| `3d3c70c` | BL-164 — the form prints one spouse row, not two |
| `e025185` | BL-163 — the footer names the operator, and fits on one line |
| `bca6dba` | Four tests stop depending on the machine's default locale |
| `67015a5` | BL-166 — an OTP bypass reachable by a deployment setting |

## The pre-build audit, and the five claims it corrected

The brief asked for `@agent-reviewer` against its own claims before any code. Five were wrong,
three load-bearing, and they reshaped three of the four planned commits.

- **"Six stale manual-completion references in `operator.md`."** There are eight, and S9-01 had
  already reconciled every one; three sit inside a block quote deliberately preserved as "what was
  true until 2026-09-13". Editing any of them would have damaged correct text. What is genuinely
  stale there is BRANDING, at `:365` and `:369` — a different subject, two lines not six.
- **"BL-163 may deserve its own session, since `OperatorIdentity` is shared with five audit
  payloads."** It does not, and there are seven, in six classes. The print path already holds the
  operator's UUID and `OperatorUserRepository.findActiveById` already returns an `OperatorAccount`
  carrying both `username` and `displayName`. One lookup gets the name with zero change to
  `OperatorIdentity`, its single construction site, its seven service consumers or its eight web
  consumers.
- **"`fru_app` holds INSERT and SELECT only on `app.operator_user`."** `V0057:73` grants
  `SELECT, INSERT, UPDATE`, for exactly this kind of write, and no later migration revokes it.
  BL-165's own backlog row carried the same wrong claim and is corrected. What blocked S9-05 was
  the environment refusing the write, not a missing grant — a session reading "the grant forbids
  it" would go looking for a migration to write, and there is none.
- **`docs/backoffice-redesign.md` §1, §4 and §5** were already reconciled by S9-06. Nothing to do
  there — though the redraw then made two of its sentences false, which is finding 1 below.
- **A third default-locale `String.format`** exists beyond the two the brief names.

## The four rulings, recorded as AD-022 (l) through (o)

Rulings (a)–(k) are not amended. (l) exists precisely because one of them was being read wider
than it was meant.

**(l) Ruling (h) reaches the header mark and the muted line, and stops there.** The paper still
prints «AZ Omni eKYC» in every page's footer and is still drawn in the AZ palette. Both are
deliberate. The product owner was asked directly, having been shown that (h)'s words — "the paper
carries the bank's identity, not the vendor's" — read wider than what was built. This is recorded
because the gap between (h)'s wording and (h)'s scope is exactly what a later session finds, reads
as a half-done migration, and "finishes".

**(m) BL-164** and **(n) BL-163** — the substance is in the commits below.

**(o) The artboards are REDRAWN**, not annotated. The as-approved drawings are recoverable at
`git show 62f28fa:Design_3/backoffice/approved/printed-form-p*.dc.html` and each redrawn file says
so in a comment. Two things found in the redraw and recorded rather than silently fixed: the
drawings ALREADY agreed with the code on (f), printing «متزوجة»; and (e)'s wording overstates its
own departure, since the artboard already carried « ج.س» and only the thousands grouping ever
differed.

## Commit 1 — the documents and the artboards

Two lines in `operator.md`, the two artboards, four appended AD-022 rulings, and BL-165's grant
correction. The sweep the brief asked for was re-run after editing and returns only deliberately
kept assets, correct back-office claims, superseded history, and one inverted guard asserting the
internal-use notice is ABSENT.

Review found five issues, all fixed before the commit. Three are worth carrying:

1. **The redraw made `backoffice-redesign.md` false.** It said in two places that "the artboards
   still draw" the old header and the watermark. True when written, not after. Corrected in place.
2. **My own `operator.md` edit introduced a claim wider than the code** — "BANK-branded" full stop,
   which is the same over-wide reading of (h) that ruling (l) exists to prevent. Narrowed, and the
   footer bullet now names «AZ Omni eKYC» explicitly.
3. **The artboard comments claimed agreement that did not hold.** They opened "so this drawing and
   the code agree" while p2 drew one spouse row and both footers drew a username — neither true
   yet. Reworded to name both points where the drawing was deliberately AHEAD of the code, so the
   next two commits are legible from the drawing alone.

No gates: this commit touches no code tier.

## Commit 2 — BL-164, one spouse row

`PrintedFormAssembler` emitted both rows unconditionally. It now emits the one the resolved sex
selects. `effectiveSex()` is unchanged, and so is the unmarried case — a single customer still gets
one row reading «غير متاح». The ruling is about WHICH row, not whether an empty row prints.

**The layout invariant the plan did not predict.** `sections()` names every field number in a fixed
order and `place()` throws when the layout names a number nothing built — 63 errors and 1 failure
on the first gate run, all `the form's layout names field 13, which nothing builds`. Adding 13/14
to the exemption beside 25/26 would have turned "exactly one spouse row" into "either or neither",
so the exemption is paired with an explicit invariant: exactly one of 13/14 must be built, throwing
on both or on neither. The guard keeps its original strength.

**`isEdited()`'s expansion did not become redundant — it became load-bearing.** An edit is recorded
against field 13 whichever row renders, so a married man's row 14 is marked only by that expansion.
With one row, it is now the whole of what stands between him and an unmarked operator-keyed value.
Its javadoc says so, because deleting it now looks like tidying a dead special case.

`SyntheticForm` drops to one row too. It feeds the renderer directly, so it would otherwise have
gone on drawing a form the assembler can no longer produce.

## Commit 3 — BL-163, the footer

The username is resolved at print time from the UUID the print path already holds.
`OperatorIdentity` is not widened; every audit `actorId` and both payloads still carry the UUID.
AD-002e is not disturbed — the paper needs a name and the trail needs an id, and those are
different requirements.

**Measured, not eyeballed.** BL-163 is half a wrapping defect, so "contains the right string"
cannot see it. `footerLineCount` counts distinct text baselines at or below the footer's Latin
anchor, generalising `captionBaselines` — the only other reader of `getYDirAdj` in the tree. No FOP
area tree exists anywhere in this backend.

| Measure | Value |
|---|---|
| Page count | 2 |
| Footer lines, page 1 | 1 |
| Footer lines, page 2 | 1 |
| Footer lines with a 36-character UUID | 2 |

The last row is the instrument check, and it is what makes the first three worth anything: a helper
that always returned 1 would pass the one-line assertions and prove nothing. Review measured the
helper independently across name lengths — 1→1, 15→1, 20→1, 25→1, 30→1, **36→2**, 96→3, identical
on both pages.

**Two fixture faults are why no test caught this.** `SyntheticForm.PRINTED_BY` was «مشغّل تجريبي» —
an Arabic DISPLAY NAME where the service passes a username, and short enough that the footer fitted
whatever it was given. Production passed a UUID and wrapped.

**A correction to this commit's own first attempt, from review.** I claimed the Arabic label could
not survive extraction beside a Latin run and weakened a per-page assertion to an FO one on that
basis. Measured: the label's WORDS extract fine on both pages; only its trailing colon is reordered
away. The weakened version left nothing asserting the label reached the rendered page, and
duplicated an assertion another test already made — a label FOP stopped DRAWING would have passed
both. The per-page assertion is restored, colon-less and derived from the constant.

## Commit 4 — the locale debts

Three `String.format` calls and one `toLowerCase` ran under the JVM default. The brief named two;
the sweep they prompted found the other two — `OtpVerificationIntegrationTest`'s `%02d`, and
`MessageChannelTest`, where `"EMAIL".toLowerCase()` is `"emaıl"` under `tr_TR`.
`PrintedFormRendererTest`'s is load-bearing: it produces the `9x9` and `19x4` strings the per-page
header guard matches, and no string in that header survives text extraction.

A comment correction made before the commit rather than after: the first draft said an Arabic-Indic
account number would be "rejected at the boundary by design". It would not.
`ContactChannelsController.clean` refuses only blank, over-length and ISO control characters; the
non-ASCII-digit rule is on the PHONE field (BL-016).

## Commit 5 — BL-166, and it was not in scope

The same sweep found a fourth default-locale site in `main`. It chains into an OTP bypass.

1. `OtpCodeGenerator.generate` formatted the code with no `Locale`, so under an Arabic-Indic
   numbering locale it emitted U+0660..U+0669.
2. `hash()` encodes with `US_ASCII`, mapping each to `'?'`. Every challenge in the system stored
   the hash of six `?` bytes; the code carried no entropy into its own hash.
3. `OtpVerificationController.parseCode` admitted anything `Character.isDigit` accepts, which
   includes those digits. Any six of them, from anyone, verified against any challenge.

Latent, gated entirely on the JVM's default locale — which is why staging is unaffected today and
why no test could see it. Proven live before fixing: under `-Duser.language=ar` a generated code
encodes to `[63,63,63,63,63,63]` and an attacker code of a different number hashes equal; under
`en_US` the same comparison is false.

Raised as out of scope and fixed on a product-owner decision rather than filed. Two independent
closures, both kept: `Locale.ROOT` at the generator, and an ASCII-range test at the boundary, which
is what CLAUDE.md's "reject non-ASCII digits at the boundary" already required.

Review confirmed `parseCode` is the only route to `matches()`, that the tightened gate breaks no
Arabic-keyboard customer (`trim()` does not strip U+200F, and the mobile client transliterates to
ASCII before submitting), and that surefire runs one JVM sequentially so the locale-forcing test
cannot leak into a concurrent one. It also caught that my BL-166 backlog row contained
`sha256(salt || …)` — a pipe breaks a GFM table cell even inside a code span, so the row split into
6 cells against a 4-column header and GitHub would have discarded the disposition and CLOSED
status. Reworded.

## Revert-restore proofs

Two, both required because the assertions are about absence rather than a wrong value.

**BL-164** — stash `PrintedFormAssembler.java`, re-run `PrintedFormAssemblerTest`:

```
[ERROR] Tests run: 43, Failures: 4, Errors: 0
[ERROR]   PrintedFormAssemblerTest.anAbsentFieldIsNeverMarkedManual:408 [row 13 is not printed on a male profile at all -- AD-022 (m)]
[ERROR]   PrintedFormAssemblerTest.editingASpouseNameMarksWhicheverOfTheTwoRowsCarriesIt:377 [and row 13 is not on his form at all to stay unmarked -- AD-022 (m)]
[ERROR]   PrintedFormAssemblerTest.theFormPrintsFieldsTwoToFortyEight...:171 [a male profile prints 14 and not 13 -- AD-022 (m)]
[ERROR]   PrintedFormAssemblerTest.theRowTheSexDoesNotSelectIsNotPrintedAtAll:614 [row 13 is not on his form at all]
```

**BL-166** — stash both main fixes, re-run the two test classes:

```
[ERROR]   OtpCodeGeneratorTest.theCodeIsAsciiEvenOnAnArabicDefaultJvm:54 the code must be ASCII digits whatever the JVM default locale: ?????? ==> expected: <true> but was: <false>
[ERROR]   OtpVerificationControllerTest.anArabicIndicDigitCodeIs400AndNeverReachesTheService:156 Servlet Request processing failed: java.lang.NullPointerException: Cannot invoke "VerificationAttemptResult.channel()" because "result" is null
```

The second does not fail on its assertion. It fails because the Arabic-Indic code passed the gate
and REACHED the service — the bypass itself, not a statement about it.

## Final gate

`./mvnw verify -Pdb-integration-test`, from `backend/`:

```
[INFO] Results:
[INFO]
[INFO] Tests run: 1332, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 509 files clean - 0 needs changes to be clean, 0 were already clean, 509 were skipped because caching determined they were already clean
[INFO]
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Analyzed bundle 'backend' with 375 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
MAVEN_EXIT=0
```

`backoffice/` was not touched by any commit, so its gates were not run.

**A trap worth recording:** the background task notification reported "exit code 0" for a run whose
`MAVEN_EXIT` was 1 — the notification reports the trailing `echo`, not Maven. Every gate result
here is read from the captured `MAVEN_EXIT` in the log.

## Not done, and why

- **BL-165's staging UPDATE was authorised and could not be run.** No AWS credentials in this
  environment (`aws sts get-caller-identity` → `NoCredentials`) and no `psql`; the SSM
  session-manager plugin is present. A tooling gap, not a permission one. Asking for a credential
  to paste would breach CLAUDE.md's secrets rule, so it was not asked for. The backlog row's wrong
  grant claim is corrected regardless.
- **`assets/index-H-K7ND01.js` stays in the staging bucket.** Unreferenced and immutable-cached;
  S9-06 already recorded this environment refusing the delete, and rollback safety cannot be
  confirmed from disk, so the brief's own condition for deleting it is unmet.
- **The untracked hosting-requirements files** (`docs/assets/`, `docs/tools/`, the `.docx` and its
  Word lock file) predate this session and were left alone. Every commit staged explicit paths
  rather than `-A`, so none of them was swept in.
