package com.sfbank.bayanati.identityscan.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * AD-008 / BL-041 — the identity-scan stage is atomic, so a device-less re-entry does not inherit
 * the previous session's identity artifacts.
 *
 * <p>The write happens in the Stage 1b re-entry transaction ({@code
 * contactchannels.service.ContactChannelsService}), but the invariant is identity-scan's: what
 * counts as an inheritable artifact is this feature's knowledge, not contact-channels'. Hence a
 * narrow port here rather than {@code ContactChannelsService} depending on the whole {@link
 * IdentityScanRepository} — the caller needs one operation and should not acquire a surface that
 * lets it insert cycles or spend scan attempts.
 *
 * <p><strong>Why this exists at all.</strong> Before it, the re-entry path superseded nothing, so a
 * person entering another customer's account number on a new device inherited that customer's
 * accepted identity cycle, document scan, extracted data, Civil Registry data, face match and
 * signature, and could submit a profile carrying all of it while editing the data and receiving
 * OTPs on their own handset. The identity artifacts are system-derived proof that a specific human
 * was present with a physical document; they must not survive a re-entry that cannot prove it is
 * the same human. customer.md l.162-174, restated at Stage 13's no-local-state resume case.
 */
public interface DeviceLessReentrySuperseder {

  /**
   * Supersedes the profile's inherited identity, if it has one, and resets the per-type scan
   * budget. A no-op returning {@link SupersededIdentity#NOTHING} when the profile has no {@code
   * active} identity cycle — which is what keeps the same-device path untouched (see below).
   *
   * <p><strong>The device-less trigger.</strong> The backend receives no device or session identity
   * at Stage 1b ({@code ContactChannelsRequest} is six fields, and the endpoint is unauthenticated
   * by design, R-051), so "no local state" cannot be detected from the request — and a
   * client-asserted flag on an unauthenticated endpoint would be attacker-controlled anyway. It is
   * detected from journey position instead: <em>a Stage 1b re-POST for a profile that already holds
   * an {@code active} identity cycle</em>. No legitimate same-device route into Stage 1b can hold
   * one:
   *
   * <ul>
   *   <li>The Stage 2 "wrong phone number?" correction ({@code channel_verification_screen.dart})
   *       lives on the channel-verification screen, which precedes the Stage 8 scan.
   *   <li>Every other in-journey route back to account entry — and therefore to Stage 1b — calls
   *       {@code abandon()}/{@code _clearSession()} first ({@code
   *       channel_verification_screen.dart}, {@code contact_channels_screen.dart}, {@code
   *       session_pending_screen.dart}), so the local state is gone by the time the request is
   *       sent, which makes it a device-less re-entry by definition rather than an exception to
   *       one.
   * </ul>
   *
   * <p>(An earlier draft of this comment argued instead that {@code ResumeContactChannels} is
   * reached "only on an unrecognised {@code resumeStage}". That is wrong — {@code
   * 'contactChannels'} is a real value written by {@code checkAccount()} and it does fall through
   * to the switch's default arm. The conclusion held, but for the reason above, not that one.
   * Corrected after {@code @agent-reviewer} checked the mobile switch.)
   *
   * <p>The cycle need not be <em>accepted</em>. AD-008 makes the identity-scan stage atomic — a
   * device-less re-entry does not resume inside it at any point — so a cycle whose scan landed but
   * whose Stage 9 registry review is still open goes too. Leaving it would show a re-entering
   * impostor the previous customer's document and registry data on the review screen.
   *
   * <p><strong>What it supersedes.</strong> The {@code active} identity cycle — which by itself
   * removes every cycle-keyed artifact from every customer-facing read: scan result and all its
   * extracted document columns, Civil Registry data, face result, {@code doc_front}, {@code
   * doc_back}, {@code doc_front_frame}, {@code doc_back_frame}, {@code portrait_uqudo}, {@code
   * portrait_registry} and {@code face_audit_trail} — plus, separately, the profile-keyed {@code
   * signature} artifact, which no cycle supersede can reach.
   *
   * <p><strong>What it deliberately does not touch.</strong> Artifact bodies are retained, not
   * deleted (customer.md l.174-175: prior artifacts are "retained in the backend, marked
   * superseded, so the audit trail records that a scan occurred and was replaced").
   * Manually-entered data — contact, social, address, occupation, income, identity type — is kept,
   * per AD-008: the customer is not punished by re-typing what is not identity-derived. {@code
   * app.profile.status} is not transitioned, matching every existing supersede caller. And of the
   * scan budget, only the two per-type counters reset — {@code scan_attempts_total} and {@code
   * scan_blocked_until} survive, because AD-008's promise that the 24-hour total block still bounds
   * abuse across sessions depends on exactly that.
   *
   * @param profileId the profile being re-entered
   * @param now supersession timestamp, from the caller's clock
   * @return what was actually superseded, for the audit event
   */
  SupersededIdentity supersedeInheritedIdentity(UUID profileId, Instant now);

  /**
   * Moves a superseded profile from {@code awaiting_registry} back to {@code in_progress} (legal
   * per V0020) and writes the matching {@code app.profile_status_history} row, actor {@code
   * system}. Call only when {@link SupersededIdentity#registryPauseToClear()} is true, and only
   * after the {@code identity_superseded} event is on the chain, since that event is what the
   * history row references.
   *
   * <p><strong>Why this is not optional.</strong> A registry lookup that fails leaves the profile
   * at {@code awaiting_registry} with an {@code active}, un-accepted cycle. Superseding that cycle
   * without moving the status strands the profile permanently: {@code issueToken} and {@code
   * submitScan} refuse while the status says {@code awaiting_registry}, and the three Stage 9
   * actions and the review read all enter through {@code requireActiveReview}, which needs the
   * cycle that was just superseded. V0020's only {@code awaiting_registry -> in_progress} arc is
   * written by {@code retryRegistryLookup}, which needs that same cycle, and no sweeper exists to
   * recover it — so the only escape would be an operator manual completion. Found by
   * {@code @agent-reviewer}.
   */
  void clearRegistryPauseAfterSupersession(UUID profileId, Instant now, long auditEventId);
}
