package io.github.lightrag.model.openai;

import io.github.lightrag.exception.ExtractionException;
import io.github.lightrag.exception.ModelException;
import io.github.lightrag.exception.ModelTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Retry policy for provider calls, mirroring the upstream tenacity decorators on
 * {@code openai_complete_if_cache} / {@code openai_embed}: three attempts with exponential
 * backoff. Retries timeouts, connection errors, 408/409, 5xx and the transient
 * "could not parse" 400s; 429s and other 4xx fail fast (upstream retries non-permanent
 * 429s only behind its 4-10 s backoff).
 */
public final class ModelRetrySupport {
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(100);

    private static final Logger log = LoggerFactory.getLogger(ModelRetrySupport.class);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(1);
    private static final Set<Integer> TRANSIENT_STATUS_CODES = Set.of(408, 409);
    private static final String TRANSIENT_BAD_REQUEST_MARKER = "could not parse";

    private ModelRetrySupport() {
    }

    public static <T> T call(Supplier<T> supplier, int maxAttempts, Duration initialBackoff) {
        return call(supplier, maxAttempts, initialBackoff, ModelRetrySupport::sleep);
    }

    static <T> T call(Supplier<T> supplier, int maxAttempts, Duration initialBackoff, Sleeper sleeper) {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(sleeper, "sleeper");
        var attempts = Math.max(1, maxAttempts);
        var attempt = 1;
        while (true) {
            try {
                return supplier.get();
            } catch (RuntimeException failure) {
                if (attempt >= attempts || !isRetryable(failure) || Thread.currentThread().isInterrupted()) {
                    throw failure;
                }
                var backoffMillis = backoffMillis(initialBackoff, attempt);
                log.debug(
                    "Retrying model call after transient failure (attempt {}/{}) in {} ms",
                    attempt + 1,
                    attempts,
                    backoffMillis,
                    failure
                );
                sleeper.sleep(backoffMillis);
                attempt++;
            }
        }
    }

    public static boolean isRetryable(Throwable failure) {
        for (var current = failure; current != null; current = current.getCause()) {
            if (current instanceof IllegalArgumentException || current instanceof ExtractionException) {
                return false;
            }
            if (current instanceof ModelTimeoutException) {
                return true;
            }
            if (current instanceof IOException) {
                return true;
            }
            if (current instanceof ModelException modelException) {
                var retryable = classifyStatus(modelException);
                if (retryable != null) {
                    return retryable;
                }
            }
        }
        return false;
    }

    private static Boolean classifyStatus(ModelException exception) {
        var status = exception.statusCode();
        if (status == null) {
            return null;
        }
        if (TRANSIENT_STATUS_CODES.contains(status) || status >= 500) {
            return true;
        }
        if (status == 400) {
            return matchesTransientBadRequestMarker(exception);
        }
        return false;
    }

    private static boolean matchesTransientBadRequestMarker(ModelException exception) {
        var rendered = exception.getMessage();
        return rendered != null && rendered.toLowerCase(Locale.ROOT).contains(TRANSIENT_BAD_REQUEST_MARKER);
    }

    private static long backoffMillis(Duration initialBackoff, int attempt) {
        var initialMillis = Math.max(1L, initialBackoff.toMillis());
        var shift = Math.min(30, Math.max(0, attempt - 1));
        return Math.min(MAX_BACKOFF.toMillis(), initialMillis << shift);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelException("Interrupted while retrying model call", exception);
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis);
    }
}
