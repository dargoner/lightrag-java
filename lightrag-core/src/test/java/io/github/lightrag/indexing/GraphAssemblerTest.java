package io.github.lightrag.indexing;

import io.github.lightrag.types.Entity;
import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
import io.github.lightrag.types.ExtractionResult;
import io.github.lightrag.types.Relation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;
import static io.github.lightrag.support.RelationIds.relationId;

class GraphAssemblerTest {
    @Test
    void mergesEntitiesByNormalizedName() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction("chunk-1", List.of(new ExtractedEntity(" Alice ", "person", "Researcher", List.of())), List.of()),
            extraction("chunk-2", List.of(new ExtractedEntity("alice", "person", "Scientist", List.of())), List.of())
        ));

        assertThat(graph.entities()).containsExactly(
            new Entity("alice", "Alice", "person", "Researcher<SEP>Scientist", List.of(), List.of("chunk-1", "chunk-2"))
        );
        assertThat(graph.relations()).isEmpty();
    }

    @Test
    void accumulatesDescriptionFragmentsAcrossChunksForTheSameEntity() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction("chunk-1", List.of(new ExtractedEntity("Alice", "person", "desc-a", List.of())), List.of()),
            extraction("chunk-2", List.of(new ExtractedEntity("alice", "person", "desc-b", List.of())), List.of()),
            extraction("chunk-3", List.of(new ExtractedEntity("ALICE", "person", "desc-a", List.of())), List.of())
        ));

        assertThat(graph.entities()).containsExactly(
            new Entity("alice", "Alice", "person", "desc-a<SEP>desc-b", List.of(), List.of("chunk-1", "chunk-2", "chunk-3"))
        );
    }

    @Test
    void mergesEntitiesByExplicitAliases() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction("chunk-1", List.of(new ExtractedEntity("Robert", "person", "Lead", List.of("Bob"))), List.of()),
            extraction("chunk-2", List.of(new ExtractedEntity("Bob", "person", "Engineer", List.of("Bobby"))), List.of())
        ));

        assertThat(graph.entities()).containsExactly(
            new Entity("robert", "Robert", "person", "Lead<SEP>Engineer", List.of("Bob", "Bobby"), List.of("chunk-1", "chunk-2"))
        );
    }

    @Test
    void mergesRelationsByCanonicalEndpoints() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction(
                "chunk-1",
                List.of(
                    new ExtractedEntity("Alice", "person", "Researcher", List.of()),
                    new ExtractedEntity("Bob", "person", "Engineer", List.of())
                ),
                List.of(new ExtractedRelation("Alice", "Bob", "works_with", "collaboration", 0.8d))
            ),
            extraction(
                "chunk-2",
                List.of(
                    new ExtractedEntity(" alice ", "person", "Researcher", List.of()),
                    new ExtractedEntity("Robert", "person", "Engineer", List.of("Bob"))
                ),
                List.of(new ExtractedRelation("ALICE", "ROBERT", "works_with", "duplicate", 1.0d))
            )
        ));

        assertThat(graph.entities()).containsExactly(
            new Entity("alice", "Alice", "person", "Researcher", List.of(), List.of("chunk-1", "chunk-2")),
            new Entity("bob", "Bob", "person", "Engineer", List.of("Robert"), List.of("chunk-1", "chunk-2"))
        );
        assertThat(graph.relations()).containsExactly(
            new Relation(
                relationId("alice", "bob"),
                "alice",
                "bob",
                "works_with",
                "collaboration<SEP>duplicate",
                1.8d,
                List.of("chunk-1", "chunk-2")
            )
        );
    }

    @Test
    void mergesRelationKeywordVariantsIntoSingleCanonicalEdge() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction(
                "chunk-1",
                List.of(
                    new ExtractedEntity("Alice", "person", "Researcher", List.of()),
                    new ExtractedEntity("Bob", "person", "Engineer", List.of())
                ),
                List.of(new ExtractedRelation("Alice", "Bob", "works_with", "first", 0.7d))
            ),
            extraction(
                "chunk-2",
                List.of(),
                List.of(new ExtractedRelation("Alice", "Bob", "works-with", "second", 0.9d))
            )
        ));

        assertThat(graph.relations()).containsExactly(
            new Relation(
                relationId("alice", "bob"),
                "alice",
                "bob",
                "works_with",
                "first<SEP>second",
                1.6d,
                List.of("chunk-1", "chunk-2")
            )
        );
    }

    @Test
    void foldsSymmetricRelationsIntoSingleCanonicalEdge() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction(
                "chunk-1",
                List.of(
                    new ExtractedEntity("Alice", "person", "Researcher", List.of()),
                    new ExtractedEntity("Bob", "person", "Engineer", List.of())
                ),
                List.of(new ExtractedRelation("Alice", "Bob", "related_to", "forward", 0.6d))
            ),
            extraction(
                "chunk-2",
                List.of(),
                List.of(new ExtractedRelation("Bob", "Alice", "related-to", "reverse", 0.8d))
            )
        ));

        assertThat(graph.relations()).containsExactly(
            new Relation(
                relationId("alice", "bob"),
                "alice",
                "bob",
                "related_to",
                "forward<SEP>reverse",
                1.4d,
                List.of("chunk-1", "chunk-2")
            )
        );
    }

    @Test
    void mergesRepeatedEndpointsEvenWhenRelationKeywordsSuggestDirection() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction(
                "chunk-1",
                List.of(
                    new ExtractedEntity("Alice", "person", "Manager", List.of()),
                    new ExtractedEntity("Bob", "person", "Engineer", List.of())
                ),
                List.of(new ExtractedRelation("Alice", "Bob", "reports_to", "forward", 0.6d))
            ),
            extraction(
                "chunk-2",
                List.of(),
                List.of(new ExtractedRelation("Bob", "Alice", "reports_to", "reverse", 0.8d))
            )
        ));

        assertThat(graph.relations()).containsExactly(
            new Relation(
                relationId("alice", "bob"),
                "alice",
                "bob",
                "reports_to",
                "forward<SEP>reverse",
                1.4d,
                List.of("chunk-1", "chunk-2")
            )
        );
    }

    @Test
    void skipsSelfLoopRelationsAfterEndpointNormalization() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction(
                "chunk-1",
                List.of(
                    new ExtractedEntity("Alice", "person", "Researcher", List.of()),
                    new ExtractedEntity("Bob", "person", "Engineer", List.of())
                ),
                List.of(
                    new ExtractedRelation(" Alice ", "alice", "same_as", "self loop", 0.4d),
                    new ExtractedRelation("Alice", "Bob", "works_with", "valid edge", 0.8d)
                )
            )
        ));

        assertThat(graph.entities()).containsExactly(
            new Entity("alice", "Alice", "person", "Researcher", List.of(), List.of("chunk-1")),
            new Entity("bob", "Bob", "person", "Engineer", List.of(), List.of("chunk-1"))
        );
        assertThat(graph.relations()).containsExactly(
            new Relation(
                relationId("alice", "bob"),
                "alice",
                "bob",
                "works_with",
                "valid edge",
                0.8d,
                List.of("chunk-1")
            )
        );
    }

    @Test
    void doesNotMergeAliasEndpointsWhenTheyHaveAnExplicitRelation() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction(
                "chunk-1",
                List.of(
                    new ExtractedEntity("提取申请", "业务", "提取业务", List.of("提取类型")),
                    new ExtractedEntity("提取类型", "类型", "购买拍卖自住住房提取", List.of())
                ),
                List.of(new ExtractedRelation("提取申请", "提取类型", "关联类型", "提取申请属于该提取类型", 1.0d))
            )
        ));

        assertThat(graph.entities()).containsExactly(
            new Entity("提取申请", "提取申请", "业务", "提取业务", List.of(), List.of("chunk-1")),
            new Entity("提取类型", "提取类型", "类型", "购买拍卖自住住房提取", List.of(), List.of("chunk-1"))
        );
        assertThat(graph.relations()).containsExactly(
            new Relation(
                relationId("提取申请", "提取类型"),
                "提取申请",
                "提取类型",
                "关联类型",
                "提取申请属于该提取类型",
                1.0d,
                List.of("chunk-1")
            )
        );
    }

    @Test
    void picksTheMajorityEntityTypeAcrossTheBatch() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction("chunk-1", List.of(new ExtractedEntity("Alice", "organization", "Founder", List.of())), List.of()),
            extraction("chunk-2", List.of(new ExtractedEntity("Alice", "person", "Researcher", List.of())), List.of()),
            extraction("chunk-3", List.of(new ExtractedEntity("Alice", "person", "Scientist", List.of())), List.of())
        ));

        assertThat(graph.entities()).containsExactly(
            new Entity(
                "alice",
                "Alice",
                "person",
                "Founder<SEP>Researcher<SEP>Scientist",
                List.of(),
                List.of("chunk-1", "chunk-2", "chunk-3")
            )
        );
    }

    @Test
    void exposesPerRowEntityTypeCountsInFirstSeenOrder() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction("chunk-1", List.of(new ExtractedEntity("Alice", "organization", "Founder", List.of())), List.of()),
            extraction("chunk-2", List.of(new ExtractedEntity("Alice", "person", "Researcher", List.of())), List.of()),
            extraction("chunk-3", List.of(new ExtractedEntity("Alice", "person", "Scientist", List.of())), List.of())
        ));

        assertThat(graph.entityTypeCounts()).containsOnlyKeys("alice");
        assertThat(graph.entityTypeCounts().get("alice"))
            .containsExactly(entry("organization", 1), entry("person", 2));
    }

    @Test
    void countsTypeVotesFromAliasMergedEntitiesPerRow() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction("chunk-1", List.of(new ExtractedEntity("Robert", "person", "Lead", List.of("Bob"))), List.of()),
            extraction("chunk-2", List.of(new ExtractedEntity("Bobby", "organization", "Agency", List.of())), List.of()),
            extraction("chunk-3", List.of(new ExtractedEntity("Bob", "person", "Engineer", List.of("Bobby"))), List.of())
        ));

        assertThat(graph.entityTypeCounts().get("robert"))
            .containsExactly(entry("person", 2), entry("organization", 1));
    }

    @Test
    void breaksEntityTypeTiesByFirstSeenOrder() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction(
                "chunk-1",
                List.of(
                    new ExtractedEntity("Alice", "person", "Researcher", List.of()),
                    new ExtractedEntity("Bob", "organization", "Employer", List.of())
                ),
                List.of()
            ),
            extraction(
                "chunk-2",
                List.of(
                    new ExtractedEntity("Alice", "organization", "Founder", List.of()),
                    new ExtractedEntity("Bob", "person", "Engineer", List.of())
                ),
                List.of()
            )
        ));

        assertThat(graph.entities()).extracting(Entity::id, Entity::type)
            .containsExactly(
                tuple("alice", "person"),
                tuple("bob", "organization")
            );
    }

    @Test
    void countsTypeVotesFromAliasMergedEntities() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction("chunk-1", List.of(new ExtractedEntity("Robert", "person", "Lead", List.of("Bob"))), List.of()),
            extraction("chunk-2", List.of(new ExtractedEntity("Bobby", "organization", "Agency", List.of())), List.of()),
            extraction("chunk-3", List.of(new ExtractedEntity("Bob", "person", "Engineer", List.of("Bobby"))), List.of())
        ));

        assertThat(graph.entities()).extracting(Entity::id, Entity::type)
            .containsExactly(tuple("robert", "person"));
    }

    @Test
    void accumulatesRelationWeightAcrossContributingChunks() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction(
                "chunk-1",
                List.of(
                    new ExtractedEntity("Alice", "person", "Researcher", List.of()),
                    new ExtractedEntity("Bob", "person", "Engineer", List.of())
                ),
                List.of(new ExtractedRelation("Alice", "Bob", "works_with", "first", 1.0d))
            ),
            extraction(
                "chunk-2",
                List.of(),
                List.of(new ExtractedRelation("Alice", "Bob", "works_with", "second", 1.0d))
            ),
            extraction(
                "chunk-3",
                List.of(),
                List.of(new ExtractedRelation("Alice", "Bob", "works_with", "third", 1.0d))
            )
        ));

        assertThat(graph.relations()).containsExactly(
            new Relation(
                relationId("alice", "bob"),
                "alice",
                "bob",
                "works_with",
                "first<SEP>second<SEP>third",
                3.0d,
                List.of("chunk-1", "chunk-2", "chunk-3")
            )
        );
    }

    @Test
    void accumulatesDedupedFilePathsForRelations() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extractionWithFilePath("chunk-1", "/a.md", new ExtractedRelation("Alice", "Bob", "works_with", "first", 1.0d)),
            extractionWithFilePath("chunk-2", "/b.md", new ExtractedRelation("Alice", "Bob", "works_with", "second", 1.0d)),
            extractionWithFilePath("chunk-3", "/a.md", new ExtractedRelation("Alice", "Bob", "works_with", "third", 1.0d))
        ));

        assertThat(graph.relations()).singleElement()
            .extracting(Relation::filePath)
            .isEqualTo("/a.md<SEP>/b.md");
    }

    private static GraphAssembler.ChunkExtraction extraction(
        String chunkId,
        List<ExtractedEntity> entities,
        List<ExtractedRelation> relations
    ) {
        return new GraphAssembler.ChunkExtraction(chunkId, new ExtractionResult(entities, relations, List.of()));
    }

    private static GraphAssembler.ChunkExtraction extractionWithFilePath(
        String chunkId,
        String filePath,
        ExtractedRelation relation
    ) {
        return new GraphAssembler.ChunkExtraction(
            chunkId,
            new ExtractionResult(List.of(), List.of(relation), List.of()),
            List.of(),
            filePath
        );
    }
}
