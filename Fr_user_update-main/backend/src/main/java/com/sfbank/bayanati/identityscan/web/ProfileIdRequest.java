package com.sfbank.bayanati.identityscan.web;

/** Shared body shape for every Stage 9 action that needs nothing beyond the profile id. */
public record ProfileIdRequest(String profileId) {}
