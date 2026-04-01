package io.github.lightrag.demo;

import io.github.lightrag.api.LightRag;
import io.github.lightrag.api.QueryResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class QueryController {
    private final LightRag lightRag;
    private final WorkspaceResolver workspaceResolver;
    private final QueryRequestMapper queryRequestMapper;

    QueryController(
        LightRag lightRag,
        WorkspaceResolver workspaceResolver,
        QueryRequestMapper queryRequestMapper
    ) {
        this.lightRag = lightRag;
        this.workspaceResolver = workspaceResolver;
        this.queryRequestMapper = queryRequestMapper;
    }

    @PostMapping("/query")
    QueryResponse query(@RequestBody QueryRequestMapper.QueryPayload payload, HttpServletRequest request) {
        var workspaceId = workspaceResolver.resolve(request);
        var queryRequest = queryRequestMapper.toBufferedRequest(payload);
        var result = lightRag.query(workspaceId, queryRequest);
        return new QueryResponse(result.answer(), result.contexts(), result.references());
    }
    record QueryResponse(String answer, java.util.List<QueryResult.Context> contexts, java.util.List<QueryResult.Reference> references) {
    }
}
