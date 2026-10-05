# What can the admin screen do, and what must it refuse?

Type: grilling
Status: resolved
Blocked by: 06

## Question

The admin does everything through the UI — the CLI (`CreateOperatorAccountRunner`) stops being the
way accounts are made. `BL-023` is the existing row for this, and it was **cut from Phase 1 MVP by
a product-owner decision on 2026-09-04**; this brief reverses that, which should be recorded.

Settle the capability set and, more importantly, the refusals:

1. **Capabilities.** List accounts · create one · disable / re-enable one · change a role · reset a
   password. Which of these ship, and does creation cover all three roles including `admin`?
2. **How does the first admin exist at all?** Today it is a deliberate out-of-band CLI act. Options:
   keep that as the one bootstrap path; seed one in a migration with a forced password change; or
   create it during deployment from a secret. A seeded admin with a known default is the classic
   way a bank system ships with a live back door — whatever is chosen must not have one.
3. **Last-admin protection.** Can an admin disable themselves, or the only remaining admin? The
   answer must be "no", enforced server-side, or the system locks itself out with no recovery path
   (accounts are never deleted, and there is no other way in).
4. **Initial passwords.** Who sets them, how are they delivered, and does the admin ever see the
   value? `must_change_password` already defaults true. A random generated password shown once is
   the usual answer; state it.
5. **`created_by`.** `V0057` has the column, NULL for every CLI-created account, waiting for an
   authenticated admin session. This is the ticket that finally populates it.
6. **Does the admin ever see customer data on this screen?** It should be possible to administer
   accounts without reading a single customer's PII — keep that property if ticket 06 allows it.
7. **Disable takes effect when?** `OperatorIdentityFilter` re-reads the account per request, so a
   disable already lands mid-session — confirm that still holds and is tested.

## Context

- `BACKLOG.md` BL-023 · `PROJECT_PLAN.md` AD-002e
- `V0057__app_operator_role_and_user.sql` — the whole table, read the comments
- `backend/…/auth/config/CreateOperatorAccountRunner.java` · `db/post-migrate/03-create-operator-account.md`
- `SecurityConfiguration` already claims `/api/v1/admin/**` for `ROLE_ADMIN` with no endpoints
  behind it — the route is reserved and waiting.
- `BACKLOG.md` BL-096 — a throwaway operator account exists on staging, disabled and unremovable.
  Whatever ships should make that situation manageable rather than permanent.

---

## Decision

Product-owner, 2026-09-13, by interview. Ticket 06 (admin is a superuser) is the prerequisite and
is resolved.

1. **All five capabilities ship** — list · create · disable/re-enable · change role · reset
   password — and **create covers all three roles, including `admin`**. The evidence for shipping
   the full set rather than a subset is `BL-096`: a throwaway account sits on staging disabled and
   unremovable, because partial administration leaves permanent mess. Role change and password
   reset are specifically what stop an admin creating a brand-new account every time something
   needs fixing.

2. **The first admin comes only from the existing CLI runner.** Rejected: seeding one in a
   migration (the password, or its hash, would live in the repo forever and git history keeps it
   even after deletion — the classic bank system shipping with a live back door, which this
   ticket's own question 2 warns about) and creating one at deployment from a secrets store (safer
   than seeding, but more machinery for a once-per-environment event). The CLI already works and
   was live-proven when `BL-024` was fixed.

3. **Last-admin protection covers BOTH cases** — an admin may not disable or demote themselves,
   and nobody may disable or demote the last enabled admin — **enforced server-side as a count
   check inside the same transaction as the change**. Accounts are never deleted and there is no
   other way in, so a lockout here has no recovery path at all.

   **CORRECTED AT REVIEW — a plain count is not enough.** A `SELECT count(*)` inside a transaction
   IS a read-then-write under READ COMMITTED: two admins disabling two DIFFERENT admins each see
   two enabled admins and both commit, producing exactly the zero-admin lockout this decision
   exists to prevent. It needs **row locks over the enabled-admin set (`SELECT … FOR UPDATE`) or
   SERIALIZABLE**. The in-repo precedent, `JdbcReviewRepository`'s `LOCK_AND_READ_STATUS` plus a
   conditional `UPDATE … WHERE`, works because it contends on ONE row; last-admin contends on a
   SET, which is the harder case and needs the stronger lock.

4. **Passwords: server-generated random, displayed once to the admin, `must_change_password =
   true`, and the identical mechanism for a reset.** This is what the CLI already does, so it is
   one behaviour rather than two. No email — there is no operator mail path, and mailing
   credentials is worse than reading them out.

5. **`created_by` is populated** from the authenticated admin session. `V0057` has carried the
   column since it was written, NULL for every CLI-created account, waiting for exactly this.

6. **The admin screen shows NO customer data.** Admin can reach customer data anyway (ticket 06),
   but through the ordinary profile screens — where it audits as a profile view rather than
   hiding inside an admin session. The two surfaces stay separate.

7. **Mid-session disable already works, and is already tested — CONFIRMED, not assumed.**
   `OperatorIdentityFilter` re-reads the account on every request via
   `OperatorUserRepository.findActiveById`, which filters to `is_enabled = true`, so a disabled
   account simply goes absent and gets no `OperatorIdentity` attribute. The test is
   `OperatorIdentityFilterTest.setsNothingForADisabledAccountTheLiveSessionDisableEffect` — named
   for this exact property.

   **But it does NOT yet cover the surface this ticket specifies — found at review.** That
   re-read only protects endpoints that resolve an `OperatorIdentity`. `/api/v1/admin/**` is gated
   by `hasRole("ADMIN")` off the **cached session principal**, and `OperatorIdentityFilter`
   deliberately sets nothing for an admin — so a disabled or demoted admin's LIVE session would keep
   full access to every admin endpoint. **Requirement for the build: the admin endpoints must
   depend on the same per-request re-read**, or "disable takes effect immediately" is true for
   operators and false for exactly the role that can do the most damage.

**One thing whoever builds this must not miss**, already flagged by ticket 06's own review:
`OperatorIdentityFilter` line 54 currently filters admin OUT
(`.filter(account -> account.role() != OperatorRole.ADMIN)`), so an admin gets no operator
identity and is refused every operator endpoint regardless of the security chain. That single line
is what makes ticket 06's ruling work, and the security chain is the obvious place to look and is
NOT the binding one.
