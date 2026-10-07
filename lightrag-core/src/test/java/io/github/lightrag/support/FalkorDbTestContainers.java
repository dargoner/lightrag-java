package io.github.lightrag.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public final class FalkorDbTestContainers {
    private FalkorDbTestContainers() {
    }

    public static GenericContainer<?> create() {
        var image = DockerImageName.parse(
            System.getenv().getOrDefault("LIGHTRAG_FALKORDB_IMAGE", "falkordb/falkordb:latest")
        );
        return new GenericContainer<>(image)
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());
    }
}
