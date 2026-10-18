package com.asthaberi.forkcast.user;

import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Demo accounts from {@code forkcast.seed.users}, created by UserSeeder in local development. */
@ConfigurationProperties(prefix = "forkcast.seed")
public record SeedUserProperties(List<SeedUser> users) {

    public SeedUserProperties {
        users = users == null ? List.of() : List.copyOf(users);
    }

    /** @param owns optional name of a restaurant this user owns */
    public record SeedUser(String name, String email, String password, Set<Role> roles, String owns) {
    }
}
