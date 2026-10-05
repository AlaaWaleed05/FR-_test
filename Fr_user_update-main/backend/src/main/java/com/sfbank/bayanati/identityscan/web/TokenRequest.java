package com.sfbank.bayanati.identityscan.web;

/** Stage 8: "the app requests an enrolment token from the backend, scoped to the document type". */
public record TokenRequest(String profileId, String documentType) {}
