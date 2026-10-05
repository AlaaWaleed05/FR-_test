# S8-25 — the salary certificate prints separately, not on the form

**Date:** 2026-09-13 · **Tier:** none — plan files only
**Outcome:** one product-owner decision recorded. No code in any tier changed, so no test or
analyze gate applies.

S8-24 made the salary certificate viewable on screen (BL-136) and raised a question it deliberately
did not answer: ticket 04 had fixed the printed form's images at both portraits and the signature,
no document scans, and had never weighed the certificate. That is the same omission ticket 02 made
about *viewing* it — and BL-136 is the proof that this particular omission produces wrong answers.
Leaving it for whoever built BL-132 would have meant a third round of the same mistake.

---

## The decision

**The salary certificate is a separate document. It is never part of the form.**

It is the customer's own file rather than a section of the bank's document, so it is never merged
into the form's PDF. At print time the operator chooses between two outcomes:

- the form alone, or
- the form **and** the certificate, as two separate documents.

There is no variant of the form that contains the certificate.

Recorded as **ticket 05 decision 9**, with **ticket 04 decision 4 amended** to say the certificate
is out of the form's field set explicitly rather than by silence. Ticket 09 carries the audit half
as an open question, BL-132 carries the build consequences, and the map's index line points at all
of it.

---

## A toolchain wall this happens to avoid

Worth recording because it would otherwise have been discovered mid-build, with the design already
committed.

A salary certificate may be `application/pdf` — `SalaryCertificateService.ALLOWED_CONTENT_TYPES`
accepts it and the mobile picker offers it, which is what made S8-24 more than a one-line change.
**Apache FOP (AD-014) renders XSL-FO to PDF and cannot concatenate an existing PDF.** Putting the
certificate inside the form would therefore have meant adding PDFBox to the toolchain purely to
staple a customer's file onto the bank's, for a document the product owner does not consider part
of the form anyway.

Separate documents need none of that: a stored certificate is streamed as it is. The decision was
taken on its own merits, not to dodge this — but the two agree, and that is worth knowing before
someone proposes merging again.

---

## Three consequences deliberately not settled

Each is carried on the ticket that owns it, with a recommendation rather than a ruling. CLAUDE.md
forbids settling these in passing, and a recommendation the next session can overrule is more
useful than an answer it cannot find the reasoning for.

**Is a printed certificate stored as a new artifact?** Ticket 05 decision 2 says every print is its
own artifact. But the certificate already *is* one — immutable, checksummed, read through
`app.artifact_read()`. *Recommendation: stream the existing artifact and store nothing new.* A
per-print copy would duplicate the densest PII object in the system for no evidentiary gain, since
the original cannot change underneath it. If that is adopted, decision 2 needs narrowing to say it
governs the rendered form only.

**Is it re-rendered or passed through?** *Recommendation: passed through byte-identical, image and
PDF alike.* It is evidence, and re-encoding it makes the stored checksum describe something nobody
printed. The cost is real and should be stated: the operator's printer, not the renderer, then
decides how a 10 MB photograph lands on A4.

**What does the audit record?** Ticket 09 decision 2 fixed print at ONE event naming the variant.
A certificate now rides along or does not. *Recommendation: still one event, carrying whether the
certificate was included and its artifact id.* Ticket 05 decision 6 exists to answer "how many
copies of that document exist", and a certificate leaving the building unrecorded defeats that as
squarely as an unrecorded form would.

One more, smaller: **a profile with no certificate must not be offered the choice.** It is optional
and gates nothing, and BL-122 means an absent one still cannot be told from a failed upload.
*(Amended 2026-09-13, S8-29: BL-122 is closed and an absent certificate now resolves to one of
three distinct states. The rule above is unchanged — all three mean the bank holds no bytes to
print — but its stated reason is stale. See the fuller amendment under the decision below.)*

---

## Files changed

`.scratch/backoffice-remaining/issues/05-printed-artifact-lifecycle.md` (decision 9),
`…/04-form-field-set.md` (decision 4 amended), `…/09-audit-events-for-the-new-surfaces.md` (the
open audit question), `.scratch/backoffice-remaining/map.md`, `BACKLOG.md` (BL-132),
`EXECUTION_PLAN.md` (this row).

No gate output: nothing in `backend/`, `backoffice/` or `mobile/` was touched. Stating that
explicitly rather than omitting the section, so it reads as "none applies" instead of "forgotten".

---

## Addendum, same session, second commit — how the choice is made

The decision above says the operator chooses. The product owner then specified the mechanism, which
turns out to carry a requirement the first recording would have left to chance.

**The certificate is visible on the profile itself** — BL-136's sixth tile, shipped at S8-24, so
this half is already true and needed nothing. **The print choice is a question asked at the moment
of printing**: not a setting, not a second print action beside the first, not a property of the
profile.

Three properties follow, recorded as requirements rather than as commentary:

- **Asked every time, defaulting to no.** This is the one worth the words. A remembered preference
  is exactly the failure this shape prevents — an operator who once opted in would go on printing
  customers' pay documents indefinitely without ever again deciding to. "Asked" has to mean asked.
- **Not offered at all on a profile with no certificate** (the product owner confirmed the
  recommendation this report made). A greyed-out option invites an operator to wonder whether the
  document exists and they simply cannot reach it. The honest limit: BL-122 means an absent
  certificate cannot be distinguished from a failed upload, so "no certificate" means "no committed
  artifact", which is the most the system can truthfully say.
  - **Amended 2026-09-13 (S8-29): that limit is gone, and the rule is unchanged by its removal.**
    BL-122 is closed — an absent certificate now resolves to `ATTACH_FAILED`, `DECLINED` or
    `NOT_REACHED`, so the system can say more than "no committed artifact". The predicate above
    still holds as written, because all three of those states mean the bank holds no bytes and
    there is nothing to print. What changes is only the reason it is honest: it is now a
    deliberate choice rather than the most the data allowed. Whoever builds BL-132 should not
    re-derive it from the stale limit.
- **Orthogonal to the variant choice.** The print already asks which of the two forms to produce
  (ticket 10 decision 7, drawn as the open menu at `Design_3/backoffice/Main.dc.html:236`). The
  certificate question applies identically to both variants, so it belongs inside that interaction.
  **The widget is ticket 08's to design** — a checkbox in the print menu or a confirm step after it
  both satisfy this; two sequential dialogs for one print do not.

Amended in ticket 05 decision 9, `map.md` and BL-132. Still no code in any tier, so still no gate.

---

## Commit proof

Captured AFTER the push, per CLAUDE.md. Single commit, straight to `main`, no branch.

```
$ git push origin main
To https://github.com/Osmantou/Fr_user_update
   9047e11..efea979  main -> main

$ git status
On branch main
Your branch is up to date with 'origin/main'.

Untracked files:
  (use "git add <file>..." to include in what will be committed)
	docs/bank-hosting-specification.md

nothing added to commit but untracked files present (use "git add" to track)
```

`docs/bank-hosting-specification.md` predates this session and is deliberately left untracked.

**Second commit** — the addendum above, and the ticket/row amendments it describes. Same session,
same files, so a commit rather than a session of its own.

```
$ git push origin main
To https://github.com/Osmantou/Fr_user_update
   9746fda..a1cf74b  main -> main

$ git status
On branch main
Your branch is up to date with 'origin/main'.
```
