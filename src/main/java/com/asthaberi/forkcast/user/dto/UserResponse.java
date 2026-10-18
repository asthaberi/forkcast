package com.asthaberi.forkcast.user.dto;

import java.util.List;

public record UserResponse(String id, String name, String email, List<String> roles) {
}
