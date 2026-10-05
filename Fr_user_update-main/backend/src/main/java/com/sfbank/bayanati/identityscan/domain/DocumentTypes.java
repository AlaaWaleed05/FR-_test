package com.sfbank.bayanati.identityscan.domain;

/**
 * Translates between two vocabularies for the same concept: the journey's own {@code identity_type}
 * (Stage 7, {@code app.profile_customer_data} — {@code "passport"}/{@code "national_id"}) and
 * Uqudo's {@code DocumentType} enum ({@code "PASSPORT"}/{@code "SDN_ID"}), which is what the JWS
 * actually carries and what the SDK's builders expect. The wire contract and the retry-budget
 * columns ({@code scan_attempts_national_id}/{@code scan_attempts_passport}, V0039) both use the
 * app vocabulary, matching Stage 7 exactly; only the Uqudo call itself needs the translation.
 */
public final class DocumentTypes {

  public static final String NATIONAL_ID = "national_id";
  public static final String PASSPORT = "passport";

  private DocumentTypes() {}

  public static boolean isValid(String appDocumentType) {
    return NATIONAL_ID.equals(appDocumentType) || PASSPORT.equals(appDocumentType);
  }

  public static String toUqudoDocumentType(String appDocumentType) {
    return switch (appDocumentType) {
      case NATIONAL_ID -> "SDN_ID";
      case PASSPORT -> "PASSPORT";
      default -> throw new IllegalArgumentException("unknown documentType: " + appDocumentType);
    };
  }

  public static String fromUqudoDocumentType(String uqudoDocumentType) {
    return switch (uqudoDocumentType) {
      case "SDN_ID" -> NATIONAL_ID;
      case "PASSPORT" -> PASSPORT;
      default ->
          throw new IllegalArgumentException("unknown Uqudo documentType: " + uqudoDocumentType);
    };
  }
}
