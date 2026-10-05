package com.sfbank.bayanati.contactchannels.web;

import java.util.List;

/**
 * What Stage 1b reports back to the app. No OTP code anywhere in this type, by design — see {@code
 * ContactChannelsService}'s class Javadoc.
 *
 * @param profileId the newly created profile — also the journey session identifier; this schema has
 *     no separate session entity (see the session report)
 * @param channels every channel that got an {@code app.profile_channel} row, including {@code
 *     declined} ones — a channel with no address entered (email, when none was supplied) is simply
 *     absent from this list
 */
public record ContactChannelsResponse(String profileId, List<ChannelInfo> channels) {}
