# S8-30 — seven open decisions closed

**2026-09-14.** Planning only; no tier's code changed, so no test or analyze gate applies. One
`CLAUDE.md` rule deleted. Records AD-017, AD-018, AD-019; closes BL-052, BL-062, BL-142, BL-143;
retires R-026; drops R-051; accepts R-054; marks R-003 not applicable.

## What was decided

All seven put to the product owner in one sitting, with a recommendation on each. Four went against
the recommendation, which is why each is recorded with its accepted exposure rather than a status
change alone.

| | Decision | Against my recommendation? |
|---|---|---|
| **AD-017** — R-051, customer-session auth | **Dropped.** Not deferred, not scheduled. Will not be built. | Yes — I recommended building it before the first store upload |
| **AD-018** — R-054, separation of duties | **Accepted as-is.** Nothing replaces four-eyes; no audit-review surface, no reviewer assigned | Yes — I recommended making the trail reviewable |
| **AD-019** — BL-143 / BL-062, re-entry inheritance | **Won't-fix.** A re-entrant inherits the previous person's certificate and signature | Yes — I recommended fixing it |
| BL-142 — purged certificate reads as `ATTACH_FAILED` | Accepted as documented | No |
| iOS — BL-052, R-003, CLAUDE.md rule | Cleaned up; rule deleted | No |
| R-026 — database encryption at rest | Retired; already satisfied by S7-03 | No |
| AD-002d — origin posture | Confirmed same-origin; no CORS to be written | No |

## The finding that reframed the session

**R-051 had already been ruled on, and three documents disagreed about it for four days.**
`PROJECT_PLAN.md:811` recorded it DEFERRED TO V2 on 2026-09-10, with the exposure written out in
full. `docs/road-to-production.md` §1.3 still called it an open blocker and "the item I would not
carry into production under any schedule pressure". `RISKS.md` still carried it 🔴 Live.

That mattered beyond bookkeeping: **on 2026-09-13 I answered a "what's next" question from
road-to-production.md and told the product owner R-051 was the last open launch blocker.** It was
not; it had been decided three days earlier. The correction was given before the decisions were
taken, not after, so the ruling below was made on the true state.

This is the BL-105 failure mode at document scale — the same rot this project has now been bitten by
three times (BL-105, BL-021/BL-065/BL-101's frozen comments, and now a risk register that outranked
a decision it never heard about). The fix applied here is mechanical: every place that named R-051
as live was found by grep and reconciled in this commit, including `infra/aws/README.md` and
`customer.md:1212`, neither of which the decision itself would have led anyone to.

## Why nothing was deleted

Every dropped or accepted item keeps its original text, struck through, with the accepted exposure
restated beneath it. A risk row that vanishes reads as a risk that was solved. For a bank project
whose whole audit posture rests on an append-only chain, silently erasing the register would be the
one change that makes the record worse rather than shorter. The product owner was told this and did
not ask for deletion instead.

Concretely, each of the three dropped/accepted items states what is accepted:

- **AD-017** — identity images (passport, national ID, Uqudo portrait, Civil Registry photograph)
  are served to anyone holding a profile UUID; there is no customer-session authentication anywhere
  on `/api/v1/**`; a served image cannot be unserved, so dropping the fix does not end the exposure,
  it only stops anyone planning to end it. **BL-137 and BL-041's residual are accepted with it** and
  must never later be reported as covered. BL-137 stays *open* rather than closed for one reason:
  its exposure is conditional on access logging being switched on, so whoever does that in
  production has to read it first.
- **AD-018** — one back-office account can key in a profile's data and approve it, with nothing to
  prevent or detect it. **R-037 is explicitly NOT accepted with it.** R-054's own text names R-037
  as a prerequisite of its mitigation: seals are produced and never exported, so the trail has no
  tamper-evidence anchor outside the database it polices. With separation of duties gone, the trail
  is the sole control rather than a supplementary one, so R-037 gets heavier, not lighter.
- **AD-019** — an operator reviewing a re-entered profile may see one real person's pay document
  presented as another real person's, unmarked.

## The one consequence that changed a launch gate

`road-to-production.md`'s "what I would refuse to launch without" read **"section 1 in full, and
3.1"**. Section 1's last item was R-051, now dropped rather than met — so that line was about to
become false in the most dangerous direction, reading as though a gate had been *satisfied*. It now
reads **"3.1, and 3.1 alone"**, and says why AD-018 makes 3.1 weigh more.

**3.1 (R-037 — neither housekeeping job is scheduled) is now the only thing on that page that would
stop a launch.** It is also the only one accruing damage daily: the audit seal chain never advances
and abandoned customers' passport images are never deleted.

The accepted-risks footer grew from two entries to five, for the same reason — a tidy register is
not a safe one.

## Files

`PROJECT_PLAN.md` (AD-017/018/019, the superseded 2026-09-10 R-051 ruling, AD-002d's origin half) ·
`RISKS.md` (R-003, R-026, R-051, R-054) · `BACKLOG.md` (BL-052, BL-062, BL-137, BL-142, BL-143) ·
`CLAUDE.md` (iOS rule) · `docs/road-to-production.md` (§1.3, §5.1, §5.3, suggested order, accepted
risks) · `docs/components/mobile-packages.md` · `docs/journeys/customer.md:1212` ·
`infra/aws/README.md`

**CLAUDE.md is 242 lines, under the 250 cap.** The cap rule requires naming what was cut to earn a
new rule's place; this session only cut. Removed: *"Every Flutter package added must have its iOS
support verified at the moment it is added…"* — four lines, replaced by two recording that iOS is
dropped and the check must not be done. It had outlived AD-003's cancellation by four days and was
costing effort on every package added for a platform that is not shipped.

## Not done

- **No code changed in any tier**, so no gates were run and none were owed.
- **BL-133 is not closed by AD-018.** AD-018 declines the audit-*review* surface; BL-133 also covers
  audit events for the new back-office surfaces, which is separate work and stays open.
- **OQ-001 (regulatory regime), OQ-026 (Uqudo's billed unit) and the bank-side asks (0.1 domain,
  0.2 Airtel wrong-password capture, 0.3 corrected administrative divisions) are untouched** — none
  is the product owner's to decide alone.
