package io.github.lightrag.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public final class MemgraphTestContainers {
    private MemgraphTestContainers() {
    }

    public static GenericContainer<?> create() {
        return new GenericContainer<>(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687)
            .withCommand("--telemetry-enabled=false")
            .waitingFor(Wait.forListeningPort());
    }
}
