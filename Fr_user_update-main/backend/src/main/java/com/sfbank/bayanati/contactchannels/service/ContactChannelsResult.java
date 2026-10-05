package com.sfbank.bayanati.contactchannels.service;

import java.util.List;
import java.util.UUID;

/** What {@link ContactChannelsService#submit} reports back. No OTP code anywhere in this type. */
public record ContactChannelsResult(UUID profileId, List<ChannelOutcome> channels) {}
