package io.github.lightrag.demo;

import io.github.lightrag.api.CreateEntityRequest;
import io.github.lightrag.api.CreateRelationRequest;
import io.github.lightrag.api.EditEntityRequest;
import io.github.lightrag.api.DeleteRelationRequest;
import io.github.lightrag.api.UpdateRelationRequest;
import io.github.lightrag.api.GraphEntity;
import io.github.lightrag.api.GraphRelation;
import io.github.lightrag.api.LightRag;
import io.github.lightrag.api.MergeEntitiesRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/graph")
class GraphController {
    private final LightRag lightRag;
    private final WorkspaceResolver workspaceResolver;

    GraphController(LightRag lightRag, WorkspaceResolver workspaceResolver) {
        this.lightRag = lightRag;
        this.workspaceResolver = workspaceResolver;
    }

    @PostMapping("/entities")
    GraphEntity createEntity(@RequestBody EntityCreatePayload payload, HttpServletRequest request) {
        var workspaceId = workspaceResolver.resolve(request);
        return lightRag.createEntity(workspaceId, CreateEntityRequest.builder()
            .name(payload.name())
            .type(payload.type())
            .description(payload.description())
            .aliases(defaultList(payload.aliases()))
            .build());
    }

    @PutMapping("/entities")
    GraphEntity editEntity(@RequestBody EntityEditPayload payload, HttpServletRequest request) {
        var workspaceId = workspaceResolver.resolve(request);
        return lightRag.editEntity(workspaceId, EditEntityRequest.builder()
            .entityName(payload.entityName())
            .newName(payload.newName())
            .type(payload.type())
            .description(payload.description())
            .aliases(payload.aliases())
            .build());
    }

    @PostMapping("/entities/merge")
    GraphEntity mergeEntities(@RequestBody EntityMergePayload payload, HttpServletRequest request) {
        var workspaceId = workspaceResolver.resolve(request);
        return lightRag.mergeEntities(workspaceId, MergeEntitiesRequest.builder()
            .sourceEntityNames(payload.sourceEntityNames())
            .targetEntityName(payload.targetEntityName())
            .targetType(payload.targetType())
            .targetDescription(payload.targetDescription())
            .targetAliases(payload.targetAliases())
            .build());
    }

    @DeleteMapping("/entities/{entityName}")
    ResponseEntity<Void> deleteEntity(@PathVariable String entityName, HttpServletRequest request) {
        var workspaceId = workspaceResolver.resolve(request);
        lightRag.deleteByEntity(workspaceId, entityName);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/relations")
    GraphRelation createRelation(@RequestBody RelationCreatePayload payload, HttpServletRequest request) {
        var workspaceId = workspaceResolver.resolve(request);
        return lightRag.createRelation(workspaceId, CreateRelationRequest.builder()
            .sourceEntityName(payload.sourceEntityName())
            .targetEntityName(payload.targetEntityName())
            .keywords(payload.keywords())
            .description(payload.description())
            .weight(payload.weight() == null ? CreateRelationRequest.DEFAULT_WEIGHT : payload.weight())
            .build());
    }

    @PutMapping("/relations")
    GraphRelation updateRelation(@RequestBody RelationUpdatePayload payload, HttpServletRequest request) {
        var workspaceId = workspaceResolver.resolve(request);
        return lightRag.updateRelation(workspaceId, UpdateRelationRequest.builder()
            .sourceEntityName(payload.sourceEntityName())
            .targetEntityName(payload.targetEntityName())
            .keywords(payload.keywords())
            .description(payload.description())
            .weight(payload.weight())
            .build());
    }

    @DeleteMapping("/relations")
    ResponseEntity<Void> deleteRelation(
        @RequestParam String sourceEntityName,
        @RequestParam String targetEntityName,
        HttpServletRequest request
    ) {
        var workspaceId = workspaceResolver.resolve(request);
        lightRag.deleteRelation(workspaceId, new DeleteRelationRequest(sourceEntityName, targetEntityName));
        return ResponseEntity.noContent().build();
    }

    private static List<String> defaultList(List<String> values) {
        return values == null ? List.of() : values;
    }

    record EntityCreatePayload(String name, String type, String description, List<String> aliases) {
    }

    record EntityEditPayload(String entityName, String newName, String type, String description, List<String> aliases) {
    }

    record EntityMergePayload(
        List<String> sourceEntityNames,
        String targetEntityName,
        String targetType,
        String targetDescription,
        List<String> targetAliases
    ) {
    }

    record RelationCreatePayload(
        String sourceEntityName,
        String targetEntityName,
        String keywords,
        String description,
        Double weight
    ) {
    }

    record RelationUpdatePayload(
        String sourceEntityName,
        String targetEntityName,
        String keywords,
        String description,
        Double weight
    ) {
    }
}
