package io.github.lightragjava.demo;

import io.github.lightragjava.api.CreateEntityRequest;
import io.github.lightragjava.api.CreateRelationRequest;
import io.github.lightragjava.api.GraphEntity;
import io.github.lightragjava.api.GraphRelation;
import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.api.MergeEntitiesRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GraphController.class)
@Import(ApiExceptionHandler.class)
class GraphControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LightRag lightRag;

    @Test
    void createEntityReturnsGraphEntity() throws Exception {
        var expected = new GraphEntity("entity-id", "Alice", "person", "Researcher", List.of("Al"), List.of("chunk-1"));
        when(lightRag.createEntity(any(CreateEntityRequest.class))).thenReturn(expected);

        mockMvc.perform(post("/graph/entities")
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                      "name": "Alice",
                      "type": "person",
                      "description": "Researcher",
                      "aliases": ["Ali"]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("entity-id"))
            .andExpect(jsonPath("$.name").value("Alice"));
    }

    @Test
    void mergeEntitiesReturnsGraphEntity() throws Exception {
        var merged = new GraphEntity("merged-id", "Group", "group", "Merged", List.of(), List.of());
        when(lightRag.mergeEntities(any(MergeEntitiesRequest.class))).thenReturn(merged);

        mockMvc.perform(post("/graph/entities/merge")
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                      "sourceEntityNames": ["A", "B"],
                      "targetEntityName": "Group"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("merged-id"))
            .andExpect(jsonPath("$.name").value("Group"));
    }

    @Test
    void createRelationReturnsGraphRelation() throws Exception {
        var relation = new GraphRelation("rel-1", "Alice", "Bob", "connects", "Details", 0.5d, List.of());
        when(lightRag.createRelation(any(CreateRelationRequest.class))).thenReturn(relation);

        mockMvc.perform(post("/graph/relations")
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                      "sourceEntityName": "Alice",
                      "targetEntityName": "Bob",
                      "relationType": "connects",
                      "description": "Details",
                      "weight": 0.5
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("rel-1"))
            .andExpect(jsonPath("$.type").value("connects"));
    }

    @Test
    void deleteEntityMissingReturnsNotFound() throws Exception {
        doThrow(new NoSuchElementException("missing"))
            .when(lightRag).deleteByEntity("missing");

        mockMvc.perform(delete("/graph/entities/{entityName}", "missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("missing"));
    }

    @Test
    void deleteRelationBadRequestWhenLightRagThrows() throws Exception {
        doThrow(new IllegalArgumentException("bad relation"))
            .when(lightRag).deleteByRelation("Alice", "Bob");

        mockMvc.perform(delete("/graph/relations")
                .queryParam("sourceEntityName", "Alice")
                .queryParam("targetEntityName", "Bob"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("bad relation"));
    }
}
