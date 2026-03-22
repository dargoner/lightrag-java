package io.github.lightragjava.indexing;

import io.github.lightragjava.types.RawDocumentSource;
import org.apache.tika.Tika;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TikaFallbackParsingProvider implements DocumentParsingProvider {
    private final TextExtractor extractor;

    public TikaFallbackParsingProvider() {
        this(new TikaTextExtractor(new Tika()));
    }

    TikaFallbackParsingProvider(TextExtractor extractor) {
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    @Override
    public ParsedDocument parse(RawDocumentSource source) {
        var rawSource = Objects.requireNonNull(source, "source");
        var text = extractor.extract(rawSource);
        var metadata = new LinkedHashMap<String, String>(rawSource.metadata());
        metadata.put("parse_mode", "tika");
        metadata.put("parse_backend", "tika");
        return new ParsedDocument(
            rawSource.sourceId(),
            rawSource.fileName(),
            text,
            List.of(),
            Map.copyOf(metadata)
        );
    }

    @FunctionalInterface
    interface TextExtractor {
        String extract(RawDocumentSource source);
    }

    private record TikaTextExtractor(Tika tika) implements TextExtractor {
        @Override
        public String extract(RawDocumentSource source) {
            try (var input = new ByteArrayInputStream(source.bytes())) {
                return tika.parseToString(input);
            } catch (IOException exception) {
                throw new UncheckedIOException("failed to parse document with tika: " + source.fileName(), exception);
            } catch (Exception exception) {
                throw new IllegalStateException("failed to parse document with tika: " + source.fileName(), exception);
            }
        }
    }
}
