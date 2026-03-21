package io.github.lightragjava.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.api.QueryResult;
import io.github.lightragjava.model.CloseableIterator;
import io.github.lightragjava.spring.boot.LightRagProperties;
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
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StreamingQueryController.class)
@Import({StreamingQueryControllerTest.TestConfig.class, ApiExceptionHandler.class})
class StreamingQueryControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LightRag lightRag;

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

        @Bean
        QueryStreamService queryStreamService() {
            return new QueryStreamService();
        }
    }

    @Test
    void streamsMetaChunkAndCompleteEvents() throws Exception {
        when(lightRag.query(any())).thenReturn(QueryResult.streaming(
            CloseableIterator.of(List.of("Alice ", "works with Bob.")),
            List.of(new QueryResult.Context("chunk-1", "Alice works with Bob", "1", "demo.txt")),
            List.of(new QueryResult.Reference("1", "demo.txt"))
        ));

        var mvcResult = mockMvc.perform(post("/query/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("""
                    {
                      "query": "Who works with Bob?",
                      "mode": "MIX"
                    }
                    """))
            .andExpect(request().asyncStarted())
            .andReturn();

        var events = dispatch(mvcResult);

        var requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(queryRequestMapper).toStreamingRequest(any());
        verify(lightRag).query(requestCaptor.capture());

        assertThat(requestCaptor.getValue().mode()).isEqualTo(QueryMode.MIX);
        assertThat(requestCaptor.getValue().stream()).isTrue();
        assertThat(events).extracting(SseEvent::name)
            .containsExactly("meta", "chunk", "chunk", "complete");
        assertThat(events.get(0).data().get("streaming").asBoolean()).isTrue();
        assertThat(events.get(0).data().get("contexts").get(0).get("sourceId").asText()).isEqualTo("chunk-1");
        assertThat(chunkTexts(events)).containsExactly("Alice ", "works with Bob.");
        assertThat(events.get(3).data().get("done").asBoolean()).isTrue();
    }

    @Test
    void streamsBufferedFallbackAsAnswerEvent() throws Exception {
        when(lightRag.query(any())).thenReturn(new QueryResult(
            "assembled prompt",
            List.of(new QueryResult.Context("chunk-1", "Alice works with Bob", "1", "demo.txt")),
            List.of(new QueryResult.Reference("1", "demo.txt"))
        ));

        var mvcResult = mockMvc.perform(post("/query/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("""
                    {
                      "query": "Who works with Bob?",
                      "onlyNeedPrompt": true
                    }
                    """))
            .andExpect(request().asyncStarted())
            .andReturn();

        var events = dispatch(mvcResult);

        var requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(lightRag).query(requestCaptor.capture());

        assertThat(requestCaptor.getValue().stream()).isTrue();
        assertThat(requestCaptor.getValue().onlyNeedPrompt()).isTrue();
        assertThat(events).extracting(SseEvent::name)
            .containsExactly("meta", "answer", "complete");
        assertThat(events.get(0).data().get("streaming").asBoolean()).isFalse();
        assertThat(events.get(1).data().get("answer").asText()).isEqualTo("assembled prompt");
        assertThat(events.get(2).data().get("done").asBoolean()).isTrue();
    }

    @Test
    void streamsContextFallbackAsAnswerEvent() throws Exception {
        when(lightRag.query(any())).thenReturn(new QueryResult(
            "assembled context",
            List.of(new QueryResult.Context("chunk-1", "Alice works with Bob", "1", "demo.txt")),
            List.of(new QueryResult.Reference("1", "demo.txt"))
        ));

        var mvcResult = mockMvc.perform(post("/query/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("""
                    {
                      "query": "Who works with Bob?",
                      "onlyNeedContext": true
                    }
                    """))
            .andExpect(request().asyncStarted())
            .andReturn();

        var events = dispatch(mvcResult);

        var requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(lightRag).query(requestCaptor.capture());

        assertThat(requestCaptor.getValue().stream()).isTrue();
        assertThat(requestCaptor.getValue().onlyNeedContext()).isTrue();
        assertThat(events).extracting(SseEvent::name)
            .containsExactly("meta", "answer", "complete");
        assertThat(events.get(0).data().get("streaming").asBoolean()).isFalse();
        assertThat(events.get(1).data().get("answer").asText()).isEqualTo("assembled context");
        assertThat(events.get(2).data().get("done").asBoolean()).isTrue();
    }

    @Test
    void streamsBypassModeAsChunksWithEmptyRetrievalMetadata() throws Exception {
        when(lightRag.query(any())).thenReturn(QueryResult.streaming(
            CloseableIterator.of(List.of("Bypass ", "answer")),
            List.of(),
            List.of()
        ));

        var mvcResult = mockMvc.perform(post("/query/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("""
                    {
                      "query": "Talk directly to the model",
                      "mode": "BYPASS",
                      "userPrompt": "Answer in one sentence."
                    }
                    """))
            .andExpect(request().asyncStarted())
            .andReturn();

        var events = dispatch(mvcResult);

        var requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(lightRag).query(requestCaptor.capture());

        assertThat(requestCaptor.getValue().mode()).isEqualTo(QueryMode.BYPASS);
        assertThat(requestCaptor.getValue().stream()).isTrue();
        assertThat(events).extracting(SseEvent::name)
            .containsExactly("meta", "chunk", "chunk", "complete");
        assertThat(events.get(0).data().get("streaming").asBoolean()).isTrue();
        assertThat(events.get(0).data().get("contexts").isEmpty()).isTrue();
        assertThat(events.get(0).data().get("references").isEmpty()).isTrue();
        assertThat(chunkTexts(events)).containsExactly("Bypass ", "answer");
        assertThat(events.get(3).data().get("done").asBoolean()).isTrue();
    }

    @Test
    void emitsErrorEventWhenStreamingIteratorFails() throws Exception {
        var closed = new AtomicBoolean();
        when(lightRag.query(any())).thenReturn(QueryResult.streaming(
            new CloseableIterator<>() {
                private int index;

                @Override
                public boolean hasNext() {
                    return index < 2;
                }

                @Override
                public String next() {
                    if (index++ == 0) {
                        return "partial ";
                    }
                    throw new IllegalStateException("stream broke");
                }

                @Override
                public void close() {
                    closed.set(true);
                }
            },
            List.of(),
            List.of()
        ));

        var mvcResult = mockMvc.perform(post("/query/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("""
                    {
                      "query": "Who works with Bob?"
                    }
                    """))
            .andExpect(request().asyncStarted())
            .andReturn();

        var events = dispatch(mvcResult);

        assertThat(closed).isTrue();
        assertThat(events).extracting(SseEvent::name)
            .containsExactly("meta", "chunk", "error");
        assertThat(chunkTexts(events)).containsExactly("partial ");
        assertThat(events.get(2).data().get("message").asText()).isEqualTo("stream broke");
    }

    @Test
    void rejectsInvalidStreamingPayloadBeforeAsyncStart() throws Exception {
        mockMvc.perform(post("/query/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("""
                    {
                      "query": "   "
                    }
                    """))
            .andExpect(status().isBadRequest());

        verify(lightRag, never()).query(any());
    }

    private List<SseEvent> dispatch(MvcResult mvcResult) throws Exception {
        mvcResult.getAsyncResult(5_000L);
        var response = mockMvc.perform(asyncDispatch(mvcResult))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        assertThat(response.getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        return parseEvents(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private List<SseEvent> parseEvents(String responseBody) throws Exception {
        var events = new ArrayList<SseEvent>();
        for (var block : responseBody.strip().split("\\R\\R")) {
            if (block.isBlank()) {
                continue;
            }
            String name = null;
            String data = null;
            for (var line : block.split("\\R")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data = line.substring("data:".length()).trim();
                }
            }
            events.add(new SseEvent(name, data == null ? objectMapper.nullNode() : objectMapper.readTree(data)));
        }
        return events;
    }

    private List<String> chunkTexts(List<SseEvent> events) {
        return events.stream()
            .filter(event -> "chunk".equals(event.name()))
            .map(event -> event.data().get("text").asText())
            .toList();
    }

    private record SseEvent(String name, JsonNode data) {
    }
}
