package com.sfbank.bayanati.contactchannels.web;

/**
 * Journey Stage 1b (docs/journeys/customer.md). {@code branch}/{@code accountNumber} are not a
 * customer.md Stage 1b field — they were collected at Stage 1a — but nothing in this codebase yet
 * ties a Stage 1b call back to a specific Stage 1a call (BL-006 is out of scope), and {@code
 * app.profile} requires both to enforce "one profile per account". A wire-contract necessity, not a
 * new journey field.
 *
 * @param sms {@code null} means "still selected" — SMS is selected by default (customer.md Stage
 *     1b), so an app that omits the flag entirely rather than sending {@code true} must not be read
 *     as having deselected it
 * @param whatsapp {@code null} is treated as selected, exactly as {@link #sms} is. <strong>This is
 *     a wire-compatibility default, NOT the journey's default any more.</strong> Since S8-05
 *     WhatsApp is NOT selected by default in the app (customer.md Stage 1b, amended 2026-09-08) —
 *     BL-086's SMS-only pilot. No backend change was owed for that flip and none was made: the
 *     mobile client declares {@code required bool whatsapp} and always writes the key, so this
 *     {@code null} branch is unreachable from the shipped app and the customer's choice is honoured
 *     verbatim. It is kept for a client that predates the flip, where "absent" has always meant
 *     "selected" and silently reinterpreting it would change what an old app is asking for. To
 *     refuse WhatsApp challenges server-side regardless of what any client sends, set {@code
 *     fru.messaging.whatsapp.enabled=false} — see {@code ChannelSelection.resolve}; that flag, not
 *     this default, is the deployment-level control
 * @param emailAddress {@code null} or blank when the customer supplied none — the email channel row
 *     only exists when this is present (customer.md: "the email channel row activates once an
 *     address is entered")
 */
public record ContactChannelsRequest(
    String branch,
    String accountNumber,
    String phoneNumber,
    Boolean sms,
    Boolean whatsapp,
    String emailAddress) {

  public boolean smsSelected() {
    return sms == null || sms;
  }

  public boolean whatsappSelected() {
    return whatsapp == null || whatsapp;
  }
}
