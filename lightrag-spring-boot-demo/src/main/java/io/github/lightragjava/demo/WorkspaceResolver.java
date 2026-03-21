package io.github.lightragjava.demo;

import io.github.lightragjava.spring.boot.LightRagProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.regex.Pattern;

@Component
class WorkspaceResolver {
    private static final int MAX_WORKSPACE_ID_LENGTH = 64;
    private static final Pattern WORKSPACE_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

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
        var normalized = workspaceId.strip();
        if (normalized.length() > MAX_WORKSPACE_ID_LENGTH || !WORKSPACE_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("workspaceId must match [A-Za-z0-9][A-Za-z0-9_-]* and be at most 64 characters");
        }
        return normalized;
    }
}
