package io.github.lightragjava.demo;

import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.api.QueryResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class QueryController {
    private final LightRag lightRag;
    private final QueryRequestMapper queryRequestMapper;

    QueryController(LightRag lightRag, QueryRequestMapper queryRequestMapper) {
        this.lightRag = lightRag;
        this.queryRequestMapper = queryRequestMapper;
    }

    @PostMapping("/query")
    QueryResponse query(@RequestBody QueryRequestMapper.QueryPayload payload) {
        var result = lightRag.query(queryRequestMapper.toBufferedRequest(payload));
        return new QueryResponse(result.answer(), result.contexts(), result.references());
    }

    record QueryResponse(String answer, java.util.List<QueryResult.Context> contexts, java.util.List<QueryResult.Reference> references) {
    }
}
