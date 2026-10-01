package io.github.lightrag.model;

import io.github.lightrag.storage.LlmCacheStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class CachedChatModelTest {
    private final InMemoryLlmCacheStore cacheStore = new InMemoryLlmCacheStore();

    @Test
    void cacheKeyIncludesPolicyVersionAndModelIdentity() {
        var key = CachedChatModel.cacheId("query", "openai-compatible:gpt-4o-mini@https://api.example/v1",
            new ChatModel.ChatRequest("system", "user"));
        assertThat(key).startsWith("v2:query:openai-compatible:gpt-4o-mini@https://api.example/v1:");
    }

    @Test
    void modelIdentityChangeInvalidatesCachedAnswers() {
        var delegateA = new RecordingChatModel("answer-a", "openai-compatible:model-a@url");
        var delegateB = new RecordingChatModel("answer-b", "openai-compatible:model-b@url");

        var first = new CachedChatModel("query", delegateA, cacheStore);
        var second = new CachedChatModel("query", delegateB, cacheStore);

        assertThat(first.generate(new ChatModel.ChatRequest("system", "user"))).isEqualTo("answer-a");
        assertThat(second.generate(new ChatModel.ChatRequest("system", "user"))).isEqualTo("answer-b");
        assertThat(delegateA.generateCalls()).isEqualTo(1);
        assertThat(delegateB.generateCalls()).isEqualTo(1);
        assertThat(cacheStore.snapshot()).hasSize(2);
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
        var model = new CachedChatModel("query", delegate, cacheStore);
        var request = new ChatModel.ChatRequest("system", "user",
            List.of(new ChatModel.ChatRequest.ConversationMessage("user", "earlier turn")));
        model.generate(request);
        model.generate(request);
        assertThat(delegate.generateCalls()).isEqualTo(2);   // neither read nor written
        assertThat(cacheStore.snapshot()).isEmpty();
    }

    @Test
    void extractionHistoryIsKeyedInsteadOfBypassed() {
        // Upstream caches history-carrying extraction requests under a key that folds the
        // history in (utils.py:5515-5662); only the answer cache bypasses (operate.py:4683-4690)
        var delegate = new RecordingChatModel("glean");
        var model = new CachedChatModel("extract", delegate, cacheStore);
        var request = new ChatModel.ChatRequest("system", "user",
            List.of(new ChatModel.ChatRequest.ConversationMessage("user", "earlier turn")));

        model.generate(request);
        model.generate(request);

        assertThat(delegate.generateCalls()).isEqualTo(1);
        assertThat(cacheStore.snapshot())
            .containsOnlyKeys(CachedChatModel.cacheId("extract", delegate.cacheIdentity(), request));
    }

    @Test
    void staticCacheIdMatchesTheKeyTheWrappedModelUses() {
        var delegate = new RecordingChatModel("answer", "openai-compatible:gpt-4o-mini@https://api.example/v1");
        var model = new CachedChatModel("extract", delegate, cacheStore);
        var request = new ChatModel.ChatRequest("system", "user");

        model.generate(request);

        assertThat(cacheStore.snapshot())
            .containsOnlyKeys(CachedChatModel.cacheId("extract", delegate.cacheIdentity(), request));
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

    private static final class InMemoryLlmCacheStore implements LlmCacheStore {
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
