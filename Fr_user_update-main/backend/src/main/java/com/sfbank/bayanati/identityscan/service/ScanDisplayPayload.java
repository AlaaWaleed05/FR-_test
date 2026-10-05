package com.sfbank.bayanati.identityscan.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * customer.md Stage 9's "single display payload" — the national number shown first and alone, then
 * the Civil Registry data once it is ready. Every {@code registry*} field is {@code null} when
 * {@code registryReady} is {@code false} (the Stage 9 pause).
 *
 * <p>{@code availableImageKinds} names which images the screen may fetch from {@code GET
 * /api/v1/identity-scan/image/{kind}} — never the bytes themselves, which would put several hundred
 * kilobytes of identity images into a payload the screen re-reads on every registry poll.
 */
public record ScanDisplayPayload(
    UUID profileId,
    UUID cycleId,
    String documentType,
    String nationalNumber,
    boolean registryReady,
    String nameArGiven,
    String nameArFather,
    String nameArGrandfather,
    String nameArGreatGrandfather,
    String nameArMother,
    String nameArMotherFather,
    String nameArMotherGrandfather,
    String nameArMotherGreatGrandfather,
    String firstNamesEn,
    String lastNameEn,
    String sexRegistry,
    LocalDate dateOfBirth,
    String rawAddressAr,
    List<String> availableImageKinds) {}
