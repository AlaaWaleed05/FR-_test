# 2026-09-04 — Recommended work sequence after the day's decisions

Written at the product owner's request at the end of the 2026-09-04 sessions. It answers
"what can be closed now, and in what order, so that nothing built later forces an edit to
something built earlier." **This is a recommendation, not a plan change:** EXECUTION_PLAN.md
has not been reordered. Inputs: the four same-day reports (listed at the end), the plan files,
and the code as of commit `fe6d0e9`.

## The ordering rule

Two things cause back-editing in this codebase, and both are avoidable by sequence:

1. **A wire contract changed after the mobile code that consumes it exists.** Mobile screens
   for stages 1a–6 already call `account-check`, `contact-channels`, `otp/resend` and the
   reference endpoints. Any change to those requests or responses is a mobile edit. So every
   contract change must land before S5-07 and S5-08 add more mobile code, and each contract
   should be touched once.
2. **A schema constraint that the adapter needs relaxed after the adapter is written.** BL-031
   (`app.omni_check` still pins the Oracle codes and requires branch) and BL-030 (no column for
   the returned identity number) must precede the adapters that need them.

Everything else — the two adapters, the outbox runner, the Uqudo verifier — sits behind ports
or touches no contract, and can be ordered by what it closes.

## The sequence

| # | Work | What it closes | Why here |
|---|---|---|---|
| 1 | **Core-banking adapter** (S3-02 re-scoped from Oracle/JDBC to HTTP, per AD-007): `@agent-researcher` step first; BL-031 migration; `CoreBankingClient` port reshaped (result type instead of `int`, branch dropped from the check, kept as profile data); HTTP adapter with explicit timeouts, config-selected; `omni_check_request`/`omni_check_response` audit artifacts; a distinct outcome for the middleware's `-1` system error (today it would collapse into INVALID and tell the customer to check their number when the bank is down) and the few-line mobile decoder edit that outcome forces. **If the product owner decides the per-install identifier, the account-check half of BL-007 goes in here too**, so that contract is touched exactly once. | S3-02, BL-031, the core-banking component; OQ-024/OQ-025 become code | Smallest fully-specified item; establishes the HTTP-client pattern the registry adapter reuses; the one unavoidable mobile back-edit is smallest now. Success path is mock-only (the only known good account is real); not-found and error paths can be live-proven with fabricated values. |
| 2 | **Civil Registry adapter** (replaces `StubCivilRegistryClient`, per AD-002b): BL-030 column and `RegistryLookupResult.identityNumber` first; adapter implementing the decided classification (matching populated record → `ok`; any other response → `not_found`; no response → `unreachable`), the `IDENTITY_NUMBER` equality guard, defensive `BIRTH_DATE`/`GENDER`, raw-response audit artifact, `PHOTOGRAPH` into `artifact_ref` per AD-004. The 2026-09-04 research report is most of the researcher step. | The registry component; AD-002b and BL-030 become code; the stub retires | No wire change, so nothing downstream moves. Success path is mock-only (the only value known to return a record is a real person's); not-found (malformed value) and unreachable (bad host) can be live-proven. |
| 3 | **BL-007 remainder**: install identifier and rate limiting on `contact-channels`, `otp/resend` and the reference endpoints, plus the edits to the existing mobile screens that call them. | BL-007 | The last contract change. After it, every endpoint the mobile app will ever call is final. **Needs the product owner's decision on what the identifier is** — without it, step 3 cannot start and step 1 touches account-check a second time later. |
| 4 | **S6-04 outbox runner.** | The notification pipeline; every DEFERRED message finally sends | Backend-only, no contract; placed here so the backend is complete for a demo before mobile work resumes. Could go anywhere without causing rework. |
| 5 | **S5-07** — mobile stages 7–9 (identity type, document scan, registry review). | Three journey stages | Built against frozen contracts and the now-settled registry response. Uqudo screens stay English by decision (R-002 note). Real proof on the Huawei device. |
| 6 | **S5-08** — mobile stages 10–12 (face session, signature, submission), absorbing the client halves of BL-021 and BL-022. | The journey end to end; the Phase 1 demo | Follows S5-07. |

**Slack filler, any time from step 2:** the real Uqudo signature verifier (RS256 against the
tenant JWKS, replacing the stub verifier). Backend-only, changes no contract.

**After the demo, in this order:** the ~176 Arabic strings for the Uqudo screens (R-002 — a
task still to be filed); resume without local data (BL-006/BL-018 — one new backend read
endpoint plus mobile, additive); then the hardening items (BL-010, BL-011, BL-014, BL-002).

**Not closable now, unchanged:** S1-03 (no macOS machine), S1-08 (mobile and back-office tiers
still lack logic directories to scope a rule to), messaging providers (bank commercial
decision, OQ-016–022), hosting (AD-002d, OQ-001/OQ-002).

## The one input needed

**BL-007's install identifier.** What identifies an install: a server-issued token on first
launch, a client-generated UUID persisted in secure storage, or something else. This is a
product decision with a security dimension (an identifier the client chooses can be rotated by
a hostile client at will). It gates step 3 and decides whether step 1 touches the account-check
contract once or twice.

## Files that carry this day's work

For anyone — or any Claude Project — that needs to understand what was done on 2026-09-04
and why, in reading order:

1. `docs/sessions/2026-09-04-open-work-assessment.md` — the starting picture and the four
   product-owner comments that drove the day.
2. `docs/sessions/2026-09-04-plan-reconciliation-and-corebanking-discovery.md` — the decisions
   filed (R-002, R-016, AD-007, OQ-023/024/025), the core-banking live discovery, and the
   Civil Registry discovery addendum that stopped on a real record.
3. `docs/sessions/2026-09-04-research-civil-registry-error-contract.md` — the researcher's
   report: no FIB precedent, no public source, five not-found candidates, the echo-provenance
   question, the twelve bank questions.
4. `docs/sessions/2026-09-04-civil-registry-decisions.md` — the product owner's answers to all
   twelve, the five authorised tests, AD-002b closed.
5. This file — the recommended sequence.

Plus the current state they produced: `PROJECT_PLAN.md` (Constraints, decisions log rows
AD-007 and AD-002b, OQ-023–025), `EXECUTION_PLAN.md` (S1-04), `RISKS.md` (R-002, R-016, R-031),
`BACKLOG.md` (BL-029–031), `CLAUDE.md` (Constraints), `docs/components/core-banking.md` and
`docs/components/civil-registry.md`.
