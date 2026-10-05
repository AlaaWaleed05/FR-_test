package com.sfbank.bayanati.dataentry.web;

/** Shared acknowledgement shape for all four data-entry stage endpoints. */
public record DataEntryResponse(String profileId, String stage) {}
