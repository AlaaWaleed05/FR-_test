package com.sfbank.bayanati.auth.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

class SignInFailureHandlerTest {

  private final SignInFailureHandler handler = new SignInFailureHandler();

  @Test
  void returns401WithNoDiscriminatingBody() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationFailure(
        new MockHttpServletRequest(), response, new BadCredentialsException("bad credentials"));

    assertEquals(401, response.getStatus());
    assertTrue(
        response.getContentAsString().isEmpty(),
        "whether the account existed must never be visible to the client");
  }
}
