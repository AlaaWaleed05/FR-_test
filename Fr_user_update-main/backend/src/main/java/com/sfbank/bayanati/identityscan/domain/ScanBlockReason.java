package com.sfbank.bayanati.identityscan.domain;

/**
 * Why a particular {@link ScanTemporarilyBlockedException} was raised, when the refusing arm
 * actually knows (S8-15, BL-118). Rides alongside the existing {@code blockedUntil} on the
 * unchanged {@code SCAN_BLOCKED} response.
 *
 * <p><strong>Why this is a second property and not a second error code.</strong> Replacing the code
 * would have been a regression for every app already in the field: {@code
 * ScanConflictCode.fromWire} degrades an unrecognised code to {@code unknown}, and Stage 8 routes
 * {@code unknown} to a resync that drops {@code blockedUntil} entirely — so a capped customer who
 * today sees an honest block screen with a deadline would instead be resynced. Additive, the way
 * BL-021 added {@code blockedUntil}: an app that does not read this field behaves exactly as it did
 * before.
 *
 * <p><strong>{@code null} means "this arm does not know", never "not capped".</strong> This is the
 * distinction the mobile half must be written against, and it is not a hedge — it is the literal
 * truth of the code. Only {@code issueToken} can tell why a block exists, because only it evaluates
 * the cap. The three other sites that report a block ({@code submitScan}, {@code cancelScan},
 * {@code reportWrongNumber}) re-report a {@code blocked_scan} row that was applied earlier, by an
 * arm they cannot see, so they assert nothing. Reading an absent reason as "an ordinary block"
 * would re-create BL-114(b) — a screen stating something the app never observed — at a new door.
 *
 * <p>Stage 9's wrong-number block needs no value here: it is not raised as this exception at all.
 * {@code reportWrongNumber} returns {@code Optional<Instant>} on a 200, so it is already
 * distinguishable from every refusal below by response shape and endpoint.
 */
public enum ScanBlockReason {

  /**
   * The profile has spent its lifetime allowance of scan-token mints (BL-039 Slice B). Permanent by
   * ruling — the 24-hour block it reuses will never lift for this profile, because the cap is
   * re-evaluated and re-applied on every subsequent request. Ruling A (BL-114) still forbids copy
   * that names manual completion or promises a remedy.
   */
  LIFETIME_CAP
}
