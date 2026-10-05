# db/post-migrate/03-create-operator-account

AD-002e (`docs/sessions/2026-09-02-research-ad-002e-auth.md`, closed at S4-05) — the real
mechanism behind "admin-created accounts, no self-registration". Unlike `01-audit-event-trigger.sql`
and `02-publish-reference-documents.md`, this step needs no elevated database role — `fru_app`
already holds `INSERT` on `app.operator_user` (V0057) — so there is no dedicated
`application-*.properties` override; only the profile and the web server disabled inline.

**Not an HTTP endpoint.** The task's OUT-OF-SCOPE line excludes back-office UI/admin screens, and
nothing asked for an account-management API this session — same reasoning
`02-publish-reference-documents.md` already states for itself (an endpoint would need to decide
its own authorization, which is circular once the thing you're building IS authorization). This
runner both bootstraps the very first admin account and creates every later operator/viewer
account. Listing, disabling or changing an existing account's role has no mechanism yet — filed to
BACKLOG.md.

## Running it

After `./mvnw package -DskipTests` (or any build that produces
`target/backend-0.0.1-SNAPSHOT.jar`):

```
DB_HOST=<host> DB_PORT=<port> DB_NAME=<db> FRU_APP_PASSWORD=<fru_app password> \
  java -jar backend/target/backend-0.0.1-SNAPSHOT.jar \
    --spring.profiles.active=create-operator-account \
    --spring.main.web-application-type=none \
    --username=<login-name> --display-name=<Arabic display name> --role=viewer|operator|admin
```

**This boots the full application context** — same disclosure as `02-publish-reference-documents.md`:
every other bean's no-default property (`fru.core-banking.client`, `fru.messaging.*.provider`,
`fru.uqudo.client`, `fru.civil-registry.client`) still needs a value or startup fails loudly. Supply
the same environment the deployment already runs with.

`CreateOperatorAccountRunner` (gated `@Profile("create-operator-account")`, inert in every other
profile) validates `--role`, generates a `SecureRandom` one-time password, hashes it with the same
`PasswordEncoder` bean the sign-in path uses, inserts the row with `must_change_password = true`,
prints the password to stdout **exactly once**, and exits the process.

**The printed password is not recoverable afterward** — it is never logged, never stored anywhere
but the bcrypt hash, and must never be pasted into a session report, a ticket, or any file in this
repository (CLAUDE.md's no-secrets rule, no exception for a generated one-time value). If it is
lost before the operator's first sign-in, the only recovery today is disabling the row directly
(`UPDATE app.operator_user SET is_enabled = false ...`) and running this command again with a new
username — there is no reset endpoint (self-service reset is out of scope by product-owner
decision).

## Bootstrapping the very first admin

Identical invocation, `--role=admin`. No admin session is required to run it — it is a CLI act
against the database directly, which is exactly why it exists instead of an HTTP endpoint (an
admin endpoint to create the first admin is circular).
