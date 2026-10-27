package com.asthaberi.forkcast.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.asthaberi.forkcast.menu.MenuItemRepository;
import com.asthaberi.forkcast.restaurant.RestaurantRepository;
import com.asthaberi.forkcast.support.TestContainers;
import com.asthaberi.forkcast.user.Role;
import com.asthaberi.forkcast.user.User;
import com.asthaberi.forkcast.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * The whole authentication path end to end: real HTTP requests through the real security filter chain,
 * real tokens and a real MongoDB. Nothing about security is mocked here.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"forkcast.seed.enabled=false", "spring.cache.type=none",
        "management.health.redis.enabled=false", "forkcast.rate-limit.enabled=false",
        // No Redis in this test: a closed port, so a locally running Redis can't be used by accident
        "spring.data.redis.port=1"})
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = TestContainers.mongo();

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RestaurantRepository restaurantRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtEncoder jwtEncoder;

    @BeforeEach
    void clean() {
        userRepository.deleteAll();
        restaurantRepository.deleteAll();
        menuItemRepository.deleteAll();
    }

    @Test
    void registerLoginAndUseTheToken() throws Exception {
        mockMvc.perform(json(post("/api/v1/auth/register"), """
                        { "name": "Astha", "email": "astha@example.com", "password": "%s" }
                        """.formatted(PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        String token = login("ASTHA@example.com");

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("astha@example.com"));
        mockMvc.perform(get("/api/v1/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsMissingForgedAndExpiredTokensWith401() throws Exception {
        mockMvc.perform(get("/api/v1/cart"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                .andExpect(jsonPath("$.title").value("Unauthenticated"));

        String token = tokenFor(customer("someone@example.com"));
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");
        mockMvc.perform(get("/api/v1/cart").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("invalid_token")));

        String expired = encode("someone", List.of("CUSTOMER"), Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3600));
        mockMvc.perform(get("/api/v1/cart").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateEmailAndBadCredentials() throws Exception {
        customer("taken@example.com");

        mockMvc.perform(json(post("/api/v1/auth/register"), """
                        { "name": "Copy", "email": "Taken@Example.com", "password": "%s" }
                        """.formatted(PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));

        for (String email : List.of("taken@example.com", "nobody@example.com")) {
            mockMvc.perform(json(post("/api/v1/auth/login"), """
                            { "email": "%s", "password": "wrong-password" }
                            """.formatted(email)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.detail").value("Email or password is incorrect"));
        }
    }

    @Test
    void cannotSelfRegisterAsAdmin() throws Exception {
        mockMvc.perform(json(post("/api/v1/auth/register"), """
                        { "name": "Sneaky", "email": "sneaky@example.com", "password": "%s", "accountType": "ADMIN" }
                        """.formatted(PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ownersManageOnlyTheirOwnRestaurantsAndAdminsManageAll() throws Exception {
        String ownerA = tokenFor(save("a@example.com", Role.RESTAURANT_OWNER));
        String ownerB = tokenFor(save("b@example.com", Role.RESTAURANT_OWNER));
        String admin = tokenFor(save("admin@example.com", Role.ADMIN));
        String customer = tokenFor(customer("c@example.com"));

        String restaurantJson = """
                { "name": "A's Kitchen", "address": "1 Road", "city": "Gwalior", "latitude": 26.21, "longitude": 78.18,
                  "cuisines": ["Cafe"], "opensAt": "09:00", "closesAt": "21:00" }
                """;
        mockMvc.perform(json(post("/api/v1/restaurants"), restaurantJson).header("Authorization", "Bearer " + customer))
                .andExpect(status().isForbidden());
        String body = mockMvc.perform(json(post("/api/v1/restaurants"), restaurantJson)
                        .header("Authorization", "Bearer " + ownerA))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String restaurantId = JsonPath.read(body, "$.id");

        String item = """
                { "name": "Filter Coffee", "category": "Beverages", "price": 49, "vegetarian": true }
                """;
        String itemsUrl = "/api/v1/restaurants/" + restaurantId + "/menu/items";
        mockMvc.perform(json(post(itemsUrl), item).header("Authorization", "Bearer " + ownerA))
                .andExpect(status().isCreated());
        mockMvc.perform(json(post(itemsUrl), item).header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"));
        mockMvc.perform(json(post(itemsUrl), item).header("Authorization", "Bearer " + admin))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/cart").header("Authorization", "Bearer " + ownerA))
                .andExpect(status().isForbidden());
        // Reading the menu stays public
        mockMvc.perform(get("/api/v1/restaurants/" + restaurantId + "/menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(2));
    }

    private String login(String email) throws Exception {
        String body = mockMvc.perform(json(post("/api/v1/auth/login"), """
                        { "email": "%s", "password": "%s" }
                        """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private User customer(String email) {
        return save(email, Role.CUSTOMER);
    }

    private User save(String email, Role role) {
        return userRepository.save(User.create("Test", email, passwordEncoder.encode(PASSWORD), Set.of(role), Instant.now()));
    }

    private String tokenFor(User user) throws Exception {
        return login(user.getEmail());
    }

    private String encode(String subject, List<String> roles, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("forkcast").subject(subject).issuedAt(issuedAt)
                .expiresAt(expiresAt).claim("roles", roles).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims))
                .getTokenValue();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
