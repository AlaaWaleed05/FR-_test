package com.sfbank.bayanati.identityscan.domain;

/**
 * What a device-less re-entry actually superseded, for the {@code identity_superseded} audit event.
 *
 * <p>Both flags are recorded rather than one, because they can legitimately differ: a customer
 * interrupted between Stage 9's acceptance and Stage 11's signature has an accepted cycle and no
 * signature row, so {@code cycleSuperseded} is true while {@code signatureSuperseded} is false. An
 * event that collapsed the two would make that ordinary case indistinguishable from a partial
 * write.
 *
 * @param cycleSuperseded whether an {@code active} identity cycle was moved to {@code superseded} —
 *     with it, every cycle-keyed artifact (scan result, extracted document data, Civil Registry
 *     data, face result, document images, both portraits, the face audit-trail image) stops being
 *     readable, since every customer-facing read joins {@code ic.state = 'active'}
 * @param signatureSuperseded whether a {@code committed} signature artifact was moved to {@code
 *     superseded}. The signature is profile-keyed (V0026), so the cycle supersede cannot reach it
 *     and this is the flag that proves BL-041's central hole was closed on this profile
 */
public record SupersededIdentity(
    boolean cycleSuperseded, boolean signatureSuperseded, boolean registryPauseToClear) {

  public static final SupersededIdentity NOTHING = new SupersededIdentity(false, false, false);

  /** True when this re-entry superseded anything at all — the audit event is written only then. */
  public boolean anything() {
    return cycleSuperseded || signatureSuperseded;
  }
}
