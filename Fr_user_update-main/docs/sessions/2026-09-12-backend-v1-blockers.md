# S8-15 — Backend V1 blockers

2026-09-12. Backend, with a small mobile change. Three items shipped, one ruled unnecessary, one built and reverted, four filed.

---

## Done

### The profile UUID is out of the operator export (BL-117)

The identity-image endpoint authorises on nothing but the profile UUID, so anyone holding a copy
of an export could fetch every passport and ID scan it listed — and an export is a file that
travels. The column is gone. The reference number, which is what operators and customers actually
use, stays and is now column one.

I re-verified before removing rather than trusting the backlog row: there is no export UI in the
back office at all, and nothing else read the column. The two things that did — the file writer
and the audit record of what each export contained — both derive from one column list and followed
automatically.

One record-keeping consequence, not a defect: export audit rows written before today still name 18
columns while new ones name 17. Nothing reads that value programmatically; it exists so an operator
can later prove what a given export contained.

### A lifetime cap can now be named on the wire — without breaking any app already installed (BL-118)

The backend could refuse a customer for two very different reasons and say the same thing both
times: an ordinary 24-hour block, and a permanent lifetime cap. The app could not tell them apart,
and correctly did not try.

**The obvious fix was designed and then abandoned, and that is the most useful thing in this
session.** A new error code would have been a regression for every app already in the field: the
app degrades an unrecognised code to "unknown", and Stage 8 sends "unknown" to a resync that
discards the deadline. So a capped customer who today sees the honest block screen built last
session would instead have been bounced through a resync. We would have paid for a wire improvement
with a worse screen.

Instead the reason rides *alongside* the existing response, and is left out of the body entirely
when there is nothing to say. An app that does not read it receives exactly what it receives today.

One limitation is written into the code where the next developer will meet it: **an absent reason
means "this part of the system does not know", never "not capped"** — only the two token-issuing
paths can tell why a block exists, and six other places report blocks they did not apply.

### A cap refusal was being recorded as something it wasn't (BL-121, found and fixed here)

Found while building the above. In the liveness service, a lifetime-cap refusal against certain
profile states set the same internal flag as a genuine budget exhaustion — and that flag's handler
writes the audit record. So those refusals were filed in the audit trail under the wrong reason.
The scan side never had this; the two had drifted.

This got its own backlog row rather than being folded into the item above, for one reason worth
stating plainly to anyone who has to answer for the trail later: **the audit log is append-only.
Every row already written under the wrong reason stays wrong forever.** That is a permanent
record-quality problem with its own history, not a story about a screen signal.

It was completely unguarded — no test anywhere referenced either event type. I proved the new test
catches it by reverting the fix and confirming the failure, which captured the defect verbatim:
a cap refusal recorded as `{"detail":null,"reason":"liveness_budget_exhausted"}`.

### The camera-denial fix was built, refuted and reverted (BL-114(a))

This is the one item that did not land, and the reason is worth reading.

The problem: a customer's lifetime allowance is charged the moment a scan token is issued, which
happens *before* the camera opens. So denying the camera permission spends one of twenty without a
scan being attempted, and twenty denials kill the account permanently.

**The fix originally filed for this cannot be built at all**, and the row now says so permanently. A
camera denial is only ever something the phone *claims* — it is detected from an SDK error code on
the handset, and the app deliberately tells the backend nothing on that path. Any server-side refund
would therefore be forgeable, on an endpoint with no customer sign-in, reopening the exact hole the
allowance exists to close. **The ceiling stays at twenty.**

So I built the client-side answer instead: keep the unused allowance and re-launch it when the
customer grants permission and retries, making every denial after the first free. It worked, it was
tested, and **the reviewer then refuted it against our own research notes before it shipped.**
Re-launching means calling Uqudo's scanner a second time with a session id it has already seen, and
`docs/components/uqudo-sdk.md` records Uqudo's own published instruction that a fresh session id is
required on every launch. CLAUDE.md does not let me settle an Uqudo question from documentation
alone, so I reverted it rather than ship a guess.

**Guessing wrong would have been worse than the bug.** If Uqudo rejects a reused session id, the
relaunch returns an SDK error that the scan screen already counts as a real attempt — so the
customer would lose one of their *five* attempts instead of one of their *twenty* allowances.

**It is not a dead end.** The same passage records that Uqudo says nothing either way about reusing
the *access token*, and that token reuse is observed live on FIB's tenant — so the shape that
probably works is to reuse the token and take a fresh session id. That is not free: it would change
what the twenty counts, from session mints to Uqudo purchases, so it needs your ruling.

**What shipped is the prerequisite only.** The backend was already reading the token's real expiry
from Uqudo and discarding it; it now returns it, resolved for the face check to the earlier of the
token's ~30 minutes and the face session's 10 — taken from the instant *before* the session is
created, which the reviewer caught. **Nothing in the app reads it yet**, and every comment on it
says so, so the next session does not assume otherwise.

Cheapest next step, per your standing preference: ask a researcher whether a second launch on the
same session id is actually refused. If it isn't, restoring the reverted work is six files.

### What the reviewer changed, across three passes

Dispositions, since they matter more than the count.

**Before any code** it refuted two assumptions: that one wire change would fix the "already
completed" lie (it is 14 places, two producers), and that a new error code was the way to name the
cap. Both changed the design before it was written.

**On the finished diff** it raised one blocker — the camera-denial fix re-launched the SDK on a used
session id — which I accepted and reverted. It also flagged three comments that would have frozen an
old truth once BL-118 shipped backend-only; all three updated in this same commit, which is what
CLAUDE.md's rule requires and what four earlier findings were about.

**After the revert** it caught what a compiler could not: two test comments describing a mechanism
that no longer exists, a decoder comment claiming a decode failure "costs the customer nothing" when
it burns one of the twenty allowances, and a backlog line still recommending the abandoned wire
code. All corrected.

**Two real test gaps, both fixed here.** With BL-118's mobile half cut, the wire *is* that item's
whole deliverable — and its liveness half had no test at all while the scan half did. Nothing pinned
`usableUntil` reaching the wire either, though the app's decoder hard-fails without it. Hence 1101
tests rather than 1099.

### Core banking stays stubbed for V1, and the test accounts were counted for real (BL-089)

Recorded as a decision with its exposure written down, not as a bare deferral: the bank's real
account check is never exercised in V1, so that integration stays unproven; every participant's
account must exist in the seed, so V1 is a closed list rather than an open pilot; and the link
between a profile and a real bank account is assured by whoever seeds it, not by the bank.

The account list lives in configuration and ships inside the build, so it reaches the deployed
stack automatically — confirmed live rather than assumed.

**I probed all 30 accounts against the deployed stack. 7 consumed, 23 free.** Free: `0000001001`,
`0000001004`, and `0000001009`–`0000001029`. Consumed: `0000001002`, `0000001003`, `0000001005`,
`0000001006`, `0000001007`, `0000001008`, `0000001030`. 23 is comfortably above the threshold of
10, so nothing new was seeded.

**The written record was wrong, which is why this was worth doing live.** Only three of the seven
consumptions appear in any session report — four accounts were spent by testing that left no trace.
The reports also contradict each other, one concluding 28 remained and the next saying 29. The
account numbers are synthetic placeholders, so they stay in the repository as before.

### Filed, not built

- **BL-122** — an operator cannot distinguish a customer who *declined* to attach a salary
  certificate from one whose *upload failed*; both leave no record at all. The phone already knows
  the difference and never sends it. Operator-facing, outlives V1.
- **BL-123** — the mobile tier has the choke point the backend does not. See below.
- **BL-124** — one 409 response in the system carries no error code at all, unlike every other.
- **BL-116 corrected** — with one finding the row would otherwise have lost: the Stage 2→3 gate
  checks that a reference list *has a version*, not that the version *has any items*. So a
  published-but-empty list passes the gate and produces an empty dropdown on the **forward** path,
  not only on resume. Any fix must count items, not versions.

---

## Needs your attention

### 1. What BL-106's rejection screen loses without the status discriminator

You ruled BL-119 closed and I have not built it. This is the detail behind that ruling, recorded
here because BL-106 is the next session and should not rediscover it.

**First, the scope is bigger than the row said.** "Journey over" is not two situations but **four**:
awaiting review, approved, rejected, and terminated on a registry mismatch. All four are reachable
when a customer re-enters, and all four currently produce the same false sentence. Whatever BL-106
builds has to be true for all four.

**What an undiscriminated screen can honestly say:** that the update for this account has ended and
to contact the bank. That is true in all four cases and claims no outcome — the same technique last
session used for the block screen, describing what was observed rather than why.

**Concretely, what each customer sees and what they lose.** A *rejected* customer stops being told
their update succeeded — the actual defect — and is pointed at the bank; they lose nothing they had,
since the reason only ever reached them by SMS anyway. An *approved* customer is not congratulated;
they lose a courtesy, and they were told by SMS too. A customer *awaiting review* is not told
"awaiting review"; same trade. The one real loss is a **submitted** customer who has cleared their
app data and can no longer recover their reference number in-app — but the resume path still
carries it whenever local state survives, and someone who has lost their reference number is going
to the bank regardless, which is what the screen tells them.

My reading: the honest screen can be built without the discriminator, the defect closes, and no
bank decision is published on an unauthenticated surface.

### 2. Threading the status through the other thirteen sites

You asked what it would take, and whether it is the same session or a second one.

**On the backend it is large and cross-cutting.** The false sentence appears at 14 places. Two
producers feed them: the launch check, which BL-119 would have changed, and a different 409 behind
the other thirteen — raised by **six separate exception classes**, one per feature, from **25 throw
sites**, through **seven** controller handlers. Those classes are separate deliberately, each
feature being its own quarantine boundary, so threading a status through them means changing six
boundaries at once plus tests for each.

**But it does not need to happen at all, and that is the finding.** All 14 sites resolve to **one**
Dart class; the copies are just navigation calls behind it. **One honest screen behind that one
class fixes all thirteen with no backend change whatever** — so this is BL-106's own work,
mobile-only, not a second backend session.

Filed as **BL-123** in its own right, as you asked, because it inverts an assumption this project
has been carrying: the tier that owns the wire contract is not automatically the tier with the
choke point.

### 3. How many people is V1 for?

23 usable test accounts remain, and each is single-use — one completed journey burns it
permanently, by design, with no reset. If V1 has more than 23 participants, more must be seeded
first, and that is a build-and-deploy step, not a runtime setting. I have not seeded more because 23
cleared the threshold you set, but the number that matters is your participant count, which I do not
know.

Related, and written into BL-089 so it is not rediscovered: consumption lives only in the deployed
database, and four of the seven were spent with no session recording it. Any future count should
probe rather than read the plan files.

---

## Carried forward

- **BL-118's mobile half — cut from V1 by your ruling.** What the customer does not get: a capped
  customer and an unluckily-blocked one keep reading the same screen, so someone who has
  permanently exhausted their account cannot learn that from the app and will find out at a branch.
- **BL-114(a)** — still open, and the account-killing loop is still reachable. Cheapest next step
  is a researcher pass on Uqudo session-id reuse; the reverted code restores in six files if it
  turns out to be allowed. The real fix — asking for camera permission *before* charging the
  allowance — needs a new dependency and iOS verification, so it stays a later slice.
- **BL-106** — the rejection screen itself, now with four cases to cover rather than two, still
  waiting on the BL-005 copy.
- **BL-116, BL-122, BL-123, BL-124** — filed this session, none built.
- **BL-119** — closed, not carried. The discriminator can return later behind the customer
  authentication R-051 already tracks, and behind sign-in is where it belongs.

---

## Gate output

Baseline, before any change:

```
[INFO] Tests run: 1092, Failures: 0, Errors: 0, Skipped: 0
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

Docker was **not** running at session start — the daemon was down and the `docker-desktop` WSL
distro was stopped. Docker Desktop was started before any backend change, per this session's own
precondition. `postgres:18` and `testcontainers/ryuk:0.14.0` were already present locally.

Final backend gate, `./mvnw verify -Pdb-integration-test` from `backend/`:

```
[INFO] Tests run: 1101, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] 
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 333 classes
[INFO] 
[INFO] --- jar:3.5.0:jar (default-jar) @ backend ---
[INFO] Building jar: C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar
[INFO] 
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ backend ---
[INFO] Replacing main artifact C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar with repackaged archive, adding nested dependencies in BOOT-INF/.
[INFO] The original artifact has been renamed to C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar.original
[INFO] 
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 459 files clean - 0 needs changes to be clean, 0 were already clean, 459 were skipped because caching determined they were already clean
[INFO] 
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 333 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  02:32 min
[INFO] Finished at: 2026-09-10T10:03:29+02:00
[INFO] ------------------------------------------------------------------------
```

Final mobile gates, from `mobile/`:

```
Analyzing mobile...
No issues found! (ran in 34.8s)
```

```
01:57 +572: All tests passed!
```

The mobile count is unchanged from baseline (572) because the feature that would have added
tests was reverted. The backend count rises from 1092 to 1101: seven tests for the items that
shipped, plus two added on the reviewer's second pass for the liveness `blockReason` and the
`usableUntil` wire contract, neither of which any test had been pinning.

## Commit proof

Captured AFTER the push, never before.

```
$ git log --oneline -2
e93390b S8-15: three backend blockers shipped, one ruled unnecessary, one reverted
6893062 docs: record S8-14's commit proof, captured after the push

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean

$ git merge-base --is-ancestor HEAD origin/main && echo ancestor
ancestor
```

Straight to `main`, which is this project's norm — no feature branch was used.
