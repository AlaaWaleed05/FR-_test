# AWS provisioning — Phase 2 deployment, session 1 of 2

**Date:** 2026-09-06 · **Type:** infrastructure provisioning (guided, product-owner driven) ·
**Region:** `eu-central-1` · **Environment:** staging

Executes the requirements→service mapping in `docs/sessions/2026-09-06-aws-hosting-requirements.md`
(M-1…M-22). Seeds Sprint 7 in EXECUTION_PLAN.md — the T-9 the pre-production audit deferred to
hosting-start.

---

## 1. Account readiness — the session's first real obstacle

**The pre-existing AWS account proved unrecoverable, and a new one was created.**

The product owner had credentials saved in a browser for two identities: an IAM user
`Administrator` at `eu-central-1.signin.aws.amazon.com`, and a root-shaped entry (an email
address) at `eu-north-1.signin.aws.amazon.com`. Neither worked, and the two failure messages
contradicted each other in a way that turned out to be diagnostic:

| Attempt | Result |
|---|---|
| Root sign-in with the email | *"An AWS account with that sign-in information does not exist"* |
| Fresh signup with the same email | *"you already have an account with this email"* |

The state that produces **both** is a **closed account**: AWS keeps the email reserved so it cannot
be reused for signup, while sign-in to a permanently closed account fails as non-existent. The IAM
route needed the 12-digit account ID, which the password manager does not store and which no
"Welcome to AWS" email existed to supply — the inbox search returned nothing for AWS at all.

Recovery was **abandoned deliberately, not exhausted**. AWS's account-recovery support form works
without sign-in but turns around in days, and the only thing the old account held was a mystery. A
staging account has no history worth recovering. Creating a clean one cost minutes.

**Readiness state, verified by API rather than by console screenshot:**

| Check | Result | Evidence |
|---|---|---|
| Console sign-in | ✅ | new account, root sign-in working |
| Billing active | ✅ | signup completed with payment method; monthly cost budget live |
| Region | ✅ | `eu-central-1`, `opt-in-not-required` |
| Admin IAM identity + programmatic creds | ✅ | `sts get-caller-identity` returns `user/fru-deploy` |
| AWS CLI locally | ✅ | `aws-cli/2.36.40`, installed this session (was absent) |

`iam get-account-summary` reported `AccountMFAEnabled: 1`, root holding **no** access keys, and
`Users: 1`. Root locked down, one non-console programmatic admin — the correct shape.

**The region gate was checked before anything was built in the region**, which is the only useful
time to check it: `rds describe-db-engine-versions --engine postgres` returned
**18.1, 18.2, 18.3, 18.4, 18.6** in `eu-central-1`. The entire M-2 mapping rests on PostgreSQL 18
being available as a managed engine; had it not been, the region choice would have had to change
first rather than be discovered later. Pinned **18.6**.

Toolchain found absent and installed: AWS CLI v2, Session Manager plugin. `terraform` absent (not
needed — see §7). Docker 29.7.2 present, which the backend gate requires.

---

## 2. A product-owner instruction that changed the build

Partway through, the PO directed: *treat this as real production; do not compromise technically on
the basis that it is staging.* Re-reading the plan against that, three corners were being cut and
were fixed before building:

| Was | Became | Why it mattered |
|---|---|---|
| Bastion in a **public** subnet | **Private** subnet, no public IP, zero ingress rules, SSM-only access | A login host on the open internet is the classic shortcut. SSM is IAM-gated and CloudTrail-audited; no SSH key exists anywhere |
| Deletion protection **off** (for tidy teardown) | **On** | A database holding passport scans should refuse accidental deletion. Teardown gains one deliberate step |
| Performance Insights + DB logs **off** (to save pennies) | **On** | PI is free at default retention. A production database you cannot see inside is one you cannot diagnose |

The knock-on cost is honest and was stated at the time: a private admin host needs an outbound
path, so the **NAT Gateway was created now rather than at S7-07** (~$32/month, starting earlier).

**What was already at the production standard and was not a concession:** encryption under a
customer-managed key, the database being unreachable from the internet, the master password
existing only in Secrets Manager, the three-role split, SG-to-SG firewall rules.

**Single-AZ is not a concession either** — the mapping decided that deliberately (§4: HA is not a
must-have, the journey tolerates an outage), and following a decision is not economising against it.

**What stays deliberately staging-scale:** `db.t4g.micro`, 20 GB gp3. This is capacity, not
correctness. Every gate below behaves identically at any instance size because each is a property
of the PostgreSQL 18 engine. It is also the reversible axis — instance class is a restart, storage
grows on demand — whereas **encryption and locale were the irreversible ones and were built to the
production standard**. ~$14/month against ~$310. The PO confirmed after asking explicitly whether
it could be extended later.

---

## 3. What provisioned, and the gate outcomes

### S7-02 networking (`infra/aws/01-network.sh`)

VPC `10.0.0.0/16`; three tiers × two AZs (public / app / db). The db pair exists because an RDS
subnet group requires ≥2 AZs even for a single-AZ instance.

**M-11 proven in both directions, by API query rather than by reading the script back:**
`rds-sg` holds **exactly one rule** — ingress tcp/5432 with `SourceSG` = the ECS task SG and
`CidrIpv4: None` — with its egress revoked entirely; and the db route table carries **only** the
`local` route, no `0.0.0.0/0`.

**M-17:** six explicit egress rules on `ecs-sg`, allow-all revoked: 443, **9494** (core banking),
**5353** (Civil Registry), 5432→`rds-sg`, and **tcp+udp 53 to the VPC resolver**.

> **Both statements above are S7-02's end state, not the session's**, and review caught the plan
> file asserting otherwise. `03-admin-access.sh` later added a **second** `rds-sg` ingress rule
> (from `admin-sg`) and created the **NAT Gateway** plus the app route table's default route — the
> NAT having been pulled forward from S7-07 by the decision in §2 to move the admin host into a
> private subnet.

> That DNS pair is **not in the mapping and is load-bearing.** Revoking allow-all egress breaks name
> resolution, and the symptom is a connection timeout to Uqudo — indistinguishable from M-17's own
> non-standard-port trap, from a different cause. Added because the failure would have been
> misdiagnosed.

### S7-03 RDS (`02-database.sh`, `04-db-gates.sh`) — the irreversible pair

`fru-staging-db`, PostgreSQL 18.6, db.t4g.micro, 20 GB gp3 autoscaling to 100.

**M-9 PASS at creation.** `StorageEncrypted: True` under a key whose `KeyManager` is **`CUSTOMER`**
— not the AWS-managed default — with annual rotation enabled, and the instance's `KmsKeyId` matches
`alias/fru-staging-rds` exactly. Backups inherit it (M-10). `PubliclyAccessible: False`.

Master credentials via `--manage-master-user-password`: AWS generates the password directly into
Secrets Manager. It was never typed, printed, or transcribed — the only shape compatible with the
no-secrets rule while an agent runs the commands.

**M-3 PASS on three sub-checks, and the third only exists because the first attempt was wrong.**
`ar-x-icu` resolves in `pg_collation`. `fru` was created explicitly — RDS accepts no initdb
arguments, so the RDS-created default database is *not* used — and reports
`datlocprovider=i datlocale=ar encoding=UTF8`.

The initial verification query failed with `operator is not unique: unknown || "char"` and the
Arabic sample rendered as mojibake in transport. **A broken check is not a pass**, so both were
rewritten: the cast fixed, and the functional test replaced with a **differential** one that builds
its characters via `chr()` (no non-ASCII byte need survive transport) and prints hex codepoints:

```
ar-x-icu order : 623 625 627 622
C (codepoint)  : 622 623 625 627
```

Alef variants order differently under ICU than by codepoint, which proves ICU is **actually
collating** rather than merely configured. Configured-but-inert would have looked identical to
working and silently changed Arabic sort order everywhere.

Connection verified **TLS 1.3**.

### S7-04 the role model — the session's load-bearing unknown

**M-4 PASS: `CREATE EVENT TRIGGER` is permitted for the RDS main user.**

This was the wall the session was told to stop at. It was proven by creating a real event trigger on
`ddl_command_end` against a probe function, confirming exactly one row in `pg_event_trigger`, then
dropping both — **not** by reading AWS's documentation, which is the [UNVERIFIED] status this gate
existed to clear.

**M-5 PASS:** `CREATE ROLE fru_migrator LOGIN CREATEROLE` succeeded, `ALTER DATABASE fru OWNER TO
fru_migrator` succeeded, `pg_get_userbyid(datdba)` returns `fru_migrator`. `fru_app`/`fru_sealer`
are not created here by design — V0001 creates them from Flyway placeholders.

fru_migrator's password was generated by AWS into Secrets Manager and applied through a 0600 temp
file rather than a command argument, because argv is visible in `ps`.

### S7-05 secrets — three of five

`fru/staging/db/{migrator,app,sealer}` created, each value generated by
`secretsmanager get-random-password` and written straight to Secrets Manager. No password was
printed or passed through a prompt at any point. The RDS master is a fourth, AWS-managed secret,
separate by design and injected into nothing.

**`UQUDO_CLIENT_ID`/`UQUDO_CLIENT_SECRET` deliberately NOT created.** They are the PO's tenant
credentials and must be entered by the PO directly. A placeholder secret would be worse than an
absent one: it would let a misconfigured task start against a wrong value instead of failing.

### S7-06 migration and the silently-skippable steps

`flyway:migrate` applied **V0001–V0065, all Success, zero Pending**, connecting as `fru_migrator`
over an SSM port-forward. The pom pins the Flyway URL to `localhost:${env.DB_PORT}`, so a tunnel is
what that configuration already expects.

**Post-migrate 1 — Layer 3 (R-028/R-035): applied and proven enforcing.**
`pg_event_trigger` returns exactly the two expected rows (`fru_audit_ddl_guard`/`ddl_command_end`,
`fru_audit_drop_guard`/`sql_drop`, both `O`).

**Live proof, and it is required here rather than optional:** a passing count query would coexist
with a guard that blocks nothing. So DDL was actually attempted — `CREATE TABLE
audit.fru_layer3_probe` **as `fru_migrator`, the schema's own owner rather than an unprivileged
role**, which is the case that could plausibly bypass it:

```
ERROR:  DDL against schema audit requires a migration session
CONTEXT:  PL/pgSQL function audit.block_audit_ddl() line 17 at RAISE
```

**Post-migrate 2 — reference documents (R-033): done.** All seven lists published:
`admin_division/1, branch/1, country/1, education_level/1, income_source/1, occupation/1,
rejection_reason/1`.

**Post-migrate 3 — operator account: run by the PRODUCT OWNER, not by this session.**
`CreateOperatorAccountRunner` prints a one-time password to stdout exactly once, unrecoverable
afterwards. Running it here would have written a live credential into the session transcript, so the
command was handed to the PO to run in their own terminal. **The PO confirmed it ran and recorded
the username and password.** Neither value was seen by this session and neither appears anywhere in
this repository.

### S7-09 BL-066 — fixed, and the placement is the substance

Re-verified absent first: no `server.*` property existed anywhere. Fixed in a **new
`application-aws.properties`, not the base file.** Honouring `X-Forwarded-*` means trusting whoever
sets those headers — correct behind an ALB that rewrites them, a spoofing vector anywhere the app is
reachable directly, where a caller could assert `X-Forwarded-Proto: https` and be believed.
Base-file placement would have enabled it for local dev and any future direct-exposure deployment.

Same file sets `sslmode=require` (inside the VPC is not encryption; the driver's default `prefer`
silently falls back to plaintext) and `spring.flyway.enabled=false` to state the zero-DDL-rights
intent explicitly. `verify-full` deliberately not set — it needs the RDS CA bundle in the image and
belongs with S7-07.

### S7-11 — partially proven, and the remainder is the point

Booting the packaged jar against the real RDS with `fru.civil-registry.client` deliberately unset
failed with exit code 1:

```
java.lang.IllegalStateException: fru.civil-registry.client is not set; accepted values are
[stub, http]. There is no default: a backend running a real Civil Registry lookup against a stub
must fail loudly, not quietly.
```

The same invocation with all six selectors set started cleanly and completed its work.

**That establishes the property survives packaging and a real database. It does NOT establish
behaviour under ECS task-definition injection** — which is where §9's actual hazard lives: Spring's
relaxed environment-variable binding for hyphenated keys such as `fru.core-banking.client`. The row
stays open; closing it requires the unset-selector boot on Fargate.

---

## 4. What did NOT get done, and why

Nothing was blocked by a gate. The session ran out of runway, not road.

| Row | State | Note |
|---|---|---|
| S7-07 backend on ECR/Fargate | ⬜ | Not started. NAT and the image's prerequisites exist; the task definition, ECR repo and Parameter Store selectors do not |
| S7-08 back office on S3 + CloudFront + ACM | ⬜ | Not started. M-16's same-origin routing therefore remains a **recommendation, not a closed decision** |
| S7-10 seal export (M-12/R-037) | ⚠️ Blocked | Needs a **separate bank-owned AWS account**. That separateness *is* the requirement — a seal bucket in the same account is protected by the credential it polices. Also Object-Lock-at-creation-only, so it must be right first time. Cannot proceed on a personal staging account |
| S7-06 post-migrate 3 | 🔵 | Operator account, handed to the PO (§3) |
| S7-05 Uqudo secrets | 🔵 | PO-supplied |

**Deviation to record honestly:** M-7's production shape for running migrations is a one-shot ECS
task. This session used an SSM port-forward from the laptop because ECR and the image (S7-07) do not
exist yet. That is a stand-in, **not a substitute** — the ECS migration task remains S7-07's work,
and the mapping's reasoning (reproducible for the bank, unlike a host someone connects to) is
unchanged.

**R-051 is untouched and remains a hard Phase 2 entry gate.** Nothing about moving to AWS changes
it: the customer chain is `permitAll`, and a WAF cannot distinguish a customer fetching their own
identity document from an attacker fetching someone else's, because the two requests are
byte-identical.

---

## 5. Cost, and the recommendation

Currently billing, roughly:

| Resource | ~USD/month |
|---|---|
| RDS db.t4g.micro + 20 GB gp3 + PI + logs | ~14 |
| **NAT Gateway** (+ EIP, + data) | **~32** |
| Admin host t4g.nano | ~3 |
| KMS customer-managed key | ~1 |
| Secrets Manager (3 secrets) | ~1.2 |
| **Total** | **~$51** |

**Recommendation made: partial teardown. Product-owner decision: keep everything production
needs — so it was NOT taken.** Recorded here because the recommendation and the decision are
different things and the report should not read as if they agreed. The reasoning below stands as
the recommendation; the stack is left running.

Of what is running, only the **admin host** is genuinely not part of the production shape — M-7
rules a bastion out for the bank, in favour of a one-shot ECS task. Everything else (RDS, NAT, KMS,
secrets) is production infrastructure either way.

Keep RDS, the KMS key and the secrets (~$16/month). The database now holds a fully migrated schema,
a proven-enforcing Layer 3, and seven published reference lists; rebuilding that is a session's work
to save $16.

**Delete the NAT Gateway and terminate the admin host (~$35/month, the majority of the bill)** once
the PO has run the operator-account step. Neither is needed until S7-07, and
`infra/aws/03-admin-access.sh` recreates both in about three minutes.

A monthly cost budget is live; a second budget at a lower threshold would give earlier warning,
since a budget alert is a lagging indicator.

---

## 6. Teardown checklist

**Partial (offered, and declined by the PO in favour of keeping the stack) — retained here
because the next idle period will raise the question again:**

1. `ec2 terminate-instances --instance-ids <admin host>`
2. `ec2 delete-nat-gateway --nat-gateway-id <nat>`, wait for `deleted`, then
   `ec2 release-address --allocation-id <eip>` — **the EIP bills separately once detached**
3. `ec2 delete-route --route-table-id <rt-app> --destination-cidr-block 0.0.0.0/0`
4. Leave RDS, KMS, secrets, VPC, subnets and security groups in place

**Full teardown (end of the deployment exercise), in this order — it fails if reordered:**

1. `rds modify-db-instance --no-deletion-protection --apply-immediately` — **deletion protection is
   on deliberately; this step is meant to be conscious**
2. `rds delete-db-instance --skip-final-snapshot` (or take a final snapshot — but note it is
   encrypted under the CMK and is unreadable if the key is later deleted)
3. `rds delete-db-subnet-group`
4. Delete the three `fru/staging/db/*` secrets — with `--force-delete-without-recovery` only if the
   passwords are genuinely disposable; otherwise the 7-day recovery window is the safer default
5. NAT Gateway → wait `deleted` → release EIP
6. Detach and delete the internet gateway; delete subnets, route tables, security groups
   (`rds-sg` last — the others reference it), then the VPC
7. Delete the IAM instance profile, detach and delete `fru-staging-admin-role`
8. `kms schedule-key-deletion --pending-window-in-days 7` — **last, and only after every snapshot
   encrypted under it is gone.** Deleting the key makes those snapshots permanently unreadable
9. Delete the CloudWatch log groups for the RDS `postgresql` export

**Do not delete:** the `fru-deploy` IAM user or its access key while further sessions are planned;
the cost budget.

---

## 7. What session 2 (the guided acceptance walk) needs

**Before it can start, S7-07 and S7-08 must be built** — there is currently no running backend and
no back office on AWS. That is the bulk of a session on its own, so session 2 is realistically
*provisioning part 2 + acceptance walk*, or a third session is needed.

Then:

- **Uqudo (real — the project's OWN tenant; this line read "FIB tenant" as written, corrected
  2026-09-06 by OQ-010's answer. The *contents* of the provisioned secret were not re-checked when
  the label was corrected, which is exactly R-001's remaining residual — confirm the stored pair
  is the project's own before any real customer data flows):** PO supplies
  `UQUDO_CLIENT_ID`/`UQUDO_CLIENT_SECRET` into Secrets
  Manager directly. Endpoints go to Parameter Store — configuration, never baked into a build (R-007)
- **Civil Registry: real**, against the PO's own national record. **This is real PII**: nothing from
  it may be committed, logged, or written into a session report
- **Core banking `CheckAccount`: stubbed** — no account available. `fru.core-banking.client=stub`
- **Messaging: `stub` only**, and not a choice — `MessageSenderConfiguration` accepts nothing else
  until the bank answers OQ-016
- **S7-11 must be closed on Fargate** before the stack is declared ready: boot the task with a
  selector unset and confirm it refuses to start

**Two production-config hazards to carry in:** `application.properties:51-52, 74-76, 138-139` hold
synthetic stub seed values — inert once selectors are `http`, but a production parameter path should
not carry them at all.

---

## 8. Gates

Backend tier touched (`application-aws.properties`), so the backend gate was run in full —
`./mvnw verify -Pdb-integration-test`, the real coverage gate per CLAUDE.md, with Docker running.

Run twice. The first (18:22:04) passed — `Tests run: 1016, Failures: 0`, BUILD SUCCESS — but it
predates the review fixes, which rewrote that properties file, so it proves nothing about what is
being committed and is recorded here as one line rather than pasted. **Final run, verbatim:**

```
[INFO] --- spotless:3.10.0:check (spotless-check) @ backend ---
[INFO] Spotless.Java is keeping 443 files clean - 0 needs changes to be clean, 0 were already clean, 443 were skipped because caching determined they were already clean
[INFO]
[INFO] --- jacoco:0.8.15:check (jacoco-check) @ backend ---
[INFO] Loading execution data file C:\Users\DELL\Documents\Osman\Waleed\Fr_user_update\backend\target\jacoco.exec
[INFO] Analyzed bundle 'backend' with 324 classes
[INFO] All coverage checks have been met.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  01:54 min
[INFO] Finished at: 2026-09-06T18:48:47+02:00
```

`Tests run: 1016, Failures: 0, Errors: 0, Skipped: 0`.

Mobile and backoffice tiers untouched — no gate applies and none was run.

---

## 9. Filed this session

- **Sprint 7** seeded in EXECUTION_PLAN.md, 13 permanent rows
- **BL-066** — property half done at S7-09; **reopened for its smoke-test assertion**, which its own text calls the more important half
- **BL-072** — the staging account is under a personal root email and cannot become production; the
  bank must own the production account's root email, and a *second* bank-owned account is required
  for M-12's seal export
- **BL-073** — every future RDS major-version upgrade silently removes Layer 3 unless the runbook
  reinstalls it; R-028's shape, but recurring
- `infra/aws/` — five scripts, secret-free, each carrying its own reasoning. Resource IDs are
  written to gitignored `*-ids.env` files so no account-specific identifier is committed

**Not filed, deliberately:** no RISKS.md edit. R-026's encryption requirement is now *implemented on
a staging stack*, which is not the same as discharged for production, and marking a risk changed on
the strength of a staging build would be the misreading the hosting mapping's §10 already warned
against.

---

## 10. Review findings and dispositions

`@agent-reviewer` against the full diff, scoped to secrets, the truth of the Sprint 7 rows against
what the scripts actually do, script defects, the Spring config, and any "live proof" a passing test
could also have produced.

**Secrets scan: clean.** No credential, key material, account ID or account-bearing ARN in any
committed file; both `*-ids.env` files confirmed matched by `.gitignore`.

**Eleven findings. All accepted; ten fixed, one accepted as accurate-as-written.** The three that
mattered are first, and two of them invalidated claims this report had already made.

| Finding | Disposition |
|---|---|
| **`04-db-gates.sh`: an empty generated password creates a passwordless privileged role and reports `PASS`.** The script runs `set -uo pipefail` without `-e`, so a failed `get-random-password` leaves `MIGPW` empty, the secret writes fail into `/dev/null`, and `CREATE ROLE fru_migrator LOGIN CREATEROLE PASSWORD ''` is issued — then printed as a passing M-5 gate | **Fixed.** Explicit empty check with `exit 1`, and the secret write no longer swallows its own failure. The worst outcome of a gate is a false PASS on the thing it exists to check |
| **`05-layer3.sh`: the Layer-3 "live proof" matched the bare token `ERROR`,** so a failed connection, a bad `~/.pgpass`, or an unrelated `permission denied` would all have printed `PASS  DDL against schema audit was refused` | **Fixed and re-run.** The match is now the guard's own message (`requires a migration session`/`block_audit_ddl`). This is the one assertion in the sprint whose entire rationale is that a row count is insufficient, so a check satisfiable by an unrelated failure defeated its own purpose. **The original evidence was in fact genuine** — the pasted output shows the guard's message and `audit.block_audit_ddl() line 17` — but that was luck rather than the check's doing, so it was re-proved with the tightened assertion rather than argued from |
| **`03-admin-access.sh`'s IAM policy was both broader than its comment and too narrow to run the scripts that use it.** The live run had been unblocked by an out-of-band `put-role-policy` never reflected in the file — so `infra/aws/` was not the reproducible record `01-network.sh:12` claims it is | **Fixed, in both directions.** The committed policy now grants the `GetRandomPassword`/`CreateSecret`/`PutSecretValue` the gate scripts actually call, scoped to `secret:fru/staging/*`, and the misleading comment is replaced. **The LIVE role policy was also narrowed to match** — the out-of-band version had `Resource:"*"` on the write actions |
| `EXECUTION_PLAN.md` S7-02 asserted a network posture a later script in the same session changed — NAT "deferred to S7-07", and `rds-sg` holding "exactly one rule" | **Fixed** in the row, in this report (§3) and in `01-network.sh`'s header and route-table comment |
| `EXECUTION_PLAN.md` S7-05 claimed three secrets created, but only `db/migrator` existed in any script; `db/app` and `db/sealer` were made by an ad-hoc command line | **Fixed** by adding `infra/aws/06-secrets.sh`, verified idempotent against the live account |
| `application-aws.properties` **contradicted mapping §9 on the selector mechanism** — it declared selectors deliberately absent, which meant relying on Spring's relaxed env-var binding for hyphenated keys, the exact hazard §9 exists to avoid. `application.properties` defines no uncommented `fru.*.client` key | **Fixed.** All six selectors now bound explicitly to named environment variables. Verified independently before accepting: `grep` for uncommented selector keys in `application.properties` returns nothing |
| **BL-066 was marked closed without the assertion its own text calls the more important half** | **Fixed.** Verified against the item's wording — *"the assertion matters more than the property"* — and reopened; tracked against S7-08, since no ALB exists to assert against yet |
| `02-database.sh` referenced `$SG_RDS` without sourcing `network-ids.env`, so it aborts under `set -u` as documented; `03-admin-access.sh` guarded only `VPC_ID` | **Fixed.** Both source the file and guard every variable they use |
| `04-db-gates.sh`'s M-4 failure branch printed `FAIL` and exited 0, which SSM reports as a successful invocation — for the one gate whose header says a failure stops the session | **Fixed** — `exit 1` |
| `05-layer3.sh` left `~/.pgpass` on disk if the script aborted between writing and removing it | **Fixed** with `trap ... EXIT`; verified on the re-run (`ABSENT (correct)`) |
| `PROJECT_PLAN.md` still listed the four RDS gates as [UNVERIFIED] and R-026 as "not yet provisioned" | **Fixed** — reconciled, with R-026 explicitly kept 🔴 Live because staging is not production |
| S7-11's phrasing read as if the unset-selector refusal were newly established, when `CivilRegistryClientConfigurationTest` already asserts it | **Accepted and reworded.** The live output proves the packaging-plus-real-database dimension, not the guard. The row now names the existing test and scopes the live evidence to the delta |

**One finding was not a defect:** the reviewer noted `03-admin-access.sh` is unattributed to any
Sprint 7 row while provisioning five billed resources. Correct, and now fixed by naming it in S7-03
and itemising every billed resource in S7-13.
