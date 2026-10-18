package com.asthaberi.forkcast.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A stricter limit for one endpoint, on top of the API-wide limit. Checked by {@link RateLimitInterceptor}
 * before the controller method runs.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** Name of a policy under {@code forkcast.rate-limit.policies}. */
    String policy();

    /** What a bucket belongs to. */
    KeyType by();

    enum KeyType {
        /** The client's IP address; for endpoints used before signing in. */
        IP,
        /** The signed-in user. */
        USER
    }
}
