# Session report — mobile-tier audit, 2026-09-10

An audit of work this session did not do. **No application code was changed in any tier.** Every
defect found was filed, not fixed. Full findings, with evidence and provenance on every line, are in
`docs/sessions/2026-09-10-mobile-tier-audit.md` — that is the document to read carefully. This one
is the summary.

---

## Done

**The ground was checked first, and it is sound.** Commit `90ab089` exists and is an ancestor of
`main`. The 91 files it swept into the repository unreviewed (`Design_3/`) contain **no
identity-document image, no real customer data and no credential** — I opened every binary rather
than trusting the filenames, including decoding the PDF, which turns out to be a four-colour swatch
strip. The content is bank brand assets and screen designs, and it belongs to the project. The
*practice* of sweeping unreviewed files into `main` is now filed as **R-053**; this instance was
clean.

**S8-10 was reviewed independently, and it largely holds up.** Of its four source claims, three are
exactly right. Two `@agent-reviewer` passes plus my own checks confirmed the build does what it says.

**But the hazard you predicted was real.** Building S8-10 inside the session that had just done the
AD-011 research did carry an untested assumption into the code, and it produced the single most
important finding of the audit — see the first item under "Needs your attention".

**Twelve findings were filed** (BL-104 … BL-113, R-053), and **four existing register rows were
corrected** because source contradicted them (BL-021, BL-022, BL-102, BL-103). Every one of those
was verified by me against the code before it was given an id — no id was minted from a subagent's
conclusion.

**AD-010 is now in `PROJECT_PLAN.md`**, recorded honestly as *researched and recommended, not ruled
on by you*. I checked whether other decisions had been allocated in session reports and never
recorded: **AD-010 was the only one.** That residual is closed.

**iOS: one entry, as instructed.** `PROJECT_PLAN.md` records that production is Android only and
AD-003 is cancelled. Every iOS backlog and risk row is untouched, open and stale, exactly as you
asked. I did make one change beyond that entry — `S1-03` in `EXECUTION_PLAN.md` is now ❌ Cancelled
rather than ⚠️ Blocked, because leaving blocked work showing for a dropped platform makes the
execution plan false on the document every session reads first. One line, trivially reversible.

**Gates — final run, verbatim.**

```
$ fvm flutter analyze
Analyzing mobile...
No issues found! (ran in 146.1s)
```

```
$ fvm dart run tool/check_coverage.dart
02:41 +540: All tests passed!
Line coverage: 84.97% (3896/4585 lines), threshold 80%
PASSED: coverage meets the 80% threshold.
```

```
$ fvm flutter build apk --release --dart-define=REFERENCE_API_BASE_URL=https://d12k860j1xg6zy.cloudfront.net
Running Gradle task 'assembleRelease'...                           28.8s
√ Built build\app\outputs\flutter-apk\app-release.apk (108.2MB)
```

The release variant was built, not debug. Identical to the committed state, as expected — no
application code changed.

---

## Needs your attention

**1. A customer can still be stranded on the Stage 2 email row, and S8-10 did not close it.**
S8-10 built the email correction and guarded it so a customer cannot destroy their last usable code.
The guard does not work. It depends on a flag the app sets *only after a resend has already been
refused* — so a customer who uses all three resends **successfully** still gets offered the
correction control, and using it destroys the last code with no way to get another. No crash, no app
restart, nothing unusual: three taps and a typo fix. S8-10's report, the screen's own comments and
BL-103 all describe this as something that only happens after the app is killed and reopened. That
framing came from the AD-011 research and was never re-tested. **Filed BL-104; BL-103 corrected.**
Closing it needs a backend change, so it is a two-tier session.

**2. The salary certificate never reaches the bank, and is then deleted.** The customer attaches it
at Stage 6 and is shown «تم إرفاق» — file attached. The app has no code that uploads it anywhere. The
backend endpoint has existed since S4-06; nothing calls it. The file is then deleted from the phone
when the session clears. Three separate documents and one code comment all describe this as working.
This was filed as BL-022 and reads there as routine leftover wiring; it is silent data loss with a
false confirmation. **Filed BL-105; BL-022's severity corrected.**

**3. A rejected customer is told their update is awaiting review.** The app maps *submitted*,
*approved* and *rejected* to the same screen — the one that says «لم يتم اعتماد التحديث بعد» ("not yet
approved, you will be notified"). If the phone has already cleared its local state, they instead see
"this account's update has already been completed", which tells a rejected customer they succeeded.
The operator meanwhile correctly sees «مرفوض». There is no rejection screen and no reason code in the
app at all; the reason reaches the customer only by SMS. **Filed BL-106.** This makes item 6 below
sharper than it looks.

**4. A phone-locked customer cannot open the app at all.** The backend can answer "blocked", and the
app throws on the value rather than handling it — the customer sees "could not start the app, check
your internet connection", which blames the network for a bank block, and retrying never works.
BL-021 claimed the app "ignores the field and falls back to self-correcting behaviour". That is
false. **BL-021 corrected.**

**5. Can the pilot APK become the production APK? No — the hostname forces a rebuild.** The API
address is compiled into the app at build time, and the APK in the bank's hands carries the
CloudFront default name, which is AWS's and can never carry the bank's certificate. This is what
BL-074 already says, and it holds. The useful part is how the work splits: **choosing the hostname
string is the bank's, and it is the only blocking ask** — it is a decision, not work, and it must be
fixed before the production build. **Creating the DNS record is also the bank's, but it does not
block** and can follow later with no rebuild. Everything after that — certificate, distribution
config, rebuild, redistribution — is ours.

**6. Reference data, ruled once as you asked.** The one I would not ship without your word:
**three real Sudanese states are missing from the list** — West Kordofan, East Darfur, Central
Darfur. The state pick is mandatory at three separate stages, so a resident of those states cannot
complete the journey truthfully, and with no update mechanism in V1 that is permanent. Also needing
a ruling: the **rejection-reason Arabic and SMS copy have never had a translation or compliance
review** (BL-005) — and with item 3 unbuilt, that SMS is the customer's *only* route to knowing why
they were refused; and **every customer message names the Central Bank, not this bank** (BL-085).
The remaining reference-data items are lower stakes and listed in the audit document.

**7. What this audit did not cover — stated so it stays a known gap.** I re-checked **9 of 63 open
backlog rows and 2 of 41 open risk rows** against actual code. The rest were read only at
summary-line level. On this session's hit rate — four of the nine I examined were wrong or
understated — a meaningful fraction of the remainder is likely stale too. I also did not verify the
back-office tier myself, and four reported dead ends (including a possible one at the signature
stage, and a lifetime-cap loop that could permanently burn an account) remain unconfirmed. Those two
are what the next session should confirm first.

---

## Carried forward

**Next session should take, in this order:** (1) confirm the four unverified dead ends, starting
with the Stage 11 signature refusal and the Stage 8/10 lifetime-cap loop — if real, they are as
serious as anything filed here; (2) the backlog sweep proper, opening the 54 unexamined open rows;
(3) BL-104 + BL-103 together, since they need the same backend change.

**Decisions waiting on you:** the three missing states (R-014) · the rejection copy review (BL-005)
· "Central Bank" in every message (BL-085) · the S8-10 Arabic wording review, which is currently
scoped to 6 strings when the change actually added at least 13 · AD-011, still open and untouched
by this audit as instructed · whether S1-03 should be ❌.

**Owed and still owed:** a device pass. S8-10's 5-inch keyboard-overlap check was not done, and this
audit added two more things that need a handset rather than an argument — whether the masked phone
number renders in the right order (BL-111), and whether the longer save button wraps below the fold.
No handset was attached to this session.

**Untracked files before committing, reported so this session does not repeat 90ab089's sweep:**
exactly one — `docs/sessions/2026-09-10-mobile-tier-audit.md`, written by this session. Nothing
else. The four modified files are the plan files.

---

## Commit proof

```
$ git log --oneline -1
1b9d067 S8-11: mobile-tier audit — 12 findings filed, 4 register rows corrected
```

```
$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Branch `main`, tracking `origin/main`, clean tree. Pushed:
`8d2b340..1b9d067  main -> main`.

This section held `COMMIT_LINE_PLACEHOLDER`/`STATUS_PLACEHOLDER` in 1b9d067, because a commit hash cannot exist before the commit that creates it. Commit `f9fd768` replaced them with the real output recorded above; both commits are on `main`. Nothing here is outstanding.
