package io.github.lightrag.ontology.discovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.ChatModel.ChatRequest;
import io.github.lightrag.model.ChatRequestOptions;
import io.github.lightrag.ontology.Ontology;
import io.github.lightrag.ontology.OntologyEntityType;
import io.github.lightrag.ontology.OntologyGovernance;
import io.github.lightrag.ontology.OntologyJsonCodec;
import io.github.lightrag.ontology.OntologyRelationType;
import io.github.lightrag.ontology.OntologyStructureValidator;
import io.github.lightrag.types.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Discovers a draft ontology from a corpus sample.
 *
 * <p>The orchestration is deliberately simple and non-persistent: sample chunks,
 * ask the LLM to propose entity types, relation types, properties, and patterns
 * for each chunk, merge the proposals with {@link Ontology#merge(Ontology)}, and
 * return the draft. The draft must be reviewed before it is adopted.
 */
public final class OntologyDiscovery {

    private static final Logger log = LoggerFactory.getLogger(OntologyDiscovery.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
        ---Role---
        You are an Ontology Engineer. Propose a lightweight ontology that describes the entity types and relationship types present in the input text.

        ---Instructions---
        1. Entity Types:
           - Identify the most important entity types in the text.
           - For each entity type, return:
             - "label": the type label, concise and specific
             - "description": what the type means in this domain
             - "properties": optional list of attributes commonly attached to this entity type

        2. Relation Types:
           - Identify direct, meaningful relationship types between the entity types.
           - For each relation type, return:
             - "label": the relation type label
             - "description": what the relation means
             - "patterns": list of (source_entity_type_label, target_entity_type_label) pairs where this relation may occur
             - "properties": optional list of attributes commonly attached to this relation

        3. Property Rules:
           - Each property has:
             - "name": property name
             - "type": one of string, integer, float, boolean, date, list
             - "description": optional explanation

        4. Output Rules:
           - Return JSON only.
           - Use this exact top-level shape:
             {
               "entityTypes": [
                 {
                   "label": "EntityType",
                   "description": "...",
                   "properties": [
                     {"name": "...", "type": "string", "description": "..."}
                   ]
                 }
               ],
               "relationTypes": [
                 {
                   "label": "...",
                   "description": "...",
                   "patterns": [
                     {"sourceLabel": "SourceType", "targetLabel": "TargetType"}
                   ],
                   "properties": [
                     {"name": "...", "type": "string", "description": "..."}
                   ]
                 }
               ]
             }
           - Do not include explanation, markdown, or code fences.
           - Do not invent types not supported by the text.
           - Keep the ontology small and focused: at most %1$d entity types, %2$d relation types, and %3$d properties per type.
        """;

    private static final String USER_PROMPT_TEMPLATE = """
        ---Task---
        Propose an ontology for the following text.

        ---Input Text---
        %1$s

        ---Output JSON---
        """;

    /** Returns a draft ontology using the default stratified-by-document sampling strategy. */
    public DiscoveryResult execute(DiscoveryRequest request) {
        return execute(request, DiscoverySampleStrategy.stratifiedByDocument(request.options().seed()));
    }

    /**
     * Returns a draft ontology using the supplied sampling strategy.
     *
     * @param request the discovery request
     * @param strategy the chunk sampling strategy
     * @return the merged draft ontology plus failures, conflicts, and fingerprint
     */
    public DiscoveryResult execute(DiscoveryRequest request, DiscoverySampleStrategy strategy) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(strategy, "strategy");

        List<Chunk> sample = strategy.select(request.chunks(), request.options().sampleSize());
        if (sample.isEmpty()) {
            return DiscoveryResult.empty();
        }

        String systemPrompt = SYSTEM_PROMPT.formatted(
            request.options().maxEntitiesPerChunk(),
            request.options().maxRelationsPerChunk(),
            request.options().maxPropertiesPerType()
        );

        Ontology merged = null;
        int parsedCount = 0;
        List<String> failures = new ArrayList<>();
        Set<String> conflicts = new LinkedHashSet<>();
        Ontology baseOntology = request.baseOntology();
        for (Chunk chunk : sample) {
            checkCancellation();
            ProposalResult proposal = proposeWithRetry(systemPrompt, chunk, request);
            if (proposal.ontology().isPresent()) {
                Ontology incremental = incrementalDraft(proposal.ontology().get(), baseOntology, conflicts);
                if (!incremental.entityTypes().isEmpty() || !incremental.relationTypes().isEmpty()) {
                    merged = merged == null ? incremental : merged.merge(incremental);
                }
                parsedCount++;
            } else {
                failures.add(proposal.failure());
            }
        }
        checkCancellation();

        if (merged == null) {
            if (parsedCount == 0) {
                throw new IllegalStateException(
                    "Ontology discovery produced no parseable proposals from " + sample.size()
                        + " chunks. Failures: " + failures
                );
            }
            merged = new Ontology(List.of(), List.of(), null, Map.of());
        }

        if (!failures.isEmpty()) {
            log.warn("Ontology discovery had {} parseable and {} failed proposals", parsedCount, failures.size());
        }

        if (!conflicts.isEmpty()) {
            log.warn("Ontology discovery detected {} conflicts with baseline", conflicts.size());
        }

        log.info(
            "Ontology discovery completed: sampleChunks={}, parseableProposals={}, entityTypes={}, relationTypes={}",
            sample.size(),
            parsedCount,
            merged.entityTypes().size(),
            merged.relationTypes().size()
        );
        String fingerprint = sampleFingerprint(request.options().seed(), sample, strategy.strategyId());
        return new DiscoveryResult(merged, failures, List.copyOf(conflicts), fingerprint);
    }

    private static Ontology incrementalDraft(Ontology proposal, Ontology baseOntology, Set<String> conflicts) {
        if (baseOntology == null
            || (baseOntology.entityTypes().isEmpty() && baseOntology.relationTypes().isEmpty())) {
            return proposal;
        }

        Map<String, OntologyEntityType> baseEntities = indexByIdentity(
            baseOntology.entityTypes(),
            OntologyEntityType::identityId
        );
        Map<String, OntologyRelationType> baseRelations = indexByIdentity(
            baseOntology.relationTypes(),
            OntologyRelationType::identityId
        );

        List<OntologyEntityType> newEntities = new ArrayList<>();
        for (OntologyEntityType entity : proposal.entityTypes()) {
            OntologyEntityType baseEntity = baseEntities.get(entity.identityId());
            if (baseEntity == null) {
                newEntities.add(entity);
                continue;
            }
            if (!isSubsetEntity(entity, baseEntity)) {
                collectConflicts(entity, baseEntity, "entity", conflicts);
            }
        }

        List<OntologyRelationType> newRelations = new ArrayList<>();
        for (OntologyRelationType relation : proposal.relationTypes()) {
            OntologyRelationType baseRelation = baseRelations.get(relation.identityId());
            if (baseRelation == null) {
                newRelations.add(relation);
                continue;
            }
            if (!isSubsetRelation(relation, baseRelation)) {
                collectConflicts(relation, baseRelation, "relation", conflicts);
            }
        }

        return new Ontology(newEntities, newRelations, null, Map.of());
    }

    private static boolean isSubsetEntity(OntologyEntityType candidate, OntologyEntityType base) {
        return OntologyGovernance.isSubset(
            new Ontology(List.of(candidate), List.of(), null, Map.of()),
            new Ontology(List.of(base), List.of(), null, Map.of())
        );
    }

    private static boolean isSubsetRelation(OntologyRelationType candidate, OntologyRelationType base) {
        return OntologyGovernance.isSubset(
            new Ontology(List.of(), List.of(candidate), null, Map.of()),
            new Ontology(List.of(), List.of(base), null, Map.of())
        );
    }

    private static void collectConflicts(
        OntologyEntityType candidate,
        OntologyEntityType base,
        String kind,
        Set<String> conflicts
    ) {
        List<String> contradictions = OntologyGovernance.detectContradictions(
            new Ontology(List.of(candidate), List.of(), null, Map.of()),
            new Ontology(List.of(base), List.of(), null, Map.of())
        );
        if (!contradictions.isEmpty()) {
            conflicts.addAll(contradictions);
        } else {
            conflicts.add(capitalize(kind) + " '" + candidate.identityId() + "' adds properties beyond the baseline");
        }
    }

    private static void collectConflicts(
        OntologyRelationType candidate,
        OntologyRelationType base,
        String kind,
        Set<String> conflicts
    ) {
        List<String> contradictions = OntologyGovernance.detectContradictions(
            new Ontology(List.of(), List.of(candidate), null, Map.of()),
            new Ontology(List.of(), List.of(base), null, Map.of())
        );
        if (!contradictions.isEmpty()) {
            conflicts.addAll(contradictions);
        } else {
            conflicts.add(capitalize(kind) + " '" + candidate.identityId() + "' adds properties or patterns beyond the baseline");
        }
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static <T> Map<String, T> indexByIdentity(List<T> items, Function<T, String> identityExtractor) {
        Map<String, T> map = new LinkedHashMap<>(items.size());
        for (T item : items) {
            map.put(identityExtractor.apply(item), item);
        }
        return map;
    }

    private static String sampleFingerprint(long seed, List<Chunk> sample, String strategyTag) {
        var canonical = new StringBuilder();
        canonical.append("v=1\n");
        canonical.append("strategy=").append(strategyTag).append('\n');
        canonical.append("seed=").append(seed).append('\n');
        for (Chunk chunk : sample) {
            canonical.append(chunk.id()).append('\n');
        }
        byte[] hash = sha256(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(input);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    private ProposalResult proposeWithRetry(String systemPrompt, Chunk chunk, DiscoveryRequest request) {
        int maxRetries = request.options().maxRetries();
        long retryDelayMillis = request.options().retryDelayMillis();
        String userPrompt = USER_PROMPT_TEMPLATE.formatted(chunk.text());
        List<ChatModel.ChatRequest.ConversationMessage> history = new ArrayList<>();
        ProposalResult lastResult = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            checkCancellation();
            lastResult = propose(systemPrompt, userPrompt, history, chunk, request.chatModel());
            if (lastResult.ontology().isPresent()) {
                return lastResult;
            }
            history.add(new ChatModel.ChatRequest.ConversationMessage("user", userPrompt));
            history.add(new ChatModel.ChatRequest.ConversationMessage("assistant", lastResult.rawResponse()));
            String reason = lastResult.failure();
            if (reason == null || reason.isEmpty()) {
                reason = "unparseable or invalid response";
            }
            userPrompt = "Your previous response could not be parsed as the required ontology JSON ("
                + reason
                + "). Please correct it and return only valid JSON matching the schema.";
            if (attempt < maxRetries && retryDelayMillis > 0) {
                try {
                    Thread.sleep(retryDelayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Ontology discovery interrupted");
                }
            }
        }
        return lastResult;
    }

    private static void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Ontology discovery cancelled");
        }
    }

    private ProposalResult propose(
        String systemPrompt,
        String userPrompt,
        List<ChatModel.ChatRequest.ConversationMessage> history,
        Chunk chunk,
        ChatModel chatModel
    ) {
        try {
            String response = chatModel.generate(
                new ChatRequest(systemPrompt, userPrompt, history, ChatRequestOptions.JSON_OBJECT)
            );
            Optional<Ontology> proposal = parseProposal(response);
            return proposal
                .map(ontology -> new ProposalResult(Optional.of(ontology), null, response))
                .orElseGet(() -> new ProposalResult(Optional.empty(), chunk.id() + ": unparseable proposal", response));
        } catch (RuntimeException exception) {
            if (exception instanceof java.util.concurrent.CancellationException) {
                throw exception;
            }
            log.warn("Chunk {}: ontology proposal failed", chunk.id(), exception);
            return new ProposalResult(Optional.empty(), chunk.id() + ": " + exception.getMessage(), "");
        }
    }

    private record ProposalResult(Optional<Ontology> ontology, String failure, String rawResponse) {
        ProposalResult {
            ontology = ontology == null ? Optional.empty() : ontology;
            rawResponse = rawResponse == null ? "" : rawResponse;
        }
    }

    private Optional<Ontology> parseProposal(String response) {
        String json = extractJsonObject(response);
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            ObjectNode document = OBJECT_MAPPER.createObjectNode();
            document.put("schemaVersion", OntologyJsonCodec.SCHEMA_VERSION);
            document.put("kind", OntologyJsonCodec.KIND);
            document.set("entityTypes", root.path("entityTypes"));
            document.set("relationTypes", root.path("relationTypes"));
            document.putNull("version");
            document.set("metadata", OBJECT_MAPPER.createObjectNode());
            Ontology ontology = OntologyJsonCodec.readTree(document);
            OntologyStructureValidator.validate(ontology);
            if (ontology.entityTypes().isEmpty() && ontology.relationTypes().isEmpty()) {
                log.warn("Ontology proposal contained no entity or relation types");
                return Optional.empty();
            }
            return Optional.of(ontology);
        } catch (JsonProcessingException exception) {
            log.warn("Failed to parse ontology proposal JSON", exception);
            return Optional.empty();
        } catch (IllegalArgumentException exception) {
            log.warn("Ontology proposal failed structural validation", exception);
            return Optional.empty();
        }
    }

    private static String extractJsonObject(String response) {
        String normalized = response == null ? "" : response.strip();
        if (normalized.isEmpty()) {
            return null;
        }

        // Try stripping a whole-code fence first.
        String stripped = stripWholeCodeFence(normalized);

        // Collect candidates in order of preference.
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(stripped);
        extractFirstCodeFence(normalized).ifPresent(candidates::add);
        extractFirstJsonObject(stripped).ifPresent(candidates::add);
        extractFirstCodeFence(normalized)
            .flatMap(OntologyDiscovery::extractFirstJsonObject)
            .ifPresent(candidates::add);

        for (String candidate : candidates) {
            if (isJsonObject(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String stripWholeCodeFence(String response) {
        String normalized = response.strip();
        if (!normalized.startsWith("```")) {
            return normalized;
        }
        int firstNewline = normalized.indexOf('\n');
        if (firstNewline < 0) {
            return normalized;
        }
        String body = normalized.substring(firstNewline + 1);
        if (body.endsWith("```")) {
            body = body.substring(0, body.length() - 3);
        }
        return body.strip();
    }

    private static Optional<String> extractFirstCodeFence(String response) {
        int start = response.indexOf("```");
        while (start >= 0) {
            int blockStart = response.indexOf('\n', start);
            if (blockStart < 0) {
                return Optional.empty();
            }
            int end = response.indexOf("```", blockStart + 1);
            if (end < 0) {
                return Optional.empty();
            }
            String candidate = response.substring(blockStart + 1, end).strip();
            if (!candidate.isEmpty()) {
                return Optional.of(candidate);
            }
            start = response.indexOf("```", end + 3);
        }
        return Optional.empty();
    }

    private static Optional<String> extractFirstJsonObject(String response) {
        int candidateStart = response.indexOf('{');
        while (candidateStart >= 0) {
            Optional<String> candidate = balancedJsonObjectAt(response, candidateStart);
            if (candidate.isPresent() && isJsonObject(candidate.get())) {
                return candidate;
            }
            candidateStart = response.indexOf('{', candidateStart + 1);
        }
        return Optional.empty();
    }

    private static Optional<String> balancedJsonObjectAt(String response, int startIndex) {
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;
        for (int index = startIndex; index < response.length(); index++) {
            char current = response.charAt(index);
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
            JsonNode root = OBJECT_MAPPER.readTree(candidate);
            return root != null && root.isObject();
        } catch (JsonProcessingException exception) {
            return false;
        }
    }
}
