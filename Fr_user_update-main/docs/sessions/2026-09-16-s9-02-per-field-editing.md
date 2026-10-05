# S9-02 — Per-field editing, REJ-03 withdrawn, BL-151 closed

**2026-09-16 · partial by design · three commits, all gated and pushed**

`624db55` per-field editing · `e7cea6c` REJ-03 withdrawn · `03260bb` BL-151 and the reject picker

Scope was BL-135's build plus the back office rebuilt to `Design_3/backoffice/approved/`. The
backend half, both backlog fixes and every plan-file consequence shipped. **The rebuild did not**,
and was handed to a fresh session at a clean boundary rather than started late — see "What is not
done".

Closed: **BL-135, BL-151, BL-152, BL-154, BL-155.** Filed: BL-156, BL-157, BL-158, BL-159.

---

## 1. The pre-build audit, and what the brief got wrong

`docs/backoffice-redesign.md` §5 requires `@agent-reviewer` against the brief and the real code
before any edit. It found ten discrepancies. Four changed what gets built and went to the product
owner; six were taken as stated defaults.

**Answered by the product owner:**

| Question | Ruling |
|---|---|
| Fields 16, 19 — digits, not free text (BL-152 q2) | **Not editable.** Matches the artboard: neither carries a «تعديل» chip. |
| REJ-03 (BL-154) | **Withdrawn.** |
| Field 20's «تعديل» chip | **Reaches only the «أخرى» free text.** |
| Field 23, birth city | **Editable only where the scan supplied no `placeOfBirth`.** |
| Editable statuses | **`submitted` and `rejected` only.** |
| The seventh artifact tile (`doc_back`) | **Keep six.** Ticket 02's "the set is the set" stands. |

Field 20 mattered most. The artboard draws an edit chip on «مصدر الدخل», but the same section says
"exactly those the customer typed as free text" and "a list-picked value is never editable" — and
income source is a coded multi-select with a primary flag. The only reading that does not
contradict the rule beside it is that the chip reaches `other_text`, which exists only when `OTHER`
is selected. Building it as drawn would have made coded values operator-writable, which is exactly
what AD-021 forbids and what filtering and export are built on.

Field 23 was the one CLAUDE.md required stopping for: BL-135 lists it as an explicitly open edge
*and* names birth city among the seven never-editable Uqudo fields, so settling it in passing was
prohibited.

The status question was a genuine correction to AD-015, not caution. `app.profile_customer_data`
is device-authoritative (V0006's own header) and every customer stage write is a full-row replace —
`JdbcDataEntryRepository.UPDATE_STAGE5` sets all eleven home-address columns unconditionally. An
operator edit to an `in_progress` profile is silently destroyed by that customer's next stage
submission: no conflict, no error, nothing in the audit trail saying the edit was lost. AD-015's
literal "any status before `approved`" is unsafe as written, and PROJECT_PLAN now says so.

**Defaults taken, all stated:**

- **The derived free-text rule extends to fields 24, 29 and 30**, which the brief names only for
  36/37. `customer.md` Stage 3 says birth state falls back to free text outside Sudan "same
  non-Sudan fallback pattern as the address hierarchy", and work state/locality share
  `resolveAddressCascade` with home. Identical columns, identical journey rule.
- **REJ-03 withdrawn by `is_active`, not a new list version** — see §3.
- **Field 4 renders the MRZ value as received.** The artboard shows «السودان», but the only source
  is an ICAO alpha-3 code while the country list is alpha-2, and no mapping exists. Hardcoding one
  breaks CLAUDE.md's reference-list rule. Filed as BL-157.
- **Field 14 «اسم الزوجة» is rendered**, sharing `spouse_name` with field 13. Absent from the brief
  entirely; the artboard fixture is a married woman, so the gap is invisible there.
- **antd dropped for the two rebuilt screens only.** The artboards are bare inline-styled HTML;
  "the screen matches `profile-screen.dc.html`" is unreachable by retinting `ConfigProvider` tokens.
- **The queue position is not built** — no backend concept exists and the brief does not scope one.
  Filed as BL-158.

---

## 2. Per-field editing (commit `624db55`)

**V0073** adds `app.profile_field_edit` — the per-field provenance storage nothing in the system
had; `grep` over `db/migration/` for `entered_by|edited_by|field_edit|source_flag` returned
nothing. One row per edited field, last edit winning, because the tamper-evident history is already
the audit chain and a second append-only copy would duplicate it without adding evidence. S9-03
reads this table for «معدَّل».

**Provenance is derived, not stamped.** AD-022 §2.1 requires it and the obvious implementation —
setting `provenance = 'manual'` on first edit — is the thing it forbids. V0073 adds
`app.derived_provenance(uuid, text)` instead, resolving "the stored column already said manual, OR
an operator has keyed at least one field". Four call sites — the detail query, the list query, the
export query and the list filter — so the rule has one definition rather than four copies. The
`p_stored = 'manual'` disjunct is load-bearing: three profiles completed manually before AD-022
carry it in the column with no rows in the new table, and dropping the disjunct would silently
re-label them `digital`. `app.profile.provenance` becomes write-never, the disposition V0071 gave
`is_manual_completion`. **BL-155 closed.**

**Editability is derived per profile.** `EditableFieldPolicy` (pure — no Spring, no database, no
clock, so it sits inside CLAUDE.md's reserved logic packages) derives the set from what was stored.
`EditabilityFacts` exists because the two callers reach it differently: the read path holds a
loaded `ProfileDetail`, while the write path must *not* — loading one there runs
`OperatorProfileViewService`, which writes a `profile_viewed` event, so every edit would record a
view that never happened. One adapter, one rule, one implementation to test.

The set is 19 constants: the brief's 14 plus five cascade fallbacks. **The "14 fields" is an
outcome, not a specification** — it is the count for a Sudan-resident, Sudan-born, Sudan-employed,
married customer with an «أخرى» income source and no scanned birth city. A customer abroad has
more; an unmarried one fewer.

**Validation is new code, not reuse, and the difference is worth stating.** `DataEntryService`
validates whole stages with cross-field rules a single-field edit has no second field to check
against — those couplings are honoured by the policy instead, so a field the rules would forbid is
never editable in the first place. And the non-blank rule *does not exist on the backend at all*:
`submitStage5`/`submitStage6` pass city, area, street, block and house number straight through, and
`submitStage3` never validates `ethnicity` or `birthCityText`. Copying the backend's validators
would have copied a gap; AD-015 asks for parity with **mobile**, which trims and refuses empty.

Two details worth keeping: `String.trim()` leaves U+00A0 where Dart's `trim()` removes it, so a
NBSP-only value would have stored a blank-looking mandatory field — hence `strip()` plus
`isSpaceChar`. And `EditableField.column()` is interpolated into SQL, safe only because `parse`
resolves against a closed enum first; a test pins every column as a bare identifier to keep it so.

**Security.** The `PATCH` route carries its own `hasRole("OPERATOR")` rule. Without it the request
falls through to the coarse `/api/v1/operator/**` VIEWER catch-all, and AD-015 names "viewers
cannot edit" as one of the bounds that make a data-entry back office safe. The service refuses them
too — two gates, matching the print route's shape.

---

## 3. REJ-03 withdrawn (commit `e7cea6c`)

AD-022 ruling 3 stopped displaying face-match results without dispositioning the reason that cites
them, leaving an operator able to reject a customer for a failure they cannot see — and the
dashboard to aggregate it as though it had been observed.

The mechanism was the decision, and two of the three routes break something:

- **Delete** is impossible: `app.profile_status_history` carries a composite FK onto
  `ref.reference_item` (V0013), so any profile already rejected under REJ-03 pins the row.
- **A new list version** satisfies the FK, but the back office resolves rejection labels and the
  reason filter against the *current* version only. Every profile already rejected under REJ-03
  would become unfilterable and show a bare code — worse than the problem.
- **`is_active = false`** withdraws it exactly where needed. `ReferenceCatalog.exists()` filters the
  flag, so the server refuses it; `find()` and both history label joins do not, so history keeps
  its labels and stays filterable.

`content_hash` needs no recomputation — V0019's canonicalisation excludes `is_active`, and
`ReferenceDocumentPublisher` rewrites the hash at publication anyway.

**The two client consumers are opposites, and the reviewer's report lumped them together.**
`RejectModal` picks a reason for a *new* rejection and must hide REJ-03 — the published document
deliberately carries inactive rows, so without the filter an operator picks it and gets a 400 at
submit. `ProfileListPage`'s reason filter searches rejections that *already happened*; filtering
there would make every profile rejected under REJ-03 unfindable, the precise outcome the flag was
chosen to avoid. One filters, one is annotated with why it does not.

---

## 4. Review findings and dispositions

`@agent-reviewer` ran twice: once against the brief before any edit (§1), once against commit 1's
diff. The second pass found one blocker.

**BLOCKER — the printed form would have marked every customer field «يدوي». Fixed.**
`PrintedFormAssembler` marks all customer-entered fields on a `manual` profile, on a premise its own
Javadoc states: *"on a MANUAL profile every customer-entered field is marked, because an operator
keyed every one of them, and that is literally true rather than an approximation."* Making
provenance derived broke that premise, and `PrintedFormService` loads through the exact query that
changed. One edited street would have stamped ~25 fields on a filed bank document, asserting an
operator hand-entered values the customer typed on their phone. A `submitted` profile is printable,
so it was reachable immediately, and no test covered it — `PrintedFormAssemblerTest` builds its
manual profile from a literal.

The fix is not to plumb the old column through. The premise is true of exactly one population —
profiles manually completed before AD-022 — and `app.profile_status_history.is_manual_completion`
identifies precisely that group. It is write-never, kept for this reason, and already loaded.

**Fixed, with tests:** `editableFields` was not status-gated on read, so an approved profile
returned a full list whose every chip would 409 — contradicting the contract written in the same
commit. The `INCOME_OTHER_TEXT` SQL pair, the only editable field targeting a second table and one
of the four rulings, had no test against Postgres. Three comments predicted BL-135 would restore a
provenance writer; it shipped and deliberately did not. The fixture map over-claimed that every
profile gains a field-edit row.

**Not acted on, with reasons:** `app.derived_provenance` in the list filter is not sargable — an
index probe per candidate row, acceptable at this volume, and recorded rather than pre-empted with
a materialised column. The plan-file reconciliation was commit 5 and is this commit.

**Disagreed with:** the claim that both `RejectModal` and `ProfileListPage` should filter
`isActive`. See §3.

---

## 5. Proofs beyond a passing test

**Derived editability.** `editableFieldsDifferBetweenASudanProfileAndAnIdenticalNonSudanOne` builds
two profiles differing only in home country and asserts the sets differ. A hardcoded list passes
every single-profile assertion and fails this one.

**Derived provenance.** `theFirstEditMakesTheProfileManualWithoutWritingTheProvenanceColumn`
asserts the API reports `manual` while `app.profile.provenance` still reads `digital`, and that the
list filter agrees with the detail. A test checking only the API would pass against the stamped
implementation AD-022 forbids.

**Revert-restore, twice.** Both are indirect assertions, so both warranted it.

Disabling the status guard (`if (false && !EDITABLE_STATUSES.contains(...))`):

```
[ERROR] FieldEditIntegrationTest.anApprovedProfileRefusesEveryEditAndOffersNoChips:304 Status expected:<409> but was:<200>
[ERROR] FieldEditServiceTest.refusesEveryStatusExceptSubmittedAndRejected:184 in_progress must not be editable ==> Expected com.sfbank.bayanati.operator.domain.FieldNotEditableException to be thrown, but nothing was thrown.
[ERROR] Tests run: 23, Failures: 2, Errors: 0, Skipped: 0
```

Reverting the printed-form fix to `"manual".equals(profile.provenance())`:

```
[ERROR] com.sfbank.bayanati.printedform.PrintedFormAssemblerTest.aProfileMadeManualByAFieldEditMarksNothing -- Time elapsed: 0.116 s <<< FAILURE!
[a derived-manual profile is not a manually COMPLETED one]
[ERROR] Tests run: 27, Failures: 1, Errors: 0, Skipped: 0
```

Exactly the named tests failed and no others, in both cases.

**No revert for the reject picker.** `does not offer a withdrawn reason, while still offering the
live ones` asserts REJ-03's absence *alongside* the two live reasons' presence — the sibling
assertions are what stop it passing against a dropdown that rendered nothing, so it is a direct
assertion.

**Two defects the tests caught in my own code**, both worth recording because neither was a typo: a
control-character case that was a trailing `\r\n`, which `strip()` legitimately removes (the test
was wrong, not the code); and a `previous_value` assertion using stage 6's `work_area` value
against a stage 5 field.

---

## 6. Gate output

**Commit 1** — `./mvnw verify -Pdb-integration-test`:

```
[INFO] Tests run: 1291, Failures: 0, Errors: 0, Skipped: 0
[INFO] Spotless.Java is keeping 506 files clean - 0 needs changes to be clean, 0 were already clean, 506 were skipped because caching determined they were already clean
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
MAVEN_EXIT=0
```

**Commit 2** — `./mvnw verify -Pdb-integration-test`:

```
[INFO] Tests run: 1294, Failures: 0, Errors: 0, Skipped: 0
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
MAVEN_EXIT=0
```

**Commit 3** — `npm run test:coverage`, `npm run lint`, `npm run build`:

```
 Test Files  21 passed (21)
      Tests  186 passed (186)

File               | % Stmts | % Branch | % Funcs | % Lines | Uncovered Line #s
All files          |    97.1 |    86.84 |   98.26 |   98.85 |
 src/profiles      |   95.36 |    83.73 |   97.22 |   98.12 |
COV_EXIT=0
LINT_EXIT=0
✓ built in 11.25s
BUILD_EXIT=0
```

Baseline before any edit, at `d66ee2a`: backend 1232 tests green; backoffice **intermittently red**
— see BL-156.

---

## 7. BL-156: the backoffice gate is flaky on `main`

Measured on an untouched tree before any S9-02 edit. Run 1 failed 1 of 185
(`ProfileDetailPage.test.tsx` "tells a refused print apart from a wrong status"); the file passes
33 of 33 in isolation; run 2 passed 185 of 185. An antd `message` toast from a sibling test —
«تعذر اعتماد الملف (409)» — is still mounted when the print assertion queries the DOM. antd's
message API is a module-level singleton and nothing clears it between tests.

Filed rather than absorbed into an unrelated diff. `npm run test:coverage` is a required gate, so
this can fail a session that changed nothing. The fix is a global `message.destroy()` in the test
setup's `afterEach`, and the session rebuilding these tests is the natural place for it.

---

## 8. What is not done

**The back-office rebuild** — the three-section profile screen, the sign-in, the approved palette,
and the editing UI that calls the endpoint commit 1 shipped. Handed to a fresh session deliberately
rather than started late: it rewrites `ProfileDetailPage`'s 33 tests against a new layout, drops
antd for those two screens, and re-anchors four `.ant-spin` selectors in `RequireAuth.test.tsx`. It
touches no file commits 1–3 touched, so CLAUDE.md's "same files means commits, not sessions" rule
is not engaged.

Beyond the artboards it needs §1's six rulings, and three known deviations from them already filed
as BL-157, BL-158 and BL-159. `editableFields` is on the wire and already status-gated, so the
screen draws a chip where the key is present and derives nothing itself.

**Not deployed.** Schema is at V0074 in the repo and V0072 on staging; `docs/road-to-production.md`
governs, and deployment follows S9-03 by the brief's own sequencing.

**Corrected 2026-09-16 (S9-04): the V0072 figure above was wrong.** It was inherited rather
than read — staging was actually at **V0070**, four migrations behind, not two. S9-04 read
`flyway_schema_history` directly and migrated staging to V0074. The claim is left standing
above rather than edited away, because what this row records is how a stale number travelled
through three reports unchallenged. `docs/road-to-production.md` §3.7 now holds the version.

---

## 9. Commit proof

```
$ git push origin main
To https://github.com/Osmantou/Fr_user_update
   e7cea6c..03260bb  main -> main

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```
