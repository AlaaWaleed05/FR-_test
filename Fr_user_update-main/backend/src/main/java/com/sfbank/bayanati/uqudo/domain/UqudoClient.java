package com.sfbank.bayanati.uqudo.domain;

/**
 * Uqudo eKYC, reached only for journey Stages 8 and 10 (docs/journeys/customer.md). Implementations
 * are selected by configuration, never by a runtime {@code if (mock)} branch — see {@code
 * UqudoClientConfiguration}. Two exist: {@code stub.StubUqudoClient} ({@code
 * fru.uqudo.client=stub}) and the real {@code http.HttpUqudoClient} ({@code fru.uqudo.client=http},
 * S5-09), which was written against the JWS shapes and endpoint behaviour S1-02 logged live. They
 * share the whole payload parser ({@link UqudoJwsParser}); only the signature step differs, HS256
 * against a stub secret versus RS256 against Uqudo's JWKS.
 *
 * <p><strong>The app never reaches this port.</strong> It forwards the raw JWS from Uqudo's SDK
 * untouched; only the backend calls {@link #verifyAndParse}. See CLAUDE.md's hard rule and
 * docs/components/uqudo-sdk.md.
 */
public interface UqudoClient {

  /**
   * {@code POST /oauth/token} — a tenant-scoped bearer token for the SDK, minted fresh at point of
   * use (customer.md Stage 8: token issuance moved to the moment of tapping to scan, because the
   * token's lifetime — observed 1859 s at S1-02, not the documented 1800 — would otherwise go stale
   * while the customer reads the preparation screen). Not session-scoped — nothing about a specific
   * attempt is passed in, matching Uqudo's own contract (docs/components/uqudo-sdk.md: "The token
   * is TENANT-scoped, not per-customer").
   *
   * <p>Returns the expiry alongside the token since S8-15 (BL-114(a)) — see {@link
   * IssuedAccessToken} for why.
   */
  IssuedAccessToken issueAccessToken();

  /**
   * Verifies the JWS signature and every claim the backend is responsible for checking — {@code jti
   * == expectedSessionId} (on an enrolment JWS {@code jti} IS the session id the backend minted,
   * observed at S1-02), {@code data.nonce == expectedNonce}, {@code data.documents[0].documentType
   * == expectedDocumentType} (there is no top-level {@code documentType}), {@code exp} not yet
   * passed — then parses the payload into the quarantine-safe {@link ParsedEnrolmentResult}. {@code
   * sessionId} and {@code nonce} are backend-minted per attempt and handed to the SDK by the app;
   * the app echoes them back unchanged on submission, and Uqudo's own signature is what ties them
   * cryptographically to a genuine SDK session — this method does not need to remember having
   * issued them.
   *
   * <p>{@code exp} must be enforced here and nowhere else: at S1-02 a JWS re-presented one second
   * after {@code exp} still verified against the JWKS — Uqudo's only "enforcement" is that the
   * images are gone by then.
   *
   * @throws JwsVerificationException on any signature or claim failure — counted as a failed scan
   *     attempt by the caller; also thrown when {@code identityNumber} (the Civil Registry key) is
   *     absent, since a scan without it can never be accepted (fail closed, uqudo-sdk.md)
   * @throws ArtifactExpiredException if {@code exp} has passed — a stale-artifact case
   *     (R-012/R-021), NOT counted against the retry budget, unlike every other verification
   *     failure
   */
  ParsedEnrolmentResult verifyAndParse(
      String jws, String expectedSessionId, String expectedNonce, String expectedDocumentType);

  /**
   * {@code GET /api/v1/info/img/{id}} — downloads one image and verifies it against {@code
   * expectedChecksum} before returning it. Must be called for every image in a {@link
   * ParsedEnrolmentResult} <strong>before</strong> the scan is accepted (R-012/R-021's ordering
   * rule: a JWS can verify perfectly while Uqudo has already deleted its images — observed at
   * S1-02: images live exactly as long as the enrolment JWS, 2 hours, then 404).
   *
   * @throws ImageUnavailableException on 404/410 — image retention has expired
   * @throws ImageIntegrityException if the downloaded bytes do not match {@code expectedChecksum}
   */
  byte[] downloadImage(String imageId, String expectedChecksum);

  /**
   * {@code DELETE /api/v1/info/{sessionId}} — purges Uqudo's cached session data. Call only after
   * every image in the scan has been downloaded and checksum-verified: a deliberate privacy control
   * (we run on FIB's borrowed tenant, R-001), not housekeeping. Since AD-004 closed (S5-06), most
   * images are also durably stored ({@code app.artifact_ref.body}) by this point, so this genuinely
   * destroys only Uqudo's copy for those — a real backend original survives behind it. The
   * exception is the raw capture frames (AD-004, docs/components/persistence.md), whose bytes are
   * deliberately never stored — for those, this call really is the last copy going away, same as
   * before AD-004 closed. Ordering this strictly after acceptance still matters (a rolled-back
   * accept must not purge).
   *
   * <p><strong>Which id, observed live at S1-02 (2026-09-03):</strong> the endpoint returns 204 for
   * ANY UUID, whether or not it purged anything. For an enrolment the {@code jti} is the session id
   * and the purge works. For a face session the {@code jti} is a different UUID — purging by it
   * returned 204 and left the audit-trail image downloadable; purging by {@code data.sessionId}
   * returned 204 and the image was gone at once. Pass {@link ParsedFaceResult#sessionId()}, never
   * {@link ParsedFaceResult#jti()}.
   *
   * @param uqudoSessionId the enrolment JWS's {@code jti}, or the face-session JWS's {@code
   *     data.sessionId}
   */
  void purgeSession(String uqudoSessionId);

  /**
   * {@code POST /api/v1/face} — uploads the reference portrait ({@code
   * documents[0].scan.faceImageId} from stage 8's enrolment, per uqudo-sdk.md's "Face matching —
   * how it actually works") and returns Uqudo's own Face Session id, valid 10 minutes. Called fresh
   * on every stage-10 attempt: the session and its uploaded image are deleted after 600 seconds, a
   * tighter clock than the enrolment token, so nothing about it survives a retry.
   *
   * @param referenceImageBytes JPEG or PNG, max 5 MB (uqudo-sdk.md, Face API OpenAPI)
   * @return Uqudo's Face Session id — passed to the app's {@code
   *     FaceSessionConfigurationBuilder.setSessionId()} and expected back as the eventual JWS's
   *     {@code data.sessionId} (NOT its {@code jti} — see {@link ParsedFaceResult})
   */
  String createFaceSession(byte[] referenceImageBytes);

  /**
   * Verifies the face-session JWS signature, {@code exp}, and its {@code data.sessionId ==
   * expectedFaceSessionId} binding, then parses the payload into {@link ParsedFaceResult}. The
   * binding is {@code data.sessionId}, observed at S1-02 — {@code jti} is a separate UUID on a real
   * result, and the S3-13 stub's {@code jti == expectedFaceSessionId} check would have rejected
   * every genuine face result. A nonce set via {@code FaceSessionConfigurationBuilder.setNonce} is
   * echoed as {@code data.nonce} (also observed); the backend does not mint one for face sessions
   * today, so it is accepted when present and not required.
   *
   * <p>A signed JWS from this method means liveness already passed inside the SDK — Uqudo issues no
   * JWS at all on a liveness failure (AD-002a); {@code match}/{@code matchLevel} on the returned
   * result answer only the face-match question, never liveness.
   *
   * @throws JwsVerificationException on any signature or claim failure — counted as a failed
   *     liveness attempt by the caller, exactly like a rejected enrolment JWS
   * @throws ArtifactExpiredException if {@code exp} (observed {@code iat + 600}) has passed — not
   *     counted against the retry budget
   */
  ParsedFaceResult verifyAndParseFaceSession(String jws, String expectedFaceSessionId);

  /**
   * BL-028: the partial artifact from a face session the SDK terminated ({@code
   * returnDataForIncompleteSession()} enabled). Same signature, {@code exp} and {@code
   * data.sessionId} checks as {@link #verifyAndParseFaceSession}, but the {@code face} object and
   * every field inside it are tolerated absent — whether Uqudo's partial JWS carries {@code
   * match:false} is {@code [UNVERIFIED]}, so the caller must degrade gracefully to an audit record
   * without match detail.
   *
   * @throws JwsVerificationException on a signature or binding failure
   * @throws ArtifactExpiredException if {@code exp} has passed
   */
  ParsedIncompleteFaceResult verifyAndParseIncompleteFaceSession(
      String jws, String expectedFaceSessionId);
}
