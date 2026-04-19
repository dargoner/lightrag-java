package io.github.lightrag.indexing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.exception.ExtractionException;
import io.github.lightrag.indexing.refinement.RefinedEntityPatch;
import io.github.lightrag.indexing.refinement.RefinedRelationPatch;
import io.github.lightrag.indexing.refinement.RefinedWindowExtraction;
import io.github.lightrag.indexing.refinement.RefinementWindow;
import io.github.lightrag.indexing.refinement.WindowEntityCandidate;
import io.github.lightrag.indexing.refinement.WindowExtractionResponse;
import io.github.lightrag.indexing.refinement.WindowRelationCandidate;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.ChatModel.ChatRequest;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
import io.github.lightrag.types.ExtractionResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class KnowledgeExtractor {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final int DEFAULT_ENTITY_EXTRACT_MAX_GLEANING = 1;
    public static final int DEFAULT_MAX_EXTRACT_INPUT_TOKENS = 20_480;
    public static final String DEFAULT_LANGUAGE = "English";
    public static final List<String> DEFAULT_ENTITY_TYPES = List.of(
        "Person", "Creature", "Organization", "Location", "Event",
        "Concept", "Method", "Content", "Data", "Artifact", "NaturalObject", "Other"
    );

    private static final String SYSTEM_PROMPT_TEMPLATE = """
        ---Role---
        You are a Knowledge Graph Specialist responsible for extracting entities and relationships from the input text.

        ---Instructions---
        1. Entity Extraction:
           - Identify clearly defined and meaningful entities in the input text.
           - For each entity, return:
             - "name": entity name
             - "type": one of these entity types whenever possible: %s
             - "description": concise but informative description based only on the input text
             - "aliases": optional list of aliases, abbreviations, or alternate names only when explicitly supported by the text
           - If none of the provided entity types apply, use "Other".
           - Ensure consistent naming across the entire extraction.
           - If the entity name is case-insensitive, normalize it in title case.

        2. Relationship Extraction:
           - Identify direct, clearly stated, and meaningful relationships between previously extracted entities.
           - Treat relationships as undirected unless direction is explicitly stated.
           - Do not output duplicate relationships.
           - If a statement implies an n-ary relationship, decompose it into the most reasonable binary relationships.
           - For each relationship, return:
             - "sourceEntityName": source entity name
             - "targetEntityName": target entity name
             - "type": one or more high-level relationship keywords, separated by comma and space
             - "description": concise explanation of the relationship
             - "weight": optional confidence or importance score; omit it if uncertain
           - The "type" field must act like high-level relationship keywords, not a database-specific edge label.

        3. Output Rules:
           - Return JSON only.
           - Return a single JSON object with this exact top-level shape:
             {
               "entities": [
                 {
                   "name": "Entity name",
                   "type": "Entity type",
                   "description": "Entity description",
                   "aliases": ["Alias if explicitly stated"]
                 }
               ],
               "relations": [
                 {
                   "sourceEntityName": "Source entity",
                   "targetEntityName": "Target entity",
                   "type": "keyword1, keyword2",
                   "description": "Relationship description",
                   "weight": 1.0
                 }
               ]
             }
           - Use empty arrays when nothing is found.
           - Output all entity and relation text in %s.
           - Proper nouns should remain in their original language when translation would be ambiguous or unnatural.
           - Write descriptions in the third person.
           - Avoid vague pronouns such as "this article", "this paper", "it", "they", "he", or "she" when the concrete entity can be named explicitly.

        4. Quality Rules:
           - Prioritize the entities and relationships most central to the meaning of the text.
           - Prefer complete, well-formed JSON over partial or malformed output.
           - Do not include explanation, markdown, or code fences.
        """;
    private static final String CONTINUE_USER_PROMPT = """
        ---Task---
        Based on the last extraction task, identify and extract any missed or incorrectly formatted entities and relationships from the same chunk.

        ---Instructions---
        - Do not repeat entities or relationships that were already extracted correctly.
        - If an entity or relationship was missed, output it now.
        - If an entity or relationship was malformed, incomplete, or inconsistent, output the corrected full JSON item.
        - Return only incremental JSON using the same schema as before.
        - Keep entity naming consistent with the previous extraction.
        - Preserve the same language and JSON-only output rules.

        ---Data to be Processed---
        Chunk ID: %s
        Document ID: %s

        <Input Text>
        %s

        <Output JSON>
        """;
    private static final String WINDOW_SYSTEM_PROMPT_SUFFIX = """

        When the input contains multiple chunks, each extracted entity and relation must include
        a supportingChunkIndexes array. The indexes must reference the chunk order in the user prompt.
        Omit any candidate when you cannot attribute it to one or more chunk indexes.
        """;
    private static final String WINDOW_SYSTEM_PROMPT_FALLBACK_SUFFIX = """

        When the input contains multiple chunks, include a supportingChunkIndexes array whenever you can.
        If a candidate is valid but you cannot determine exact chunk indexes, you may return the candidate with an empty supportingChunkIndexes array.
        The indexes must reference the chunk order in the user prompt.
        """;

    private final ChatModel chatModel;
    private final int entityExtractMaxGleaning;
    private final int maxExtractInputTokens;
    private final String language;
    private final List<String> entityTypes;
    private final boolean allowDeterministicAttributionFallback;

    public KnowledgeExtractor(ChatModel chatModel) {
        this(
            chatModel,
            DEFAULT_ENTITY_EXTRACT_MAX_GLEANING,
            DEFAULT_MAX_EXTRACT_INPUT_TOKENS,
            DEFAULT_LANGUAGE,
            DEFAULT_ENTITY_TYPES,
            false
        );
    }

    public KnowledgeExtractor(ChatModel chatModel, int entityExtractMaxGleaning, int maxExtractInputTokens) {
        this(chatModel, entityExtractMaxGleaning, maxExtractInputTokens, DEFAULT_LANGUAGE, DEFAULT_ENTITY_TYPES, false);
    }

    public KnowledgeExtractor(
        ChatModel chatModel,
        int entityExtractMaxGleaning,
        int maxExtractInputTokens,
        String language,
        List<String> entityTypes
    ) {
        this(chatModel, entityExtractMaxGleaning, maxExtractInputTokens, language, entityTypes, false);
    }

    public KnowledgeExtractor(
        ChatModel chatModel,
        int entityExtractMaxGleaning,
        int maxExtractInputTokens,
        String language,
        List<String> entityTypes,
        boolean allowDeterministicAttributionFallback
    ) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        if (entityExtractMaxGleaning < 0) {
            throw new IllegalArgumentException("entityExtractMaxGleaning must not be negative");
        }
        if (maxExtractInputTokens <= 0) {
            throw new IllegalArgumentException("maxExtractInputTokens must be positive");
        }
        this.entityExtractMaxGleaning = entityExtractMaxGleaning;
        this.maxExtractInputTokens = maxExtractInputTokens;
        this.language = requireNonBlank(language, "language");
        var normalizedEntityTypes = List.copyOf(Objects.requireNonNull(entityTypes, "entityTypes")).stream()
            .map(type -> requireNonBlank(type, "entityTypes entry"))
            .toList();
        if (normalizedEntityTypes.isEmpty()) {
            throw new IllegalArgumentException("entityTypes must not be empty");
        }
        this.entityTypes = normalizedEntityTypes;
        this.allowDeterministicAttributionFallback = allowDeterministicAttributionFallback;
    }

    public ExtractionResult extract(Chunk chunk) {
        Objects.requireNonNull(chunk, "chunk");

        var warnings = new ArrayList<String>();
        var userPrompt = buildUserPrompt(chunk);
        var systemPrompt = buildSystemPrompt();
        var response = chatModel.generate(new ChatRequest(systemPrompt, userPrompt));
        var current = parseExtractionResult(response);

        var history = new ArrayList<ChatRequest.ConversationMessage>();
        history.add(new ChatRequest.ConversationMessage("user", userPrompt));
        history.add(new ChatRequest.ConversationMessage("assistant", response));

        for (int attempt = 0; attempt < entityExtractMaxGleaning; attempt++) {
            var continuePrompt = buildContinuePrompt(chunk);
            if (estimateTokenCount(systemPrompt + continuePrompt + conversationText(history)) > maxExtractInputTokens) {
                warnings.add("skipped gleaning because extraction context exceeded maxExtractInputTokens");
                break;
            }
            var gleanResponse = chatModel.generate(new ChatRequest(systemPrompt, continuePrompt, history));
            var gleaned = parseExtractionResult(gleanResponse);
            current = merge(current, gleaned);
            history.add(new ChatRequest.ConversationMessage("user", continuePrompt));
            history.add(new ChatRequest.ConversationMessage("assistant", gleanResponse));
        }

        return new ExtractionResult(current.entities(), current.relations(), List.copyOf(warnings));
    }

    public RefinedWindowExtraction extractWindow(RefinementWindow window) {
        Objects.requireNonNull(window, "window");

        var response = chatModel.generate(new ChatRequest(buildWindowSystemPrompt(), buildWindowPrompt(window)));
        var parsed = parseWindowExtractionResponse(response);
        var warnings = new ArrayList<String>(parsed.warnings());
        var entityPatches = new ArrayList<RefinedEntityPatch>();
        for (var candidate : parsed.entities()) {
            toEntityPatch(candidate, window, warnings).ifPresent(entityPatches::add);
        }
        var relationPatches = new ArrayList<RefinedRelationPatch>();
        for (var candidate : parsed.relations()) {
            toRelationPatch(candidate, window, warnings, allowDeterministicAttributionFallback).ifPresent(relationPatches::add);
        }
        return new RefinedWindowExtraction(
            List.copyOf(entityPatches),
            List.copyOf(relationPatches),
            List.copyOf(warnings),
            !entityPatches.isEmpty() || !relationPatches.isEmpty()
        );
    }

    private static ExtractionResult parseExtractionResult(String response) {
        var root = parseResponse(response);
        return new ExtractionResult(
            parseEntities(topLevelArray(root, "entities")),
            parseRelations(topLevelArray(root, "relations")),
            List.of()
        );
    }

    private static WindowExtractionResponse parseWindowExtractionResponse(String response) {
        var root = parseResponse(response);
        return new WindowExtractionResponse(
            parseWindowEntities(topLevelArray(root, "entities")),
            parseWindowRelations(topLevelArray(root, "relations")),
            parseWarnings(root.get("warnings"))
        );
    }

    private static JsonNode parseResponse(String response) {
        var invalidJson = (JsonProcessingException) null;
        for (var candidate : responseCandidates(response)) {
            try {
                var root = OBJECT_MAPPER.readTree(candidate);
                if (root != null && root.isObject()) {
                    return root;
                }
            } catch (JsonProcessingException exception) {
                if (invalidJson == null) {
                    invalidJson = exception;
                }
            }
        }
        if (invalidJson != null) {
            throw new ExtractionException("Knowledge extraction response is not valid JSON", invalidJson);
        }
        throw new ExtractionException("Knowledge extraction response must be a JSON object");
    }

    private static List<String> responseCandidates(String response) {
        var normalized = Objects.requireNonNull(response, "response").strip();
        if (normalized.isEmpty()) {
            return List.of(normalized);
        }
        var candidates = new LinkedHashSet<String>();
        var strippedFence = stripWholeCodeFence(normalized);
        candidates.add(strippedFence);
        extractFirstCodeFence(normalized).ifPresent(candidates::add);
        extractFirstJsonObject(strippedFence).ifPresent(candidates::add);
        extractFirstCodeFence(normalized)
            .flatMap(KnowledgeExtractor::extractFirstJsonObject)
            .ifPresent(candidates::add);
        return List.copyOf(candidates);
    }

    private static String stripWholeCodeFence(String response) {
        var normalized = response.strip();
        if (!normalized.startsWith("```")) {
            return normalized;
        }
        var firstNewline = normalized.indexOf('\n');
        if (firstNewline < 0) {
            return normalized;
        }
        var body = normalized.substring(firstNewline + 1);
        if (body.endsWith("```")) {
            body = body.substring(0, body.length() - 3);
        }
        return body.strip();
    }

    private static Optional<String> extractFirstCodeFence(String response) {
        var start = response.indexOf("```");
        while (start >= 0) {
            var blockStart = response.indexOf('\n', start);
            if (blockStart < 0) {
                return Optional.empty();
            }
            var end = response.indexOf("```", blockStart + 1);
            if (end < 0) {
                return Optional.empty();
            }
            var candidate = response.substring(blockStart + 1, end).strip();
            if (!candidate.isEmpty()) {
                return Optional.of(candidate);
            }
            start = response.indexOf("```", end + 3);
        }
        return Optional.empty();
    }

    private static Optional<String> extractFirstJsonObject(String response) {
        var candidateStart = response.indexOf('{');
        while (candidateStart >= 0) {
            var candidate = balancedJsonObjectAt(response, candidateStart);
            if (candidate.isPresent() && isJsonObject(candidate.get())) {
                return candidate;
            }
            candidateStart = response.indexOf('{', candidateStart + 1);
        }
        return Optional.empty();
    }

    private static Optional<String> balancedJsonObjectAt(String response, int startIndex) {
        var depth = 0;
        var inString = false;
        var escaping = false;
        for (int index = startIndex; index < response.length(); index++) {
            var current = response.charAt(index);
            if (inString) {
                if (escaping) {
                    escaping = false;
                    continue;
                }
                if (current == '\\') {
                    escaping = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return Optional.of(response.substring(startIndex, index + 1).strip());
                }
                if (depth < 0) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isJsonObject(String candidate) {
        try {
            var root = OBJECT_MAPPER.readTree(candidate);
            return root != null && root.isObject();
        } catch (JsonProcessingException exception) {
            return false;
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
        if (aliasesNode == null || !aliasesNode.isArray()) {
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
                    parseWeightOrConfidence(relationNode)
                ));
            } catch (IllegalArgumentException ignored) {
                // Skip malformed relation rows while preserving valid rows.
            }
        }
        return List.copyOf(relations);
    }

    private static List<WindowEntityCandidate> parseWindowEntities(JsonNode entitiesNode) {
        if (!entitiesNode.isArray()) {
            return List.of();
        }

        var entities = new ArrayList<WindowEntityCandidate>();
        for (var entityNode : entitiesNode) {
            var name = normalizedText(entityNode.get("name"));
            if (name.isEmpty()) {
                continue;
            }

            entities.add(new WindowEntityCandidate(
                name,
                normalizedText(entityNode.get("type")),
                normalizedText(entityNode.get("description")),
                parseAliases(entityNode.get("aliases")),
                parseSupportingChunkIndexes(entityNode.get("supportingChunkIndexes"))
            ));
        }
        return List.copyOf(entities);
    }

    private static List<WindowRelationCandidate> parseWindowRelations(JsonNode relationsNode) {
        if (!relationsNode.isArray()) {
            return List.of();
        }

        var relations = new ArrayList<WindowRelationCandidate>();
        for (var relationNode : relationsNode) {
            var sourceEntityName = normalizedText(relationNode.get("sourceEntityName"));
            var targetEntityName = normalizedText(relationNode.get("targetEntityName"));
            var type = normalizedText(relationNode.get("type"));

            if (sourceEntityName.isEmpty() || targetEntityName.isEmpty() || type.isEmpty()) {
                continue;
            }

            relations.add(new WindowRelationCandidate(
                sourceEntityName,
                targetEntityName,
                type,
                normalizedText(relationNode.get("description")),
                parseWeightOrConfidence(relationNode),
                parseSupportingChunkIndexes(relationNode.get("supportingChunkIndexes"))
            ));
        }
        return List.copyOf(relations);
    }

    private static List<String> parseWarnings(JsonNode warningsNode) {
        if (warningsNode == null || warningsNode.isNull()) {
            return List.of();
        }
        if (!warningsNode.isArray()) {
            throw new ExtractionException("Knowledge extraction response field 'warnings' must be an array");
        }

        var warnings = new ArrayList<String>();
        for (var warningNode : warningsNode) {
            var warning = normalizedText(warningNode);
            if (!warning.isEmpty()) {
                warnings.add(warning);
            }
        }
        return List.copyOf(warnings);
    }

    private static List<Integer> parseSupportingChunkIndexes(JsonNode indexesNode) {
        if (indexesNode == null || indexesNode.isNull()) {
            return List.of();
        }
        if (!indexesNode.isArray()) {
            return List.of();
        }

        var indexes = new ArrayList<Integer>();
        for (var indexNode : indexesNode) {
            if (!indexNode.canConvertToInt()) {
                return List.of();
            }
            indexes.add(indexNode.intValue());
        }
        return List.copyOf(indexes);
    }

    private static Double parseWeightOrConfidence(JsonNode relationNode) {
        var weight = parseNumericValue(relationNode.get("weight"));
        if (weight != null) {
            return clampProbability(weight);
        }
        var confidence = parseNumericValue(relationNode.get("confidence"));
        return confidence == null ? null : clampProbability(confidence);
    }

    private static Double parseNumericValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }

        var weight = node.asText("").strip();
        if (weight.isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(weight);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static double clampProbability(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static String normalizedText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText("").strip();
    }

    private static String buildUserPrompt(Chunk chunk) {
        return """
            ---Task---
            Extract entities and relationships from the input text below.

            ---Data to be Processed---
            Chunk ID: %s
            Document ID: %s

            <Input Text>
            %s

            <Output JSON>
            """.formatted(chunk.id(), chunk.documentId(), chunk.text());
    }

    private static String buildContinuePrompt(Chunk chunk) {
        return CONTINUE_USER_PROMPT.formatted(chunk.id(), chunk.documentId(), chunk.text());
    }

    private String buildSystemPrompt() {
        return SYSTEM_PROMPT_TEMPLATE.formatted(String.join(", ", entityTypes), language);
    }

    private String buildWindowSystemPrompt() {
        return buildSystemPrompt() + (
            allowDeterministicAttributionFallback
                ? WINDOW_SYSTEM_PROMPT_FALLBACK_SUFFIX
                : WINDOW_SYSTEM_PROMPT_SUFFIX
        );
    }

    private static String buildWindowPrompt(RefinementWindow window) {
        var prompt = new StringBuilder()
            .append("Document ID: ").append(window.documentId()).append('\n')
            .append("Scope: ").append(window.scope()).append('\n')
            .append("Anchor Chunk Index: ").append(window.anchorChunkIndex()).append('\n')
            .append("Estimated Token Count: ").append(window.estimatedTokenCount()).append('\n')
            .append("Chunks:\n");
        for (int index = 0; index < window.chunks().size(); index++) {
            var chunk = window.chunks().get(index);
            prompt.append('[').append(index).append("] Chunk ID: ").append(chunk.id()).append('\n')
                .append(chunk.text()).append('\n');
        }
        return prompt.toString().strip();
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

    private static Optional<RefinedEntityPatch> toEntityPatch(
        WindowEntityCandidate candidate,
        RefinementWindow window,
        List<String> warnings
    ) {
        var supportingChunkIds = resolveSupportingChunkIds(candidate.supportingChunkIndexes(), window);
        if (supportingChunkIds.isEmpty()) {
            warnings.add("dropped entity candidate because supportingChunkIndexes were invalid");
            return Optional.empty();
        }
        try {
            return Optional.of(new RefinedEntityPatch(
                new ExtractedEntity(
                    candidate.name(),
                    candidate.type(),
                    candidate.description(),
                    candidate.aliases()
                ),
                supportingChunkIds
            ));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<RefinedRelationPatch> toRelationPatch(
        WindowRelationCandidate candidate,
        RefinementWindow window,
        List<String> warnings,
        boolean allowDeterministicAttributionFallback
    ) {
        var supportingChunkIds = resolveSupportingChunkIds(candidate.supportingChunkIndexes(), window);
        if (supportingChunkIds.isEmpty()) {
            warnings.add("dropped relation candidate because supportingChunkIndexes were invalid");
            if (!allowDeterministicAttributionFallback) {
                return Optional.empty();
            }
        }
        try {
            return Optional.of(new RefinedRelationPatch(
                new ExtractedRelation(
                    candidate.sourceEntityName(),
                    candidate.targetEntityName(),
                    canonicalRelationType(candidate.type()),
                    candidate.description(),
                    candidate.weight()
                ),
                supportingChunkIds
            ));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static List<String> resolveSupportingChunkIds(List<Integer> supportingChunkIndexes, RefinementWindow window) {
        if (supportingChunkIndexes == null || supportingChunkIndexes.isEmpty()) {
            return List.of();
        }
        var chunkIds = new LinkedHashSet<String>();
        for (var index : supportingChunkIndexes) {
            if (index == null || index < 0 || index >= window.chunks().size()) {
                return List.of();
            }
            chunkIds.add(window.chunks().get(index).id());
        }
        return List.copyOf(chunkIds);
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
        var leftSource = normalizeRelationEndpoint(left.sourceEntityName());
        var leftTarget = normalizeRelationEndpoint(left.targetEntityName());
        var canonicalSourceKey = leftSource.compareTo(leftTarget) <= 0 ? leftSource : leftTarget;
        var canonicalTargetKey = leftSource.compareTo(leftTarget) <= 0 ? leftTarget : leftSource;
        var canonicalSource = preferredEndpointForKey(canonicalSourceKey, left, right);
        var canonicalTarget = preferredEndpointForKey(canonicalTargetKey, left, right);

        return new ExtractedRelation(
            canonicalSource,
            canonicalTarget,
            canonicalRelationType(preferredText(left.type(), right.type())),
            longerText(left.description(), right.description()),
            Math.max(left.weight(), right.weight())
        );
    }

    private static String relationKey(ExtractedRelation relation) {
        var left = normalizeRelationEndpoint(relation.sourceEntityName());
        var right = normalizeRelationEndpoint(relation.targetEntityName());
        var first = left.compareTo(right) <= 0 ? left : right;
        var second = left.compareTo(right) <= 0 ? right : left;
        return first
            + "\u0000"
            + second
            + "\u0000"
            + canonicalRelationType(relation.type());
    }

    private static String normalizeRelationEndpoint(String value) {
        return Objects.requireNonNull(value, "value").strip().toLowerCase(Locale.ROOT);
    }

    private static String canonicalRelationType(String value) {
        var normalized = Objects.requireNonNull(value, "value").strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return normalized;
        }
        var keywords = new java.util.TreeSet<String>();
        for (var rawKeyword : normalized.split(",")) {
            var keyword = rawKeyword.strip().replaceAll("[\\s_-]+", "_");
            if (!keyword.isEmpty()) {
                keywords.add(keyword);
            }
        }
        return String.join(", ", keywords);
    }

    private static String preferredEndpoint(String left, String right) {
        var normalizedLeft = left == null ? "" : left.strip();
        var normalizedRight = right == null ? "" : right.strip();
        if (normalizedLeft.isEmpty()) {
            return normalizedRight;
        }
        if (normalizedRight.isEmpty()) {
            return normalizedLeft;
        }
        return normalizedRight.length() > normalizedLeft.length() ? normalizedRight : normalizedLeft;
    }

    private static String preferredEndpointForKey(String normalizedEndpoint, ExtractedRelation left, ExtractedRelation right) {
        var preferred = "";
        preferred = preferredMatchingEndpoint(preferred, left.sourceEntityName(), normalizedEndpoint);
        preferred = preferredMatchingEndpoint(preferred, left.targetEntityName(), normalizedEndpoint);
        preferred = preferredMatchingEndpoint(preferred, right.sourceEntityName(), normalizedEndpoint);
        preferred = preferredMatchingEndpoint(preferred, right.targetEntityName(), normalizedEndpoint);
        return preferred;
    }

    private static String preferredMatchingEndpoint(String current, String candidate, String normalizedEndpoint) {
        if (candidate == null || candidate.isBlank()) {
            return current;
        }
        if (!normalizeRelationEndpoint(candidate).equals(normalizedEndpoint)) {
            return current;
        }
        return preferredEndpoint(current, candidate);
    }

    private static String preferredText(String left, String right) {
        return left == null || left.isBlank() ? right : left;
    }

    private static String longerText(String left, String right) {
        return (right != null && right.strip().length() > (left == null ? 0 : left.strip().length())) ? right : left;
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
