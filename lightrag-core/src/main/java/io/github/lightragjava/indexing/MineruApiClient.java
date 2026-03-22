package io.github.lightragjava.indexing;

import io.github.lightragjava.types.RawDocumentSource;

import java.util.Objects;

public final class MineruApiClient implements MineruClient {
    private final Transport transport;

    public MineruApiClient(Transport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public String backend() {
        return "mineru_api";
    }

    @Override
    public ParseResult parse(RawDocumentSource source) {
        return transport.parse(source);
    }

    @FunctionalInterface
    public interface Transport {
        ParseResult parse(RawDocumentSource source);
    }
}
