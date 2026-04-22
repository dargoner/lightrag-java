package io.github.lightrag.support;

import org.testcontainers.containers.Neo4jContainer;

public final class Neo4jTestContainers {
    private Neo4jTestContainers() {
    }

    public static Neo4jContainer<?> create() {
        return new Neo4jContainer<>("neo4j:5-community")
            .withAdminPassword("password")
            .withEnv("NEO4J_server_memory_heap_initial__size", "128M")
            .withEnv("NEO4J_server_memory_heap_max__size", "128M")
            .withEnv("NEO4J_server_memory_pagecache_size", "128M")
            .withEnv("NEO4J_server_jvm_additional", "-XX:+ExitOnOutOfMemoryError");
    }
}
