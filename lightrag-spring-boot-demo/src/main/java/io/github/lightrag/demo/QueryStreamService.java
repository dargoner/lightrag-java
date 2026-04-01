package io.github.lightrag.demo;

import io.github.lightrag.api.QueryResult;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
final class QueryStreamService {
    private static final long STREAM_TIMEOUT_MILLIS = 30_000L;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final ExecutorService executor;

    QueryStreamService() {
        this(Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                var thread = new Thread(runnable, "query-stream-service-" + THREAD_COUNTER.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        }));
    }

    QueryStreamService(ExecutorService executor) {
        this.executor = executor;
    }

    SseEmitter stream(QueryResult result) {
        var emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        var closed = new AtomicBoolean();
        var task = executor.submit(() -> writeEvents(emitter, result, closed));
        emitter.onCompletion(() -> cancel(task, result, closed));
        emitter.onTimeout(() -> {
            cancel(task, result, closed);
            emitter.complete();
        });
        emitter.onError(error -> cancel(task, result, closed));
        return emitter;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private void writeEvents(SseEmitter emitter, QueryResult result, AtomicBoolean closed) {
        var answerStream = result.answerStream();
        try {
            sendMeta(emitter, result);
            if (result.streaming()) {
                while (!Thread.currentThread().isInterrupted() && answerStream.hasNext()) {
                    emitter.send(SseEmitter.event()
                        .name("chunk")
                        .data(Map.of("text", answerStream.next())));
                }
            } else {
                emitter.send(SseEmitter.event()
                    .name("answer")
                    .data(Map.of("answer", result.answer())));
            }
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            emitter.send(SseEmitter.event()
                .name("complete")
                .data(Map.of("done", true)));
            emitter.complete();
        } catch (Exception exception) {
            if (!Thread.currentThread().isInterrupted()) {
                completeWithErrorEvent(emitter, exception);
            }
        } finally {
            closeResult(result, closed);
        }
    }

    private void sendMeta(SseEmitter emitter, QueryResult result) throws IOException {
        emitter.send(SseEmitter.event()
            .name("meta")
            .data(Map.of(
                "streaming", result.streaming(),
                "contexts", result.contexts(),
                "references", result.references()
            )));
    }

    private void completeWithErrorEvent(SseEmitter emitter, Exception exception) {
        try {
            emitter.send(SseEmitter.event()
                .name("error")
                .data(Map.of("message", errorMessage(exception))));
            emitter.complete();
        } catch (Exception sendFailure) {
            emitter.completeWithError(sendFailure);
        }
    }

    private String errorMessage(Exception exception) {
        return exception.getMessage() == null ? "streaming query failed" : exception.getMessage();
    }

    private void cancel(Future<?> task, QueryResult result, AtomicBoolean closed) {
        task.cancel(true);
        closeResult(result, closed);
    }

    private void closeResult(QueryResult result, AtomicBoolean closed) {
        if (closed.compareAndSet(false, true)) {
            result.close();
        }
    }
}
