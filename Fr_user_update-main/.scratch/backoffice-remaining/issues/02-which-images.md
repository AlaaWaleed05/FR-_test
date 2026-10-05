# Which artifacts does an operator actually see, and in what arrangement?

Type: prototype
Status: resolved
Blocked by: —

## Question

The brief names two images: the identity document front, and the Civil Registry portrait. Six
artifact kinds carry bytes on a submitted profile: `doc_front`, `doc_back`, `portrait_uqudo`,
`portrait_registry`, `face_audit_trail`, `signature`.

The Uqudo portrait against the registry portrait **is the comparison the review stage exists to
make**. Showing only the document front and the registry photo would drop it. That needs to be a
decision, not an omission.

1. Which kinds appear, and which are reachable but not shown by default?
2. `doc_front_frame` / `doc_back_frame` **must not be offered** — `AD-004` deliberately stores no
   bytes for them, yet `ArtifactRefView` currently lists them as metadata. Confirm the endpoint
   refuses them rather than returning an empty body.
3. What is the arrangement that makes a face comparison actually performable — side by side at
   what size, zoomable how? Build a rough prototype against real stored artifacts on a seeded
   profile and react to it.
4. What does a viewer see, given a viewer may not print or edit? Images too, or not? Note that `operator/domain/OperatorAccessLevel.java` l.5 already asserts a viewer "can search, filter and view everything a profile holds, **including images**" — so a default is already written down, and changing it is a decision, not an omission.

## Context

- `V0026__app_artifact_kinds_signature_and_portraits.sql` · `V0053`, `V0054`, `V0055`
- ~~`backoffice/src/profiles/portraitPlaceholder.ts` — today's grey silhouette~~ (deleted at S8-23)
- `backoffice/src/profiles/ProfileDetailPage.tsx` — ~~today's metadata-only attachments table~~
  (the table is gone; the tiles live in `profiles/ArtifactContactSheet.tsx`)
- `BACKLOG.md` BL-075 lists three requirements for whoever builds this — read it in full.
- **No real customer images anywhere.** Prototype against seeded/stub artifacts only.

---

## Decision

Product-owner, 2026-09-13, after flipping three structurally different variants against REAL stored
artifacts on a seeded profile in a browser (the prototype this ticket asked for). Variants were
mounted on the real `/profiles/:id` route behind `?variant=A|B|C`, so each was judged against the
real header, density and data rather than in a vacuum.

**Chosen: Variant B — the flat contact sheet.** A uniform grid of equal tiles, no privileged pair,
no attachments table at all, each tile clickable into a pop-up (the S7-12 product-owner request).
Rejected: A ("comparison first", a hero portrait pair with everything else demoted) and C
("stage and rail", master/detail with comparison as a mode).

Two corrections applied to B after seeing it:

1. **The tile caption is the image NAME, not metadata.** `ID Document`, `Document Photo`, `CR`,
   `Liveness`, `Signature`. MIME type, byte size and checksum are gone from the tile — an operator
   reviewing a face does not read a checksum, and five tiles of grey type made the grid harder to
   scan than the images it exists to show.
2. **Only those five kinds appear.** Everything else is absent rather than shown-and-refused.

**CORRECTED 2026-09-13 at S8-24 — there are SIX, and this ticket's opening premise is why there were five.** This document opens "Six artifact kinds carry bytes on a submitted profile" and lists six. There are seven: `JdbcSalaryCertificateRepository` writes a `body` for `salary_certificate` exactly as the others do. So "the set is the set" was written without the seventh ever being weighed, and the exclusion was an arithmetic slip rather than a ruling. Put to the product owner and answered: **yes, an operator may view the salary certificate.** It is the sixth tile, captioned «شهادة المرتب» and last in order. `doc_back` is untouched by this — it was a real decision and remains excluded. Filed as BL-136, closed at S8-24.

**One caveat for whoever builds BL-075, flagged at review:** those five captions are ENGLISH in an
Arabic-first, fully RTL UI. That is consistent with the backend's own `ArtifactRefView.label`,
which `api/types.ts:224-227` calls "a system-origin tag rather than translatable customer-facing
copy" — so it is defensible, not accidental. But it was not put to the product owner as a
language decision, and swapping the five strings for Arabic is a one-line change. Decide it
deliberately when the real tiles are built.

**DECIDED 2026-09-13 at S8-23, when the real tiles were built: ARABIC.** Put to the product owner as a language decision, which is what both this ticket and ticket 08 said had never happened. The five captions are «وثيقة الهوية», «صورة الوثيقة», «السجل المدني», «إثبات الحياة» and «التوقيع», in `backoffice/src/profiles/artifactTiles.ts`. The defence for English was that it matched the backend's system-origin `ArtifactRefView.label`; that label is no longer rendered on any screen, so the consistency argument it rested on is gone as well.

Answers to this ticket's numbered questions:

1. **Which kinds** — the five above. `doc_front`, `portrait_uqudo`, `portrait_registry`,
   `face_audit_trail`, `signature`. None are "reachable but not shown": the set is the set.
2. **`doc_front_frame` / `doc_back_frame` must not be offered — CONFIRMED, and the cost of getting
   it wrong is now measured rather than predicted.** Live against the dev database, `doc_front_frame`
   declares `content_type: image/jpeg` and **1,726,304 bytes** with `body IS NULL`. It is the
   LARGEST row in the attachments table and it has no bytes at all, so a link to it would have been
   a guaranteed broken fetch on the most prominent row. It is now excluded by name, not merely
   refused by the endpoint.
3. **Arrangement** — a `repeat(auto-fill, minmax(190px, 1fr))` grid, 190px tiles, click-to-enlarge
   in a modal. Zoom is the modal, not an in-tile control.
4. **What a viewer sees — UNCHANGED, and deliberately not re-decided here.**
   `operator/domain/OperatorAccessLevel.java:5` already asserts a viewer "can search, filter and
   view everything a profile holds, including images". That written default stands; this ticket did
   not put it to the product owner, so a later session must not read this closure as having
   confirmed it.

## A finding this prototype produced that the ticket did not ask for

**`portrait_registry` is not a photograph in any stubbed environment.** The civil-registry stub
stores the 32-byte ASCII string `stub-photograph-not-a-real-image` under `content_type: image/jpeg`.
So the Uqudo-vs-registry face comparison — the comparison this ticket calls "the comparison the
review stage exists to make" — **cannot be judged at all with `fru.civil-registry.client=stub`**,
and any `<img>` pointed at it renders broken.

Two consequences worth carrying:

- A row's `content_type` is DECLARED and can lie. This strengthens BL-075 requirement (iv), pinning
  the served content type from an allow-list rather than trusting the column.
- Any future acceptance walk that intends to prove the face comparison needs a real Civil Registry
  response, not the stub. A stubbed walk can prove the layout and nothing else.
