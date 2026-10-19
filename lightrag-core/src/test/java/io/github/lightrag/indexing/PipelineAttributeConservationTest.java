package io.github.lightrag.indexing;

import io.github.lightrag.indexing.refinement.ExtractionRefinementOptions;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.ontology.Ontology;
import io.github.lightrag.ontology.OntologyAttributeProjector;
import io.github.lightrag.ontology.OntologyEntityType;
import io.github.lightrag.ontology.OntologyExtractionOverlay;
import io.github.lightrag.ontology.OntologyProperty;
import io.github.lightrag.ontology.OntologyPropertyType;
import io.github.lightrag.ontology.OntologyRelationType;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.task.TaskMetadataReporter;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Entity;
import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
import io.github.lightrag.types.ExtractionResult;
import io.github.lightrag.types.Relation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attribute conservation across the two graph-writing pipelines: the final projection at
 * each save boundary, merge accumulation, chunk-snapshot resume, and deletion rebuilds.
 */
class PipelineAttributeConservationTest {

    @Test
    void indexingSaveGraphProjectsOnceOntoTheFinalEntityAndRelation() {
        var storage = InMemoryStorageProvider.create();
        var pipeline = indexingPipeline(storage, attributeOntology());
        var entityAttributes = new LinkedHashMap<String, Object>();
        entityAttributes.put("age", 30L);
        entityAttributes.put("city", "Paris");
        entityAttributes.put("undeclared", "dropped");
        var relationAttributes = new LinkedHashMap<String, Object>();
        relationAttributes.put("since", "2020");
        relationAttributes.put("undeclared", "dropped");

        var graph = new GraphAssembler.Graph(
            List.of(new Entity(
                "person:alice", "Alice", "Person", "Alice", List.of(), List.of("chunk-1"), "doc.md",
                entityAttributes
            )),
            List.of(new Relation(
                "rel-1", "person:alice", "person:bob", "works_with", "works with", 1.0d, "chunk-1", "doc.md",
                relationAttributes
            ))
        );

        storage.writeAtomically(view -> {
            pipeline.saveGraph(graph, view);
            return null;
        });

        assertThat(storage.graphStore().loadEntity("person:alice")).get().satisfies(saved ->
            assertThat(saved.attributes()).containsExactly(
                Map.entry("age", 30L),
                Map.entry("city", "Paris")
            )
        );
        assertThat(storage.graphStore().loadRelation("rel-1")).get().satisfies(saved ->
            assertThat(saved.attributes()).containsExactly(Map.entry("since", "2020"))
        );
    }

    @Test
    void materializationSaveGraphProjectsOnceOntoTheFinalEntityAndRelation() {
        var storage = InMemoryStorageProvider.create();
        var pipeline = graphMaterializationPipeline(storage, attributeOntology());

        var graph = new GraphAssembler.Graph(
            List.of(new Entity(
                "person:alice", "Alice", "Person", "Alice", List.of(), List.of("chunk-1"), "doc.md",
                Map.of("age", 42L, "undeclared", "dropped")
            )),
            List.of(new Relation(
                "rel-1", "person:alice", "person:bob", "works_with", "works with", 1.0d, "chunk-1", "doc.md",
                Map.of("since", "2019", "undeclared", "dropped")
            ))
        );

        storage.writeAtomically(view -> {
            pipeline.saveGraph(graph, view);
            return null;
        });

        assertThat(storage.graphStore().loadEntity("person:alice")).get().satisfies(saved ->
            assertThat(saved.attributes()).containsExactly(Map.entry("age", 42L))
        );
        assertThat(storage.graphStore().loadRelation("rel-1")).get().satisfies(saved ->
            assertThat(saved.attributes()).containsExactly(Map.entry("since", "2019"))
        );
    }

    @Test
    void entityAndRelationMergesCombineAttributesWithIncomingWinning() {
        var existingEntity = new GraphStore.EntityRecord(
            "person:alice", "Alice", "Person", "stored", List.of(), List.of("chunk-0"), "",
            Map.of("age", 1L, "city", "A")
        );
        var incomingEntity = new Entity(
            "person:alice", "Alice", "Person", "incoming", List.of(), List.of("chunk-1"), "",
            Map.of("age", 2L, "country", "B")
        );

        var mergedEntity = IndexingPipeline.mergeEntityWithCaps(
            existingEntity,
            incomingEntity,
            false,
            SourceIdLimits.DEFAULT_MAX_SOURCE_IDS,
            SourceIdLimits.Method.KEEP,
            FilePathLimits.DEFAULT_MAX_FILE_PATHS,
            DescriptionSummarizer.withoutLlm()
        );

        assertThat(mergedEntity.attributes()).containsExactlyInAnyOrderEntriesOf(
            Map.of("age", 2L, "city", "A", "country", "B")
        );

        var existingRelation = new GraphStore.RelationRecord(
            "rel-1", "person:alice", "person:bob", "works_with", "stored", 1.0d, "chunk-0", "",
            Map.of("since", "2019", "scope", "A")
        );
        var incomingRelation = new Relation(
            "rel-1", "person:alice", "person:bob", "works_with", "incoming", 1.0d, "chunk-1", "",
            Map.of("since", "2020", "owner", "B")
        );

        var mergedRelation = IndexingPipeline.mergeRelationWithCaps(
            existingRelation,
            incomingRelation,
            false,
            SourceIdLimits.DEFAULT_MAX_SOURCE_IDS,
            SourceIdLimits.Method.KEEP,
            FilePathLimits.DEFAULT_MAX_FILE_PATHS,
            DescriptionSummarizer.withoutLlm()
        );

        assertThat(mergedRelation.sourceChunkIds()).containsExactly("chunk-0", "chunk-1");
        assertThat(mergedRelation.attributes()).containsExactlyInAnyOrderEntriesOf(
            Map.of("since", "2020", "scope", "A", "owner", "B")
        );
    }

    @Test
    void chunkSnapshotRoundTripPreservesExtractedAttributes() {
        var storage = InMemoryStorageProvider.create();
        var pipeline = graphMaterializationPipeline(storage, attributeOntology());
        var chunk = new Chunk("doc-1:0", "doc-1", "Alice works with Bob", 4, 0, Map.of());
        var extraction = new GraphAssembler.ChunkExtraction(
            chunk.id(),
            new ExtractionResult(
                List.of(new ExtractedEntity(
                    "Alice", "Person", "Alice", List.of(),
                    Map.of("age", 30L, "undeclared", "kept")
                )),
                List.of(new ExtractedRelation(
                    "Alice", "Bob", "works_with", "works with", 1.0d,
                    Map.of("since", "2020", "undeclared", "kept")
                )),
                List.of()
            ),
            List.of()
        );

        var snapshots = pipeline.toChunkSnapshots("doc-1", List.of(extraction), List.of(chunk), Instant.now());
        var restored = GraphMaterializationPipeline.toChunkExtractions(snapshots);

        assertThat(restored).singleElement().satisfies(restoredExtraction -> {
            assertThat(restoredExtraction.extraction().entities()).singleElement().satisfies(entity ->
                assertThat(entity.attributes()).containsExactly(
                    Map.entry("age", 30L),
                    Map.entry("undeclared", "kept")
                )
            );
            assertThat(restoredExtraction.extraction().relations()).singleElement().satisfies(relation ->
                assertThat(relation.attributes()).containsExactly(
                    Map.entry("since", "2020"),
                    Map.entry("undeclared", "kept")
                )
            );
        });
    }

    @Test
    void deletionChunkReferenceRebuildPreservesAttributes() {
        var entity = new GraphStore.EntityRecord(
            "person:alice", "Alice", "Person", "Alice", List.of(), List.of("chunk-1", "chunk-2"), "",
            Map.of("age", 30L)
        );
        var trimmedEntity = DeletionPipeline.removeEntityChunkReferences(entity, Set.of("chunk-1"));

        assertThat(trimmedEntity.sourceChunkIds()).containsExactly("chunk-2");
        assertThat(trimmedEntity.attributes()).containsExactly(Map.entry("age", 30L));

        var relation = new GraphStore.RelationRecord(
            "rel-1", "person:alice", "person:bob", "works_with", "works with", 1.0d,
            "chunk-1" + RelationCanonicalizer.GRAPH_FIELD_SEPARATOR + "chunk-2", "", Map.of("since", "2020")
        );
        var trimmedRelation = DeletionPipeline.removeRelationChunkReferences(relation, Set.of("chunk-1"));

        assertThat(trimmedRelation.sourceChunkIds()).containsExactly("chunk-2");
        assertThat(trimmedRelation.attributes()).containsExactly(Map.entry("since", "2020"));
    }

    @Test
    void projectorWithoutOntologyPassesAttributesThrough() {
        var attributes = Map.<String, Object>of("manual", "keep", "age", 7L);

        assertThat(OntologyAttributeProjector.projectEntity("Person", attributes, null))
            .containsExactlyInAnyOrderEntriesOf(attributes);
        assertThat(OntologyAttributeProjector.projectRelation("works_with", attributes, null))
            .containsExactlyInAnyOrderEntriesOf(attributes);
        assertThat(OntologyAttributeProjector.projectEntity("Person", Map.of(), null)).isEmpty();
    }

    @Test
    void indexingSaveGraphWithoutOntologyKeepsExistingManualAttributes() {
        var storage = InMemoryStorageProvider.create();
        var pipeline = indexingPipeline(storage, null);
        seedManualAttributes(storage);

        storage.writeAtomically(view -> {
            pipeline.saveGraph(reingestGraphWithAttributes(), view);
            return null;
        });

        assertConservedAttributes(storage);
    }

    @Test
    void materializationSaveGraphWithoutOntologyKeepsExistingManualAttributes() {
        var storage = InMemoryStorageProvider.create();
        var pipeline = graphMaterializationPipeline(storage, null);
        seedManualAttributes(storage);

        storage.writeAtomically(view -> {
            pipeline.saveGraph(reingestGraphWithAttributes(), view);
            return null;
        });

        assertConservedAttributes(storage);
    }

    private static void seedManualAttributes(AtomicStorageProvider storage) {
        storage.writeAtomically(view -> {
            view.graphStore().saveEntities(List.of(new GraphStore.EntityRecord(
                "person:alice", "Alice", "Person", "stored", List.of(), List.of("chunk-0"), "",
                Map.of("manual", "keep", "age", 7L)
            )));
            view.graphStore().saveRelations(List.of(new GraphStore.RelationRecord(
                "rel-1", "person:alice", "person:bob", "works_with", "works with", 1.0d,
                "chunk-0", "", Map.of("manual_rel", "keep")
            )));
            return null;
        });
    }

    private static GraphAssembler.Graph reingestGraphWithAttributes() {
        return new GraphAssembler.Graph(
            List.of(new Entity(
                "person:alice", "Alice", "Person", "Alice", List.of(), List.of("chunk-1"), "doc.md",
                Map.of("incoming", "new")
            )),
            List.of(new Relation(
                "rel-1", "person:alice", "person:bob", "works_with", "works with", 1.0d, "chunk-1", "doc.md",
                Map.of("since", "2021")
            ))
        );
    }

    private static void assertConservedAttributes(AtomicStorageProvider storage) {
        assertThat(storage.graphStore().loadEntity("person:alice")).get().satisfies(saved ->
            assertThat(saved.attributes())
                .containsEntry("manual", "keep")
                .containsEntry("age", 7L)
                .containsEntry("incoming", "new")
        );
        assertThat(storage.graphStore().loadRelation("rel-1")).get().satisfies(saved ->
            assertThat(saved.attributes())
                .containsEntry("manual_rel", "keep")
                .containsEntry("since", "2021")
        );
    }

    private static Ontology attributeOntology() {
        var person = new OntologyEntityType(
            "Person",
            null,
            List.of(
                new OntologyProperty("Age", OntologyPropertyType.INTEGER, null, null),
                new OntologyProperty("City", OntologyPropertyType.STRING, null, null)
            ),
            null
        );
        var worksWith = new OntologyRelationType(
            "works_with",
            null,
            List.of(),
            List.of(new OntologyProperty("Since", OntologyPropertyType.STRING, null, null)),
            null
        );
        return new Ontology(List.of(person), List.of(worksWith), null, Map.of());
    }

    private static IndexingPipeline indexingPipeline(AtomicStorageProvider storage, Ontology ontology) {
        ChatModel chatModel = request -> "";
        EmbeddingModel embeddingModel = texts -> texts.stream().map(text -> List.of(1.0d, 1.0d)).toList();
        return new IndexingPipeline(
            chatModel,
            chatModel,
            embeddingModel,
            storage,
            null,
            null,
            null,
            100,
            1,
            1,
            KnowledgeExtractor.DEFAULT_ENTITY_EXTRACT_MAX_GLEANING,
            KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS,
            KnowledgeExtractor.DEFAULT_LANGUAGE,
            KnowledgeExtractor.DEFAULT_ENTITY_TYPES,
            true,
            List.of(),
            List.of(),
            false,
            0.80d,
            ExtractionRefinementOptions.disabled(),
            IndexingProgressListener.noop(),
            DescriptionSummarizer.withoutLlm(),
            SourceIdLimits.DEFAULT_MAX_SOURCE_IDS,
            SourceIdLimits.DEFAULT_MAX_SOURCE_IDS,
            SourceIdLimits.Method.KEEP,
            FilePathLimits.DEFAULT_MAX_FILE_PATHS,
            KnowledgeExtractor.DEFAULT_MAX_EXTRACTION_RECORDS,
            KnowledgeExtractor.DEFAULT_MAX_EXTRACTION_ENTITIES,
            null,
            true,
            OntologyExtractionOverlay.empty(),
            ontology
        );
    }

    private static GraphMaterializationPipeline graphMaterializationPipeline(
        AtomicStorageProvider storage,
        Ontology ontology
    ) {
        ChatModel chatModel = request -> "";
        EmbeddingModel embeddingModel = texts -> texts.stream().map(text -> List.of(1.0d, 1.0d)).toList();
        return new GraphMaterializationPipeline(
            chatModel,
            embeddingModel,
            storage,
            ExtractionRefinementOptions.disabled(),
            null,
            TaskMetadataReporter.noop(),
            IndexingProgressListener.noop(),
            1,
            KnowledgeExtractor.DEFAULT_ENTITY_EXTRACT_MAX_GLEANING,
            KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS,
            KnowledgeExtractor.DEFAULT_LANGUAGE,
            KnowledgeExtractor.DEFAULT_ENTITY_TYPES,
            List.of(),
            List.of(),
            io.github.lightrag.api.CancellationCheckpoint.NONE,
            DescriptionSummarizer.withoutLlm(),
            SourceIdLimits.DEFAULT_MAX_SOURCE_IDS,
            SourceIdLimits.DEFAULT_MAX_SOURCE_IDS,
            SourceIdLimits.Method.KEEP,
            FilePathLimits.DEFAULT_MAX_FILE_PATHS,
            KnowledgeExtractor.DEFAULT_MAX_EXTRACTION_RECORDS,
            KnowledgeExtractor.DEFAULT_MAX_EXTRACTION_ENTITIES,
            null,
            true,
            OntologyExtractionOverlay.empty(),
            ontology
        );
    }
}
