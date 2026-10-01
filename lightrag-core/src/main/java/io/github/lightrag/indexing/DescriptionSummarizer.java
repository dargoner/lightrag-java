package io.github.lightrag.indexing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.HeuristicTokenCounter;
import io.github.lightrag.model.TokenCounter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Summarizes merged description fragments with a map-reduce loop, a faithful port of upstream
 * {@code _handle_entity_relation_summary} / {@code _summarize_descriptions} ({@code operate.py:372-651},
 * prompt {@code prompt.py:297-325}).
 *
 * <p>Below both thresholds ({@code force_llm_summary_on_merge} fragments and {@code summary_max_tokens}
 * tokens) there is no LLM call: the deduped fragments are {@code <SEP>}-joined and stored as-is, which is
 * upstream's storage format. Above them the fragments are summarized — splitting into context-sized groups
 * first when the list exceeds {@code summary_context_size} tokens.</p>
 *
 * <p>A summary-model failure propagates: upstream wraps neither the map/reduce loop nor
 * {@code _summarize_descriptions} in a try/except, so a failure fails the document ingest rather than
 * persisting a description upstream would never have written.</p>
 */
public final class DescriptionSummarizer {
    public static final int DEFAULT_FORCE_LLM_SUMMARY_ON_MERGE = 8;
    public static final int DEFAULT_SUMMARY_MAX_TOKENS = 1_200;
    public static final int DEFAULT_SUMMARY_CONTEXT_SIZE = 12_000;
    public static final int DEFAULT_SUMMARY_LENGTH_RECOMMENDED = 600;

    private static final Logger log = LoggerFactory.getLogger(DescriptionSummarizer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String PROMPT_TEMPLATE = """
        ---Role---
        You are a Knowledge Graph Specialist, proficient in data curation and synthesis.

        ---Task---
        Your task is to synthesize a list of descriptions of a given entity or relation into a single, comprehensive, and cohesive summary.

        ---Instructions---
        1. Input Format: The description list is provided in JSON format. Each JSON object (representing a single description) appears on a new line within the `Description List` section.
        2. Output Format: The merged description will be returned as plain text, presented in multiple paragraphs, without any additional formatting or extraneous comments before or after the summary.
        3. Comprehensiveness: The summary must integrate all key information from *every* provided description. Do not omit any important facts or details.
        4. Context: Ensure the summary is written from an objective, third-person perspective; explicitly mention the name of the entity or relation for full clarity and context.
        5. Context & Objectivity:
          - Write the summary from an objective, third-person perspective.
          - Explicitly mention the full name of the entity or relation at the beginning of the summary to ensure immediate clarity and context.
        6. Conflict Handling:
          - In cases of conflicting or inconsistent descriptions, first determine if these conflicts arise from multiple, distinct entities or relationships that share the same name.
          - If distinct entities/relations are identified, summarize each one *separately* within the overall output.
          - If conflicts within a single entity/relation (e.g., historical discrepancies) exist, attempt to reconcile them or present both viewpoints with noted uncertainty.
        7. Length Constraint: The summary's total length must not exceed %s tokens, while still maintaining depth and completeness.
        8. Language: The entire output must be written in %s. Proper nouns (e.g., personal names, place names, organization names) should be retained in their original language if a proper, widely accepted translation is not available or would cause ambiguity.

        ---Input---
        %s Name: %s

        Description List:

        ```
        %s
        ```

        ---Output---
        """;

    private final ChatModel summaryModel;
    private final TokenCounter tokenCounter;
    private final int forceLlmSummaryOnMerge;
    private final int summaryMaxTokens;
    private final int summaryContextSize;
    private final int summaryLengthRecommended;
    private final String language;
    private final boolean llmEnabled;

    public DescriptionSummarizer(
        ChatModel summaryModel,
        TokenCounter tokenCounter,
        int forceLlmSummaryOnMerge,
        int summaryMaxTokens,
        int summaryContextSize,
        int summaryLengthRecommended,
        String language
    ) {
        this(
            summaryModel,
            tokenCounter,
            forceLlmSummaryOnMerge,
            summaryMaxTokens,
            summaryContextSize,
            summaryLengthRecommended,
            language,
            true
        );
    }

    private DescriptionSummarizer(
        ChatModel summaryModel,
        TokenCounter tokenCounter,
        int forceLlmSummaryOnMerge,
        int summaryMaxTokens,
        int summaryContextSize,
        int summaryLengthRecommended,
        String language,
        boolean llmEnabled
    ) {
        this.summaryModel = summaryModel;
        this.tokenCounter = tokenCounter == null ? new HeuristicTokenCounter() : tokenCounter;
        this.forceLlmSummaryOnMerge = Math.max(1, forceLlmSummaryOnMerge);
        this.summaryMaxTokens = Math.max(1, summaryMaxTokens);
        this.summaryContextSize = Math.max(1, summaryContextSize);
        this.summaryLengthRecommended = Math.max(1, summaryLengthRecommended);
        this.language = language == null || language.isBlank()
            ? KnowledgeExtractor.DEFAULT_LANGUAGE
            : language.strip();
        this.llmEnabled = llmEnabled && summaryModel != null;
    }

    /** Default summarizer for a model: upstream thresholds, heuristic token counter, extractor language. */
    public static DescriptionSummarizer forModel(ChatModel summaryModel, String language) {
        return new DescriptionSummarizer(
            summaryModel,
            new HeuristicTokenCounter(),
            DEFAULT_FORCE_LLM_SUMMARY_ON_MERGE,
            DEFAULT_SUMMARY_MAX_TOKENS,
            DEFAULT_SUMMARY_CONTEXT_SIZE,
            DEFAULT_SUMMARY_LENGTH_RECOMMENDED,
            language
        );
    }

    /** Join-only instance: never calls a model, used by tests that assert raw {@code <SEP>} joins. */
    public static DescriptionSummarizer withoutLlm(TokenCounter tokenCounter, String language) {
        return new DescriptionSummarizer(
            null,
            tokenCounter,
            DEFAULT_FORCE_LLM_SUMMARY_ON_MERGE,
            DEFAULT_SUMMARY_MAX_TOKENS,
            DEFAULT_SUMMARY_CONTEXT_SIZE,
            DEFAULT_SUMMARY_LENGTH_RECOMMENDED,
            language,
            false
        );
    }

    public static DescriptionSummarizer withoutLlm() {
        return withoutLlm(new HeuristicTokenCounter(), KnowledgeExtractor.DEFAULT_LANGUAGE);
    }

    public record Summary(String description, boolean llmUsed) {
        public Summary {
            description = Objects.requireNonNull(description, "description");
        }
    }

    public Summary summarize(String descriptionType, String name, List<String> descriptionList) {
        var fragments = DescriptionFragments.combine(List.of(), descriptionList);
        if (fragments.isEmpty()) {
            return new Summary("", false);
        }
        if (!llmEnabled) {
            return new Summary(String.join(DescriptionFragments.SEPARATOR, fragments), false);
        }
        if (fragments.size() == 1) {
            return new Summary(fragments.get(0), false);
        }

        var current = new ArrayList<>(fragments);
        boolean llmUsed = false;
        while (true) {
            long totalTokens = current.stream().mapToLong(tokenCounter::countTokens).sum();
            if (totalTokens <= summaryContextSize || current.size() <= 2) {
                if (current.size() < forceLlmSummaryOnMerge && totalTokens < summaryMaxTokens) {
                    return new Summary(String.join(DescriptionFragments.SEPARATOR, current), llmUsed);
                }
                if (totalTokens > summaryContextSize && current.size() <= 2) {
                    log.warn("summarize_event=oversize_description name={} fragments={}", name, current.size());
                }
                return new Summary(summarizeWithLlm(descriptionType, name, current), true);
            }

            var chunks = splitIntoContextChunks(current);
            log.info(
                "summarize_event=map name={} fragments={} groups={}",
                name,
                current.size(),
                chunks.size()
            );
            var next = new ArrayList<String>(chunks.size());
            for (var chunk : chunks) {
                if (chunk.size() == 1) {
                    next.add(chunk.get(0));
                } else {
                    next.add(summarizeWithLlm(descriptionType, name, chunk));
                    llmUsed = true;
                }
            }
            current = next;
        }
    }

    private List<List<String>> splitIntoContextChunks(List<String> current) {
        var chunks = new ArrayList<List<String>>();
        var currentChunk = new ArrayList<String>();
        long currentTokens = 0;
        for (var description : current) {
            long tokens = tokenCounter.countTokens(description);
            if (currentTokens + tokens > summaryContextSize && !currentChunk.isEmpty()) {
                if (currentChunk.size() == 1) {
                    currentChunk.add(description);
                    chunks.add(List.copyOf(currentChunk));
                    log.warn("summarize_event=oversize_description fragments={}", current.size());
                    currentChunk = new ArrayList<>();
                    currentTokens = 0;
                } else {
                    chunks.add(List.copyOf(currentChunk));
                    currentChunk = new ArrayList<>();
                    currentChunk.add(description);
                    currentTokens = tokens;
                }
            } else {
                currentChunk.add(description);
                currentTokens += tokens;
            }
        }
        if (!currentChunk.isEmpty()) {
            chunks.add(List.copyOf(currentChunk));
        }
        return List.copyOf(chunks);
    }

    private String summarizeWithLlm(String descriptionType, String name, List<String> descriptions) {
        var rendered = descriptions.stream().map(DescriptionSummarizer::renderJsonLine).toList();
        var joinedDescriptions = String.join("\n", truncateToTokenBudget(rendered, summaryContextSize));
        var prompt = PROMPT_TEMPLATE.formatted(
            summaryLengthRecommended,
            language,
            descriptionType,
            name,
            joinedDescriptions
        );
        var response = summaryModel.generate(new ChatModel.ChatRequest("", prompt));
        var summary = TextSanitizer.sanitizeForEncoding(response);
        var summaryTokens = tokenCounter.countTokens(response);
        if (summaryTokens > summaryMaxTokens) {
            log.warn(
                "summarize_event=oversize_summary name={} tokens={} limit={}",
                name,
                summaryTokens,
                summaryMaxTokens
            );
        }
        return summary;
    }

    private List<String> truncateToTokenBudget(List<String> renderedLines, int maxTokens) {
        if (maxTokens <= 0 || renderedLines.isEmpty()) {
            return List.of();
        }
        var kept = new ArrayList<String>();
        for (var renderedLine : renderedLines) {
            kept.add(renderedLine);
            if (tokenCounter.countTokens(String.join("\n", kept)) > maxTokens) {
                kept.remove(kept.size() - 1);
                break;
            }
        }
        return List.copyOf(kept);
    }

    private static String renderJsonLine(String description) {
        try {
            // Space after the colon matches Python's json.dumps default separators (", ", ": "), the exact
            // byte layout upstream renders into the prompt (and therefore into the cache key).
            return "{\"Description\": " + JSON.writeValueAsString(description) + "}";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to render description JSON line", exception);
        }
    }
}
