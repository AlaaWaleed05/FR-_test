# `infra/aws/` — the AWS staging stack

These scripts are the record of how the staging stack was provisioned (S7). They are not a
deployment tool and not idempotent as a set: several create resources that cannot be created
twice, and one rotates a live credential if re-run. Read this file before running anything.

The stack these built is **staging, not production**. Neither R-026 nor R-051 is discharged by
anything in this directory, and as of 2026-09-14 neither is outstanding work either — but for
opposite reasons, so do not read them as one item: **R-026 is retired**, satisfied by `03-rds.sh`'s
customer-managed KMS key and `StorageEncrypted: true` (the row was behind the code, not ahead of
it), while **R-051 is dropped** by product-owner decision (AD-017) with the exposure accepted in
full — identity images stay fetchable by profile UUID, permanently. See RISKS.md and PROJECT_PLAN.md
AD-017.

## Run order

The numbers are not the order. `06` must run **before** the migration, because the passwords
it files are consumed as Flyway placeholders by the migrations themselves:

```
01-network.sh      VPC, subnets, IGW, route tables, security groups, DB subnet group
02-database.sh     KMS CMK + the RDS instance                      (needs network-ids.env)
03-admin-access.sh NAT gateway, admin SG, IAM role, admin host     (needs network-ids.env)
04-db-gates.sh     CREATES the fru database + fru_migrator, and gates M-3/M-4/M-5
06-secrets.sh      files the fru_app and fru_sealer passwords
./mvnw flyway:migrate        V0001 creates fru_app, V0030 creates fru_sealer
05-layer3.sh       installs the audit event trigger — must run AFTER the migration
```

Then the app tier (S7-07 / S7-08), which needs the database migrated first:

```
07-ecr-and-params.sh   ECR repository + Parameter Store config; verifies the Uqudo secret
                       (docker build + push happens between 07 and 08)
08-backend-service.sh  log group, execution role, cluster, target group, INTERNAL ALB,
                       task definition, service.  Needs IMAGE_TAG=<the tag you pushed>
09-backoffice-cloudfront.sh   S3 + OAC + CloudFront Function + VPC origin + distribution,
                       and the ALB security-group narrowing.  Needs backoffice/dist built
10-s711-selector-probe.sh     optional: proves an unset selector refuses to start on Fargate
11-egress-probe.sh            optional: proves the bank's endpoints reachable from the BACKEND's
                              own SG/subnet/NAT. Do NOT test this from the admin host --
                              fru-staging-admin-sg has no 9494/5353 egress, so it times out and
                              reads like an unreachable bank endpoint (cost real time at S7-12).
                              PROBE_EXTRA="label=https://host:port/path" adds a target that has
                              no Parameter Store entry yet.
```

`08` is the script you re-run to deploy a new image. It is safe to re-run and deliberately
does NOT re-open the VPC-CIDR placeholder once `09` has narrowed the security group to
CloudFront's service-managed group — an earlier version did, silently, on every redeploy.

`09` must run after `08` (it needs the ALB) and its VPC origin takes up to 15 minutes to
reach `Deployed`. If it reports that it could not find `CloudFront-VPCOrigins-Service-SG`,
the VPC-CIDR placeholder is still open and the script says so — re-run it once the origin
has deployed.

`06` is order-independent as long as it precedes the migration. `05` is numbered before `06`
only because it was written first; it genuinely runs last.

**As actually executed in S7**, `06-secrets.sh` did not exist yet and those two secrets were
created by an ad-hoc command line; the script was written afterwards to close that gap and
has only ever run as a no-op idempotency check. The order above is the dependency order, and
it is what a rebuild should follow — it is not a transcript of the original session. The
transcript is in `docs/sessions/2026-09-06-aws-provisioning.md`.

## `04-db-gates.sh` is a provisioning step, not a test

Despite the name, it **creates the `fru` database** with the ICU locale (which cannot be
changed afterwards), **creates the privileged `fru_migrator` role**, and transfers database
ownership to it. `flyway:migrate` cannot connect at all until it has run.

**Re-running it rotates a live credential.** `01` and `02` refuse to run twice and say so;
this one does not. On a second run it resets `fru_migrator`'s password and overwrites the
stored secret. Anything holding the old password breaks. To re-check a gate, read the
transcript from the run that provisioned the database — do not re-run the script.

## `*-ids.env` and how to get them back

`01` and `03` write `network-ids.env` and `admin-ids.env`. Both are gitignored: they hold
resource IDs, which are not secrets, but they are machine-specific state rather than source.
Everything downstream sources them and refuses to run without them.

That used to be a dead end on a second machine — the only script that produces the IDs is
`01`, and `01` refuses to run when the VPC already exists. **`00-discover-ids.sh` rebuilds
both files** from the `Name=fru-staging-*` tags that `01` and `03` set on every resource. It
is read-only (`describe-*` only), refuses to write a partial file if anything is missing or
matches more than one resource, and keeps a `.bak` of whatever it replaces.

```
AWS_PROFILE=fru-deploy bash infra/aws/00-discover-ids.sh            # rewrite both files
AWS_PROFILE=fru-deploy bash infra/aws/00-discover-ids.sh --print    # stdout only
```

The `Name` tag values are therefore a contract between `01`, `03` and `00`. Renaming a
resource breaks recovery.

## Known rough edge: the admin host's user-data is best-effort

`03-admin-access.sh` installs `postgresql17` via user-data. On the live run that install ran
before the NAT route was usable, so `psql` was simply absent and the first `04-db-gates.sh`
invocation died on it. The install is best-effort; if the gates fail with
`psql: command not found`, install it on the host and re-invoke. This is the one failure of
`04` that is safe to retry, because it aborts at the connectivity check before touching any
credential.

## Testing a change to `04-db-gates.sh`

`04` runs once, against a live account, and creates the thing it is named after checking, so
its failure branches were never executed by anything — which is how a gate that printed
`PASS` when its own query errored survived two reviews.

`test/run-gate-tests.sh` executes every branch with stub `psql`/`aws`/`jq` earlier on `PATH`.
No network, no credentials, no account:

```
bash infra/aws/test/run-gate-tests.sh
```

Test 15 runs the pre-fix script (95f5b4d) against the same stubs and shows it printing `PASS`
and exiting 0 on a failed ICU query — the regression the current version fails on.

Run it after any change to `04-db-gates.sh`. It is the only way that script gets exercised
before it touches an account.

## Teardown

Do not tear anything down from these scripts. The checklist, and what it costs to leave the
stack running, is in `docs/sessions/2026-09-06-aws-provisioning.md` §6.
