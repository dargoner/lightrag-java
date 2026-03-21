package io.github.lightragjava.demo;

import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.api.QueryResult;
import io.github.lightragjava.spring.boot.WorkspaceLightRagFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class QueryController {
    private final WorkspaceLightRagFactory workspaceLightRagFactory;
    private final WorkspaceResolver workspaceResolver;
    private final QueryRequestMapper queryRequestMapper;

    QueryController(
        WorkspaceLightRagFactory workspaceLightRagFactory,
        WorkspaceResolver workspaceResolver,
        QueryRequestMapper queryRequestMapper
    ) {
        this.workspaceLightRagFactory = workspaceLightRagFactory;
        this.workspaceResolver = workspaceResolver;
        this.queryRequestMapper = queryRequestMapper;
    }

    @PostMapping("/query")
    QueryResponse query(@RequestBody QueryRequestMapper.QueryPayload payload, HttpServletRequest request) {
        var queryRequest = queryRequestMapper.toBufferedRequest(payload);
        var result = lightRag(request).query(queryRequest);
        return new QueryResponse(result.answer(), result.contexts(), result.references());
    }

    private LightRag lightRag(HttpServletRequest request) {
        return workspaceLightRagFactory.get(workspaceResolver.resolve(request));
    }
    record QueryResponse(String answer, java.util.List<QueryResult.Context> contexts, java.util.List<QueryResult.Reference> references) {
    }
}
