# 2026-09-06 — plan-file reconciliation: the Uqudo tenant

Documentation only. No code, no gates, no test run — nothing in `backend/`, `mobile/` or
`backoffice/` was touched.

## The stale fact

Several plan files and both Uqudo component cards asserted that this project's Uqudo
integration runs on **FIB's borrowed tenant**. That is no longer true: the project has
acquired its **own** Uqudo tenant, and the two device runs already conducted — S5-07
(2026-09-05, scan) and S5-08 (2026-09-06, liveness) — ran against it.

What that is proven by, and nothing more: real enrolment JWS RS256/JWKS-verified and
accepted; real face session with `match: true` at `matchLevel 5` against
`thresholdApplied 3`; a 406 KB face image fetched by JWS reference.

**No tenant identifier, endpoint or credential is recorded anywhere in this reconciliation.**
Those stay in configuration and a local gitignored file, per CLAUDE.md. The record now says
"the project has its own tenant, proven on hardware" and stops there.

## A contradiction found and resolved before any edit

The prompt's premise conflicted with the repository's own contemporaneous records, on a
compliance-bearing risk. Raised before writing anything, per the standing rule to verify
task claims against source:

| Source | What it said |
|---|---|
| `docs/sessions/2026-09-05-s5-07-device-run.md:6` | config was "the **real (FIB-borrowed) Uqudo tenant**" |
| `docs/sessions/2026-09-06-s5-08-liveness-device-run.md:6` | identical wording |
| `docs/sessions/2026-09-06-pre-production-audit.md:173` | dated the same day — "Everything today runs on FIB's borrowed Uqudo tenant" |

The evidence those two reports cite does **not** distinguish the tenants. RS256/JWKS
verification, `match: true` and `matchLevel 5` are tenant-agnostic: Uqudo's JWKS and the SDK
behave identically whichever tenant's `client_id` minted the token. S5-08's own
"**Real tenant confirmed**" line reasons from a 406 KB face image, which separates *real from
stub*, not *own from borrowed*. So the reports' evidence proved the runs were real and left
whose-tenant open.

**Resolved by the user:** the credentials those runs used were the project's own tenant, and
the "FIB-borrowed" label was carried forward out of habit from the pre-acquisition sessions.
Both reports are corrected in place with a dated note (below) so the record stops
contradicting itself.

## What changed

### `PROJECT_PLAN.md`
- **OQ-010 → ANSWERED (provisioning half)**, citing S5-07 and S5-08 by file. The
  **document-type half stays open**: SDN_ID enrolment is unexercised on *any* tenant, and the
  reasoning that used to stand in for it — that FIB's tenant is licensed for SDN_ID via their
  production web app — does not transfer to a different tenant. First real check is UAT with a
  card in hand.
- **OQ-026 (billing) — routing changed, question not decided.** "Who to ask" was FIB, who held
  the contract for a borrowed tenant. It is now the project's own Uqudo contract, and Uqudo
  directly. The obstacle was *access*; access is no longer the obstacle. The metered unit is
  still unread and nothing about it is settled here. The caveat that any saving would accrue
  to FIB rather than to us is removed.
- **OQ-011 → no longer applicable as posed.** It asked whether real customer documents may be
  processed through *FIB's* tenant; the subject is gone. Explicitly does **not** close OQ-001,
  the underlying regulatory/consent position, which never depended on whose tenant it was.
- Purge rationale narrowed (the `DELETE /info/{id}` privacy control): it is still worth calling
  deliberately to limit how long identity data sits in the vendor's cache; the stronger
  *another-bank's-account* half is gone.

### `RISKS.md`
- **R-001 → 🟡 Watching** (from 🔴 Live), with the risk column prefixed to match this file's
  convention for superseded rows. The impact column now describes a path that does not exist.
- **Not retired outright, deliberately.** The cutover is a question of *which credentials each
  environment holds*, and that is confirmed only for the local device-run config. The AWS
  staging secret was provisioned under the old label. Retirement condition stated in the row:
  confirm `fru.uqudo.http.*` and the `UQUDO_CLIENT_ID`/`UQUDO_CLIENT_SECRET` pair are the
  project's own in staging and production, before any real customer data flows.
- **The two FIB-backend observations are kept, not deleted** — the unauthenticated `GET /token`
  returning a tenant-wide bearer, and identity-document images logged at INFO. They are real,
  verified facts about FIB's setup. What changed is what they bear on: no longer our data path,
  still relevant to how much of FIB's design should be copied, and the logging one is now filed
  explicitly as an anti-pattern our own adapter must not reproduce on our own tenant.
- **R-008** — the own-tenant leg is discharged; the row stays 🔴 on the macOS/iPhone (AD-003)
  leg alone. Tenant procurement is off the critical path.

### `BACKLOG.md` — BL-063 (token reuse)
- The tenant caveat is removed and replaced with what it becomes: **(a)** the contract
  governing the metered unit is now the project's own, so OQ-026 is answerable by reading a
  document this project is party to; **(b)** any saving now accrues to this project, which
  strengthens the cost case.
- The **asymmetric risk that kept it deferred is weakened**: being wrong is no longer a breach
  on another bank's tenant escalating into an inter-bank commercial problem, just an ordinary
  contractual matter with Uqudo. Still a reason to read the contract first.
- **Not decided here.** BL-063 remains blocked on OQ-026; the metered unit is still unread.

### `EXECUTION_PLAN.md`
- **S7-12** (the AWS acceptance walk) said "real Uqudo (FIB tenant)" as forward-looking config.
  Corrected, with R-001's residual named as a pre-session check.
- **S1-02's row is left alone** — it ran 2026-09-03 on FIB's tenant, before the project's own
  was in use. Historically accurate.

### `docs/components/uqudo-sdk.md`
- The SDN_ID licensing bullet no longer rests on FIB's tenant being licensed for it. What the
  project's own tenant is proven licensed for: PASSPORT enrolment and face sessions, nothing more.
- The purge rationale and the live-test carve-out narrowed the same way as the plan files; the
  carve-out also notes that a real scan **has** since happened on hardware.
- "Reuse is live in production on FIB's tenant — the one we borrow" keeps the `[OBSERVED]`
  finding and drops the false present tense.
- **The whole "S1-02 observed" section is kept verbatim** with a tenant note added: every fact
  in it is about the *shape* of the SDK output and the JWS — claim layout, `exp − iat`, image
  retention, the `data.sessionId` binding — and holds regardless of which tenant minted the
  token. S5-07/S5-08 re-observed the same shapes. Nothing needed re-deriving.
- `deviceAttestation`: recorded as absent on FIB's tenant, **not checked** on the project's own
  at S5-07/S5-08 — flagged for the next device run rather than assumed to carry over.
- Open items: "obtain our own tenant" checked off; a **new** open item added for confirming
  SDN_ID on the project's own tenant, since that half did not come with it.

### `docs/components/uqudo-api-findings.md`
- Purge rationale narrowed, same wording as the SDK card.

### Session reports — corrected in place
- `2026-09-05-s5-07-device-run.md` and `2026-09-06-s5-08-liveness-device-run.md`: the config
  line's tenant label corrected, with a dated blockquote stating what it read on the day, why
  it was wrong, and that **nothing else about either run changes** — not the evidence, timings,
  findings or conclusions, only whose tenant it was.
- `2026-09-06-aws-provisioning.md`: the staging secret's "FIB tenant" label corrected, with the
  explicit note that the secret's **contents** were not re-checked when the label was — which
  is precisely R-001's remaining residual.
- `2026-09-06-pre-production-audit.md`: the OQ-010/OQ-011/R-001 row marked superseded later the
  same day, its original text preserved after the marker. Its P0 rating does not survive.

## What was deliberately not done

- **No tenant details invented.** No identifier, endpoint, credential, contract term or
  provisioning date appears anywhere in these edits.
- **No FIB observations deleted.** Every fact about FIB's own setup stays filed where it was;
  only the claim that it describes *our* data path was removed.
- **OQ-026 and BL-063 not decided.** The change to both is that the contract is now reachable,
  not that it has been read.
- **S1-02-era history not rewritten.** Those runs were on FIB's tenant and the record says so.
- **R-001 not retired**, because the per-environment credential confirmation genuinely has not
  happened.

## Verification

Every mention of `borrowed` / `FIB tenant` / `FIB's tenant` / `FIB-borrowed` / `R-001` /
`OQ-010` across the four plan files and both component cards was re-grepped after the edits.
What remains is, in each case, intentional: dated S1-02 history, a quoted "this used to read"
fragment inside a correction, or `PROJECT_PLAN.md:241`'s unrelated "borrowed from the
documented default" about the face-match threshold value.
