# AD-002c — Outbound messaging providers for Sudan (SMS, WhatsApp, email) and the backend port

**Session:** research · **Date:** 2026-08-29 · **Decision ID:** AD-002c
**Status of this document:** research report. It proposes; it does not settle. AD-002c stays OPEN in PROJECT_PLAN.md until the bank answers §7.

---

## 0. Reading taken, and readings not pursued

The task is ambiguous in one place: "which providers should this project integrate" could mean *which vendor do we sign with*, or *which vendor do we design the adapter against*. I took the second reading, because the delivery model in the task and the precedent already set by S3-01 (`CoreBankingClient` + stub + configuration selector, no live integration) make the first reading impossible for us to answer — we cannot open an account, and the account is not ours to open. **The deliverable is therefore an interface design plus a ranked, costed provider recommendation the bank can act on, and a stub.**

Readings not pursued:
- *"Pick a vendor and integrate now."* Cannot be done under the delivery model; would also breach CLAUDE.md's `@agent-researcher`-before-integration rule in reverse (we would be integrating against documentation alone, with no account and no logged response).
- *"Design the outbox and the dispatcher."* Explicitly out of scope — settled by AD-005.
- *"Write the message copy."* BL-005.

---

## 1. Question

Which outbound messaging providers should this project integrate for SMS, WhatsApp and email to customers in Sudan — and what single interface should the backend expose so that the provider is a configuration choice rather than a code change?

---

## 2. Answer

**Three recommendations, one per channel, plus the port.**

1. **SMS — recommend the bank's own SMS gateway as the primary target, reached through a generic HTTP adapter behind our port.** This is the FIB precedent, observed in real source: FIB sends every OTP through the bank's own `/SMSMessage` endpoint, authenticated with a partner token, and uses no third-party CPaaS at all [OBSERVED `../FIB/backend server fib/utility/src/main/java/com/aztech/utility/service/Impl/BankServiceImpl.java` lines 256–288]. Under our constraints this is not merely convenient, it is the only option whose cost is defensible: at Twilio's published Sudan rate the campaign's SMS bill is **~$427,000**; on a domestic route it is **~$5,000–$30,000** — a factor of 15–90 (§4.1). It is also the only option with no sanctions or account-eligibility exposure whatsoever, because the account already exists, is already the bank's, and is already paid for in SDG.

2. **WhatsApp — recommend the Meta WhatsApp Cloud API, with the WhatsApp Business Account (WABA) owned by the bank and onboarded through a Business Solution Provider using partner-led business verification; and recommend shipping v1 with the WhatsApp adapter configured to the stub.** Sudan is *not* on Meta's excluded-country list for the WhatsApp Business Platform [DOC], so the platform is legally and technically open to a Sudanese business — but the billing rail is the problem, not the platform, and the verification chain (business verification → display name → template approval) is a multi-week, bank-owned process we cannot start (§6). WhatsApp is the *cheapest* channel per message and carries no Arabic segmentation penalty; it deserves to be built, just not blocked on.

3. **Email — recommend the bank's own SMTP relay via `JavaMailSender`, with a non-US ESP as the named fallback.** Email is optional throughout the journey, carries ~1.4 messages per account, and costs approximately nothing on any option. Spending sanctions-review effort on it is the wrong allocation. The real work is DNS (SPF/DKIM/DMARC on the bank's sending domain), which is a bank action regardless of provider.

4. **The port — one interface, `MessageSender`, with a sealed per-channel payload, an `Urgency` value distinguishing interactive OTP from deferred notification, and a flat result record shaped to survive RFC 8785 canonicalisation into the audit trail.** Full sketch in §5. Ship the stub only at this stage; write the concrete adapter when the bank supplies its gateway specification.

**The single most decision-relevant fact in this report:** the SMS routing choice is worth roughly **$400,000** across the campaign, and it is a commercial decision belonging to the bank, not a library choice belonging to us. Everything else in AD-002c is secondary to it.

---

## 3. Evidence

### 3.1 What the reference implementation actually does

[OBSERVED] `../FIB/backend server fib/utility/src/main/java/com/aztech/utility/service/Impl/BankServiceImpl.java`, method `sendOtp` (lines 197–295): the OTP is generated locally with `SecureRandom`, persisted to a `Message` entity, then posted to **two endpoints on the bank's own API** — `{bank.url}/EmailMessage` and `{bank.url}/SMSMessage` — carrying an `Authorization: Bearer` token obtained from the same bank's `/Login` partner authentication. **There is no Twilio, no Infobip, no SendGrid, no SMTP client, and no third-party messaging dependency anywhere in the FIB backend.**

[OBSERVED] `../FIB/.../config/BankReaderConfig.java`: `@ConfigurationProperties(prefix = "bank")` binding `url`, `partnerId`, `partnerSecret`, `imageLocation`, `separator`, `ttl`, `sendEmail`, `sendMessage`. The last two are **integer feature toggles** — `if (bankReaderConfig.getSendEmail() == 1)` and `if (bankReaderConfig.getSendMessage() == 1)` — i.e. FIB already treats each channel as independently switchable by configuration. Credential values for `partnerId`/`partnerSecret` are supplied from `../FIB/.../src/main/resources/application.yml`. **Values present; not read, not reproduced here, and not to be reproduced anywhere.**

[OBSERVED] `../FIB/.../request/FibMessageRequest.java`: one request object serves both channels — `{ CIF, Message, Phone, Email, Subject }`. `Subject` is meaningful only for email; `Phone` only for SMS. The gateway is a simple JSON POST.

[OBSERVED] `../FIB/.../response/SMSMessageResponse.java`: `{ cif, phone, responseCode, message, responseStatus }`, with a sample response preserved in a source comment showing `"responseCode": "0"`, `"responseStatus": "success"`. `EmailMessageResponse` is its sibling. **The response codes are strings, not integers, and the success test in `BankServiceImpl` is `responseStatus.equals("success") && responseCode.equals("0")` — two fields, both raw provider values.** This is the shape a generic HTTP adapter has to accommodate.

[OBSERVED] `../FIB/.../mapper/MessageMapper.java` line 31: the OTP body is a single bilingual string — `"Dear Sir/Mis,  Your OTP Code is: %s Thanks!.  عزيزنا العميل رقم التحقق لمرة واحدة الخاص بك هو %s"`. Approximately 104 characters. Because it contains Arabic it is UCS-2 in its entirety, so at 67 characters per concatenated segment it is **two segments**. This is a real observed anchor for the segment-count estimate in §4.1, not a guess.

[OBSERVED] `../FIB/.../domain/Message.java`: persists `isSent` / `isSentMail` booleans plus raw `messageResponse` / `emailResponse` strings. `isSent` is set on any 2xx with a body — i.e. **FIB records "the gateway accepted it" and calls it "sent"**. There is no delivery receipt anywhere in FIB. Our journey requires a *delivery result* per channel in the audit trail (customer.md, "Notification dispatched per channel, with delivery result"), which is a stricter requirement than FIB meets.

[OBSERVED] Repo-wide case-insensitive ripgrep for `whatsapp` across `../FIB` returned **no matches**. FIB has no WhatsApp integration of any kind. There is no precedent to inherit for this channel.

[OBSERVED — do not copy] `../FIB/.../BankServiceImpl.java` line 232/266: `result.setReqId(genOTP)` — **the generated OTP is returned to the client in the `reqId` field of the `/SendOTP` response.** `otpVerification` (lines 317–340) then verifies by comparing `request.getReqId()` against `request.getVerCode()`, both of which are supplied by the client [OBSERVED: the payload contract is confirmed independently from the recovered web sources in `../FIB/docs/research/2026-06-14-otp-flow-review.md` §2, `{ email, phone, datetime, ReqId, ver_code }`]. **The code never has to be delivered for verification to succeed.** Our design keeps the code exclusively in `app.otp_challenge` server-side and the port never returns it; this is stated here because it constrains the port's signature, not merely as a criticism of the reference.

### 3.2 SMS — the Sudan facts that decide it

[DOC — twilio.com/en-us/guidelines/sd/sms, fetched 2026-08-29] Sudan SMS guidelines:
- Alphanumeric sender ID: **supported, pre-registration required, ~3 weeks provisioning**. "Promotional content is not allowed to be registered."
- Domestic long code: **not supported**. International long code: supported, but **sender ID not preserved**. Short code: **not supported**.
- **Two-way SMS: not supported.**
- UCS-2 encoding: supported.
- **"MTN Sudan and Sudani One networks require alphanumeric pre-registration; numeric sender IDs will fail delivery on these networks."**

That last line is the deliverability finding. Roughly half the Sudanese market sits on MTN plus Sudani [UNVERIFIED market-share split, see below], and on those networks an unregistered numeric sender **does not deliver at all**. A "just point it at an international aggregator and go" plan silently loses half the customer base at stage 2, which is the exact point the journey gates on.

[DOC — twilio.com/en-us/sms/pricing/sd, fetched 2026-08-29] Outbound SMS to Sudan: **$0.4749 per message segment (USD)**, the same rate for international numbers and for alphanumeric sender IDs. The page carries the standard "Prices may change from time to time without notice and additional carrier fees may apply."

[DOC — twilio.com/docs/glossary/what-sms-character-limit, fetched 2026-08-29] GSM-7: 160 single / 153 per concatenated segment. **UCS-2: 70 single / 67 per concatenated segment / 700 maximum.** A single Arabic character forces the *entire* message to UCS-2 — there is no partial saving from mixing scripts, which is why FIB's bilingual message costs two segments rather than one.

[secondary — infobip.com/docs/essentials/getting-started/sms-coverage-and-connectivity, rendered 2026-08-29] Infobip states worldwide SMS coverage for self-signup users with limited exceptions that do not include Sudan. **I could not extract the exclusion list verbatim from the page** — the fetched rendering was truncated before Sudan alphabetically and contained no self-signup exclusion list. Treat "Infobip covers Sudan" as **[UNVERIFIED]** pending a per-network quote from the Infobip portal, which is where their documentation says the real coverage and pricing live.

[UNVERIFIED — secondary, sent.dm 2025 guide] Sudan operator market shares: Zain ~50%, MTN ~30%, Sudani ~20%; operators typically require 10,000–50,000 messages/month minimum for a direct business account; Sender ID registration with the **Telecommunications and Post Regulatory Authority (TPRA)** is required to send A2P SMS legally in Sudan. Every element of this paragraph is from a commercial secondary source, none of it from a primary regulator or operator publication, and it should be confirmed by the bank's own telecom relationships before anyone relies on it. **The authoritative sources would be TPRA's own published A2P rules and the three operators' enterprise-messaging contracts, which the bank plausibly already holds.**

### 3.3 WhatsApp — the Meta facts

[DOC — developers.facebook.com/docs/whatsapp/cloud-api/support/, fetched 2026-08-29] Verbatim:
> "Businesses in Cuba, Iran, North Korea, Syria, and three sanctioned regions in Ukraine (Crimea, Donetsk, Luhansk) are not eligible to use the WhatsApp Business Platform."
> "WhatsApp Messenger (WhatsApp) and WhatsApp Business app users in Cuba, Iran, North Korea, Syria, and three sanctioned regions in Ukraine (Crimea, Donetsk, Luhansk) are not eligible to receive messages sent via the WhatsApp Business Platform."

**Sudan appears nowhere on either list.** A Sudanese business is eligible to hold a WABA, and Sudanese WhatsApp users are eligible to receive platform messages. The page carries no overall last-updated date; the most recent dated statement on it is "As of May 15, 2024, Türkiye is no longer restricted."

[DOC — developers.facebook.com/documentation/business-messaging/whatsapp/pricing, fetched 2026-08-29] Per-message pricing, charged on delivery of a template message. **Utility templates are free inside an open customer-service window; authentication templates are charged when sent outside one.** Every message this journey sends is outside a customer-service window — the customer never messages the bank first — so **all of our WhatsApp traffic is charged**. Rates vary by the recipient's country calling code, published as downloadable rate-card CSV/PDF files effective **2026-07-01**.

[DOC — same page] **Sudan (+249) is not separately listed in the country-to-region mapping; it falls to the "Other" bucket.** The exact "Other" USD rate is **[UNVERIFIED]** — I could not extract figures from the rate-card files. **The authoritative source is the rate-card CSV linked from that pricing page, effective 2026-07-01.** Get it before committing to a WhatsApp budget line.

[DOC — developers.facebook.com/documentation/business-messaging/whatsapp/pricing/authentication-international-rates/, fetched 2026-08-29] The authentication-international surcharge applies to exactly nine countries: Egypt, India, Indonesia, Malaysia, Nigeria, Pakistan, Saudi Arabia, South Africa, UAE. **Sudan is not among them** — so a foreign-domiciled sender messaging Sudanese numbers does not pay that surcharge. Businesses deemed eligible get 30 days' notice before it applies.

[DOC — developers.facebook.com/documentation/business-messaging/whatsapp/templates/authentication-templates/…, via search 2026-08-29] Authentication templates:
- Mandatory for OTP/verification codes delivered over WhatsApp: *"If your mobile app offers users the option to receive one-time passwords or verification codes via WhatsApp, you must use an authentication template."*
- Body text is **fixed, non-customisable preset text**, plus an optional security disclaimer, an optional expiration warning, and one of: one-tap autofill button, copy-code button, or no button (zero-tap).
- **URLs, media and emojis are not permitted** in authentication template content or parameters; **parameters are limited to 15 characters**.
- Effective **2026-06-15**, keyboard suggestions are enabled by default for all authentication templates; on iOS 26+, the OTP is detected in the push notification and offered as one-tap autofill with no integration change.

[DOC — developers.facebook.com / facebook.com/business/help, via search 2026-08-29] The onboarding chain is: connect the app to a Meta Business portfolio → **business verification** (upload an official document, PDF/JPG/JPEG/PNG, showing the business's legal name and physical address) → **display-name review**, initiated automatically once business verification completes → **per-template review**. An Official Business Account additionally requires 30 days on the platform, a verified portfolio, and two-step verification on the number. **Meta publishes no committed turnaround time for business verification or display-name review** — I looked and found none; the only evidence of duration is a developer-community thread titled "WhatsApp Business Display Name Pending Review for months", which is anecdote, not a service level. Treat the calendar time as **[UNVERIFIED]** and plan a range, not a date. **Partner-led business verification** exists specifically so a Solution Partner can submit a business's verification on its behalf — this is the mechanism that matters for a Sudanese bank (§6).

### 3.4 Sanctions and account eligibility

**United States.**

[secondary — summarised from OFAC and Federal Register indexes via search, 2026-08-29; the OFAC Sudan programme page itself timed out on fetch and the Federal Register document redirected to an access-block page] The current US Sudan programme is **list-based, not a comprehensive country embargo**: designations under the Sudan-related executive orders targeting the SAF, the RSF, and their procurement and financing networks, with fresh designations as recently as 2026-06-26. Separately, on **2026-04-24** the US determined that the **Government of Sudan** used chemical weapons, triggering **Chemical and Biological Weapons Control and Warfare Elimination Act of 1991** sanctions; on **2026-06-23** the Under Secretary of State decided to impose additional CBW Act measures. Those measures bite on the **Government of Sudan** and on national-security-sensitive exports (reviewed under a presumption of denial), not on ordinary commercial services to private Sudanese entities.

**I could not open either primary document.** [UNVERIFIED as to precise scope and current text.] **The authoritative sources are:** OFAC's Sudan and Darfur Sanctions programme page and the consolidated SDN list; the State Department's CBW Act determinations; and the Federal Register notices 2026-14568 and the 2018 revocation of 31 CFR Part 538. **This is a legal question and the bank's compliance function must answer it, not us.**

**The practical obstacle is de-risking, not the legal prohibition.** Two data points:

[secondary — Twilio support "Can I fund my Twilio project from an international billing address?", rendered via search 2026-08-29; the article itself returned HTTP 403 to direct fetch and its help.twilio.com mirror rendered empty] Twilio accepts cards from 230+ countries but **does not accept payments from countries limited by OFAC**, and Sudan is named in secondary renderings of that list alongside Iran, Cuba, Syria, North Korea and Crimea. **[UNVERIFIED as to Sudan specifically.]** The authoritative test is trivially cheap and should be run before anyone plans around Twilio: attempt account creation with a Sudanese billing address, or get Twilio compliance to confirm in writing.

[DOC-adjacent — facebook.com/business/help/103628146695524, rendered via search 2026-08-29] For Meta's **Monetization Manager**, a bank account located in Sudan is not accepted. This is a *payout* product, not WhatsApp billing, so the inference to WhatsApp Business Platform billing is **[UNVERIFIED]** — but it is a strong signal that Meta's payment rails treat Sudanese bank accounts as ineligible, and WhatsApp billing runs on the same Meta billing infrastructure. **The authoritative test:** attempt to add a payment method to a Meta Business portfolio with a Sudanese bank account or card, or ask a BSP whether they will bill the WABA on the bank's behalf in a currency the bank can pay.

**European Union.**

[DOC — Council Regulation (EU) 2023/2147 and Decision (CFSP) 2023/2135, applicable since **2023-10-12**; Consilium press releases 2024-01-22, 2024-12-16, 2025-07-18, 2026-01-29] The EU Sudan regime is **purely targeted**: asset freezes and a prohibition on making funds or economic resources available to **listed** persons and entities, plus travel bans. As of the most recent listing round it covers **18 individuals and 8 entities**. There is no comprehensive EU trade or services embargo on Sudan. **A Sudanese commercial bank that is not itself listed is not prohibited by EU law from buying messaging services from an EU provider.**

That asymmetry is materially useful and is the reason a non-US provider ranks above a US one in §4: **US-domiciled providers face the strictest exposure and apply the bluntest blanket policies; EU-domiciled providers face a narrow, named-entity regime.** It does not eliminate de-risking — the EU provider's own bank still has to accept the payment — but it moves the question from "categorically refused" to "commercially negotiable".

**The one thing that would change this whole section.** The backend's Java package root is `sd.gov.bank.fruserupdate`. If the client bank is **state-owned**, the CBW Act measures against the Government of Sudan become directly relevant and every provider's compliance answer gets worse. I am not going to assume either way. **This is question B1 in §7 and it must be answered before any provider conversation starts.**

### 3.5 What the journey and the existing schema impose

[OBSERVED `docs/journeys/customer.md`] Stage 1b/2: SMS and WhatsApp default-selected and independently deselectable, at least one phone channel required; email optional. **Three independent codes, one per channel.** Codes valid 5 minutes; resend at 30s/60s/120s, capped at **3 resends per channel per session**; 5 wrong attempts locks a channel. "The delivered message should name its own channel so a customer can tell which code belongs where."

[OBSERVED `docs/journeys/customer.md` Stage 12, `docs/journeys/operator.md` "Every status transition"] Every status transition without exception is communicated to **all verified channels** — not just approve/reject, but the in-flight ones too (`awaiting_registry`, `blocked_scan`, `blocked_liveness`, `abandoned`, `terminated_registry_mismatch`). Channels in `declined` or `unverified` receive nothing. Notification dispatch is fire-and-forget relative to the profile; **delivery results are recorded per channel in the audit trail**.

[OBSERVED `backend/src/main/resources/db/migration/V0007__app_channels_and_otp.sql` line 5, and `V0021__otp_challenge_channel_check.sql` line 7] `app.profile_channel.channel` and `app.otp_challenge.channel` both carry `CHECK (channel IN ('sms','whatsapp','email'))`. **Any Java enum in the port must serialise to exactly those three lowercase tokens.** There is no fourth channel and adding one is a migration.

[OBSERVED `docs/sessions/2026-08-22-research-ad-005-persistence.md` §6A] The outbox contract: one `app.notification_outbox` row per **verified** channel, inserted in the same transaction as the status update, the history row and the audit event; a dispatcher polls `WHERE state='pending' AND next_attempt_at <= clock_timestamp() ORDER BY next_attempt_at FOR UPDATE SKIP LOCKED LIMIT 50`; each attempt commits its own short transaction updating the outbox row **and** writing a `notification_dispatched` audit event carrying the per-channel delivery result.

[OBSERVED — no migration matches `outbox` or `notification` under `backend/src/main/resources/db/migration/`] **`app.notification_outbox` does not exist yet.** It is designed in the AD-005 report and unbuilt. AD-002c does not build it either (out of scope), but the port must be shaped so that the outbox row and the port's result record line up without a translation layer.

[OBSERVED `docs/components/persistence.md`, "How application code writes an audit event"] **`payload_json` is RFC 8785 canonical JSON built by `audit.domain.CanonicalJson`, which supports flat objects with `String`/integral/`Boolean`/`null` values and rejects everything else.** This is a hard constraint on the result record's shape: no nested objects, no `Instant`, no `Duration`, no enums-as-objects. Every field has to flatten to a string, an integral, a boolean or null. **This is the constraint most likely to be discovered late and cause a rewrite, which is why it is stated here rather than left to the implementer.**

[OBSERVED `backend/.../corebanking/config/CoreBankingClientConfiguration.java` lines 15–29, and `docs/sessions/2026-08-28-s3-01-account-check-slice.md` §"The stub, selected by configuration"] The established pattern: the implementation is chosen **once at startup from one property**, with **no default**; an unrecognised, empty or missing value fails startup with a message naming the property, quoting what it found and listing what is accepted; and no code on the request path may ask whether it is talking to a stub. AD-002c's configuration must follow this exactly.

---

## 4. Options

### 4.1 The cost model everything else is judged against

**Volume, derived from the journey — not from the vendor's example.**

Channel take-up assumptions **[UNVERIFIED — these are my estimates, not measured; they are the largest source of error in the figures below]**: SMS selected and verified by ~100% of customers; WhatsApp selected by ~70% and verified by ~60%; email supplied by ~40% and verified by ~30%.

| Event | Basis | SMS/acct | WhatsApp/acct | Email/acct |
|---|---|---|---|---|
| Stage 2 OTP + resends | 1 per **selected** channel × 1.3 resend factor | 1.30 | 0.91 | 0.52 |
| Stage 12 submission notice | 1 per **verified** channel | 1.00 | 0.60 | 0.30 |
| Post-submission transitions (`approved`, or `rejected` then later `approved`) | 1.3 per verified channel | 1.30 | 0.78 | 0.39 |
| In-flight transitions (`awaiting_registry`, `blocked_scan`, `blocked_liveness`, `abandoned`, `terminated_registry_mismatch`) | 0.5 per verified channel | 0.50 | 0.30 | 0.15 |
| **Total per account** | | **4.10** | **2.59** | **1.36** |
| **Total, 100,000 accounts** | | **410,000** | **259,000** | **136,000** |

This confirms CLAUDE.md's instruction to size for several messages per customer, and quantifies it: **roughly four SMS, not one.**

**The abuse ceiling, for sizing headroom rather than budget:** the resend cap of 3 per channel per session bounds one session at 3 channels × 4 sends = 12 OTP messages. If every customer maxed every cap, OTP traffic alone would be 1.2 M messages. The cap is what makes the budget finite; it should not be relaxed without re-running this arithmetic.

**Segments — the Arabic penalty.** Arabic forces UCS-2: **70 characters single-segment, 67 per segment when concatenated** [DOC]. An Arabic OTP body that names the bank, names its own channel (the journey requires this), carries the code, states the 5-minute validity and warns against sharing runs about 90–110 characters — **two segments**, matching FIB's observed 104-character bilingual message [OBSERVED]. A rejection notice carrying REJ-0n's customer-facing message plus the reference number runs 140–180 characters — **three segments**. Blended estimate: **2.2 segments per SMS**.

> Stated plainly, because it is the point of the exercise: **the same message in English would be one segment. Arabic roughly doubles the SMS bill.** Not "adds a bit" — doubles it. Any per-message price quoted to the bank must be multiplied by 2.2, not by 1.

**410,000 SMS × 2.2 ≈ 900,000 billable segments over the campaign.**

**Cost, per route:**

| Route | Rate/segment | Campaign SMS cost | Marker |
|---|---|---|---|
| Twilio international, published | **$0.4749** | **$427,410** | [DOC twilio.com/en-us/sms/pricing/sd 2026-08-29] |
| International aggregator, negotiated mid | ~$0.10 | ~$90,000 | [UNVERIFIED] |
| Regional MENA aggregator, plausible | ~$0.03 | ~$27,000 | [UNVERIFIED] |
| Domestic route / bank's own gateway | ~$0.005–$0.02 | **~$4,500–$18,000** | [UNVERIFIED] |

WhatsApp: 259,000 charged messages. At an assumed "Other"-tier rate of $0.04–$0.08 **[UNVERIFIED — get the rate card CSV]** that is **~$10,000–$21,000** for the whole campaign, **with no segmentation penalty for Arabic**. WhatsApp is plausibly *cheaper per customer reached* than international SMS by a factor of twenty.

Email: 136,000 messages. Amazon SES list pricing would be on the order of **$14**. The bank's own relay: **$0**. Email cost is noise; do not optimise it.

**Conclusion of the cost model: the entire messaging budget is the SMS routing decision. It is a five-to-six-figure question and it is 97% of the total.**

### 4.2 SMS — four options

**Option S1 — The bank's own SMS gateway (Sudan-local; the FIB precedent). RECOMMENDED.**

| Dimension | Assessment |
|---|---|
| Account eligibility for a Sudanese bank | **Non-issue.** The account exists, is the bank's, and is already used for the bank's own customer messaging. No sanctions surface at all. |
| Coverage and deliverability into Sudan | Best available. A domestic gateway is on-net or interconnected with all three operators, and the bank's sender ID is already registered with TPRA and with each operator — which is precisely what the international route cannot get without ~3 weeks of pre-registration [DOC Twilio Sudan guidelines] and without which MTN and Sudani reject numeric senders outright. |
| Arabic / UCS-2 | Must be confirmed against the actual gateway, but FIB has been sending an Arabic-containing body through exactly this class of endpoint in production [OBSERVED `MessageMapper.constructMessage`], which is direct evidence that UCS-2 works on this route. |
| Cost at our volume | ~$4,500–$18,000 [UNVERIFIED rate], and quite possibly **zero marginal cost** to the project if the bank absorbs it as internal infrastructure — which is how FIB is structured. |
| Exit cost if wrong | **Lowest of any option.** One adapter class behind `MessageSender`. Nothing else in the codebase knows the gateway exists. |
| Risk we own | The gateway's contract is unknown to us; we have FIB's *sibling* gateway shape (`{CIF, Message, Phone, Email, Subject}` in, `{cif, phone, responseCode, message, responseStatus}` out) but that is a different bank. **No delivery receipts observed in FIB — if this gateway also lacks them, we can record acceptance but not delivery, and customer.md asks for a delivery result.** See §8. |

**Option S2 — Sudan-local aggregator or direct MNO enterprise accounts (Zain / MTN / Sudani).**

Contracted by the bank in its own name, in SDG. Same sanctions position as S1 (none). Same deliverability position (domestic, registered sender). Cost similar. Requires three contracts if taken direct, or one if via a local aggregator with connections to all three. [UNVERIFIED] operators typically require 10,000–50,000 messages/month minimum — at ~410,000 messages over 12–18 months we average ~25,000/month, which sits inside that band. **This is the fallback if the bank has no gateway of its own, and the difference from S1 is purely commercial: same adapter, different endpoint.** Exit cost identical to S1.

**Option S3 — Regional MENA CPaaS (Cequens, Unifonic, D7 Networks, or Infobip's MEA operation).**

| Dimension | Assessment |
|---|---|
| Account eligibility | **The crux, and unresolved.** Cequens is Egypt/UAE-domiciled, Unifonic Saudi, Infobip Croatian/EU. None is US-domiciled, so none is subject to the blanket OFAC-driven refusal that catches Twilio; the EU regime is targeted only [DOC Reg 2023/2147]. But every one of them still needs its own bank to clear a payment from a Sudanese counterparty, and that is where de-risking bites. **[UNVERIFIED for all four.]** |
| Coverage into Sudan | Cequens publishes direct interconnects for Egypt, Saudi, UAE, Kuwait, Bahrain, Jordan, Morocco, Pakistan — **Sudan is not on the published list** [secondary, cequens.com, 2026-08-29], so Sudan would be reached via a partner route, not direct. Infobip claims worldwide coverage; Sudan-specific confirmation **[UNVERIFIED]**. |
| Arabic / UCS-2 | Universally supported; these are MENA-first vendors. |
| Cost | Better than a US aggregator, worse than domestic. ~$27,000 at an assumed $0.03 [UNVERIFIED]. |
| Exit cost | Low — one adapter. But if the account is the *vendor's* (ours) rather than the bank's, exit at delivery means re-contracting, and the bank inherits a supplier it did not choose. |
| Genuine advantage | One contract covers **SMS *and* WhatsApp** — most of these are Meta Business Solution Providers, which is exactly the mechanism §6 needs. That is worth real money in coordination cost for a solo developer. |

**Option S4 — Global US aggregator (Twilio, AWS End User Messaging, Vonage, Bird). NOT RECOMMENDED.**

Ruled out on two independent grounds, either of which alone is sufficient:

1. **Cost.** $427,410 at Twilio's published Sudan rate for a campaign whose entire justification is a regulatory data refresh. It is not defensible and the bank will not pay it.
2. **Account eligibility.** Twilio does not accept payment from countries limited by OFAC [secondary; Sudan-specific **[UNVERIFIED]**]. Even if a workaround existed — the vendor holding the account and re-billing the bank — that is a structure we should refuse: it makes us the sanctions-exposed party, it fails the delivery model (the bank cannot take the account over), and it converts a $427k line item into our credit risk.

Named and costed because ruling it out explicitly is the point; a later session must not quietly reach for Twilio because it has the best documentation.

### 4.3 WhatsApp — four options

**Option W1 — Meta WhatsApp Cloud API, WABA owned by the bank, onboarded via a Business Solution Provider using partner-led business verification. RECOMMENDED as the target; ship as stub in v1.**

| Dimension | Assessment |
|---|---|
| Platform eligibility for a Sudanese business | **Open.** Sudan is on neither of Meta's two exclusion lists [DOC]. |
| Billing eligibility | **The blocker, and unresolved.** Meta's payment rails reject Sudanese bank accounts for Monetization Manager [DOC-adjacent]; the inference to WhatsApp billing is **[UNVERIFIED]**. A BSP that bills the bank in a currency the bank can actually pay, and settles with Meta itself, is the standard route around this — and is the specific reason to prefer BSP-mediated over direct. |
| Deliverability into Sudan | Not carrier-mediated at all. WhatsApp rides data, which sidesteps the entire sender-ID registration problem that dominates SMS. Depends instead on the customer having WhatsApp and data — high in Sudan, but **[UNVERIFIED]** as a percentage. |
| Arabic | Native UTF-8, **no segmentation penalty**. This is a genuine and under-appreciated cost advantage over SMS for an Arabic-first product. |
| Cost | ~$10,000–$21,000 for the campaign [UNVERIFIED rate — get the 2026-07-01 rate card]. |
| Exit cost | Moderate. The adapter is one class, but the **templates are registered assets on Meta's side**, tied to the WABA. Moving BSP is cheap if the WABA is the bank's; moving to a different WABA means re-approving every template. **This is why the WABA must be the bank's from the start and not the BSP's.** |
| Lead time | Business verification + display-name review + per-template approval, **[UNVERIFIED duration, no published SLA]**. Plan for weeks; do not plan a date. |

**Option W2 — WhatsApp via a CPaaS that also carries SMS (Infobip, Cequens, Twilio-as-BSP, 360dialog).**

Same platform, one supplier, one contract, one invoice. The trade is that some BSPs create the WABA under their own portfolio, which raises the exit cost from "swap an adapter" to "re-approve every template". **Insist on bank-owned WABA with the BSP holding only system-user access.** Twilio as BSP inherits Twilio's billing problem and is not viable here.

**Option W3 — the bank's existing WhatsApp presence, if any.**

Worth asking before anything else. A bank that already runs a WhatsApp channel already has a verified portfolio, an approved display name and a working billing arrangement — collapsing weeks of lead time and the entire billing question to nothing. **Cheapest possible answer if it exists.** [UNVERIFIED whether it does — question B4 in §7.]

**Option W4 — do not ship WhatsApp in v1.**

Named because it is genuinely viable, not as a straw man. The journey requires at least one *phone* channel verified, and SMS satisfies that alone. Every WhatsApp dependency — verification lead time, template approval, billing eligibility — is removed. The cost is real: WhatsApp is the cheapest channel and, for customers whose SMS is failing on an MTN or Sudani route, possibly the *only* one that works. **Recommended only as the v1 configuration state, not as the design.** Build the adapter slot; leave it stubbed; turn it on when the WABA exists.

### 4.4 Email — three options

**Option E1 — the bank's own SMTP relay via Spring's `JavaMailSender`. RECOMMENDED.**

Zero sanctions surface, zero cost, one small adapter, and the mail already originates from the bank's real domain — which matters because a customer receiving a bank OTP from `noreply@some-esp.example` is a phishing lesson we would be teaching at scale. The work is DNS (SPF, DKIM, DMARC alignment), which is a bank action under every option. Exit cost: one class. Weakness: no bounce or complaint feedback unless the relay provides it, and an on-premise relay's IP reputation is entirely the bank's problem.

**Option E2 — non-US ESP with an HTTP API (Brevo, Mailjet/Sinch, Zoho ZeptoMail, Elastic Email).**

EU or India-domiciled, so subject to the targeted EU regime rather than the blanket US one [DOC Reg 2023/2147]. Provides bounce/complaint webhooks, which is the one thing E1 may lack and which our per-channel delivery-result requirement wants. Cost negligible at 136,000 messages. **Account eligibility for a Sudan-domiciled payer is [UNVERIFIED] for all four.** Exit cost: one class, plus re-verifying the sending domain with the new provider.

**Option E3 — US ESP (Amazon SES, SendGrid, Mailgun, Postmark).**

Technically excellent, cheapest per message on paper, best-documented. Same account-eligibility exposure as S4 — AWS's terms place sanctions compliance on the customer and reserve the right to suspend [DOC aws.amazon.com/service-terms], which for a Sudanese account holder is a suspension risk sitting under a live customer journey. **Not recommended.** Named because SES will otherwise be the reflexive choice.

### 4.5 Ruled out across all channels: provider-hosted OTP products

Twilio Verify, Infobip 2FA, AWS End User Messaging's verification service and their equivalents generate, store and validate the code inside the vendor. Adopting one would move OTP state out of `app.otp_challenge`, break the per-channel `verified`/`declined`/`unverified` model the profile stores, and put the "code issued / verification attempt / verification outcome" audit events outside our hash-chained trail. **We generate the code, we store it, we validate it; the provider is a transport and nothing more.** This is not negotiable and the port below reflects it — `MessageSender` has no verify method and never returns a code.

---

## 5. The interface

One port, `MessageSender`. Package placement follows CLAUDE.md: shared outbound integrations sit beside the features, one package per external system, and the port lives with the logic, never in its adapter's package.

```
sd.gov.bank.fruserupdate.messaging
├─ domain/    MessageSender (the port), MessageChannel, Urgency, MessagePayload (sealed),
│             OutboundMessage, MessageDispatchResult, DispatchOutcome, DeliveryState,
│             DeliveryReceipt
├─ service/   ChannelRoutingMessageSender (composite; implements the port)
├─ config/    MessageSenderConfiguration (startup selection, no default)
├─ stub/      StubMessageSender, StubMessagingProperties
├─ http/      (later) GenericHttpSmsSender + its properties        — adapter, plumbing
├─ smtp/      (later) SmtpEmailSender                              — adapter, plumbing
└─ whatsapp/  (later) CloudApiWhatsAppSender                       — adapter, plumbing
```

`domain` and `service` are the reserved logic suffixes and must remain testable with plain JUnit — no Spring context, no database, no socket, no real clock. Everything under `http`, `smtp`, `whatsapp`, `stub` and `config` is plumbing.

### 5.1 Channel and urgency

```java
package sd.gov.bank.fruserupdate.messaging.domain;

/**
 * The three verifiable channels. The wire values MUST stay byte-identical to the
 * CHECK constraint on app.profile_channel.channel (V0007) and app.otp_challenge.channel
 * (V0021): 'sms', 'whatsapp', 'email'. Adding a fourth channel is a migration, not an enum edit.
 */
public enum MessageChannel {
  SMS("sms"),
  WHATSAPP("whatsapp"),
  EMAIL("email");

  private final String wireValue;
  MessageChannel(String wireValue) { this.wireValue = wireValue; }
  public String wireValue() { return wireValue; }
}
```

```java
/**
 * Whether a send is on the customer's critical path.
 *
 * <p>INTERACTIVE — a stage 2 OTP. The customer is staring at a countdown; the journey is
 * blocked until the code arrives. The adapter MUST use the provider's lowest-latency route,
 * apply a short deadline, and MUST NOT retry internally — a retried OTP is a duplicate code
 * on the customer's handset, and the resend control (30s/60s/120s, capped at 3) is the
 * journey's own retry mechanism and the only one permitted.
 *
 * <p>DEFERRED — a submission or status-transition notification. Fire-and-forget relative to
 * the profile (customer.md: "a failed SMS does not change a status"). The adapter MAY use a
 * bulk/economy route and a longer deadline; retry is the AD-005 outbox dispatcher's job,
 * driven by next_attempt_at, never the adapter's.
 */
public enum Urgency { INTERACTIVE, DEFERRED }
```

> **Why urgency is a value on the request and not a second method.** Two methods (`sendInteractive` / `sendDeferred`) was the obvious alternative and I rejected it: it doubles the surface every adapter must implement, and every adapter would implement both identically except for a route selector and a timeout. Carrying it as a value keeps one method per adapter and puts the difference exactly where the provider expresses it — in the route, the priority flag and the deadline. The *caller-side* difference is expressed structurally instead: an OTP is sent inline from the request thread by the OTP service, a notification is sent by the outbox dispatcher. Two callers, one port.

### 5.2 Payload — sealed, because the three channels are genuinely not interchangeable

```java
/**
 * What actually goes on the wire, already rendered. Rendering from a template key plus
 * parameters is business logic and belongs in a domain/service class (BL-005 owns the copy);
 * by the time a payload reaches the port there is nothing left to decide.
 *
 * <p>Sealed rather than one bag-of-strings because the three channels differ irreducibly:
 * SMS carries a body and a sender id; email carries a subject; WhatsApp carries no free text
 * at all, only a template name registered with Meta plus positional parameters. Flattening
 * that into one record would force every adapter to validate by hand what the compiler can
 * check here.
 */
public sealed interface MessagePayload
    permits SmsPayload, WhatsAppPayload, EmailPayload {
  MessageChannel channel();
}

public record SmsPayload(String body) implements MessagePayload {
  // No senderId field: the sender id is registered with the operator and belongs to the
  // adapter's configuration, not to a per-message call site. See Twilio's Sudan guidelines —
  // an unregistered sender does not deliver on MTN Sudan or Sudani One.
  public MessageChannel channel() { return MessageChannel.SMS; }
}

/**
 * @param templateName  the name registered with Meta. Never free text: authentication and
 *                      utility traffic outside a customer-service window is template-only.
 * @param languageCode  Meta's code, e.g. "ar".
 * @param bodyParameters positional parameters. Meta limits AUTHENTICATION template parameters
 *                      to 15 characters and forbids URLs, media and emojis.
 * @param category      declared, not inferred — it decides the price and the rules.
 */
public record WhatsAppPayload(String templateName,
                              String languageCode,
                              List<String> bodyParameters,
                              WhatsAppTemplateCategory category) implements MessagePayload {
  public MessageChannel channel() { return MessageChannel.WHATSAPP; }
}

public enum WhatsAppTemplateCategory { AUTHENTICATION, UTILITY, MARKETING }

public record EmailPayload(String subject, String bodyText, String bodyHtml)
    implements MessagePayload {
  public MessageChannel channel() { return MessageChannel.EMAIL; }   // bodyHtml nullable
}
```

### 5.3 The request

```java
/**
 * @param messageId   OUR identifier and OUR idempotency key — the app.notification_outbox row
 *                    id for a DEFERRED send, the app.otp_challenge id for an INTERACTIVE one.
 *                    Passed to the provider as its client-reference/idempotency field wherever
 *                    one exists, so a dispatcher retry after an unacknowledged response cannot
 *                    produce a second message.
 * @param destination E.164 for SMS and WhatsApp; an address for EMAIL.
 * @param correlationId opaque, non-PII, for provider-side tracing. NEVER the account number,
 *                    the national number, or the customer's name.
 */
public record OutboundMessage(UUID messageId,
                              MessageChannel channel,
                              String destination,
                              MessagePayload payload,
                              Urgency urgency,
                              String correlationId) {

  public OutboundMessage {
    Objects.requireNonNull(messageId);
    Objects.requireNonNull(channel);
    Objects.requireNonNull(payload);
    Objects.requireNonNull(urgency);
    if (destination == null || destination.isBlank()) {
      throw new IllegalArgumentException("destination must not be blank");
    }
    if (payload.channel() != channel) {                       // the one invariant worth enforcing
      throw new IllegalArgumentException(
          "payload is for channel " + payload.channel() + " but message declares " + channel);
    }
  }
}
```

**Note what is absent: there is no OTP code field, and no way for a caller to ask the port what code was sent.** The code reaches the port only already rendered inside a body or a template parameter. This is the structural answer to the FIB defect in §3.1.

### 5.4 The port

```java
/**
 * The single outbound-messaging port. One implementation is selected per channel at startup
 * from configuration — never a runtime {@code if (stub)} branch. Callers depend on this type
 * and on nothing else in this package.
 */
public interface MessageSender {

  /** Which channels this implementation can serve. The router uses it to validate its map at startup. */
  Set<MessageChannel> supportedChannels();

  /**
   * Attempts one delivery of one message to one destination.
   *
   * <p><strong>A provider-level rejection is a returned value, not a thrown exception.</strong>
   * "The gateway said code 12, invalid destination" is information the audit trail must record
   * and the dispatcher must act on; an exception discards the provider's own words. Only a
   * programming error — a null argument, an unsupported channel — throws. This mirrors
   * {@code CoreBankingClient} returning ProcessOmniCheckAct's raw 1/2/-1 rather than an enum.
   *
   * <p>Implementations MUST NOT retry internally. Retry policy belongs to the outbox
   * dispatcher (AD-005) for DEFERRED traffic and to the customer's resend control for
   * INTERACTIVE traffic.
   *
   * <p>Implementations MUST bound their own latency and return TRANSIENT_FAILURE on timeout,
   * because an INTERACTIVE send happens on the request thread with a customer watching.
   */
  MessageDispatchResult send(OutboundMessage message);
}
```

### 5.5 The result — shaped for the audit trail, not for the vendor

```java
/**
 * The per-message outcome of exactly one attempt.
 *
 * <p><strong>Every field is a String, an int, a long or a boolean, deliberately.</strong>
 * This record is written straight into audit.audit_event.payload_json, which is RFC 8785
 * canonical JSON produced by audit.domain.CanonicalJson — flat objects only, with
 * String / integral / Boolean / null values, everything else rejected
 * (docs/components/persistence.md). An Instant, a Duration or a nested object here would fail
 * at the audit write, at runtime, in production, on the notification path.
 *
 * @param outcome            our closed classification — what the dispatcher acts on
 * @param providerId         the configured adapter name, e.g. "bank-gateway", "stub"
 * @param providerMessageId  the provider's own id, null when it returns none. This is what a
 *                           later delivery receipt correlates on
 * @param providerStatusCode raw, verbatim, exactly as returned. FIB's gateway returns a STRING
 *                           ("0"), not an int — do not parse it, do not normalise it
 * @param providerStatusText raw, verbatim, nullable
 * @param billedSegments     UCS-2 segments the provider says it charged for; -1 when unknown.
 *                           This is the only place the Arabic cost multiplier becomes
 *                           measurable rather than estimated — record it from day one
 * @param attemptedAtIso     ISO-8601 UTC, server clock
 * @param latencyMillis      wall-clock duration of the attempt
 */
public record MessageDispatchResult(String messageId,
                                    String channel,
                                    DispatchOutcome outcome,
                                    String providerId,
                                    String providerMessageId,
                                    String providerStatusCode,
                                    String providerStatusText,
                                    int billedSegments,
                                    String attemptedAtIso,
                                    long latencyMillis) { }

/** What the dispatcher acts on. Adapters map onto this; they never extend it. */
public enum DispatchOutcome {
  /** Provider accepted it for delivery. NOT proof of delivery — see DeliveryState. */
  ACCEPTED,
  /** Provider refused this message and will refuse it again. Do not retry. Bad number, blocked
   *  destination, unapproved template. */
  REJECTED,
  /** Timeout, 5xx, rate limit, connection failure. Retry per the outbox schedule. */
  TRANSIENT_FAILURE,
  /** Auth failure, quota exhausted, account suspended. Retry is pointless and the operator
   *  must be told. Distinguished from REJECTED because the fault is ours/the account's, not
   *  the destination's. */
  PERMANENT_FAILURE
}
```

### 5.6 Provider delivery status → something the profile can store

Acceptance and delivery are two different events and the journey asks for the second. `MessageDispatchResult` answers "did the provider take it". `DeliveryReceipt` answers "did it arrive", asynchronously, on a webhook or a poll.

```java
/**
 * The closed set the profile and the outbox may store. It is the UNION across channels,
 * deliberately: READ is WhatsApp-only, EXPIRED is SMS-and-WhatsApp, UNDELIVERED covers an SMS
 * DLR failure and an email bounce alike. An adapter that meets a provider status it cannot map
 * MUST return UNKNOWN and preserve the raw code — it must never invent a new constant, and it
 * must never guess DELIVERED.
 */
public enum DeliveryState { ACCEPTED, DELIVERED, READ, UNDELIVERED, EXPIRED, UNKNOWN }

/**
 * @param providerMessageId correlates back to MessageDispatchResult.providerMessageId
 * @param rawStatusCode/rawStatusText preserved verbatim — the same principle as storing the raw
 *        Uqudo JWS and the raw Civil Registry response. The mapped enum is derived data; the
 *        provider's own words are the evidence. Store BOTH: the enum on the outbox row for
 *        querying, the raw pair in the audit event for proving.
 */
public record DeliveryReceipt(String providerId,
                              String providerMessageId,
                              DeliveryState state,
                              String rawStatusCode,
                              String rawStatusText,
                              String reportedAtIso) { }
```

The mapping table (`"DELIVRD" -> DELIVERED`, `"UNDELIV" -> UNDELIVERED`, HTTP 200 + `responseStatus:"success"` -> `ACCEPTED`, …) lives **inside each adapter package** and is never exposed. That is what makes the provider a configuration choice: a new provider means a new adapter plus a new mapping table, and no change anywhere else.

**Consequence to state now, because it will otherwise surface as a defect report:** if the bank's gateway has no delivery-receipt facility — and FIB's has none observable [OBSERVED: no DLR handling anywhere in the FIB backend] — then every message stays at `ACCEPTED` for ever and `notification_dispatched`'s "delivery result" means "the gateway took it". That is a real gap against customer.md and it must be surfaced to the bank as question B3, not silently papered over by writing `DELIVERED`.

### 5.7 The router — so callers depend on exactly one type

```java
package sd.gov.bank.fruserupdate.messaging.service;

/**
 * The composite the rest of the backend injects. Implements the port itself, so a caller
 * depends on MessageSender and on nothing else — no registry, no per-channel bean, no Map.
 */
public final class ChannelRoutingMessageSender implements MessageSender {

  private final Map<MessageChannel, MessageSender> byChannel;

  public ChannelRoutingMessageSender(Map<MessageChannel, MessageSender> byChannel) {
    // Startup validation: every enabled channel has exactly one sender, and every sender
    // actually claims the channel it is mapped to. A mismatch fails startup, not a customer's
    // OTP at 22:00.
    byChannel.forEach((ch, sender) -> {
      if (!sender.supportedChannels().contains(ch)) {
        throw new IllegalStateException(
            "sender " + sender.getClass().getName() + " is mapped to " + ch
                + " but supports only " + sender.supportedChannels());
      }
    });
    this.byChannel = Map.copyOf(byChannel);
  }

  @Override public Set<MessageChannel> supportedChannels() { return byChannel.keySet(); }

  @Override public MessageDispatchResult send(OutboundMessage message) {
    MessageSender sender = byChannel.get(message.channel());
    if (sender == null) {
      // A channel that is switched off at this deployment. Not an exception: stage 1b should
      // never have offered it, and if it did, the honest record is a permanent failure with a
      // reason, not a stack trace on the customer's request thread.
      return MessageDispatchResult.permanentFailure(
          message, "routing", "CHANNEL_NOT_CONFIGURED",
          "no sender configured for channel " + message.channel().wireValue());
    }
    return sender.send(message);
  }
}
```

This class is pure logic — a map, a validation, a lookup — so it lives in `service` and is exercised by plain JUnit with no Spring context, satisfying the S1-08 scoping rule.

### 5.8 Configuration — the S3-01 pattern, unchanged

```properties
# One property per channel. NO DEFAULTS. An unset, empty or unrecognised value fails startup
# with a message naming the property, quoting what was found, and listing what is accepted —
# exactly as CoreBankingClientConfiguration does. A backend that silently sent a real customer's
# OTP into a stub, or silently sent nothing, would be a serious defect and an invisible one.
fru.messaging.sms.provider=stub          # stub | bank-gateway
fru.messaging.whatsapp.provider=stub     # stub | cloud-api
fru.messaging.email.provider=stub        # stub | smtp

# Channel availability, read by the stage 1b contract. FALSE means the app must not offer the
# channel at all, rather than offering it and failing at stage 2.
fru.messaging.sms.enabled=true
fru.messaging.whatsapp.enabled=false
fru.messaging.email.enabled=true
```

`MessageSenderConfiguration` is one `@Configuration` class with one switch per channel, run once at startup, producing exactly one `ChannelRoutingMessageSender` bean. **No code on the request path asks which implementation it holds** — the rule the S3-01 Javadoc states explicitly, and the reason a startup factory is not the `if (mock)` branch the delivery model forbids.

### 5.9 The stub

`StubMessageSender` implements all three channels, returns `ACCEPTED` with a synthetic `providerMessageId`, records the attempt where a test can read it, and — mirroring `fru.core-banking.stub.accounts[…]` — carries a seeded outcome table so tests can force the interesting paths:

```properties
fru.messaging.stub.outcomes[+249900000001]=REJECTED
fru.messaging.stub.outcomes[+249900000002]=TRANSIENT_FAILURE
fru.messaging.stub.outcomes[bounce@example.invalid]=PERMANENT_FAILURE
fru.messaging.stub.latency-millis=0
```

Unseeded destinations return `ACCEPTED`. This is the whole of what AD-002c should build now. **No real adapter should be written before the bank supplies a gateway specification** — writing one against documentation alone is precisely what CLAUDE.md's `@agent-researcher`-before-integration rule exists to prevent, and against an unknown gateway there is no documentation to write it against anyway.

### 5.10 The generic HTTP adapter — considered, deferred, and why

There is a tempting design: one `GenericHttpSmsSender` configured with a URL, an auth scheme, a request-body template and a success predicate (a JSON pointer plus an expected value), which would make "the provider is a configuration choice" literally true for the whole family of gateways that are just "POST some JSON, get a code back" — the family FIB's gateway belongs to. It would let the bank point us at any of options S1, S2 or S3 with no new code.

**I recommend building it, but only after the bank's gateway specification is in hand, and not now.** A configuration mini-language written speculatively is a mini-language written against imagined requirements; it will be wrong in the places that matter and will then have to be maintained alongside the concrete adapter that replaces it. **The stub plus a well-shaped port already delivers everything AD-002c owes.** Recorded here so that a later session recognises the option rather than reinventing it, and so that whoever writes the first concrete adapter writes it in a way the generic one can subsume.

---

## 6. WhatsApp Business API specifics

**What a sender identity requires.** A Meta Business portfolio; a WhatsApp Business Account within it; a **phone number that is not currently registered to WhatsApp Messenger or the WhatsApp Business app** and that can receive an SMS or a voice call for one-time registration; two-step verification enabled on that number; and an approved **display name**, which is reviewed by Meta and re-reviewed on every subsequent change [DOC developers.facebook.com / facebook.com/business/help, 2026-08-29]. **The number cannot be one the bank already uses for a consumer WhatsApp account** without migrating it, which is a distinct and irreversible-feeling operation — flag it before anyone volunteers the branch hotline number.

**What business verification requires.** Connecting the app to a Meta Business portfolio and uploading an official document (PDF, JPG, JPEG or PNG) showing the business's **legal name and physical address** [DOC]. Display-name review is initiated automatically once verification completes. **Partner-led business verification** exists, letting a Solution Partner submit the verification on the business's behalf — this is the mechanism that matters here, because a BSP already inside Meta's partner programme is far better placed to push a Sudanese entity through review than the bank submitting cold.

**How long it takes.** **[UNVERIFIED — Meta publishes no committed timeline for business verification, display-name review, or template review.]** I looked for one on the Meta for Developers documentation and in the Business Help Center and found none; the only duration evidence available is a developer-community thread reporting display-name review pending for months, which is anecdote. **Plan a range with a stub fallback, not a date.** The authoritative answer, such as it is, would come from a BSP who has recently onboarded a Sudanese financial institution.

**Does an OTP template category exist with different rules from a marketing one — yes, and the differences bite.**

| | AUTHENTICATION | UTILITY | MARKETING |
|---|---|---|---|
| Our use | Stage 2 OTP | Submission + status notifications | Never used by this product |
| Mandatory? | **Yes** — *"If your mobile app offers users the option to receive one-time passwords or verification codes via WhatsApp, you must use an authentication template."* [DOC] | For transactional notices | — |
| Body text | **Fixed, non-customisable preset text.** Optional security disclaimer, optional expiration warning [DOC] | Free-form, subject to review | Free-form, subject to review |
| Buttons | One-tap autofill, copy-code, or none (zero-tap) [DOC] | Optional | Optional |
| Content limits | **No URLs, no media, no emojis; parameters capped at 15 characters** [DOC] | Looser | Looser |
| Charged? | **Always, in our case** — every send is outside a customer-service window [DOC] | **Always, in our case** — same reason; the in-window free allowance never applies to us | Always |
| 2026 change | From **2026-06-15** keyboard suggestions default on for all authentication templates; on iOS 26+ the OTP is auto-detected from the push notification for one-tap autofill, no integration change [DOC] | — | — |

**The one place this collides with the journey.** customer.md says of stage 2: *"the delivered message should name its own channel so a customer can tell which code belongs where."* **A Meta AUTHENTICATION template's body is fixed preset text and cannot be made to say "this is your WhatsApp code".** The requirement is not satisfiable on WhatsApp as specified. In practice the impact is small — the message arrives *in WhatsApp*, which identifies the channel more reliably than any words could — but the journey line is written as a general rule and is now known to have an exception. Flagged in §10; not redesigned here.

---

## 7. What the bank must decide, not us

Every item below is a question I refuse to answer with an assumption. Several are cheap to ask and expensive to guess wrong.

| # | Question | Why it decides something |
|---|---|---|
| **B1** | **Is the client bank state-owned or privately owned?** | The CBW Act measures target the **Government of Sudan**. A state-owned bank changes every provider's compliance answer and may close the international options entirely. The `sd.gov.bank` package root raises the question; nobody has answered it. |
| **B2** | **Does the bank already operate an SMS gateway, and what is its API contract?** | This is the recommendation. FIB's peer bank does [OBSERVED]. If ours does, the answer to AD-002c is essentially settled and worth ~$400k. Ask for the endpoint specification, the auth scheme, the request/response shape, the rate limit, and a test credential. |
| **B3** | **Does that gateway report delivery, or only acceptance?** | Decides whether `notification_dispatched`'s "delivery result" can mean *delivered* or only *accepted*. If it cannot, customer.md's audit requirement is met only in the weaker sense and someone should say so out loud. |
| **B4** | **Does the bank already have a Meta Business portfolio, a verified business, or an existing WhatsApp Business presence?** | If yes, weeks of verification lead time and the entire billing-eligibility question collapse to nothing. |
| **B5** | **In whose name is each messaging account held, and who pays?** | The delivery model says the bank. If any account is held by us, the bank inherits a supplier it did not choose and we carry the sanctions exposure and the credit risk on a $400k line. **The WABA in particular must be the bank's, not a BSP's, or every template must be re-approved on any BSP change.** |
| **B6** | **Which legal business identity is used for Meta business verification, and can the bank supply an official document showing legal name and physical address in a form Meta accepts?** | The gating artifact for the entire WhatsApp path [DOC]. |
| **B7** | **Which alphanumeric sender ID is registered with TPRA and with Zain, MTN and Sudani, and is it already registered?** | Twilio's Sudan guidelines state MTN Sudan and Sudani One **reject numeric senders outright**, and pre-registration takes ~3 weeks [DOC]. An unregistered sender loses roughly half the market at stage 2. |
| **B8** | **Can the bank actually settle in USD/EUR with a foreign supplier?** | The real blocker is not whether a provider will sell, it is whether the payment clears. This is a treasury question and it should be asked before any provider conversation, not after. |
| **B9** | **Which domain sends customer email, and who controls its DNS for SPF/DKIM/DMARC?** | Required under every email option. Also decides whether OTP mail lands in inbox or spam, which is the difference between email being a channel and being decoration. |
| **B10** | **Has compliance/legal reviewed sending customer PII — a name, a reference number, a rejection reason — through a foreign messaging provider?** | Related to OQ-001 and to OQ-011's shape. A status notification naming a rejection reason is customer data crossing a border. |

---

## 8. What I could not determine

Stated explicitly. None of these is filled with a plausible guess.

1. **Whether Twilio, Infobip, Cequens, Unifonic, Brevo, Zoho or AWS will open and bill an account for a Sudan-domiciled entity.** Twilio's blanket "no payments from OFAC-limited countries" is secondary-sourced and its article returned 403 to direct fetch; Sudan-specific confirmation is **[UNVERIFIED]** for all seven. **Authoritative source: an account application from a Sudanese billing address, or each provider's compliance team in writing.** This is the single largest unknown in the report and it is cheap to resolve.
2. **The actual per-segment SMS price on any domestic Sudanese route.** My $0.005–$0.02 band is an estimate with no primary source. The $427k Twilio figure is [DOC]; the alternative it is compared against is not. **Authoritative source: a quote from the bank's gateway operator or from Zain/MTN/Sudani enterprise sales.**
3. **The WhatsApp "Other"-tier per-message rate for authentication and utility.** Sudan is not separately listed and falls to "Other" [DOC], but I could not extract figures from the rate-card files. **Authoritative source: the rate-card CSV linked from developers.facebook.com/documentation/business-messaging/whatsapp/pricing, effective 2026-07-01.**
4. **How long Meta business verification, display-name review and template review actually take.** Meta publishes no SLA I could find. **Authoritative source: a BSP with recent Sudanese financial-services onboarding experience.**
5. **Whether Meta's billing accepts a Sudanese payment instrument for WhatsApp.** The Monetization Manager exclusion is documented; the inference to WhatsApp billing is not. **Authoritative source: attempting to add a payment method, or a BSP confirming they will bill the bank directly.**
6. **The precise current scope of US Sudan measures.** The OFAC programme page timed out and the Federal Register notice redirected to an access-block page. My characterisation — list-based, plus CBW Act measures aimed at the Government of Sudan — is from search summaries of primary indexes, not from the primary text. **[UNVERIFIED.] Authoritative source: OFAC's Sudan and Darfur Sanctions page, the SDN list, and Federal Register 2026-14568. This is a legal question for the bank's compliance function.**
7. **Infobip's Sudan coverage and self-signup exclusions.** Their page truncated before Sudan. **[UNVERIFIED.]**
8. **Sudanese operator market shares, TPRA A2P registration rules and minimum monthly volumes.** All from one commercial secondary source. **[UNVERIFIED.]**
9. **The bank's own gateway contract.** We have FIB's *peer* gateway shape [OBSERVED], not ours. Every adapter detail depends on it and none of it is known.
10. **WhatsApp penetration among the bank's retail customer base.** Decides whether the WhatsApp channel is worth its lead time. Nobody has measured it.

---

## 9. Risks

| # | If the recommendation is wrong | Cost of being wrong | Cost to reverse |
|---|---|---|---|
| 1 | **The bank has no SMS gateway and no domestic route, and international is the only option** | The campaign's SMS bill goes from ~$15k to ~$427k, or the bank refuses and SMS is cut — which would break stage 2's at-least-one-phone-channel gate for every customer without WhatsApp. | Reversing the *code* is one adapter class. Reversing the *budget* is a project-level conversation. **This is why B2 must be asked in the next contact with the bank, not in sprint 4.** |
| 2 | **WhatsApp billing turns out to be blocked for a Sudanese entity** | WhatsApp never ships. SMS carries the whole journey, and customers on MTN/Sudani routes with sender-ID problems have no fallback. | Low, by design: the adapter is stubbed and `fru.messaging.whatsapp.enabled=false` already anticipates it. **But stage 1b must not offer a channel that cannot deliver** — see §10. |
| 3 | **The bank's gateway has no delivery receipts** | customer.md's per-channel "delivery result" degrades to "the gateway accepted it". An operator investigating "the customer says they never got the rejection notice" cannot distinguish not-sent from not-delivered. | Cheap in code — `DeliveryState.UNKNOWN` already models it honestly. Expensive in trust if discovered by an auditor rather than declared by us. |
| 4 | **The alphanumeric sender ID is not registered** | Silent, partial delivery failure on MTN and Sudani — roughly half the market [UNVERIFIED share] — presenting as "some customers never get their code". The hardest class of production bug to diagnose, because it looks like customer error. | Registration is ~3 weeks [DOC] and cannot be compressed. **Start it before the pilot, not after.** |
| 5 | **The Arabic segment estimate (2.2) is low** | Direct multiplier on the SMS bill. If real copy averages 3 segments, add 36%. | Zero to reverse, and it is measurable from day one *if* `billedSegments` is recorded — which is why it is in the result record. |
| 6 | **`Urgency` as a request field proves too coarse** | An adapter needs a third route class (e.g. a premium OTP route distinct from standard interactive). | Trivial: add an enum constant. The alternative design — two port methods — would have made this change a breaking one across every adapter. |
| 7 | **A future channel is needed (push, USSD, voice OTP)** | `MessageChannel` is closed and pinned to a database CHECK constraint. | A migration plus an enum constant plus one adapter. Bounded and visible, which is the point of pinning it to the constraint rather than leaving it open. |
| 8 | **Sanctions position changes mid-campaign** | A provider suspends the account under a live customer journey. AWS's terms explicitly reserve this [DOC]. | This is the strongest argument for the domestic route, which has no such exposure. If an international provider is used, the stub-plus-config structure means failing over to a second provider is a property change and a restart — **provided a second adapter already exists.** Consider that the real justification for the generic HTTP adapter in §5.10. |

---

## 10. Implications for the journey and for settled decisions

**Two, both flagged rather than resolved, per the instruction to stop rather than redesign.**

**(a) Stage 1b offers WhatsApp by default; a deployment where WhatsApp is unavailable must not.** customer.md has SMS and WhatsApp both selected by default at 1b, each independently deselectable, at least one required. If the WABA does not exist at delivery — the likely v1 state given §6 — the app would offer a WhatsApp row, send a code into a stub, and leave the customer watching a timer for a message that will never arrive. **The port and its configuration already carry `fru.messaging.<channel>.enabled`, but stage 1b's contract has no field for it and the journey document assumes three channels are always on offer.** This is a small, additive change to the 1b contract and to AD-002f's reference/manifest shape — *which channels this deployment can actually verify* — not a redesign of the journey. It needs a decision from whoever owns customer.md. I have not made it.

**(b) "The delivered message should name its own channel" is not satisfiable over WhatsApp.** Meta AUTHENTICATION templates have fixed, non-customisable body text [DOC]; we cannot add "this is your WhatsApp code". The channel is nonetheless obvious from the app the message arrives in, so the customer-facing intent survives — but the journey states a general rule that now has a known exception, and the rule should say so rather than be quietly violated by the implementation.

Nothing in this report contradicts a decision recorded as settled in PROJECT_PLAN.md. AD-005's outbox and dispatcher are taken as given and untouched; the port is designed to fit them, not to replace them.

---

## 11. Card updates

No `docs/components/messaging.md` exists. Drafted in full below, following the `core-banking.md` format.

````markdown
# Component: Outbound messaging (SMS · WhatsApp · email)

Status: **research only — no port written, no adapter written, no account held**
Last verified: never · Card created 2026-08-29 (AD-002c research)
Decision: **AD-002c is OPEN.** This card records the proposed design and what is known
versus assumed, so the assumptions are attackable when the first adapter is written.

## The delivery-model position

We are a vendor building for delivery to a bank. **We hold no messaging account and will
hold none before delivery.** Every provider is stubbed behind one port; configuration swaps
stub for real at delivery. Nothing in this card licenses writing a concrete adapter against
documentation alone — CLAUDE.md's `@agent-researcher`-before-integration rule bites the
moment real HTTP appears.

## The port (proposed, not written)

- `sd.gov.bank.fruserupdate.messaging.domain.MessageSender` — one method,
  `MessageDispatchResult send(OutboundMessage)`, plus `Set<MessageChannel> supportedChannels()`.
- `ChannelRoutingMessageSender` (in `…messaging.service`) implements the port and delegates by
  channel, so callers depend on exactly one type.
- Selected at startup by `fru.messaging.{sms,whatsapp,email}.provider`. **No default** — an
  unset, empty or unrecognised value fails startup, per the `CoreBankingClientConfiguration`
  precedent (S3-01).
- `fru.messaging.{channel}.enabled` declares whether a deployment can verify that channel at
  all. Stage 1b must read it. **[Journey gap — customer.md assumes all three are always offered.]**

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

- [ ] **B2** — does the bank have an SMS gateway, and what is its contract? (worth ~$400k)
- [ ] **B1** — is the bank state-owned? (changes every compliance answer)
- [ ] **B3** — does the gateway report delivery, or only acceptance?
- [ ] **B7** — is an alphanumeric sender ID registered with TPRA, Zain, MTN and Sudani? (~3 weeks)
- [ ] **B4/B5/B6** — existing Meta portfolio; who owns the WABA; verification document
- [ ] **B8** — can the bank settle with a foreign supplier at all?
- [ ] **B9** — sending domain and DNS control for SPF/DKIM/DMARC
- [ ] Meta rate-card CSV (effective 2026-07-01) — the "Other"-tier authentication and utility rates
- [ ] `app.notification_outbox` does not exist yet (AD-005 designed it; no migration matches)
````

**One line to add to `docs/components/persistence.md`, under "Open verification items":**

```markdown
- [ ] `app.notification_outbox` is designed in the AD-005 report §6A but **no migration creates
      it** — no file under `db/migration/` matches `outbox` or `notification`. [OBSERVED
      2026-08-29] AD-002c's port is shaped to fit that design; the table itself is unbuilt.
```

**One line to add to `RISKS.md`** (next free R-number):

```markdown
| R-0nn | International SMS routing for an Arabic-first campaign in Sudan | ~$427,000 at Twilio's
published Sudan rate ($0.4749/segment [DOC], × ~900,000 UCS-2 segments) versus ~$5,000–$18,000
on a domestic route — a factor of 15–90, and 97% of total messaging spend. Compounded by MTN
Sudan and Sudani One rejecting numeric sender IDs outright [DOC], so an unregistered
international sender silently loses roughly half the market at stage 2. | The port makes the
provider a configuration choice, so the code cost of being wrong is one adapter. The budget cost
is not reversible by us. Ask the bank whether it has its own SMS gateway (AD-002c question B2)
and start alphanumeric sender-ID registration — ~3 weeks — before any pilot. | 🔴 Live |
```

---

## 12. Noticed in passing

Not expanded, not acted on.

- `../FIB/backend server fib/utility - Copy/` is a byte-level duplicate of the FIB backend tree. Every grep against FIB returns each file twice. Harmless, but it doubles the noise in any future FIB survey and is worth knowing before someone concludes there are two implementations.
- FIB's `sendOtp` sends the **same generated code** to both SMS and email — one code, two channels. That is exactly the pattern CLAUDE.md forbids ("a single code sent to several channels proves none of them"), which is presumably why the constraint is written the way it is. Confirms the per-channel design rather than challenging it.
- FIB carries `spring-boot-starter-data-redis` and uses Redis purely to cache the bank partner login token with a TTL [OBSERVED `BankServiceImpl.login`]. AD-005 already ruled out Redis for us; if our bank gateway also issues a short-lived partner token, that token cache is a `Caffeine` entry or a plain `AtomicReference` in the adapter, not a new infrastructure component.
- Twilio publishes per-country regulatory guideline pages under a stable URL pattern (`twilio.com/en-us/guidelines/<iso2>/sms`) and per-country pricing under `twilio.com/en-us/sms/pricing/<iso2>`. Both render cleanly to markdown and are the fastest primary source for A2P rules in any market, whether or not Twilio is the provider. Worth recording for future sessions.

---

## Sources

- [Twilio — Sudan SMS Guidelines](https://www.twilio.com/en-us/guidelines/sd/sms)
- [Twilio — SMS Pricing in Sudan](https://www.twilio.com/en-us/sms/pricing/sd)
- [Twilio — What is the SMS character limit?](https://www.twilio.com/docs/glossary/what-sms-character-limit)
- [Twilio — Can I fund my Twilio project from an international billing address?](https://support.twilio.com/hc/en-us/articles/223183268-Can-I-fund-my-Twilio-project-from-an-international-billing-address)
- [Meta for Developers — WhatsApp Cloud API Support (country restrictions)](https://developers.facebook.com/docs/whatsapp/cloud-api/support/)
- [Meta for Developers — Pricing on the WhatsApp Business Platform](https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing)
- [Meta for Developers — Authentication-international rates](https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing/authentication-international-rates/)
- [Meta for Developers — Authentication templates](https://developers.facebook.com/documentation/business-messaging/whatsapp/templates/authentication-templates/authentication-templates)
- [Meta for Developers — Partner-led business verification](https://developers.facebook.com/docs/whatsapp/solution-providers/partner-led-business-verification/)
- [Meta Business Help Centre — Add payment information for Monetization Manager](https://www.facebook.com/business/help/103628146695524)
- [Infobip Docs — SMS coverage and connectivity](https://www.infobip.com/docs/essentials/getting-started/sms-coverage-and-connectivity)
- [OFAC — Sudan and Darfur Sanctions](https://ofac.treasury.gov/sanctions-programs-and-country-information/sudan-and-darfur-sanctions)
- [Federal Register — Imposition of Additional Sanctions on Sudan Under the CBW Act (2026-14568)](https://www.federalregister.gov/documents/2026/07/20/2026-14568/imposition-of-additional-sanctions-on-sudan-under-the-chemical-and-biological-weapons-control-and)
- [EUR-Lex — Council Regulation (EU) 2023/2147](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=OJ%3AL_202302147)
- [Consilium — Sudan: Council sanctions individuals and entities (2025-07-18)](https://www.consilium.europa.eu/en/press/press-releases/2025/07/18/sudan-council-sanctions-individuals-and-entities-over-serious-human-rights-violations-and-threats-to-the-peace-stability-and-security-of-the-country/)
- [CEQUENS — CPaaS platform overview](https://www.cequens.com/)
- [AWS Service Terms](https://aws.amazon.com/service-terms/)
- [sent.dm — Sudan SMS guide (secondary, unverified)](https://www.sent.dm/resources/sudan-sms-guide)

**Local files read (all absolute paths):**
`c:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\PROJECT_PLAN.md` ·
`…\EXECUTION_PLAN.md` ·
`…\docs\journeys\customer.md` ·
`…\docs\journeys\operator.md` ·
`…\docs\components\persistence.md` ·
`…\docs\components\core-banking.md` ·
`…\docs\sessions\2026-08-22-research-ad-005-persistence.md` ·
`…\backend\src\main\java\sd\gov\bank\fruserupdate\corebanking\domain\CoreBankingClient.java` ·
`…\backend\src\main\java\sd\gov\bank\fruserupdate\corebanking\config\CoreBankingClientConfiguration.java` ·
`…\backend\src\main\resources\db\migration\V0007__app_channels_and_otp.sql` ·
`…\backend\src\main\resources\db\migration\V0021__otp_challenge_channel_check.sql` ·
`c:\Users\DELL\Documents\Osman\Waleed\FIB\backend server fib\utility\src\main\java\com\aztech\utility\service\Impl\BankServiceImpl.java` ·
`…\FIB\backend server fib\utility\src\main\java\com\aztech\utility\controller\OTPController.java` ·
`…\FIB\backend server fib\utility\src\main\java\com\aztech\utility\request\FibMessageRequest.java` ·
`…\FIB\backend server fib\utility\src\main\java\com\aztech\utility\mapper\MessageMapper.java` ·
`…\FIB\backend server fib\utility\src\main\java\com\aztech\utility\response\SMSMessageResponse.java` ·
`…\FIB\backend server fib\utility\src\main\java\com\aztech\utility\domain\Message.java` ·
`…\FIB\backend server fib\utility\src\main\java\com\aztech\utility\config\BankReaderConfig.java` ·
`…\FIB\docs\research\2026-06-14-otp-flow-review.md`

**No file in `../FIB` was modified. No credential, key, endpoint password or connection-string value from `../FIB` appears anywhere in this report.**

---

## 13. Commit/push proof

```
$ git log --oneline -1
20ee3bb docs: AD-002c research — outbound messaging providers for Sudan

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

Push output:
```
To https://github.com/Osmantou/Fr_user_update
   4283d43..20ee3bb  main -> main
```
