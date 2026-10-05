# Research report — AD-010: how the mobile app's public API hostname should be provided

**Date:** 2026-09-07 · **Researcher pass** (no code, no infrastructure changes, no console actions were taken)

## 0. ID verification and reading of the question

**AD-010 is unused.** `grep` for `AD-009|AD-010|AD-011` across the repository returns **no matches**; the decisions log in `PROJECT_PLAN.md` ends at `AD-008` (2026-09-05) and `AD-002d (provider)` (2026-09-06). So this report is filed as **AD-010**. [OBSERVED — repo-wide grep, 2026-09-07]

⚠ One thing the product owner should decide, not me: **AD-009 is also unused.** Taking AD-010 leaves a permanent hole at AD-009. If the log is meant to be dense, renumber to AD-009 before recording; nothing in this report depends on the number.

**Reading of the question I pursued** (it is ambiguous in one place): "how should the hostname be *provided*" is read as **the DNS and certificate mechanism for a single public API hostname on a bank-owned domain**, not as "what should the hostname be called" (explicitly out of scope) and not as "should the backend be exposed at all" (already true — CloudFront serves it publicly today). Readings I did **not** pursue: a split-horizon or in-country DNS arrangement (nothing in the plan files suggests one); a non-AWS CDN in front (AD-002d closed the provider); a per-environment hostname scheme beyond prod (staging already runs on `*.cloudfront.net` and is not release-baked).

---

## 1. Answer

**Ask the bank for one subdomain of `sfbank-sd.com`, delegated by NS record to a Route 53 public hosted zone created in the existing AWS account — option 2.** The bank performs exactly one DNS action, once, and is never asked again: not for certificate validation, not for renewal, not for the privacy-policy host, not for App Links, not for any future record. Everything after the NS record is self-service inside the account that is already being handed to the bank, so there is no cross-account transfer of anything at handover.

**The AWS-side change is small and does not touch the origin posture.** The internal ALB stays internal, the VPC origin stays as built, and no public ALB and no API Gateway is introduced. The entire change is adding `Aliases` and a `ViewerCertificate` (ACM, `us-east-1`) to the **existing** distribution, replacing the `{"CloudFrontDefaultCertificate": true}` currently set at `infra/aws/09-backoffice-cloudfront.sh:273`. [OBSERVED]

**One hostname, not two.** The closed AD-002d same-origin arrangement already puts the back office SPA and `/api/*` on one distribution; one alternate domain name therefore serves the mobile API, the back office and the privacy policy. A second hostname is possible but buys **no isolation** — CloudFront routes by `Host` header to the same distribution, so both names would serve both the SPA and the API. Recommend one name, and put the privacy policy on a path of it.

**The critical decoupling, and the most useful sentence in this report:** the choice between option 2 and option 3 **does not change the hostname string**. Only the *name* is decide-once and APK-baked; the delegation mechanism is reversible at any time with no rebuild and no store release. So the bank ask splits into a fast decision (the name — needed to unblock the BL-079 rebuild) and a slower operation (the DNS record — needed only before public release). Do not let the second block the first.

**Rejected:** option 1 (vendor-owned domain) on identity grounds before cost — a Sudanese bank's identity-document app must not resolve on a domain the bank does not own, and `.sd` cannot be registered through Route 53 at all. Option 3 (bank keeps all DNS) is the fallback, not the recommendation, only because it converts every future record into a bank ticket.

**Costs, all options:** ACM public certificate **$0** [DOC]; Route 53 hosted zone **$0.50/month** [DOC]; alias-record queries to CloudFront **$0** [DOC]. Total recurring cost of the recommendation ≈ **$6/year**.

---

## 2. Evidence

### 2.1 What exists today (local, observed)

- **The mobile app bakes exactly one base URL, and it is not HTTPS by default.** `mobile/lib/core/config/app_config.dart:5-10` declares a single `String.fromEnvironment('REFERENCE_API_BASE_URL', defaultValue: 'http://localhost:8080')`, and `mobile/lib/core/network/dio_provider.dart:9` uses it as the **only** Dio `baseUrl` in the app. Despite the name, this is the whole app's API address, not just reference data. [OBSERVED]
- **No certificate pinning exists.** Grep across `mobile/` for `badCertificateCallback|SecurityContext|HttpClientAdapter|setTrustedCertificates|sha256/` returns nothing. AD-001's rationale cites certificate pinning as a reason to be native, but none is implemented. This is load-bearing here: **it means an ACM-managed certificate that rotates automatically is safe today.** [OBSERVED]
- **The backend has no public address.** The ALB is internal; CloudFront reaches it through a VPC origin; `SG_ALB`'s internet-facing 443/80 ingress was revoked at S7-08. [OBSERVED — `docs/sessions/2026-09-06-aws-app-tier-and-acceptance.md:104-108`]
- **The distribution serves the SPA on the default behaviour and `/api/*` on a second behaviour, with the CloudFront default certificate.** [OBSERVED — `docs/components/cloudfront-vpc-origin.md`; `infra/aws/09-backoffice-cloudfront.sh:273`]
- **Production runs on the current account, which is then handed over.** `docs/road-to-production.md` §0.6 and §3.6 record the product-owner change to BL-072: no new bank-owned account is created for the main stack. **This is the fact that removes cross-account transfer from the problem entirely** — the hosted zone, the certificate and the distribution all travel with the account. [OBSERVED]
- Stack region is `eu-central-1`; CloudFront's control plane is `us-east-1`. [OBSERVED — `docs/components/cloudfront-vpc-origin.md`]

### 2.2 Route 53 domain registration and `.sd`

- **`.sd` cannot be registered or transferred through Route 53.** The supported geographic TLD list for Africa is exactly `.ac`, `.co.za`, `.sh`. AWS states: *"If the TLD isn't included, you can't register the domain with Route 53"* and *"You can transfer a domain to Route 53 if the TLD is included on the following lists."* [DOC — https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/registrar-tld-list.html, read 2026-09-07]
- **But `.sd` — or any TLD — can be *hosted* in Route 53.** *"You can use the Route 53 DNS service with any top-level domain you choose and with any domain registrar."* [DOC — same page] This is what makes option 2 work regardless of where `sfbank-sd.com` is registered, and would equally work if the bank later moved to a `.sd` name.
- **A vendor-registered domain would therefore be a gTLD** (`.com`, `.app`, etc.), not a Sudanese name. For a bank asking customers to photograph their passport, a hostname on a domain the bank does not own is a phishing-education problem the bank inherits forever, and it cannot be repaired without another APK and another store release.
- **Sanctions/registrant screening: not established.** The Route 53 Domain Name Registration Agreement contains **no** explicit country-eligibility, export-control or sanctions clause; the only adjacent language is a general right to terminate *"in order to comply with the law or requests of governmental entities"* (§10.4(C)). [DOC — https://aws.amazon.com/route53/domain-registration-agreement/, read 2026-09-07] Separately, Route 53 is documented as applying fraud/abuse checks that can block registration on newer accounts with a generic message removable only by a Support case. [Reputable secondary — AWS re:Post thread surfaced in search, not a doc page] **[UNVERIFIED — whether a Sudanese registrant or billing address is accepted for a Route 53 registration.]** What would settle it: an AWS Support case asking the Route 53 Domains team directly, before relying on it. **This does not gate the recommendation, because the recommendation registers nothing.**
- **`.sd` registry rules: conflicting secondary sources.** The registry is the Sudan Internet Society; commercial registrar pages disagree on whether local presence is required (101domain and Register.Domains describe a Sudanese-presence expectation; DomainWorld/Fugue describe none). **[UNVERIFIED]** Settled only by the Sudan Internet Society or an accredited registrar. Not on the critical path — the bank already owns a `.com`.

### 2.3 Certificates

- **The ACM certificate must be in `us-east-1`, regardless of where the stack runs.** *"To use an ACM certificate with a CloudFront distribution, make sure you request (or import) the certificate in the US East (N. Virginia) Region (`us-east-1`)."* [DOC — https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/cnames-and-https-requirements.html, read 2026-09-07] Our stack is `eu-central-1`; this is the single easiest step to get wrong.
- **ACM public certificates cost nothing.** *"Public certificate (non-exportable)" — "No cost."* [DOC — https://aws.amazon.com/certificate-manager/pricing/, read 2026-09-07]
- **DNS validation is one CNAME that must stay forever.** *"The CNAME records must be added to your DNS database only once. ACM automatically renews your certificate as long as the certificate is in use and your CNAME record remains in place."* And: *"You can stop automatic renewal either by removing the certificate from the AWS service with which it is associated or by deleting the CNAME record."* [DOC — https://docs.aws.amazon.com/acm/latest/userguide/dns-validation.html, read 2026-09-07]
- **The validation window is 72 hours.** *"If ACM is not able to validate the domain name within 72 hours from the time it generates a CNAME value for you, ACM changes the certificate status to Validation timed out ... you must request a new certificate."* [DOC — same page] **This is precisely "what breaks if the bank is slow" for option 3.** It is cheap to recover (request again, free) but it is a manual step that recurs each time the bank misses the window.
- **The validation token is per-domain and per-account.** *"Each record, created specifically for your domain and your account"* and *"you can request additional ACM certificates for your fully qualified domain name (FQDN) for as long as the CNAME record remains in place ... You can also replace a deleted certificate."* [DOC — same page] Because the account is handed over intact, the token stays valid across handover. If the bank ever rebuilds the stack in a *different* account, they need a new validation record — a bank DNS ticket at exactly the worst moment.
- ACM DNS validation depends on the record being **publicly resolvable**; a leading-underscore CNAME is required and some DNS providers mishandle it. [DOC — same page, "If your DNS provider does not support CNAME values with a leading underscore"]
- **[UNVERIFIED]** No AWS page enumerates TLDs ACM will issue for. Moot for `sfbank-sd.com` (`.com`); would need checking if a `.sd` name is ever chosen.

### 2.4 Attaching the name to the existing distribution

- **A certificate covering the name is how CloudFront proves you may claim it.** *"To add an alternate domain name (CNAME) to a CloudFront distribution, you must attach to your distribution a trusted, valid TLS certificate that covers the alternate domain name ... CloudFront checks the subject alternative name (SAN)"*, and *"Only one certificate can be attached to a CloudFront distribution at a time."* [DOC — https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/CNAMEs.html, read 2026-09-07]
- **Globally unique.** *"You cannot add an alternate domain name to a CloudFront distribution if the same alternate domain name already exists in another CloudFront distribution, even if your AWS account owns the other distribution."* [DOC — same page] Consequence for handover-adjacent work: you cannot stand up a replacement distribution carrying the same name in parallel; the name must be moved.
- **Both hostnames would serve everything.** *"CloudFront identifies a distribution for a HTTP request based on the `Host` header."* [DOC — same page] So adding a second alternate domain name for the back office on the same distribution yields two names for the identical set of behaviours — the operator SPA remains reachable on the customer API name. Separate hostnames are cosmetic here, not a boundary.
- **Apex names cannot be CNAMEs.** *"You can't create a CNAME record for the top node of a DNS namespace, also known as the zone apex; the DNS protocol doesn't allow it."* Route 53 alias records solve this; other DNS providers need Anycast static IPs. [DOC — same page] Irrelevant if the name is a subdomain (which it should be), decisive if anyone proposes pointing `sfbank-sd.com` itself at the distribution.
- **A wildcard SAN removes future certificate work.** *"You want to add marketing.example.com ... You list in your certificate `*.example.com` ... you can add any alternate domain name ... that replaces the wildcard at that level."* [DOC — same page] Under option 2, a `*.<delegated-subdomain>.sfbank-sd.com` certificate means every future host under the delegated zone needs no new certificate request and no bank involvement.

### 2.5 Cost, and one correction to the component card

- Route 53: *"$0.50 per hosted zone per month for the first 25 hosted zones"*; *"$0.40 per million queries"*; *"Queries for Alias records are provided at no additional cost"* when mapped to CloudFront. [DOC — https://aws.amazon.com/route53/pricing/, read 2026-09-07]
- **CloudFront flat-rate plans are opt-in, per distribution, and everything else stays pay-as-you-go.** *"If your hosted zone is not attached to your plan, it will remain on pay-as-you-go pricing"*; *"Your distribution and all associated plan resources will then switch to pay-as-you-go pricing"*; *"You can disable the unsupported feature and use an alternative option, or keep pay-as-you-go for your distribution."* [DOC — https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/flat-rate-pricing-plan.html, read 2026-09-07]
- **This resolves the `[UNVERIFIED]` cost note in `docs/components/cloudfront-vpc-origin.md`.** *"Private origins within VPC"* is a **row in the flat-rate plan feature matrix**, marked available on **Business and Premium only** — it is not a gate on pay-as-you-go, which is what this stack uses (the distribution was created by CLI with no plan, and a VPC origin has been running since S7-08). [DOC — same page, feature table; corroborated by https://aws.amazon.com/cloudfront/pricing/ read 2026-09-07] **Practical warning worth recording:** if the bank ever subscribes this distribution to the **Free ($0) or Pro ($15)** plan to simplify billing, **VPC origins are not included at those tiers** — the cheap-looking billing change would break the only path to the backend. Business is $200/month.
- Flat-rate plans list *"TLS certificate"* as included at every tier including Free, and *"Each pricing plan covers one CloudFront distribution with up to one apex (root) domain."* [DOC — same page]
- **[UNVERIFIED]** The current CloudFront pricing page no longer shows the historical "SNI Custom SSL: free / Dedicated IP Custom SSL: $600 per month" line, and I could not retrieve a verbatim statement that SNI custom SSL is free under pay-as-you-go. Nothing found suggests a charge, and the flat-rate tables include a TLS certificate at $0. Settle by reading the distribution's next bill after the alias is added, or by an AWS Support/pricing-calculator check — **do not assume $0 in a budget the bank signs.**
- **[UNVERIFIED]** `.com` registration price via Route 53 (published only in a linked PDF I did not fetch). Option 1 is rejected on other grounds, so this was not pursued.

### 2.6 Transfer procedures (only relevant if the recommendation is not taken)

- **Hosted zones do not transfer in place.** The documented migration is: create a new zone in the target account → export records with `list-resource-record-sets` → hand-edit the JSON (`Changes` element, delete NS/SOA, reorder alias records, fix alias hosted-zone IDs) → `change-resource-record-sets` → compare → **repoint the parent's NS records** → *"Don't delete the old hosted zone ... for at least 48 hours."* It also warns explicitly to check that no certificate renewal is mid-flight during the migration. [DOC — https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/hosted-zones-migrating.html, read 2026-09-07]
- **Domain registrations transfer between AWS accounts by a two-party handshake**, not automatically: *"Domains cannot be transferred within the first 14 days of registration"*; root or `AmazonRoute53DomainsFullAccess`-class permissions required; a password is passed out of band; *"You have three days to accept the request"*; and *"When you transfer a domain to a different AWS account, the hosted zone for the domain isn't transferred."* [DOC — https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/domain-transfer-between-aws-accounts.html, read 2026-09-07]
- **Subdomain delegation is a documented, ordinary procedure.** Create a hosted zone for the subdomain → add records → *"Add NS records for the subdomain to the zone file of the parent domain ... specify the four Route 53 name servers"*, with *"Do not add a start of authority (SOA) record to the zone file for the parent domain"* and *"Do not create extra name server (NS) or SOA records in the Route 53 hosted zone."* [DOC — https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/CreatingNewSubdomain.html, read 2026-09-07]

---

## 3. The options, costed against our constraints

| | **Option 0 — do nothing** (`*.cloudfront.net` in the APK) | **Option 1 — vendor-registered domain** | **Option 2 — RECOMMENDED: bank delegates one subdomain by NS** | **Option 3 — bank keeps all DNS, pastes records** |
|---|---|---|---|---|
| **Bank does** | Nothing | Nothing now; accepts a domain it does not own on its customer app | **One NS record, once.** Never asked again | **Two CNAMEs now** (ACM validation + host), and one more for every future host or new certificate; must never delete the validation record |
| **We do** | Nothing | Register + pay + hold the domain; later transfer domain (14-day lock, 3-day accept window) and separately migrate the zone | Create hosted zone; request wildcard ACM cert in `us-east-1`; ACM writes validation records into our own zone; add `Aliases` + `ViewerCertificate` to the existing distribution; create the alias A/AAAA record | Request ACM cert in `us-east-1`; send the bank two records; wait; add `Aliases` + `ViewerCertificate` |
| **At handover** | N/A | **Two separate transfers**, both manual, both with windows that expire; a mis-sequenced transfer strands the domain in a personal account | **Nothing to do.** Zone, cert and distribution are all in the account being handed over | **Nothing on our side** — but the bank now owns a validation CNAME whose purpose nobody at the bank remembers |
| **If the bank is slow** | Nothing breaks; the damage is permanent instead | Nothing breaks; we ship a wrong-owner hostname | Release slips. The APK can still be built and the name fixed — only public release waits | Release slips, **and** ACM validation times out after 72 h [DOC] and must be re-requested; repeatable each time |
| **Recurring cost** | $0 | Domain renewal (annual, ours until transferred) + $0.50/mo zone + $0 cert | **$0.50/mo zone + $0 cert ≈ $6/yr** | **$0** cash; recurring cost is paid in bank tickets |
| **Recurring manual steps** | 0 | Domain renewal, plus every DNS change | **0 after the NS record** — ACM renews itself against a zone we control | **1 bank ticket per future record**, indefinitely |
| **Exit cost if wrong** | **Another APK + another store release** | Another APK + another store release + an orphaned registration | Ask the bank to remove the NS record and paste records instead — **no rebuild, hostname unchanged** | Ask the bank for an NS record instead — **no rebuild, hostname unchanged** |

**Why option 0 is not a baseline, it is a defect.** `*.cloudfront.net` is AWS's name, not the bank's; no ACM certificate can be issued for it; and BL-074 already records that it *"cannot be moved, re-pointed, or survive replacing the distribution."* [OBSERVED — BACKLOG.md BL-074] Combined with the immutable-package-id constraint, shipping it makes the first release the last release on that address.

**Why option 1 is rejected before cost.** `.sd` is not registrable through Route 53 [DOC §2.2], so a vendor-registered domain is a gTLD with no relationship to `sfbank-sd.com`. A bank that trains customers to distrust unfamiliar domains would be asking them to photograph a passport on one. Add an unverified sanctions/registrant-screening question [UNVERIFIED §2.2] and two independent manual transfers at handover, and it loses on every axis the constraints name.

**Why option 3 is the fallback and not the answer.** It is genuinely fine on day one and costs nothing in cash. Its cost is structural: every subsequent DNS need — the privacy-policy host if it is not a path, App Links `assetlinks.json` verification if it ever moves, a second certificate after a distribution rebuild in a different account, a status page, an iOS association file — becomes a bank change request, executed by people with no context, against a validation record whose deletion silently kills TLS renewal ~13 months later with no warning. For a solo developer handing the system to a bank, "the bank must not delete this record, and here is why" is a worse artefact than "this subdomain is yours; do what you like inside it."

---

## 4. Conditions under which the recommendation flips

1. **The bank's DNS team refuses subdomain delegation.** Common where a central DNS policy forbids handing NS authority to a system team. → **Flip to option 3.** No hostname change, no rebuild, no rework of anything in this plan.
2. **`sfbank-sd.com`'s DNS is operated by a third party (ISP/hosting provider) that cannot create NS records or underscore CNAMEs.** → Flip to option 3, and if underscore CNAMEs are also impossible, ACM DNS validation is unusable and email validation is the only remaining route — which cannot auto-renew and is a materially worse position; say so loudly before accepting it. [DOC — ACM: *"If you lack authority to edit your domain's DNS database, you must use email validation instead"*]
3. **The bank decides the customer-facing name must be a `.sd` name it does not yet own.** → The recommendation's *shape* is unchanged (delegate a subdomain, host it in Route 53), but registration happens outside AWS and the ACM-for-`.sd` question [UNVERIFIED] must be settled first.
4. **The bank later requires the stack in a different AWS account** (reversing road-to-production §0.6). → Option 2 remains right, but the hosted zone migration and the alternate-domain-name move become real work [DOC §2.6]; budget it rather than discovering it.
5. **Certificate pinning is added to the mobile app** (AD-001 contemplates it; none exists today [OBSERVED]). → Nothing about the hostname flips, but pinning must target the CA/public key, never the leaf, because ACM rotates the leaf automatically and a leaf pin turns a silent renewal into a total outage that only a store release can fix.

---

## 5. Ordered actions

### The bank does

| # | Action | Evidence it is done |
|---|---|---|
| B1 | **Decide the hostname string** (a subdomain of `sfbank-sd.com`). This is the only decide-once item and it blocks the BL-079/BL-081 rebuild. Do not wait for B2. | Written confirmation from the product owner naming the exact FQDN, recorded in `PROJECT_PLAN.md` |
| B2 | **Create NS records in `sfbank-sd.com`** for that subdomain, pointing at the four name servers we supply. No SOA record. [DOC §2.6] | `dig NS <subdomain>.sfbank-sd.com` from outside AWS returns the four Route 53 name servers; and `dig` of a throwaway TXT record we create in the zone resolves publicly |
| B3 | **Publish the privacy policy text** (content, Arabic, bank-approved). Hosting is ours. | The approved text delivered; the page live at the agreed path |
| B4 | *(Option 3 fallback only)* Add the ACM validation CNAME **and** the host CNAME, within 72 h of us requesting the certificate. [DOC §2.3] | Both records resolve publicly; certificate reaches `ISSUED` |

### We do

| # | Action | Evidence it is done |
|---|---|---|
| W1 | Create a **public hosted zone** for the delegated subdomain in the existing account, `us-east-1`-independent (Route 53 is global). Do not create extra NS/SOA records inside it. [DOC §2.6] | `aws route53 get-hosted-zone` output showing the zone and its four name servers; the same four handed to the bank for B2 |
| W2 | After B2, **verify delegation** before anything else. | `dig +trace` / external resolver showing the subdomain answered by the Route 53 name servers, not by the parent |
| W3 | Request an **ACM public certificate in `us-east-1`** covering the host and a wildcard at that level (`<host>` + `*.<subdomain>.sfbank-sd.com`), DNS validation. [DOC §2.3, §2.4] | `aws acm describe-certificate --region us-east-1` → `Status: ISSUED`, `ValidationMethod: DNS`, SANs listed |
| W4 | Create the ACM validation records **in our own zone** (Route 53 console button, or `change-resource-record-sets`). No bank involvement. [DOC §2.3] | The `_x.<host>` CNAME present in the zone; certificate transitions `PENDING_VALIDATION` → `ISSUED` |
| W5 | Add `Aliases` + `ViewerCertificate` (ACM ARN, SNI) to the **existing** distribution, replacing `{"CloudFrontDefaultCertificate": true}`. **Do not create a second distribution** — the alternate domain name is globally unique and a parallel build cannot hold it. [DOC §2.4] [OBSERVED `infra/aws/09-backoffice-cloudfront.sh:273`] | `aws cloudfront get-distribution-config` showing the alias and the ACM ARN; distribution `Deployed` |
| W6 | Create the **alias A (and AAAA if IPv6 is on) record** to the distribution in our zone — alias, not CNAME, so queries are free. [DOC §2.5] | `curl -sv https://<host>/api/v1/...` from outside AWS returns the backend's response over a certificate whose SAN is `<host>` and whose issuer is Amazon |
| W7 | **Publish the privacy policy** on the same hostname, at a path served by the existing S3 origin. Requires its own cache behaviour or an object the SPA-fallback CloudFront Function does not rewrite — the function currently rewrites unknown URIs to `/index.html` on the default behaviour. [OBSERVED — `docs/components/cloudfront-vpc-origin.md`] | `curl -i https://<host>/privacy` returns `200` with the policy HTML, **not** the SPA shell |
| W8 | **Re-prove BL-066** after the alias is live: the CloudFront→ALB hop is still HTTP, so `X-Forwarded-Proto: http` is unchanged and the explicit `Secure` cookie settings must still hold. [OBSERVED — BL-066, `docs/components/cloudfront-vpc-origin.md`] | `Set-Cookie: JSESSIONID=...; Secure; HttpOnly` and `XSRF-TOKEN=...; Secure` observed on a real sign-in **through the new hostname** |
| W9 | Rebuild the APK with `--dart-define=REFERENCE_API_BASE_URL=https://<host>` in the same rebuild as BL-079/BL-081. Note this is currently the app's **only** base URL and its default is plaintext `http://localhost:8080`. [OBSERVED `app_config.dart:5-10`, `dio_provider.dart:9`] | A device run against the new hostname completing at least Stage 1b, plus the built APK's define recorded in the session report |
| W10 | Add to the handover runbook: **(a)** the ACM validation record must never be deleted; **(b)** subscribing this distribution to the Free or Pro flat-rate plan removes VPC origins and breaks the API path. [DOC §2.5] | The two lines present in the handover document alongside BL-073's event-trigger step |

---

## 6. What I could not determine

1. **Whether AWS accepts a Sudanese registrant/billing address for a Route 53 domain registration.** No AWS page states a country restriction; the registration agreement has only a general legal-compliance termination clause. Settled by an AWS Support case to the Route 53 Domains team. Does not gate the recommendation, which registers nothing.
2. **Whether `.sd` imposes a local-presence requirement.** Registrar pages contradict each other. Settled by the Sudan Internet Society or an accredited registrar.
3. **Who operates DNS for `sfbank-sd.com` today, and whether that provider supports NS delegation of a subdomain and CNAMEs with a leading underscore.** This is the single largest unknown in the plan, and it decides options 2 vs 3. I did not query public DNS or WHOIS — no shell in this session, and the reading is worth doing properly rather than through a third-party web lookup. **One `dig NS sfbank-sd.com` answers it.**
4. **Whether CloudFront charges anything for SNI custom SSL under pay-as-you-go in 2026.** The historical free-SNI/$600-dedicated-IP text is no longer on the pricing page. Nothing suggests a charge; nothing confirms $0.
5. **Whether the existing distribution is on pay-as-you-go.** Inferred from CLI creation with no plan and a working VPC origin, not read from the account. `aws cloudfront list-distributions` / the billing console would confirm.
6. **The exact lead time the bank's DNS process imposes.** This determines whether the 72-hour ACM window is a nuisance or a repeated failure, and therefore how strongly option 3 loses.
7. **Whether Google Play will accept a privacy-policy URL on the same host as an operator login page.** No reason it would not, but I found no policy statement either way and did not assume one.

---

## 7. Risks

| Risk | What breaks | Cost to reverse |
|---|---|---|
| The hostname string is wrong (wrong subdomain, wrong domain, apex chosen) | The APK is stranded; every installed copy points at a dead name | **A new APK and a new store release.** For an apex specifically, also a DNS architecture change (alias-only, or Anycast static IPs) [DOC §2.4]. This is the only truly expensive mistake here |
| The certificate is requested in `eu-central-1` | CloudFront silently will not accept it; the alias cannot be added | Minutes — request again in `us-east-1`, free [DOC §2.3] |
| The bank deletes the ACM validation CNAME after handover (option 3 especially) | TLS renewal fails at ~13 months; **the customer app stops working with no prior warning and no server-side error** | A bank DNS ticket under outage pressure, plus ACM re-validation. Under option 2 this cannot happen — the record is in a zone the account owns |
| The bank subscribes the distribution to the Free/Pro flat-rate plan | **VPC origins are not included at those tiers** [DOC §2.5]; the `/api/*` path to the backend breaks | Cancel the plan (reverts to pay-as-you-go at the next billing cycle) or upgrade to Business at $200/month |
| Someone builds a replacement distribution carrying the same alternate domain name in parallel | Refused — the name is globally unique across all AWS accounts [DOC §2.4] | Move the name (brief outage) or plan a cutover; not recoverable by "build the new one first" |
| Certificate pinning is added later against the ACM leaf | An automatic renewal bricks every installed app | A new APK and a new store release. Prevent by pinning the CA/SPKI, or not at all |
| Option 1 is chosen and the domain never transfers cleanly | The bank's customer-facing hostname stays under a personal AWS account — **the exact exposure BL-072 exists to close**, relocated to DNS | Domain transfer (14-day lock, 3-day accept) plus a hosted-zone migration [DOC §2.6], under time pressure |
| The privacy-policy path is swallowed by the SPA history-fallback function | Play submission blocked; the URL returns the back-office shell with `200` | A behaviour or function change; found in seconds by `curl`, missed forever if nobody looks |

---

## 8. Card updates

A card exists: **`docs/components/cloudfront-vpc-origin.md`**. Three edits, all marked.

**(a) Correct the "Why this project uses it" paragraph** — the second sentence becomes false once AD-010 is executed:

> Why this project uses it: ACM will not issue a certificate for an `*.elb.amazonaws.com` name, and no bank-owned domain exists yet (BL-074). A public ALB could therefore only serve plaintext. With a VPC origin the backend has no public address at all, and the CloudFront→ALB hop stays on AWS's network. This is the production shape, not a staging stand-in; adding a domain later adds an alternate domain name and certificate to the *same* distribution.
> **`[AD-010, 2026-09-07]` The domain question is now decided and does not change this shape. A bank-owned subdomain of `sfbank-sd.com` is delegated by NS to a Route 53 hosted zone in this account; the ALB stays internal, the VPC origin is untouched, and the only change is `Aliases` + `ViewerCertificate` on this distribution. No public ALB and no API Gateway is introduced. `[DOC CNAMEs.html, cnames-and-https-requirements.html]`**

**(b) Replace the `## Cost` section's `[UNVERIFIED]` block** — it is now resolved:

> No charge for the VPC origin itself, and none for the ENI's private IPv4.
> `[DOC AWS News Blog launch post, private-content-vpc-origins.html]`
> **`[DOC flat-rate-pricing-plan.html, read 2026-09-07 — resolves the previous [UNVERIFIED]]` "Private origins within VPC" is a row in the CloudFront *flat-rate pricing plan* feature matrix, available on **Business and Premium only**. It is NOT a gate on pay-as-you-go pricing, which is what this distribution uses. Flat-rate plans are opt-in per distribution: *"If your hosted zone is not attached to your plan, it will remain on pay-as-you-go pricing"* and cancelling a plan returns the distribution to pay-as-you-go.**
> **⚠ HANDOVER WARNING: subscribing THIS distribution to the Free ($0) or Pro ($15/month) plan to simplify billing would remove VPC origins and break the only path to the backend. Business is $200/month. `[DOC same]`**
> Traffic does not traverse the NAT gateway, so no NAT data-processing charge applies to it.

**(c) Add a new section** after "Distribution":

```markdown
## Custom domain (AD-010, 2026-09-07)

- ACM certificate MUST be requested in `us-east-1`, not `eu-central-1` where the rest of
  the stack lives. `[DOC cnames-and-https-requirements.html]` Public ACM certificates cost
  nothing. `[DOC aws.amazon.com/certificate-manager/pricing]`
- The alternate domain name must be covered by the certificate's SAN; only one certificate
  can be attached to a distribution at a time. A `*.<subdomain>` wildcard SAN lets any
  future host under the delegated zone be added with no new certificate request.
  `[DOC CNAMEs.html]`
- An alternate domain name is UNIQUE ACROSS ALL AWS ACCOUNTS. You cannot stand up a
  replacement distribution holding the same name in parallel — it must be moved.
  `[DOC CNAMEs.html]`
- CloudFront routes by `Host` header, so every alternate domain name on this distribution
  serves BOTH the back-office SPA and `/api/*`. A second hostname gives no isolation.
  `[DOC CNAMEs.html]`
- Zone apex cannot be a CNAME. Use a Route 53 alias record (also free for queries), or a
  subdomain. `[DOC CNAMEs.html, aws.amazon.com/route53/pricing]`
- ACM DNS validation: the CNAME must remain in DNS FOREVER — deleting it stops automatic
  renewal, which fails silently ~13 months later. Validation times out after 72 hours if
  the record is not published. `[DOC acm/dns-validation.html]`
- Adding the domain does NOT change the CloudFront→ALB hop, which stays HTTP. BL-066's
  explicit `Secure` cookie settings are still required, and must be re-proven through the
  new hostname. `[OBSERVED BL-066, S7-08]`
- Route 53 hosted zone for the delegated subdomain: $0.50/month; alias queries to
  CloudFront free. `[DOC aws.amazon.com/route53/pricing]`
- `[UNVERIFIED]` Whether SNI custom SSL carries any charge under pay-as-you-go in 2026 —
  the historical "SNI free / dedicated IP $600" text is no longer on the pricing page.
```

No new card is drafted: this is not an SDK investigation, and the territory belongs to an existing card.

---

## 9. Noticed in passing

- **`AppConfig.referenceApiBaseUrl` is misnamed.** It is the whole app's only Dio base URL (`dio_provider.dart:9`), not a reference-data URL, and its `--dart-define` key is `REFERENCE_API_BASE_URL`. Whoever does the BL-079 rebuild will set a define whose name says the wrong thing. Cheap to rename in the same session; not filed by me. [OBSERVED]
- **The default base URL is `http://`, cleartext.** Worth confirming during the release build that Android's cleartext-traffic posture (`usesCleartextTraffic` / network security config) does not permit plaintext in the release variant — a fat-fingered define would then fail loudly instead of silently shipping PII over HTTP. Not investigated further. [OBSERVED]
- **No certificate pinning exists anywhere in `mobile/`**, though AD-001's rationale for choosing native over PWA cites pinning as one of the three capabilities a browser cannot provide. That gap is tracked nowhere I could find. [OBSERVED — grep for `badCertificateCallback|SecurityContext|HttpClientAdapter|setTrustedCertificates|sha256/` returns nothing]
- **`docs/road-to-production.md` §0.1** frames the domain ask as one combined request including privacy policy, app name and package id. That framing is right, and this report's B1 (the *name*, needed fast) versus B2 (the *record*, needed before release) split is the one refinement I would make to it — the combined ask currently reads as though nothing can start until all four land.

**Sources:**
- [Domains that you can register with Amazon Route 53](https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/registrar-tld-list.html)
- [Amazon Route 53 Pricing](https://aws.amazon.com/route53/pricing/)
- [Requirements for using SSL/TLS certificates with CloudFront](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/cnames-and-https-requirements.html)
- [Use custom URLs by adding alternate domain names (CNAMEs)](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/CNAMEs.html)
- [AWS Certificate Manager DNS validation](https://docs.aws.amazon.com/acm/latest/userguide/dns-validation.html)
- [AWS Certificate Manager Pricing](https://aws.amazon.com/certificate-manager/pricing/)
- [Creating a subdomain that uses Route 53 without migrating the parent domain](https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/CreatingNewSubdomain.html)
- [Migrating a hosted zone to a different AWS account](https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/hosted-zones-migrating.html)
- [Transferring a domain to a different AWS account](https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/domain-transfer-between-aws-accounts.html)
- [CloudFront flat-rate pricing plans](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/flat-rate-pricing-plan.html)
- [Amazon CloudFront Pricing](https://aws.amazon.com/cloudfront/pricing/)
- [Amazon Route 53 Domain Name Registration Agreement](https://aws.amazon.com/route53/domain-registration-agreement)
- [.sd Domain Registration — 101domain](https://www.101domain.com/sd.htm) (secondary, conflicting)
- [What is a .sd Domain? — DomainWorld](https://domainworld.com/domains-world-tld-blog/tld-sd.html) (secondary, conflicting)
- [Route 53 Domain Registration Restricted on Root User Account — AWS re:Post](https://repost.aws/questions/QUaBwlmFY4RvGhbzYV0fv_cg/route-53-domain-registration-restricted-on-root-user-account-cannot-register-domain) (secondary)

---

## 10. Commit proof

The report was committed on its own; the unrelated in-flight messaging/Airtel work and
the concurrently-modified plan files in the working tree were deliberately left unstaged.

```
$ git log --oneline -1
040d059 docs: research report for AD-010 — mobile API public hostname on AWS

$ git status
On branch main
Your branch is up to date with 'origin/main'.

Changes not staged for commit:
  (use "git add <file>..." to update what will be committed)
  (use "git restore <file>..." to discard changes in working directory)
	modified:   BACKLOG.md
	modified:   EXECUTION_PLAN.md
	modified:   PROJECT_PLAN.md
	modified:   RISKS.md
	modified:   backend/src/main/java/sd/gov/bank/fruserupdate/messaging/config/MessageSenderConfiguration.java
	modified:   backend/src/main/resources/application.properties
	modified:   backend/src/test/java/sd/gov/bank/fruserupdate/messaging/config/MessageSenderConfigurationTest.java
	modified:   docs/components/messaging.md
	modified:   docs/road-to-production.md

Untracked files:
  (use "git add <file>..." to include in what will be committed)
	backend/src/main/java/sd/gov/bank/fruserupdate/messaging/airtel/
	backend/src/test/java/sd/gov/bank/fruserupdate/messaging/airtel/
	backend/src/test/java/sd/gov/bank/fruserupdate/messaging/config/MessageSenderConfigurationTestAccess.java

no changes added to commit (use "git add" and/or "git commit -a")
```

`git push origin main` → `666b34c..040d059  main -> main`.

This section itself lands in a follow-up commit, since the proof above could only be
captured after the report commit existed.
