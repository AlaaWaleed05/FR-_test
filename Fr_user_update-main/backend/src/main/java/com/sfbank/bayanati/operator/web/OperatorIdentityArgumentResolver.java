package com.sfbank.bayanati.operator.web;

import com.sfbank.bayanati.operator.domain.OperatorIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves {@link OperatorIdentity} controller parameters -- the S4-01 task's explicit constraint:
 * the operator's identity and access level must be "an explicit input the security layer will later
 * supply -- not as something each handler invents, and not as a header a client can set."
 *
 * <p><strong>Why a request attribute, not a header</strong>: an HTTP client fully controls every
 * header, query parameter and body field it sends -- trusting one of those for identity would let
 * any caller claim to be any operator. A {@link HttpServletRequest} <em>attribute</em> (as opposed
 * to a header) can only be set server-side, by a servlet filter running earlier in the chain,
 * before this resolver or the controller ever sees the request.
 *
 * <p><strong>AD-002e (S4-05) closed this</strong>: {@code auth.web.OperatorIdentityFilter} is that
 * filter, added {@code addFilterAfter(..., AnonymousAuthenticationFilter.class)} in {@code
 * auth.config.SecurityConfiguration}. It calls {@code request.setAttribute(REQUEST_ATTRIBUTE,
 * identity)} for any authenticated, still-enabled back-office account — admin included, since
 * AD-013 (BL-139) — re-reading {@code app.operator_user} on every request. This resolver, every
 * controller, and every service/repository below it are unaffected by that filter's existence --
 * they already took {@link OperatorIdentity} as an explicit parameter and know nothing about how it
 * was obtained.
 *
 * <p>{@link #resolveArgument} still throws the same {@link ResponseStatusException}(401) whenever
 * the attribute is absent -- now reachable for real reasons, not only "the filter doesn't exist
 * yet": no session, or an account disabled since the session began (this is what makes
 * account-disable take effect on a live session, not only at next login -- see {@code
 * auth.web.OperatorIdentityFilter}'s Javadoc). An admin was a third reason until AD-013 (BL-139)
 * gave admins an identity too; note it was never a reachable one in practice, since {@code
 * AuthorizationFilter} ran first and 403'd an admin before resolution began. Argument resolution
 * runs before a controller method body starts, so nothing downstream ever gets a chance to catch
 * anything this method throws.
 *
 * <p>Tests populate the attribute directly via {@code MockMvc}'s {@code .requestAttr(...)}, which
 * is not a client-controlled input -- it sets test state on the simulated request object itself,
 * the same place a real filter would write to, never anything an actual HTTP request carries.
 */
public class OperatorIdentityArgumentResolver implements HandlerMethodArgumentResolver {

  public static final String REQUEST_ATTRIBUTE = "fru.operatorIdentity";

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return OperatorIdentity.class.equals(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    Object attribute = webRequest.getAttribute(REQUEST_ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST);
    if (attribute instanceof OperatorIdentity identity) {
      return identity;
    }
    throw new ResponseStatusException(
        HttpStatus.UNAUTHORIZED,
        "no operator identity available for this request -- not signed in, or the account was"
            + " disabled since this session began");
  }
}
