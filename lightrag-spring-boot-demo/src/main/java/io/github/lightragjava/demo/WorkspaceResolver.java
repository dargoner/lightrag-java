package io.github.lightragjava.demo;

import io.github.lightragjava.spring.boot.LightRagProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
class WorkspaceResolver {
    private final LightRagProperties properties;

    WorkspaceResolver(LightRagProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    String resolve(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        var headerName = properties.getWorkspace().getHeaderName();
        if (headerName == null || headerName.isBlank()) {
            throw new IllegalStateException("lightrag.workspace.header-name must not be blank");
        }
        var workspaceId = request.getHeader(headerName);
        if (workspaceId == null || workspaceId.isBlank()) {
            workspaceId = properties.getWorkspace().getDefaultId();
        }
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalStateException("lightrag.workspace.default-id must not be blank");
        }
        return workspaceId.strip();
    }
}
