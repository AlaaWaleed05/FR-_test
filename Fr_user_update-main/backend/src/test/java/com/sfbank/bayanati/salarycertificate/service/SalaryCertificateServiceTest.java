package com.sfbank.bayanati.salarycertificate.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sfbank.bayanati.audit.domain.AuditEvent;
import com.sfbank.bayanati.audit.domain.AuditEventWriter;
import com.sfbank.bayanati.profile.domain.ProfileRepository;
import com.sfbank.bayanati.salarycertificate.domain.ProfileNotEditableException;
import com.sfbank.bayanati.salarycertificate.domain.SalaryCertificateRejectedException;
import com.sfbank.bayanati.salarycertificate.domain.SalaryCertificateRepository;
import com.sfbank.bayanati.salarycertificate.domain.UnknownProfileException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * {@code SalaryCertificateService}'s orchestration, with every collaborator faked or mocked -- no
 * Spring context, no database, no real clock (CLAUDE.md's business-logic testability rule). Same
 * shape as {@code AccountCheckServiceTest}.
 */
class SalaryCertificateServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");
  private static final UUID PROFILE_ID = UUID.randomUUID();

  private final SalaryCertificateRepository salaryCertificateRepository =
      mock(SalaryCertificateRepository.class);
  private final ProfileRepository profileRepository = mock(ProfileRepository.class);
  private final AuditEventWriter auditEventWriter = mock(AuditEventWriter.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  {
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(transactionStatus);
    when(auditEventWriter.append(any())).thenReturn(1L);
  }

  private SalaryCertificateService service() {
    return new SalaryCertificateService(
        salaryCertificateRepository,
        profileRepository,
        auditEventWriter,
        clock,
        transactionManager);
  }

  @Test
  void anUnknownProfileThrows() {
    when(salaryCertificateRepository.checkTerminal(PROFILE_ID)).thenReturn(Optional.empty());

    assertThrows(
        UnknownProfileException.class,
        () -> service().submitCertificate(PROFILE_ID, "image/jpeg", "bytes".getBytes()));
    verify(salaryCertificateRepository, never())
        .upsertSalaryCertificateArtifact(any(), any(), anyLong(), any(), any(), any(), any());
  }

  @Test
  void aTerminalProfileIsRejectedAndAudited() {
    when(salaryCertificateRepository.checkTerminal(PROFILE_ID)).thenReturn(Optional.of(true));

    assertThrows(
        ProfileNotEditableException.class,
        () -> service().submitCertificate(PROFILE_ID, "image/jpeg", "bytes".getBytes()));

    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter).append(event.capture());
    assertEquals("salary_certificate_rejected", event.getValue().eventType());
    verify(salaryCertificateRepository, never())
        .upsertSalaryCertificateArtifact(any(), any(), anyLong(), any(), any(), any(), any());
  }

  @Test
  void anUnsupportedContentTypeIsRejectedBeforeAnyProfileCheck() {
    assertThrows(
        SalaryCertificateRejectedException.class,
        () -> service().submitCertificate(PROFILE_ID, "application/zip", "bytes".getBytes()));

    verify(salaryCertificateRepository, never()).checkTerminal(any());
  }

  @Test
  void emptyContentIsRejected() {
    assertThrows(
        SalaryCertificateRejectedException.class,
        () -> service().submitCertificate(PROFILE_ID, "image/jpeg", new byte[0]));
  }

  @Test
  void oversizedContentIsRejected() {
    byte[] tooBig = new byte[(int) SalaryCertificateService.MAX_BYTES + 1];

    assertThrows(
        SalaryCertificateRejectedException.class,
        () -> service().submitCertificate(PROFILE_ID, "image/jpeg", tooBig));
  }

  @Test
  void aValidUploadIsStoredAuditedAndTouchesLastActivity() {
    when(salaryCertificateRepository.checkTerminal(PROFILE_ID)).thenReturn(Optional.of(false));
    byte[] content = "real-certificate-bytes".getBytes();

    service().submitCertificate(PROFILE_ID, "application/pdf", content);

    ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
    verify(salaryCertificateRepository)
        .upsertSalaryCertificateArtifact(
            eq(PROFILE_ID),
            eq("application/pdf"),
            eq((long) content.length),
            any(),
            stored.capture(),
            any(),
            eq(NOW));
    assertArrayEquals(content, stored.getValue());

    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventWriter, times(1)).append(event.capture());
    assertEquals("salary_certificate_uploaded", event.getValue().eventType());
    assertFalse(
        event.getValue().payloadJson().contains("real-certificate-bytes"),
        "the certificate bytes must never appear in the audit payload");

    verify(profileRepository).touchLastActivity(PROFILE_ID, NOW);
  }
}
