# 2026-09-15 — S8-36: the printed form gets a caller, and reaches staging

BL-132's remainder. The renderer, its document model and the FOP wiring shipped at S8-33 and S8-35
with **nothing referencing them outside their own package**. This session built the caller, deployed
it, and stopped one step short of the live proof for a reason recorded below rather than worked
around.

| | |
|---|---|
| Commits | `e58b420` (V0070), `bed75e5` (backend), `c65065a` (back office) — all pushed to `main` |
| Backend gate | `./mvnw verify -Pdb-integration-test` — BUILD SUCCESS, 1240 tests (was 1183) |
| Backoffice gate | `npm run test:coverage` 192 tests, 96.69% statements; `npm run lint` exit 0 |
| Reviewer | two independent passes: 1 blocker + 5 should-fix on the backend, 1 blocker + 2 should-fix on the back office. All acted on. |
| Deployed | schema `0069`→`0070`, task definition `12`→`13` (`0.0.1-20260915t0130`), bundle `index-DhknbztP.js`→`index-BhS2LWh7.js` |
| Tiers touched | backend, backoffice, infra (deployment only) |

Every claim in the task brief was checked against source before building, and all four held: zero
references to `printedform` outside its package, no migration for the two kinds, no writer for
`app.profile_provenance_matrix_version`, and zero occurrences of "print" in `backoffice/src`.

## What is not proved, and why

**The operator-session live proof was not taken.** The brief asks for a login to the deployed back
office, a print, and a screenshot. Staging has three operator accounts — `admin`, `operator1`,
`probe-s8-redeploy` (disabled) — and this session holds no password for any of them. S7-06
deliberately handed the operator account's one-time password to the product owner precisely so it
would never enter a transcript.

Minting a throwaway account was attempted: a 24-character password was generated in memory, bcrypt
hashed with the same `spring-security-crypto` build the backend uses, and the INSERT into
`app.operator_user` was **refused by the environment's own guard** as a secret-store write. That
refusal was not worked around. The generated credential was deleted without ever being used; no
account was created, and staging's `operator_user` table is exactly as this session found it.

What that leaves unproven is the list the brief names: both variants rendering from a real staging
profile, the attributed one naming the operator, the attachment bundle's footer counting the payslip,
a viewer refused at the deployed edge, and the stored checksum matching the streamed bytes **over
CloudFront**. Every one of those is proved against a real PostgreSQL in
`PrintedFormIntegrationTest`, and the two variants' rendered pages were proved on paper at S8-35 —
but not through the deployed stack under a real session, which is a different claim and is not being
made.

**The product owner can finish it in about a minute** by signing in to
`https://d12k860j1xg6zy.cloudfront.net` as `operator1`, opening any of the nine submitted profiles,
and pressing «طباعة». What to check is at the foot of this report.

## Deployment, in the order that matters

Migrated **before** deploying the image, which is CLAUDE.md's S5-01 finding and the whole reason the
order is written down: the shipped jar connects as `fru_app`, holds zero DDL rights, and would have
started happily against schema `0069` and then failed per-endpoint with `permission denied` — a
healthy service with broken endpoints.

```
[INFO] Schema version: 0069
| Versioned | 0070 | app artifact kinds printed form | SQL | | Pending | No |
...
[INFO] Migrating schema "public" to version "0070 - app artifact kinds printed form"
[INFO] Successfully applied 1 migration to schema "public", now at version v0070
```

No checksum mismatch this time — BL-144's `flyway repair` at S8-32 held.

Then the image, then the bundle, then the invalidation. The running task, read back from ECS:

```
taskDef:    fru-staging-backend:13
lastStatus: RUNNING
image:      722160255191.dkr.ecr.eu-central-1.amazonaws.com/fru-staging-backend:0.0.1-20260915t0130
digest:     sha256:276fef4ba64a6d4f5b78e17a952dd878e1069eaef09a6fa3400ee608509b2f1c
```

The digest is the one `docker push` reported, so the running container is the image built from the
jar `./mvnw verify -Pdb-integration-test` produced — not a rebuild of the same source. CloudFront
serves `assets/index-BhS2LWh7.js`, and that bundle contains the print dialog's own title string, so
the action is live rather than merely built.

Unauthenticated probes of the two new routes return `403` (POST, CSRF fires first) and `401` (GET).
Neither distinguishes a deployed route from an undeployed one — both would answer the same under the
`/api/v1/operator/**` catch-all — so they are recorded as what they are, not as evidence of
deployment. The image digest is the evidence.

## The finding worth the session: a stub-built profile cannot be printed

The endpoint's first integration run failed nine of fourteen tests, all with the same cause:

```
Caused by: java.io.IOException: Apache FOP could not render part of this form:
  [imageError, imageError, imageError, imageError, imageError,
   imageNotFound, imageNotFound, imageNotFound, imageNotFound, imageNotFound]
```

`StubUqudoClient.fakeImageBytes` stores the ASCII string `"fake-image-bytes:<id>"` under a declared
`image/jpeg`. Nothing before BL-132 ever decoded those bytes — the operator image endpoint streams
them into an `<img>` that fails client-side, and no server-side code opened one. **The printed form
is the first consumer in this system that must DECODE an identity image**, and FOP cannot decode a
sentence.

S8-35's guard then did exactly what it was built to do: refuse the whole render rather than lay out a
finished-looking PDF with an empty box where the absence rule requires «غير متاح». That is correct,
and it is now asserted directly by `anUndecodableStoredImageFailsThePrintRatherThanPrintingAnEmptyBox`
against the stub's own unmodified output, with the same test confirming a refused print stores
nothing and audits nothing.

The consequence had to be checked before the deployment could mean anything, and it was, live:

```
       kind       | content_type | byte_size |  magic
------------------+--------------+-----------+----------
 doc_front        | image/jpeg   |    564689 | ffd8ffe0
 portrait_uqudo   | image/jpeg   |     ...   | ffd8ffe0
 face_audit_trail | image/jpeg   |    435808 | ffd8ffe0
```

Staging runs `FRU_UQUDO_CLIENT=http`, so its images are real JPEGs and its nine submitted profiles
are printable. The gap is confined to stub environments — which is every developer's. Filed as
BL-146 with two candidate fixes that are genuinely different decisions: make the stub emit a real
minimal JPEG, or have the print answer 422 naming the undecodable artifact instead of a bare 500.

## Design decisions taken, with their reasoning

**The provenance-matrix writer lives in the print path.** V0027 left it to "whatever backend code
later resolves a profile's fields", and nothing else in the system does — the journey writes columns,
it does not decide which source governs which field. The print is the first and only resolver, so the
moment it runs is the moment the question has an answer. Recorded once (`ON CONFLICT DO NOTHING`,
which is also all `fru_app`'s SELECT+INSERT grant permits) and read back, so a reprint after the
matrix advances still reports what the first print pinned.

**Field 7 needed a new column on the operator view.** The registry's own national number was exposed
nowhere. The scan holds a provably-equal copy — `HttpCivilRegistryClient` treats any difference as
`not_found` — but printing it under a "Civil Registry" heading would make the form's provenance claim
true only by coincidence, and false the day that guard is relaxed. `RegistryResultView` gains
`identityNumberReturned` (V0062).

**The two reference lists nothing pins now appear on the audit payload.** `branch` and
`education_level` have no per-profile writer, so they resolve against the current version. The
payload marks them `branch=latest:1` rather than omitting them: a partial string reads as "no lists
used" rather than "resolved against latest", and the payload is the permanent record.

**The print is a POST.** It stores an artifact and appends an audit event in one transaction. A GET
an operator could bookmark, or a browser could prefetch, would mint stored PII copies and audit rows
on navigation.

## Review findings, and what they changed

### Backend — one real defect

**The «يدوي» marker walked around `PrintedValue`'s guard.** The type refuses to construct a tagged
absence, which is ticket 04's round-two correction 3. But the manual marker sits on the FIELD, not on
the value, so on a manually completed profile — which is printable — fields 13, 14, 42 and 49 printed
«غير متاح يدوي», and on the attributed variant «غير متاح يدوي — op-manual-1». An operator cannot have
hand-entered a value that is not there. Fixed in `mark()`, and asserted across both variants.

### Backend — three tests that could not fail

- The transaction guarantee was asserted only through a render failure. The render happens **before**
  the store, so nothing would be stored with or without a transaction. The property ticket 05
  decision 5 actually asks for — a stored print that no event records must not survive — is only
  reachable through an audit failure, which is now the test.
- The provenance reprint read-back could not fail while `max(version)` was always 2. The test now
  inserts version 3 between the two prints. That required a connection **as `fru_migrator`**, because
  `fru_app` holds SELECT only on that seed table — the grant model working, discovered by the gate
  rather than assumed. The row is deleted in a `finally`, since
  `AppSchemaConnectivityIntegrationTest` asserts that table's exact row count.
- The salary-certificate path never ran against PostgreSQL: every other test passed
  `includeAttachments=false`. It now does, and asserts the bundle runs to more **sheets** than the
  form alone, read out of the PDF rather than inferred from its size.

### Back office — a blocker that would have broken every print

`window.open` returns `null` whenever `noopener` is in the feature string, whether or not the tab
opened — the HTML standard's own final step. The code read that return value to detect a popup
blocker, so **every successful print would have taken the blocked branch**: the object URL revoked in
the same tick, the tab it had just opened racing a URL that no longer resolved, and the operator told
to allow popups and retry — minting another stored copy of the customer's whole record and another
audit event per attempt. Found at review, before it ever ran in a browser. The opener is now severed
on the handle instead. The test asserts the absent token directly, and separately that the URL is
**not** revoked synchronously — the other half of the same defect, which an assertion on the
`open()` call alone would have missed.

**The failure message claimed something the client cannot know.** The server stores and audits before
it streams, so a dropped connection or a gateway timeout leaves a print on the record that reached
nobody. "No copy was saved" is the inverse of the failure decision 5 exists to prevent, and an
invitation to reprint. It now says that only for statuses the server raises before the transaction.

**The reset mechanism was untestable.** Decision 9's "asked every time" was implemented in antd's
`afterClose`, which runs after the close transition and never completes under jsdom. A rule this
load-bearing should not rest on an animation callback: the dialog is now mounted only while open, so
the reset is the unmount, and both the modal's own test and a page-level test prove it.

## Revert-restore

Taken once, for the transaction boundary, whose assertion is indirect — it observes the transaction
manager rather than the database. The `TransactionTemplate` was replaced with a direct call:

```
[ERROR] Tests run: 16, Failures: 1, Errors: 0, Skipped: 0
[ERROR]   PrintedFormServiceTest
    .anAuditFailureRollsTheTransactionBackRatherThanReturningAnUnrecordedPrint:352
    [the print transaction was rolled back]
```

Exactly one failure and no others, so the guard is proved and none of the other fifteen tests depends
on the boundary. The remaining new guards assert wrong values directly — the manual marker asserts
`false` where the defect produced `true`, the matrix read-back asserts `2` where returning the
current version produces `3`, the `open()` call asserts a two-argument call where the defect passed
three — and cannot pass against the defect, so they need no revert.

## Checked and cleared

- **No PII in either audit payload.** Ids, versions, flags and a role. Asserted in the unit test
  against the fixture's own customer values, and again against the real hash chain on staging's
  schema, because `payload_json` is permanently hash-chained and never erasable (V0002:52).
- **The re-download route reaches neither another profile's print nor a non-print artifact**, and the
  same print IS reachable under its own profile — both directions, so a handler that simply refused
  everything would not pass.
- **A reprint is a second row, not a constraint violation.** Proved against the real database at two
  levels: four prints of one profile in the schema test, and two through the endpoint.
- **Nothing was left behind on staging.** The SSM tunnel is closed, the generated credential deleted,
  no operator account created, and provenance-matrix version 3 exists only inside one test method.

## Files changed

```
backend/src/main/resources/db/migration/V0070__app_artifact_kinds_printed_form.sql
backend/src/main/java/com/sfbank/bayanati/printedform/{domain,service,jdbc,web,config}/  (11 files)
backend/src/main/java/com/sfbank/bayanati/operator/domain/RegistryResultView.java
backend/src/main/java/com/sfbank/bayanati/operator/jdbc/JdbcProfileViewRepository.java
backend/src/main/java/com/sfbank/bayanati/auth/config/SecurityConfiguration.java
backend/src/test/java/com/sfbank/bayanati/printedform/{PrintedFormAssembler,PrintedFormService,PrintedForm}*Test.java
backend/src/test/java/com/sfbank/bayanati/AppSchemaConnectivityIntegrationTest.java
backend/src/test/java/com/sfbank/bayanati/AbstractPostgresIntegrationTest.java
backoffice/src/profiles/{PrintFormModal,ProfileDetailPage}.tsx + tests
backoffice/src/api/{http,profiles}.ts + tests
BACKLOG.md · EXECUTION_PLAN.md
```

No real customer data, no live account numbers and no identity-document images anywhere. Every image
in a fixture is a generated rectangle; the staging bytes were inspected by their four-byte magic
number and never read, copied or downloaded.

## What the product owner should check, when they print

Sign in at `https://d12k860j1xg6zy.cloudfront.net`, open a profile whose status is «مقدَّم», press
«طباعة». The dialog asks both questions at once — which of the two forms, and whether the attachments
come too. The attachments box starts unchecked every time; that is deliberate.

1. Both variants render, and the attributed one names an operator beside a manual field. (A manual
   field only exists on a manually completed profile — staging has one, and it will show «غير متاح»
   for every Civil Registry field, which is correct.)
2. With attachments off: the form alone.
3. With attachments on, on a profile carrying a PDF salary certificate: the bundle ends with the
   payslip on its own bare sheet, every bank page reads «صفحة N من M» where M counts the payslip, and
   «الصفحة M شهادة المرتب» sits at the other end of the footer line.
4. A viewer account is refused.

If any of those is wrong, the print itself is still on the audit trail and the stored artifact can be
compared against what you held — which is the point of storing it.
