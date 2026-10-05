# Component: Outbound messaging (SMS · WhatsApp · email)

Status: **port, stub, `app.notification_outbox`, the port's first real caller, and the scheduled
outbox drain built (S3-05, S3-06, S6-04)** — **and, since 2026-09-07 (S8-01), one real adapter: `messaging/airtel/AirtelSmsSender`, the Airtel Sudan SMS gateway (BL-080), selected by `fru.messaging.sms.provider=http` and accepted for SMS only. WhatsApp and email still have no adapter and no account** — SMS is the only messaging channel in the release version (product-owner decision 2026-09-07)
Last verified: 2026-09-04 (S6-04, live against a real PostgreSQL 18) · Card created 2026-08-29
(AD-002c research), filed 2026-08-29 (S3-04), built 2026-08-29 (S3-05), first `INTERACTIVE`
caller added 2026-08-29 (S3-06), outbox drained on a schedule 2026-09-04 (S6-04)

## S3-06 — the first `Urgency.INTERACTIVE` caller

Journey Stage 1b (`com.sfbank.bayanati.contactchannels.service.ContactChannelsService`)
sends each challenged channel's OTP straight through `MessageSender` with
`Urgency.INTERACTIVE`, synchronously, before its database transaction opens — **never** through
`app.notification_outbox`, confirming §5.1's own design note in practice: "an OTP is sent
inline from the request thread by the OTP service, a notification is sent by the outbox
dispatcher." The S3-06 task text asked for an outbox row per OTP; this was not built, because
nothing drains the outbox yet (the scheduled dispatcher is still out of scope) and an OTP the
customer is actively waiting on cannot wait for one. See
`docs/sessions/2026-08-29-s3-06-stage-1b.md` for the full reasoning and the live proof that
`billedSegments` for a real Arabic OTP SMS is `2`, matching this card's own §4.1 estimate.
Decision: **AD-002c is OPEN** for provider selection. The port design recorded on this card is
**CLOSED** — see the AD-002c (port) row in PROJECT_PLAN.md's decisions log. This card records
the proposed design and what is known versus assumed, so the assumptions are attackable when
the first adapter is written.

## S3-05 — what exists now

`MessageSender`, the sealed `MessagePayload`, `Urgency`, `MessageDispatchResult` and
`StubMessageSender` are implemented exactly as designed below, in
`com.sfbank.bayanati.messaging` (`domain`/`service`/`config`/`stub`). `app.notification_outbox`
is migration V0035, one row per channel, with result columns that line up 1:1 with
`MessageDispatchResult` so a dispatcher writes a result back with a plain UPDATE, no mapper.
`com.sfbank.bayanati.notification` (`domain`/`service`/`jdbc`) claims one pending row, sends
it through `MessageSender`, records the result on the row, and writes a `notification_dispatched`
audit event on the profile's chain — proven live end to end
(`NotificationOutboxIntegrationTest`). **Still not built, deliberately:** any concrete adapter —
this task proves the shape with one manual dispatch, per its explicit scope. The scheduled
dispatcher loop it also left out was built at S6-04, below. See
`docs/sessions/2026-08-29-s3-05-messaging-port-and-outbox.md`.

## S6-04 — the queue actually moves

Until 2026-09-04 **nothing in the codebase called `OutboxDispatcher.dispatchOnePending()`**, so
every `DEFERRED` message the journey enqueued — the submission notification, and every
approve/reject/manual-completion status transition — was written to `app.notification_outbox` and
never sent. `notification.scheduler.OutboxDispatchScheduler` is the timer that drains it, on a
`fixedDelay` of 30 s (`fru.notification.outbox.poll-interval`, a property with that default): the
same figure as the claim lease in `JdbcNotificationOutboxRepository.CLAIM_ONE_PENDING`, since a
row left `pending` by a TRANSIENT_FAILURE cannot come due sooner than its lease anyway. Each tick
calls `OutboxDispatcher.drainPending()`, which loops until the claim comes back empty or 50
attempts have been made, isolating and counting each attempt's failure so one poison row neither
crashes the tick nor blocks the rows behind it. **No retry-backoff schedule and no dead-letter
state** — the lease is the retry, and anything more belongs with the real provider adapter, which
still does not exist. Proven live: one tick, four rows enqueued (three accepted, one rejected by
the stub), all four resolved and audited. See
`docs/sessions/2026-09-04-s6-04-outbox-dispatcher.md`.

## The delivery-model position

We are a vendor building for delivery to a bank. **We hold no messaging account and will
hold none before delivery.** Every provider is stubbed behind one port; configuration swaps
stub for real at delivery. Nothing in this card licenses writing a concrete adapter against
documentation alone — CLAUDE.md's `@agent-researcher`-before-integration rule bites the
moment real HTTP appears.

## The port (proposed, not written)

- `com.sfbank.bayanati.messaging.domain.MessageSender` — one method,
  `MessageDispatchResult send(OutboundMessage)`, plus `Set<MessageChannel> supportedChannels()`.
- `ChannelRoutingMessageSender` (in `…messaging.service`) implements the port and delegates by
  channel, so callers depend on exactly one type.
- Selected at startup by `fru.messaging.{sms,whatsapp,email}.provider`. **No default** — an
  unset, empty or unrecognised value fails startup, per the `CoreBankingClientConfiguration`
  precedent (S3-01).
- `fru.messaging.{channel}.enabled` declares whether a deployment can verify that channel at
  all. Stage 1b must read it. **[Journey gap — customer.md assumes all three are always offered;
  see R-042.]**

## Hard constraints on the shape

| Constraint | Source | Marker |
|---|---|---|
| `MessageChannel` wire values are exactly `sms`, `whatsapp`, `email` | `CHECK` on `app.profile_channel.channel` (V0007) and `app.otp_challenge.channel` (V0021) | [OBSERVED] |
| `MessageDispatchResult` must be a **flat** record of String/int/long/boolean only | `payload_json` is RFC 8785 canonical JSON via `audit.domain.CanonicalJson`, which rejects nested objects | [OBSERVED docs/components/persistence.md] |
| A provider rejection is a **returned value**, never an exception | The audit trail must record the provider's own words; the dispatcher must act on them | Design decision, AD-002c |
| Adapters **never** retry internally | Retry belongs to the AD-005 outbox (`next_attempt_at`) and to the customer's resend control (30s/60s/120s, cap 3) | [OBSERVED customer.md; AD-005 §6A] |
| The port never carries or returns an OTP code | The code lives only in `app.otp_challenge`; see the FIB defect below | Design decision, AD-002c |
| Provider status is stored **both** mapped (`DeliveryState`) and raw | Same principle as storing the raw Uqudo JWS and raw Civil Registry response | [OBSERVED customer.md audit principles] |

## What is known about Sudan

| Item | Status | Source |
|---|---|---|
| Arabic SMS is UCS-2: **70 chars single, 67 per concatenated segment** | [DOC] | twilio.com/docs/glossary/what-sms-character-limit, 2026-08-29 |
| A realistic Arabic OTP body is **2 segments**; a rejection notice **3** | [OBSERVED anchor] | FIB's 104-char bilingual body, `../FIB/.../mapper/MessageMapper.java:31` |
| Twilio's published Sudan rate | **$0.4749 per segment** [DOC] | twilio.com/en-us/sms/pricing/sd, 2026-08-29 |
| Campaign SMS volume ≈ **410,000 messages ≈ 900,000 segments** | [UNVERIFIED estimate] | AD-002c §4.1, derived from customer.md + the 100k-account scale |
| Campaign SMS cost, international vs domestic | **~$427,000 vs ~$5,000–$18,000** | [DOC rate] × [UNVERIFIED estimate] |
| **MTN Sudan and Sudani One reject numeric sender IDs**; alphanumeric pre-registration required, ~3 weeks | [DOC] | twilio.com/en-us/guidelines/sd/sms, 2026-08-29 |
| Sudan: no domestic long code, no short code, no two-way SMS | [DOC] | Same |
| **Sudan is NOT on Meta's WhatsApp Business Platform exclusion list** | [DOC] | developers.facebook.com/docs/whatsapp/cloud-api/support/, 2026-08-29 |
| Sudan is not on Meta's authentication-international list (no surcharge) | [DOC] | developers.facebook.com/…/authentication-international-rates/, 2026-08-29 |
| Sudan (+249) falls to Meta's **"Other"** pricing tier; rate itself | [UNVERIFIED] | Rate-card CSV, effective 2026-07-01, linked from Meta's pricing doc |
| **All** our WhatsApp traffic is charged — every send is outside a customer-service window | [DOC] | developers.facebook.com/…/whatsapp/pricing, 2026-08-29 |
| AUTHENTICATION templates are **mandatory** for OTP, with **fixed non-customisable body text**, no URLs/media/emojis, params ≤15 chars | [DOC] | Meta authentication-templates docs, 2026-08-29 |
| Meta business verification / display name / template review durations | **[UNVERIFIED — no published SLA]** | Looked; none exists |
| Meta rejects a **bank account located in Sudan** for Monetization Manager; inference to WhatsApp billing | [DOC-adjacent] / **[UNVERIFIED]** | facebook.com/business/help/103628146695524 |
| Twilio does not accept payment from OFAC-limited countries; Sudan specifically | secondary / **[UNVERIFIED]** | Twilio support 223183268 (403 on direct fetch) |
| **EU** Sudan measures are targeted only (18 individuals, 8 entities), applicable since 2023-10-12 | [DOC] | Council Reg (EU) 2023/2147; Consilium releases to 2026-01-29 |
| **US** Sudan measures are list-based, plus CBW Act measures aimed at the **Government of Sudan** (determination 2026-04-24; further measures 2026-06-23) | **[UNVERIFIED — primary text not retrievable]** | OFAC Sudan programme page; FR 2026-14568 |

## Precedent from `../FIB` — read-only, not authoritative

- **FIB uses no third-party messaging provider.** OTPs go to the *bank's own* `/SMSMessage`
  and `/EmailMessage` endpoints with a partner bearer token
  [OBSERVED `../FIB/backend server fib/utility/src/main/java/com/aztech/utility/service/Impl/BankServiceImpl.java:256-288`].
- Request shape `{CIF, Message, Phone, Email, Subject}`; SMS response
  `{cif, phone, responseCode, message, responseStatus}` with **string** codes
  [OBSERVED `.../request/FibMessageRequest.java`, `.../response/SMSMessageResponse.java`].
- Per-channel on/off is already an integer config toggle (`bank.sendEmail`, `bank.sendMessage`)
  [OBSERVED `.../config/BankReaderConfig.java`]. Credential values for `partnerId`/`partnerSecret`
  are present in `.../src/main/resources/application.yml` — **key names only; never reproduce a value.**
- **FIB has no WhatsApp integration of any kind** [OBSERVED: repo-wide case-insensitive
  ripgrep for `whatsapp` returns no matches]. No precedent to inherit.
- **FIB has no delivery receipts.** `isSent` is set on any 2xx — i.e. it records acceptance and
  calls it delivery. Our journey asks for a delivery result, which is stricter.

## DO NOT COPY — a real defect in the reference

`BankServiceImpl.sendOtp` returns the generated OTP to the client as `reqId`
(`result.setReqId(genOTP)`), and `otpVerification` then compares the client-supplied `reqId`
with the client-supplied `verCode` — **so the code never has to be delivered for verification
to succeed** [OBSERVED `BankServiceImpl.java:232,266,317-340`; payload contract confirmed at
`../FIB/docs/research/2026-06-14-otp-flow-review.md` §2]. Our code lives only in
`app.otp_challenge`, is never returned by any endpoint, and never appears in the port's
signature. This is why `MessageSender` has no verify method and no code field.

## Ruled out

- **Provider-hosted OTP products** (Twilio Verify, Infobip 2FA, AWS verification). They own the
  code, which would move OTP state out of `app.otp_challenge`, break the per-channel
  verified/declined/unverified model, and put the code-issued and verification-outcome audit
  events outside our hash chain. We generate, store and validate; the provider transports.
- **A US-domiciled aggregator as the primary SMS route.** $427,410 at published rates, plus an
  account-eligibility exposure we would be carrying on the bank's behalf.

## Open items before any adapter is written

- [ ] **OQ-016** — does the bank have an SMS gateway, and what is its contract, including
      whether it reports delivery or only acceptance? (worth ~$400k)
- [ ] **OQ-018** — is the bank state-owned? (changes every compliance answer)
- [ ] **OQ-017** — is an alphanumeric sender ID registered with TPRA, Zain, MTN and Sudani? (~3 weeks)
- [ ] Does the bank already have a Meta Business portfolio, a verified business, or an existing
      WhatsApp presence? Which legal business identity, and can the bank supply an official
      document showing legal name and physical address in a form Meta accepts? (not yet assigned
      an OQ number)
- [ ] **OQ-019** — in whose name is each messaging account held, and who pays? (the WABA
      specifically must be the bank's, not a solution provider's)
- [ ] **OQ-020** — can the bank settle with a foreign supplier at all?
- [ ] **OQ-022** — which domain sends customer email, and who controls its DNS for SPF/DKIM/DMARC?
- [ ] **OQ-021** — has compliance/legal reviewed sending customer PII through a foreign messaging
      provider? Related to OQ-001 and OQ-011.
- [ ] Meta rate-card CSV (effective 2026-07-01) — the "Other"-tier authentication and utility rates
- [x] `app.notification_outbox` — built at S3-05 (V0035)

Full evidence, options analysis, cost model and sourcing: see
`docs/sessions/2026-08-29-research-ad-002c-messaging.md`.
