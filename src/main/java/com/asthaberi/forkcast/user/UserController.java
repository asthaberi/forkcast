package com.asthaberi.forkcast.user;

import com.asthaberi.forkcast.common.web.CurrentUser;
import com.asthaberi.forkcast.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public UserResponse me(@CurrentUser String userId) {
        return userService.getUser(userId);
    }
}
