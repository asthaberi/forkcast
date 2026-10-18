package com.asthaberi.forkcast.user;

import com.asthaberi.forkcast.common.web.CurrentUser;
import com.asthaberi.forkcast.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Users", description = "The signed-in user")
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    @Operation(summary = "Get the signed-in user")
    public UserResponse me(@CurrentUser String userId) {
        return userService.getUser(userId);
    }
}
