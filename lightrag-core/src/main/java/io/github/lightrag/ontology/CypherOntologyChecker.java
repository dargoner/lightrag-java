package io.github.lightrag.ontology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lexical, pure-function validator for model-generated read-only Cypher,
 * scoped to an applied ontology snapshot plus a physical graph descriptor.
 *
 * <p>Checks, in order: (1) read-only - write or unsupported keywords
 * (CREATE/MERGE/SET/DELETE/REMOVE/DROP/DETACH/FOREACH/CALL/LOAD/APOC) are
 * rejected; (2) the query must contain a RETURN clause; (3) physical label
 * whitelist - every {@code :Label} scanned outside string literals and
 * comments must be in {@link PhysicalGraphProfile#nodeLabels()} or
 * {@link PhysicalGraphProfile#relationshipTypes()} depending on the
 * enclosing pattern position, so hallucinated physical labels such as
 * {@code :Person} are rejected; (4) business type values - equality and IN
 * predicates on the profile type property must resolve to allowed values of
 * {@link CypherOntologyAllowlist} (string literals and {@code $parameter}
 * values are both resolved; parameters must be supplied).
 *
 * <p>The implementation is deliberately lexical rather than a full Cypher
 * parser. A type condition that cannot be statically resolved (function or
 * expression operands, unsupported operators, missing parameters) is
 * reported through {@link CheckResult#unverified()} and keeps {@code ok}
 * false - <em>anything unverifiable is not a pass</em>. Tokens that cannot be
 * attributed to a pattern position land in {@link CheckResult#warnings()}
 * and do not block, since lexically ambiguous but harmless text is tolerated
 * ({@code :Label} inside a map literal is a map key, not a pattern). Write
 * keyword and missing-RETURN violations are reported through
 * {@code unverified} as well, because the result shape is frozen to six
 * fields and both make {@code ok} false the same way.
 *
 * <p>Pure function: no storage, no network, no state.
 */
public final class CypherOntologyChecker {

    private static final String[] WRITE_KEYWORDS = {
        "CREATE", "MERGE", "SET", "DELETE", "REMOVE", "DROP", "DETACH", "FOREACH", "CALL", "LOAD"
    };

    private static final Pattern APOC = Pattern.compile("(?<![A-Za-z0-9_$])apoc\\s*\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern RETURN_CLAUSE = Pattern.compile("(?<![A-Za-z0-9_$])RETURN(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private CypherOntologyChecker() {}

    /**
     * Outcome of a lexical check.
     *
     * @param ok                true only when nothing violated and nothing was left unverifiable
     * @param unknownLabels     node-position physical labels outside the profile; raw as written
     * @param unknownRelTypes   relationship-position physical types outside the profile; raw as written
     * @param unknownTypeValues type-property values outside the allowlist; raw literal/parameter values
     * @param unverified        statically unresolvable conditions and definite violations (write keywords, missing RETURN)
     * @param warnings          lexically unattributable tokens; informational, never block
     */
    public record CheckResult(
        boolean ok,
        List<String> unknownLabels,
        List<String> unknownRelTypes,
        List<String> unknownTypeValues,
        List<String> unverified,
        List<String> warnings
    ) {
        public CheckResult {
            unknownLabels = unknownLabels == null ? List.of() : List.copyOf(unknownLabels);
            unknownRelTypes = unknownRelTypes == null ? List.of() : List.copyOf(unknownRelTypes);
            unknownTypeValues = unknownTypeValues == null ? List.of() : List.copyOf(unknownTypeValues);
            unverified = unverified == null ? List.of() : List.copyOf(unverified);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /**
     * Checks a generated Cypher statement against the allowlist and profile.
     *
     * @param cypher     the generated statement; null and blank behave as a statement without RETURN
     * @param parameters parameter values supplied alongside the statement; may be null
     * @param allowlist  business values allowed on the type property
     * @param profile    physical descriptor of the configured graph backend
     * @return the check outcome; {@code ok} requires every check to pass and nothing to be unverified
     */
    public static CheckResult check(
        String cypher,
        Map<String, Object> parameters,
        CypherOntologyAllowlist allowlist,
        PhysicalGraphProfile profile
    ) {
        Objects.requireNonNull(allowlist, "allowlist");
        Objects.requireNonNull(profile, "profile");
        String text = cypher == null ? "" : cypher;
        Map<String, Object> params = parameters == null ? Map.of() : parameters;

        Masks masks = maskFor(text, profile.typePropertyName());
        LinkedHashSet<String> unknownLabels = new LinkedHashSet<>();
        LinkedHashSet<String> unknownRelTypes = new LinkedHashSet<>();
        LinkedHashSet<String> unknownTypeValues = new LinkedHashSet<>();
        LinkedHashSet<String> unverified = new LinkedHashSet<>();
        LinkedHashSet<String> warnings = new LinkedHashSet<>();

        checkReadOnly(masks.structure(), unverified);
        if (!RETURN_CLAUSE.matcher(masks.structure()).find()) {
            unverified.add("query must contain a RETURN clause");
        }
        scanLabels(masks.structure(), profile, unknownLabels, unknownRelTypes, warnings);
        scanTypeConditions(
            masks, text, params, allowlist, unknownTypeValues, unverified);

        boolean ok = unknownLabels.isEmpty()
            && unknownRelTypes.isEmpty()
            && unknownTypeValues.isEmpty()
            && unverified.isEmpty();
        return new CheckResult(
            ok,
            List.copyOf(unknownLabels),
            List.copyOf(unknownRelTypes),
            List.copyOf(unknownTypeValues),
            List.copyOf(unverified),
            List.copyOf(warnings));
    }

    private static void checkReadOnly(String structure, LinkedHashSet<String> unverified) {
        for (String keyword : WRITE_KEYWORDS) {
            Pattern pattern = Pattern.compile(
                "(?<![A-Za-z0-9_$])" + keyword + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
            if (pattern.matcher(structure).find()) {
                unverified.add("write or unsupported keyword: " + keyword);
            }
        }
        if (APOC.matcher(structure).find()) {
            unverified.add("write or unsupported keyword: APOC");
        }
    }

    private static void scanLabels(
        String structure,
        PhysicalGraphProfile profile,
        LinkedHashSet<String> unknownLabels,
        LinkedHashSet<String> unknownRelTypes,
        LinkedHashSet<String> warnings
    ) {
        Deque<Character> brackets = new ArrayDeque<>();
        int length = structure.length();
        for (int i = 0; i < length; i++) {
            char current = structure.charAt(i);
            switch (current) {
                case '(', '[', '{' -> brackets.push(current);
                case ')' -> popIf(brackets, '(');
                case ']' -> popIf(brackets, '[');
                case '}' -> popIf(brackets, '{');
                case ':' -> {
                    LabelRead label = readLabel(structure, i + 1);
                    if (label == null) {
                        continue;
                    }
                    i = label.end() - 1;
                    char context = brackets.peek() == null ? ' ' : brackets.peek();
                    if (context == '{') {
                        // map literal key, not a pattern label
                        continue;
                    }
                    if (context == '[') {
                        if (!profile.relationshipTypes().contains(label.raw())) {
                            unknownRelTypes.add(label.raw());
                        }
                    } else if (context == '(') {
                        if (!profile.nodeLabels().contains(label.raw())) {
                            unknownLabels.add(label.raw());
                        }
                    } else if (!profile.nodeLabels().contains(label.raw())
                        && !profile.relationshipTypes().contains(label.raw())) {
                        unknownLabels.add(label.raw());
                        warnings.add(
                            "label ':" + label.raw()
                                + "' appears outside a node or relationship pattern");
                    }
                }
                default -> {
                    // ordering and other characters carry no labels
                }
            }
        }
    }

    private static void popIf(Deque<Character> brackets, char expected) {
        if (brackets.peek() != null && brackets.peek() == expected) {
            brackets.pop();
        }
    }

    private record LabelRead(String raw, int end) {}

    private static LabelRead readLabel(String structure, int start) {
        int length = structure.length();
        if (start >= length) {
            return null;
        }
        if (structure.charAt(start) == '`') {
            int end = structure.indexOf('`', start + 1);
            if (end < 0) {
                return null;
            }
            return new LabelRead(structure.substring(start + 1, end), end + 1);
        }
        Matcher matcher = IDENTIFIER.matcher(structure);
        if (matcher.find(start) && matcher.start() == start) {
            return new LabelRead(matcher.group(), matcher.end());
        }
        return null;
    }

    private static void scanTypeConditions(
        Masks masks,
        String original,
        Map<String, Object> parameters,
        CypherOntologyAllowlist allowlist,
        LinkedHashSet<String> unknownTypeValues,
        LinkedHashSet<String> unverified
    ) {
        String values = masks.values();
        String property = masks.typePropertyName();
        Pattern access = Pattern.compile(
            "(?<![A-Za-z0-9_$])([A-Za-z_][A-Za-z0-9_]*)\\.(`?)"
                + Pattern.quote(property) + "(\\2)(?![A-Za-z0-9_])");
        Matcher matcher = access.matcher(values);
        List<Literal> literals = scanLiterals(original, masks);
        while (matcher.find()) {
            if (masks.inString()[matcher.start()]) {
                continue;
            }
            int after = skipWhitespace(values, matcher.end());
            boolean handled = false;
            if (after < values.length()) {
                char current = values.charAt(after);
                if (isOperatorChar(current)) {
                    handled = true;
                    String operator = readOperator(values, after);
                    if ("=".equals(operator)) {
                        verifyOperand(
                            values, literals, skipWhitespace(values, after + operator.length()),
                            "right-hand side", parameters, allowlist, unknownTypeValues, unverified);
                    } else {
                        unverified.add(
                            "type condition is not statically verifiable: unsupported operator '"
                                + operator + "'");
                    }
                } else if (isWordAt(values, after, "IN")) {
                    handled = true;
                    verifyInOperand(
                        values, literals, skipWhitespace(values, after + 2),
                        parameters, allowlist, unknownTypeValues, unverified);
                } else if (isWordAt(values, after, "CONTAINS")
                    || isWordAt(values, after, "STARTS")
                    || isWordAt(values, after, "ENDS")) {
                    handled = true;
                    unverified.add(
                        "type condition is not statically verifiable: unsupported operator '"
                            + readWord(values, after) + "'");
                }
            }
            if (!handled) {
                // The access may sit on the right-hand side: '<operand> = n.type'
                int before = skipWhitespaceBackwards(values, matcher.start() - 1);
                if (before >= 0 && isOperatorChar(values.charAt(before))) {
                    String operator = readOperatorBackwards(values, before);
                    if ("=".equals(operator)) {
                        verifyOperand(
                            values, literals,
                            operandStart(values, skipWhitespaceBackwards(values, before - 1)),
                            "left-hand side", parameters, allowlist, unknownTypeValues, unverified);
                    } else {
                        unverified.add(
                            "type condition is not statically verifiable: unsupported operator '"
                                + operator + "'");
                    }
                }
            }
        }
    }

    private static void verifyOperand(
        String values,
        List<Literal> literals,
        int position,
        String side,
        Map<String, Object> parameters,
        CypherOntologyAllowlist allowlist,
        LinkedHashSet<String> unknownTypeValues,
        LinkedHashSet<String> unverified
    ) {
        if (position < 0 || position >= values.length()) {
            unverified.add(
                "type condition is not statically verifiable: " + side
                    + " is not a string literal or parameter");
            return;
        }
        char first = values.charAt(position);
        if (first == '\'' || first == '"') {
            Literal literal = literalAt(literals, position);
            if (literal == null) {
                unverified.add(
                    "type condition is not statically verifiable: " + side
                        + " string literal is not terminated");
                return;
            }
            verifyValue(literal.value(), allowlist, unknownTypeValues);
            return;
        }
        if (first == '$') {
            String name = readParameterName(values, position);
            if (name == null) {
                unverified.add(
                    "type condition is not statically verifiable: malformed parameter on the "
                        + side);
                return;
            }
            verifyParameterScalar(name, parameters, allowlist, unknownTypeValues, unverified);
            return;
        }
        unverified.add(
            "type condition is not statically verifiable: " + side
                + " is not a string literal or parameter");
    }

    private static void verifyInOperand(
        String values,
        List<Literal> literals,
        int position,
        Map<String, Object> parameters,
        CypherOntologyAllowlist allowlist,
        LinkedHashSet<String> unknownTypeValues,
        LinkedHashSet<String> unverified
    ) {
        if (position >= values.length()) {
            unverified.add(
                "type condition is not statically verifiable: IN list is missing");
            return;
        }
        char first = values.charAt(position);
        if (first == '[') {
            int cursor = position + 1;
            while (true) {
                cursor = skipWhitespace(values, cursor);
                if (cursor >= values.length()) {
                    unverified.add(
                        "type condition is not statically verifiable: IN list is not terminated");
                    return;
                }
                char current = values.charAt(cursor);
                if (current == ']') {
                    return;
                }
                if (current == ',' ) {
                    cursor++;
                    continue;
                }
                Literal literal = literalAt(literals, cursor);
                if (literal != null) {
                    verifyValue(literal.value(), allowlist, unknownTypeValues);
                    cursor = literal.end();
                    continue;
                }
                if (current == '$') {
                    String name = readParameterName(values, cursor);
                    if (name == null) {
                        unverified.add(
                            "type condition is not statically verifiable: malformed parameter in"
                                + " the IN list");
                        return;
                    }
                    verifyParameterScalar(
                        name, parameters, allowlist, unknownTypeValues, unverified);
                    cursor += 1 + name.length();
                    continue;
                }
                unverified.add(
                    "type condition is not statically verifiable: IN list element is not a"
                        + " string literal or parameter");
                return;
            }
        }
        if (first == '$') {
            String name = readParameterName(values, position);
            if (name == null) {
                unverified.add(
                    "type condition is not statically verifiable: malformed parameter for IN");
                return;
            }
            verifyParameterList(name, parameters, allowlist, unknownTypeValues, unverified);
            return;
        }
        unverified.add(
            "type condition is not statically verifiable: IN right-hand side is not a list"
                + " literal or parameter");
    }

    private static void verifyValue(
        String value, CypherOntologyAllowlist allowlist, LinkedHashSet<String> unknownTypeValues) {
        if (value != null && !allowlist.containsTypeValue(value)) {
            unknownTypeValues.add(value);
        }
    }

    private static void verifyParameterScalar(
        String name,
        Map<String, Object> parameters,
        CypherOntologyAllowlist allowlist,
        LinkedHashSet<String> unknownTypeValues,
        LinkedHashSet<String> unverified
    ) {
        if (!parameters.containsKey(name)) {
            unverified.add("parameter '$" + name + "' is not provided");
            return;
        }
        Object value = parameters.get(name);
        if (value == null) {
            unverified.add("parameter '$" + name + "' is null");
            return;
        }
        if (value instanceof String text) {
            verifyValue(text, allowlist, unknownTypeValues);
            return;
        }
        unverified.add(
            "type condition is not statically verifiable: parameter '$" + name
                + "' value is not a string");
    }

    private static void verifyParameterList(
        String name,
        Map<String, Object> parameters,
        CypherOntologyAllowlist allowlist,
        LinkedHashSet<String> unknownTypeValues,
        LinkedHashSet<String> unverified
    ) {
        if (!parameters.containsKey(name)) {
            unverified.add("parameter '$" + name + "' is not provided");
            return;
        }
        Object value = parameters.get(name);
        if (value == null) {
            unverified.add("parameter '$" + name + "' is null");
            return;
        }
        if (value instanceof String text) {
            verifyValue(text, allowlist, unknownTypeValues);
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object element : collection) {
                if (element instanceof String text) {
                    verifyValue(text, allowlist, unknownTypeValues);
                } else {
                    unverified.add(
                        "type condition is not statically verifiable: parameter '$" + name
                            + "' list element is not a string");
                }
            }
            return;
        }
        unverified.add(
            "type condition is not statically verifiable: parameter '$" + name
                + "' value is not a string or string list");
    }

    private record Literal(int start, int end, String value) {}

    private static Literal literalAt(List<Literal> literals, int position) {
        for (Literal literal : literals) {
            if (literal.start() == position || literal.end() - 1 == position) {
                return literal;
            }
        }
        return null;
    }

    /**
     * Walks back to the start of a right-anchored operand: the {@code $} of a
     * parameter, or the end position itself for literals and other tokens.
     */
    private static int operandStart(String values, int endPosition) {
        int cursor = endPosition;
        while (cursor >= 0 && isIdentifierChar(values.charAt(cursor))) {
            cursor--;
        }
        if (cursor >= 0 && values.charAt(cursor) == '$') {
            return cursor;
        }
        return endPosition;
    }

    private static List<Literal> scanLiterals(String original, Masks masks) {
        List<Literal> literals = new ArrayList<>();
        int length = original.length();
        for (int i = 0; i < length; i++) {
            if (!masks.inString()[i] || (i > 0 && masks.inString()[i - 1])) {
                continue;
            }
            int quoteStart = i;
            if (original.charAt(i) != '\'' && original.charAt(i) != '"') {
                continue;
            }
            StringBuilder value = new StringBuilder();
            char quote = original.charAt(i);
            int cursor = i + 1;
            boolean terminated = false;
            while (cursor < length) {
                char current = original.charAt(cursor);
                if (current == '\\' && cursor + 1 < length) {
                    value.append(decodeEscape(original.charAt(cursor + 1)));
                    cursor += 2;
                    continue;
                }
                if (current == quote) {
                    if (cursor + 1 < length && original.charAt(cursor + 1) == quote) {
                        value.append(quote);
                        cursor += 2;
                        continue;
                    }
                    terminated = true;
                    cursor++;
                    break;
                }
                value.append(current);
                cursor++;
            }
            if (terminated) {
                literals.add(new Literal(quoteStart, cursor, value.toString()));
                i = cursor - 1;
            }
        }
        return literals;
    }

    private static char decodeEscape(char escaped) {
        return switch (escaped) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            case 'b' -> '\b';
            case 'f' -> '\f';
            default -> escaped;
        };
    }

    private static boolean isOperatorChar(char value) {
        return value == '<' || value == '>' || value == '=' || value == '!' || value == '~';
    }

    private static String readOperator(String values, int position) {
        int cursor = position;
        while (cursor < values.length() && isOperatorChar(values.charAt(cursor))) {
            cursor++;
        }
        return values.substring(position, cursor);
    }

    private static String readOperatorBackwards(String values, int position) {
        int cursor = position;
        while (cursor >= 0 && isOperatorChar(values.charAt(cursor))) {
            cursor--;
        }
        return values.substring(cursor + 1, position + 1);
    }

    private static String readWord(String values, int position) {
        Matcher matcher = IDENTIFIER.matcher(values);
        if (matcher.find(position) && matcher.start() == position) {
            return matcher.group();
        }
        return values.substring(position, Math.min(position + 1, values.length()));
    }

    private static String readParameterName(String values, int dollar) {
        Matcher matcher = IDENTIFIER.matcher(values);
        if (matcher.find(dollar + 1) && matcher.start() == dollar + 1) {
            return matcher.group();
        }
        return null;
    }

    private static boolean isWordAt(String text, int position, String word) {
        if (position + word.length() > text.length()) {
            return false;
        }
        if (!text.regionMatches(true, position, word, 0, word.length())) {
            return false;
        }
        int end = position + word.length();
        return (end >= text.length() || !isIdentifierChar(text.charAt(end)))
            && (position == 0 || !isIdentifierChar(text.charAt(position - 1)));
    }

    private static boolean isIdentifierChar(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    private static int skipWhitespace(String text, int position) {
        int cursor = position;
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static int skipWhitespaceBackwards(String text, int position) {
        int cursor = position;
        while (cursor >= 0 && Character.isWhitespace(text.charAt(cursor))) {
            cursor--;
        }
        return cursor;
    }

    /**
     * Length-preserving masks over the original text.
     *
     * @param structure       strings and comments blanked to spaces (for keyword/label scanning)
     * @param values          comments blanked to spaces, string literals kept (for value parsing)
     * @param inString        true at positions inside string literals
     * @param typePropertyName profile type property name carried for the access regex
     */
    private record Masks(String structure, String values, boolean[] inString, String typePropertyName) {}

    private static Masks maskFor(String text, String typePropertyName) {
        int length = text.length();
        StringBuilder structure = new StringBuilder(length);
        StringBuilder values = new StringBuilder(length);
        boolean[] inString = new boolean[length];
        int i = 0;
        while (i < length) {
            char current = text.charAt(i);
            if (current == '\'' || current == '"') {
                char quote = current;
                inString[i] = true;
                structure.append(' ');
                values.append(current);
                int cursor = i + 1;
                while (cursor < length) {
                    char inner = text.charAt(cursor);
                    if (inner == '\\' && cursor + 1 < length) {
                        inString[cursor] = true;
                        inString[cursor + 1] = true;
                        structure.append("  ");
                        values.append(inner).append(text.charAt(cursor + 1));
                        cursor += 2;
                        continue;
                    }
                    if (inner == quote) {
                        if (cursor + 1 < length && text.charAt(cursor + 1) == quote) {
                            inString[cursor] = true;
                            inString[cursor + 1] = true;
                            structure.append("  ");
                            values.append(inner).append(inner);
                            cursor += 2;
                            continue;
                        }
                        inString[cursor] = true;
                        structure.append(' ');
                        values.append(inner);
                        cursor++;
                        break;
                    }
                    inString[cursor] = true;
                    structure.append(' ');
                    values.append(inner);
                    cursor++;
                }
                i = cursor;
                continue;
            }
            if (current == '/' && i + 1 < length && text.charAt(i + 1) == '/') {
                while (i < length && text.charAt(i) != '\n') {
                    structure.append(' ');
                    values.append(' ');
                    i++;
                }
                continue;
            }
            if (current == '/' && i + 1 < length && text.charAt(i + 1) == '*') {
                structure.append("  ");
                values.append("  ");
                i += 2;
                while (i < length
                    && !(text.charAt(i) == '*' && i + 1 < length && text.charAt(i + 1) == '/')) {
                    structure.append(' ');
                    values.append(' ');
                    i++;
                }
                if (i < length) {
                    structure.append("  ");
                    values.append("  ");
                    i += 2;
                }
                continue;
            }
            structure.append(current);
            values.append(current);
            i++;
        }
        return new Masks(structure.toString(), values.toString(), inString, typePropertyName);
    }
}
