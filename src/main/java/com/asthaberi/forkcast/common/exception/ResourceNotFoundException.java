package com.asthaberi.forkcast.common.exception;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, String id) {
        super("%s not found with id '%s'".formatted(resource, id));
    }
}
