# What is the life of a printed form once it is stored?

> **SUPERSEDED IN PART BY AD-022 (2026-09-16).** MORE THAN ONE POINT is superseded. There are not two `kind` values — V0072 withdrew
> `printed_form_attributed`, leaving `printed_form` alone (decision 2). Decision 7's print audit
> event no longer names a variant; that member was dropped at S9-01. Decision 8's reasoning that
> "manual completion sets `status = submitted`, so the branch-visit case is covered" is void —
> manual completion is gone, and `SubmissionService` is the only path into `submitted`. The
> lifecycle itself — store-then-stream, every print its own artifact, no supersession — stands. Everything not named above still stands and is still the reason the current design is
> what it is. This is a
> dated decision record, not a live spec — it is stamped rather than rewritten or deleted.

Type: grilling
Status: resolved
Blocked by: —

## Question

The brief: "printed things from backoffice should be saved as pdf in backend just as they way they
were printed."

**"Just as they were printed" already settles one thing** and it should be recorded rather than
re-argued: the server renders the PDF once, streams those bytes to the operator, and stores the
same bytes. A browser-side render would make the stored file a re-render — a near-copy nobody can
prove matches what the operator held. So: server-rendered, one render, two destinations.

What is still open:

1. **Where do the bytes live?** `AD-004` settled artifact storage: bytes in
   `app.artifact_ref.body` in Postgres, no object store. A printed form is a new `kind` on the
   existing `artifact_ref_kind_check`. Confirm that is right rather than assuming it — a PDF of
   the whole profile is larger than a portrait, and this table is also covered by
   `app.purge_abandoned_artifacts()`, whose 90-day purge must **not** eat a printed record.
2. **Is a reprint a new artifact or a supersession?** `V0063` already carries a `superseded` state
   for artifacts. A second print has a different timestamp and possibly a different operator, so
   it is genuinely a different document. Recommended: every print is its own artifact, nothing
   superseded — the audit question is always "what did this operator hold, at that moment".
3. **Retention.** How long is a printed form kept? It is the densest PII object in the system:
   one file, one customer, every field. Longer than the profile? Shorter?
4. **Does the stored PDF get a checksum and the `app.artifact_read()` treatment**, like every
   other artifact? (It should — that function refuses a checksum mismatch outright.)
5. **Does storing it fail the print?** If the render succeeds and the store fails, does the
   operator get the file anyway? One transaction, or best-effort? A print the customer received
   but the bank has no record of is the failure mode that matters.
6. **Who may re-download a stored print, and is that its own audit event** (distinct from the
   original print event)?

## Context

- `PROJECT_PLAN.md` AD-004 · `V0053`, `V0054`, `V0055`, `V0059`, `V0063`
- `docs/components/persistence.md`

## Carried in from ticket 03 (2026-09-13)

**A stored print can never be re-derived and compared.** Whichever renderer wins, the render is not
byte-reproducible across runs — FOP, iText and Chromium all stamp a creation date and a document
ID. That is fine for "one render, two destinations", but it means the stored checksum is the *only*
proof of what was printed, not a value anyone can recompute from the profile later. It strengthens
the case for question 4 above (give the printed PDF the `app.artifact_read()` treatment) and it
means question 2's "reprint" can never be verified against the original by re-rendering.

---

## Decision

Product-owner, 2026-09-13, by interview.

1. **The bytes live in `app.artifact_ref`**, as a new `kind` on the existing check constraint,
   with a checksum and read through `app.artifact_read()` like every other artifact — which
   refuses a checksum mismatch outright rather than returning a wrong document. This matters more
   here than anywhere else, because (ticket 03) the render is **not byte-reproducible** across
   runs: FOP, iText and Chromium all stamp a creation date and a document id, so the stored
   checksum is the ONLY proof of what was printed. Nobody can recompute it from the profile later.

   **The 90-day purge is not a threat to this, contrary to this ticket's question 1.** `V0055`
   scopes `app.purge_abandoned_artifacts()` to `p.status = 'abandoned' AND p.last_activity_at <
   cutoff`, and decision 8 below forbids printing anything but `submitted` and `approved`. No
   stored print can ever sit on a profile that later abandons.

2. **Every print is its own artifact. Nothing is superseded.** **The row is PROFILE-keyed with
   `cycle_id` NULL** — the shape `signature` and `salary_certificate` already use — and takes **no**
   per-profile unique index. This is load-bearing, found at review: `app.artifact_ref` carries
   `UNIQUE (cycle_id, kind)` (`V0008:82`), so a cycle-keyed print would make the SECOND print of a
   profile a constraint violation, which contradicts this very decision. Many prints per profile is
   the expected case, not an edge one.

   **TWO kinds, not one (2026-09-13, ticket 10 decision 7).** The printed form ships in an
   unattributed and an attributed variant, and they are two `kind` values on the existing
   `artifact_ref_kind_check` rather than one kind with a flag — `artifact_ref` has no generic
   metadata column, and `kind` is already the enumeration of what an artifact IS. A stored row
   therefore says which form it is without anyone opening the PDF. Everything else in this ticket
   applies to both identically: own artifact, no supersession, checksum, `app.artifact_read()`,
   retention, and the store-then-stream transaction.
    `V0063`'s `superseded` state is not
   used here. A second print has a different timestamp and possibly a different operator, so it is
   genuinely a different document, and the audit question is always "what did THIS operator hold,
   at that moment". Ticket 03 strengthens it: a reprint could never be verified against the
   original by re-rendering anyway.

3. **Retention is exactly as long as the profile's PII, and no longer** — the printed form must
   never outlive the record it documents, because it is the densest PII object in the system: one
   file, one customer, every field, plus both portraits.

   **CORRECTED AT REVIEW: this rule must be BUILT, it does not exist.** The decision originally
   asserted that "`app.profile`'s PII is nulled at 90 days" as though that were live behaviour. It
   is not. The only 90-day rule in the system is the *abandoned-profile* retention described in
   `docs/journeys/customer.md:1324`, itself marked provisional pending OQ-001; `V0055`'s own header
   says full profile PII nulling "stays unwritten by any code"; and nothing in `backend/src` writes
   `pii_purged_at`. For the only printable statuses — `submitted` and `approved`, decision 7 — there
   is **no PII-nulling rule at all today**. So this decision states the rule the print must follow
   ONCE such a rule exists, and until then a stored print is retained indefinitely alongside its
   profile. Whoever builds profile retention owns the print's retention in the same change.

4. **Server-rendered once, two destinations** — already settled by the brief's "just as they were
   printed" and recorded here rather than re-argued. A browser-side render would make the stored
   file a re-render that nobody can prove matches what the operator held.

5. **Store first, then stream, in ONE transaction. A failed store means the operator gets no
   file.**

   **The reason is NOT the one this ticket's Question gives.** Product-owner correction,
   2026-09-13: **the printed form is INTERNAL. It is not handed to the customer.** So "a print the
   customer received that the bank has no record of" — the Question's stated failure mode, and the
   reasoning this decision originally borrowed — describes something that cannot happen.

   The decision is unchanged, because the real failure mode survives the correction and is arguably
   worse: **a copy of the densest PII object in the system exists on someone's disk, and the bank
   has no record that it was ever produced.** An unrecorded internal copy is exactly what the audit
   trail is supposed to make impossible, and with four-eyes gone (R-054) that trail is the only
   control there is. A failed print is an inconvenience; an untracked one is a hole nothing can
   reconstruct.

6. **Re-download is operator and admin only — never viewer** (ticket 06: viewer may not print, and
   a re-download is a print in every way that matters) — **and it is its own audit event, distinct
   from the original print.** A log that cannot tell a re-download from the original print cannot
   answer how many copies of that document exist.

7. **The printed form is INTERNAL — it is never handed to the customer.** Product-owner
   correction, 2026-09-13, recorded here because two decisions borrowed reasoning from the opposite
   assumption (decision 5 above, and ticket 10 decision 7).

   **What this closes:** a `submitted` profile is both printable and editable, so a form can be
   printed and the record then edited. Raised at review as an open gap — "the customer holds a
   document that contradicts the bank's record". That framing is void. What actually remains is an
   internal file holding a point-in-time snapshot that a later edit superseded, which is what
   decision 2 already describes on purpose ("what did THIS operator hold, at that moment") and what
   a filing system does by nature. The edit is on the audit trail; the snapshot is honest about its
   own date. **Whether a branch must reprint after an edit is a bank PROCESS question, not a system
   one**, and is deliberately not answered here.

9. **The salary certificate is a SEPARATE DOCUMENT, never part of the form, and printing it is the
   operator's choice.** Product-owner, 2026-09-13, answering the question S8-24 raised when BL-136
   made the certificate viewable on screen.

   The certificate is a file in its own right — the customer's own document, not a section of the
   bank's form — so it is **never merged into the form's PDF**. At print time the operator chooses
   between two outcomes: **the form alone**, or **the form and the certificate as two separate
   documents**. There is no variant of the form that contains the certificate.

   **HOW the choice is made — product-owner, 2026-09-13, sharpening this decision the same day.**
   The certificate is visible on the profile itself (BL-136's sixth tile, shipped at S8-24), and the
   choice to print it is **a question asked at the moment the operator prints**, not a setting, not
   a second print action, and not a property of the profile. Three properties follow, and they are
   requirements rather than commentary:

   - **Asked every time, defaulting to NO.** A remembered preference is the failure mode this
     shape exists to prevent: an operator who once opted in would go on printing customers' pay
     documents indefinitely without ever deciding to again. "Asked" means asked.
   - **Not offered at all when the profile has no certificate.** It is optional and gates nothing,
     so absence is the ordinary case, and a greyed-out or no-op option invites an operator to
     wonder whether the document exists and they simply cannot reach it. Note the limit honestly:
     BL-122 means an absent certificate cannot be told from a failed upload, so "no certificate"
     here means "no committed artifact", which is all the system can say.
   - **Orthogonal to the variant choice.** The print already asks which of two forms to produce
     (ticket 10 decision 7, drawn as the menu at `Design_3/backoffice/Main.dc.html:236`). The
     certificate question applies identically to both, so it belongs in that same interaction rather
     than as a second prompt stacked behind it. **The widget is ticket 08's to design** — a checkbox
     in the print menu and a confirm step after it are both consistent with this decision; two
     sequential dialogs for one print are not.

   **Why this is not a formatting preference.** Ticket 04 decision 4 settled the form's images as
   both portraits and the signature, no document scans; the certificate was never weighed there —
   the same blind spot ticket 02 had, which BL-136 proved produces wrong answers. This closes it
   explicitly rather than by omission: the certificate is out of the form's field set permanently,
   and reachable through a separate print instead.

   **It also sidesteps a toolchain wall.** A salary certificate may be `application/pdf`
   (`SalaryCertificateService.ALLOWED_CONTENT_TYPES`, and the mobile picker offers it — S8-24).
   Apache FOP (AD-014) renders XSL-FO to PDF and **cannot concatenate an existing PDF**; merging
   would have meant adding PDFBox purely to staple a customer's file onto the bank's. Separate
   documents need none of that: a stored certificate is streamed as it is.
   **SUPERSEDED 2026-09-14 — see the widening below. The product owner wants one document, so
   PDFBox is now a main-scope dependency and the certificate is appended as pages.**

### WIDENED 2026-09-14 (S8-33): "the salary certificate" becomes "the attachments"

Product-owner ruling, on reading the second render. The opt-in question is no longer "also print
the salary certificate" — it is **"also print the attachments"**, and the attachments are the five
images (identity document front, the portrait off it, the Civil Registry photograph, the liveness
frame, the signature) TOGETHER WITH the salary certificate.

Decision 9's three properties are **restated, not dropped**, with the first widened to match:

- **Not offered at all when there are no committed attachments** — widened from "when the profile
  has no certificate". A manually completed profile has no artifacts of any kind, and offering an
  opt-in that produces nothing invites an operator to wonder whether the documents exist and they
  simply cannot reach them.
- **Asked every time, defaulting to NO.** Unchanged, and for the same reason: an operator who once
  opted in would otherwise go on printing customers' documents indefinitely without deciding to.
- **Orthogonal to the variant choice**, in the same interaction. Unchanged.

**An absent image gets no attachment page.** The form's own images section still shows «غير متاح»
for it, so the absence is visible; it is simply not given a sheet of paper.

**THE MECHANISM CHANGED, and this reverses the "separate documents" half of decision 9.** The
product owner was explicit: *"salary certificate doesn't have to be a separate file, i just mean to
print it in a separate paper."* A separate PAGE, not a separate FILE. So the print is ONE document:
FOP lays out the form and the image attachments, and PDFBox appends the certificate's pages.

That means **PDFBox moves to main scope**, which decision 9 had avoided — "merging would have meant
adding PDFBox purely to staple a customer's file onto the bank's". That reasoning rested on the
certificate being a separate document, and it no longer is. The pom records it at the dependency.

Two consequences, recorded rather than left to be found:

- The certificate's pages are **copied, not re-rendered**, so the operator holds the customer's own
  document. The stored artifact is untouched and its checksum still describes the uploaded bytes.
- **The footer's page count covers the bank's pages only.** «صفحة N من M» is resolved by FOP over
  what FOP laid out, so a six-page form-plus-attachments says "6" while the file has seven. Making
  them agree would mean computing the total before the render and giving up
  `fo:page-number-citation-last`. The bank's numbering covering the bank's document, with the
  customer's own file appended behind it, is the more honest reading.

### What this decision does NOT settle — for this ticket and ticket 09, not for whoever notices first

   - **Is a printed certificate STORED as a new artifact?** Decision 2 says every print is its own
     artifact, but the certificate already IS one — immutable, checksummed, `app.artifact_read()`
     readable. *Recommendation: stream the existing artifact, store nothing new.* A per-print copy
     would duplicate PII for no evidentiary gain, since the original cannot change under it. If that
     recommendation is taken, decision 2 needs narrowing to say it governs the RENDERED form only.
   - **Is the certificate re-rendered or passed through?** *Recommendation: passed through
     byte-identical, image and PDF alike.* It is evidence; re-encoding it makes the stored
     checksum describe something nobody printed. Note this means the operator's printer, not the
     renderer, decides how a 10 MB photograph lands on A4.
   - **What the audit records.** Ticket 09 decision 2 fixed print at ONE event naming the variant.
     A certificate now rides along or does not. *Recommendation: still one event, carrying whether
     the certificate was included and its artifact id* — decision 6's stated purpose is answering
     "how many copies of that document exist", and a certificate leaving the building unrecorded
     defeats it as squarely as an unrecorded form would.
   - **A profile with no certificate must not be offered the choice.** It is optional and gates
     nothing, and BL-122 means an absent one cannot be told from a failed upload.

8. **Only `submitted` and `approved` profiles may be printed.** Manual completion sets `status =
   'submitted'` (`JdbcManualCompletionRepository`), so the branch-visit case is fully covered.
   **What this does to decision 3, stated accurately at review:** it does not reconcile decisions
   1 and 3 — it makes decision 3 **inoperative for now**. No purge or nulling event can reach a
   `submitted` or `approved` profile today, so "retained as long as the profile's PII" currently has
   no trigger and constrains nothing. It is a rule waiting for the retention work in decision 3's
   correction, not a rule in force.
