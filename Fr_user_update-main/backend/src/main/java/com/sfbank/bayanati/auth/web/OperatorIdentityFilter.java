package com.sfbank.bayanati.auth.web;

import com.sfbank.bayanati.auth.domain.OperatorRole;
import com.sfbank.bayanati.auth.domain.OperatorUserRepository;
import com.sfbank.bayanati.auth.service.OperatorUserDetails;
import com.sfbank.bayanati.operator.domain.OperatorAccessLevel;
import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import com.sfbank.bayanati.operator.web.OperatorIdentityArgumentResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * AD-002e: the filter {@link OperatorIdentityArgumentResolver}'s own Javadoc names as the only
 * thing it was waiting for. Added via {@code addFilterAfter(this,
 * AnonymousAuthenticationFilter.class)} in {@code auth.config.SecurityConfiguration} — runs after
 * authentication is established, before {@code AuthorizationFilter}.
 *
 * <p><strong>Re-reads {@code app.operator_user} on every request</strong> rather than trusting the
 * session's cached principal. This — not the {@code account_disabled} audit event — is what makes
 * disabling an account or changing its role take effect on a LIVE session rather than only at next
 * login (AD-002e §3.5(f)): a disabled account fails {@link OperatorUserRepository#findActiveById},
 * the attribute is never set, and {@link OperatorIdentityArgumentResolver} then 401s every operator
 * endpoint for that still-"authenticated" session.
 *
 * <p>Sets nothing for an anonymous or unauthenticated request. It DOES set an identity for {@code
 * admin}, at the {@code OPERATOR} access level — see {@code accessLevelOf}. That reverses AD-002e
 * §3.5(e), under which this filter dropped admins outright; AD-013 (BL-139) made admin a superuser.
 * Note that the drop was never what produced the 403 an admin used to get: {@code
 * AuthorizationFilter} runs after this one and refused them first, for want of {@code ROLE_VIEWER}.
 * Both gates had to move, and changing only this one would have left the 403 exactly where it was.
 *
 * <p>Identity is still never client-supplied: a request attribute can only be set server-side, and
 * this filter is the only thing that sets it.
 */
public class OperatorIdentityFilter extends OncePerRequestFilter {

  private final OperatorUserRepository operatorUserRepository;

  public OperatorIdentityFilter(OperatorUserRepository operatorUserRepository) {
    this.operatorUserRepository = operatorUserRepository;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && authentication.getPrincipal() instanceof OperatorUserDetails principal) {
      operatorUserRepository
          .findActiveById(principal.userId())
          .ifPresent(
              account ->
                  request.setAttribute(
                      OperatorIdentityArgumentResolver.REQUEST_ATTRIBUTE,
                      new OperatorIdentity(
                          account.userId().toString(),
                          accessLevelOf(account.role()),
                          // Lower-cased to the app.operator_role.code values, which is what the
                          // audit payload records. See OperatorIdentity#actorRole.
                          account.role().name().toLowerCase(Locale.ROOT))));
    }
    chain.doFilter(request, response);
  }

  /**
   * The one place a back-office role becomes an access level. AD-013 (BL-139) put admin at the top
   * of the ladder, so admin maps ONTO {@code OPERATOR} rather than adding a third level — {@link
   * OperatorAccessLevel} stays two-valued, and the three service-side gates that read {@code
   * accessLevel() != OPERATOR} keep working untouched. Admin's extra authority is the {@code
   * /api/v1/admin/**} surface, which is a Spring Security rule, not an access level.
   */
  private static OperatorAccessLevel accessLevelOf(OperatorRole role) {
    return switch (role) {
      case OPERATOR, ADMIN -> OperatorAccessLevel.OPERATOR;
      case VIEWER -> OperatorAccessLevel.VIEWER;
    };
  }
}
