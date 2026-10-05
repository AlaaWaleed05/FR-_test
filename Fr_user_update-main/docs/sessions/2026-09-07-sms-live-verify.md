# 2026-09-07 — live SMS send verification on AWS (S8-02, BL-080, R-041)

**Guided session.** Claude Code did the AWS and backend side; the product owner placed the Airtel
credentials and confirmed the handset. No credential, no phone number and no request URL was
handled, printed, logged or committed — not even redacted.

**Headline: a real Arabic OTP was sent from the deployed AWS stack through the real Airtel gateway
and arrived on a real handset in the same minute, Arabic rendering correctly, sender displayed as
`SFB`. Road map item 1.1 is closed. The send also exposed a defect no test could have caught — every
customer-facing message in the system named the CENTRAL BANK — which the product owner elected to
fix in-session.**

Scope was config-and-send. One code change was made, on an explicit product-owner override of the
brief's own flag-don't-fix rule; that override is recorded in §7 rather than glossed.

---

## 1. The order was inverted, deliberately

The brief put credential wiring first and egress second. That order costs the product owner a
credential-handling step for a stack that might not reach the gateway at all — and a stack that
cannot reach the host looks identical to a broken adapter. **The egress probe ran first.**

`infra/aws/11-egress-probe.sh` with `PROBE_EXTRA="airtel-sms=https://www.airtel.sd/api/html_send_sms/"`,
which is exactly the hook S7-12 built for an endpoint with no Parameter Store entry yet:

```
  RESULT civil-registry mb1.sfbank-sd.com:5353 dns=196.1.223.28    tcp=OPEN
  RESULT uqudo-auth     auth.uqudo.io:443      dns=150.171.109.106 tcp=OPEN
  RESULT uqudo-api      id.uqudo.io:443        dns=2603:1061:14:64::1 tcp=OPEN
  RESULT airtel-sms     www.airtel.sd:443      dns=196.202.146.54  tcp=OPEN
```

From `fru-staging-ecs-sg`, in the app subnet, behind the same NAT as the running backend — the
backend's own path, not the admin host's, which is the distinction that cost S7-12 real time. Both
Uqudo controls passed, so `tcp=OPEN` for Airtel is a fact about the far end rather than about the
probe. STEP 2 did not block.

## 2. What the property keys actually are

Read from source before wiring anything, per the brief. `AirtelSmsProperties` binds
`fru.messaging.sms.http.*`; `MessageSenderConfiguration.airtelSmsSender` names the four it demands.
**`sender-id` is kebab-case, not `senderId`** — worth stating because it is the one key where a
wrong guess binds to nothing.

| Property | Value | Where |
|---|---|---|
| `fru.messaging.sms.provider` | `http` | task-definition env literal |
| `fru.messaging.sms.http.endpoint` | the gateway URL | Parameter Store |
| `fru.messaging.sms.http.sender-id` | `SFB` | Parameter Store |
| `fru.messaging.sms.http.username` | *(real)* | Secrets Manager, product owner |
| `fru.messaging.sms.http.password` | *(real)* | Secrets Manager, product owner |

## 3. The deployment-config gap this session found

**`application-aws.properties` bound the SMS *selector* but none of the four values that selector
demands.** Flipping `FRU_MESSAGING_SMS_PROVIDER` to `http` would have failed at startup naming four
properties — correctly, but only after a deploy. **This is S7-12's core-banking finding in a new
place**: the Uqudo block was wired this way, core banking was missed and fixed at S7-12, SMS was
missed and is fixed here.

Four explicit `${…}` bindings were added rather than relying on Spring's relaxed environment-variable
binding. Relaxed binding *would* have worked — `AirtelSmsProperties` is a `@ConfigurationProperties`
record, not `@Value`, so `FRU_MESSAGING_SMS_HTTP_SENDER_ID` binds onto `sender-id`. It was still
written out, because the hosting mapping's §9 is explicit that a hyphenated key must not depend on
it: a key that silently fails to bind lands in the one failure mode the whole design exists to
prevent. The alternative was put to the product owner with that trade-off stated, and the explicit
form was chosen.

Empty defaults (`${…:}`) match the Uqudo and core-banking blocks: `airtelSmsSender` performs its own
check and names **every** missing property in one message, a better error than a
placeholder-resolution stack trace naming one — and an empty default is what lets the same image
still run with the selector set to `stub`.

## 4. Wiring, and who did which half

- `07-ecr-and-params.sh` — created `/fru/staging/sms/endpoint` and `/fru/staging/sms/sender-id`.
  **Configuration, not secrets**: the gateway host changes before production and the sender id is
  printed on every message a customer receives.
- `07` also gained a **precondition check** on `fru/staging/sms`: present, both keys non-empty,
  verdict only. Modelled on the Uqudo check and existing for the same reason — the Uqudo secret was
  once filed with placeholder prose from a session brief and a key-name-only check passed it. Before
  the product owner filed the secret, `07` correctly failed:

  ```
  FAIL  fru/staging/sms does not exist - the product owner must file the Airtel account
        credentials (keys: username, password) before the SMS selector may be 'http'
  ```

  and afterwards:

  ```
  fru/staging/sms present, both keys non-empty (values never printed)
  ```

- `08-backend-service.sh` — the four values injected by reference (two Parameter Store, two Secrets
  Manager), and `FRU_MESSAGING_SMS_PROVIDER` flipped from `stub` to `http`. WhatsApp and email stay
  `stub`: they have no real adapter, and `MessageSenderConfiguration` refuses `http` for them at
  startup. The literal in the task definition is commented as what it is — every OTP and status
  notification from this stack now costs real money and lands on a real handset.

**The credentials themselves were placed by the product owner directly in the console.** This
session never read them; the structural check prints a verdict only.

## 5. Deploy, and the startup proof that came free

Image `0.0.1-20260907t1258`, digest
`sha256:a36581c3ac8ae4673d91c0ae283949d3f7a864a4d631b2dc75b4dacf8c40c457`, task definition revision
5, rollout `COMPLETED`, 1 task running.

The four bindings were verified **inside the shipped jar**, not just the working tree — the check
S7-12 established, because a property that exists only in the repo is a property the running task
has never seen.

**`Started BackendApplication in 12.72 seconds` is itself a load-bearing proof.** `airtelSmsSender`
throws `IllegalStateException` during context refresh if any of the four is missing or blank, so a
task that reached a passing health check is a task where all four bound and the credentials
resolved. No separate assertion was needed.

## 6. The send, and what came back

One SMS. `POST /api/v1/account-check` then `POST /api/v1/contact-channels` with `sms=true`,
`whatsapp=false`, no email — the genuine Stage 1b OTP path through `AirtelSmsSender`, not a bypass.
Account `0000000009`, synthetic; `0000000001` carries S7-12's profile in terminal status `approved`
and would have been refused with 409 by design.

Read back from `audit.audit_event` — the SUCCESS path deliberately logs nothing, so the audit chain
is the only record. The query ran **on the admin host over SSM**, so the database credential was
read by the host's own IAM role and never left AWS:

```
event_type              | at       | channel | outcome  | provider | status_code | api_msg_id | status_text | billed_segments | latency_ms
notification_dispatched | 11:12:01 | sms     | ACCEPTED | airtel   | completed   | 4526963    |             | 2               | 1969
(1 row)
```

- `ACCEPTED` is **positively confirmed**, not inferred from HTTP 200 — which is 200 on both paths.
- `billed_segments = 2` independently corroborates the body was the real Arabic OTP: two UCS-2
  segments, matching the 2026-09-07 Arabic capture. A Latin "test" string of that length bills 1.
- `(1 row)` — exactly one message. WhatsApp `declined`, email absent.
- `status_text` empty is correct; it carries the provider's own words only on failure.

### The credential-in-URL guard, verified live

The request URI carries the password in its query string, so the URI **is** a credential. S8-01
closed three leak paths and proved two by revert-restore against mocks. This was the first
opportunity to check against a real credential on a real send. Across the entire log group:

```
  html_send_sms      0
  airtel.sd          0
  password           0
  username           0
  phone_number       0
  SFB                0
  AirtelSmsSender    0
  Airtel             0
```

Zero, including the destination's digits in both stored and national form. One apparent match
appeared on a first pass and was a **pagination artefact** of `length(events)` — resolved by
re-running unpaginated and by enumerating every matching event, which returned nothing. Recorded
because "we saw a 1 and moved on" is how a real leak gets missed.

## 7. The defect the live send found — BL-085

**Reading the message on a handset showed every customer-facing message in the system names the
Central Bank of Sudan.** `OtpMessageRenderer`, `SubmissionMessageRenderer` and
`ReviewMessageRenderer` each hard-coded `BANK_NAME_AR = "بنك السودان"` — the regulator. The bank is
the Sudanese French Bank, «البنك السوداني الفرنسي».

**No test could have caught this and none did.** A unit test asserts the string it was given; it
cannot know the string names the wrong institution. It survived the S7-12 acceptance walk because
both of that walk's OTPs went through the stub — nobody had ever read one of these messages on a
phone. It is the same misattribution BL-081 corrected in the *store listing* wording; the message
bodies were never checked.

It is more than a wording bug: an OTP naming the regulator is phishing-shaped. It tells the customer
the central bank is asking them to verify a code, and the bank's own brand never appears —
undermining the one anti-phishing cue an OTP has.

**Fixed in-session, against the brief, on an explicit product-owner decision.** The brief said to
stop and flag a code fix. The product owner was asked directly, with "file it, fix with BL-081's
naming work" offered as the recommendation, and chose to fix now. The wording was **not guessed** —
«البنك السوداني الفرنسي» is the formal name the product owner had already settled in BL-081.

**Cost was measured before changing it**, because the correct name is longer: the body grows
104 → 115 characters and stays at **2 UCS-2 segments** (the two-segment ceiling is 134). The correct
name bills exactly the same as the wrong one, so R-043 raises no objection.

**The regression guard had to be rewritten, not merely retargeted.** The old assertion was
`body().contains("بنك السودان")`. That string is a **substring** of «البنك السوداني الفرنسي», so a
`contains` check on either name passes against the defect it is supposed to guard. It now asserts
`startsWith("البنك السوداني الفرنسي: ")`. That is a direct wrong-value assertion, so **no
revert-restore is owed**: it cannot pass against the old constant.

**Proven live, not only in tests.** The corrected build was rebuilt (`0.0.1-20260907t1344`), the three
shipped `.class` files were checked to carry the formal name with **zero** bare occurrences of the
regulator's name, and rev 6 was deployed. A second SMS — the last of the two the brief allowed — went
out and was confirmed on the handset:

```
at       | outcome  | status_code | api_msg_id | billed_segments | latency_ms
11:49:40 | ACCEPTED | completed   | 4528435    | 2               | 2144
(1 row)
```

The product owner's screenshot shows both messages in one thread: 13:12 opening `بنك السودان:` and
13:49 opening `البنك السوداني الفرنسي:`. **Still 2 segments**, exactly as the cost check predicted — the
correct name is free. **Redeploying was not optional:** staff will test against the deployed stack, so
fixing this in the repository alone would have left every staff-test message naming the regulator.

**Deliberately not changed:** the bank's own captured message places the name at the **end** of the
body («… لا تشاركه مع أي شخص. البنك السوداني الفرنسي»), while the template leads with it and a colon.
Only the name was changed; the position is left to BL-081's wording pass rather than decided here.

## 8. Handset confirmation — the actual proof

Reported by the product owner, with a screenshot:

| Question | Answer |
|---|---|
| Arrived? | **Yes** |
| Arabic correct? | **Yes** — real letters, correct RTL, code readable |
| Latency | **13:12 Sudan time**, the same minute the gateway accepted it (11:12:01 UTC) |
| Sender shown | **`SFB`** |
| Network | **Sudani** |

**The network matters as much as the arrival.** Sudani is not the gateway operator's own range, so
this was an **interconnect** delivery — R-041's harder case, and the one that row predicted would
fail. The unregistered alphanumeric sender was neither dropped nor rewritten.

## 9. Gates

Backend touched twice: config only for the wiring, then a code change for BL-085. Both runs used
`./mvnw verify -Pdb-integration-test`, the real coverage gate.

First run (config wiring only): **1091 tests, all passing, coverage met** — one line per the rule.

Second run (after the BL-085 fix) **FAILED**, and the failure is pasted because it is evidence:

```
[ERROR] Run 'mvn spotless:apply' to fix these violations.
[ERROR] Violations also present in:
[ERROR]     ReviewMessageRenderer.java
[ERROR]     SubmissionMessageRenderer.java
[ERROR]     OtpMessageRendererTest.java
[ERROR]     Ucs2SegmenterTest.java
[ERROR]     StubMessageSenderTest.java
```

**Cause, worth recording because it disguises itself.** The edits were scripted and wrote LF; these
files are stored CRLF. Spotless then reported a diff of *every line in every file*, which reads like
a formatting catastrophe rather than a line-ending flip — the actual change was one constant per
class. `spotless:apply` restored the endings, and `git diff --stat` afterwards showed only the
intended edits. Tests had already passed; Spotless runs after them.

Final run, after the BL-085 fix, verbatim:

```
[INFO] Results:
[INFO] 
[INFO] Tests run: 1091, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] 
[INFO] --- jacoco:0.8.15:report (jacoco-report) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 330 classes
[INFO] 
[INFO] --- jar:3.5.0:jar (default-jar) @ backend ---
[INFO] Building jar: C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar
[INFO] 
[INFO] --- spring-boot:4.1.0:repackage (repackage) @ backend ---
[INFO] Replacing main artifact C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar with repackaged archive, adding nested dependencies in BOOT-INF/.
[INFO] The original artifact has been renamed to C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\backend-0.0.1-SNAPSHOT.jar.original
[INFO] 
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 456 files clean - 0 needs changes to be clean, 0 were already clean, 456 were skipped because caching determined they were already clean
[INFO] 
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 330 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  03:02 min
[INFO] Finished at: 2026-09-07T13:43:30+02:00
[INFO] ------------------------------------------------------------------------
```

iOS support check: **not applicable** — no Flutter package was added; backend and infrastructure
only. Mobile and back office untouched, so no gate applies to either and none was run.

## 10. What this proves, and what it does not

**Proves:** the Airtel adapter delivers a real Arabic OTP end to end from the deployed AWS stack —
adapter, egress, credentials, encoding and real handset delivery. Road map 1.1's three conditions
are all met. Staff-test SMS is **GO**, subject to §11.

**Does not prove, stated plainly:**

- **R-041 is not closed and must not be downgraded on one handset.** One network delivered. Zain and
  MTN are untested, and the row's claim is about *partial* failure by network. Three phones at pilot
  closes it cheaply.
- **Delivery is still unobservable.** `ACCEPTED` means the gateway took the message. There is no
  delivery-receipt path from this gateway at all, so at pilot a silent handset remains
  indistinguishable from a successful send at the backend.
- **The wrong-password response is still uncaptured** (road map 0.2). The adapter's handling of it
  stays `[UNVERIFIED]`, and an unrecognised body still maps to `TRANSIENT_FAILURE`.
- **BL-084 is now reachable in production.** With a real adapter live, a persistently unrecognised
  response retries every 30 s forever, and nothing reads `attempt_count`. Out of scope here, but it
  stopped being theoretical the moment the selector flipped.
- **One send is not load.** Nothing here says anything about throughput or the campaign's volume.

## 11. Owed, and not glossed

**`@agent-reviewer` was NOT run against this diff**, and CLAUDE.md requires it before a task is
marked done. This session ran under an explicit instruction not to spawn subagents unless asked —
the same conflict S7-12 recorded. The harness constraint was honoured and the gap is stated here
rather than hidden. The reviewer step is **owed**, specifically on the BL-085 change: it is small,
but it alters every outbound message in the system, and the substring trap in its own test is
exactly the kind of thing a review exists to catch.

Also owed: road map 0.2's wrong-password capture; BL-084's attempt cap; and BL-081's wording pass,
which should settle the name's *position* in the body.

## 12. Files

Modified: `backend/src/main/resources/application-aws.properties` (four SMS bindings);
`infra/aws/07-ecr-and-params.sh` (two parameters, one precondition check);
`infra/aws/08-backend-service.sh` (four injections, selector flipped to `http`);
`backend/.../contactchannels/domain/OtpMessageRenderer.java`,
`.../operator/domain/ReviewMessageRenderer.java`,
`.../submission/domain/SubmissionMessageRenderer.java` (BL-085);
tests `OtpMessageRendererTest`, `Ucs2SegmenterTest`, `StubMessageSenderTest`;
`BACKLOG.md` (BL-080, BL-085 filed), `RISKS.md` (R-041), `EXECUTION_PLAN.md` (S8-02),
`docs/road-to-production.md` (1.1 closed).

**No credential, no phone number, no filled endpoint URL and no OTP code appears in any of them.**
