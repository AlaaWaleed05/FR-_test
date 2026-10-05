# How does the back office address an identity image? (R-046)

Type: grilling
Status: resolved
Blocked by: —

## Question

`R-046` is live and explicitly unsettled: an operator must be able to see `doc_front`,
`portrait_uqudo` and `portrait_registry`, viewing an image must be its own audit event, and
`<img>` cannot carry an `Authorization` header. The row frames the choice as **signed URL vs
authenticated blob fetch**, and warns that a signed URL fires the audit event when the URL is
*issued*, not when the image is *seen*, and can be redeemed later, twice, or by another session.

**The premise may have moved.** `R-046` was written before `S7-08` settled the deployment origin,
and nothing went back to re-read it. As charted (`infra/aws/09-backoffice-cloudfront.sh`), the
back office is **same-origin with the API**: one CloudFront distribution, default behaviour → the
private S3 bucket via OAC, `/api/*` → the internal ALB via a CloudFront VPC origin. The `/api/*`
behaviour uses **CachingDisabled** (`4135ea2d-…`) and the **AllViewer** origin request policy
(`216adef6-…`), so it caches nothing and forwards the session cookie to the origin.

That matters because this system never used `Authorization` headers — it uses a `JSESSIONID`
session cookie. A same-origin `<img src="/api/v1/operator/profiles/{id}/artifacts/{artifactId}">`
carries that cookie automatically. R-046's dilemma may simply not arise.

Settle, with reasons on the record:

1. Is the same-origin cookie-authenticated `GET` the answer, or is there a reason it is not?
2. If it is: what stops the browser serving a second view from its own cache and skipping the
   audit event? (`Cache-Control: no-store` on the response is the candidate; CloudFront already
   caches nothing, but the browser is a separate cache.)
3. Does the answer survive a later move to bank hosting without a rebuild?
4. `storage_key` on `app.artifact_ref` is described as "reserved for R-046's still-open addressing
   scheme". What happens to it — used, or finally dropped?

## Context

- `RISKS.md` R-046 · `BACKLOG.md` BL-075 · `PROJECT_PLAN.md` AD-004, AD-002d
- `infra/aws/09-backoffice-cloudfront.sh` · `backend/…/auth/config/SecurityConfiguration.java`
- `V0054__app_artifact_read_function.sql` — `app.artifact_read()` verifies the SHA-256 on read and
  refuses a mismatch outright; it is the only sanctioned way to read bytes back.

## Answer

Product-owner decision, 2026-09-13, taken on the recommendation below. **This closes `R-046`.**

### The decision

**A cookie-authenticated `GET` on the operator API, rendered straight into an `<img>`. No signed
URLs, no blob plumbing, no new key material.**

`R-046`'s premise does not hold on this deployment, and that is the whole of the answer. The row is
built on "an `<img>` tag cannot carry an `Authorization` header" — true, but this system has never
used `Authorization` headers for the back office. It uses a `JSESSIONID` session cookie, and
`S7-08` made the back office **same-origin with the API**: one CloudFront distribution, default
behaviour to the private S3 bucket via OAC, `/api/*` to the internal ALB over a CloudFront VPC
origin (`infra/aws/09-backoffice-cloudfront.sh`). A same-origin `<img src="/api/v1/operator/…">`
carries that cookie by itself. `R-046` was written before that origin was settled and nothing went
back to re-read it.

### Why this is the secure option, not merely the easy one

1. **The audit stays honest.** Every view is an origin request, so the event fires when the image is
   *seen*. That is the exact property `R-046` says a signed URL loses — it fires at issue time and
   stays redeemable later, twice, or from another session, while the audit record says otherwise.
2. **No second copy of the PII.** Bytes live in Postgres (`AD-004`). A CloudFront signed-URL scheme
   would mean copying identity documents into S3 — a second store of passport images at rest with
   its own encryption, lifecycle, replication and deletion story. A data-protection regression
   bought for nothing.
3. **Nothing is cached in front of it.** The `/api/*` behaviour already uses the **CachingDisabled**
   cache policy (`4135ea2d-…`) and the **AllViewer** origin request policy (`216adef6-…`), so
   CloudFront holds no image bytes at any edge and forwards the cookie to the origin.
4. **Revocation is immediate.** `auth.web.OperatorIdentityFilter` re-reads the account per request,
   so disabling someone 403s their next image. An already-issued signed URL keeps working until it
   expires, whatever is done to the account.
5. **It survives the move to bank hosting.** A cookie-authenticated endpoint is portable to any
   host. CloudFront signed URLs are AWS-specific — a key group and a private key to store and
   rotate — and would be thrown away at the migration the product owner has deliberately deferred.

### Binding requirements on whoever builds `BL-075`

The risk on this feature is not transport. It is these, and the build is not done without them:

- **`Cache-Control: no-store, private` on the image response.** CloudFront caches nothing, but the
  browser is a separate cache, and an `<img>` re-rendered from it never reaches the origin — a
  silently missed audit event. This is the one that turns a correct decision into a broken one.
- **Authorize per artifact, per profile.** The handler must verify the artifact belongs to the
  profile in the path. Without it an operator can walk artifact ids across other customers. This is
  the real vulnerability class here and it is unrelated to how the URL is addressed.
- **Refuse `doc_front_frame` / `doc_back_frame`.** `AD-004` stores no bytes for them, yet
  `ArtifactRefView` lists them today. They must 404, never return an empty body. (`BL-075` carries
  this requirement already — read that row in full.)
- **Read through `app.artifact_read()`** (`V0054`), which verifies the stored SHA-256 and refuses a
  mismatch outright rather than returning bytes a caller would trust.
- **Pin the content type**: the stored MIME from an allow-list, `Content-Disposition: inline`,
  `X-Content-Type-Options: nosniff`.
- **Keep the artifact id in the `<img src>`, not in the SPA's address bar**, so it stays out of
  browser history and out of any referrer.

### Consequences

- **`R-046` is closed** and its status moves to ✅ in `RISKS.md`.
- **`BL-075` is unblocked.**
- **`app.artifact_ref.storage_key` has no remaining purpose.** `AD-004`'s closure narrowed it to
  "reserved for R-046's still-open addressing scheme", and that scheme turns out to need no stored
  key at all — the artifact's own id addresses it. It is **not** dropped in this decision: dropping
  a column is its own migration with its own review, and nothing is gained by rushing it. It is
  recorded here as dead, so the next reader does not infer a meaning from its presence.
- **Residual, unfixable and accepted:** an operator who may see an image may screenshot or save it.
  The control is the audit trail, not prevention — which is precisely why the audit event must fire
  at view time.
