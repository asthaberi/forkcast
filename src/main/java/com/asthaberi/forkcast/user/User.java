package com.asthaberi.forkcast.user;

import java.time.Instant;
import java.util.Set;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class User {

    @Id
    private String id;

    private String name;

    // Stored lowercase; the unique index stops two accounts sharing an email, even under concurrent sign-ups.
    @Indexed(unique = true)
    private String email;

    // Never the password itself: a salted, slow hash ("{bcrypt}$2a$10$...").
    private String passwordHash;

    private Set<Role> roles;

    private Instant createdAt;

    public static User create(String name, String email, String passwordHash, Set<Role> roles, Instant createdAt) {
        User user = new User();
        user.name = name;
        user.email = email;
        user.passwordHash = passwordHash;
        user.roles = Set.copyOf(roles);
        user.createdAt = createdAt;
        return user;
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }
}
