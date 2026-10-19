package io.github.lightrag.indexing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.lightrag.exception.ExtractionException;
import io.github.lightrag.api.GraphExtractionExample;
import io.github.lightrag.api.KgExtractionValidator;
import io.github.lightrag.indexing.refinement.RefinedEntityPatch;
import io.github.lightrag.indexing.refinement.RefinedRelationPatch;
import io.github.lightrag.indexing.refinement.RefinedWindowExtraction;
import io.github.lightrag.indexing.refinement.RefinementWindow;
import io.github.lightrag.indexing.refinement.WindowEntityCandidate;
import io.github.lightrag.indexing.refinement.WindowExtractionResponse;
import io.github.lightrag.indexing.refinement.WindowRelationCandidate;
import io.github.lightrag.model.CachedChatModel;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.ChatModel.ChatRequest;
import io.github.lightrag.model.ChatRequestOptions;
import io.github.lightrag.model.HeuristicTokenCounter;
import io.github.lightrag.model.TokenCounter;
import io.github.lightrag.ontology.AttributeValues;
import io.github.lightrag.ontology.BoundAttributes;
import io.github.lightrag.ontology.ExtractionMode;
import io.github.lightrag.ontology.OntologyAttributeBinder;
import io.github.lightrag.ontology.OntologyAttributeSchema;
import io.github.lightrag.ontology.OntologyExtractionOverlay;
import io.github.lightrag.ontology.OntologyPattern;
import io.github.lightrag.ontology.OntologyProperty;
import io.github.lightrag.ontology.UnmatchedLabel;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
import io.github.lightrag.types.ExtractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class KnowledgeExtractor {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Logger log = LoggerFactory.getLogger(KnowledgeExtractor.class);
    public static final int DEFAULT_ENTITY_EXTRACT_MAX_GLEANING = 1;
    public static final int DEFAULT_MAX_EXTRACT_INPUT_TOKENS = 20_480;
    // Per-response output limits for the extraction prompts, matching upstream constants.py:26-27.
    public static final int DEFAULT_MAX_EXTRACTION_RECORDS = 100;
    public static final int DEFAULT_MAX_EXTRACTION_ENTITIES = 40;
    private static final TokenCounter SECTION_CONTEXT_TOKEN_COUNTER = new HeuristicTokenCounter();
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
             - "type": one of these entity types whenever possible: %1$s
             - "description": concise but informative description based only on the input text
             - "aliases": optional list of aliases, abbreviations, or alternate names only when explicitly supported by the text
           - Only include aliases when they are strict synonyms for the same entity, such as abbreviations, acronyms, full-name variants, or alternate spellings.
           - Do not use aliases for categories, subtypes, examples, materials, counterparties, institutions, account variants, process steps, or entities connected by an explicit relationship.
           %3$s
           - Ensure consistent naming across the entire extraction.
           - If the entity name is case-insensitive, normalize it in title case.

        2. Relationship Extraction:
           - Extract every direct, clearly stated, and meaningful relationship between previously extracted entities.
           - Do not stop early or keep only the most central relationships; dense passages may legitimately produce many relationships. Prefer completeness, but never infer a relationship that the text does not state.
           - Treat relationships as undirected unless direction is explicitly stated.
           - Do not output duplicate relationships.
           - If a statement implies an n-ary relationship, decompose it into the most reasonable binary relationships.
           - For each relationship, return:
             - "source_entity": source entity name
             - "target_entity": target entity name
             - "relationship_keywords": one or more high-level relationship keywords, separated by comma and space
             - "relationship_description": concise explanation of the relationship
           - The "relationship_keywords" field must act like high-level relationship keywords, not a database-specific edge label.
%2$s

        3. Output Rules:
           - Return JSON only.
           - Return a single JSON object with this exact top-level shape:
             {
               "entities": [
                 {
                   "name": "Entity name",
                   "type": "Entity type",
                   "description": "Entity description",
                   "aliases": ["Alias if explicitly stated"]%7$s
                 }
               ],
               "relations": [
                 {
                   "source_entity": "Source entity",
                   "target_entity": "Target entity",
                   "relationship_keywords": "keyword1, keyword2",
                   "relationship_description": "Relationship description"%8$s
                 }
               ]
             }
           - The JSON shape above and any examples in these instructions describe the output format only; they are never source text. Do not extract, infer, or copy entities or relations from them.
           - All string values must be properly escaped JSON strings (escape `"` as `\\"`, escape backslashes as `\\\\`, and newlines as `\\n`).
           - Any LaTeX quoted inside a string value must use double-escaped backslashes (write `\\frac` as `\\\\frac` in the JSON).
           - Use empty arrays when nothing is found.
           - Output at most %5$d total records across entities and relations in this response.
           - Output at most %6$d entity objects in this response.
           - Output all entity and relation text in %4$s.
           - Proper nouns should remain in their original language when translation would be ambiguous or unnatural.
           - When the user prompt includes a `---Section Context---` heading path, use it only as background to disambiguate references; never extract entities or relations from the heading text itself, and do not mention the headings unless they also appear in the input text.
           - Write descriptions in the third person.
           - Avoid vague pronouns such as "this article", "this paper", "it", "they", "he", or "she" when the concrete entity can be named explicitly.

        4. Quality Rules:
           - Extract all relationships explicitly supported by the text; prioritize completeness over brevity, but do not invent relationships.
           - Only output relationship objects whose source_entity and target_entity are both included in the entities list of this response.
           - Prefer complete, well-formed JSON over partial or malformed output.
           - Do not include explanation, markdown, or code fences.
        """;
    private static final String CONTINUE_USER_PROMPT = """
        ---Task---
        Based on the last extraction task, identify and extract any missed or incorrectly formatted entities and relationships from the same chunk. Only the fenced `<Input Text>` block is extraction content; everything outside that block is context, not content.

        ---Instructions---
        - Do not repeat entities or relationships that were already extracted correctly.
        - If an entity or relationship was missed, output it now.
        - If an entity or relationship was malformed, incomplete, or inconsistent, output the corrected full JSON item.
        - If no entity or relationship was missed, return {"entities": [], "relations": []} and do not invent increments.
        - Return only incremental JSON using the same schema as before.
        - Output at most %5$d total records and at most %6$d entity rows in this response; a relationship row may reference entities already extracted correctly in the previous response.
        - Keep entity naming consistent with the previous extraction.
        - Preserve the same language and JSON-only output rules.

        ---Data to be Processed---
        Chunk ID: %1$s
        Document ID: %2$s

        %3$s<Input Text>
        ```
        %4$s
        ```

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
    private static final Set<String> RESERVED_ENTITY_ATTRIBUTES =
        Set.of("name", "type", "description", "aliases");
    private static final Set<String> RESERVED_RELATION_ATTRIBUTES =
        Set.of("source_entity", "target_entity", "relationship_keywords", "relationship_description", "weight");
    private static final String ENTITY_ATTRIBUTES_OUTPUT_SHAPE =
        ",\n           \"attributes\": {\"property\": \"value\"}";
    private static final String RELATION_ATTRIBUTES_OUTPUT_SHAPE =
        ",\n           \"attributes\": {\"property\": \"value\"}";
    private static final Comparator<UnmatchedLabel> ATTRIBUTE_DIAGNOSTIC_ORDER = Comparator
        .comparing((UnmatchedLabel diagnostic) ->
            OntologyAttributeBinder.diagnosticSortKey(diagnostic.ownerLabel()))
        .thenComparing((UnmatchedLabel diagnostic) ->
            OntologyAttributeBinder.diagnosticSortKey(diagnostic.rawLabel()))
        .thenComparing((UnmatchedLabel diagnostic) ->
            diagnostic.reason() == null ? "" : diagnostic.reason().name())
        .thenComparing((UnmatchedLabel diagnostic) ->
            diagnostic.kind() == null ? "" : diagnostic.kind().name())
        .thenComparing((UnmatchedLabel diagnostic) ->
            diagnostic.rawLabel() == null ? "" : diagnostic.rawLabel())
        .thenComparing((UnmatchedLabel diagnostic) ->
            diagnostic.chunkId() == null ? "" : diagnostic.chunkId());

    private final ChatModel chatModel;
    private final int entityExtractMaxGleaning;
    private final int maxExtractInputTokens;
    private final int entityExtractMaxRecords;
    private final int entityExtractMaxEntities;
    private final KgExtractionValidator kgExtractionValidator;
    private final boolean sectionContextEnabled;
    private final String language;
    private final List<String> entityTypes;
    private final List<String> relationTypes;
    private final List<GraphExtractionExample> examples;
    private final boolean allowDeterministicAttributionFallback;
    private final OntologyExtractionOverlay ontologyExtractionOverlay;
    private final OntologyAttributeSchema attributeSchema;
    private final OntologyAttributeBinder attributeBinder = new OntologyAttributeBinder();

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
        this(
            chatModel,
            entityExtractMaxGleaning,
            maxExtractInputTokens,
            language,
            entityTypes,
            List.of(),
            List.of(),
            allowDeterministicAttributionFallback
        );
    }

    public KnowledgeExtractor(
        ChatModel chatModel,
        int entityExtractMaxGleaning,
        int maxExtractInputTokens,
        String language,
        List<String> entityTypes,
        List<String> relationTypes,
        List<GraphExtractionExample> examples,
        boolean allowDeterministicAttributionFallback
    ) {
        this(
            chatModel,
            entityExtractMaxGleaning,
            maxExtractInputTokens,
            language,
            entityTypes,
            relationTypes,
            examples,
            allowDeterministicAttributionFallback,
            DEFAULT_MAX_EXTRACTION_RECORDS,
            DEFAULT_MAX_EXTRACTION_ENTITIES
        );
    }

    public KnowledgeExtractor(
        ChatModel chatModel,
        int entityExtractMaxGleaning,
        int maxExtractInputTokens,
        String language,
        List<String> entityTypes,
        List<String> relationTypes,
        List<GraphExtractionExample> examples,
        boolean allowDeterministicAttributionFallback,
        int entityExtractMaxRecords,
        int entityExtractMaxEntities
    ) {
        this(
            chatModel,
            entityExtractMaxGleaning,
            maxExtractInputTokens,
            language,
            entityTypes,
            relationTypes,
            examples,
            allowDeterministicAttributionFallback,
            entityExtractMaxRecords,
            entityExtractMaxEntities,
            null,
            true
        );
    }

    public KnowledgeExtractor(
        ChatModel chatModel,
        int entityExtractMaxGleaning,
        int maxExtractInputTokens,
        String language,
        List<String> entityTypes,
        List<String> relationTypes,
        List<GraphExtractionExample> examples,
        boolean allowDeterministicAttributionFallback,
        int entityExtractMaxRecords,
        int entityExtractMaxEntities,
        KgExtractionValidator kgExtractionValidator,
        boolean sectionContextEnabled
    ) {
        this(
            chatModel,
            entityExtractMaxGleaning,
            maxExtractInputTokens,
            language,
            entityTypes,
            relationTypes,
            examples,
            allowDeterministicAttributionFallback,
            entityExtractMaxRecords,
            entityExtractMaxEntities,
            kgExtractionValidator,
            sectionContextEnabled,
            OntologyExtractionOverlay.empty()
        );
    }

    public KnowledgeExtractor(
        ChatModel chatModel,
        int entityExtractMaxGleaning,
        int maxExtractInputTokens,
        String language,
        List<String> entityTypes,
        List<String> relationTypes,
        List<GraphExtractionExample> examples,
        boolean allowDeterministicAttributionFallback,
        int entityExtractMaxRecords,
        int entityExtractMaxEntities,
        KgExtractionValidator kgExtractionValidator,
        boolean sectionContextEnabled,
        OntologyExtractionOverlay ontologyExtractionOverlay
    ) {
        this(
            chatModel,
            entityExtractMaxGleaning,
            maxExtractInputTokens,
            language,
            entityTypes,
            relationTypes,
            examples,
            allowDeterministicAttributionFallback,
            entityExtractMaxRecords,
            entityExtractMaxEntities,
            kgExtractionValidator,
            sectionContextEnabled,
            ontologyExtractionOverlay,
            OntologyAttributeSchema.empty()
        );
    }

    public KnowledgeExtractor(
        ChatModel chatModel,
        int entityExtractMaxGleaning,
        int maxExtractInputTokens,
        String language,
        List<String> entityTypes,
        List<String> relationTypes,
        List<GraphExtractionExample> examples,
        boolean allowDeterministicAttributionFallback,
        int entityExtractMaxRecords,
        int entityExtractMaxEntities,
        KgExtractionValidator kgExtractionValidator,
        boolean sectionContextEnabled,
        OntologyExtractionOverlay ontologyExtractionOverlay,
        OntologyAttributeSchema attributeSchema
    ) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        if (entityExtractMaxGleaning < 0) {
            throw new IllegalArgumentException("entityExtractMaxGleaning must not be negative");
        }
        if (maxExtractInputTokens <= 0) {
            throw new IllegalArgumentException("maxExtractInputTokens must be positive");
        }
        if (entityExtractMaxRecords < 1) {
            throw new IllegalArgumentException("entityExtractMaxRecords must be positive");
        }
        if (entityExtractMaxEntities < 1) {
            throw new IllegalArgumentException("entityExtractMaxEntities must be positive");
        }
        this.entityExtractMaxGleaning = entityExtractMaxGleaning;
        this.maxExtractInputTokens = maxExtractInputTokens;
        this.entityExtractMaxRecords = entityExtractMaxRecords;
        this.entityExtractMaxEntities = entityExtractMaxEntities;
        this.kgExtractionValidator = kgExtractionValidator;
        this.sectionContextEnabled = sectionContextEnabled;
        this.language = requireNonBlank(language, "language");
        var normalizedEntityTypes = List.copyOf(Objects.requireNonNull(entityTypes, "entityTypes")).stream()
            .map(type -> requireNonBlank(type, "entityTypes entry"))
            .toList();
        if (normalizedEntityTypes.isEmpty()) {
            throw new IllegalArgumentException("entityTypes must not be empty");
        }
        this.entityTypes = normalizedEntityTypes;
        this.relationTypes = relationTypes == null
            ? List.of()
            : List.copyOf(relationTypes).stream()
                .map(type -> requireNonBlank(type, "relationTypes entry"))
                .toList();
        this.examples = examples == null ? List.of() : List.copyOf(examples);
        this.allowDeterministicAttributionFallback = allowDeterministicAttributionFallback;
        this.ontologyExtractionOverlay = ontologyExtractionOverlay == null
            ? OntologyExtractionOverlay.empty()
            : ontologyExtractionOverlay;
        this.attributeSchema = attributeSchema == null || attributeSchema.isEmpty()
            ? OntologyAttributeSchema.empty()
            : attributeSchema;
    }

    public ExtractionResult extract(Chunk chunk) {
        return extractWithCacheIds(chunk).extraction();
    }

    public ExtractionRun extractWithCacheIds(Chunk chunk) {
        Objects.requireNonNull(chunk, "chunk");

        var warnings = new ArrayList<String>();
        var cacheIds = new ArrayList<String>();
        var attributeDiagnostics = new ArrayList<UnmatchedLabel>();
        var userPrompt = buildUserPrompt(chunk);
        var systemPrompt = buildSystemPrompt();
        var request = new ChatRequest(systemPrompt, userPrompt, ChatRequestOptions.JSON_OBJECT);
        cacheIds.add(CachedChatModel.cacheId("extract", chatModel.cacheIdentity(), request));
        var response = chatModel.generate(request);
        var current = sanitizeAliasConflicts(parseOrEmpty(response, chunk, warnings, attributeDiagnostics,
            "primary extraction response was empty or unrecoverable; continuing with an empty extraction"));

        var history = new ArrayList<ChatRequest.ConversationMessage>();
        history.add(new ChatRequest.ConversationMessage("user", userPrompt));
        history.add(new ChatRequest.ConversationMessage("assistant", response));

        for (int attempt = 0; attempt < entityExtractMaxGleaning; attempt++) {
            var continuePrompt = buildContinuePrompt(chunk);
            if (estimateTokenCount(systemPrompt + continuePrompt + conversationText(history)) > maxExtractInputTokens) {
                warnings.add("skipped gleaning because extraction context exceeded maxExtractInputTokens");
                break;
            }
            var gleanRequest = new ChatRequest(systemPrompt, continuePrompt, history, ChatRequestOptions.JSON_OBJECT);
            cacheIds.add(CachedChatModel.cacheId("extract", chatModel.cacheIdentity(), gleanRequest));
            var gleanResponse = chatModel.generate(gleanRequest);
            var gleaned = sanitizeAliasConflicts(parseOrEmpty(gleanResponse, chunk, warnings, attributeDiagnostics,
                "gleaning extraction response was empty or unrecoverable; keeping the accumulated extraction"));
            current = sanitizeAliasConflicts(merge(current, gleaned));
            history.add(new ChatRequest.ConversationMessage("user", continuePrompt));
            history.add(new ChatRequest.ConversationMessage("assistant", gleanResponse));
        }

        if (kgExtractionValidator != null) {
            var validated = kgExtractionValidator.validate(chunk.id(), chunk.text(), current);
            if (validated == null) {
                throw new ExtractionException("kgExtractionValidator must return an ExtractionResult");
            }
            current = validated;
        }
        forwardAttributeDiagnostics(attributeDiagnostics);

        return new ExtractionRun(
            new ExtractionResult(current.entities(), current.relations(), List.copyOf(warnings)),
            List.copyOf(cacheIds)
        );
    }

    public RefinedWindowExtraction extractWindow(RefinementWindow window) {
        Objects.requireNonNull(window, "window");

        var response = chatModel.generate(new ChatRequest(
            buildWindowSystemPrompt(), buildWindowPrompt(window), ChatRequestOptions.JSON_OBJECT));
        var parsed = parseWindowExtractionResponse(response, window);
        var warnings = new ArrayList<String>(parsed.warnings());
        var entityPatches = new ArrayList<RefinedEntityPatch>();
        for (var candidate : parsed.entities()) {
            toEntityPatch(candidate, window, warnings).ifPresent(entityPatches::add);
        }
        var relationPatches = new ArrayList<RefinedRelationPatch>();
        for (var candidate : parsed.relations()) {
            toRelationPatch(candidate, window, warnings, allowDeterministicAttributionFallback).ifPresent(relationPatches::add);
        }
        forwardAttributeDiagnostics(parsed.attributeDiagnostics());
        return new RefinedWindowExtraction(
            List.copyOf(entityPatches),
            List.copyOf(relationPatches),
            List.copyOf(warnings),
            !entityPatches.isEmpty() || !relationPatches.isEmpty()
        );
    }

    private ExtractionResult parseOrEmpty(
        String response,
        Chunk chunk,
        List<String> warnings,
        List<UnmatchedLabel> attributeDiagnostics,
        String unrecoverableMessage
    ) {
        try {
            return parseExtractionResult(response, chunk.id(), warnings, attributeDiagnostics);
        } catch (ExtractionException failure) {
            // Upstream degrades an unrecoverable extraction payload to a warning plus an empty
            // result (operate.py:948-953) instead of failing the chunk; mirror that here so one
            // malformed response cannot abort the whole ingest batch.
            log.warn("Chunk {}: {}", chunk.id(), unrecoverableMessage, failure);
            warnings.add(unrecoverableMessage);
            return new ExtractionResult(List.of(), List.of(), List.of());
        }
    }

    private ExtractionResult parseExtractionResult(
        String response,
        String context,
        List<String> warnings,
        List<UnmatchedLabel> attributeDiagnostics
    ) {
        var root = parseResponse(response);
        // Models quoting LaTeX in descriptions routinely under-escape backslashes ("\frac" is
        // valid JSON meaning form feed + "rac"); restore the zero-risk cases before field
        // sanitization would otherwise delete the control characters and leave a maimed formula.
        // Upstream applies the nested variant to every string leaf of the parsed result
        // (operate.py:960); covers initial extraction and gleaning, which both parse here.
        repairLatexEscapeDamage(root, context);
        return new ExtractionResult(
            parseEntities(tolerantArray(root, "entities", context, warnings), context, attributeDiagnostics),
            parseRelations(tolerantArray(root, "relations", context, warnings), context, attributeDiagnostics),
            List.of()
        );
    }

    private static JsonNode tolerantArray(JsonNode root, String fieldName, String context, List<String> warnings) {
        var field = root.path(fieldName);
        if (field.isArray() || field.isMissingNode()) {
            return field;
        }
        // Upstream warns and treats a mistyped payload field as empty (operate.py:963-968),
        // keeping whatever sibling fields did parse.
        var warning = "knowledge extraction response field '%s' is not an array; treating it as empty".formatted(fieldName);
        log.warn("Chunk {}: {}", context, warning);
        warnings.add(warning);
        return OBJECT_MAPPER.createArrayNode();
    }

    private static void repairLatexEscapeDamage(JsonNode node, String context) {
        if (node instanceof ObjectNode objectNode) {
            var entries = new ArrayList<Map.Entry<String, JsonNode>>();
            objectNode.fields().forEachRemaining(entries::add);
            for (var entry : entries) {
                var childContext = context == null || context.isEmpty()
                    ? entry.getKey()
                    : context + "." + entry.getKey();
                var value = entry.getValue();
                if (value.isTextual()) {
                    objectNode.put(entry.getKey(), LatexEscapeRepair.repair(value.textValue(), childContext));
                } else {
                    repairLatexEscapeDamage(value, childContext);
                }
            }
            return;
        }
        if (node instanceof ArrayNode arrayNode) {
            for (var index = 0; index < arrayNode.size(); index++) {
                var value = arrayNode.get(index);
                if (value.isTextual()) {
                    arrayNode.set(index, TextNode.valueOf(LatexEscapeRepair.repair(value.textValue(), context)));
                } else {
                    repairLatexEscapeDamage(value, context);
                }
            }
        }
    }

    private WindowExtractionResponse parseWindowExtractionResponse(String response, RefinementWindow window) {
        var root = parseResponse(response);
        var anchorChunkId = window.chunks().get(window.anchorChunkIndex()).id();
        var attributeDiagnostics = new ArrayList<UnmatchedLabel>();
        var entities = parseWindowEntities(
            topLevelArray(root, "entities"), anchorChunkId, attributeDiagnostics);
        var relations = parseWindowRelations(
            topLevelArray(root, "relations"), anchorChunkId, attributeDiagnostics);
        return new WindowExtractionResponse(
            entities,
            relations,
            parseWarnings(root.get("warnings")),
            normalizeAttributeDiagnostics(attributeDiagnostics)
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

    private List<ExtractedEntity> parseEntities(
        JsonNode entitiesNode,
        String chunkId,
        List<UnmatchedLabel> attributeDiagnostics
    ) {
        if (!entitiesNode.isArray()) {
            return List.of();
        }

        var entities = new ArrayList<ExtractedEntity>();
        for (var entityNode : entitiesNode) {
            var name = normalizedText(entityNode.get("name"));
            if (name.isEmpty()) {
                continue;
            }

            var type = normalizedText(entityNode.get("type"));
            var bound = bindEntityAttributes(chunkId, type, entityNode.get("attributes"));
            attributeDiagnostics.addAll(bound.diagnostics());
            try {
                entities.add(new ExtractedEntity(
                    name,
                    type,
                    normalizedText(entityNode.get("description")),
                    parseAliases(entityNode.get("aliases")),
                    bound.values()
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

    private List<ExtractedRelation> parseRelations(
        JsonNode relationsNode,
        String chunkId,
        List<UnmatchedLabel> attributeDiagnostics
    ) {
        if (!relationsNode.isArray()) {
            return List.of();
        }

        var relations = new ArrayList<ExtractedRelation>();
        for (var relationNode : relationsNode) {
            var sourceEntityName = normalizedText(relationNode.get("source_entity"));
            var targetEntityName = normalizedText(relationNode.get("target_entity"));
            var keywords = normalizedText(relationNode.get("relationship_keywords"));

            if (sourceEntityName.isEmpty() || targetEntityName.isEmpty() || keywords.isEmpty()) {
                continue;
            }

            var bound = bindRelationAttributes(chunkId, keywords, relationNode.get("attributes"));
            attributeDiagnostics.addAll(bound.diagnostics());
            try {
                relations.add(new ExtractedRelation(
                    sourceEntityName,
                    targetEntityName,
                    keywords,
                    normalizedText(relationNode.get("relationship_description")),
                    // Upstream fixes the extraction-path weight at 1.0 (operate.py:1084): a model-supplied
                    // weight is ignored and the storage merge re-derives growth from the source ids.
                    1.0d,
                    bound.values()
                ));
            } catch (IllegalArgumentException ignored) {
                // Skip malformed relation rows while preserving valid rows.
            }
        }
        return List.copyOf(relations);
    }

    private List<WindowEntityCandidate> parseWindowEntities(
        JsonNode entitiesNode,
        String chunkId,
        List<UnmatchedLabel> attributeDiagnostics
    ) {
        if (!entitiesNode.isArray()) {
            return List.of();
        }

        var entities = new ArrayList<WindowEntityCandidate>();
        for (var entityNode : entitiesNode) {
            var name = normalizedText(entityNode.get("name"));
            if (name.isEmpty()) {
                continue;
            }

            var type = normalizedText(entityNode.get("type"));
            var bound = bindEntityAttributes(chunkId, type, entityNode.get("attributes"));
            attributeDiagnostics.addAll(bound.diagnostics());
            entities.add(new WindowEntityCandidate(
                name,
                type,
                normalizedText(entityNode.get("description")),
                parseAliases(entityNode.get("aliases")),
                parseSupportingChunkIndexes(entityNode.get("supportingChunkIndexes")),
                bound.values()
            ));
        }
        return List.copyOf(entities);
    }

    private List<WindowRelationCandidate> parseWindowRelations(
        JsonNode relationsNode,
        String chunkId,
        List<UnmatchedLabel> attributeDiagnostics
    ) {
        if (!relationsNode.isArray()) {
            return List.of();
        }

        var relations = new ArrayList<WindowRelationCandidate>();
        for (var relationNode : relationsNode) {
            var sourceEntityName = normalizedText(relationNode.get("source_entity"));
            var targetEntityName = normalizedText(relationNode.get("target_entity"));
            var keywords = normalizedText(relationNode.get("relationship_keywords"));

            if (sourceEntityName.isEmpty() || targetEntityName.isEmpty() || keywords.isEmpty()) {
                continue;
            }

            var bound = bindRelationAttributes(chunkId, keywords, relationNode.get("attributes"));
            attributeDiagnostics.addAll(bound.diagnostics());
            relations.add(new WindowRelationCandidate(
                sourceEntityName,
                targetEntityName,
                keywords,
                normalizedText(relationNode.get("relationship_description")),
                1.0d,
                parseSupportingChunkIndexes(relationNode.get("supportingChunkIndexes")),
                bound.values()
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

    private static String normalizedText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText("").strip();
    }

    private BoundAttributes bindEntityAttributes(String chunkId, String type, JsonNode attributesNode) {
        if (attributeSchema.isEmpty()) {
            return new BoundAttributes(Map.of(), List.of());
        }
        return attributeBinder.bindEntity(attributeSchema, chunkId, type, attributesNode);
    }

    private BoundAttributes bindRelationAttributes(String chunkId, String keywords, JsonNode attributesNode) {
        if (attributeSchema.isEmpty()) {
            return new BoundAttributes(Map.of(), List.of());
        }
        return attributeBinder.bindRelation(attributeSchema, chunkId, keywords, attributesNode);
    }

    private void forwardAttributeDiagnostics(List<UnmatchedLabel> diagnostics) {
        var normalized = normalizeAttributeDiagnostics(diagnostics);
        if (normalized.isEmpty()) {
            return;
        }
        if (kgExtractionValidator == null) {
            log.warn(
                "attribute_diagnostics_discarded_without_validator count={}",
                normalized.size()
            );
            return;
        }
        kgExtractionValidator.onAttributeDiagnostics(normalized);
    }

    private static List<UnmatchedLabel> normalizeAttributeDiagnostics(List<UnmatchedLabel> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return List.of();
        }
        var sorted = new ArrayList<UnmatchedLabel>(diagnostics);
        sorted.sort(ATTRIBUTE_DIAGNOSTIC_ORDER);
        var unique = new ArrayList<UnmatchedLabel>(sorted.size());
        String previousKey = null;
        for (var diagnostic : sorted) {
            String key = diagnosticKey(diagnostic);
            if (!key.equals(previousKey)) {
                unique.add(diagnostic);
                previousKey = key;
            }
        }
        return List.copyOf(unique);
    }

    private static String diagnosticKey(UnmatchedLabel diagnostic) {
        return OntologyAttributeBinder.diagnosticSortKey(diagnostic.ownerLabel())
            + '\u0000'
            + OntologyAttributeBinder.diagnosticSortKey(diagnostic.rawLabel())
            + '\u0000'
            + (diagnostic.reason() == null ? "" : diagnostic.reason().name());
    }

    private String buildUserPrompt(Chunk chunk) {
        return """
            ---Task---
            Extract entities and relationships from the input text below. Only the fenced `<Input Text>` block is extraction content; everything outside that block is context, not content.

            ---Data to be Processed---
            Chunk ID: %1$s
            Document ID: %2$s

            %3$s<Input Text>
            ```
            %4$s
            ```

            <Output JSON>
            """.formatted(chunk.id(), chunk.documentId(), sectionContextBlock(chunk), chunk.text());
    }

    private String buildContinuePrompt(Chunk chunk) {
        return CONTINUE_USER_PROMPT.formatted(
            chunk.id(),
            chunk.documentId(),
            sectionContextBlock(chunk),
            chunk.text(),
            entityExtractMaxRecords,
            entityExtractMaxEntities
        );
    }

    private String sectionContextBlock(Chunk chunk) {
        if (!sectionContextEnabled) {
            return "";
        }
        return SectionContextFormatter.block(chunk, SECTION_CONTEXT_TOKEN_COUNTER);
    }

    private String buildSystemPrompt() {
        return SYSTEM_PROMPT_TEMPLATE.formatted(
            String.join(", ", entityTypesForPrompt()),
            renderGraphExtractionGuidance(),
            renderEntityTypeFallbackInstruction(),
            language,
            entityExtractMaxRecords,
            entityExtractMaxEntities,
            hasEntityAttributeDeclarations() ? ENTITY_ATTRIBUTES_OUTPUT_SHAPE : "",
            hasRelationAttributeDeclarations() ? RELATION_ATTRIBUTES_OUTPUT_SHAPE : ""
        );
    }

    private List<String> entityTypesForPrompt() {
        if (ontologyExtractionOverlay.mode() != ExtractionMode.STRICT) {
            return entityTypes;
        }
        return entityTypes.stream()
            .filter(type -> {
                String normalized = type.strip().toLowerCase(java.util.Locale.ROOT);
                return !"other".equals(normalized) && !"unknown".equals(normalized);
            })
            .toList();
    }

    private String renderEntityTypeFallbackInstruction() {
        return ontologyExtractionOverlay.mode() == ExtractionMode.STRICT
            ? "- Do not use a fallback type; only the listed entity types are permitted."
            : "- If none of the provided entity types apply, use \"Other\".";
    }

    private String renderGraphExtractionGuidance() {
        var guidance = new StringBuilder();
        ExtractionMode mode = ontologyExtractionOverlay.mode();
        if (mode == ExtractionMode.STRICT) {
            guidance.append("\n           - STRICT ONTOLOGY MODE: Extract only entity types, relationship keywords, and patterns declared below.");
            guidance.append(" Reject any entity type or relationship that is not explicitly listed in the ontology.");
        } else if (mode == ExtractionMode.PREFER) {
            guidance.append("\n           - PREFER ONTOLOGY MODE: Prefer the entity types, relationship keywords, and patterns declared below.");
            guidance.append(" If the text clearly describes something outside the ontology, you may use \"Other\" for entity types or an unknown relationship keyword.");
        }
        if (!relationTypes.isEmpty()) {
            if (mode == ExtractionMode.STRICT) {
                guidance.append("\n           - Allowed relationship keywords/types are: ")
                    .append(String.join(", ", relationTypes))
                    .append(". Select relationship_keywords from this list and skip relationships that do not fit.");
            } else if (mode == ExtractionMode.PREFER) {
                guidance.append("\n           - Preferred relationship keywords/types are: ")
                    .append(String.join(", ", relationTypes))
                    .append(". Prefer these keywords when they fit, but you may still use an unknown keyword if the text clearly supports it.");
            } else {
                guidance.append("\n           - Common relationship keywords/types (soft guidance): ")
                    .append(String.join(", ", relationTypes))
                    .append(". You may use any relationship keyword clearly supported by the text.");
            }
        }
        if (mode != ExtractionMode.OPEN && !ontologyExtractionOverlay.patterns().isEmpty()) {
            guidance.append("\n\n        ")
                .append(mode == ExtractionMode.STRICT ? "Ontology Relationship Patterns:" : "Preferred Relationship Patterns:");
            for (var entry : ontologyExtractionOverlay.patterns().entrySet()) {
                guidance.append("\n           - ").append(entry.getKey()).append(" may occur between:");
                for (OntologyPattern pattern : entry.getValue()) {
                    guidance.append("\n             - ")
                        .append(pattern.sourceLabel())
                        .append(" -> ")
                        .append(pattern.targetLabel());
                }
            }
            guidance.append("\n           - Patterns are directional guidance; if direction is not explicitly stated in the text, both directions are acceptable.");
        }
        if (hasAttributeDeclarations()) {
            guidance.append(renderAttributeDeclarations());
        } else if (attributeSchema.isEmpty() && !ontologyExtractionOverlay.attributeBlocks().isEmpty()) {
            guidance.append("\n\n        Ontology Attributes:");
            for (var block : ontologyExtractionOverlay.attributeBlocks().values()) {
                for (String line : block.split("\n", -1)) {
                    guidance.append("\n           ").append(line);
                }
            }
        }
        if (!examples.isEmpty()) {
            guidance.append("\n\n        Knowledge-base Graph Examples:");
            for (int index = 0; index < examples.size(); index++) {
                var example = examples.get(index);
                guidance.append("\n           Example ").append(index + 1).append(" Input: ")
                    .append(example.text().replace('\n', ' ').strip());
                guidance.append("\n           Example ").append(index + 1).append(" Nodes:");
                for (var node : example.nodes()) {
                    guidance.append("\n             - ").append(node.name());
                    if (!node.attributes().isEmpty()) {
                        guidance.append(" attributes: ").append(String.join("; ", node.attributes()));
                    }
                }
                guidance.append("\n           Example ").append(index + 1).append(" Relations:");
                for (var relation : example.relations()) {
                    guidance.append("\n             - ")
                        .append(relation.node1())
                        .append(" -> ")
                        .append(relation.node2())
                        .append(" type: ")
                        .append(relation.type());
                }
            }
            guidance.append("\n           Use these examples as extraction hints, but still return the JSON schema below.");
        }
        return guidance.toString();
    }

    private boolean hasAttributeDeclarations() {
        return hasEntityAttributeDeclarations() || hasRelationAttributeDeclarations();
    }

    private boolean hasEntityAttributeDeclarations() {
        for (var properties : attributeSchema.entityTypes().values()) {
            if (!renderableProperties(properties, RESERVED_ENTITY_ATTRIBUTES).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRelationAttributeDeclarations() {
        for (var properties : attributeSchema.relationTypes().values()) {
            if (!renderableProperties(properties, RESERVED_RELATION_ATTRIBUTES).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private String renderAttributeDeclarations() {
        var guidance = new StringBuilder();
        guidance.append("\n\n        Ontology Attributes:");
        guidance.append("\n           - Extract only the attributes declared below; omit any attribute the input text does not state and never invent undeclared attributes.");
        guidance.append("\n           - Emit each declared attribute inside the \"attributes\" object of the corresponding entity or relationship, using the declared value type (LIST as a JSON array, DATE as ISO 8601 yyyy-MM-dd).");
        if (hasEntityAttributeDeclarations()) {
            guidance.append("\n           Entity type attribute declarations (match the entity \"type\" identity):");
            for (var entry : attributeSchema.entityTypes().entrySet()) {
                var properties = renderableProperties(entry.getValue(), RESERVED_ENTITY_ATTRIBUTES);
                if (properties.isEmpty()) {
                    continue;
                }
                guidance.append("\n             - ").append(entry.getKey()).append(':');
                appendPropertyDeclarations(guidance, properties);
            }
        }
        if (hasRelationAttributeDeclarations()) {
            guidance.append("\n           Relation type attribute declarations (match the relationship_keywords identities):");
            for (var entry : attributeSchema.relationTypes().entrySet()) {
                var properties = renderableProperties(entry.getValue(), RESERVED_RELATION_ATTRIBUTES);
                if (properties.isEmpty()) {
                    continue;
                }
                guidance.append("\n             - ").append(entry.getKey()).append(':');
                appendPropertyDeclarations(guidance, properties);
            }
        }
        return guidance.toString();
    }

    private static void appendPropertyDeclarations(
        StringBuilder guidance,
        List<OntologyProperty> properties
    ) {
        for (var property : properties) {
            guidance.append("\n               - ")
                .append(property.identityId())
                .append(" (")
                .append(property.type().name().toLowerCase(Locale.ROOT))
                .append(')');
            if (property.description() != null && !property.description().isEmpty()) {
                guidance.append(": ").append(property.description());
            }
        }
    }

    private static List<OntologyProperty> renderableProperties(
        List<OntologyProperty> properties,
        Set<String> reservedIdentities
    ) {
        var renderable = new ArrayList<OntologyProperty>();
        for (var property : properties) {
            if (!reservedIdentities.contains(property.identityId())) {
                renderable.add(property);
            }
        }
        return List.copyOf(renderable);
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
                    candidate.aliases(),
                    candidate.attributes()
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
                    canonicalKeywords(candidate.keywords()),
                    candidate.description(),
                    candidate.weight(),
                    candidate.attributes()
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
            List.copyOf(aliases),
            AttributeValues.merge(left.attributes(), right.attributes())
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
            mergeKeywords(left.keywords(), right.keywords()),
            longerText(left.description(), right.description()),
            // Rows merged here come from the same chunk (gleaning rounds), so both operands are the
            // fixed 1.0 extraction weight; summing would double-count one chunk's evidence.
            Math.max(left.weight(), right.weight()),
            AttributeValues.merge(left.attributes(), right.attributes())
        );
    }

    private static ExtractionResult sanitizeAliasConflicts(ExtractionResult result) {
        var relationPairs = new LinkedHashSet<String>();
        for (var relation : result.relations()) {
            var source = normalizeRelationEndpoint(relation.sourceEntityName());
            var target = normalizeRelationEndpoint(relation.targetEntityName());
            if (!source.equals(target)) {
                relationPairs.add(relationPairKey(source, target));
            }
        }

        var sanitizedEntities = new ArrayList<ExtractedEntity>(result.entities().size());
        for (var entity : result.entities()) {
            var entityKey = normalizeRelationEndpoint(entity.name());
            var aliases = entity.aliases().stream()
                .filter(alias -> {
                    var aliasKey = normalizeRelationEndpoint(alias);
                    return !entityKey.equals(aliasKey) && !relationPairs.contains(relationPairKey(entityKey, aliasKey));
                })
                .toList();
            sanitizedEntities.add(new ExtractedEntity(
                entity.name(), entity.type(), entity.description(), aliases, entity.attributes()));
        }

        return new ExtractionResult(List.copyOf(sanitizedEntities), result.relations(), result.warnings());
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
            + canonicalKeywords(relation.keywords());
    }

    private static String relationPairKey(String left, String right) {
        return left.compareTo(right) <= 0 ? left + "\u0000" + right : right + "\u0000" + left;
    }

    private static String normalizeRelationEndpoint(String value) {
        return Objects.requireNonNull(value, "value").strip().toLowerCase(Locale.ROOT);
    }

    private static String canonicalKeywords(String value) {
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

    private static String mergeKeywords(String left, String right) {
        var keywords = new java.util.TreeSet<String>();
        addKeywords(keywords, left);
        addKeywords(keywords, right);
        return String.join(", ", keywords);
    }

    private static void addKeywords(java.util.Set<String> keywords, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (var rawKeyword : value.split(",")) {
            var keyword = rawKeyword.strip().replaceAll("[\\s_-]+", "_");
            if (!keyword.isEmpty()) {
                keywords.add(keyword);
            }
        }
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

    public record ExtractionRun(ExtractionResult extraction, List<String> cacheIds) {
        public ExtractionRun {
            extraction = Objects.requireNonNull(extraction, "extraction");
            cacheIds = List.copyOf(Objects.requireNonNull(cacheIds, "cacheIds"));
        }
    }
}
