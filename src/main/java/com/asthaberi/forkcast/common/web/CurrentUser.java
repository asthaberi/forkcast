package com.asthaberi.forkcast.common.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the id of the user making the request into a controller parameter.
 * Controllers depend only on this annotation, so when JWT authentication arrives, only
 * {@link CurrentUserArgumentResolver} changes.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
