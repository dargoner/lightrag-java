package io.github.lightragjava.demo;

import io.github.lightragjava.api.LightRag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

@RestController
class StreamingQueryController {
    private final LightRag lightRag;
    private final WorkspaceResolver workspaceResolver;
    private final QueryRequestMapper queryRequestMapper;
    private final QueryStreamService queryStreamService;

    StreamingQueryController(
        LightRag lightRag,
        WorkspaceResolver workspaceResolver,
        QueryRequestMapper queryRequestMapper,
        QueryStreamService queryStreamService
    ) {
        this.lightRag = lightRag;
        this.workspaceResolver = workspaceResolver;
        this.queryRequestMapper = queryRequestMapper;
        this.queryStreamService = queryStreamService;
    }

    @PostMapping(path = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@RequestBody QueryRequestMapper.QueryPayload payload, HttpServletRequest request) {
        try {
            var workspaceId = workspaceResolver.resolve(request);
            var queryRequest = queryRequestMapper.toStreamingRequest(payload);
            return queryStreamService.stream(lightRag.query(workspaceId, queryRequest));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }
}
