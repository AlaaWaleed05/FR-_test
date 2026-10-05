# S9-04 — staging migrated to V0074, and the version it was actually on

Date: 2026-09-16. Tiers: **none — no application code was changed.** One database migrated, four
documents corrected.
Task row: EXECUTION_PLAN.md S9-04. Asked for: bring staging up to the repo's schema and prove it.

## The state was not what two reports said

The brief flagged the V0072 figure as inherited and unverified. It was also wrong.

```
 installed_rank | version | description                                | success | installed_on        |
----------------+---------+--------------------------------------------+---------+---------------------+
 70             | 0070    | app artifact kinds printed form            | t       | 2026-09-15 01:28:39 |
 69             | 0069    | app salary certificate claimed             | t       | 2026-09-14 01:42:30 |
 68             | 0068    | app operator role drop is back office      | t       | 2026-09-14 01:42:28 |
 67             | 0067    | app profile status history four eyes remov | t       | 2026-09-14 01:42:27 |
 66             | 0066    | app artifact ref storage key dead r046 clo | t       | 2026-09-14 01:42:25 |
 65             | 0065    | app profile lifetime token cap             | t       | 2026-09-06 18:08:53 |

 highest_applied | failed_rows | strictly_in_order | versioned_rows |
-----------------+-------------+-------------------+----------------+
 70              | 0           | t                 | 70             |
```

**V0070, not V0072 — four migrations behind, not two.** No failed rows, strictly ascending.

The number reached two S9-02 reports without anyone reading the database. Both now carry a
correction that leaves the wrong figure visible rather than editing it away, because what the row
records is how a stale number travelled unchallenged. `docs/road-to-production.md` §3.7 now holds
the version, with the standing warning not to quote it as evidence.

## BL-144's trap did not fire, and it was checked rather than assumed

`flyway:validate` before migrating anything:

```
[ERROR] Detected resolved migration not applied to database: 0071.
[ERROR] Detected resolved migration not applied to database: 0072.
[ERROR] Detected resolved migration not applied to database: 0073.
[ERROR] Detected resolved migration not applied to database: 0074.
```

Only pending migrations. **No checksum mismatch**, so every applied file V0001–V0070 still matches
what staging ran, and `flyway repair` was neither run nor needed. There is no diff to quote because
no file drifted. BL-144 stays open: this is one clean reading, not a gate.

## The migration

```
[INFO] Database: jdbc:postgresql://localhost:55432/fru (PostgreSQL 18.6)
[INFO] Successfully validated 74 migrations (execution time 00:00.467s)
[INFO] Current version of schema "public": 0070
[INFO] Migrating schema "public" to version "0071 - app is manual completion write never"
[INFO] Migrating schema "public" to version "0072 - app artifact kinds one printed form"
[INFO] Migrating schema "public" to version "0073 - app profile field edit"
[INFO] Migrating schema "public" to version "0074 - ref withdraw rej03"
[INFO] Successfully applied 4 migrations to schema "public", now at version v0074 (execution time 00:02.026s)
[INFO] BUILD SUCCESS
```

Run as `fru_migrator` over an SSM port-forward, `DB_PORT=55432`. The three passwords went from
Secrets Manager into environment variables for the life of one child process and were never written
to a file, a log or this report.

## Proof 1 — the history reads V0074, success, in order

```
 installed_rank | version | description                          | success | installed_on        |
----------------+---------+--------------------------------------+---------+---------------------+
 74             | 0074    | ref withdraw rej03                   | t       | 2026-09-16 11:56:07 |
 73             | 0073    | app profile field edit               | t       | 2026-09-16 11:56:05 |
 72             | 0072    | app artifact kinds one printed form  | t       | 2026-09-16 11:56:04 |
 71             | 0071    | app is manual completion write never | t       | 2026-09-16 11:56:03 |
 70             | 0070    | app artifact kinds printed form      | t       | 2026-09-15 01:28:39 |
 69             | 0069    | app salary certificate claimed       | t       | 2026-09-14 01:42:30 |

 highest_applied | failed_rows | strictly_in_order | versioned_rows |
-----------------+-------------+-------------------+----------------+
 74              | 0           | t                 | 74             |
```

## Proof 2 — V0073's table and function

Columns exactly as V0073 declares them:

```
 column_name    | data_type                | is_nullable |
----------------+--------------------------+-------------+
 profile_id     | uuid                     | NO          |
 field_key      | text                     | NO          |
 field_number   | integer                  | NO          |
 previous_value | text                     | YES         |
 new_value      | text                     | YES         |
 edited_by      | text                     | NO          |
 edited_at      | timestamp with time zone | NO          |
```

Constraints and grants, because a table with the right columns and the wrong rights is the S5-01
failure mode — `fru_app` gets no DELETE, which is V0073's stated intent:

```
 conname                                  | contype | definition                                                                     |
------------------------------------------+---------+--------------------------------------------------------------------------------+
 edited_by_present                        | c       | CHECK ((edited_by <> ''::text))                                                |
 profile_field_edit_field_number_check    | c       | CHECK (((field_number >= 1) AND (field_number <= 54)))                         |
 profile_field_edit_profile_id_fkey       | f       | FOREIGN KEY (profile_id) REFERENCES app.profile(profile_id) ON DELETE RESTRICT |
 profile_field_edit_pkey                  | p       | PRIMARY KEY (profile_id, field_key)                                            |

 grantee      | privileges                                                    |
--------------+---------------------------------------------------------------+
 fru_app      | INSERT, SELECT, UPDATE                                        |
 fru_migrator | DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE |
```

`app.derived_provenance()` callable, and both disjuncts of its rule exercised — a profile with no
edit rows reads `digital`, a stored `manual` survives:

```
 no_edits_digital | stored_manual |
------------------+---------------+
 digital          | manual        |

 fru_app_can_execute | public_can_execute | volatility |
---------------------+--------------------+------------+
 t                   | f                  | s          |
```

`volatility = s` is STABLE, which V0073 requires so the planner can still use it in a WHERE clause.
PUBLIC is revoked.

## Proof 3 — REJ-03 withdrawn, and still explaining itself

```
 item_code | is_active | label_ar                                | label_en                                      |
-----------+-----------+-----------------------------------------+-----------------------------------------------+
 REJ-01    | t         | صور المستندات غير واضحة أو رديئة الجودة | Document images illegible or poor quality     |
 REJ-02    | t         | تعارض تفاصيل المستند مع السجل المدني    | Document details conflict with Civil Registry |
 REJ-03    | f         | فشل أو عدم وضوح مطابقة الوجه            | Face match failed or inconclusive             |
 REJ-04    | t         | شبهة تلاعب أو تزوير في المستند          | Suspected document tampering or forgery       |
```

**The second half could not be proved on a stored profile, and that is a finding rather than a
shortcut.** Staging holds **zero** rejections under any reason code:

```
 reason_code | rejections |
-------------+------------+
(0 rows)
```

So there is no profile already rejected under REJ-03 to resolve a label for. What can still be
proved is the mechanism, run as the two query shapes the code actually uses. Validation — which is
`JdbcReferenceCatalog.EXISTS`, the query behind `OperatorReviewService.validateReasonCode` — refuses
REJ-03 and still offers REJ-04:

```
 query_site                                       | rej03_offerable | rej04_offerable |
--------------------------------------------------+-----------------+-----------------+
 JdbcReferenceCatalog.EXISTS (validateReasonCode) | 0               | 1               |
```

Label resolution — `JdbcProfileViewRepository:131`'s join, with a synthetic history row standing in
for the profile staging does not have — still returns the Arabic:

```
 reason_code | label_ar                     | label_en                          |
-------------+------------------------------+-----------------------------------+
 REJ-03      | فشل أو عدم وضوح مطابقة الوجه | Face match failed or inconclusive |
```

That the join carries no `is_active` filter is the reason it works, and it is visible in the source:
`JdbcReferenceCatalog.EXISTS` (`:28`) filters `AND is_active = true`; `FIND` (`:35`) does not, and
neither does the view repository's join nor `JdbcProfileListRepository:90`. Withdrawal by flag
therefore does exactly what V0074's header claims — the code stops being choosable without any
recorded use of it becoming unreadable.

The honest limit: a real REJ-03 rejection on staging would be a stronger proof than a `VALUES` row.
It cannot be manufactured without writing to the database, which this session had no mandate to do.

## Gates

None run, and none owed. No application code, test, build file or resource was changed — the diff is
four Markdown files, and the `git status` below is the evidence for that claim.

## What was deliberately not done

- **The image was not redeployed.** Staging still runs the task definition it ran this morning. The
  schema is now *ahead* of the deployed jar, which is the safe direction — new objects the old code
  does not use. The reverse is what breaks endpoints per-request (S5-01).
- **No data was written.** Every query outside `flyway:migrate` ran on a read-only connection.
- **BL-144 was not closed.** Validate came back clean, which is a reading, not the gate the item asks for.

## A correction worth recording for the next session

The Secrets Manager payloads at `fru/staging/db/{migrator,app,sealer}` are **plain 40-character
strings, not JSON objects**. A fetch that pipes them through a JSON parser fails with
`Expecting value: line 1 column 1 (char 0)`, which reads like an empty response and invites the
wrong diagnosis — it cost this session a detour.

Three local facts that go with it: this machine has no `psql`; an SSM port-forward binds `127.0.0.1`
only, so a container cannot reach it through `host.docker.internal`, and queries were run through a
throwaway JDBC reader in the scratchpad against the driver already in `~/.m2`; and the console is
cp1256, so Arabic needs `-Dstdout.encoding=UTF-8` and a file, or it comes back as mojibake.

## Commit proof

Captured AFTER the push.

```
$ git push origin main
To https://github.com/Osmantou/Fr_user_update
   e448259..dedfece  main -> main

$ git status
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean

$ git log --oneline -2
dedfece S9-04: staging migrated to V0074, and it was at V0070 not V0072
e448259 S9-02: bring the session report current with its own session
```

Six files, staged by name rather than with `git add -A` (R-053). This section itself lands in a
follow-up commit, which is the only way a post-push status can appear inside the report it describes.

