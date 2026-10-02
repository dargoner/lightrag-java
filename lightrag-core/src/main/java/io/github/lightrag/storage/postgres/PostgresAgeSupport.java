package io.github.lightrag.storage.postgres;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pure helpers for the Apache AGE graph backend, ported from the upstream Python
 * {@code PGGraphStorage} ({@code kg/postgres_impl.py}). No JDBC connection involved.
 */
final class PostgresAgeSupport {
    /** Graph namespace used by the single Java knowledge graph (upstream {@code namespace.py:20}). */
    static final String GRAPH_NAMESPACE = "chunk_entity_relation";

    /** PostgreSQL's {@code name} type limit (NAMEDATALEN - 1), upstream {@code _PG_NAME_MAX_BYTES}. */
    static final int PG_NAME_MAX_BYTES = 63;

    private static final Pattern VERSION_PATTERN = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");

    private PostgresAgeSupport() {
    }

    /**
     * Derives the AGE graph name for a workspace, mirroring upstream
     * {@code PGGraphStorage._get_workspace_graph_name}: the default workspace keeps the bare
     * namespace for backward compatibility, any other workspace is prefixed after sanitising
     * everything outside {@code [A-Za-z0-9_]}. The result is clipped to
     * {@link #PG_NAME_MAX_BYTES} characters, which equals bytes because sanitising yields ASCII.
     */
    static String graphName(String workspaceId) {
        var workspace = workspaceId == null ? "" : workspaceId.strip();
        var candidate = workspace.isEmpty() || workspace.equalsIgnoreCase("default")
            ? GRAPH_NAMESPACE
            : workspace.replaceAll("[^A-Za-z0-9_]", "_") + "_" + GRAPH_NAMESPACE;
        return candidate.substring(0, Math.min(candidate.length(), PG_NAME_MAX_BYTES));
    }

    /**
     * Finds a PostgreSQL dollar-quote tag that does not collide with the content, upstream
     * {@code _dollar_quote}. {@code s.endsWith(tag-without-closing-dollar)} is rejected too: the
     * leading {@code $} of the appended closing wrapper would then complete a premature delimiter
     * at the seam and truncate the literal.
     */
    static String dollarQuote(String value) {
        var content = value == null ? "" : value;
        for (int index = 1; ; index++) {
            var wrapper = "$AGE" + index + "$";
            if (!content.contains(wrapper) && !content.endsWith(wrapper.substring(0, wrapper.length() - 1))) {
                return wrapper + content + wrapper;
            }
        }
    }

    /**
     * Renders a Cypher property map with values as JSON literals, upstream
     * {@code _format_properties}: keys are backtick-quoted with embedded backticks doubled.
     */
    static String formatProperties(Map<String, ?> properties) {
        var builder = new StringBuilder("{");
        var first = true;
        for (var entry : properties.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.append('`').append(entry.getKey().replace("`", "``")).append("`: ");
            builder.append(jsonValue(entry.getValue()));
        }
        return builder.append('}').toString();
    }

    private static String jsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return jsonString(text);
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Iterable<?> iterable) {
            var builder = new StringBuilder("[");
            var first = true;
            for (var element : iterable) {
                if (!first) {
                    builder.append(", ");
                }
                first = false;
                builder.append(jsonValue(element));
            }
            return builder.append(']').toString();
        }
        return jsonString(String.valueOf(value));
    }

    /** JSON string encoding matching Python {@code json.dumps(..., ensure_ascii=False)}: non-ASCII stays raw. */
    static String jsonString(String value) {
        var builder = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }

    /**
     * Parses exactly three ASCII numeric components, upstream {@code _parse_age_version}. Returns
     * {@code null} when {@code raw} is {@code null} (source absent) and also when present-but-unparseable;
     * callers distinguish the two by the raw value being non-null.
     */
    static AgeVersion parseAgeVersion(String raw) {
        if (raw == null || !VERSION_PATTERN.matcher(raw).matches()) {
            return null;
        }
        var parts = raw.split("\\.");
        return new AgeVersion(
            Integer.parseInt(parts[0]),
            Integer.parseInt(parts[1]),
            Integer.parseInt(parts[2])
        );
    }

    record AgeVersion(int major, int minor, int patch) implements Comparable<AgeVersion> {
        @Override
        public int compareTo(AgeVersion other) {
            var majorComparison = Integer.compare(major, other.major);
            if (majorComparison != 0) {
                return majorComparison;
            }
            var minorComparison = Integer.compare(minor, other.minor);
            if (minorComparison != 0) {
                return minorComparison;
            }
            return Integer.compare(patch, other.patch);
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }
}
