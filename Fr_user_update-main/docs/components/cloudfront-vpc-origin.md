# CloudFront VPC origin — back office + API on one distribution

Written at S7-08 from `@agent-researcher` findings against AWS's own documentation, per
CLAUDE.md's rule against integrating from documentation alone. Markers preserved: `[DOC]` is
verified against the named AWS page; `[UNVERIFIED]` is not established and must not be built
on without checking.

## What it is

A CloudFront-managed path from the edge to an **internal** ALB in a private subnet. CloudFront
creates a service-managed elastic network interface **in our private subnet** and consumes one
private IPv4 address from it — connectivity is not entirely outside our address space.
`[DOC private-content-vpc-origins.html]`

Why this project uses it: ACM will not issue a certificate for an `*.elb.amazonaws.com` name,
and no bank-owned domain exists yet (BL-074). A public ALB could therefore only serve
plaintext. With a VPC origin the backend has no public address at all, and the
CloudFront→ALB hop stays on AWS's network. This is the production shape, not a staging
stand-in; adding a domain later adds an alternate domain name and certificate to the *same*
distribution.

## Access control — the rule that matters

Inbound on the ALB security group: **TCP 80 from the security group named
`CloudFront-VPCOrigins-Service-SG`, referenced as a source security group.** AWS creates it
automatically when the VPC origin is created, manages it, and it must not be edited. It is the
only option that restricts traffic to *our* distributions. `[DOC private-content-vpc-origins.html]`

The alternative AWS documents — the managed prefix list
`com.amazonaws.global.cloudfront.origin-facing` — is valid but **weaker**: it admits any
CloudFront distribution in any AWS account. For an API that serves identity documents that is
the wrong boundary. There is no VPC-origin-specific prefix list; the origin-facing one is the
public-origin list. Use it only as a bridge before the origin deploys, and revoke it after.
`[DOC LocationsOfEdgeServers.html]`

**Ordering, and it is forced:** create the VPC origin → wait for `Deployed` → look up the
service SG → add the SG-to-SG rule → revoke the placeholder. The group does not exist before
the origin does, which is why `infra/aws/09-backoffice-cloudfront.sh` narrows the group at the
end rather than `08` doing it up front.

## Two prerequisites that bite

**The VPC must keep an internet gateway.** It is a required flag denoting that the VPC can
receive traffic from the internet; it routes nothing for this path and no route table changes
are needed. Do not delete it as "unused". `[DOC private-content-vpc-origins.html]`

**NACLs are asymmetric.** Inbound NACL rules are *not* evaluated for CloudFront→origin
traffic, but outbound rules *are* evaluated on the return path and must allow ephemeral TCP
1024–65535. Our app subnets use the default allow-all NACL, so this is satisfied today — but a
later "tighten the NACLs" change would break the site with no inbound-side symptom.
`[DOC private-content-vpc-origins.html]`

## Resource shape

`aws cloudfront create-vpc-origin --vpc-origin-endpoint-config`. All of `Name`, `Arn`,
`HTTPPort`, `HTTPSPort`, `OriginProtocolPolicy` are **required** — including both ports even
when only one is used. `Arn` is the **ALB ARN**. `OriginProtocolPolicy: http-only` with
`HTTPPort: 80` is documented and valid, so **no HTTPS listener and therefore no certificate
and no domain are needed**. `[DOC API_VpcOriginEndpointConfig.html, private-content-vpc-origins.html]`

CloudFront is global: its control plane is `us-east-1`, so these calls use that region while
the ALB and everything else stay in `eu-central-1`.

**Editing a VPC origin is not in place.** The documented procedure is to detach it from
*every* distribution, edit, wait for `Deployed` again, then re-attach. Treat port and protocol
as get-it-right-first-time — the same class as S7-03's at-creation-only RDS encryption and
locale, though reversible at a cost rather than irreversible. `[DOC same]`

`Status` values: `Deploying` then `Deployed`. `Deployed` was the only value AWS documents;
**`Deploying` was OBSERVED live at S7-08** while this stack's origin was created, which closes
the researcher's `[UNVERIFIED]` on the state list to that extent. Creation is documented as
taking up to 15 minutes and did.

Region `eu-central-1` is supported. Quotas: 25 VPC origins per account, 50 distributions per
origin, 100 origins and 75 cache behaviours per distribution. Not supported with VPC origins:
gRPC, and Lambda@Edge origin-request/origin-response triggers.
`[DOC private-content-vpc-origins.html, cloudfront-limits.html]`

## Distribution

The origin uses `VpcOriginConfig` **only**, not `CustomOriginConfig` — the three origin config
blocks are alternatives for one slot. `DomainName` is still a required field.
`[DOC API_Origin.html]` `[UNVERIFIED — what value it must hold for an ALB VPC origin. This
stack passes the internal ALB's DNS name; confirm against a working distribution.]`

`/api/*` behaviour: `CachePolicyId 4135ea2d-6df8-44a3-9df3-4b5a84be39ad` (`CachingDisabled`)
and `OriginRequestPolicyId 216adef6-5c7f-47e4-b989-5492eafa07d3` (`AllViewer`), which forwards
`Cookie`, `Authorization` and the CSRF header. **All seven methods must be allowed** — the
default two-method set would break every POST in the customer journey. Do **not** use
`AllViewerExceptHostHeader`; AWS scopes that to API Gateway and Lambda function URL origins.
`[DOC using-managed-cache-policies.html, using-managed-origin-request-policies.html, API_CacheBehavior.html]`

Timeouts: read 1–120 s (default 30), keep-alive 1–300 s (default 5). Relevant here — the
operator XLSX export is generated server-side and must complete inside `OriginReadTimeout`;
this stack sets 60 s. `[DOC API_VpcOriginConfig.html]`

## HARD RULE — no `CustomErrorResponses` on this distribution

`CustomErrorResponses` is a `DistributionConfig` field. `CacheBehavior` has **no** equivalent,
and CloudFront looks the mapping up from *"your distribution configuration"* regardless of
which behaviour served the failing request. A `403/404 → /index.html (200)` mapping — the
conventional SPA history fallback — **would rewrite genuine API 403s and 404s from the ALB**
into an HTML page with status 200, silently breaking the back office's auth handling: the
browser would see `200 text/html` where the code expects `403 application/json`, and a
session-expiry redirect would never fire.
`[DOC API_DistributionConfig.html, API_CacheBehavior.html, HTTPStatusCodes.html]`

The history fallback is done instead with a **`viewer-request` CloudFront Function attached to
the default behaviour only**. `FunctionAssociations` *is* per-behaviour, and AWS states that
when a function changes `uri` it *"doesn't change the cache behavior for the request or the
origin that an origin request is sent to"* — so the rewrite cannot leak into the API origin.
`[DOC API_CacheBehavior.html, functions-event-structure.html]`

The S3 static-website-endpoint alternative is forbidden: a website endpoint must be a custom
origin and therefore cannot use OAC, which forces a public bucket.
`[DOC private-content-restricting-access-to-s3.html]`

**CLI note learned the hard way:** `create-function --function-code` is a *blob* parameter and
requires `fileb://`. With `file://` the CLI sends the JavaScript source as a literal string and
fails with an unhelpful error.

## Trap — `X-Forwarded-Proto` and BL-066

ELB stores *"the protocol used between the client and the load balancer"* in
`X-Forwarded-Proto`. `[DOC elasticloadbalancing/x-forwarded-headers.html]` CloudFront is the
ALB's client and connects over HTTP, so the ALB emits `X-Forwarded-Proto: http`, and Spring
Boot honouring `X-Forwarded-*` concludes the request is plaintext — issuing `JSESSIONID` and
`XSRF-TOKEN` **without `Secure`**. That is exactly the defect BL-066 exists to prevent, and
`server.forward-headers-strategy` alone does not fix it.

Two candidate fixes: set the cookie attributes explicitly rather than deriving them, or give
the ALB an HTTPS listener (which needs a certificate, which needs the domain we do not have).
Whichever is chosen, **assert `Secure` on both cookies after a real sign-in through
CloudFront** — through the ALB directly the test would pass for the wrong reason.

`[UNVERIFIED — whether the ALB overwrites or preserves an inbound X-Forwarded-Proto. AWS
documents the value it sets but not the overwrite rule; only X-Forwarded-For has a documented
processing mode attribute.]`

## Cost

No charge for the VPC origin itself, and none for the ENI's private IPv4.
`[DOC AWS News Blog launch post, private-content-vpc-origins.html]`
`[UNVERIFIED — the pricing page lists "Private Origins Within VPC" under the Business/Premium/
Custom flat-rate plans; whether that gates the feature on those plans was not established.
Resolve before assuming zero cost on whatever plan the account is on.]`

Traffic does not traverse the NAT gateway, so no NAT data-processing charge applies to it.
