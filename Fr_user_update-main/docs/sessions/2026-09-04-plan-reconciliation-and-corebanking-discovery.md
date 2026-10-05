# 2026-09-04 — Plan reconciliation (four product-owner decisions) and core-banking discovery

Input: the product owner's 2026-09-04 comments as captured in
`docs/sessions/2026-09-04-open-work-assessment.md` (committed with this session; it was
untracked). Documentation-only session plus a throwaway discovery script. **No application
code in any tier was touched, so no test/analyze gate was run** — there is nothing for a gate
to measure. The discovery calls were made from a curl script in the session scratchpad, not
from committed code; the script is not in the repository.

## 0. Account-number check — clean

The real account number from the product owner's message was located in the prior session's
transcript (outside the repository) and compared, without ever being printed, against:

| Scope | Method | Hits |
|---|---|---|
| Every repo file, generated dirs excluded | byte search over the working tree | 0 |
| Every commit in history | `git log -S<value>` per candidate | 0 |
| The untracked assessment report, `.env`, `.env.example` | included in the working-tree search | 0 |
| Every non-transcript file under the Claude project directory (memory, scratchpad, task outputs) | byte search | 0 |
| Component cards (`docs/components/*.md`) | included in the working-tree search | 0 |

Two numeric candidates were extracted from the transcript (6 and 8 digits); neither appears
anywhere. Nothing needed removing. The value does not appear in this report, in the plan
files, or in the discovery script. A separate scan of the whole diff for any 6+ digit run
returned nothing (the fabricated inputs are in prose only, and one is 20 digits).

## 1. R-002 — note added, status unchanged

RISKS.md R-002 stays 🔴. Appended: the first Phase 1 demo shows the Uqudo SDK's own scan
screens in English; the ~176 Arabic replacement strings become their own task after the demo,
no longer inside S5-07's scope; our own app screens are already Arabic. No EXECUTION_PLAN
change — the future task is not filed this session (rule 5: no reordering).

## 2. R-016 — ✅ Retired by product-owner decision; BL-029 filed

RISKS.md R-016 → ✅ Retired. The row carries, labelled as **reasoning, not a live-tested
fact**: FIB's production app on the same Uqudo service never reads the match result and has no
reported wrong-person case, consistent with the SDK blocking a mismatch internally before any
success result. Accepted limitation in the same row: a blocked mismatch cannot be told apart
from a genuine camera problem by the reviewing operator. Phase 1 residual, not required to
fix. BL-028's partial artifact and the "never set `setMinimumMatchLevel`" rule stand.

PROJECT_PLAN.md's Architecture paragraph that references R-016 got a one-line pointer to the
retirement so the two files agree.

BACKLOG.md **BL-029** (highest existing ID was BL-028): operator-visible distinction between a
camera failure and an identity mismatch, if a future SDK version exposes it. Marked explicitly
**nice-to-have, NOT a requirement**.

## 3. Civil Registry — access route recorded; OQ-023 filed, unanswered

PROJECT_PLAN.md AD-002b: access route ANSWERED — HTTPS endpoint on a hostname, reachable from
outside the bank, request key and fifteen response fields match `docs/components/civil-registry.md`
exactly; the "no TLS / bank-internal only" gaps are closed. The decision stays OPEN on the
remainder, filed as **OQ-023** (highest existing was OQ-022): (a) unknown/malformed number
behaviour, (b) service-down behaviour, (c) whether the certificate is trusted by our default
trust store, (d) whether a login is required. None of the four was probed or guessed this
session — the task said not to. The component card was not edited (not in scope).

## 4a. Core banking — connection type corrected (AD-007)

Changed from "Oracle stored procedure `ProcessOmniCheckAct` over JDBC, branch + account,
returns 1/2/-1" to "secure HTTPS/JSON POST to the bank's middleware, account only, text
code + message" in:

- PROJECT_PLAN.md: Overview, Constraints (two bullets), Architecture (audit-artifact list and
  the design-principle paragraph), Module map, Delivery model, OQ-006 marked MOOT (kept, not
  deleted), decisions log row **AD-007** dated 2026-09-04, source = the product owner's
  exchange + the assessment report + this report. The row says explicitly that it replaces the
  earlier assumption.
- CLAUDE.md: Constraints bullet, Architecture backend bullet, the researcher-first rule
  ("the Oracle procedure" → "the core banking middleware").

Hostnames were written as placeholders in the plan files (`<middleware host>`,
`<registry host>`); they are configuration. The real endpoint appears below as evidence of
what was called and in the assessment report.

`docs/components/core-banking.md` was NOT rewritten — it is the card the real adapter task
must rewrite against a researcher report; touching it now would be settling the adapter's
shape in passing. Historical rows (S3-01, S3-02, the original OQ-006 text) keep the Oracle
wording as history.

## 4b. Branch — OQ-024 filed and answered

Product-owner decision 2026-09-04: branch is stored as profile data, collected elsewhere in
the journey, NOT sent to the core-banking check; only the account number is. The consequence
for code (port, mobile entry screen, audit payload all carry branch + account today) is
written into the question as follow-up, not done.

## 4c. Core-banking discovery — live, three calls, fabricated values only

Endpoint: `https://mb1.sfbank-sd.com:9191/OMNI_PH3/resources/bankRoutes/CheckAccount`, `POST`,
`Content-Type: application/json`, body `{"Account": "<value>"}`. No authentication header
sent. curl 8.17.0 (Schannel) from this Windows machine, outside the bank's network.

Stop rule: at least one non-success beside the known success. Two distinct non-success codes
appeared by call 3, so the planned fourth call (non-numeric) was not made.

| # | Input (fabricated) | HTTP | Raw body | Time |
|---|---|---|---|---|
| 1 | `00000000` (repeated digit) | 200 | `{"Response_Code":0,"Response_Message":"Account not Found"}` | 1.22 s |
| 2 | `99999999999999999999` (20 digits, out of range) | 200 | `{"Response_Code":0,"Response_Message":"Account not Found"}` | 1.21 s |
| 3 | `""` (empty string) | 200 | `{"Response_Code":-1,"Response_Message":"System Error"}` | 1.22 s |
| — | product owner's example, a real account — **not re-run** | 200 | `Response_Code` `1`, `Response_Message` `"Account Found"` (as supplied) | — |

Response headers, identical on all three calls:

```
HTTP/1.1 200 OK
Server: Eclipse GlassFish 8.0.0
X-Powered-By: Servlet/6.0 JSP/3.1(Eclipse GlassFish 8.0.0 Java/Oracle Corporation/21)
Content-Type: application/json
```

What the connection needed beyond a plain HTTPS call: **nothing.**

- No authentication: no header sent, none demanded, no 401/403, no login page, no cookie set.
  An earlier body-less `OPTIONS` to the same URL also returned 200.
- No certificate to install. TLS probe (`openssl s_client`, no request body):

```
subject=C=AE, L=Sharjah, O=Al Hawafiz Computer Devices LLC, CN=*.sfbank-sd.com
issuer=C=US, O=DigiCert Inc, CN=DigiCert Global G2 TLS RSA SHA256 2020 CA1
depth=2 ... CN=DigiCert Global Root G2   verify return:1
Verification: OK   Verify return code: 0 (ok)   Protocol: TLSv1.2
```

  curl/Schannel: `ssl_verify=0` on every call. The JBR 21.0.8 `cacerts` (the JDK this
  project builds with) lists `digicertglobalrootg2 [jdk], trustedCertEntry`, so a default
  Java `HttpClient` will trust it with no truststore configuration.
- Nothing unexpected, with two observations worth carrying: every outcome is HTTP 200 and the
  result lives in the body, so an adapter must not key on status; and the codes arrive as JSON
  **numbers** (`0`, `-1`) whereas the product owner's example was transcribed as the string
  `"1"` — the adapter must accept both forms.
- No credential turned up, so nothing was written to a local config.

**OQ-025** filed and marked **ANSWERED IN PART**: three codes (`1`, `0`, `-1`) from four
inputs. Explicitly NOT presented as the full list — inactive/dormant/closed accounts, a
non-numeric value and a real outage were not exercised. The bank's code table is to be asked
for before the adapter is written; until then anything other than `1` is treated as
not-found-or-error, fail-closed.

EXECUTION_PLAN.md **S1-04** → ✅ with the scope correction stated in the task cell (original
Oracle wording kept after "Original scope:"), the findings above in the notes cell, and the
S3-02 re-scope named as follow-up. S3-02 itself was not edited (rule 5). No other row moved.

## Rules touched

- `@agent-researcher`-before-integration: not triggered — nothing was integrated; a curl
  script is not an adapter. It fires when S3-02 is re-scoped and started.
- Plan mode was used and the plan approved before any edit or any POST was sent.
- The discovery script lives only in the session scratchpad; it is not in the diff.

## Reviewer findings and dispositions

`@agent-reviewer` ran against the diff, the S1-04 row and CLAUDE.md's hard rules. Clean
checks it reported: no 6+ digit run added anywhere; hostnames in the plan files are
placeholders; no credential, host, token or key in the diff; every added/changed table row has
the right cell count; OQ/BL/AD numbering unique and sequential; R-016 row, PROJECT_PLAN
mention and BL-029 agree; no row other than S1-04 reordered or status-flipped; no code,
generated file or `../FIB` file touched.

| # | Finding | Disposition |
|---|---|---|
| 1 | SHOULD FIX — OQ-023/024/025 landed under "Open architecture decisions" (my edit anchored on the AD-002c bullet's tail), not "Open questions" | **Fixed**: the three bullets moved to the end of "Open questions", after the OQ-016–022 line |
| 2 | SHOULD FIX — AD-007 says it supersedes `docs/components/core-banking.md`, but the card was untouched and still calls S1-04 blocked and OQ-006 open | **Fixed** with a dated "SUPERSEDED by AD-007" banner at the top; the card body is deliberately not rewritten (that is the re-scoped S3-02's job, after its researcher step) |
| 3 | SHOULD FIX — `docs/reference/branches.md`, `docs/journeys/customer.md` stage 1a and `docs/journeys/operator.md` still say branch + account go to `ProcessOmniCheckAct`, contradicting OQ-024 | **Fixed** with one dated pointer block in branches.md and customer.md (old text kept as history), and a wording correction in operator.md's design-principle paragraph |
| 4 | NOTE — Constraints in PROJECT_PLAN.md and CLAUDE.md describe the code as text while the wire carried JSON numbers | **Fixed**: both now say number or string |
| 5 | NOTE — R-016's retained "row stays 🔴" clause contradicts ✅ Retired in the same cell | **Fixed**: clause marked superseded by the retirement |
| 6 | NOTE — R-002's retained "S5-07's scope" contradicts the new note, and the ~176-string work is carried by no task | **Wording fixed**; the task is deliberately NOT filed — item 1 said note only, item 5 said no new task ordering. Next planning session should file it (BL or a post-demo sprint row) |
| 7 | NOTE — BL-029 sits before BL-028 in the table | **Not changed**: the table's tail is date-descending and the newest row goes first; the user's instruction did not specify a position |
| 8 | NOTE — the diff de-Oracle-ed more of PROJECT_PLAN.md than the two Constraints bullets | **Accepted as intended**: each is the same corrected fact applied where the old one was stated; all were listed in the approved plan; nothing new was settled |
| 9 | NOTE — S1-04 marked ✅ on live calls without the researcher step | **Accepted**: no integration code was written; the curl script is discovery, the same boundary the card already draws for S3-01. The researcher step is named in the S1-04 row and the card banner as the re-scoped S3-02's first act |

## Files changed

- `PROJECT_PLAN.md`, `CLAUDE.md`, `RISKS.md`, `BACKLOG.md`, `EXECUTION_PLAN.md` — as described above.
- `docs/components/core-banking.md` — supersession banner only (reviewer finding 2).
- `docs/reference/branches.md`, `docs/journeys/customer.md`, `docs/journeys/operator.md` —
  dated pointer lines only (reviewer finding 3).
- `docs/sessions/2026-09-04-open-work-assessment.md` — the untracked input, committed as-is.
- This report.
- Not in the repo: the discovery script and its captured responses, in the session scratchpad.

## Still open after this session

- OQ-023 (Civil Registry) — answered in part later this session (see the addendum below):
  certificate and login answered, malformed input observed; well-formed not-found and outage
  still unknown, not to be guessed.
- OQ-025 — CLOSED by product-owner decision later this session (addendum): the observed
  codes are the list; no code table is requested.
- S3-02 re-scope from Oracle/JDBC to an HTTP adapter (researcher step first), and the
  `CoreBankingClient` port reshaping (drop branch, replace the `int`) — follow-up, not done.
- The ~176 Uqudo Arabic strings as a post-demo task — to be filed.

## Addendum (same day, later) — Civil Registry discovery and OQ-025 closure

Instruction from the product owner: run the same discovery against the Civil Registry, and
close OQ-025 on the observed codes.

### OQ-025 — CLOSED

Product-owner decision: the observed set (`1` found, `0` not found, `-1` system error) is the
list the adapter is built against; no code table is requested from the bank. Any other value
is fail-closed and logged. PROJECT_PLAN.md OQ-025 updated; CLAUDE.md's pointer to OQ-025 is
unchanged and still correct.

### Civil Registry discovery — four calls, then stopped

Endpoint: `https://mb1.sfbank-sd.com:5353/CRSAPI/Services/GetCRSData`, `POST`, JSON body
`{"NID": "<value>"}`, no auth header, same scratchpad curl script with the URL and key swapped.
Nothing added to the codebase.

Pre-flight, no request body sent:

```
subject=C=AE, L=Sharjah, O=Al Hawafiz Computer Devices LLC, CN=*.sfbank-sd.com
issuer=C=US, O=DigiCert Inc, CN=DigiCert Global G2 TLS RSA SHA256 2020 CA1
depth=2 ... CN=DigiCert Global Root G2   verify return:1
Verification: OK   Verify return code: 0 (ok)   Protocol: TLSv1.2
```

`OPTIONS` → HTTP 200, `Allow: POST,OPTIONS`, body is a Jersey WADL (`Jersey: 2.10.4`,
GlassFish Server Open Source Edition 4.1, Java 1.8) declaring one `POST` with
`application/json` in and out. CORS headers list `Authorization` as an *allowed* header,
which is a browser hint, not a requirement.

| # | Input (fabricated) | HTTP | Content-Type | Body |
|---|---|---|---|---|
| 1 | `00000000000` (eleven zeros) | 200 | `application/json` | **Fourteen of fifteen fields populated. The photograph decodes to a genuine photo of a real person, not a placeholder.** Content not recorded here or anywhere; the captured body, headers and image were deleted from the scratchpad. |
| 2 | `99999999999999999999` (20 digits) | 400 | `text/html` | GlassFish error page: "HTTP Status 400 - Bad Request — The request sent by the client was syntactically incorrect." |
| 3 | `""` (empty string) | 400 | `text/html` | Identical page to call 2. |
| 4 | `99999999999` (eleven nines) | — | — | **Not made.** The tool permission layer refused the command; it was not retried, and after call 1 it should not be. |

Field-level facts recorded from call 1 without printing any value: `IDENTITY_NUMBER` was
eleven characters (consistent with an echo of the input), `FIRST_NAMES` was the only empty
field, every name field was a plausible short Arabic name rather than a test word, and the
photo was 18,134 bytes. That is as far as inspection went.

What this establishes, and what it does not:

- (c) Certificate trust: **yes**, default trust store, same wildcard as the middleware.
- (d) Login: **none observed** — a bare `POST` succeeded.
- (a) Malformed input: **observed** — HTTP 400 with an HTML page, no JSON. The adapter must
  map a non-JSON 400 to a fail-closed outcome and never parse it.
- (a) Unknown, well-formed number: **still not observed.** The one repeated-digit value that
  parsed returned a real record, so the "obviously fake pattern" assumption that made the
  core-banking probes safe does not hold for this service. Probing stopped on that finding.
- (b) Service down: untestable from our side, still unknown.
- The adapter must never treat "200 with a record" as proof the submitted number was genuine;
  it must verify `IDENTITY_NUMBER` against the scanned `identityNumber` and fail closed.

Questions for the bank, in place of further probes: is the all-zeros record a seeded test
entry or a real citizen's entry; a sample not-found response for a well-formed number; and
what the service does during an outage.

Files changed in the addendum: PROJECT_PLAN.md (OQ-023 answered in part, OQ-025 closed,
AD-002b narrowed), RISKS.md (R-031 narrowed, new fact recorded), docs/components/civil-registry.md
(HTTPS route, observed error contract, open items), this report. No code touched; no gate run.

### Reviewer pass on the addendum — findings and dispositions

`@agent-reviewer` ran on the addendum diff with the no-customer-data rule as its first check.
Clean: no value from the returned record anywhere; only the fabricated digit patterns; hostnames
placeholdered in plan files and card; OQ-023 sub-items each carry an explicit status; OQ-025
wording is closed-by-decision, not "verified complete"; cell counts right; four files only.

| # | Finding | Disposition |
|---|---|---|
| 1 | BLOCKER — the report's call-1 row carried the decoded photo's pixel dimensions, which is image detail read out of the record, not a length | **Fixed**: dimensions removed; the byte count (a length) stays |
| 2 | SHOULD FIX — "all fifteen fields populated" contradicted "`FIRST_NAMES` was the only empty field", in the report, PROJECT_PLAN.md and the card | **Fixed**: "fourteen of fifteen" in all three |
| 3 | SHOULD FIX — this report's "Still open after this session" still said OQ-023 unanswered and OQ-025 incomplete | **Fixed**: both bullets now point at the addendum's end state |
| 4 | SHOULD FIX — EXECUTION_PLAN.md's S1-04 note still said the code list is incomplete | **Fixed**: dated clause, closed by decision, see OQ-025 |
| 5 | NOTE — the card said "the existing parser already keys on `identityNumber`", but no parser or echo check exists | **Fixed**: now says the port takes `identityNumber` and the echo check must be written into the real adapter |
| 6 | NOTE — the new Error-contract section split the Endpoint section's request-body and auth paragraphs off under the wrong heading | **Fixed**: section moved below them |
| 7 | NOTE — the card implied the zeros call was the last one; it was the first | **Fixed**: order stated |
| 8 | NOTE — the certificate subject introduced the bank's domain into the card and PROJECT_PLAN beside the `<registry host>` placeholder | **Fixed**: "the bank's DigiCert-issued wildcard"; the subject stays only in this report |
| — | Out of scope, pre-existing, flagged by the reviewer: the card's `ADDRESS` row carried a real sample address string from the 2026-08-23 Postman response (state/locality/district/block granularity), and the `GENDER` row a one-letter sample | **`ADDRESS` sample redacted** to a structural description in this commit — it is real-record data under CLAUDE.md's rule. It remains in git history from the S2-08-era commit; purging history is the product owner's call, not taken here. `GENDER` left: a single letter identifies nobody |

## Commit proof

```
$ git log --oneline -1
9e487f8 docs: file 2026-09-04 decisions (R-002 note, R-016 retired, BL-029, OQ-023/024/025, AD-007 core banking is HTTPS middleware) and S1-04 live discovery
$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Pushed to `origin/main` directly, no branch. This proof section was appended in a follow-up
commit, the same pattern as the two previous 2026-09-04 reports.

### Addendum commit proof

```
$ git log --oneline -1
0e4057c docs: Civil Registry live discovery (OQ-023 answered in part, probing stopped on a real record), OQ-025 closed by decision, R-031 narrowed, card address sample redacted
$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```
