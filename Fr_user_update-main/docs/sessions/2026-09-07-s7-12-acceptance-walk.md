# S7-12 — the guided acceptance walk on AWS staging

Sprint 7, session 3. The stack from `2026-09-06-aws-app-tier-and-acceptance.md` was used as left
running; nothing was re-provisioned or torn down.

**Outcome in one line: the whole solution ran end to end on real AWS infrastructure — customer
stages 1→12 on a physical device against the project's own Uqudo tenant and the REAL Civil
Registry, then a real operator sign-in, review and approval — and the walk found one defect that
made the real registry lookup impossible for any passport holder, fixed and redeployed mid-session.**

Profile `FRU-000000001`, final status `approved`. 37 hash-chained audit events across 3 chains,
every `prev_hash` verified, spanning a code fix and a full backend redeploy.

No real PII appears below. The product owner's national number, name, date of birth, address and
account number, the recovered OTP codes and the operator's cookie values are all deliberately
absent; every table here is state, shape, count or hash.

---

## 1. GATE 0 — passed, and it upgraded R-001 from provenance to inspection

The brief required confirming the AWS Uqudo secret holds the project's own tenant before any real
PII moved. Session 2 discharged this **by provenance**, stating explicitly that inspection was
impossible because it would need a known-good client id to compare against "which this session must
not hold".

That was wrong in one respect, and the correction is the gate: **the known-good pair was already in
the repo-root `.env`.** S5-07's report says its credentials "came from the repo-root `.env`", and
S5-07 (real scan) and S5-08 (real liveness) are the hardware runs that answered OQ-010. So the
`.env` pair is known-own-tenant by measurement, and a comparison is possible.

```
--- structural ---
  PASS  both keys present, non-empty, no whitespace, no placeholder prose
        (client_id length 36, client_secret length 64 — lengths only, no values)
--- identity vs the local own-tenant config (.env, the credentials S5-07/S5-08 ran on) ---
  PASS  client_id MATCHES the local own-tenant client_id (SHA-256 equality)
  PASS  client_secret MATCHES the local own-tenant client_secret (SHA-256 equality)
--- live token mint against https://auth.uqudo.io/api/oauth/token (AWS-stored credentials) ---
  PASS  the AWS-stored credentials minted a real Uqudo access token (expires_in=1859)
        token claim keys: aud,client_id,exp,iat,iss,jti,nbf,scope,sub
        iss          https://auth.uqudo.io
        scope        ["KYC_SCAN_API","KYC_INFO_SDK","KYC_SCAN_SDK","KYC_EVENT_SDK",
                      "KYC_FACE_FLOW","KYC_FACE_SEARCH_SDK"]
```

The mint matters beyond identity: it proves the stored pair is **currently valid**, not merely
byte-identical to a pair that once worked. R-001's staging half is discharged; it stays amber for
production only, which is exactly what its own retirement condition says.

## 2. GATE 1 — it caught a walk-stopper, which is why the brief put it first

Session 1 created exactly one operator account: `admin`. **That account could never have reviewed
anything.** `OperatorUserDetails` grants `admin` only `ROLE_ADMIN` — never `ROLE_VIEWER` — and
`SecurityConfiguration` gates all of `/api/v1/operator/**` on `hasRole("VIEWER")`. It would have
403'd at the review step, after the customer had already submitted.

`operator1` (role `operator`) was created through `CreateOperatorAccountRunner` over an SSM
port-forward. **The one-time password never entered this transcript**: the runner's stdout was
redirected straight to a file outside the repository for the product owner to read once. Success was
verified from the database row, not from the output.

```
 username  |   role   | is_enabled | must_change_password | password_changed_at | last_sign_in_at
-----------+----------+------------+----------------------+---------------------+-----------------
 admin     | admin    | t          | t                    |                     |
 operator1 | operator | t          | f                    | 22:27:20            | 22:26:48
```

## 3. The defect the walk existed to find

**The real Civil Registry lookup could not succeed for any passport holder.**

A Sudanese passport prints the national number grouped — 13 characters carrying 11 digits, in a
3-4-4 pattern. Uqudo transcribes the document faithfully, which is correct OCR behaviour. The
adapter forwarded that verbatim. The registry binds `NID` as a string and matches on the bare
digits, so it answered with its 400/HTML page, the adapter classified that as `not_found` — also
correct — and the customer saw *"the service is temporarily unavailable, try again shortly"* for a
lookup that could never succeed, with no exit from the retry loop.

Every link behaved correctly except one:

| Link | Behaviour | Verdict |
|---|---|---|
| Passport → Uqudo | returns the number exactly as printed, 13 chars / 11 digits | correct |
| Uqudo → `app.scan_result` | stored verbatim | correct |
| adapter → registry `NID` | **forwarded verbatim, hyphens included** | **the defect** |
| registry | binds `NID` as a string, matches bare digits, else 400/HTML | correct |
| adapter → `registry_result` | maps 400 → `not_found` | correct, given what was sent |

Diagnosis evidence, shape only:

```
 raw_length | digit_count | non_digit_count | needs_normalising
         13 |          11 |               2 | t
```

Uqudo was exonerated by hash comparison against the number the product owner read off the passport —
both the raw value and the digits-only form matched, computed in memory, values never printed. The
component card's five authorised probes of 2026-09-04 already held the other half: `NID` lengths 1
and 11 returned populated records; lengths 3, 10 and 12 returned the identical 400 page.

**No test could have caught this.** Every existing test supplies an already-bare number.

### The fix, and the mistake the gate caught in it

`RegistryFieldNormaliser.digitsOnly`, applied at the adapter boundary **only** —
`app.scan_result.identity_number` keeps the document's own form because it is evidence, and the
audit artifact is byte-identical to the response. Only the wire is canonical.

The comparison had to change too, or the fix would have made things worse: once the request carries
bare digits the registry answers with bare digits, while the scanned value still holds hyphens, so
comparing the raw strings would fire `IDENTITY_NUMBER_MISMATCH` — BL-030, "the failure mode once
judged the worst in the system" — on **every successful passport lookup**.

**The first draft of that comparison was wrong, and it was wrong in the direction that matters.** It
applied `digitsOnly` to both sides, which folds a returned `…0001x` onto `…0001` and reinstates
prefix matching. The report of that change asserted the guard was "unweakened". The pre-existing
`nearMissesThatAreNotWhitespaceAreMismatches` failed on its `'no prefix matching'` case and proved
otherwise:

```
[ERROR] HttpCivilRegistryClientTest.nearMissesThatAreNotWhitespaceAreMismatches:380
        no prefix matching ==> expected: <false> but was: <true>
[ERROR] Tests run: 1032, Failures: 1, Errors: 0, Skipped: 0
```

The canonicalisation is therefore **asymmetric**: digits are extracted from what we *send*, because
we know its provenance; nothing is extracted from what comes *back*, which keeps the whitespace-only
strip. If the registry ever answers in grouped form — never observed — it reads as a mismatch and
fails closed. This is recorded rather than quietly fixed because it is the same class of defect the
session was hunting: a check that looks right, is argued for confidently, and is not.

Two regression tests. The load-bearing one asserts on the **request bytes**, not the outcome — an
outcome-only test passes against a build that still sends hyphens to a cooperative mock.

### After the fix, live

```
 state |          queried_at           | attempts | given | father | dob | sex | address | id_returned | en_first | en_last
-------+-------------------------------+----------+-------+--------+-----+-----+---------+-------------+----------+---------
 ok    | 2026-09-06 22:14:22.018107+00 |        2 | t     | t      | t   | t   | t       | t           | t        | t

 returned_length | returned_digits | returned_masked_shape | scanned_masked_shape
              11 |              11 | 99999999999           | 999-9999-9999
```

All fourteen registry fields populated, including both English name fields — worth noting because
the component card records `FIRST_NAMES` as empty in the one previously observed record. The two
masked shapes side by side are the whole bug and the whole fix.

**This is the first real Civil Registry success in the project's history.** Prior runs hit the stub
or the not-found path only.

## 4. The walk, stage by stage

All evidence from the audit chain and the database, never from a screenshot.

| Stage | Result | Evidence |
|---|---|---|
| 1 account entry (stub `0000000001`) | pass | `account_check_attempted` on the no-profile chain, `session_created` on a new one |
| 2 three-channel OTP | pass, 2 of 3 channels | `otp_issued`×2 + `notification_dispatched`×2; both verified first try, `wrong_code_attempts = 0`. **Email never exercised — the PO declared only SMS and WhatsApp** |
| 3–7 data entry | pass | five `stageN_data_submitted` events in 2m12s, no retries |
| 8 real passport scan | pass | `PASSPORT`, `mrz_verified = t`, `issuing_country SDN`, tampering score 0 |
| 9 REAL Civil Registry | **failed, fixed, then passed** | see §3 |
| 10 liveness | pass | `match = t`, `matchLevel 5` vs `thresholdApplied 3`, token→verified in 21 s |
| 11 signature (uploaded) | pass | `image/jpeg`, 231,967 B |
| 12 submission | pass | `FRU-000000001`, matching the on-screen reference exactly |
| operator review + approve | pass, but see BL-075 | `submitted → approved`, operator the only actor with a named `actor_id` |

The status history records the fix landing rather than hiding it:

```
 seq | from_status       | to_status         | occurred_at | actor_kind | actor_named
   1 |                   | in_progress       | 21:37:43    | customer   | f
   2 | in_progress       | awaiting_registry | 21:43:54    | system     | f
   3 | awaiting_registry | in_progress       | 22:14:23    | system     | f
   4 | in_progress       | submitted         | 22:24:03    | customer   | f
   5 | submitted         | approved          | 22:41:25    | operator   | t
```

`awaiting_registry` held the customer's progress for 31 minutes across a code fix and a redeploy,
then released cleanly — the pause design proving itself under conditions nobody planned for.

```
PASS  every prev_hash links (37 events across 3 chains)
```

**BL-039 held on real hardware for both paths**: a successful scan and a successful liveness each
spent zero attempts (`scan_attempts_total = 0`, `liveness_attempts = 0`, one token minted on each
side). That is the rule's core claim, now proven on AWS rather than only in tests.

**S5-08's open gap is closed**: an uploaded signature stores as **231,967 bytes** against 4,460 for
the drawn PNG — a 52× difference between two routes into the same one-per-profile slot, and the
first figure the component card has had for the upload route.

Four `notification_outbox` rows: two at submission, two at approval, across both verified channels.
This also settles a question parked mid-walk — the outbox carries submission and status-transition
messages, while OTP dispatch deliberately bypasses it, consistent with `StubMessageSender`'s comment
that retaining an OTP payload would violate `otp_challenge`'s never-store-the-code rule.

## 5. Real CheckAccount, proven against the live middleware

Not in the brief; the product owner supplied a real account and asked. All three Response_Codes now
covered live, including `1 Account Found` **for the first time** — the component card recorded why
it never had been: "the only known-good account is a real one".

```
[live] cold=1970ms warm=359ms
[live] input=<real account, withheld> http=200 content-type=application/json
       body={"Response_Code":1,"Response_Message":"Account Found"}
[live] input=00000000            body={"Response_Code":0,"Response_Message":"Account not Found"}
[live] input=<empty>             body={"Response_Code":-1,"Response_Message":"System Error"}
[live] input=99999999999999999999 body={"Response_Code":0,"Response_Message":"Account not Found"}
```

The account number reached the test only as an environment variable and is never printed, asserted
on, or committed. Two calls are timed separately on purpose: the first attempt reported 4200 ms,
which would have made the 5 s connect timeout look marginal — it was JVM warm-up and a cold TLS
handshake. Warm steady state is **359 ms**, faster than the documented 0.79–0.98 s.

`infra/aws/11-egress-probe.sh` (new) proved reachability from the backend's **own** SG, subnet and
NAT:

```
  RESULT civil-registry mb1.sfbank-sd.com:5353 dns=196.1.223.28 tcp=OPEN
  RESULT uqudo-auth     auth.uqudo.io:443      tcp=OPEN
  RESULT uqudo-api      id.uqudo.io:443        tcp=OPEN
  RESULT core-banking   mb1.sfbank-sd.com:9494 tcp=OPEN
```

**Why this needed its own script**: the admin host's SG has no 5353 egress, so a connect test from
there times out and reads exactly like an unreachable bank endpoint. It cost real time before the
cause was found. The Uqudo control target is deliberate — without it a 5353 timeout is equally
consistent with a broken probe or a missing NAT route.

The probe's first run reported "no verdict" for a run that had in fact succeeded: it read the log
stream before CloudWatch had ingested it. Fixed with a retry loop — the same
report-failure-when-nothing-failed shape, inverted.

`fru.core-banking.http.endpoint` was also bound in `application-aws.properties` and
`/fru/staging/core-banking/endpoint` created. **The profile bound the selector but never the
endpoint**, so flipping `FRU_CORE_BANKING_CLIENT` to `http` would have failed at startup — correctly,
but only after a deploy. `07` and `08` updated so the stack stays reproducible from the scripts. The
selector remains `stub`: the wiring lands, the behaviour does not change.

## 6. BL-066 — the owed real-sign-in confirmation, discharged

Session 2 proved both cookies carry `Secure` on a **401** and flagged that a real sign-in was still
owed. That was not pedantry: `sessionFixation().changeSessionId()` mints a **new `JSESSIONID`** when
authentication succeeds, by a different path than the anonymous one.

Read from DevTools after a real operator sign-in through CloudFront, values masked by the product
owner:

```
 Name         | HttpOnly | Secure
 JSESSIONID   |    ✓     |   ✓
 XSRF-TOKEN   |          |   ✓
```

Correct in both directions — the session cookie unreadable to JavaScript, the CSRF token readable
because the SPA must read it. **BL-066 is closed on all three counts: the property, the assertion,
and the real sign-in.**

The same screenshot produced a new finding: neither cookie states `SameSite` (BL-078).

## 7. Findings, triaged

| # | Finding | Class | Filed |
|---|---|---|---|
| 1 | Registry lookup impossible for passport holders (hyphens) | **blocker** | **fixed this session** |
| 2 | Operator cannot see any identity image — R-046 demonstrated live | **blocker** (blocked on an open decision) | BL-075 |
| 3 | `admin` role structurally cannot review; only account that existed | **blocker** | resolved by GATE 1 |
| 4 | `resume_stage` dead column, always 1, comment claims otherwise | fix-before-real-users | BL-077 |
| 5 | `applicationId = com.example.mobile`, immutable after first store upload | fix-before-real-users | BL-079 |
| 6 | `SameSite` unstated on both operator cookies | fix-before-real-users | BL-078 |
| 7 | `doc_front_frame` listed as a 1.97 MB attachment though AD-004 stores no bytes | cosmetic now, breaks when R-046 lands | BL-075(a) |
| 8 | UI polish: bank logo, look and feel, non-idiomatic Arabic in status history, ISO dates in both tiers, `--` for an em-dash, RTL alignment of the one Latin field, portrait-shaped signature preview, sparse Stage 12 | cosmetic | BL-076 |

**The brief's pointer to "OQ-026/BL-055" for the UI polish pass was wrong** — BL-055 is device-run
logcat observability and OQ-026 is Uqudo's billed unit. The polish pass had no home; BL-076 is it.

Passes worth recording so the polish pass does not undo them: the national number **is** rendered
first, alone and prominent as customer.md requires, and reads that way on a real 5-inch screen; the
irreversibility warning sits directly above the submit button; the confirmation screen names only
the customer's actually-verified channels; the signature downscale was imperceptible on a 2019
budget phone.

## 8. Gates

Backend touched, so `./mvnw verify -Pdb-integration-test` — the real coverage gate. It ran twice:
the first run **failed** (§3, pasted there as evidence). Final run, verbatim:

```
[INFO] Tests run: 1032, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 444 files clean - 0 needs changes to be clean, 0 were already clean, 444 were skipped because caching determined they were already clean
[INFO]
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 324 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  02:05 min
[INFO] Finished at: 2026-09-07T00:05:36+02:00
[INFO] ------------------------------------------------------------------------
```

Coverage passing is itself the evidence the integration suite ran: standard tests alone measure
60.52%, which fails the check.

Mobile: rebuilt only (`fvm flutter build apk --debug --dart-define=REFERENCE_API_BASE_URL=…`), no
Dart changed, so no mobile gate applies and none was run. No Flutter package was added, so no iOS
support check was due. Back office untouched.

The deployed image was verified to carry the new property **inside the shipped jar**, not just the
working tree, before it was pushed.

**`@agent-reviewer` was NOT run against this diff, and CLAUDE.md requires it before a task is marked
done.** This session ran under an explicit instruction not to spawn subagents unless asked, which
conflicts with that rule; the harness constraint was honoured and the gap is recorded here rather
than glossed. The reviewer step is **owed** on the S7-12 diff — specifically on the
`HttpCivilRegistryClient` asymmetric-canonicalisation change, which is the kind of subtle guard
logic a review exists to catch, and where this session already got the first draft wrong.

## 9. What this proves, and what it does NOT

**Proves:** the solution runs end to end on AWS against the project's own real Uqudo tenant and the
real Civil Registry, with a real operator completing the review.

**Does NOT prove, stated plainly so "the walk passed" is not read as "production-ready":**

- The account is still personal-root-owned (**BL-072**) and the public name is still AWS's
  (**BL-074**). Staging on AWS is not production.
- **R-026 and R-051 are untouched.** R-051 remains a hard Phase 2 entry gate.
- **SDN_ID is unexercised** — passport only, no national ID card. Note the walk's own defect was
  passport-specific, so the card route's number format is still unobserved.
- **The email OTP channel was never exercised** — the product owner declared only two channels.
- **The four-eyes rule was NOT tested.** It gates approval of a *manually completed* profile; this
  one was digitally submitted, so the rule never engaged. Testing it needs a manually-completed
  profile and a second operator.
- **The operator review was not a real review** — no identity image is viewable (BL-075).
- Core banking ran as `stub` for the journey. The real middleware was proven separately, at the
  adapter, not through the deployed journey.

## 10. Filed this session

- `infra/aws/11-egress-probe.sh` (new); `07-ecr-and-params.sh`, `08-backend-service.sh` updated
- `RegistryFieldNormaliser.digitsOnly` + `HttpCivilRegistryClient` fix, 2 adapter tests, 5 normaliser tests
- `HttpCoreBankingClientLiveTest.aRealAccountIsFound`
- `application-aws.properties`: `fru.core-banking.http.endpoint`
- BL-075, BL-076, BL-077, BL-078, BL-079; R-001 and R-046 updated
- Image `0.0.1-20260907t0006`, task definition revision 4, deployed and settled
