package dev.langchain4j.data.segment;

import dev.langchain4j.data.document.Metadata;

public final class TextSegment {
    private final String text;
    private final Metadata metadata;

    public TextSegment(String text, Metadata metadata) {
        this.text = text;
        this.metadata = metadata;
    }

    public static TextSegment from(String text, Metadata metadata) {
        return new TextSegment(text, metadata);
    }

    public String text() {
        return text;
    }

    public Metadata metadata() {
        return metadata;
    }
}
