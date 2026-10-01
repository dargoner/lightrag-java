package io.github.lightrag.model.openai;

import io.github.lightrag.exception.ExtractionException;
import io.github.lightrag.exception.ModelException;
import io.github.lightrag.exception.ModelTimeoutException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRetrySupportTest {

    @Test
    void retriesTransientStatusesAndGivesUpPermanently() {
        var attempts = new AtomicInteger();
        var slept = new ArrayList<Long>();
        var result = ModelRetrySupport.call(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new ModelException("boom", 503, null, null, null);
            }
            return "ok";
        }, 3, Duration.ofMillis(1), slept::add);

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
        assertThat(slept).containsExactly(1L, 2L);
    }

    @Test
    void stopsAfterTheConfiguredAttempts() {
        var attempts = new AtomicInteger();
        assertThatThrownBy(() -> ModelRetrySupport.call(() -> {
            attempts.incrementAndGet();
            throw new ModelException("down", 500, null, null, null);
        }, 3, Duration.ofMillis(1), millis -> { }))
            .isInstanceOf(ModelException.class)
            .hasMessageContaining("down");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void doesNotRetryRateLimitOrBadRequest() {
        assertThat(ModelRetrySupport.isRetryable(new ModelException("rate", 429, null, null, null)))
            .isFalse();
        assertThat(ModelRetrySupport.isRetryable(new ModelException("bad", 400, null, null, null))).isFalse();
        assertThat(ModelRetrySupport.isRetryable(
            new ModelException("rate", 429, "{\"error\":{\"type\":\"insufficient_quota\"}}", null, null)
        )).isFalse();
        assertThat(ModelRetrySupport.isRetryable(
            new ModelException("rate", 429, "{\"error\":{\"type\":\"budget_exceeded\"}}", null, null)
        )).isFalse();
        assertThat(ModelRetrySupport.isRetryable(new ModelTimeoutException("t", null, null))).isTrue();
    }

    @Test
    void retriesTransientStatusesAndTransientBadRequests() {
        assertThat(ModelRetrySupport.isRetryable(new ModelException("timeout", 408, null, null, null))).isTrue();
        assertThat(ModelRetrySupport.isRetryable(new ModelException("conflict", 409, null, null, null))).isTrue();
        assertThat(ModelRetrySupport.isRetryable(new ModelException("server", 500, null, null, null))).isTrue();
        assertThat(ModelRetrySupport.isRetryable(new ModelException("gateway", 503, null, null, null))).isTrue();
        assertThat(ModelRetrySupport.isRetryable(
            new ModelException("bad", 400, "We could not parse the JSON body of your request", null, null)
        )).isTrue();
    }

    @Test
    void retriesIoAndTimeoutCauses() {
        assertThat(ModelRetrySupport.isRetryable(
            new ModelException("connection", null, null, null, null, new IOException("reset"))
        )).isTrue();
        assertThat(ModelRetrySupport.isRetryable(
            new ModelTimeoutException("t", new SocketTimeoutException("timeout"), "http://localhost")
        )).isTrue();
    }

    @Test
    void neverRetriesClientErrorsOrUnclassifiedFailures() {
        assertThat(ModelRetrySupport.isRetryable(new ModelException("missing", 404, null, null, null))).isFalse();
        assertThat(ModelRetrySupport.isRetryable(new ModelException("missing response body"))).isFalse();
        assertThat(ModelRetrySupport.isRetryable(new IllegalArgumentException("bad temperature"))).isFalse();
        assertThat(ModelRetrySupport.isRetryable(new ExtractionException("invalid json"))).isFalse();
        assertThat(ModelRetrySupport.isRetryable(
            new ModelException("wrapped", new ExtractionException("invalid json"))
        )).isFalse();
    }

    @Test
    void exponentialBackoffDoublesUntilTheCap() {
        var slept = new ArrayList<Long>();
        assertThatThrownBy(() -> ModelRetrySupport.call(() -> {
            throw new ModelException("down", 500, null, null, null);
        }, 4, Duration.ofMillis(400), slept::add)).isInstanceOf(ModelException.class);

        assertThat(slept).containsExactly(400L, 800L, 1_000L);
    }

    @Test
    void singleAttemptConfigurationDisablesRetry() {
        for (var configuredAttempts : List.of(0, 1)) {
            var attempts = new AtomicInteger();
            assertThatThrownBy(() -> ModelRetrySupport.call(() -> {
                attempts.incrementAndGet();
                throw new ModelException("down", 503, null, null, null);
            }, configuredAttempts, Duration.ofMillis(1), millis -> { })).isInstanceOf(ModelException.class);
            assertThat(attempts).hasValue(1);
        }
    }

    @Test
    void interruptedThreadsAreNotRetried() {
        var attempts = new AtomicInteger();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> ModelRetrySupport.call(() -> {
                attempts.incrementAndGet();
                throw new ModelException("down", 503, null, null, null);
            }, 3, Duration.ofMillis(1), millis -> { })).isInstanceOf(ModelException.class);
        } finally {
            Thread.interrupted();
        }
        assertThat(attempts).hasValue(1);
    }
}
