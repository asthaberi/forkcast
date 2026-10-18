package com.asthaberi.forkcast.cart;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.asthaberi.forkcast.cart.dto.AddCartItemRequest;
import com.asthaberi.forkcast.cart.dto.CartResponse;
import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.security.TestJwt;
import com.asthaberi.forkcast.security.WithApiSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CartController.class)
@WithApiSecurity
class CartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CartService cartService;

    @Test
    void requiresAToken() throws Exception {
        mockMvc.perform(get("/api/v1/cart"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                .andExpect(jsonPath("$.title").value("Unauthenticated"));
        verifyNoInteractions(cartService);
    }

    @Test
    void restaurantOwnersCannotUseACart() throws Exception {
        mockMvc.perform(get("/api/v1/cart").with(TestJwt.owner("o1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"));
        verifyNoInteractions(cartService);
    }

    @Test
    void getReturnsCartOfTheTokenUser() throws Exception {
        when(cartService.getCart("u1")).thenReturn(CartResponse.empty());

        mockMvc.perform(get("/api/v1/cart").with(TestJwt.customer("u1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0.00))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void restaurantMismatchIsA409WithCodeAndCurrentRestaurant() throws Exception {
        when(cartService.addItem(eq("u1"), any(), eq(false))).thenThrow(new BusinessException(
                ErrorCode.CART_RESTAURANT_MISMATCH, "Your cart has items from Dosa Junction.",
                Map.of("currentRestaurantId", "r1", "currentRestaurantName", "Dosa Junction")));

        mockMvc.perform(post("/api/v1/cart/items").with(TestJwt.customer("u1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "menuItemId": "pizza", "quantity": 1 }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_RESTAURANT_MISMATCH"))
                .andExpect(jsonPath("$.currentRestaurantId").value("r1"))
                .andExpect(jsonPath("$.currentRestaurantName").value("Dosa Junction"));
    }

    @Test
    void replaceFlagIsPassedThrough() throws Exception {
        when(cartService.addItem(eq("u1"), any(), eq(true))).thenReturn(CartResponse.empty());

        mockMvc.perform(post("/api/v1/cart/items").param("replace", "true").with(TestJwt.customer("u1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "menuItemId": "pizza", "quantity": 1 }
                                """))
                .andExpect(status().isOk());

        verify(cartService).addItem("u1", new AddCartItemRequest("pizza", 1), true);
    }

    @Test
    void validatesQuantity() throws Exception {
        mockMvc.perform(post("/api/v1/cart/items").with(TestJwt.customer("u1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "menuItemId": "pizza", "quantity": 0 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.quantity").exists());
    }

    @Test
    void clearReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/cart").with(TestJwt.customer("u1")))
                .andExpect(status().isNoContent());

        verify(cartService).clear("u1");
    }
}
