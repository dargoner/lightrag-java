package io.github.lightrag.model;

import io.github.lightrag.model.ChatModel.ChatRequest;
import io.github.lightrag.storage.LlmCacheStore;
import io.github.lightrag.storage.memory.InMemoryLlmCacheStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.assertj.core.api.Assertions.assertThat;

class CachedChatModelTest {
    private final LlmCacheStore store = new InMemoryLlmCacheStore(new ReentrantReadWriteLock());
    private final RecordingLlmCacheStore recordingStore = new RecordingLlmCacheStore();

    @Test
    void cachesCompleteResponsesAndReplaysThemWithoutCallingTheDelegateAgain() {
        var delegate = new StubChatModel(new ChatResponse("answer", "stop", null));
        var cached = new CachedChatModel("extract", delegate, store);
        var request = new ChatRequest("system", "user");

        assertThat(cached.generate(request)).isEqualTo("answer");
        assertThat(cached.generate(request)).isEqualTo("answer");

        assertThat(delegate.calls()).isEqualTo(1);
        assertThat(store.contains(
            CachedChatModel.cacheId("extract", delegate.cacheIdentity(), request))).isTrue();
    }

    @Test
    void doesNotCacheTruncatedResponses() {
        var delegate = new StubChatModel(new ChatResponse("partial", "length", new ChatResponse.Usage(12, 3)));
        var cached = new CachedChatModel("extract", delegate, store);
        var request = new ChatRequest("s", "u");

        var response = cached.generateResponse(request);

        assertThat(response.truncated()).isTrue();
        assertThat(response.content()).isEqualTo("partial");
        assertThat(response.usage().completionTokens()).isEqualTo(3);
        assertThat(store.load(
            CachedChatModel.cacheId("extract", delegate.cacheIdentity(), request))).isEmpty();

        cached.generateResponse(request);
        assertThat(delegate.calls()).isEqualTo(2);
    }

    @Test
    void generateResponseReplaysCompleteResponsesFromTheCache() {
        var delegate = new StubChatModel(new ChatResponse("answer", "stop", null));
        var cached = new CachedChatModel("extract", delegate, store);
        var request = new ChatRequest("s", "u");

        cached.generateResponse(request);
        var replayed = cached.generateResponse(request);

        assertThat(replayed.content()).isEqualTo("answer");
        assertThat(replayed.truncated()).isFalse();
        assertThat(delegate.calls()).isEqualTo(1);
    }

    @Test
    void cacheKeyIncludesPolicyVersionAndHashesTheModelIdentity() {
        var identity = "openai-compatible:gpt-4o-mini@https://api.example/v1";
        var key = CachedChatModel.cacheId("query", identity,
            new ChatModel.ChatRequest("system", "user"));
        assertThat(key).startsWith("v2:query:").doesNotContain(identity);
    }

    @Test
    void cacheKeyStaysWithinTheMysqlColumnLimitForLongIdentities() {
        // The identity is hashed so cache_id (VARCHAR(191) on MySQL) never overflows: an oversized key
        // fails the INSERT instead of merely missing the cache (review round 2, should-fix).
        var identity = "openai-compatible:gpt-4o-mini@https://example.internal/"
            + "very/long/deployment/base/path/".repeat(6) + "v1";
        var key = CachedChatModel.cacheId("query", identity, new ChatModel.ChatRequest("system", "user"));

        assertThat(identity.length()).isGreaterThan(191);
        assertThat(key.length()).isLessThan(191);
    }

    @Test
    void modelIdentityChangeInvalidatesCachedAnswers() {
        var delegateA = new RecordingChatModel("answer-a", "openai-compatible:model-a@url");
        var delegateB = new RecordingChatModel("answer-b", "openai-compatible:model-b@url");

        var first = new CachedChatModel("query", delegateA, recordingStore);
        var second = new CachedChatModel("query", delegateB, recordingStore);

        assertThat(first.generate(new ChatModel.ChatRequest("system", "user"))).isEqualTo("answer-a");
        assertThat(second.generate(new ChatModel.ChatRequest("system", "user"))).isEqualTo("answer-b");
        assertThat(delegateA.generateCalls()).isEqualTo(1);
        assertThat(delegateB.generateCalls()).isEqualTo(1);
        assertThat(recordingStore.snapshot()).hasSize(2);
    }

    @Test
    void requestOptionsChangeTheCacheKeyWithoutChangingThePrompt() {
        // temperature/max_tokens/top_p/response_format alter provider behaviour but not the
        // prompt text; hashing the rendered request alone would under-split (review round 2, M6)
        var identity = "openai-compatible:gpt-4o-mini@https://api.example/v1";
        var plain = new ChatModel.ChatRequest("system", "user", List.of(), ChatRequestOptions.NONE);
        var base = CachedChatModel.cacheId("query", identity, plain);

        assertThat(CachedChatModel.cacheId("query", identity,
            new ChatModel.ChatRequest("system", "user", List.of(),
                new ChatRequestOptions(0.2d, null, null, null)))).isNotEqualTo(base);
        assertThat(CachedChatModel.cacheId("query", identity,
            new ChatModel.ChatRequest("system", "user", List.of(),
                new ChatRequestOptions(null, 512, null, null)))).isNotEqualTo(base);
        assertThat(CachedChatModel.cacheId("query", identity,
            new ChatModel.ChatRequest("system", "user", List.of(),
                new ChatRequestOptions(null, null, 0.9d, null)))).isNotEqualTo(base);
        assertThat(CachedChatModel.cacheId("query", identity,
            new ChatModel.ChatRequest("system", "user", List.of(),
                new ChatRequestOptions(null, null, null, "json_object")))).isNotEqualTo(base);
        assertThat(CachedChatModel.cacheId("query", identity,
            new ChatModel.ChatRequest("system", "user", List.of(), ChatRequestOptions.NONE))).isEqualTo(base);
    }

    @Test
    void historyCarryingRequestsBypassTheCache() {
        var delegate = new RecordingChatModel("answer");
        var model = new CachedChatModel("query", delegate, recordingStore);
        var request = new ChatModel.ChatRequest("system", "user",
            List.of(new ChatModel.ChatRequest.ConversationMessage("user", "earlier turn")));
        model.generate(request);
        model.generate(request);
        assertThat(delegate.generateCalls()).isEqualTo(2);   // neither read nor written
        assertThat(recordingStore.snapshot()).isEmpty();
    }

    @Test
    void extractionHistoryIsKeyedInsteadOfBypassed() {
        // Upstream caches history-carrying extraction requests under a key that folds the
        // history in (utils.py:5515-5662); only the answer cache bypasses (operate.py:4683-4690)
        var delegate = new RecordingChatModel("glean");
        var model = new CachedChatModel("extract", delegate, recordingStore);
        var request = new ChatModel.ChatRequest("system", "user",
            List.of(new ChatModel.ChatRequest.ConversationMessage("user", "earlier turn")));

        model.generate(request);
        model.generate(request);

        assertThat(delegate.generateCalls()).isEqualTo(1);
        assertThat(recordingStore.snapshot())
            .containsOnlyKeys(CachedChatModel.cacheId("extract", delegate.cacheIdentity(), request));
    }

    @Test
    void staticCacheIdMatchesTheKeyTheWrappedModelUses() {
        var delegate = new RecordingChatModel("answer", "openai-compatible:gpt-4o-mini@https://api.example/v1");
        var model = new CachedChatModel("extract", delegate, recordingStore);
        var request = new ChatModel.ChatRequest("system", "user");

        model.generate(request);

        assertThat(recordingStore.snapshot())
            .containsOnlyKeys(CachedChatModel.cacheId("extract", delegate.cacheIdentity(), request));
    }

    private static final class StubChatModel implements ChatModel {
        private final List<ChatResponse> responses;
        private final List<ChatRequest> requests = new ArrayList<>();

        private StubChatModel(ChatResponse... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public String generate(ChatRequest request) {
            return generateResponse(request).content();
        }

        @Override
        public ChatResponse generateResponse(ChatRequest request) {
            requests.add(request);
            return responses.get(Math.min(requests.size() - 1, responses.size() - 1));
        }

        int calls() {
            return requests.size();
        }
    }

    private static final class RecordingChatModel implements ChatModel {
        private final String response;
        private final String identity;
        private int generateCalls;

        private RecordingChatModel(String response) {
            this(response, "recording:test");
        }

        private RecordingChatModel(String response, String identity) {
            this.response = response;
            this.identity = identity;
        }

        @Override
        public String generate(ChatRequest request) {
            generateCalls++;
            return response;
        }

        @Override
        public String cacheIdentity() {
            return identity;
        }

        private int generateCalls() {
            return generateCalls;
        }
    }

    private static final class RecordingLlmCacheStore implements LlmCacheStore {
        private final Map<String, CacheRecord> records = new ConcurrentHashMap<>();

        @Override
        public void save(CacheRecord record) {
            records.put(record.id(), record);
        }

        @Override
        public Optional<CacheRecord> load(String cacheId) {
            return Optional.ofNullable(records.get(cacheId));
        }

        @Override
        public boolean contains(String cacheId) {
            return records.containsKey(cacheId);
        }

        @Override
        public void delete(List<String> cacheIds) {
            cacheIds.forEach(records::remove);
        }

        @Override
        public void drop() {
            records.clear();
        }

        private Map<String, CacheRecord> snapshot() {
            return Map.copyOf(records);
        }
    }
}
