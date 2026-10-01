package io.github.lightrag.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.model.TokenCounter;
import io.github.lightrag.types.ScoredChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves a chunk's heading breadcrumb the way upstream renders {@code content_headings}: each level
 * cleaned like {@code _clean_heading_text}, hard-capped at 80 characters, joined with {@code " → "},
 * and the joined breadcrumb token-budgeted at 256 tokens like {@code _truncate_section_context}.
 *
 * <p>Platform chunk metadata carries headings either as a JSON array of levels ({@code headingPath},
 * {@code sectionHierarchy}) or pre-joined with {@code " > "} ({@code section_path}, {@code sectionPath},
 * {@code smart_chunker.section_path}), so both shapes normalize to the upstream breadcrumb.</p>
 */
final class ChunkHeadings {
    private static final String SEPARATOR = " → ";
    private static final String PRE_JOINED_SEPARATOR = " > ";
    private static final String ELLIPSIS = "…";
    private static final int MAX_LEVEL_CHARS = 80;
    private static final int MAX_BREADCRUMB_TOKENS = 256;
    private static final String KEEP_WHITESPACE_CONTROLS = "\t\n\u000B\f\r";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final ObjectMapper JSON = new ObjectMapper();

    private ChunkHeadings() {
    }

    static Optional<String> resolve(ScoredChunk chunk, TokenCounter tokenCounter) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(tokenCounter, "tokenCounter");
        var metadata = chunk.chunk().metadata();

        var levels = cleaned(jsonLevels(metadata.get("headingPath")));
        if (levels.isEmpty()) {
            levels = cleaned(jsonLevels(metadata.get("sectionHierarchy")));
        }
        if (levels.isEmpty()) {
            levels = cleaned(preJoinedLevels(firstNonBlank(
                metadata.get("section_path"),
                metadata.get("sectionPath"),
                metadata.get("smart_chunker.section_path")
            )));
        }
        if (levels.isEmpty()) {
            return Optional.empty();
        }

        var budgeted = fitToBudget(String.join(SEPARATOR, levels), tokenCounter);
        return budgeted.isEmpty() ? Optional.empty() : Optional.of(budgeted);
    }

    private static List<String> jsonLevels(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            var node = JSON.readTree(value);
            if (!node.isArray()) {
                return List.of();
            }
            var levels = new ArrayList<String>(node.size());
            for (var element : node) {
                levels.add(element.asText());
            }
            return levels;
        } catch (Exception malformed) {
            return List.of();
        }
    }

    private static List<String> preJoinedLevels(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(Pattern.quote(PRE_JOINED_SEPARATOR), -1));
    }

    private static List<String> cleaned(List<String> rawLevels) {
        var levels = new ArrayList<String>(rawLevels.size());
        for (var rawLevel : rawLevels) {
            var level = truncateLevel(clean(rawLevel), MAX_LEVEL_CHARS);
            if (!level.isEmpty()) {
                levels.add(level);
            }
        }
        return List.copyOf(levels);
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        var withoutSeparator = value.replace("→", " ");
        var builder = new StringBuilder(withoutSeparator.length());
        for (var index = 0; index < withoutSeparator.length(); ) {
            var codePoint = withoutSeparator.codePointAt(index);
            index += Character.charCount(codePoint);
            var category = Character.getType(codePoint);
            var droppable = category == Character.CONTROL || category == Character.FORMAT;
            if (!droppable || KEEP_WHITESPACE_CONTROLS.indexOf(codePoint) >= 0) {
                builder.appendCodePoint(codePoint);
            }
        }
        return WHITESPACE.matcher(builder.toString()).replaceAll(" ").strip();
    }

    private static String truncateLevel(String level, int maxChars) {
        if (level.codePointCount(0, level.length()) <= maxChars) {
            return level;
        }
        return level.substring(0, level.offsetByCodePoints(0, maxChars - 1)).stripTrailing() + ELLIPSIS;
    }

    private static String fitToBudget(String breadcrumb, TokenCounter tokenCounter) {
        if (tokenCounter.countTokens(breadcrumb) <= MAX_BREADCRUMB_TOKENS) {
            return breadcrumb;
        }
        var levels = breadcrumb.split(Pattern.quote(SEPARATOR), -1);
        var collapsed = levels.length >= 3
            ? levels[0] + SEPARATOR + ELLIPSIS + SEPARATOR + levels[levels.length - 1]
            : breadcrumb;
        if (tokenCounter.countTokens(collapsed) <= MAX_BREADCRUMB_TOKENS) {
            return collapsed;
        }
        for (var keep = collapsed.codePointCount(0, collapsed.length()) - 1; keep > 0; keep--) {
            var prefix = collapsed.substring(0, collapsed.offsetByCodePoints(0, keep)).stripTrailing();
            var withEllipsis = prefix + ELLIPSIS;
            if (tokenCounter.countTokens(withEllipsis) <= MAX_BREADCRUMB_TOKENS) {
                return withEllipsis;
            }
            if (tokenCounter.countTokens(prefix) <= MAX_BREADCRUMB_TOKENS) {
                return prefix;
            }
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
