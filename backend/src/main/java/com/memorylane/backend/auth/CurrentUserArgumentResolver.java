package com.memorylane.backend.auth;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.UUID;

/** Supplies {@link CurrentUser} parameters from the user id stored by the session filter. */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && parameter.getParameterType().equals(UUID.class);
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer container,
            NativeWebRequest request,
            WebDataBinderFactory binderFactory) {
        Object userId =
                request.getAttribute(SessionAuthenticationFilter.USER_ID, RequestAttributes.SCOPE_REQUEST);
        // Only reachable through a mapping the filter skips, which is a programming error.
        if (!(userId instanceof UUID id))
            throw new IllegalStateException("@CurrentUser is only available under /api/v1 outside /api/v1/auth");
        return id;
    }
}
