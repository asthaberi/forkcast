package com.asthaberi.forkcast.common.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the id of the signed-in user (from the JWT) into a controller parameter. Controllers depend only
 * on this annotation, not on Spring Security types, so switching from the earlier X-User-Id header to JWT
 * didn't change any controller.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
