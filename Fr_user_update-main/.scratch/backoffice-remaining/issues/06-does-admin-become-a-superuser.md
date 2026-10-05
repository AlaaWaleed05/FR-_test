# Does admin become a superuser? (reopens AD-002e)

Type: grilling
Status: resolved
Blocked by: —

## Question

The product owner's answer on 2026-09-13: *"both operator and admin can print, the main difference
of viewer is that he can not print or edit like the other two, and admin is the only one able to
generate backoffice users."*

Read literally that makes admin a **superuser**: everything an operator can do, plus account
management. **That reverses `AD-002e`, a closed architecture decision**, and contradicts three
things currently in the code:

- `docs/journeys/operator.md`: "A third role, **admin**, exists purely to create/manage accounts —
  it is **not** a back-office access level: an admin signs in but reaches no `/api/v1/operator/**`
  endpoint… two-valued (viewer/operator) exactly as this table describes."
- `V0057__app_operator_role_and_user.sql`: `operator_role.is_back_office` is **false** for
  `admin`, and each account holds **exactly one** role, so admin is structurally exclusive of
  operator — "the forbidden combination is not merely rejected, it is inexpressible."
- `SecurityConfiguration`: `/api/v1/operator/**` requires `ROLE_VIEWER`, which an admin is never
  granted, so an authenticated admin gets a real 403 today.

**The concrete scenario that makes this more than bookkeeping.** The four-eyes rule says an
operator may not approve a profile they manually completed. An admin creates accounts and sets
their initial passwords. So an admin can already create a second account, sign in as it, manually
complete a profile, and approve it as themselves — four-eyes defeated by one person, today, via
the CLI. Granting admin operator powers does not create that hole, but it removes the last
friction in front of it: no second account needed at all.

Settle:

1. Does admin gain operator powers (view, print, approve, reject, manually complete), or only
   *print and view*, or stay account-management-only with printing done by operators?
2. If admin gains them: is an admin counted as an operator for the four-eyes predicate in
   `V0009`/`V0020`, and does `is_back_office` become true for admin?
3. Should four-eyes additionally exclude an approver acting on a profile completed by an account
   **they created**?
4. How is the reversal recorded — a new `AD` row superseding `AD-002e`, or an amendment to it?

## Answer

Product-owner decision, 2026-09-13. Two rulings.

### 1. Admin is a superuser

Admin gains the full operator capability set — view, print, approve, reject, manually complete —
**on top of** being the only role that may create back-office users. The role ladder becomes:

| Role | May |
|---|---|
| viewer | view only. **No print, no edit.** |
| operator | view · print · approve · reject · manually complete |
| admin | everything an operator may · **plus** create and manage back-office users |

This **reverses `AD-002e`'s "admin is not a back-office access level"**. What that costs, each
item to be carried out by whoever builds ticket 07:

- `V0057`'s `operator_role.is_back_office` becomes **true** for `admin`. A migration, not an edit —
  `V0057` is applied.
- `SecurityConfiguration`'s chain 1 gates `/api/v1/operator/**` on `ROLE_VIEWER` and the
  approve/reject/manual-complete verbs on `ROLE_OPERATOR`. An admin currently holds neither. The
  role-granting in `OperatorUserDetails` has to make admin imply both, or the matchers change.
  Whichever is chosen, the **403-for-admin** behaviour proven at S4-05 is deliberately being
  undone, and its test must be inverted rather than deleted.
- `AdminHomePage.tsx` stops being a dead end. `RequireRole`'s redirect of an admin away from
  `/profiles` is removed.
- `operator.md`'s roles table and its "not a back-office access level" paragraph (l.35-38) are now false.
- **`auth/web/OperatorIdentityFilter.java` l.53 filters out admin**: `.filter(account -> account.role() != OperatorRole.ADMIN)`. An admin therefore never gets an `OperatorIdentity` request attribute, and `OperatorIdentityArgumentResolver` refuses every operator endpoint regardless of what the security chain allows. **This is the single change without which the decision does not work.** Found by the review pass, not by the original charting — worth noting, because the security chain is the obvious place to look and it is not the binding one.
- **`operator/domain/OperatorAccessLevel.java` is a two-valued enum** (`VIEWER`, `OPERATOR`) that the filter maps into. Admin has no mapping and needs one, or the access model needs a third value.
- **`auth/domain/OperatorRole.java` l.5-9's javadoc** asserts "an `admin` account never receives an `OperatorIdentity` at all" — false after this change, and exactly the kind of comment CLAUDE.md's frozen-comment rule exists for.
- **Record it as a new `AD` row that supersedes `AD-002e`**, not an edit to `AD-002e`. The
  original decision and its reasoning stay readable; a reader needs to see that this was reversed
  and why, not find a rewritten history.

### 2. Four-eyes is removed entirely — "for now"

The rule — *an operator may not approve a profile they themselves manually completed* — is
**dropped**. Narrower than its name: it never touched reject, and never touched a profile
submitted from the mobile app, so it only ever bit on manually-completed profiles.

The status-history data that drives it (`app.profile_status_history.is_manual_completion`) is
**kept**, so the rule can be switched back on later without a migration and without a blind spot
for profiles completed in the meantime. That was the explicit choice over the cheaper option of
dropping the flag too.

Blast radius, verified against source at resolution time:

| File | What changes |
|---|---|
| `operator/jdbc/JdbcReviewRepository.java` | the `NOT EXISTS` clause inside the conditional approve `UPDATE` (~l.62-66), and the standalone predicate at ~l.52 that feeds `canApprove` |
| `operator/domain/FourEyesViolationException.java` | gone |
| `operator/service/OperatorReviewService.java` | the refusal path that distinguishes a four-eyes violation from a lost race |
| `operator/domain/ProfileDetail.java`, `ProfileViewRepository.java`, `service/OperatorProfileViewService.java`, `web/ProfileDetailResponse.java`, `web/ReviewController.java` | `canApprove` / `approveBlockedReason` |
| `backoffice/src/api/types.ts`, `profiles/ProfileDetailPage.tsx` (+ 2 tests) | the blocked-approve state in the UI |
| `OperatorReviewIntegrationTest`, `OperatorProfileViewIntegrationTest`, **`auth/OperatorAuthenticationIntegrationTest` l.189** | the **three** tests asserting the rule — **inverted, not deleted**. The third drives a real sign-in and asserts 403 on the same operator's approve, so it **fails the build**, it does not merely go unasserted |
| `operator/domain/ReviewRepository.java` | `isManualCompletionActor(UUID, String)` at l.60 — the **port**, not just the JDBC adapter — plus the rule's documentation at l.56-58, l.74-75, l.81-82 |

Two things that are easy to miss:

- **No migration is required to remove it.** The rule lives in Java-side SQL, never in a DB
  constraint. But `V0009__app_status_history.sql`'s header comment documents it as enforced, in
  detail, and that comment becomes **false**. `V0009` is applied and must not be edited, so the
  correction belongs in a new migration's comment and in `docs/journeys/operator.md` — otherwise
  it is exactly the frozen-comment failure `CLAUDE.md` has now caught four times.
- **`BL-013` is closed** and records `canApprove` as delivered. Removing it needs BL-013 annotated
  rather than silently contradicted.

**The compensating control is now the audit trail, and nothing else.** With four-eyes gone and
admin holding every power, no separation of duties remains in the product: one person can create
an account, complete a profile and approve it. That is a deliberate, recorded "for now" — it
raises the stakes on ticket 09 and it needs an `R-` row in `RISKS.md` before production, which is
carried as fog on the map rather than assumed.

**Unblocks:** ticket 07.
