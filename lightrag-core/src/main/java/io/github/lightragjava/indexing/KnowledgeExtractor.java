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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class KnowledgeExtractor {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final int DEFAULT_ENTITY_EXTRACT_MAX_GLEANING = 1;
    public static final int DEFAULT_MAX_EXTRACT_INPUT_TOKENS = 20_480;

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
    private static final String CONTINUE_USER_PROMPT = """
        Continue extracting any missing entities or relations from the same chunk.
        Return only incremental JSON using the same schema as before.
        Chunk ID: %s
        Document ID: %s
        Text:
        %s
        """;

    private final ChatModel chatModel;
    private final int entityExtractMaxGleaning;
    private final int maxExtractInputTokens;

    public KnowledgeExtractor(ChatModel chatModel) {
        this(chatModel, DEFAULT_ENTITY_EXTRACT_MAX_GLEANING, DEFAULT_MAX_EXTRACT_INPUT_TOKENS);
    }

    public KnowledgeExtractor(ChatModel chatModel, int entityExtractMaxGleaning, int maxExtractInputTokens) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        if (entityExtractMaxGleaning < 0) {
            throw new IllegalArgumentException("entityExtractMaxGleaning must not be negative");
        }
        if (maxExtractInputTokens <= 0) {
            throw new IllegalArgumentException("maxExtractInputTokens must be positive");
        }
        this.entityExtractMaxGleaning = entityExtractMaxGleaning;
        this.maxExtractInputTokens = maxExtractInputTokens;
    }

    public ExtractionResult extract(Chunk chunk) {
        Objects.requireNonNull(chunk, "chunk");

        var warnings = new ArrayList<String>();
        var userPrompt = buildUserPrompt(chunk);
        var response = chatModel.generate(new ChatRequest(SYSTEM_PROMPT, userPrompt));
        var current = parseExtractionResult(response);

        var history = new ArrayList<ChatRequest.ConversationMessage>();
        history.add(new ChatRequest.ConversationMessage("user", userPrompt));
        history.add(new ChatRequest.ConversationMessage("assistant", response));

        for (int attempt = 0; attempt < entityExtractMaxGleaning; attempt++) {
            var continuePrompt = buildContinuePrompt(chunk);
            if (estimateTokenCount(SYSTEM_PROMPT + continuePrompt + conversationText(history)) > maxExtractInputTokens) {
                warnings.add("skipped gleaning because extraction context exceeded maxExtractInputTokens");
                break;
            }
            var gleanResponse = chatModel.generate(new ChatRequest(SYSTEM_PROMPT, continuePrompt, history));
            var gleaned = parseExtractionResult(gleanResponse);
            current = merge(current, gleaned);
            history.add(new ChatRequest.ConversationMessage("user", continuePrompt));
            history.add(new ChatRequest.ConversationMessage("assistant", gleanResponse));
        }

        return new ExtractionResult(current.entities(), current.relations(), List.copyOf(warnings));
    }

    private static ExtractionResult parseExtractionResult(String response) {
        var root = parseResponse(response);
        return new ExtractionResult(
            parseEntities(topLevelArray(root, "entities")),
            parseRelations(topLevelArray(root, "relations")),
            List.of()
        );
    }

    private static JsonNode parseResponse(String response) {
        try {
            var root = OBJECT_MAPPER.readTree(normalizeResponse(response));
            if (root == null || !root.isObject()) {
                throw new ExtractionException("Knowledge extraction response must be a JSON object");
            }
            return root;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new ExtractionException("Knowledge extraction response is not valid JSON", exception);
        }
    }

    private static String normalizeResponse(String response) {
        var normalized = Objects.requireNonNull(response, "response").strip();
        if (normalized.startsWith("```")) {
            var firstNewline = normalized.indexOf('\n');
            if (firstNewline >= 0) {
                normalized = normalized.substring(firstNewline + 1);
            }
            if (normalized.endsWith("```")) {
                normalized = normalized.substring(0, normalized.length() - 3);
            }
        }
        return normalized.strip();
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

    private static String buildContinuePrompt(Chunk chunk) {
        return CONTINUE_USER_PROMPT.formatted(chunk.id(), chunk.documentId(), chunk.text());
    }

    private static int estimateTokenCount(String value) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            return 0;
        }
        return normalized.split("\\s+").length;
    }

    private static String conversationText(List<ChatRequest.ConversationMessage> history) {
        return history.stream()
            .map(message -> message.role() + ": " + message.content())
            .reduce("", (left, right) -> left + "\n" + right);
    }

    private static ExtractionResult merge(ExtractionResult base, ExtractionResult gleaned) {
        var entities = new LinkedHashMap<String, ExtractedEntity>();
        for (var entity : base.entities()) {
            entities.put(entity.name(), entity);
        }
        for (var entity : gleaned.entities()) {
            entities.merge(entity.name(), entity, KnowledgeExtractor::mergeEntity);
        }

        var relations = new LinkedHashMap<String, ExtractedRelation>();
        for (var relation : base.relations()) {
            relations.put(relationKey(relation), relation);
        }
        for (var relation : gleaned.relations()) {
            relations.merge(relationKey(relation), relation, KnowledgeExtractor::mergeRelation);
        }

        return new ExtractionResult(List.copyOf(entities.values()), List.copyOf(relations.values()), List.of());
    }

    private static ExtractedEntity mergeEntity(ExtractedEntity left, ExtractedEntity right) {
        var aliases = new LinkedHashSet<String>();
        aliases.addAll(left.aliases());
        aliases.addAll(right.aliases());
        return new ExtractedEntity(
            left.name(),
            preferredText(left.type(), right.type()),
            longerText(left.description(), right.description()),
            List.copyOf(aliases)
        );
    }

    private static ExtractedRelation mergeRelation(ExtractedRelation left, ExtractedRelation right) {
        return new ExtractedRelation(
            left.sourceEntityName(),
            left.targetEntityName(),
            left.type(),
            longerText(left.description(), right.description()),
            Math.max(left.weight(), right.weight())
        );
    }

    private static String relationKey(ExtractedRelation relation) {
        return relation.sourceEntityName() + "\u0000" + relation.targetEntityName() + "\u0000" + relation.type();
    }

    private static String preferredText(String left, String right) {
        return left == null || left.isBlank() ? right : left;
    }

    private static String longerText(String left, String right) {
        return (right != null && right.strip().length() > (left == null ? 0 : left.strip().length())) ? right : left;
    }
}
