package io.github.lightragjava.demo;

import io.github.lightragjava.api.CreateEntityRequest;
import io.github.lightragjava.api.CreateRelationRequest;
import io.github.lightragjava.api.EditEntityRequest;
import io.github.lightragjava.api.EditRelationRequest;
import io.github.lightragjava.api.GraphEntity;
import io.github.lightragjava.api.GraphRelation;
import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.api.MergeEntitiesRequest;
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

    GraphController(LightRag lightRag) {
        this.lightRag = lightRag;
    }

    @PostMapping("/entities")
    GraphEntity createEntity(@RequestBody EntityCreatePayload payload) {
        return lightRag.createEntity(CreateEntityRequest.builder()
            .name(payload.name())
            .type(payload.type())
            .description(payload.description())
            .aliases(defaultList(payload.aliases()))
            .build());
    }

    @PutMapping("/entities")
    GraphEntity editEntity(@RequestBody EntityEditPayload payload) {
        return lightRag.editEntity(EditEntityRequest.builder()
            .entityName(payload.entityName())
            .newName(payload.newName())
            .type(payload.type())
            .description(payload.description())
            .aliases(payload.aliases())
            .build());
    }

    @PostMapping("/entities/merge")
    GraphEntity mergeEntities(@RequestBody EntityMergePayload payload) {
        return lightRag.mergeEntities(MergeEntitiesRequest.builder()
            .sourceEntityNames(payload.sourceEntityNames())
            .targetEntityName(payload.targetEntityName())
            .targetType(payload.targetType())
            .targetDescription(payload.targetDescription())
            .targetAliases(payload.targetAliases())
            .build());
    }

    @DeleteMapping("/entities/{entityName}")
    ResponseEntity<Void> deleteEntity(@PathVariable String entityName) {
        lightRag.deleteByEntity(entityName);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/relations")
    GraphRelation createRelation(@RequestBody RelationCreatePayload payload) {
        return lightRag.createRelation(CreateRelationRequest.builder()
            .sourceEntityName(payload.sourceEntityName())
            .targetEntityName(payload.targetEntityName())
            .relationType(payload.relationType())
            .description(payload.description())
            .weight(payload.weight() == null ? CreateRelationRequest.DEFAULT_WEIGHT : payload.weight())
            .build());
    }

    @PutMapping("/relations")
    GraphRelation editRelation(@RequestBody RelationEditPayload payload) {
        return lightRag.editRelation(EditRelationRequest.builder()
            .sourceEntityName(payload.sourceEntityName())
            .targetEntityName(payload.targetEntityName())
            .currentRelationType(payload.currentRelationType())
            .newRelationType(payload.newRelationType())
            .description(payload.description())
            .weight(payload.weight())
            .build());
    }

    @DeleteMapping("/relations")
    ResponseEntity<Void> deleteRelation(
        @RequestParam String sourceEntityName,
        @RequestParam String targetEntityName
    ) {
        lightRag.deleteByRelation(sourceEntityName, targetEntityName);
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
        String relationType,
        String description,
        Double weight
    ) {
    }

    record RelationEditPayload(
        String sourceEntityName,
        String targetEntityName,
        String currentRelationType,
        String newRelationType,
        String description,
        Double weight
    ) {
    }
}
