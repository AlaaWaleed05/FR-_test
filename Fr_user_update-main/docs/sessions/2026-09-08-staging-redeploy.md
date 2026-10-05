# S8-08 — staging redeploy: the SFB- build, and WhatsApp off

Date: 2026-09-08. Tier: **deployment and configuration only — no repository code changed.**
Task row: EXECUTION_PLAN.md S8-08 (added this session). Both actions were owed by
`docs/sessions/2026-09-08-backend-prepilot.md` §Owed and both are on the critical path to the pilot.

The stack was already up and was used, not re-provisioned. Nothing was torn down.

## What changed on the running stack

| | Before | After |
|---|---|---|
| Task definition | `fru-staging-backend:8` | **`fru-staging-backend:10`** |
| Image | `0.0.1-20260907t1848` (pre-S8-07) | **`0.0.1-20260908t1638`** |
| Image digest | — | `sha256:1cd6ab21876c80a1892c560543b891a6f302673262421197ec8880d30b5309d0` |
| Reference prefix minted | `FRU-` | **`SFB-`** |
| `FRU_MESSAGING_WHATSAPP_ENABLED` | unset (so `enabled` defaulted `true`) | **`false`** |

Two revisions were registered, not one, and that was deliberate — see "Why two deployments".
Revision 9 = new image only. Revision 10 = revision 9 plus the WhatsApp flag.

Everything else is byte-identical to revision 8: same 13 secret references, same six selectors,
`FRU_CORE_BANKING_CLIENT` still `stub` (BL-089 untouched), no country/reference-data change
(BL-092 untouched). The revision-10 JSON was derived programmatically from revision 9's own
`describe-task-definition` output with one key added, so no value could drift by transcription.

### Order of operations — read this before the two proof sections

The two actions are written up in the brief's order, but they did **not** run in that order, and the
evidence only makes sense under the real one:

```
probe on rev 8 (whatsapp: unverified)   →  deploy rev 9  →  probe (whatsapp: unverified)
  →  deploy rev 10  →  probes (a) and (b) (whatsapp: declined)  →  manual completion LAST
```

The mint had to be last. Manual completion moves the profile to `submitted`, which
`V0005__app_status_and_profile.sql` marks terminal, and `ContactChannelsService` rejects a re-entry
on a terminal profile with `ProfileAlreadyCompleteException` → 409. So probe (b)'s `HTTP 200` on
that same profile is only possible before the completion, and running the mint first would have
destroyed the ability to prove Action 2 at all. It is also why the seeded account is now spent.

## Action 1 — the S8-07 build is deployed, and it mints `SFB-`

Built from `9d97f80` with a clean tree — the S8-07 commit itself. `./mvnw package -DskipTests`
(BUILD SUCCESS, 13.9 s); the full gate at this commit is S8-07's, not re-run here because no code
changed since.

**The jar was checked before it was shipped**, because "I built from the right commit" and "the
artifact mints SFB-" are different claims. Both compiled mint sites inside
`backend/target/backend-0.0.1-SNAPSHOT.jar`:

```
--- BOOT-INF/classes/.../submission/jdbc/JdbcSubmissionRepository.class
  SFB- occurrences: 1
  FRU- occurrences: 0
--- BOOT-INF/classes/.../operator/jdbc/JdbcManualCompletionRepository.class
  SFB- occurrences: 1
  FRU- occurrences: 0
```

Pushed to ECR under a new immutable tag, deployed with `infra/aws/08-backend-service.sh` (the
documented redeploy path — it correctly declined to re-open the VPC-CIDR placeholder on the ALB
security group), and the running task's digest read back from ECS:

```
<account>.dkr.ecr.eu-central-1.amazonaws.com/fru-staging-backend:0.0.1-20260908t1638
digest: sha256:1cd6ab21876c80a1892c560543b891a6f302673262421197ec8880d30b5309d0   (identical to the pushed digest)
```

### The proof is a minted number, not a digest

A matching digest only says the right bytes are running. The brief asked for the actual minted
prefix and it is right to: the failure mode is a deploy that reports success and still serves the
old behaviour. So a reference number was **generated on the deployed stack**:

```
POST /api/v1/operator/profiles/b61be8ad-…/manual-complete    (task definition 10)
→ HTTP 200
  {"profileId":"b61be8ad-…","status":"submitted","alreadyDone":false,
   "referenceNumber":"SFB-000000004","notifiedChannels":[]}
```

`alreadyDone:false` is the load-bearing field — this is a fresh mint off
`app.reference_number_seq`, not a stored value read back. `notifiedChannels:[]` because the
profile had no verified channel, so the completion sent no message.

Read back from the database, every reference number this stack has ever issued:

```
reference_number status     provenance submitted_at
FRU-000000001    approved   digital    2026-09-07 00:24:03.602189
FRU-000000002    submitted  digital    2026-09-07 20:46:00.554419
FRU-000000003    submitted  digital    2026-09-07 21:55:41.718457
SFB-000000004    submitted  manual     2026-09-08 16:48:42.729543
```

That table is worth more than the response line. The prefix flips exactly at the deployment
boundary, the sequence continues unbroken across it, and **the three pre-existing `FRU-` numbers
are untouched** — S8-07 asserted that in a test; here it is live on real rows.

### Why the mint went through manual completion, and what it cost

The other mint site is submission, which requires an accepted Uqudo identity cycle, a face result
and a committed signature — a real document scan and liveness pass on a handset. Not scriptable.
Manual completion is the only mint reachable from an API client, and it needs `ROLE_OPERATOR`.

Cost, stated plainly: **one seeded stub account was consumed** — `0000001030`, chosen as the
highest so the low-numbered accounts stay free for the pilot walk. Manual completion moves the
profile to `submitted`, which is terminal, so that account is now spent. Twenty-nine of the thirty
BL-089 accounts remain. The phone number used was `+249900000003`, synthetic, matching the
repository's own `fru.messaging.stub.outcomes` convention. No real customer data was created.

## Action 2 — `fru.messaging.whatsapp.enabled=false`, proven behaviourally

### The property name was confirmed against source, not assumed

`MessageSenderConfiguration.enabledProperty` (`:92-94`) builds
`"fru.messaging." + channel.wireValue() + ".enabled"`, and two places read it through
`Environment.getProperty(..., Boolean.class, true)` — the `messageSender` bean (`:78-81`, which
drops a disabled channel from the router map) and `ContactChannelsService.isEnabled` (`:487-490`),
which feeds `ChannelSelection.resolve`'s `whatsappEnabled`. `stateFor(selected, enabled)` returns
`UNVERIFIED` only when both hold, else `DECLINED`. So the flag decides whether a challenge is
minted at all, which is exactly what BL-086 asked for.

### The relaxed-binding hazard was real, and was settled by measurement

`application-aws.properties:58-66` explicitly warns against relying on Spring's environment-variable
binding and names the hyphens as the reason — `fru.core-banking.client` cannot be un-mangled from
`FRU_CORE_BANKING_CLIENT` unambiguously, so every selector is bound by an explicit `${...}` line
instead. **`fru.messaging.whatsapp.enabled` has no hyphen in any segment**, so
`FRU_MESSAGING_WHATSAPP_ENABLED` maps to it unambiguously — but that is an argument, not evidence,
and this stack has been bitten here before. It was therefore not trusted; it was tested against the
deployed application, and the behavioural result below is what settles it.

### Before / after, same request, same account

All three probes are the same endpoint on the same profile via the re-entry path, so nothing but
the deployment differs between them.

```
=== BEFORE (task def 8, image 0.0.1-20260907t1848, no flag) ===
POST /api/v1/contact-channels {"branch":"001","accountNumber":"0000001030",
                               "phoneNumber":"+249900000003","sms":false,"whatsapp":true}
→ HTTP 200
  {"profileId":"b61be8ad-…","channels":[
     {"channel":"sms","state":"declined","maskedDestination":"•••• 0003"},
     {"channel":"whatsapp","state":"unverified","maskedDestination":"•••• 0003"}]}
```

`unverified` means a WhatsApp OTP challenge **was** minted and handed to
`FRU_MESSAGING_WHATSAPP_PROVIDER=stub`, which delivers nothing. That is the tester-waits-forever
failure this action exists to close, reproduced live before touching it.

```
=== AFTER THE IMAGE, BEFORE THE FLAG (task def 9, image 0.0.1-20260908t1638) ===
same request → HTTP 200
  ...{"channel":"whatsapp","state":"unverified",...}
```

Unchanged. **This is why two deployments were used**: it isolates the new image from the flag. Had
the flag been folded into a single revision, "WhatsApp stopped being challenged" would have had two
candidate causes and the proof would have been weaker.

```
=== AFTER THE FLAG (task def 10) ===
probe (a) same request (whatsapp ticked, sms unticked)
→ HTTP 400  {"status":400,"error":"Bad Request","path":"/api/v1/contact-channels"}

probe (b) whatsapp TICKED alongside sms
POST ... {"sms":true,"whatsapp":true}
→ HTTP 200
  {"profileId":"b61be8ad-…","channels":[
     {"channel":"sms","state":"unverified","maskedDestination":"•••• 0003"},
     {"channel":"whatsapp","state":"declined","maskedDestination":"•••• 0003"}]}
```

Probe (a) is `NoPhoneChannelSelectedException` — with WhatsApp disabled and SMS deselected, no phone
channel survives enablement, so the request is refused before anything is written or sent. The
identical request returned 200 twice on the two previous revisions. That alone proves the flag bound.

Probe (b) is the answer to the question actually asked: **a tester who ticks WhatsApp anyway now gets
`declined`, not `unverified`.** No challenge row, no message handed to a stub, no waiting forever.
The journey continues on SMS. The flag is not merely present in the task definition — the deployed
application read it and changed what it did.

Probe (b) sent one real SMS through the Airtel gateway to `+249900000003`, a synthetic
non-subscriber number. That was the price of exercising the ticked-WhatsApp path with a phone
channel that survives; no real handset was contacted.

## The flag is NOT durable, and that is the one thing to act on

`infra/aws/08-backend-service.sh` builds its task definition from a literal here-document that does
not contain `FRU_MESSAGING_WHATSAPP_ENABLED`. That script is, by its own README, "the script you
re-run to deploy a new image". **The next person who redeploys with it silently reverts this
change** and staging goes back to minting undeliverable WhatsApp challenges, with nothing failing
and nothing logged.

Making it durable means editing `08-backend-service.sh` (and arguably adding an explicit
`fru.messaging.whatsapp.enabled=${FRU_MESSAGING_WHATSAPP_ENABLED:true}` line to
`application-aws.properties`, which is what that file's own §9 convention demands rather than
leaning on relaxed binding). Both are repository code, which this session's brief put out of scope
and told me to flag rather than build. **Flagged, not built** — filed as **BL-097**.

## The throwaway operator, and why it was disabled rather than deleted

Created for the mint proof over an SSM port-forward to RDS, exactly as S7-12 did:
`probe-s8-redeploy`, role `operator`, user id `24520a82-…`. The runner's one-time password went
straight to a file outside the repository and was never printed, logged, or written to any
repository file; the forced first-change password was generated locally and handled the same way.
Both files were shredded at the end of the session. `git status` is clean.

**Deletion was refused, and not out of caution.** V0057's own comment on the table says it:

> `operator_user`: … **NEVER deleted** — `app.profile_status_history.actor_id` and
> `audit.audit_event.actor_id` both reference `user_id` as plain text forever, and both tables are
> append-only, so a deleted account would orphan the four-eyes predicate (V0009) and every audit
> attribution. Disable, do not delete — same reasoning as `app.profile`.

This account has now performed an audited manual completion, so its id is in both of those
append-only tables. Deleting the row would orphan that attribution permanently. `fru_app` also holds
only `SELECT, INSERT, UPDATE` on the table — no `DELETE` grant — so the design forbids it twice.

Disabled instead — the same in-place `UPDATE … SET is_enabled = false` operation
`db/post-migrate/03-create-operator-account.md:43-45` uses when a one-time password is lost. (That
document describes it for the lost-password case, not as an end state for a throwaway account; the
load-bearing argument here is V0057's, above. Stated precisely because the weaker citation was in an
earlier draft of this report and review caught it.) Then **proven neutralised rather than assumed**:

```
username             role      enabled  disabled_at_set
admin                admin     true     false
operator1            operator  true     false
probe-s8-redeploy    operator  false    true

POST /api/v1/auth/login   with the CORRECT password  → HTTP 401
GET  /api/v1/auth/me      with its live session cookie → HTTP 401
```

The correct password no longer signs in and the account's existing session is dead. It is inert, not
merely marked. Because a disabled row still lingers with no admin surface to manage it, it is filed
as **BL-096** rather than left undocumented — per the explicit instruction.

## The pilot APK — an honest note

The brief asked me to state that the pilot APK should be rebuilt after this. The accurate version:
**these two deploy actions do not by themselves invalidate a built APK.** The app's only coupling to
the backend is `REFERENCE_API_BASE_URL`, still `https://d12k860j1xg6zy.cloudfront.net`, and no wire
contract changed — `ContactChannelsResponse` is the same shape, it just now reports `declined` where
it reported `unverified`.

What genuinely changed is that **every device validation done before today was against the old
backend**. So the consolidated walk must be run against revision 10, and the APK handed to staff must
be one built from at least `0fdd9d5` (the last mobile commit, carrying S8-05's look-and-feel and
S8-06's `com.sfbank.bayanati` namespace). Whether the existing batch-2 APK already satisfies that is
the mobile session's record to confirm, not this one's.

## Gates

**None run, and none owed: no repository code was changed.** The working tree is clean; the only
file written outside `docs/` is `infra/aws/app-tier-ids.env`, which is gitignored and is regenerated
by `08-backend-service.sh` on every deploy. The backend gate evidence for this image is S8-07's —
`./mvnw verify -Pdb-integration-test`, 1092 tests, 0 failures, coverage met — and this session ships
that exact commit.

## Final deployed state

```
service   fru-staging-backend   1/1 running   taskDefinition :10   rolloutState COMPLETED
target    10.0.12.251           healthy       (the previous task's ENI still draining)
GET https://d12k860j1xg6zy.cloudfront.net/api/v1/reference/manifest → HTTP 200
```

## Owed

- **BL-097 — the WhatsApp flag is not in `08-backend-service.sh`.** The next redeploy through the
  documented script reverts it silently. Repository change, deliberately not made here.
- **BL-096 — `probe-s8-redeploy` is disabled but still present, and will stay present permanently.**
  Deletion is barred by V0057's audit-integrity rule, so no future session will ever remove this row
  and nothing tracks it as removable. That is a decision, not an oversight: what BL-096 files as
  genuinely owed is the missing admin surface for managing operator accounts, which `admin` and
  `operator1` from S7-12 need just as much.
- **BL-092 / W-12 Sudan-first** — still deferred, still needs the PO's regional country list, and its
  migrate-plus-publish is a separate indivisible deploy step. Untouched here, as instructed.
- **BL-089** — `FRU_CORE_BANKING_CLIENT` remains `stub`. Not flipped, as instructed.
- **R-042** — nothing tells the app which channels a deployment can verify. The flag closes the
  minting half of BL-086; the app still renders a WhatsApp row it cannot know is unavailable. It is
  now merely unticked (S8-05) rather than actively harmful, which is the improvement, not the fix.
- **One seeded stub account spent** — `0000001030`. Twenty-nine remain.
- **R-026 / R-051** are not discharged by any of this; staging is still staging.
