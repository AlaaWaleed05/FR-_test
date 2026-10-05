# Uqudo API findings — 2026-08-21

Findings from the OpenAPI specifications and the Sudan document field pages, obtained
after `docs/sessions/2026-08-21-research-ad-002a-uqudo-integration.md` was written. These
close several of that report's §12 undetermined items and correct one assumption.

To be filed into `docs/components/uqudo-sdk.md`, `PROJECT_PLAN.md` and `RISKS.md`.

Sources:
- `docs/components/uqudo-face-api.openapi.yaml` (Face API, OpenAPI 3.0.1)
- `docs/components/uqudo-info-api.openapi.yaml` (Info API, OpenAPI 3.0.1)
- https://docs.uqudo.com/docs/kyc/uqudo-api/scan/country-specific-ids/sdn_id-sudan-id
- https://docs.uqudo.com/docs/kyc/uqudo-api/scan/passports/sdn-sudan

---

## 1. Face API — R-023 CLOSED

`POST /api/v1/face` (`initSession`), server `https://id.uqudo.io`. [DOC OpenAPI]

**The reference image is bytes, not an id.** Two accepted content types:
- `multipart/form-data` with `idPhoto` as `format: binary`
- `application/json` with `idPhoto` as `format: byte` (base64)

There is **no variant accepting an existing Uqudo image id**. The backend must obtain the
portrait bytes and upload them.

**Response `201`:** `{ "sessionId": "..." }` — described as *"Session id needed to trigger
the Face Session Flow in the uqudo SDK. It's valid for 10 minutes"*. This confirms both the
field name (previously undetermined) and the 600-second window from a second source.

**Hard limits:**
- `413` — max upload 5 MB
- `415` — JPEG and PNG only

**Also present, not used by us:** `POST /api/v1/face/match` (backend-only 1:1 compare, no
liveness — route C, already rejected), and three 1:N endpoints
(`/face/one-to-n/search`, `/insert`, `DELETE /search/{oneToNVerificationId}`) which explain
the `oneToNVerificationId` fields in the face object.

**Useful default:** `FaceMatchRequest.minimumMatchLevel` documents *"Minimum match level
1 → 5, the default value is 3"*. That is Uqudo's own default and the sensible starting
threshold for the **server-side** check on `face.matchLevel` — the threshold AD-002a
forbids letting the SDK enforce.

---

## 2. Info API — half of R-021 CLOSED

`GET /api/v1/info/img/{id}` (`img`). [DOC OpenAPI]

**`404` description: *"Resource not found or expired. Images are kept only for the duration
of the session, 30 minutes."***

This is the clock that actually binds, not the JWS `exp`. Combined with AD-002a's rule that
a scan is accepted only after its images download successfully, **the practical retry window
for a dropped upload is 30 minutes.** Stage 12's resume copy must say so.

**`200` content type is `image/jpeg`** — satisfies the Face API's JPEG/PNG requirement, so
the portrait can be piped straight through. Size is still unverified against the 5 MB limit,
but an extracted portrait will not approach it.

**`DELETE /api/v1/info/{sessionId}`** — *"allows you to delete the session data that have
been cached without waiting for the automatic deletion. The session id is the value of the
`jti` property of the JWS returned by the uqudo SDK."*

Two consequences:
- **Confirms `jti` = session id**, previously undetermined.
- **Worth calling deliberately.** Purging session data from Uqudo once our backend has
  downloaded and stored the images minimises how long customer identity data sits in the
  vendor's cache. A real privacy control, not housekeeping. **Rationale narrowed 2026-09-06:**
  this used to read "we run on FIB's tenant (R-001), so ... minimises how much customer identity
  data sits in another bank's account". The project now runs on its own tenant (OQ-010), so the
  third-party-disclosure half is gone; the control itself is unchanged.

**Both `GET /api/v1/info` variants exist only for the QR-code flow**, which this journey does
not use. Of the whole Info API only `/info/img/{id}` and the `DELETE` are relevant.

---

## 3. Sudan document fields — the parser contract

### The Civil Registry lookup key is `identityNumber`, NOT `documentNumber`

Both document types expose both. `documentNumber` is the MRZ value — passport booklet
number or card number. Querying the registry with it would fail or, worse, succeed against
the wrong record.

### SDN_ID (Sudan ID) — two card versions

| | Previous version | Latest version |
|---|---|---|
| Front | faceImage · name (Arabic) · dateOfBirth(+Formatted) · placeOfBirth (Arabic) · **identityNumber** · address (Arabic) · occupation (Arabic) | same, **plus bloodType** |
| Back | placeOfIssue · dateOfExpiryFull(+Formatted) · issueDate(+Formatted) · MRZ block | same, **plus name (English)** |

MRZ block, both versions: `secondaryId` · `primaryId` · `dateOfBirth(+Formatted)` ·
`dateOfExpiry(+Formatted)` · `documentNumber` · `nationality` · `issuer` · `sex` ·
`documentCode` · `mrzText` · `mrzVerified` · `opt1` · `opt2`.

**The parser must handle both versions.** An older card yields no English name — harmless,
since the provenance mapping takes both names from the Civil Registry.

### SDN passport — single field set

`issueDate(+Formatted)` · `placeOfBirth` · `dateOfBirthFull(+Formatted)` · **`fullName`
(English)** · **`fullNameArabic`** · **`identityNumber`** · `placeOfIssue` · `faceImage` ·
plus the same MRZ block, with `opt1` only.

**The passport carries `identityNumber`** — confirming that both accepted document types can
drive the Civil Registry lookup. No customer reaches stage 9 without a key.

### Field names differ per document type

The passport uses `fullName` / `fullNameArabic`; SDN_ID uses `name` (Arabic on front,
English on back in the latest version only). **There is no generic field set — the parser is
per-document-type.**

### Two smaller notes

- **`mrzVerified`** is an MRZ checksum boolean. Worth storing as an integrity signal on the
  profile and in audit.
- **SDN_ID carries `address` and `occupation` in Arabic on the card.** We also collect both
  from the customer. The provenance rule keeps them customer-entered — a card address may be
  years stale and the campaign exists to refresh it — but the document values exist if the
  bank ever wants a cross-check.

---

## 4. One new discrepancy to verify — [UNVERIFIED]

The scan field pages document **`faceImage` — "Base64 encoded face image of the user"**,
while `scan-object` documents **`faceImageId`** as a reference requiring a separate
authenticated download.

Most likely these describe different surfaces: the Scan API (server-side upload) returning
base64, versus the SDK result returning ids. **But if the SDK result carries base64 directly,
the backend never downloads the portrait before uploading it to the Face API, and stage 10
loses an entire round trip.**

One line to check on the first real JWS at S1-02. It changes the stage 10 backend design.

---

## 5. Net effect on open items

| Item | Status |
|---|---|
| R-023 — Face API request body | **CLOSED** — bytes, multipart or base64; never an id |
| Face session id field name | **CLOSED** — `sessionId` |
| 600-second face session window | **CONFIRMED** by a second source |
| Image retention window | **CLOSED** — 30 minutes |
| Image response format | **CLOSED** — `image/jpeg` |
| `jti` = session id | **CLOSED** |
| SDN_ID / passport OCR field names | **CLOSED** — tabulated above |
| Server-side match threshold default | **ANSWERED** — Uqudo's own default is 3 |
| JWS `exp` value | Still unverified — S1-02 |
| `setMinimumMatchLevel()` suppression | Still unverified — S1-02 |
| `faceImage` base64 vs `faceImageId` | **NEW**, unverified — S1-02 |
