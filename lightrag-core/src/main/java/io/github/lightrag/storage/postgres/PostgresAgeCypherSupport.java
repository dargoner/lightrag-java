package io.github.lightrag.storage.postgres;

import io.github.lightrag.exception.StorageException;

import java.util.ArrayList;
import java.util.List;

/**
 * Text-level helpers for running caller-supplied Cypher on an Apache AGE graph, used by
 * {@link PostgresAgeGraphStore#executeCypher}. {@code ag_catalog.cypher(...)} requires the result
 * column definition list up front, so the top-level RETURN clause of the statement is inspected
 * first and each item becomes a positional {@code ag_catalog.agtype} column; {@code RETURN *}
 * cannot be expressed that way and is rejected. Values are declared as agtype and converted back
 * to plain Java here, which keeps vertices, edges and paths structured instead of AGE's escaped
 * tuple text form.
 */
final class PostgresAgeCypherSupport {
    private static final String[] TRAILING_CLAUSE_KEYWORDS = {"ORDER", "SKIP", "LIMIT", "UNION"};
    private static final String[] GRAPH_VALUE_SUFFIXES = {"vertex", "edge", "path"};

    private PostgresAgeCypherSupport() {
    }

    /**
     * Top-level RETURN items of a single Cypher statement, or an empty list when the statement has
     * no RETURN clause. Throws {@link StorageException} for {@code RETURN *} (AGE needs the
     * columns declared before the statement runs) and for malformed empty items.
     */
    static List<String> returnItems(String cypher) {
        var text = stripComments(cypher);
        var returnIndex = indexOfKeyword(text, "RETURN", 0);
        if (returnIndex < 0) {
            return List.of();
        }
        var itemsStart = returnIndex + "RETURN".length();
        var itemsEnd = text.length();
        for (var keyword : TRAILING_CLAUSE_KEYWORDS) {
            var index = indexOfKeyword(text, keyword, itemsStart);
            if (index >= 0 && index < itemsEnd) {
                itemsEnd = index;
            }
        }
        var body = text.substring(itemsStart, itemsEnd).strip();
        if (startsWithKeyword(body, "DISTINCT")) {
            body = body.substring("DISTINCT".length()).strip();
        }
        var items = splitTopLevel(body);
        for (var item : items) {
            if (item.equals("*")) {
                throw new StorageException(
                    "Apache AGE cannot execute 'RETURN *' because the result columns must be declared"
                        + " before the statement runs; list the returned expressions explicitly"
                );
            }
        }
        return items;
    }

    /** Display names of RETURN items: the AS alias when present, otherwise the raw expression. */
    static List<String> columnNames(List<String> items) {
        var names = new ArrayList<String>(items.size());
        for (var item : items) {
            names.add(columnName(item));
        }
        return List.copyOf(names);
    }

    /**
     * Column definition list for {@code ag_catalog.cypher(...)}: one positional agtype column per
     * RETURN item, or the shared single dummy column for statements without RETURN.
     */
    static String columnDefinitionList(int columnCount) {
        if (columnCount <= 0) {
            return "value ag_catalog.agtype";
        }
        var builder = new StringBuilder();
        for (var index = 1; index <= columnCount; index++) {
            if (index > 1) {
                builder.append(", ");
            }
            builder.append('c').append(index).append(" ag_catalog.agtype");
        }
        return builder.toString();
    }

    /**
     * AGE agtype text to a plain Java value: quoted strings unquote, composites become
     * {@code Map}/{@code List}, and the {@code ::vertex} / {@code ::edge} / {@code ::path} suffixes
     * are removed before parsing. Anything that is not valid JSON thereafter stays a raw string.
     */
    static Object toJavaValue(String agtypeText) {
        if (agtypeText == null) {
            return null;
        }
        try {
            return JdbcJsonCodec.readJsonValue(stripGraphValueSuffixes(agtypeText));
        } catch (IllegalArgumentException exception) {
            return agtypeText;
        }
    }

    private static String columnName(String item) {
        var asIndex = lastTopLevelKeyword(item, "AS");
        if (asIndex >= 0) {
            var alias = stripBackticks(item.substring(asIndex + "AS".length()).strip());
            if (!alias.isEmpty()) {
                return alias;
            }
        }
        return item;
    }

    /** Removes {@code //...} and block comments while preserving string and backtick content. */
    private static String stripComments(String cypher) {
        var builder = new StringBuilder(cypher.length());
        var state = CommentState.NONE;
        var quote = '\0';
        for (var index = 0; index < cypher.length(); index++) {
            var character = cypher.charAt(index);
            var next = index + 1 < cypher.length() ? cypher.charAt(index + 1) : '\0';
            switch (state) {
                case NONE -> {
                    if (character == '/' && next == '/') {
                        state = CommentState.LINE;
                        index++;
                    } else if (character == '/' && next == '*') {
                        state = CommentState.BLOCK;
                        index++;
                    } else {
                        builder.append(character);
                        if (character == '\'' || character == '"') {
                            state = CommentState.QUOTED;
                            quote = character;
                        } else if (character == '`') {
                            state = CommentState.BACKTICK;
                        }
                    }
                }
                case QUOTED -> {
                    builder.append(character);
                    if (character == '\\' && index + 1 < cypher.length()) {
                        builder.append(cypher.charAt(++index));
                    } else if (character == quote) {
                        state = CommentState.NONE;
                    }
                }
                case BACKTICK -> {
                    builder.append(character);
                    if (character == '`') {
                        if (next == '`') {
                            builder.append(cypher.charAt(++index));
                        } else {
                            state = CommentState.NONE;
                        }
                    }
                }
                case LINE -> {
                    if (character == '\n') {
                        builder.append('\n');
                        state = CommentState.NONE;
                    }
                }
                case BLOCK -> {
                    if (character == '*' && next == '/') {
                        index++;
                        state = CommentState.NONE;
                    }
                }
            }
        }
        return builder.toString();
    }

    private enum CommentState {
        NONE,
        QUOTED,
        BACKTICK,
        LINE,
        BLOCK
    }

    private static int indexOfKeyword(String text, String keyword, int fromIndex) {
        var depth = 0;
        for (var index = 0; index < text.length(); index++) {
            var character = text.charAt(index);
            if (character == '\'' || character == '"' || character == '`') {
                index = skipQuoted(text, index, character);
                continue;
            }
            if (character == '(' || character == '[' || character == '{') {
                depth++;
                continue;
            }
            if (character == ')' || character == ']' || character == '}') {
                depth--;
                continue;
            }
            if (depth != 0 || index < fromIndex) {
                continue;
            }
            if (text.regionMatches(true, index, keyword, 0, keyword.length())
                && isKeywordBoundaryBefore(text, index)
                && isKeywordBoundaryAfter(text, index + keyword.length())) {
                return index;
            }
        }
        return -1;
    }

    private static int lastTopLevelKeyword(String text, String keyword) {
        var index = indexOfKeyword(text, keyword, 0);
        var last = -1;
        while (index >= 0) {
            last = index;
            index = indexOfKeyword(text, keyword, index + keyword.length());
        }
        return last;
    }

    private static List<String> splitTopLevel(String body) {
        var items = new ArrayList<String>();
        var depth = 0;
        var start = 0;
        for (var index = 0; index < body.length(); index++) {
            var character = body.charAt(index);
            if (character == '\'' || character == '"' || character == '`') {
                index = skipQuoted(body, index, character);
                continue;
            }
            if (character == '(' || character == '[' || character == '{') {
                depth++;
                continue;
            }
            if (character == ')' || character == ']' || character == '}') {
                depth--;
                continue;
            }
            if (character == ',' && depth == 0) {
                items.add(item(body.substring(start, index)));
                start = index + 1;
            }
        }
        items.add(item(body.substring(start)));
        return List.copyOf(items);
    }

    private static String item(String candidate) {
        var item = candidate.strip();
        if (item.isEmpty()) {
            throw new StorageException(
                "Apache AGE cannot execute a Cypher statement with an empty RETURN item: '"
                    + candidate.strip() + "'"
            );
        }
        return item;
    }

    private static boolean startsWithKeyword(String text, String keyword) {
        return text.regionMatches(true, 0, keyword, 0, keyword.length())
            && isKeywordBoundaryAfter(text, keyword.length());
    }

    /**
     * A keyword does not start at a property access ({@code n.return}) or a parameter
     * ({@code $return}); both read better as non-keywords for this scan.
     */
    private static boolean isKeywordBoundaryBefore(String text, int index) {
        if (index == 0) {
            return true;
        }
        var previous = text.charAt(index - 1);
        return !Character.isLetterOrDigit(previous)
            && previous != '_'
            && previous != '.'
            && previous != '$';
    }

    private static boolean isKeywordBoundaryAfter(String text, int index) {
        if (index >= text.length()) {
            return true;
        }
        var next = text.charAt(index);
        return !Character.isLetterOrDigit(next) && next != '_';
    }

    /** Returns the index of the closing quote, or the text end when the literal is unterminated. */
    private static int skipQuoted(String text, int start, char quote) {
        for (var index = start + 1; index < text.length(); index++) {
            var character = text.charAt(index);
            if (character == '\\' && quote != '`') {
                index++;
                continue;
            }
            if (character == quote) {
                if (quote == '`' && index + 1 < text.length() && text.charAt(index + 1) == '`') {
                    index++;
                    continue;
                }
                return index;
            }
        }
        return text.length();
    }

    private static String stripBackticks(String value) {
        if (value.length() >= 2 && value.startsWith("`") && value.endsWith("`")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * Removes agtype's {@code ::vertex} / {@code ::edge} / {@code ::path} suffixes (also when they
     * nest inside paths) without touching them inside JSON strings.
     */
    private static String stripGraphValueSuffixes(String text) {
        if (text.indexOf("::") < 0) {
            return text;
        }
        var builder = new StringBuilder(text.length());
        for (var index = 0; index < text.length(); index++) {
            var character = text.charAt(index);
            if (character == '"') {
                var end = skipQuoted(text, index, '"');
                builder.append(text, index, Math.min(end + 1, text.length()));
                index = end;
                continue;
            }
            if (character == ':' && index + 1 < text.length() && text.charAt(index + 1) == ':') {
                var rest = text.substring(index + 2);
                var suffix = graphValueSuffix(rest);
                if (suffix != null) {
                    index += 1 + suffix.length();
                    continue;
                }
            }
            builder.append(character);
        }
        return builder.toString();
    }

    private static String graphValueSuffix(String rest) {
        for (var suffix : GRAPH_VALUE_SUFFIXES) {
            if (rest.startsWith(suffix) && isKeywordBoundaryAfter(rest, suffix.length())) {
                return suffix;
            }
        }
        return null;
    }
}
