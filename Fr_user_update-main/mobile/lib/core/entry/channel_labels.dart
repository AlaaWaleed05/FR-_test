import 'package:flutter/material.dart';

/// Arabic label for a channel's wire value (`sms`/`whatsapp`/`email`). Shared by
/// `ChannelVerificationScreen` and `SessionPendingScreen` — previously duplicated as each screen's
/// own private switch (S5-02); factored out once a second screen needed it (S5-04).
String channelLabel(String wireValue) {
  switch (wireValue) {
    case 'sms':
      return 'الرسائل النصية';
    case 'whatsapp':
      return 'واتساب';
    case 'email':
      return 'البريد الإلكتروني';
    default:
      return wireValue;
  }
}

/// The icon for a channel, alongside its label. Walk comments 4c and 5c both ask for channel
/// iconography; the OTP screen already had this as a private method, so it moves here rather than
/// being written a second time — the same reason `channelLabel` was factored out at S5-04.
///
/// These are Material glyphs, NOT brand marks. The product owner asked specifically for the
/// WhatsApp logo; shipping the real one means bundling Meta's brand asset under their guidelines,
/// which is a licensing question and a new asset rather than a presentation change. Filed on
/// BL-090 alongside the document-card artwork, since both need the same decision.
IconData channelIcon(String wireValue) {
  switch (wireValue) {
    case 'sms':
      return Icons.sms_outlined;
    case 'whatsapp':
      return Icons.chat_outlined;
    case 'email':
      return Icons.email_outlined;
    default:
      return Icons.circle_outlined;
  }
}
