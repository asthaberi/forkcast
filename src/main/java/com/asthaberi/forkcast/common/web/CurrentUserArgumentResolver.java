package com.asthaberi.forkcast.common.web;

import java.util.regex.Pattern;

import com.asthaberi.forkcast.common.exception.UnauthenticatedException;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * TEMPORARY: reads the user id from an {@code X-User-Id} header until JWT authentication is added.
 * Anyone can send any id, so this is for local development only.
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String USER_ID_HEADER = "X-User-Id";

    private static final Pattern VALID_USER_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class) && parameter.getParameterType() == String.class;
    }

    @Override
    public String resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String userId = webRequest.getHeader(USER_ID_HEADER);
        if (userId == null || !VALID_USER_ID.matcher(userId).matches()) {
            throw new UnauthenticatedException("Missing or invalid " + USER_ID_HEADER + " header");
        }
        return userId;
    }
}
