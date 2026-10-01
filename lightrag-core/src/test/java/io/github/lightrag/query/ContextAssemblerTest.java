package io.github.lightrag.query;

import io.github.lightrag.model.HeuristicTokenCounter;
import io.github.lightrag.model.TokenCounter;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Entity;
import io.github.lightrag.types.QueryContext;
import io.github.lightrag.types.Relation;
import io.github.lightrag.types.ScoredChunk;
import io.github.lightrag.types.ScoredEntity;
import io.github.lightrag.types.ScoredRelation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAssemblerTest {
    private static final TokenCounter TOKEN_COUNTER = new HeuristicTokenCounter();

    @Test
    void assemblesRelationsUsingEndpointPairAndKeywords() {
        var assembler = new ContextAssembler();
        var atlas = new Entity("atlas", "Atlas", "Component", "", List.of(), List.of("chunk-1"));
        var graphStore = new Entity("graphstore", "GraphStore", "Service", "", List.of(), List.of("chunk-1"));
        var relation = new Relation(
            "rel-1",
            atlas.id(),
            graphStore.id(),
            "depends_on, owned_by",
            "Atlas depends on GraphStore.",
            0.88d,
            List.of("chunk-1")
        );
        var chunk = new Chunk("chunk-1", "doc-1", "Atlas 依赖 GraphStore。", 4, 0, Map.of());

        var context = new QueryContext(
            List.of(new ScoredEntity(atlas.id(), atlas, 0.95d)),
            List.of(new ScoredRelation(relation.id(), relation, 0.88d)),
            List.of(new ScoredChunk(chunk.id(), chunk, 0.90d)),
            ""
        );

        var assembled = assembler.assemble(context);

        assertThat(assembled)
            .contains("Relations:")
            .contains("- atlas -> graphstore | depends_on, owned_by | 0.880")
            .doesNotContain("- rel-1 | depends_on, owned_by | 0.880");
    }

    @Test
    void rendersReferenceIdsHeadingsAndReferenceList() {
        var context = contextWith(List.of(
            scoredChunk("c1", "alpha", Map.of("smart_chunker.section_path", "Setup > Install")),
            scoredChunk("c2", "beta", Map.of())
        ));

        var assembled = new ContextAssembler(TOKEN_COUNTER).assemble(context);

        assertThat(assembled)
            .contains("- [1] c1")
            .contains("headings: Setup → Install")
            .contains("Reference Document List:")
            .contains("- [1] ");
    }

    @Test
    void referenceIdsMatchQueryReferencesOrdering() {
        var chunks = List.of(
            scoredChunk("c1", "alpha", Map.of("file_path", "doc-b")),
            scoredChunk("c2", "beta", Map.of("file_path", "doc-a")),
            scoredChunk("c3", "gamma", Map.of("file_path", "doc-a"))
        );

        var assembled = new ContextAssembler(TOKEN_COUNTER).assemble(contextWith(chunks));

        for (var context : QueryReferences.fromChunks(chunks, true).contexts()) {
            assertThat(assembled).contains("[" + context.referenceId() + "] " + context.sourceId());
        }
    }

    @Test
    void approxProjectionOmitsReferenceIdSoStageOneNeverUndercountsTheRenderer() {
        var chunk = scoredChunk("c1", "alpha", Map.of("smart_chunker.section_path", "Setup > Install"));
        var headings = ChunkHeadings.resolve(chunk, TOKEN_COUNTER);
        var renderedLine = new ContextAssembler(TOKEN_COUNTER).assemble(contextWith(List.of(chunk))).lines()
            .filter(line -> line.contains("c1"))
            .findFirst()
            .orElseThrow();

        assertThat(renderedLine).startsWith("- [1] c1");
        assertThat(ContextAssembler.approxChunkProjection(chunk, headings))
            .isEqualTo(renderedLine.replace("- [1] ", "- "));
    }

    private static QueryContext contextWith(List<ScoredChunk> chunks) {
        return new QueryContext(List.of(), List.of(), chunks, "");
    }

    private static ScoredChunk scoredChunk(String id, String text, Map<String, String> metadata) {
        return new ScoredChunk(id, new Chunk(id, "doc-1", text, text.length(), 0, metadata), 0.912d);
    }
}
