package com.sfbank.bayanati.auth.service;

import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Backs {@code auth.config.SecurityConfiguration}'s {@code DaoAuthenticationProvider}. Looks up by
 * {@code username} unfiltered by {@code is_enabled} — {@link OperatorUserRepository#findByUsername}
 * must return a disabled row too, so {@link OperatorUserDetails#isEnabled()} can correctly deny it
 * rather than this method reporting "no such user" for an account that exists but is disabled.
 */
@Service
public class OperatorUserDetailsService implements UserDetailsService {

  private final OperatorUserRepository operatorUserRepository;

  public OperatorUserDetailsService(OperatorUserRepository operatorUserRepository) {
    this.operatorUserRepository = operatorUserRepository;
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    return operatorUserRepository
        .findByUsername(username)
        .map(OperatorUserDetails::new)
        .orElseThrow(() -> new UsernameNotFoundException("no operator account for that username"));
  }
}
