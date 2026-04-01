package io.github.lightrag.indexing;

import io.github.lightrag.types.Chunk;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jChunkAdapterTest {
    @Test
    void convertsChunksIntoLangChain4jCompatibleTextSegments() throws Exception {
        var adapter = new LangChain4jChunkAdapter();
        var chunks = List.of(new Chunk(
            "doc-1:0",
            "doc-1",
            "Alpha retrieval.",
            16,
            0,
            Map.of(
                "source", "guide.md",
                SmartChunkMetadata.SECTION_PATH, "Guide"
            )
        ));

        var segments = adapter.toTextSegments(chunks);

        assertThat(segments).hasSize(1);
        var segment = segments.get(0);
        assertThat(segment.getClass().getName()).isEqualTo("dev.langchain4j.data.segment.TextSegment");
        assertThat(invoke(segment, "text")).isEqualTo("Alpha retrieval.");
        var metadata = invoke(segment, "metadata");
        @SuppressWarnings("unchecked")
        var values = (Map<String, Object>) invoke(metadata, "asMap");
        assertThat(values)
            .containsEntry("source", "guide.md")
            .containsEntry(SmartChunkMetadata.SECTION_PATH, "Guide");
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }
}
