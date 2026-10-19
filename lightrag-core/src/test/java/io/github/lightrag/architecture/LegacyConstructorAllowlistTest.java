package io.github.lightrag.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source-rule gate for the attribute-bearing records: the legacy constructors exist
 * for binary compatibility, but they silently create an empty attribute map. Rebuilding
 * an existing record through them loses attributes, and neither the compiler nor the
 * JVM can tell that apart from a legitimate empty placeholder. Keep the allowlist
 * explicit and review every new entry.
 */
class LegacyConstructorAllowlistTest {

    private static final Map<String, Set<Integer>> LEGACY_ARITIES = Map.ofEntries(
        Map.entry("ExtractedEntity", Set.of(4)),
        Map.entry("ExtractedRelation", Set.of(5)),
        Map.entry("Entity", Set.of(6, 7)),
        Map.entry("Relation", Set.of(7, 8)),
        Map.entry("EntityRecord", Set.of(6, 7)),
        Map.entry("RelationRecord", Set.of(7, 8)),
        Map.entry("WindowEntityCandidate", Set.of(5)),
        Map.entry("WindowRelationCandidate", Set.of(6)),
        Map.entry("WindowExtractionResponse", Set.of(2, 3)),
        Map.entry("GraphEntity", Set.of(6, 7)),
        Map.entry("GraphRelation", Set.of(7, 8)),
        Map.entry("ExtractedEntityRecord", Set.of(4)),
        Map.entry("ExtractedRelationRecord", Set.of(5))
    );

    /**
     * Reviewed legacy uses. Every entry must stay in place: the test fails both on a
     * new unlisted use and on a stale entry whose code moved or disappeared.
     */
    private static final Map<String, String> ALLOWLIST = Map.of(
        "src/main/java/io/github/lightrag/indexing/GraphAssembler.java:185 "
            + "new ExtractedEntity -> 4 args",
        "empty placeholder in ensureEntity",
        "src/main/java/io/github/lightrag/indexing/refinement/DefaultExtractionMergePolicy.java:53 "
            + "new ExtractedEntity -> 4 args",
        "empty placeholder in computeIfAbsent",
        "src/main/java/io/github/lightrag/ontology/OntologyConformanceValidator.java:114 "
            + "new ExtractedEntity -> 4 args",
        "PREFER deliberately clears attributes"
    );

    private static final Pattern CONSTRUCTOR = Pattern.compile("\\bnew\\s+([\\w.]+)\\s*\\(");

    @Test
    void legacyAttributeConstructorsOnlyAppearAtReviewedPlaceholders() throws IOException {
        var sourceRoot = mainSourceRoot();
        var moduleRoot = sourceRoot.getParent().getParent().getParent();
        var found = new TreeSet<String>();
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                found.addAll(scan(moduleRoot, file));
            }
        }

        assertThat(found).containsExactlyInAnyOrderElementsOf(ALLOWLIST.keySet());
    }

    @Test
    void qualifiedConstructorsAreScannedByTheirSimpleTypeName() {
        var source = """
            class Sample {
                Object first = new GraphStore.EntityRecord(1, 2, 3, 4, 5, 6);
                Object second = new io.github.lightrag.storage.GraphStore.EntityRecord(1, 2, 3, 4, 5, 6);
            }
            """;

        assertThat(scanSource("Sample.java", source)).containsExactly(
            "Sample.java:2 new EntityRecord -> 6 args",
            "Sample.java:3 new EntityRecord -> 6 args"
        );
    }

    private static List<String> scan(Path moduleRoot, Path file) throws IOException {
        var relative = moduleRoot.relativize(file).toString().replace('\\', '/');
        return scanSource(relative, Files.readString(file, StandardCharsets.UTF_8));
    }

    static List<String> scanSource(String relative, String source) {
        var masked = maskCommentsAndLiterals(source);
        var violations = new ArrayList<String>();
        Matcher matcher = CONSTRUCTOR.matcher(masked);
        while (matcher.find()) {
            var qualifiedType = matcher.group(1);
            var type = qualifiedType.substring(qualifiedType.lastIndexOf('.') + 1);
            var legacyArities = LEGACY_ARITIES.get(type);
            if (legacyArities == null) {
                continue;
            }
            int arguments = countArguments(masked, matcher.end() - 1);
            if (legacyArities.contains(arguments)) {
                int line = (int) masked.substring(0, matcher.start()).chars()
                    .filter(character -> character == '\n')
                    .count() + 1;
                violations.add(relative + ":" + line + " new " + type + " -> " + arguments + " args");
            }
        }
        return violations;
    }

    /**
     * Counts top-level arguments of the constructor invocation starting at {@code openParen}.
     * Commas nested in calls, arrays, literals, or generic type arguments do not separate
     * arguments. Angle brackets are only treated as nesting when they look like a generic
     * type argument list, which keeps comparison operators from hiding an argument.
     */
    private static int countArguments(String source, int openParen) {
        int depth = 0;
        int genericDepth = 0;
        int commas = 0;
        boolean hasArgument = false;
        for (int index = openParen; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    break;
                }
            } else if (depth == 1) {
                if (current == '<') {
                    char previous = source.charAt(index - 1);
                    if (Character.isLetterOrDigit(previous) || "._>?".indexOf(previous) >= 0) {
                        genericDepth++;
                    }
                } else if (current == '>' && genericDepth > 0) {
                    genericDepth--;
                } else if (current == ',' && genericDepth == 0) {
                    commas++;
                } else if (!Character.isWhitespace(current)) {
                    hasArgument = true;
                }
            }
        }
        return hasArgument ? commas + 1 : 0;
    }

    /**
     * Replaces comments and string/char/text-block contents with spaces while preserving
     * offsets and line breaks, so constructor-looking text inside them is not scanned.
     */
    private static String maskCommentsAndLiterals(String source) {
        var masked = source.toCharArray();
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                while (index < source.length() && source.charAt(index) != '\n') {
                    masked[index++] = ' ';
                }
            } else if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                masked[index++] = ' ';
                masked[index++] = ' ';
                while (index < source.length()) {
                    if (source.charAt(index) == '*' && index + 1 < source.length()
                        && source.charAt(index + 1) == '/') {
                        masked[index++] = ' ';
                        masked[index++] = ' ';
                        break;
                    }
                    if (source.charAt(index) != '\n') {
                        masked[index] = ' ';
                    }
                    index++;
                }
            } else if (current == '"' || current == '\'') {
                boolean textBlock = current == '"' && source.startsWith("\"\"\"", index);
                int delimiter = textBlock ? 3 : 1;
                for (int close = 0; close < delimiter; close++) {
                    masked[index++] = ' ';
                }
                while (index < source.length()) {
                    char literal = source.charAt(index);
                    if (literal == '\\' && index + 1 < source.length()) {
                        masked[index++] = ' ';
                        if (source.charAt(index) != '\n') {
                            masked[index] = ' ';
                        }
                        index++;
                    } else if (textBlock
                        ? source.startsWith("\"\"\"", index)
                        : literal == current) {
                        for (int close = 0; close < delimiter; close++) {
                            masked[index++] = ' ';
                        }
                        break;
                    } else {
                        if (literal != '\n') {
                            masked[index] = ' ';
                        }
                        index++;
                    }
                }
            } else {
                index++;
            }
        }
        return new String(masked);
    }

    private static Path mainSourceRoot() {
        for (Path candidate = Path.of("").toAbsolutePath(); candidate != null; candidate = candidate.getParent()) {
            Path direct = candidate.resolve("src/main/java");
            if (Files.isDirectory(direct) && Files.isDirectory(candidate.resolve("src/test/java"))) {
                return direct;
            }
            Path module = candidate.resolve("lightrag-core/src/main/java");
            if (Files.isDirectory(module)) {
                return module;
            }
        }
        throw new IllegalStateException("lightrag-core source root not found from " + Path.of("").toAbsolutePath());
    }
}
