package com.sfbank.bayanati.profile.domain;

/**
 * {@code app.profile_channel.state} (V0007). Distinguishes three outcomes the profile means
 * differently when the bank later tries to reach a customer (docs/journeys/customer.md, Stage 2
 * "What is recorded"):
 *
 * <ul>
 *   <li>{@link #VERIFIED} — proven, saved, usable for contact. Never written at Stage 1b — nothing
 *       is verified until Stage 2.
 *   <li>{@link #DECLINED} — deselected at Stage 1b (or disabled at this deployment), never
 *       attempted.
 *   <li>{@link #UNVERIFIED} — selected and attempted, never yet proven.
 * </ul>
 */
public enum ChannelState {
  VERIFIED("verified"),
  DECLINED("declined"),
  UNVERIFIED("unverified");

  private final String wireValue;

  ChannelState(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  /**
   * The inverse of {@link #wireValue()}. Fails closed on an unrecognised value — the only caller
   * reads this back out of {@code app.profile_channel.state}, whose {@code CHECK} constraint
   * already bounds it to these three values.
   */
  public static ChannelState fromWireValue(String wireValue) {
    for (ChannelState state : values()) {
      if (state.wireValue.equals(wireValue)) {
        return state;
      }
    }
    throw new IllegalArgumentException("unrecognised channel state: " + wireValue);
  }
}
