package io.github.lightragjava.demo;

import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.api.QueryResult;
import io.github.lightragjava.spring.boot.LightRagProperties;
import io.github.lightragjava.spring.boot.WorkspaceLightRagFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QueryController.class)
@Import({QueryControllerTest.TestConfig.class, WorkspaceResolver.class, ApiExceptionHandler.class})
class QueryControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkspaceLightRagFactory workspaceLightRagFactory;

    private LightRag defaultLightRag;
    private LightRag alphaLightRag;

    @SpyBean
    private QueryRequestMapper queryRequestMapper;

    @TestConfiguration
    static class TestConfig {
        @Bean
        LightRagProperties lightRagProperties() {
            return new LightRagProperties();
        }

        @Bean
        QueryRequestMapper queryRequestMapper(LightRagProperties properties) {
            return new QueryRequestMapper(properties);
        }
    }

    @BeforeEach
    void setUp() {
        defaultLightRag = mock(LightRag.class);
        alphaLightRag = mock(LightRag.class);
        when(workspaceLightRagFactory.get("default")).thenReturn(defaultLightRag);
        when(workspaceLightRagFactory.get("alpha")).thenReturn(alphaLightRag);
    }

    @Test
    void mapsExtendedQueryFieldsToCoreRequestAndResolvedWorkspace() throws Exception {
        when(alphaLightRag.query(any())).thenReturn(new QueryResult(
            "Alice works with Bob.",
            List.of(new QueryResult.Context("chunk-1", "Alice works with Bob", "1", "demo.txt")),
            List.of(new QueryResult.Reference("1", "demo.txt"))
        ));

        mockMvc.perform(post("/query")
                .header("X-Workspace-Id", "alpha")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "query": "  Who works with Bob?  ",
                      "mode": "GLOBAL",
                      "topK": 5,
                      "chunkTopK": 8,
                      "maxEntityTokens": 1200,
                      "maxRelationTokens": 2400,
                      "maxTotalTokens": 3600,
                      "responseType": "Bullet Points",
                      "enableRerank": false,
                      "includeReferences": true,
                      "onlyNeedContext": true,
                      "onlyNeedPrompt": false,
                      "userPrompt": "  Answer briefly.  ",
                      "hlKeywords": ["organization"],
                      "llKeywords": ["Alice"],
                      "conversationHistory": [
                        {"role": " user ", "content": " Earlier question "},
                        {"role": "assistant", "content": " Earlier answer "}
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.answer").value("Alice works with Bob."))
            .andExpect(jsonPath("$.contexts[0].sourceId").value("chunk-1"))
            .andExpect(jsonPath("$.references[0].referenceId").value("1"));

        var payloadCaptor = ArgumentCaptor.forClass(QueryRequestMapper.QueryPayload.class);
        var requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(queryRequestMapper).toBufferedRequest(payloadCaptor.capture());
        verify(workspaceLightRagFactory).get("alpha");
        verify(alphaLightRag).query(requestCaptor.capture());

        var payload = payloadCaptor.getValue();
        assertThat(payload.query()).isEqualTo("  Who works with Bob?  ");
        assertThat(payload.userPrompt()).isEqualTo("  Answer briefly.  ");
        assertThat(payload.includeReferences()).isTrue();
        assertThat(payload.onlyNeedContext()).isTrue();
        assertThat(payload.stream()).isNull();
        assertThat(payload.conversationHistory())
            .extracting(message -> message.role() + ":" + message.content())
            .containsExactly(" user : Earlier question ", "assistant: Earlier answer ");

        var request = requestCaptor.getValue();
        assertThat(request.query()).isEqualTo("Who works with Bob?");
        assertThat(request.mode()).isEqualTo(QueryMode.GLOBAL);
        assertThat(request.topK()).isEqualTo(5);
        assertThat(request.chunkTopK()).isEqualTo(8);
        assertThat(request.maxEntityTokens()).isEqualTo(1200);
        assertThat(request.maxRelationTokens()).isEqualTo(2400);
        assertThat(request.maxTotalTokens()).isEqualTo(3600);
        assertThat(request.responseType()).isEqualTo("Bullet Points");
        assertThat(request.enableRerank()).isFalse();
        assertThat(request.includeReferences()).isTrue();
        assertThat(request.onlyNeedContext()).isTrue();
        assertThat(request.onlyNeedPrompt()).isFalse();
        assertThat(request.stream()).isFalse();
        assertThat(request.userPrompt()).isEqualTo("Answer briefly.");
        assertThat(request.hlKeywords()).containsExactly("organization");
        assertThat(request.llKeywords()).containsExactly("Alice");
        assertThat(request.conversationHistory())
            .extracting(message -> message.role() + ":" + message.content())
            .containsExactly("user:Earlier question", "assistant:Earlier answer");
    }

    @Test
    void fallsBackToDefaultWorkspaceAndAppliesConfiguredDefaultsWhenHeaderMissing() throws Exception {
        when(defaultLightRag.query(any())).thenReturn(new QueryResult(
            "Alice works with Bob.",
            List.of(),
            List.of()
        ));

        mockMvc.perform(post("/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "query": "  Who works with Bob?  "
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.answer").value("Alice works with Bob."));

        var requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(workspaceLightRagFactory).get("default");
        verify(defaultLightRag).query(requestCaptor.capture());

        var request = requestCaptor.getValue();
        assertThat(request.query()).isEqualTo("Who works with Bob?");
        assertThat(request.mode()).isEqualTo(QueryMode.MIX);
        assertThat(request.topK()).isEqualTo(10);
        assertThat(request.chunkTopK()).isEqualTo(10);
        assertThat(request.maxEntityTokens()).isEqualTo(QueryRequest.DEFAULT_MAX_ENTITY_TOKENS);
        assertThat(request.maxRelationTokens()).isEqualTo(QueryRequest.DEFAULT_MAX_RELATION_TOKENS);
        assertThat(request.maxTotalTokens()).isEqualTo(QueryRequest.DEFAULT_MAX_TOTAL_TOKENS);
        assertThat(request.responseType()).isEqualTo("Multiple Paragraphs");
        assertThat(request.enableRerank()).isTrue();
        assertThat(request.includeReferences()).isFalse();
        assertThat(request.onlyNeedContext()).isFalse();
        assertThat(request.onlyNeedPrompt()).isFalse();
        assertThat(request.stream()).isFalse();
        assertThat(request.userPrompt()).isEmpty();
        assertThat(request.hlKeywords()).isEmpty();
        assertThat(request.llKeywords()).isEmpty();
        assertThat(request.conversationHistory()).isEmpty();
    }

    @Test
    void rejectsStreamingRequestsOnBufferedEndpoint() throws Exception {
        mockMvc.perform(post("/query")
                .header("X-Workspace-Id", "alpha")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "query": "Who works with Bob?",
                      "stream": true
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("stream=true is not supported on /query; use buffered requests only"));

        verify(workspaceLightRagFactory, never()).get(anyString());
        verify(alphaLightRag, never()).query(any());
    }

    @Test
    void rejectsInvalidNumericFields() throws Exception {
        mockMvc.perform(post("/query")
                .header("X-Workspace-Id", "alpha")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "query": "Who works with Bob?",
                      "topK": 0
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("topK must be positive"));

        verify(workspaceLightRagFactory, never()).get(anyString());
        verify(alphaLightRag, never()).query(any());
    }

    @Test
    void rejectsBlankConversationMessageFields() throws Exception {
        mockMvc.perform(post("/query")
                .header("X-Workspace-Id", "alpha")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "query": "Who works with Bob?",
                      "conversationHistory": [
                        {"role": " ", "content": "Earlier question"}
                      ]
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("conversationHistory.role must not be blank"));

        verify(workspaceLightRagFactory, never()).get(anyString());
        verify(alphaLightRag, never()).query(any());
    }
}
