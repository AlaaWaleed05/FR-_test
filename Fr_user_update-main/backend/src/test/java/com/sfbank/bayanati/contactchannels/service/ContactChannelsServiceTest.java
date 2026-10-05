package com.sfbank.bayanati.contactchannels.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.audit.domain.CanonicalJson;
import com.sfbank.bayanati.contactchannels.domain.NoPhoneChannelSelectedException;
import com.sfbank.bayanati.contactchannels.domain.ProfileAlreadyCompleteException;
import com.sfbank.bayanati.identityscan.domain.DeviceLessReentrySuperseder;
import com.sfbank.bayanati.identityscan.domain.SupersededIdentity;
import com.sfbank.bayanati.messaging.config.MessageSenderConfiguration;
import com.sfbank.bayanati.messaging.domain.DispatchOutcome;
import com.sfbank.bayanati.messaging.domain.MessageChannel;
import com.sfbank.bayanati.messaging.domain.MessageDispatchResult;
import com.sfbank.bayanati.messaging.domain.MessageSender;
import com.sfbank.bayanati.messaging.domain.OutboundMessage;
import com.sfbank.bayanati.profile.domain.ChannelState;
import com.sfbank.bayanati.profile.domain.ContactSnapshot;
import com.sfbank.bayanati.profile.domain.ExistingProfile;
import com.sfbank.bayanati.profile.domain.ProfileRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * {@code ContactChannelsService}'s orchestration, with every collaborator faked or mocked — no
 * Spring context, no database, no real clock (CLAUDE.md's business-logic testability rule).
 */
class ContactChannelsServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");

  private final ProfileRepository profileRepository = mock(ProfileRepository.class);
  private final AuditEventWriter auditEventWriter = mock(AuditEventWriter.class);
  private final MessageSender messageSender = mock(MessageSender.class);
  private final DeviceLessReentrySuperseder deviceLessReentrySuperseder =
      mock(DeviceLessReentrySuperseder.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  {
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(transactionStatus);
    when(auditEventWriter.append(any())).thenReturn(1L);
    // No existing profile by default -- every test predating S3-07 exercises the fresh-creation
    // path. Re-entry/rejection tests below override this per account.
    when(profileRepository.findExisting(any())).thenReturn(Optional.empty());
    // Mockito defaults an unstubbed boolean-returning method to false, which would make every
    // re-entry test below hit the mid-transaction rejection path unless overridden here.
    when(profileRepository.lockAndCheckStillEligibleForReentry(any())).thenReturn(true);
    // AD-008/BL-041: the default is "this profile had no inherited identity to supersede", which
    // is every pre-existing test's world. Mockito would otherwise return null here and the
    // superseded.anything() call would NPE. The supersession tests override this.
    when(deviceLessReentrySuperseder.supersedeInheritedIdentity(any(), any()))
        .thenReturn(SupersededIdentity.NOTHING);
    when(messageSender.send(any()))
        .thenAnswer(
            invocation -> {
              OutboundMessage message = invocation.getArgument(0);
              return new MessageDispatchResult(
                  message.messageId().toString(),
                  message.channel().wireValue(),
                  DispatchOutcome.ACCEPTED,
                  "stub",
                  "provider-id",
                  "0",
                  "ok",
                  message.channel() == MessageChannel.SMS ? 2 : -1,
                  NOW.toString(),
                  5L);
            });
  }

  private ContactChannelsService service(MockEnvironment environment) {
    return new ContactChannelsService(
        profileRepository,
        auditEventWriter,
        messageSender,
        deviceLessReentrySuperseder,
        environment,
        clock,
        transactionManager);
  }

  private static MockEnvironment allEnabled() {
    return new MockEnvironment();
  }

  private static MockEnvironment disabling(MessageChannel channel) {
    MockEnvironment env = new MockEnvironment();
    env.setProperty(MessageSenderConfiguration.enabledProperty(channel), "false");
    return env;
  }

  @Test
  void allThreeChannelsSelectedCreatesThreeOfEverythingAndSendsThreeMessages() {
    ContactChannelsResult result =
        service(allEnabled())
            .submit("16", "0000000001", "+249900004821", true, true, "ahmed@example.invalid");

    assertEquals(3, result.channels().size());
    verify(profileRepository, times(3)).upsertChannel(any(), any(), any());
    verify(profileRepository, times(3))
        .insertOtpChallenge(any(), any(), any(), any(), any(), any(), any());
    verify(messageSender, times(3)).send(any());
    verify(profileRepository).ensureAuditChain(any());
    verify(profileRepository).insertProfile(any(), eq("16"), eq("0000000001"), eq(NOW), eq(1L));
    verify(profileRepository)
        .insertContactDetails(any(), eq("+249900004821"), eq("ahmed@example.invalid"), eq(NOW));

    // 1 session_created + 3 otp_issued + 3 notification_dispatched
    verify(auditEventWriter, times(7)).append(any());
  }

  @Test
  void whatsAppDeselectedRecordsDeclinedAndOnlySendsTwoMessages() {
    ContactChannelsResult result =
        service(allEnabled()).submit("16", "0000000001", "+249900004821", true, false, null);

    assertEquals(2, result.channels().size());
    ChannelOutcome whatsapp =
        result.channels().stream()
            .filter(c -> c.channel() == MessageChannel.WHATSAPP)
            .findFirst()
            .orElseThrow();
    assertEquals(ChannelState.DECLINED, whatsapp.state());

    verify(messageSender, times(1)).send(any());
    verify(profileRepository, times(1))
        .insertOtpChallenge(any(), any(), any(), any(), any(), any(), any());
    verify(profileRepository)
        .upsertChannel(any(), eq(MessageChannel.WHATSAPP), eq(ChannelState.DECLINED));
    verify(profileRepository)
        .upsertChannel(any(), eq(MessageChannel.SMS), eq(ChannelState.UNVERIFIED));
  }

  @Test
  void bothPhoneChannelsDeselectedThrowsBeforeAnyRepositoryOrSenderInteraction() {
    assertThrows(
        NoPhoneChannelSelectedException.class,
        () ->
            service(allEnabled())
                .submit("16", "0000000001", "+249900004821", false, false, "a@example.invalid"));

    verifyNoInteractions(profileRepository, messageSender, auditEventWriter);
  }

  @Test
  void aDisabledChannelBehavesExactlyLikeADeselectedOne() {
    ContactChannelsResult result =
        service(disabling(MessageChannel.WHATSAPP))
            .submit("16", "0000000001", "+249900004821", true, true, null);

    ChannelOutcome whatsapp =
        result.channels().stream()
            .filter(c -> c.channel() == MessageChannel.WHATSAPP)
            .findFirst()
            .orElseThrow();
    assertEquals(ChannelState.DECLINED, whatsapp.state());
    verify(messageSender, never())
        .send(org.mockito.ArgumentMatchers.argThat(m -> m.channel() == MessageChannel.WHATSAPP));
  }

  @Test
  void noEmailAddressNeverTouchesTheEmailChannelAtAll() {
    service(allEnabled()).submit("16", "0000000001", "+249900004821", true, true, null);

    verify(profileRepository, never()).upsertChannel(any(), eq(MessageChannel.EMAIL), any());
    verify(profileRepository, never())
        .insertOtpChallenge(any(), any(), eq(MessageChannel.EMAIL), any(), any(), any(), any());
    verify(messageSender, never())
        .send(org.mockito.ArgumentMatchers.argThat(m -> m.channel() == MessageChannel.EMAIL));
  }

  @Test
  void anEmptyEmailStringIsTreatedTheSameAsNoEmail() {
    ContactChannelsResult result =
        service(allEnabled()).submit("16", "0000000001", "+249900004821", true, true, "   ");

    assertEquals(2, result.channels().size());
    assertFalse(result.channels().stream().anyMatch(c -> c.channel() == MessageChannel.EMAIL));
  }

  @Test
  void everySendUsesInteractiveUrgencyNeverDeferred() {
    service(allEnabled())
        .submit("16", "0000000001", "+249900004821", true, true, "ahmed@example.invalid");

    ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
    verify(messageSender, times(3)).send(sent.capture());
    assertTrue(
        sent.getAllValues().stream()
            .allMatch(m -> m.urgency() == com.sfbank.bayanati.messaging.domain.Urgency.INTERACTIVE),
        "an OTP must never be sent as DEFERRED — that is the outbox's urgency, and nothing drains it");
  }

  @Test
  void aSenderThatThrowsForOneChannelStillLetsTheOtherChannelsAndThePersistenceSucceed() {
    // doAnswer(...).when(mock)... rather than when(mock.method(any())).thenAnswer(...): the latter
    // re-primes by actually invoking send() once to record the stubbing, which would re-trigger the
    // constructor's already-registered answer with a null argument and NPE there instead of here.
    org.mockito.Mockito.doAnswer(
            invocation -> {
              OutboundMessage message = invocation.getArgument(0);
              if (message.channel() == MessageChannel.WHATSAPP) {
                throw new RuntimeException("provider connection reset");
              }
              return new MessageDispatchResult(
                  message.messageId().toString(),
                  message.channel().wireValue(),
                  DispatchOutcome.ACCEPTED,
                  "stub",
                  "provider-id",
                  "0",
                  "ok",
                  2,
                  NOW.toString(),
                  5L);
            })
        .when(messageSender)
        .send(any());

    ContactChannelsResult result =
        service(allEnabled())
            .submit("16", "0000000001", "+249900004821", true, true, "ahmed@example.invalid");

    // The exception must not propagate: the profile, its channels and every challenge are still
    // created, and a failure result is recorded for the channel whose send() threw.
    assertEquals(3, result.channels().size());
    verify(profileRepository, times(3)).upsertChannel(any(), any(), any());
    verify(profileRepository, times(3))
        .insertOtpChallenge(any(), any(), any(), any(), any(), any(), any());

    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, times(7)).append(events.capture());
    boolean sawFailedWhatsAppDispatch =
        events.getAllValues().stream()
            .filter(e -> e.eventType().equals("notification_dispatched"))
            .anyMatch(
                e ->
                    e.payloadJson().contains("\"channel\":\"whatsapp\"")
                        && e.payloadJson().contains("\"outcome\":\"PERMANENT_FAILURE\""));
    assertTrue(sawFailedWhatsAppDispatch, "the thrown exception must still be recorded, not lost");
  }

  @Test
  void sendsHappenBeforeTheDatabaseTransactionOpens() {
    service(allEnabled())
        .submit("16", "0000000001", "+249900004821", true, true, "ahmed@example.invalid");

    InOrder order = org.mockito.Mockito.inOrder(messageSender, transactionManager);
    order.verify(messageSender, times(3)).send(any());
    order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
  }

  @Test
  void theOtpIssuedAuditEventCarriesNoCode() {
    service(allEnabled()).submit("16", "0000000001", "+249900004821", true, true, null);

    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, times(5)).append(events.capture()); // session + 2 otp + 2 dispatched

    List<AuditEvent> otpIssued =
        events.getAllValues().stream().filter(e -> e.eventType().equals("otp_issued")).toList();
    assertEquals(2, otpIssued.size());

    Instant expectedExpiry = NOW.plusSeconds(300);
    for (AuditEvent event : otpIssued) {
      String channelWire = event.payloadJson().contains("\"channel\":\"sms\"") ? "sms" : "whatsapp";
      String expected =
          CanonicalJson.object(
              java.util.Map.of("channel", channelWire, "expiresAtIso", expectedExpiry.toString()));
      assertEquals(expected, event.payloadJson());
    }
  }

  @Test
  void everyAuditEventOnThisRequestGoesToTheProfilesOwnChainNotTheSystemChain() {
    service(allEnabled()).submit("16", "0000000001", "+249900004821", true, true, null);

    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, org.mockito.Mockito.atLeastOnce()).append(events.capture());

    for (AuditEvent event : events.getAllValues()) {
      assertEquals("profile", event.chainKind());
      assertTrue(isValidUuid(event.chainSubject()));
    }
  }

  @Test
  void theSessionCreatedEventDistinguishesSelectedFromChallengedForADisabledChannel() {
    // WhatsApp is genuinely selected by the customer but disabled at this deployment. The audit
    // record must show BOTH facts, not just the effective 'declined' state app.profile_channel is
    // limited to -- otherwise "the customer never wanted it" and "we couldn't serve it" become
    // permanently indistinguishable in a 7-year record.
    service(disabling(MessageChannel.WHATSAPP))
        .submit("16", "0000000001", "+249900004821", true, true, null);

    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, org.mockito.Mockito.atLeastOnce()).append(events.capture());
    AuditEvent sessionCreated =
        events.getAllValues().stream()
            .filter(e -> e.eventType().equals("session_created"))
            .findFirst()
            .orElseThrow();

    assertTrue(sessionCreated.payloadJson().contains("\"whatsappSelected\":true"));
    assertTrue(sessionCreated.payloadJson().contains("\"whatsappChallenged\":false"));
    assertTrue(sessionCreated.payloadJson().contains("\"smsSelected\":true"));
    assertTrue(sessionCreated.payloadJson().contains("\"smsChallenged\":true"));
  }

  // --- S3-07: profile-existence branch (re-entry / rejection) ---------------------------------

  /**
   * AD-008/BL-041. The supersede is consulted on the re-entry path only, and its result decides
   * whether an {@code identity_superseded} event is written — an event on every re-entry would make
   * the audit trail claim a revocation that never happened.
   */
  @Test
  void aReEntryThatSupersededAnInheritedIdentityAuditsIt() {
    UUID existingProfileId = UUID.randomUUID();
    givenExistingProfile(existingProfileId, "in_progress");
    when(deviceLessReentrySuperseder.supersedeInheritedIdentity(existingProfileId, NOW))
        .thenReturn(new SupersededIdentity(true, true, false));

    service(allEnabled()).submit("16", "0000000001", "+249900002222", true, true, null);

    verify(deviceLessReentrySuperseder).supersedeInheritedIdentity(existingProfileId, NOW);
    AuditEvent superseded = onlyEventOfType("identity_superseded");
    assertEquals("system", superseded.actorKind());
    assertTrue(superseded.payloadJson().contains("\"reason\":\"device_less_reentry\""));
    assertTrue(superseded.payloadJson().contains("\"cycleSuperseded\":true"));
    assertTrue(superseded.payloadJson().contains("\"signatureSuperseded\":true"));
  }

  @Test
  void aReEntryThatSupersededNothingWritesNoSupersessionEvent() {
    UUID existingProfileId = UUID.randomUUID();
    givenExistingProfile(existingProfileId, "in_progress");
    // The default stubbing already returns NOTHING; stated explicitly because it is the point.
    when(deviceLessReentrySuperseder.supersedeInheritedIdentity(existingProfileId, NOW))
        .thenReturn(SupersededIdentity.NOTHING);

    service(allEnabled()).submit("16", "0000000001", "+249900002222", true, true, null);

    assertEquals(0, eventsOfType("identity_superseded").size());
  }

  /** A first entry has no prior identity by construction, so the superseder is never consulted. */
  @Test
  void aFreshEntryNeverConsultsTheSuperseder() {
    service(allEnabled()).submit("16", "0000000001", "+249900002222", true, true, null);

    verifyNoInteractions(deviceLessReentrySuperseder);
    assertEquals(0, eventsOfType("identity_superseded").size());
  }

  private void givenExistingProfile(UUID profileId, String status) {
    when(profileRepository.findExisting("0000000001"))
        .thenReturn(Optional.of(new ExistingProfile(profileId, status, false)));
    when(profileRepository.currentContactDetails(profileId))
        .thenReturn(new ContactSnapshot("+249900001111", null));
    when(profileRepository.currentChannelStates(profileId))
        .thenReturn(
            Map.of(
                MessageChannel.SMS,
                ChannelState.UNVERIFIED,
                MessageChannel.WHATSAPP,
                ChannelState.UNVERIFIED));
  }

  private List<AuditEvent> eventsOfType(String eventType) {
    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, org.mockito.Mockito.atLeastOnce()).append(events.capture());
    return events.getAllValues().stream().filter(e -> e.eventType().equals(eventType)).toList();
  }

  private AuditEvent onlyEventOfType(String eventType) {
    List<AuditEvent> matches = eventsOfType(eventType);
    assertEquals(1, matches.size(), "expected exactly one " + eventType + " event");
    return matches.get(0);
  }

  @Test
  void reEntryToAnIncompleteProfileUpdatesRatherThanInserts() {
    UUID existingProfileId = UUID.randomUUID();
    when(profileRepository.findExisting("0000000001"))
        .thenReturn(Optional.of(new ExistingProfile(existingProfileId, "in_progress", false)));
    when(profileRepository.currentContactDetails(existingProfileId))
        .thenReturn(new ContactSnapshot("+249900001111", null));
    when(profileRepository.currentChannelStates(existingProfileId))
        .thenReturn(
            Map.of(
                MessageChannel.SMS,
                ChannelState.UNVERIFIED,
                MessageChannel.WHATSAPP,
                ChannelState.UNVERIFIED));

    ContactChannelsResult result =
        service(allEnabled()).submit("16", "0000000001", "+249900002222", true, true, null);

    assertEquals(existingProfileId, result.profileId());
    verify(profileRepository, never()).insertProfile(any(), any(), any(), any(), anyLong());
    verify(profileRepository, never()).insertContactDetails(any(), any(), any(), any());
    verify(profileRepository).updateContactDetails(existingProfileId, "+249900002222", null, NOW);
    verify(profileRepository).updateBranchCode(existingProfileId, "16");
    verify(profileRepository).invalidateOtpChallenges(existingProfileId, NOW);
    verify(profileRepository).touchLastActivity(existingProfileId, NOW);
    verify(profileRepository, never()).reactivateFromAbandoned(any(), any(), anyLong());
    verify(profileRepository, times(2)).upsertChannel(eq(existingProfileId), any(), any());

    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, org.mockito.Mockito.atLeastOnce()).append(events.capture());
    AuditEvent reentered =
        events.getAllValues().stream()
            .filter(e -> e.eventType().equals("session_reentered"))
            .findFirst()
            .orElseThrow();
    assertTrue(reentered.payloadJson().contains("\"previousPhoneNumber\":\"+249900001111\""));
    assertTrue(reentered.payloadJson().contains("\"previousEmailAddress\":null"));
    assertEquals(
        0,
        events.getAllValues().stream()
            .filter(e -> e.eventType().equals("session_created"))
            .count());
  }

  @Test
  void reEntryToAnAbandonedProfileReactivatesItRatherThanJustTouchingLastActivity() {
    UUID existingProfileId = UUID.randomUUID();
    when(profileRepository.findExisting("0000000001"))
        .thenReturn(Optional.of(new ExistingProfile(existingProfileId, "abandoned", false)));
    when(profileRepository.currentContactDetails(existingProfileId))
        .thenReturn(new ContactSnapshot("+249900001111", null));
    when(profileRepository.currentChannelStates(existingProfileId)).thenReturn(Map.of());

    service(allEnabled()).submit("16", "0000000001", "+249900002222", true, true, null);

    verify(profileRepository).reactivateFromAbandoned(eq(existingProfileId), eq(NOW), anyLong());
    verify(profileRepository, never()).touchLastActivity(any(), any());
  }

  @Test
  void reEntryUnderADifferentBranchFindsTheSameProfileAndRecordsTheNewBranch() {
    // BL-032 / V0061: the profile was created under branch 16; the customer comes back with the
    // same account but selects branch 22. The lookup ignores the branch (the account number is
    // the identity), the existing profile is reused rather than a second one inserted, and the
    // profile's branch_code is refreshed to the branch selected this time.
    UUID existingProfileId = UUID.randomUUID();
    when(profileRepository.findExisting("0000000001"))
        .thenReturn(Optional.of(new ExistingProfile(existingProfileId, "in_progress", false)));
    when(profileRepository.currentContactDetails(existingProfileId))
        .thenReturn(new ContactSnapshot("+249900001111", null));
    when(profileRepository.currentChannelStates(existingProfileId)).thenReturn(Map.of());

    ContactChannelsResult result =
        service(allEnabled()).submit("22", "0000000001", "+249900002222", true, true, null);

    assertEquals(existingProfileId, result.profileId());
    verify(profileRepository).findExisting("0000000001");
    verify(profileRepository, never()).insertProfile(any(), any(), any(), any(), anyLong());
    verify(profileRepository).updateBranchCode(existingProfileId, "22");

    // The re-entry event carries the branch submitted this time, so the previous value is
    // recoverable from the chain even though app.profile now holds only the latest.
    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, org.mockito.Mockito.atLeastOnce()).append(events.capture());
    AuditEvent reentered =
        events.getAllValues().stream()
            .filter(e -> e.eventType().equals("session_reentered"))
            .findFirst()
            .orElseThrow();
    assertTrue(reentered.payloadJson().contains("\"branch\":\"22\""), reentered.payloadJson());
  }

  @Test
  void aDroppedEmailChannelOnReEntryIsDeclinedNotDeleted() {
    UUID existingProfileId = UUID.randomUUID();
    when(profileRepository.findExisting("0000000001"))
        .thenReturn(Optional.of(new ExistingProfile(existingProfileId, "in_progress", false)));
    when(profileRepository.currentContactDetails(existingProfileId))
        .thenReturn(new ContactSnapshot("+249900001111", "old@example.invalid"));
    when(profileRepository.currentChannelStates(existingProfileId))
        .thenReturn(
            Map.of(
                MessageChannel.SMS, ChannelState.UNVERIFIED,
                MessageChannel.WHATSAPP, ChannelState.UNVERIFIED,
                MessageChannel.EMAIL, ChannelState.UNVERIFIED));

    // This submission supplies no email -- it was present before.
    service(allEnabled()).submit("16", "0000000001", "+249900002222", true, true, null);

    verify(profileRepository)
        .declineChannelsNotIn(
            existingProfileId, Set.of(MessageChannel.SMS, MessageChannel.WHATSAPP));
  }

  @Test
  void aTerminalExistingProfileIsRejectedBeforeAnythingIsSentOrWritten() {
    UUID existingProfileId = UUID.randomUUID();
    when(profileRepository.findExisting("0000000001"))
        .thenReturn(Optional.of(new ExistingProfile(existingProfileId, "submitted", true)));

    assertThrows(
        ProfileAlreadyCompleteException.class,
        () ->
            service(allEnabled())
                .submit("16", "0000000001", "+249900002222", true, true, "a@example.invalid"));

    verifyNoInteractions(messageSender);
    verify(profileRepository, never()).insertProfile(any(), any(), any(), any(), anyLong());
    verify(profileRepository, never()).updateContactDetails(any(), any(), any(), any());
    verify(profileRepository, never()).upsertChannel(any(), any(), any());
    verify(profileRepository, never())
        .insertOtpChallenge(any(), any(), any(), any(), any(), any(), any());
    verify(profileRepository, never()).invalidateOtpChallenges(any(), any());

    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, times(1)).append(events.capture());
    AuditEvent rejected = events.getValue();
    assertEquals("contact_channels_rejected", rejected.eventType());
    assertEquals(existingProfileId, rejected.profileId());
    assertTrue(rejected.payloadJson().contains("\"profileStatus\":\"submitted\""));
  }

  @Test
  void aProfileThatTurnsTerminalDuringTheSendLoopIsRejectedInsteadOfOverwritten() {
    // findExisting (before the send loop) sees a non-terminal profile, but by the time the
    // transaction opens it has become terminal -- an operator's manual completion racing the
    // request, most plausibly. The lock-and-recheck must catch this even though findExisting
    // itself was never wrong.
    UUID existingProfileId = UUID.randomUUID();
    when(profileRepository.findExisting("0000000001"))
        .thenReturn(Optional.of(new ExistingProfile(existingProfileId, "in_progress", false)));
    when(profileRepository.lockAndCheckStillEligibleForReentry(existingProfileId))
        .thenReturn(false);

    assertThrows(
        ProfileAlreadyCompleteException.class,
        () -> service(allEnabled()).submit("16", "0000000001", "+249900002222", true, true, null));

    // The OTPs were already sent before the race was caught -- that part cannot be undone -- but
    // no write to app.* may follow.
    verify(profileRepository, never()).updateContactDetails(any(), any(), any(), any());
    verify(profileRepository, never()).updateBranchCode(any(), any());
    verify(profileRepository, never()).invalidateOtpChallenges(any(), any());
    verify(profileRepository, never()).upsertChannel(any(), any(), any());
    verify(profileRepository, never()).touchLastActivity(any(), any());
    verify(profileRepository, never()).reactivateFromAbandoned(any(), any(), anyLong());
    verify(profileRepository, never())
        .insertOtpChallenge(any(), any(), any(), any(), any(), any(), any());

    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, org.mockito.Mockito.atLeastOnce()).append(events.capture());
    AuditEvent rejected =
        events.getAllValues().stream()
            .filter(e -> e.eventType().equals("contact_channels_rejected"))
            .findFirst()
            .orElseThrow();
    assertEquals(existingProfileId, rejected.profileId());
    assertTrue(
        rejected.payloadJson().contains("\"reason\":\"profile_became_complete_during_processing\""),
        rejected.payloadJson());
    // AD-008/BL-041: the supersede is a destructive write and belongs on the same
    // forbidden-writes list as everything else this path must not do. Without this the
    // test stays green if the supersede is ever reordered above the eligibility check.
    verifyNoInteractions(deviceLessReentrySuperseder);
  }

  // --- S3-08 / R-044: re-entry gated by Stage 2's escalating phone lock -----------------------

  @Test
  void reEntryWhilePhoneLockIsActiveIsRefusedWithNothingSentOrWritten() {
    UUID existingProfileId = UUID.randomUUID();
    when(profileRepository.findExisting("0000000001"))
        .thenReturn(Optional.of(new ExistingProfile(existingProfileId, "in_progress", false)));
    when(profileRepository.currentPhoneLockUntil(existingProfileId))
        .thenReturn(Optional.of(NOW.plusSeconds(60)));

    assertThrows(
        com.sfbank.bayanati.contactchannels.domain.SessionTemporarilyBlockedException.class,
        () -> service(allEnabled()).submit("16", "0000000001", "+249900002222", true, true, null));

    verifyNoInteractions(messageSender);
    verify(profileRepository, never()).updateContactDetails(any(), any(), any(), any());
    verify(profileRepository, never()).upsertChannel(any(), any(), any());
    verify(profileRepository, never()).invalidateOtpChallenges(any(), any());
    verify(profileRepository, never())
        .insertOtpChallenge(any(), any(), any(), any(), any(), any(), any());

    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, times(1)).append(events.capture());
    AuditEvent rejected = events.getValue();
    assertEquals("contact_channels_rejected", rejected.eventType());
    assertTrue(rejected.payloadJson().contains("\"reason\":\"phone_temporarily_blocked\""));
    assertTrue(
        rejected.payloadJson().contains("\"blockedUntilIso\":\"" + NOW.plusSeconds(60) + "\""),
        rejected.payloadJson());
    // AD-008/BL-041: the supersede is a destructive write and belongs on the same
    // forbidden-writes list as everything else this path must not do. Without this the
    // test stays green if the supersede is ever reordered above the eligibility check.
    verifyNoInteractions(deviceLessReentrySuperseder);
  }

  @Test
  void reEntryWithAnExpiredPhoneLockProceedsNormally() {
    UUID existingProfileId = UUID.randomUUID();
    when(profileRepository.findExisting("0000000001"))
        .thenReturn(Optional.of(new ExistingProfile(existingProfileId, "in_progress", false)));
    when(profileRepository.currentPhoneLockUntil(existingProfileId))
        .thenReturn(Optional.of(NOW.minusSeconds(1))); // already expired
    when(profileRepository.currentContactDetails(existingProfileId))
        .thenReturn(new ContactSnapshot("+249900001111", null));
    when(profileRepository.currentChannelStates(existingProfileId)).thenReturn(Map.of());

    ContactChannelsResult result =
        service(allEnabled()).submit("16", "0000000001", "+249900002222", true, true, null);

    assertEquals(existingProfileId, result.profileId());
    verify(profileRepository).updateContactDetails(existingProfileId, "+249900002222", null, NOW);
  }

  @Test
  void aBrandNewProfileNeverConsultsThePhoneLock() {
    service(allEnabled()).submit("16", "0000000001", "+249900004821", true, true, null);

    verify(profileRepository, never()).currentPhoneLockUntil(any());
    // The branch travels on insertProfile for a brand-new profile; updateBranchCode is the
    // re-entry path's refresh only.
    verify(profileRepository, never()).updateBranchCode(any(), any());
  }

  private static boolean isValidUuid(String value) {
    try {
      UUID.fromString(value);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
