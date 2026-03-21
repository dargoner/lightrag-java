package io.github.lightragjava.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = {DemoApplication.class, DemoApplicationTest.TestConfig.class},
    properties = {
        "lightrag.chat.base-url=http://localhost:11434/v1/",
        "lightrag.chat.model=qwen2.5:7b",
        "lightrag.chat.api-key=dummy",
        "lightrag.embedding.base-url=http://localhost:11434/v1/",
        "lightrag.embedding.model=nomic-embed-text",
        "lightrag.embedding.api-key=dummy",
        "lightrag.storage.type=in-memory",
        "lightrag.demo.async-ingest-enabled=true"
    }
)
@AutoConfigureMockMvc
class ActuatorEndpointsTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointExposesLightragComponent() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components.lightrag.status").value("UP"))
            .andExpect(jsonPath("$.components.lightrag.details.storageType").value("IN_MEMORY"))
            .andExpect(jsonPath("$.components.lightrag.details.asyncIngestEnabled").value(true));
    }

    @Test
    void infoEndpointExposesLightragRuntimeConfig() throws Exception {
        mockMvc.perform(get("/actuator/info"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lightrag.storage.type").value("IN_MEMORY"))
            .andExpect(jsonPath("$.lightrag.demo.asyncIngestEnabled").value(true))
            .andExpect(jsonPath("$.lightrag.query.defaultMode").value("MIX"));
    }
}
