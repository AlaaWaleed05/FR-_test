# Research — Airtel Sudan SMS: latency, throughput and rate limits

**Date:** 2026-09-18 · S9-08 · research only, no code written from this report
**Question:** what per-message cost L should we assume for SMS through Airtel, so that the
measured ceiling `30000 / (P + 50L)` submissions per hour becomes a number we can guarantee to
Sudanese French Bank?

---

## Answer

**L = 2.1 s. Ceiling ≈ 215–225 submissions/hour at P = 30 s, ≈ 276/hour at P = 5 s.**

This is not an industry estimate. It comes from two real sends through the real gateway.

---

## 1. The measurement we already owned

`docs/sessions/2026-09-07-sms-live-verify.md` (S8-02), two live sends from the deployed stack to
a real Sudanese handset, read back out of `audit.audit_event`:

```
11:12:01 | sms | ACCEPTED | airtel | apiMsgId 4526963 | billed_segments 2 | latency_ms 1969
11:49:40 | sms | ACCEPTED | airtel | apiMsgId 4528435 | billed_segments 2 | latency_ms 2144
```

`latency_ms` is `AirtelSmsSender`'s own `System.nanoTime()` bracket around the whole `send()` —
destination conversion, URI build, HTTP exchange, parse. It is exactly "the gateway's per-message
latency" as the dispatcher pays it, and it does **not** include the dispatcher's own database work,
which S9-08 measured separately at 15.8 ms. **No double counting.**

These are the only two Airtel latency figures that exist anywhere in this project.

**Confidence: [OBSERVED], n = 2.** The strongest marker available here, and still two samples.

**Caveat that matters.** The sends were 37 minutes apart, so each almost certainly paid a cold
TCP+TLS handshake. Frankfurt↔Khartoum RTT is on the order of 150 ms, so the handshake could be
~0.45 s of the ~2.0 s. During a sustained drain the connection pool would be warm and true L might
be ~1.5 s. **[UNVERIFIED]** — but it means these two samples are plausibly an *upper* bound, and
that hosting inside Sudan buys less than it looks: the gateway's own processing, not the distance,
is most of the two seconds.

## 2. Who "Airtel" is — not who the name suggests

The provider is **Airtel Technology Co. Ltd**, a Sudanese bulk-SMS aggregator founded 2018,
Albaraka Tower, Khartoum. `airteltech.net` and `airtel.sd` are the same company, which closes the
question of whether our configured endpoint host is this vendor's own.

**It has no relationship to Bharti Airtel or Airtel Africa, neither of which operates in Sudan.**
The Sudanese MNOs are Zain, MTN and Sudani. Anything found under `airtel.in`, `airtel.africa` or
"Airtel IQ" is a different company. This is recorded loudly because the first page of results for
"Airtel SMS API" is Bharti Airtel's Indian product, and a future session will otherwise quote
Indian throughput figures at a Sudanese bank.

## 3. What the vendor publishes — the complete list

One two-page PDF: `airteltech.net/wp-content/uploads/2023/02/AirTel-API.pdf`, documenting
`bulksms/webacc.aspx` — send and balance, `Nums` semicolon-separated, max 100 per request,
responses `Ok` / `Invalid` / `Rejected`.

**Absent, and each was looked for specifically:** no rate limit, no TPS, no messages-per-second,
no concurrent-connection limit, no latency or response-time figure, no SLA, no delivery reports,
no retry guidance. The pricing page publishes no figures and directs you to email.

**[UNKNOWN] is the correct answer for every throughput question, and it is an evidenced absence.**

**Two generations of API on one host.** The published endpoint is `webacc.aspx`
(`user`/`pwd`/`Sender`/`smstext`/`Nums`, answers `Ok`). We call `/api/html_send_sms/`
(`username`/`password`/`sender`/`message`/`phone_number`, answers `Status:` / `Total Units:` /
`apiMsgId`). **Our endpoint is undocumented publicly**, so the only contract we hold for it is
`AirtelSmsResponseParser`'s Postman capture of 2026-09-07. That is a standing risk with no vendor
document to check against.

## 4. The batch endpoint: investigated and rejected

A batch endpoint **exists** (100 recipients per call), and our own endpoint's reply grammar is
multi-recipient, so it likely accepts several numbers too.

**It cannot help us, structurally rather than for want of effort.** Both endpoints take **one
message body for many recipients**. Every message this system sends is per-customer: a distinct
OTP at Stage 1b, a distinct `SFB-` reference on every status transition. There is no traffic class
here where two customers receive byte-identical text, so a 100-recipient batch would carry one.

Three further reasons even if a shared-body case appeared: the documented batch returns only `Ok`,
destroying `providerMessageId` and `billedSegments` (the figure that makes the Arabic cost
multiplier measurable); `app.notification_outbox` is one row per recipient with per-row result
columns that a batch call has nothing to write back to; and one `Rejected` for 100 recipients
attributes failure to nobody.

**Recorded as investigated and rejected so a later session does not rediscover the recipient cap
and reach the opposite conclusion.**

## 5. The ceiling, and what each lever buys

Formula: `ceiling = 30000 / (P + 50L)` submissions/hour. Sanity check: at S9-08's 15.8 ms
dispatcher floor it gives 974/hour, matching the harness's own 971.

| L | Basis | Ceiling at P = 30 s |
|---|---|---|
| 0.016 s | dispatcher floor, stub sender | 974/hour |
| 1.52 s | if warm-connection reuse removes the handshake **[UNVERIFIED]** | 286/hour |
| **2.08 s** | **mean of two real sends + 15.8 ms [OBSERVED]** | **224/hour** |
| 2.16 s | worst observed + 15.8 ms [OBSERVED] | 217/hour |
| 4.0 s | loaded gateway [UNVERIFIED] | 130/hour |
| 15.0 s | every send timing out | 40/hour |

| Change | Ceiling | Verdict |
|---|---|---|
| nothing | 224/hour | — |
| P: 30 → 5 s | 276/hour | **+23%. Taken at S9-08.** Also cuts notification delay ~46 s → ~8 s |
| 4 concurrent sends | 481/hour | needs Airtel's rate limit known first |
| 8 concurrent sends | 698/hour | ditto |
| 8 concurrent **and** P = 5 s | ~1,670/hour | what it would take to make a 1,500/hour claim true |

**Do not raise concurrency before asking.** Eight parallel synchronous GETs from one account
against a small aggregator is the shape of request that gets an account throttled, and a throttled
OTP route is a Stage 2 outage for every customer.

**One inference, marked and not to be sized on. [UNVERIFIED]** The two `apiMsgId` values differ by
1,472 across 37m39s ≈ 0.65 ids/second, and our account contributed 2 of those — so the counter is
demonstrably not per-account. If it is a global sequential send counter, the whole platform was
averaging under one message per second on a business afternoon. That is **not** a rate limit and
must never be published as one. It is a prior: this is a small domestic aggregator, not a platform
built for burst TPS, and a ~2 s synchronous response is consistent with per-message submission to
an operator SMSC rather than accept-and-queue. Two messages a known interval apart would settle it.

## 6. Industry norms, for contrast only — none of this is Airtel

Twilio's submit-accept latency is p50 114 ms. **Ours is ~18× that.** Anyone reaching for "SMS APIs
respond in ~100 ms" as a planning figure overstates our ceiling by a factor of twenty. Tier-1 CPaaS
numbers are not transferable to this route.

## 7. What could not be determined

- Airtel's rate limit, TPS cap, or concurrent-connection allowance. Nothing published anywhere.
- Any published latency or SLA from Airtel, TPRA, Zain, MTN or Sudani.
- How the ~2 s decomposes between TLS, network and gateway processing. **This is the highest-value
  cheap measurement available**, because it decides whether sustained L is ~1.5 s or ~2.1 s — a 30%
  swing in the published ceiling.
- Whether `/api/html_send_sms/` accepts multiple numbers. Moot per §4, but factually open.
- Whether our sender ID `SFB` is registered with TPRA or the operators (OQ-017, open). One
  confirmed Sudani interconnect delivery at S8-02 is not a volume guarantee.
- What happens to L, or to the account, above some unknown submission rate. We have never sent two
  messages closer together than 37 minutes.

None of these has been filled with a plausible figure.

## 8. Questions to put to Airtel Technology

Ordered so the first three are the ones that change the published number.

1. **What is the maximum sending rate on our account** through `/api/html_send_sms/` — per second,
   minute or hour? Is any limit enforced, and **what does the gateway return when it is exceeded?**
   (We must be able to tell a throttle from an outage: our adapter classifies any unrecognised body
   as a transient failure, so a throttle we cannot recognise gets retried, making it worse.)
2. **How many simultaneous requests may one account have in flight?** We send strictly one at a
   time. If we send 4, or 8, is that acceptable, and does it change the answer to Q1?
3. **What response time should we expect, and what does the gateway do before it responds** —
   return once queued, or once submitted to the operator's SMSC? We measure ~2 s from outside
   Sudan and need to know how much of that is yours. Is there a busy-hour figure?
4. **Is `/api/html_send_sms/` documented anywhere?** The public PDF documents a different endpoint.
   Please send the current document, including every `Status:` value and every `FAILED:` reason.
   **Specifically: what is returned for a wrong username or password?** We have never captured it
   and deliberately refuse to guess.
5. Does `phone_number` accept more than one number? Separator, maximum, one `apiMsgId` each?
6. **Any delivery-receipt facility** — callback, status query, portal report? customer.md asks for
   a delivery result; we record acceptance only. (OQ-016, open.)
7. **Is sender ID `SFB` registered** with TPRA, Zain, MTN and Sudani? If not, at what monthly volume
   does an unregistered alphanumeric sender start being filtered or rewritten? (OQ-017.)
8. Monthly volume commitment or minimum? Does exceeding a package throttle, or only re-bill? Our
   campaign averages ~25,000 SMS/month over 12–18 months, with a bank-driven batch spike.
9. Maintenance window, and what the gateway returns during it?

**BR-13 in the hosting document already asks the bank for exactly this.** Questions 1–3 are the
specific form BR-13 should take. Separately, ask the bank's telecom contact whether Airtel
Technology is the contracted aggregator of record and whether any written SLA covers throughput —
if one exists, it supersedes this report.

## 9. Risks if this recommendation is wrong

| If | What breaks | Reversibility |
|---|---|---|
| **L is higher than 2.1 s under sustained load** (likeliest — two cold samples at low load) | At L = 4 s the ceiling is 130/hour, **below the 200/hour the sizing commits to**. Campaign batches queue; OTPs arrive after their 5-minute expiry | Cheap in code, expensive in credibility — republishing a *lower* capacity to a bank after signature is the worst version |
| **We publish 215 and it is really 286** | We undersell | Free |
| **Concurrency raised to 8 without asking** | Account throttled; every OTP-gated stage stops | Hours to days of vendor negotiation with the product down |
| **The parser's captured contract drifts** (no vendor document to check against) | Changed grammar → `UNRECOGNISED` → `TRANSIENT_FAILURE` → queue backs up behind retries and the ceiling collapses. Fails closed, but only ERROR logs say so | Low code cost; depends on somebody watching the log |

**The recommendation flips if:** a sustained measurement shows warm L ≤ 1.5 s (publish ~285/hour);
or Airtel confirms concurrency ≥ 4 (publish 480/hour+); or the bank moves to a direct operator SMPP
bind, which changes the model entirely — `submit_sm` is asynchronous and windowed, so `50L` stops
being the governing term and the formula needs rederiving.
