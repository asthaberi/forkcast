package com.asthaberi.forkcast.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * The containers integration tests run against, defined once so an image version is a one-line change.
 * Keep the versions in step with docker-compose.yml.
 */
public final class TestContainers {

    public static final String MONGO_IMAGE = "mongo:8.0";
    public static final String REDIS_IMAGE = "redis:8.2";

    private TestContainers() {
    }

    /** A single-node replica set, so multi-document transactions work as in production. */
    public static MongoDBContainer mongo() {
        return new MongoDBContainer(MONGO_IMAGE).withReplicaSet();
    }

    public static GenericContainer<?> redis() {
        return new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
    }
}
