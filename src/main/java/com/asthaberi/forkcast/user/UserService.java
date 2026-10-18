package com.asthaberi.forkcast.user;

import com.asthaberi.forkcast.common.exception.ResourceNotFoundException;
import com.asthaberi.forkcast.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public UserResponse getUser(String userId) {
        return userRepository.findById(userId)
                .map(UserService::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    public static UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(),
                user.getRoles().stream().map(Role::name).sorted().toList());
    }
}
