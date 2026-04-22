package io.github.lightrag.api;

public record DeleteRelationRequest(
    String sourceEntityName,
    String targetEntityName
) {
    public DeleteRelationRequest {
        sourceEntityName = CreateRelationRequest.normalizeEndpoint(sourceEntityName, "sourceEntityName");
        targetEntityName = CreateRelationRequest.normalizeEndpoint(targetEntityName, "targetEntityName");
    }
}
