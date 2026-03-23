package io.github.lightragjava.indexing;

import io.github.lightragjava.types.RawDocumentSource;

import java.util.Objects;

public final class MineruSelfHostedClient implements MineruClient {
    private final Transport transport;

    public MineruSelfHostedClient(Transport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public String backend() {
        return "mineru_self_hosted";
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
