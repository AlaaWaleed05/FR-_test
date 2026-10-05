# S8-04 — Unblocking staff journey testing on AWS staging

A follow-up to S8-03, which found on the reference handset that **no account on staging could get
past Stage 1a**. Backend configuration and a redeploy; no application code changed.

---

## 1. The blocker, precisely

Three facts combine into a dead end:

1. AWS staging runs the core-banking **stub** (`FRU_CORE_BANKING_CLIENT=stub`), whose only ACTIVE
   seed was `0000000001`.
2. `ProfileRepository.findExisting` keys on the **account number alone** — the `app.profile`
   UNIQUE constraint has been account-only since V0061/BL-032. The branch appears in the rejection
   message but plays no part in the lookup, so trying another branch does nothing.
3. Stage 1a refuses re-entry once a profile is complete — customer.md: *"there is no re-entry, no
   supersede path"*.

`0000000001` had been completed during earlier testing, so it was burned permanently. Confirmed
live against the deployed stack, and this single line is the whole diagnosis:

```
0000000001   ACTIVE       TERMINAL               HTTP 200
```

The account check itself succeeds — the account IS active — but the continuation is `TERMINAL`
because a completed profile already exists for that number.

**An account number is therefore single-use, and that is by design, not an oversight.** V0010
documents `app.profile` as never deleted (90-day retention nulls PII in place instead), and
`app.profile_status_history` carries append-only triggers that reject even `TRUNCATE`. There is
no reset, and weakening either guard to create one would be the wrong trade.

---

## 2. Why seeding, not the real middleware

Both were on the table. The product owner chose seeding, and it is the better call for a pilot:

- **Repeatable.** It does not depend on the bank's middleware being reachable and healthy.
- **No real customer data.** Testing against the real `CheckAccount` would mean handling real
  account numbers, which CLAUDE.md forbids in the repo, in fixtures, in prompts and in reports.
- **It does not settle BL-089 by accident.** Flipping the selector is a real decision with its own
  verification; doing it as a side effect of needing a test account would be settling it in passing.

---

## 3. What changed

Thirty synthetic accounts, `0000001001`–`0000001030`, seeded in
`backend/src/main/resources/application.properties`. The `aws` profile does not override the stub
map, so the base seeds apply in staging.

**Choosing the range was the only part needing care.** `AbstractPostgresIntegrationTest` carries a
disjoint-range registry for every `@Tag("integration")` class — and warns, from experience, *"Grep
the sources, not just this list"*, because BL-041 once claimed a range on the strength of that list
and collided with a proof it did not mention. So the range was picked by grepping **all 136
ten-digit account literals** in the backend sources plus the one generated pattern
(`OtpVerificationIntegrationTest`'s `"00000002%02d"`). `00000010xx` was entirely free.

**`0000009999` was deliberately left unseeded.** It is `AccountCheckIntegrationTest`'s
`UNKNOWN_ACCOUNT`, and seeding it would have silently converted a passing not-found proof into a
test asserting the opposite of what it claims.

---

## 4. Gate output, verbatim

`./mvnw verify -Pdb-integration-test` — the real backend gate per CLAUDE.md, and the one that
matters here, since a range collision would only ever surface in the integration suite:

```
[INFO] Tests run: 1091, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 456 files clean - 0 needs changes to be clean
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

---

## 5. Live proof against the deployed stack

Shipped as `0.0.1-20260907t1848`, task definition revision **8**, rollout `COMPLETED`, 1/1 running.

Revisions 7 and 8 carry the **identical image digest** (`sha256:ccd7b34dda068a7ba4de8fdae526865b92cd78d5d62c063de0df236f277578c8`). Revision 8 exists only
because the deploy was re-run while recovering from a laptop shutdown mid-session; it re-registered the
same image rather than changing anything. Recorded rather than tidied away, so the revision numbers in
this report match what is actually running. The probes below were re-run against revision 8 and
reproduced every line.

```
account       outcome      continuation           status
0000001001    ACTIVE       PROCEED                HTTP 200
0000001015    ACTIVE       PROCEED                HTTP 200
0000001030    ACTIVE       PROCEED                HTTP 200
0000001031    INVALID      RETRY                  HTTP 200
0000009999    INVALID      RETRY                  HTTP 200
0000000002    -            -                      HTTP 503
0000000001    ACTIVE       TERMINAL               HTTP 200
```

Each line is chosen to prove something distinct:

| Probe | What it establishes |
|---|---|
| `...1001` / `...1015` / `...1030` | First, middle and last of the block are all live |
| **`...1031`** | One past the end is NOT seeded — the block is exactly thirty, with no accidental over-seed |
| **`0000009999`** | The integration suite's not-found account is still not-found — the proof it guards was not broken |
| `0000000002` | The middleware "System Error" path still reaches its 503 |
| `0000000001` | The original blocker, unchanged and now explained |

**On the device.** With the S8-03 APK unchanged, entering `0000001001` advanced past Stage 1a to
Stage 1b «وسائل التواصل» — where every previous attempt hit the terminal screen. That is the
blocker actually lifted in the app a tester will use, not merely in an API response.

---

## 6. What this does NOT fix

- **BL-089 stays open.** Core banking is still `stub` while Uqudo and Civil Registry are `http`, so
  a **real** customer account still returns "not found". Only the account-supply half is relieved.
- **Thirty accounts mean thirty complete walks, not thirty testers indefinitely.** Each is burned by
  one completed journey. When the block runs out, extend it — do not try to clear a profile.
- **BL-086 is now visible to every tester.** The Stage 1b screenshot shows both «الرسائل النصية»
  and «واتساب» checked by default, and the pilot is SMS-only, so a tester who leaves WhatsApp
  selected waits for a code that cannot arrive. This is the first session in which anyone could
  actually reach that screen, which is why it now matters in practice.

---

## 7. Commit proof

```
2c7fc0c fix(backend): seed thirty staff-testing accounts â€” staging had none usable
```

Pushed straight to `main`, per CLAUDE.md. `strartup.mp4` remains untracked and was not staged — every commit used explicit paths rather than `git add -A`.
