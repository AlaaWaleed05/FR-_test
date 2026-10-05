package com.sfbank.bayanati.otpverification.domain;

import com.sfbank.bayanati.profile.domain.ChannelState;
import java.time.Instant;

/**
 * One {@code app.profile_channel} row's verification-relevant columns (V0007), as they stand at the
 * moment {@link OtpVerificationRepository#findChannelState} reads them.
 *
 * @param lockedAt {@code null} unless the channel already reached the wrong-attempt limit
 */
public record ChannelVerificationState(
    ChannelState state, Instant lockedAt, int wrongCodeAttempts, int resendCount) {

  public boolean locked() {
    return lockedAt != null;
  }
}
