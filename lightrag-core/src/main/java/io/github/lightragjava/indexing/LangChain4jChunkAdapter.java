package io.github.lightragjava.indexing;

import io.github.lightragjava.types.Chunk;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class LangChain4jChunkAdapter {
    public List<Object> toTextSegments(List<Chunk> chunks) {
        var source = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        return source.stream()
            .map(this::toTextSegment)
            .toList();
    }

    private Object toTextSegment(Chunk chunk) {
        try {
            var metadataClass = Class.forName("dev.langchain4j.data.document.Metadata");
            var metadataFactory = metadataClass.getMethod("from", Map.class);
            var metadata = metadataFactory.invoke(null, chunk.metadata());
            var textSegmentClass = Class.forName("dev.langchain4j.data.segment.TextSegment");
            try {
                Method factory = textSegmentClass.getMethod("from", String.class, metadataClass);
                return factory.invoke(null, chunk.text(), metadata);
            } catch (NoSuchMethodException ignored) {
                Constructor<?> constructor = textSegmentClass.getConstructor(String.class, metadataClass);
                return constructor.newInstance(chunk.text(), metadata);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("LangChain4j classes are not available on the classpath", exception);
        }
    }
}
