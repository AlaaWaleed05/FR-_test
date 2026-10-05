package com.sfbank.bayanati.identityscan.web;

/** Stage 8: "Customer cancels out of the SDK" -- counts as a failed attempt. */
public record CancelScanRequest(String profileId, String documentType) {}
