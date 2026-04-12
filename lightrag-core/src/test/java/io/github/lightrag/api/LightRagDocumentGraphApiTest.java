package io.github.lightrag.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class LightRagDocumentGraphApiTest {
    @Test
    void exposesDocumentAndChunkGraphMaterializationApiSignatures() throws Exception {
        assertMethod(
            "inspectDocumentGraph",
            DocumentGraphInspection.class,
            String.class,
            String.class
        );
        assertMethod(
            "materializeDocumentGraph",
            DocumentGraphMaterializationResult.class,
            String.class,
            String.class,
            GraphMaterializationMode.class
        );
        assertMethod(
            "getDocumentChunkGraphStatus",
            DocumentChunkGraphStatus.class,
            String.class,
            String.class,
            String.class
        );
        assertMethod(
            "listDocumentChunkGraphStatuses",
            java.util.List.class,
            String.class,
            String.class
        );
        assertGenericListReturnType(
            "listDocumentChunkGraphStatuses",
            DocumentChunkGraphStatus.class,
            String.class,
            String.class
        );
        assertMethod(
            "resumeChunkGraph",
            ChunkGraphMaterializationResult.class,
            String.class,
            String.class,
            String.class
        );
        assertMethod(
            "repairChunkGraph",
            ChunkGraphMaterializationResult.class,
            String.class,
            String.class,
            String.class
        );
        assertMethod(
            "submitDocumentGraphMaterialization",
            String.class,
            String.class,
            String.class,
            GraphMaterializationMode.class
        );
        assertMethod(
            "submitChunkGraphMaterialization",
            String.class,
            String.class,
            String.class,
            String.class,
            GraphChunkAction.class
        );
    }

    @Test
    void graphMaterializationEnumsMatchSpec() {
        assertThat(GraphMaterializationMode.values()).containsExactly(
            GraphMaterializationMode.AUTO,
            GraphMaterializationMode.RESUME,
            GraphMaterializationMode.REPAIR,
            GraphMaterializationMode.REBUILD
        );
        assertThat(GraphMaterializationStatus.values()).containsExactly(
            GraphMaterializationStatus.NOT_STARTED,
            GraphMaterializationStatus.MERGING,
            GraphMaterializationStatus.PARTIAL,
            GraphMaterializationStatus.MERGED,
            GraphMaterializationStatus.FAILED,
            GraphMaterializationStatus.STALE,
            GraphMaterializationStatus.MISSING
        );
        assertThat(ChunkExtractStatus.values()).containsExactly(
            ChunkExtractStatus.NOT_STARTED,
            ChunkExtractStatus.RUNNING,
            ChunkExtractStatus.SUCCEEDED,
            ChunkExtractStatus.FAILED
        );
        assertThat(ChunkMergeStatus.values()).containsExactly(
            ChunkMergeStatus.NOT_STARTED,
            ChunkMergeStatus.RUNNING,
            ChunkMergeStatus.SUCCEEDED,
            ChunkMergeStatus.FAILED
        );
        assertThat(ChunkGraphStatus.values()).containsExactly(
            ChunkGraphStatus.NOT_MATERIALIZED,
            ChunkGraphStatus.MATERIALIZED,
            ChunkGraphStatus.PARTIAL,
            ChunkGraphStatus.FAILED,
            ChunkGraphStatus.STALE,
            ChunkGraphStatus.MISSING
        );
        assertThat(SnapshotStatus.values()).containsExactly(
            SnapshotStatus.BUILDING,
            SnapshotStatus.READY,
            SnapshotStatus.PARTIAL,
            SnapshotStatus.FAILED
        );
        assertThat(SnapshotSource.values()).containsExactly(
            SnapshotSource.PRIMARY_EXTRACTION,
            SnapshotSource.RECOVERED_FROM_STORAGE
        );
        assertThat(GraphChunkAction.values()).containsExactly(
            GraphChunkAction.NONE,
            GraphChunkAction.RESUME,
            GraphChunkAction.REPAIR
        );
        assertThat(FailureStage.values()).containsExactly(
            FailureStage.SNAPSHOT_LOADING,
            FailureStage.SNAPSHOT_RECOVERY,
            FailureStage.GRAPH_INSPECTION,
            FailureStage.ENTITY_MATERIALIZATION,
            FailureStage.RELATION_MATERIALIZATION,
            FailureStage.VECTOR_REPAIR,
            FailureStage.FINALIZING
        );
    }

    @Test
    void materializationRecordShapesMatchSpec() {
        assertRecord(
            DocumentGraphInspection.class,
            new String[] {
                "documentId",
                "documentStatus",
                "graphStatus",
                "snapshotStatus",
                "snapshotVersion",
                "expectedEntityCount",
                "expectedRelationCount",
                "materializedEntityCount",
                "materializedRelationCount",
                "missingEntityKeys",
                "missingRelationKeys",
                "orphanEntityKeys",
                "orphanRelationKeys",
                "recommendedMode",
                "repairable",
                "summary"
            },
            new Class<?>[] {
                String.class,
                DocumentStatus.class,
                GraphMaterializationStatus.class,
                SnapshotStatus.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                java.util.List.class,
                java.util.List.class,
                java.util.List.class,
                java.util.List.class,
                GraphMaterializationMode.class,
                boolean.class,
                String.class
            },
            "missingEntityKeys",
            "missingRelationKeys",
            "orphanEntityKeys",
            "orphanRelationKeys"
        );
        assertRecord(
            DocumentGraphMaterializationResult.class,
            new String[] {
                "documentId",
                "requestedMode",
                "executedMode",
                "finalStatus",
                "snapshotVersion",
                "entitiesExpected",
                "relationsExpected",
                "entitiesMaterialized",
                "relationsMaterialized",
                "snapshotReused",
                "snapshotRecoveredFromStorage",
                "summary",
                "errorMessage"
            },
            new Class<?>[] {
                String.class,
                GraphMaterializationMode.class,
                GraphMaterializationMode.class,
                GraphMaterializationStatus.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                boolean.class,
                String.class,
                String.class
            },
            new String[0]
        );
        assertRecord(
            DocumentChunkGraphStatus.class,
            new String[] {
                "documentId",
                "chunkId",
                "chunkOrder",
                "extractStatus",
                "mergeStatus",
                "graphStatus",
                "expectedEntityCount",
                "expectedRelationCount",
                "materializedEntityCount",
                "materializedRelationCount",
                "missingEntityKeys",
                "missingRelationKeys",
                "repairable",
                "recommendedAction",
                "errorMessage"
            },
            new Class<?>[] {
                String.class,
                String.class,
                int.class,
                ChunkExtractStatus.class,
                ChunkMergeStatus.class,
                ChunkGraphStatus.class,
                int.class,
                int.class,
                int.class,
                int.class,
                java.util.List.class,
                java.util.List.class,
                boolean.class,
                GraphChunkAction.class,
                String.class
            },
            "missingEntityKeys",
            "missingRelationKeys"
        );
        assertRecord(
            ChunkGraphMaterializationResult.class,
            new String[] {
                "documentId",
                "chunkId",
                "executedAction",
                "finalStatus",
                "expectedEntityCount",
                "expectedRelationCount",
                "materializedEntityCount",
                "materializedRelationCount",
                "summary",
                "errorMessage"
            },
            new Class<?>[] {
                String.class,
                String.class,
                GraphChunkAction.class,
                ChunkGraphStatus.class,
                int.class,
                int.class,
                int.class,
                int.class,
                String.class,
                String.class
            },
            new String[0]
        );
    }

    private static void assertMethod(String name, Class<?> returnType, Class<?>... parameterTypes) throws Exception {
        Method method = LightRag.class.getDeclaredMethod(name, parameterTypes);
        assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
        assertThat(method.getReturnType()).isEqualTo(returnType);
    }

    private static void assertGenericListReturnType(
        String methodName,
        Class<?> elementType,
        Class<?>... parameterTypes
    ) throws Exception {
        Method method = LightRag.class.getDeclaredMethod(methodName, parameterTypes);
        Type genericReturnType = method.getGenericReturnType();
        assertThat(genericReturnType).isInstanceOf(ParameterizedType.class);
        ParameterizedType parameterizedType = (ParameterizedType) genericReturnType;
        assertThat(parameterizedType.getRawType()).isEqualTo(List.class);
        assertThat(parameterizedType.getActualTypeArguments()).containsExactly(elementType);
    }

    private static void assertRecord(
        Class<?> recordType,
        String[] names,
        Class<?>[] types,
        String... listStringFields
    ) {
        assertThat(recordType.isRecord()).isTrue();
        RecordComponent[] components = recordType.getRecordComponents();
        assertThat(Arrays.stream(components).map(RecordComponent::getName).collect(Collectors.toList()))
            .containsExactly(names);
        assertThat(Arrays.stream(components).map(RecordComponent::getType).collect(Collectors.toList()))
            .containsExactly(types);
        assertThat(Arrays.stream(components)
            .filter(component -> component.getType().equals(List.class))
            .map(RecordComponent::getName)
            .collect(Collectors.toSet()))
            .containsExactlyInAnyOrder(listStringFields);
        for (String fieldName : listStringFields) {
            RecordComponent component = Arrays.stream(components)
                .filter(candidate -> candidate.getName().equals(fieldName))
                .findFirst()
                .orElseThrow();
            assertThat(component.getGenericType()).isInstanceOf(ParameterizedType.class);
            ParameterizedType parameterizedType = (ParameterizedType) component.getGenericType();
            assertThat(parameterizedType.getRawType()).isEqualTo(List.class);
            assertThat(parameterizedType.getActualTypeArguments()).containsExactly(String.class);
        }
    }
}
