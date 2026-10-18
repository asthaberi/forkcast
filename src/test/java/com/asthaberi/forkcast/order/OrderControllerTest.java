package com.asthaberi.forkcast.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.asthaberi.forkcast.order.OrderService.OrderPlacement;
import com.asthaberi.forkcast.order.dto.OrderResponse;
import com.asthaberi.forkcast.security.TestJwt;
import com.asthaberi.forkcast.security.WithApiSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(OrderController.class)
@WithApiSecurity
class OrderControllerTest {

    private static final String BODY = """
            { "deliveryAddress": { "line": "Hostel 3, ABV-IIITM", "latitude": 26.2491, "longitude": 78.1734 } }
            """;

    private static final OrderResponse ORDER = new OrderResponse("o1", OrderStatus.PLACED, "r1", "Dosa Junction",
            List.of(), 2, new BigDecimal("258.00"), new BigDecimal("30.00"), new BigDecimal("12.90"),
            new BigDecimal("300.90"), new DeliveryAddress("Hostel 3", 26.2491, 78.1734),
            Instant.parse("2026-10-18T07:00:00Z"), List.of());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    void newOrderReturns201WithLocation() throws Exception {
        when(orderService.placeOrder(eq("u1"), eq("key-1"), any())).thenReturn(new OrderPlacement(ORDER, true));

        mockMvc.perform(placeOrder().header("Idempotency-Key", "key-1"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/orders/o1"))
                .andExpect(jsonPath("$.total").value(300.90))
                .andExpect(jsonPath("$.status").value("PLACED"));
    }

    @Test
    void replayedKeyReturns200WithReplayHeader() throws Exception {
        when(orderService.placeOrder(eq("u1"), eq("key-1"), any())).thenReturn(new OrderPlacement(ORDER, false));

        mockMvc.perform(placeOrder().header("Idempotency-Key", "key-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value("o1"));
    }

    @Test
    void idempotencyKeyIsRequired() throws Exception {
        mockMvc.perform(placeOrder())
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orderService);
    }

    @Test
    void deliveryAddressIsValidated() throws Exception {
        mockMvc.perform(post("/api/v1/orders").with(TestJwt.customer("u1")).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "deliveryAddress": { "line": "", "latitude": 200 } }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['deliveryAddress.line']").exists())
                .andExpect(jsonPath("$.errors['deliveryAddress.latitude']").exists())
                .andExpect(jsonPath("$.errors['deliveryAddress.longitude']").exists());
    }

    @Test
    void pageSizeIsCapped() throws Exception {
        mockMvc.perform(get("/api/v1/orders").with(TestJwt.customer("u1")).param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.size").exists());
    }

    private static MockHttpServletRequestBuilder placeOrder() {
        return post("/api/v1/orders").with(TestJwt.customer("u1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY);
    }
}
