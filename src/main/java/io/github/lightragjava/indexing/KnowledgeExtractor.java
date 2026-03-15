package io.github.lightragjava.indexing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightragjava.exception.ExtractionException;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.ChatModel.ChatRequest;
import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.ExtractedEntity;
import io.github.lightragjava.types.ExtractedRelation;
import io.github.lightragjava.types.ExtractionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class KnowledgeExtractor {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
        Extract entities and relations from the provided text.
        Return JSON with this shape:
        {
          "entities": [
            {
              "name": "Entity name",
              "type": "entity type",
              "description": "brief description",
              "aliases": ["alias"]
            }
          ],
          "relations": [
            {
              "sourceEntityName": "source entity",
              "targetEntityName": "target entity",
              "type": "relation type",
              "description": "brief description",
              "weight": 1.0
            }
          ]
        }
        Use empty arrays when nothing is found.
        """;

    private final ChatModel chatModel;

    public KnowledgeExtractor(ChatModel chatModel) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
    }

    public ExtractionResult extract(Chunk chunk) {
        Objects.requireNonNull(chunk, "chunk");

        var response = chatModel.generate(new ChatRequest(SYSTEM_PROMPT, buildUserPrompt(chunk)));
        var root = parseResponse(response);
        var entitiesNode = topLevelArray(root, "entities");
        var relationsNode = topLevelArray(root, "relations");

        return new ExtractionResult(
            parseEntities(entitiesNode),
            parseRelations(relationsNode),
            List.of()
        );
    }

    private static JsonNode parseResponse(String response) {
        try {
            var root = OBJECT_MAPPER.readTree(response);
            if (root == null || !root.isObject()) {
                throw new ExtractionException("Knowledge extraction response must be a JSON object");
            }
            return root;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new ExtractionException("Knowledge extraction response is not valid JSON", exception);
        }
    }

    private static JsonNode topLevelArray(JsonNode root, String fieldName) {
        var field = root.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return OBJECT_MAPPER.createArrayNode();
        }
        if (!field.isArray()) {
            throw new ExtractionException("Knowledge extraction response field '%s' must be an array".formatted(fieldName));
        }
        return field;
    }

    private static List<ExtractedEntity> parseEntities(JsonNode entitiesNode) {
        if (!entitiesNode.isArray()) {
            return List.of();
        }

        var entities = new ArrayList<ExtractedEntity>();
        for (var entityNode : entitiesNode) {
            var name = normalizedText(entityNode.get("name"));
            if (name.isEmpty()) {
                continue;
            }

            try {
                entities.add(new ExtractedEntity(
                    name,
                    normalizedText(entityNode.get("type")),
                    normalizedText(entityNode.get("description")),
                    parseAliases(entityNode.get("aliases"))
                ));
            } catch (IllegalArgumentException ignored) {
                // Skip malformed entity rows while preserving valid rows.
            }
        }
        return List.copyOf(entities);
    }

    private static List<String> parseAliases(JsonNode aliasesNode) {
        if (!aliasesNode.isArray()) {
            return List.of();
        }

        var aliases = new ArrayList<String>();
        for (var aliasNode : aliasesNode) {
            var alias = normalizedText(aliasNode);
            if (!alias.isEmpty()) {
                aliases.add(alias);
            }
        }
        return List.copyOf(aliases);
    }

    private static List<ExtractedRelation> parseRelations(JsonNode relationsNode) {
        if (!relationsNode.isArray()) {
            return List.of();
        }

        var relations = new ArrayList<ExtractedRelation>();
        for (var relationNode : relationsNode) {
            var sourceEntityName = normalizedText(relationNode.get("sourceEntityName"));
            var targetEntityName = normalizedText(relationNode.get("targetEntityName"));
            var type = normalizedText(relationNode.get("type"));

            if (sourceEntityName.isEmpty() || targetEntityName.isEmpty() || type.isEmpty()) {
                continue;
            }

            try {
                relations.add(new ExtractedRelation(
                    sourceEntityName,
                    targetEntityName,
                    type,
                    normalizedText(relationNode.get("description")),
                    parseWeight(relationNode.get("weight"))
                ));
            } catch (IllegalArgumentException ignored) {
                // Skip malformed relation rows while preserving valid rows.
            }
        }
        return List.copyOf(relations);
    }

    private static Double parseWeight(JsonNode weightNode) {
        if (weightNode == null || weightNode.isNull()) {
            return null;
        }
        if (weightNode.isNumber()) {
            return weightNode.doubleValue();
        }

        var weight = weightNode.asText("").strip();
        if (weight.isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(weight);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String normalizedText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText("").strip();
    }

    private static String buildUserPrompt(Chunk chunk) {
        return """
            Chunk ID: %s
            Document ID: %s
            Text:
            %s
            """.formatted(chunk.id(), chunk.documentId(), chunk.text());
    }
}
