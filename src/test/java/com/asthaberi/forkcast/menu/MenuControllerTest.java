package com.asthaberi.forkcast.menu;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import com.asthaberi.forkcast.menu.dto.MenuItemResponse;
import com.asthaberi.forkcast.menu.dto.MenuResponse;
import com.asthaberi.forkcast.menu.dto.MenuResponse.MenuCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MenuController.class)
class MenuControllerTest {

    private static final MenuItemResponse DOSA = new MenuItemResponse("i1", "Masala Dosa", "Crispy crepe", "Dosas",
            new BigDecimal("129.00"), true, SpiceLevel.MILD, List.of("breakfast"), true);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MenuService menuService;

    @Test
    void getMenuReturnsCategories() throws Exception {
        when(menuService.getMenu("r1"))
                .thenReturn(new MenuResponse("r1", 1, List.of(new MenuCategory("Dosas", List.of(DOSA)))));

        mockMvc.perform(get("/api/v1/restaurants/r1/menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(1))
                .andExpect(jsonPath("$.categories[0].name").value("Dosas"))
                .andExpect(jsonPath("$.categories[0].items[0].price").value(129.00))
                .andExpect(jsonPath("$.categories[0].items[0].spiceLevel").value("MILD"));
    }

    @Test
    void addItemReturns201() throws Exception {
        when(menuService.addItem(eq("r1"), any())).thenReturn(DOSA);

        mockMvc.perform(post("/api/v1/restaurants/r1/menu/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Masala Dosa", "category": "Dosas", "price": 129, "vegetarian": true }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/restaurants/r1/menu/items/i1"));
    }

    @Test
    void addItemValidatesPrice() throws Exception {
        mockMvc.perform(post("/api/v1/restaurants/r1/menu/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Free Lunch", "category": "Mains", "price": 0.5, "vegetarian": true }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").exists());
    }

    @Test
    void deleteItemReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/restaurants/r1/menu/items/i1"))
                .andExpect(status().isNoContent());

        verify(menuService).deleteItem("r1", "i1");
    }
}
