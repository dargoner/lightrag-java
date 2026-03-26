package io.github.lightragjava.api;

import java.util.Objects;

public record WorkspaceScope(String workspaceId) {
    public WorkspaceScope {
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        if (workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        workspaceId = workspaceId.strip();
    }
}

