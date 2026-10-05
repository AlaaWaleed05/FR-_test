package com.sfbank.bayanati.contactchannels.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DestinationMaskerTest {

  @Test
  void aPhoneNumberShowsOnlyItsLastFourDigits() {
    assertEquals("•••• 4821", DestinationMasker.maskPhone("+249900004821"));
  }

  @Test
  void anEmailAddressShowsOnlyItsFirstCharacterAndDomain() {
    assertEquals("a•••@gmail.com", DestinationMasker.maskEmail("ahmed@gmail.com"));
  }

  @Test
  void neitherMaskExposesTheFullDestination() {
    String phone = "+249900004821";
    String maskedPhone = DestinationMasker.maskPhone(phone);
    org.junit.jupiter.api.Assertions.assertFalse(maskedPhone.contains(phone));

    String email = "someone@example.invalid";
    String maskedEmail = DestinationMasker.maskEmail(email);
    org.junit.jupiter.api.Assertions.assertFalse(maskedEmail.contains(email));
  }
}
