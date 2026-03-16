package io.github.lightragjava.demo;

import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.api.QueryResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class QueryController {
    private final LightRag lightRag;

    QueryController(LightRag lightRag) {
        this.lightRag = lightRag;
    }

    @PostMapping("/query")
    QueryResponse query(@RequestBody QueryPayload payload) {
        var result = lightRag.query(QueryRequest.builder()
            .query(payload.query())
            .mode(payload.mode() == null ? QueryMode.MIX : payload.mode())
            .topK(payload.topK() == null ? QueryRequest.DEFAULT_TOP_K : payload.topK())
            .chunkTopK(payload.chunkTopK() == null ? QueryRequest.DEFAULT_CHUNK_TOP_K : payload.chunkTopK())
            .build());
        return new QueryResponse(result.answer(), result.contexts(), result.references());
    }

    record QueryPayload(String query, QueryMode mode, Integer topK, Integer chunkTopK) {
    }

    record QueryResponse(String answer, java.util.List<QueryResult.Context> contexts, java.util.List<QueryResult.Reference> references) {
    }
}
