# 2026-09-04 — Civil Registry: product-owner decisions on the twelve research questions; AD-002b closed

Same-day follow-up to docs/sessions/2026-09-04-research-civil-registry-error-contract.md. The
product owner answered all twelve questions in one exchange. This file records each answer as
given, the decision it produces, and the five authorised tests. Documentation only; no code; no
gate. No personal data from any registry record appears here.

## The answers, as given, and what each settles

| # | Question (short) | Product owner's answer | Decision recorded |
|---|---|---|---|
| 1 | Not-found response for a valid-looking number | We don't know and need to close this. Assume: got a response but not success → invalid; got no response → did not reach the registry. No need to distinguish non-success cases as long as we can tell we reached it. | Coarse classification, AD-002b. Any response that is not a matching populated record → `not_found`; no response → `unreachable`. |
| 2 | Is `IDENTITY_NUMBER` copied from the request or read from the record? | It is a full profile from the registry, not a copy of ours; in a success it will of course be the same. | Read from the found record. The equality guard is real; the research's corroboration requirement is dropped. |
| 3 | Exact match or partial? | Assume exact match. | Recorded as an assumption, AD-002b. |
| 4 | Why does all-zeros return a record? | Don't bother, not your job. | Not pursued. No special-casing of the value in the adapter. |
| 5 | Text or number; leading zeros? | Don't care; use the example's information and run a limited number of tests. | Five tests run (below): `NID` is bound as a string; leading zeros preserved. |
| 6 | Outage behaviour | Same as Q1. | No response → `unreachable`; a response of any other kind → `not_found`. |
| 7 | Open, unauthenticated endpoint | Not your job; the bank gave us this and we use it that way. Later improvement possible, not this project. | Accepted as given. Recorded in AD-002b; not a risk row. |
| 8 | Synthetic test numbers | Cannot; none known. | Real-service tests limited to the values already exercised. |
| 9 | Rate limit | None known. | One retry on no-response is acceptable. |
| 10 | National-number format spec | Don't assume what you have no basis for; it is as given. | No length, structure or check-digit validation. Non-empty ASCII digits only. |
| 11 | Partial dates; gender casing | We don't know; test or make a safe choice covering the possibilities. | Safe choice: parse `DD/MM/YYYY`, store raw and null the date on failure; lowercase and trim gender, accept `m`/`f`, else null with raw kept. Never reject. |
| 12 | Photo always present; which fields guaranteed | Photo yes; other fields unknown; take what comes. | `PHOTOGRAPH` required; every other field optional, stored as received. |

## The five authorised tests (Q5)

Throwaway script in the session scratchpad, not committed. Body `{"NID": <value>}` to the
registry endpoint, no auth header. Bodies were hashed (SHA-256) and field-counted in memory,
never printed, never written to disk. Values: zeros of length 1, 3, 10, 11, 12.

| Length | HTTP | Content-Type | Bytes | Populated fields | Body hash group |
|---|---|---|---|---|---|
| 11 | 200 | application/json | 24,602 | 14 of 15 | A |
| 1 | 200 | application/json | 12,836 | 14 of 15 | B (different from A) |
| 3 | 400 | text/html | 1,105 | — | C (the GlassFish page) |
| 10 | 400 | text/html | 1,105 | — | C |
| 12 | 400 | text/html | 1,105 | — | C |

What this establishes [OBSERVED]: two different populated records exist for `0` and for eleven
zeros; three other lengths return the identical 400 page. What it implies [INFERRED]: `NID` is
bound as a string (numeric coercion would have made `0` and eleven zeros the same lookup and
would not have failed `000`), so leading zeros survive the round trip; and the HTTP 400 HTML
page is most plausibly the service's own not-found response rather than a binding failure, since
a length rule admitting 1 and 11 but not 3, 10 or 12 is implausible. Under the decided
classification the distinction is immaterial — a 400 is `not_found` either way — so it was not
pursued. The research report's "the 400 is not not-found" inference is marked doubtful on the
card, with this evidence. No further calls were made.

## Files changed

- PROJECT_PLAN.md — OQ-023 answered on all twelve points; AD-002b moved from the open list to
  the decisions log as CLOSED; the Uqudo Government DB Lookup alternative noted as not pursued.
- RISKS.md — R-031 ✅ Retired by decision, residual stated: a not-found and an invalid number
  are indistinguishable to customer and operator.
- BACKLOG.md — BL-030 narrowed: no `mismatch` state; store the returned identity number for
  audit; equality guard in the real adapter.
- docs/components/civil-registry.md — new "Decisions" section with the classification table;
  the five tests added to the error-contract table; the research-era adapter bullets kept as
  trail and marked adopted / not adopted; `IDENTITY_NUMBER` row answered; stub section and open
  items updated.
- docs/journeys/field-provenance.md — field 7 note updated to the answer.
- This file.

## Reviewer findings and dispositions

`@agent-reviewer` on the diff. No blockers. Clean: no personal data (counts, bytes and hash labels
only); placeholders kept; the twelve answers each recorded without overstatement except Q8 (fixed
below); classification agrees across all six places and fits the existing four-state CHECK and
customer.md stage 9's pause branch; cell counts right; scope exact.

| # | Finding | Disposition |
|---|---|---|
| 1 | SHOULD FIX — AD-002b's bullet under "Open architecture decisions" still carried the full open-era text with "stays OPEN" in bold, the CLOSED sentence merely appended | **Fixed**: bullet collapsed to a pointer at the decisions-log row |
| 2 | SHOULD FIX — the card's "NOT OBSERVED" and "five candidates" rows rest on the research premise the five-test row now marks doubtful, and still read as live instructions | **Fixed**: both marked as superseded trail, cross-referenced to the five-test row |
| 3 | SHOULD FIX — three card passages still read as open instructions (raise Q7 with the bank; AD-002b's unresolved route; "no error contract, see R-031") | **Fixed**: each annotated as answered/superseded |
| 4 | SHOULD FIX — "store the raw string" for `BIRTH_DATE`/`GENDER` named no storage; `app.registry_result` has no raw columns | **Fixed**: no new columns — the byte-identical raw-response audit artifact the architecture already requires carries them; profile columns left null. Card and BL-030 say so |
| 5 | NOTE — the card's "product owner" Decisions section mixed in three engineering choices (timeouts, one retry, ASCII-digit boundary) | **Fixed**: split into a "derived by this session" paragraph |
| 6 | NOTE — "no synthetic test numbers exist" turned "none known" into a fact | **Fixed**: "none known to the product owner", card and OQ-023 |
| 7 | NOTE — "only three requests were ever made" is stale after the five tests | **Fixed**: eight |
| 8 | NOTE — "`FIRST_NAMES` was empty in the observed records" (plural) overstates: the tests recorded counts only | **Fixed**: singular, scope stated |
| 9 | NOTE — the Uqudo Government DB Lookup alternative was closed by the session, not by an answer | **Fixed**: reworded as the session's reading of Q7, bank may still weigh it |
| 10 | NOTE — this file lacked dispositions and commit proof | **Fixed**: this section; proof appended after commit |

## What this closes and what it leaves

Closed: AD-002b; OQ-023; R-031. The real registry adapter is now specified well enough to build
once the `@agent-researcher` step is formally run (the 2026-09-04 report covers most of it).

Left, by decision and not as risks: the identity of the all-zeros and single-zero records; the
exact not-found mechanism; the outage shape; whether a login could ever be required; the
unauthenticated public reachability of the endpoint. Any of these becomes a project concern only
if the bank changes the service.

## Commit proof

```
$ git log --oneline -1
dd4f40d docs: Civil Registry decisions — product owner answers all twelve questions; AD-002b closed, OQ-023 answered, R-031 retired, BL-030 narrowed; five authorised tests recorded
$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```
