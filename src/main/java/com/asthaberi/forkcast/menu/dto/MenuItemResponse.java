package com.asthaberi.forkcast.menu.dto;

import java.math.BigDecimal;
import java.util.List;

import com.asthaberi.forkcast.menu.SpiceLevel;

public record MenuItemResponse(
        String id,
        String name,
        String description,
        String category,
        BigDecimal price,
        boolean vegetarian,
        SpiceLevel spiceLevel,
        List<String> tags,
        boolean available) {
}
