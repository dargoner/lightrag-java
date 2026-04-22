package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.types.Entity;
import io.github.lightrag.types.Relation;
import io.github.lightrag.types.ScoredEntity;
import io.github.lightrag.types.ScoredRelation;
import io.github.lightrag.types.reasoning.PathRetrievalResult;
import io.github.lightrag.types.reasoning.ReasoningPath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPathScorerTest {
    @Test
    void ranksBetterSupportedPathAboveWeakerAlternative() {
        var scorer = new DefaultPathScorer();
        var atlas = new Entity("atlas", "Atlas", "Component", "", List.of(), List.of("chunk-1"));
        var graphStore = new Entity("graphstore", "GraphStore", "Service", "", List.of(), List.of("chunk-1", "chunk-2"));
        var team = new Entity("team", "KnowledgeGraphTeam", "Team", "", List.of(), List.of("chunk-2"));
        var dependsOn = new Relation("relation:1", atlas.id(), graphStore.id(), "depends_on", "", 1.0d, List.of("chunk-1"));
        var ownedBy = new Relation("relation:2", graphStore.id(), team.id(), "owned_by", "", 1.0d, List.of("chunk-2"));

        var result = new PathRetrievalResult(
            List.of(
                new ScoredEntity(atlas.id(), atlas, 0.95d),
                new ScoredEntity(graphStore.id(), graphStore, 0.70d)
            ),
            List.of(
                new ScoredRelation(dependsOn.id(), dependsOn, 0.88d),
                new ScoredRelation(ownedBy.id(), ownedBy, 0.84d)
            ),
            List.of(
                new ReasoningPath(
                    List.of(atlas.id(), graphStore.id()),
                    List.of(dependsOn.id()),
                    List.of("chunk-1"),
                    1,
                    0.0d
                ),
                new ReasoningPath(
                    List.of(atlas.id(), graphStore.id(), team.id()),
                    List.of(dependsOn.id(), ownedBy.id()),
                    List.of("chunk-1", "chunk-2"),
                    2,
                    0.0d
                )
            )
        );

        var reranked = scorer.rerank(QueryRequest.builder()
            .query("Atlas 通过谁影响知识图谱组？")
            .build(), result);

        assertThat(reranked).hasSize(2);
        assertThat(reranked.get(0).hopCount()).isEqualTo(2);
        assertThat(reranked.get(0).score()).isGreaterThan(reranked.get(1).score());
    }

    @Test
    void prefersHigherWeightRelationWhenOtherSignalsAreSimilar() {
        var scorer = new DefaultPathScorer();
        var atlas = new Entity("atlas", "Atlas", "Component", "", List.of(), List.of("chunk-1"));
        var graphStore = new Entity("graphstore", "GraphStore", "Service", "", List.of(), List.of("chunk-1"));
        var platform = new Entity("platform", "Platform", "System", "", List.of(), List.of("chunk-2"));
        var weak = new Relation("relation:weak", atlas.id(), graphStore.id(), "depends_on", "", 0.2d, List.of("chunk-1"));
        var strong = new Relation("relation:strong", atlas.id(), platform.id(), "depends_on", "", 1.0d, List.of("chunk-2"));

        var reranked = scorer.rerank(QueryRequest.builder()
            .query("Atlas 依赖什么？")
            .build(), new PathRetrievalResult(
            List.of(new ScoredEntity(atlas.id(), atlas, 0.95d)),
            List.of(
                new ScoredRelation(weak.id(), weak, 0.90d),
                new ScoredRelation(strong.id(), strong, 0.90d)
            ),
            List.of(
                new ReasoningPath(List.of(atlas.id(), graphStore.id()), List.of(weak.id()), List.of("chunk-1"), 1, 0.0d),
                new ReasoningPath(List.of(atlas.id(), platform.id()), List.of(strong.id()), List.of("chunk-2"), 1, 0.0d)
            )
        ));

        assertThat(reranked.get(0).relationIds()).containsExactly("relation:strong");
    }

    @Test
    void prefersPathWhoseTerminalEntityMatchesQueryTarget() {
        var scorer = new DefaultPathScorer();
        var atlas = new Entity("atlas", "Atlas", "Component", "", List.of(), List.of("chunk-1"));
        var graphStore = new Entity("graphstore", "GraphStore", "Service", "", List.of(), List.of("chunk-1"));
        var team = new Entity("team", "KnowledgeGraphTeam", "Team", "", List.of(), List.of("chunk-2"));
        var platform = new Entity("platform", "PlatformOps", "Team", "", List.of(), List.of("chunk-3"));
        var dependsOn = new Relation("relation:dependsOn", atlas.id(), graphStore.id(), "depends_on", "", 0.9d, List.of("chunk-1"));
        var ownedBy = new Relation("relation:ownedBy", graphStore.id(), team.id(), "owned_by", "", 0.8d, List.of("chunk-2"));
        var operatedBy = new Relation("relation:operatedBy", graphStore.id(), platform.id(), "operated_by", "", 1.0d, List.of("chunk-3"));

        var reranked = scorer.rerank(QueryRequest.builder()
            .query("Atlas 通过谁关联 KnowledgeGraphTeam？")
            .build(), new PathRetrievalResult(
            List.of(
                new ScoredEntity(atlas.id(), atlas, 0.95d),
                new ScoredEntity(graphStore.id(), graphStore, 0.92d),
                new ScoredEntity(team.id(), team, 0.40d),
                new ScoredEntity(platform.id(), platform, 0.40d)
            ),
            List.of(
                new ScoredRelation(dependsOn.id(), dependsOn, 0.90d),
                new ScoredRelation(ownedBy.id(), ownedBy, 0.85d),
                new ScoredRelation(operatedBy.id(), operatedBy, 0.95d)
            ),
            List.of(
                new ReasoningPath(List.of(atlas.id(), graphStore.id(), team.id()), List.of(dependsOn.id(), ownedBy.id()), List.of("chunk-1", "chunk-2"), 2, 0.0d),
                new ReasoningPath(List.of(atlas.id(), graphStore.id(), platform.id()), List.of(dependsOn.id(), operatedBy.id()), List.of("chunk-1", "chunk-3"), 2, 0.0d)
            )
        ));

        assertThat(reranked.get(0).entityIds()).endsWith(team.id());
    }
}
