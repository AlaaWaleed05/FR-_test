package com.sfbank.bayanati.identityscan.web;

import com.sfbank.bayanati.identityscan.service.ScanDisplayPayload;
import java.util.List;

/**
 * Stage 9's single display payload, wire-shaped. Every {@code registry*} field is absent (null)
 * while {@code registryReady} is {@code false}.
 */
public record ScanDisplayResponse(
    String profileId,
    String cycleId,
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
    String dateOfBirth,
    String rawAddressAr,
    List<String> availableImageKinds) {

  public static ScanDisplayResponse from(ScanDisplayPayload payload) {
    return new ScanDisplayResponse(
        payload.profileId().toString(),
        payload.cycleId().toString(),
        payload.documentType(),
        payload.nationalNumber(),
        payload.registryReady(),
        payload.nameArGiven(),
        payload.nameArFather(),
        payload.nameArGrandfather(),
        payload.nameArGreatGrandfather(),
        payload.nameArMother(),
        payload.nameArMotherFather(),
        payload.nameArMotherGrandfather(),
        payload.nameArMotherGreatGrandfather(),
        payload.firstNamesEn(),
        payload.lastNameEn(),
        payload.sexRegistry(),
        payload.dateOfBirth() == null ? null : payload.dateOfBirth().toString(),
        payload.rawAddressAr(),
        payload.availableImageKinds());
  }
}
