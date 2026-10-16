package io.github.lightrag.ontology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * predicates on the profile type property (including pattern property maps
 * such as {@code (n:Entity {type: 'Person'})}) must resolve to allowed values
 * of {@link CypherOntologyAllowlist} (string literals and {@code $parameter}
 * values are both resolved; parameters must be supplied; compound operands
 * and unsupported wrapper functions are rejected as unverifiable).
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
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*");

    private static final Set<String> SUBQUERY_KEYWORDS = Set.of(
        "MATCH", "OPTIONAL", "WHERE", "WITH", "RETURN", "UNWIND", "CALL", "UNION", "EXISTS");

    private static final Set<String> NON_FUNCTION_WORDS = Set.of(
        "WHERE", "AND", "OR", "NOT", "IN", "ON", "WHEN", "THEN", "ELSE", "CASE", "END",
        "WITH", "MATCH", "OPTIONAL", "RETURN", "ANY", "ALL", "NONE", "SINGLE", "EXISTS",
        "COUNT", "FILTER", "UNWIND", "UNION", "CALL", "DISTINCT", "BY", "AS");

    /** Wrappers that do not change which values can match under the case-insensitive allowlist. */
    private static final Set<String> TRANSPARENT_WRAPPERS = Set.of("TOLOWER", "TOUPPER");

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
        List<Literal> literals = scanLiterals(text, masks);
        LinkedHashSet<String> unknownLabels = new LinkedHashSet<>();
        LinkedHashSet<String> unknownRelTypes = new LinkedHashSet<>();
        LinkedHashSet<String> unknownTypeValues = new LinkedHashSet<>();
        LinkedHashSet<String> unverified = new LinkedHashSet<>();
        LinkedHashSet<String> warnings = new LinkedHashSet<>();

        checkReadOnly(masks, unverified);
        if (firstMatchOutsideBackticks(RETURN_CLAUSE, masks) == null) {
            unverified.add("query must contain a RETURN clause");
        }
        scanLabels(masks, profile, unknownLabels, unknownRelTypes, warnings);
        scanPatternMapTypeValues(
            masks, literals, params, allowlist, unknownTypeValues, unverified);
        scanTypeConditions(
            masks, literals, params, allowlist, unknownTypeValues, unverified);

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

    private static Integer firstMatchOutsideBackticks(Pattern pattern, Masks masks) {
        Matcher matcher = pattern.matcher(masks.structure());
        boolean[] inBacktick = masks.inBacktick();
        while (matcher.find()) {
            if (!inBacktick[matcher.start()]) {
                return matcher.start();
            }
        }
        return null;
    }

    private static void checkReadOnly(Masks masks, LinkedHashSet<String> unverified) {
        String structure = masks.structure();
        boolean[] inBacktick = masks.inBacktick();
        for (String keyword : WRITE_KEYWORDS) {
            Pattern pattern = Pattern.compile(
                "(?<![A-Za-z0-9_$])" + keyword + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(structure);
            while (matcher.find()) {
                if (!inBacktick[matcher.start()]) {
                    unverified.add("write or unsupported keyword: " + keyword);
                    break;
                }
            }
        }
        Matcher apoc = APOC.matcher(structure);
        while (apoc.find()) {
            if (!inBacktick[apoc.start()]) {
                unverified.add("write or unsupported keyword: APOC");
                break;
            }
        }
    }

    private static void scanLabels(
        Masks masks,
        PhysicalGraphProfile profile,
        LinkedHashSet<String> unknownLabels,
        LinkedHashSet<String> unknownRelTypes,
        LinkedHashSet<String> warnings
    ) {
        String structure = masks.structure();
        boolean[] inBacktick = masks.inBacktick();
        Deque<Brace> brackets = new ArrayDeque<>();
        int length = structure.length();
        for (int i = 0; i < length; i++) {
            if (inBacktick[i]) {
                continue;
            }
            char current = structure.charAt(i);
            switch (current) {
                case '(' -> brackets.push(new Brace('(', false));
                case '[' -> brackets.push(new Brace('[', false));
                case '{' -> brackets.push(new Brace('{', isSubqueryBrace(structure, i + 1)));
                case ')' -> popIf(brackets, '(');
                case ']' -> popIf(brackets, '[');
                case '}' -> popIf(brackets, '{');
                case ':' -> {
                    LabelRead label = readLabel(structure, labelStart(structure, i));
                    if (label == null) {
                        continue;
                    }
                    i = label.end() - 1;
                    Brace enclosing = brackets.peek();
                    char context = enclosing == null ? ' ' : enclosing.kind();
                    if (context == '{' && !enclosing.subquery()) {
                        // map literal key, not a pattern label
                        continue;
                    }
                    if (context == '{') {
                        checkOutsidePattern(label.raw(), profile, unknownLabels, warnings);
                    } else if (context == '[') {
                        if (!profile.relationshipTypes().contains(label.raw())) {
                            unknownRelTypes.add(label.raw());
                        }
                        i = scanUnionRelTypes(
                            structure, label, profile, unknownRelTypes, i);
                    } else if (context == '(') {
                        if (!profile.nodeLabels().contains(label.raw())) {
                            unknownLabels.add(label.raw());
                        }
                    } else {
                        checkOutsidePattern(label.raw(), profile, unknownLabels, warnings);
                    }
                }
                default -> {
                    // ordering and other characters carry no labels
                }
            }
        }
    }

    /**
     * Reads a relationship type alternation such as {@code r:RELATION|FORBIDDEN},
     * checking every branch. Returns the index of the last consumed character.
     */
    private static int scanUnionRelTypes(
        String structure,
        LabelRead first,
        PhysicalGraphProfile profile,
        LinkedHashSet<String> unknownRelTypes,
        int consumed
    ) {
        int cursor = first.end();
        while (true) {
            int pipe = skipWhitespace(structure, cursor);
            if (pipe >= structure.length() || structure.charAt(pipe) != '|') {
                return consumed;
            }
            int next = skipWhitespace(structure, pipe + 1);
            if (next < structure.length() && structure.charAt(next) == ':') {
                next = skipWhitespace(structure, next + 1);
            }
            if (next < structure.length() && structure.charAt(next) == '!') {
                next++;
            }
            LabelRead label = readLabel(structure, next);
            if (label == null) {
                return consumed;
            }
            if (!profile.relationshipTypes().contains(label.raw())) {
                unknownRelTypes.add(label.raw());
            }
            cursor = label.end();
            consumed = label.end() - 1;
        }
    }

    private static int labelStart(String structure, int colon) {
        int start = skipWhitespace(structure, colon + 1);
        if (start < structure.length() && structure.charAt(start) == '!') {
            start++;
        }
        return start;
    }

    private static void checkOutsidePattern(
        String raw,
        PhysicalGraphProfile profile,
        LinkedHashSet<String> unknownLabels,
        LinkedHashSet<String> warnings
    ) {
        if (!profile.nodeLabels().contains(raw) && !profile.relationshipTypes().contains(raw)) {
            unknownLabels.add(raw);
            warnings.add(
                "label ':" + raw + "' appears outside a node or relationship pattern");
        }
    }

    /**
     * A {@code &#123;} brace opening a subquery body (e.g. {@code EXISTS &#123; MATCH ... &#125;})
     * rather than a map literal. Decided by the first word inside: a subquery clause
     * keyword not followed by {@code :} (which would make it a map key).
     */
    private static boolean isSubqueryBrace(String structure, int afterBrace) {
        int cursor = skipWhitespace(structure, afterBrace);
        Matcher matcher = IDENTIFIER.matcher(structure);
        if (cursor >= structure.length()
            || !matcher.find(cursor)
            || matcher.start() != cursor) {
            return false;
        }
        int end = matcher.end();
        if (end < structure.length() && structure.charAt(end) == ':') {
            return false;
        }
        return SUBQUERY_KEYWORDS.contains(matcher.group().toUpperCase());
    }

    private record Brace(char kind, boolean subquery) {}

    private static void popIf(Deque<Brace> brackets, char expected) {
        if (brackets.peek() != null && brackets.peek().kind() == expected) {
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

    /**
     * Reads an identifier token (plain or backtick-quoted, doubling escaped)
     * starting exactly at {@code start}; -1 when no token starts there.
     */
    private static int readTokenEnd(String values, int start) {
        int length = values.length();
        if (start < 0 || start >= length) {
            return -1;
        }
        char first = values.charAt(start);
        if (first == '`') {
            int cursor = start + 1;
            while (cursor < length) {
                if (values.charAt(cursor) == '`') {
                    if (cursor + 1 < length && values.charAt(cursor + 1) == '`') {
                        cursor += 2;
                        continue;
                    }
                    return cursor + 1;
                }
                cursor++;
            }
            return -1;
        }
        if (!isIdentifierStart(first)) {
            return -1;
        }
        int cursor = start + 1;
        while (cursor < length && isIdentifierChar(values.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static String tokenContent(String values, int start, int end) {
        if (values.charAt(start) == '`') {
            return values.substring(start + 1, end - 1).replace("``", "`");
        }
        return values.substring(start, end);
    }

    /** True when {@code i} is the opening backtick of a backtick-quoted token. */
    private static boolean isBacktickTokenStart(String values, boolean[] inBacktick, int i) {
        return values.charAt(i) == '`' && (i == 0 || !inBacktick[i - 1]);
    }

    private static boolean isIdentifierStart(char value) {
        return Character.isLetter(value) || value == '_';
    }

    /** Start of the identifier ending just before {@code endExclusive}; -1 when none. */
    private static int identifierStartBefore(String values, int endExclusive) {
        int cursor = endExclusive - 1;
        if (cursor < 0 || !isIdentifierChar(values.charAt(cursor))) {
            return -1;
        }
        while (cursor >= 0 && isIdentifierChar(values.charAt(cursor))) {
            cursor--;
        }
        return cursor + 1;
    }

    /** Position of the {@code )} closing the call containing {@code position}; -1 when unbalanced. */
    private static int callEndAfter(String values, List<Literal> literals, int position) {
        int length = values.length();
        int depth = 0;
        int cursor = position;
        while (cursor < length) {
            Literal literal = literalAt(literals, cursor);
            if (literal != null && literal.start() == cursor) {
                cursor = literal.end();
                continue;
            }
            char current = values.charAt(cursor);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                if (depth == 0) {
                    return cursor;
                }
                depth--;
            }
            cursor++;
        }
        return -1;
    }

    /**
     * Verifies type-property values set inside a node or relationship pattern
     * map (e.g. {@code (n:Entity {type: 'Person'})}), which are equality
     * filters and must obey the allowlist like any other type condition.
     */
    private static void scanPatternMapTypeValues(
        Masks masks,
        List<Literal> literals,
        Map<String, Object> parameters,
        CypherOntologyAllowlist allowlist,
        LinkedHashSet<String> unknownTypeValues,
        LinkedHashSet<String> unverified
    ) {
        String values = masks.values();
        boolean[] inString = masks.inString();
        boolean[] inBacktick = masks.inBacktick();
        Deque<Character> brackets = new ArrayDeque<>();
        int length = values.length();
        for (int i = 0; i < length; i++) {
            if (inString[i] || (inBacktick[i] && !isBacktickTokenStart(values, inBacktick, i))) {
                continue;
            }
            char current = values.charAt(i);
            switch (current) {
                case '(' -> brackets.push('(');
                case '[' -> brackets.push('[');
                case '{' -> {
                    boolean patternMap = brackets.peek() != null
                        && (brackets.peek() == '(' || brackets.peek() == '[')
                        && !isSubqueryBrace(values, i + 1);
                    if (patternMap) {
                        i = scanMapEntries(
                            masks, literals, i, parameters, allowlist,
                            unknownTypeValues, unverified);
                    }
                }
                case ')' -> popIfChar(brackets, '(');
                case ']' -> popIfChar(brackets, '[');
                default -> {
                    // only braces open maps and patterns
                }
            }
        }
    }

    /**
     * Verifies type-property entries at the top level of one pattern map;
     * returns the index of its closing brace.
     */
    private static int scanMapEntries(
        Masks masks,
        List<Literal> literals,
        int openBrace,
        Map<String, Object> parameters,
        CypherOntologyAllowlist allowlist,
        LinkedHashSet<String> unknownTypeValues,
        LinkedHashSet<String> unverified
    ) {
        String values = masks.values();
        String property = masks.typePropertyName();
        int length = values.length();
        int cursor = openBrace + 1;
        int depth = 0;
        while (cursor < length) {
            Literal literal = literalAt(literals, cursor);
            if (literal != null && literal.start() == cursor) {
                cursor = literal.end();
                continue;
            }
            char current = values.charAt(cursor);
            if (current == '{') {
                depth++;
                cursor++;
                continue;
            }
            if (current == '}') {
                if (depth == 0) {
                    return cursor;
                }
                depth--;
                cursor++;
                continue;
            }
            if (depth == 0) {
                int tokenEnd = readTokenEnd(values, cursor);
                if (tokenEnd > 0) {
                    int after = skipWhitespace(values, tokenEnd);
                    if (after < length
                        && values.charAt(after) == ':'
                        && property.equals(tokenContent(values, cursor, tokenEnd))) {
                        verifyOperand(
                            values, literals, skipWhitespace(values, after + 1),
                            "map property value", parameters, allowlist,
                            unknownTypeValues, unverified);
                    }
                    cursor = tokenEnd;
                    continue;
                }
            }
            cursor++;
        }
        return length - 1;
    }

    private static void popIfChar(Deque<Character> brackets, char expected) {
        if (brackets.peek() != null && brackets.peek() == expected) {
            brackets.pop();
        }
    }

    private static void scanTypeConditions(
        Masks masks,
        List<Literal> literals,
        Map<String, Object> parameters,
        CypherOntologyAllowlist allowlist,
        LinkedHashSet<String> unknownTypeValues,
        LinkedHashSet<String> unverified
    ) {
        String values = masks.values();
        String property = masks.typePropertyName();
        boolean[] inString = masks.inString();
        boolean[] inBacktick = masks.inBacktick();
        int length = values.length();
        for (int i = 0; i < length; i++) {
            if (inString[i] || (inBacktick[i] && !isBacktickTokenStart(values, inBacktick, i))) {
                continue;
            }
            int accessEnd = matchTypeAccess(values, i, property, literals);
            if (accessEnd < 0) {
                continue;
            }
            inspectTypeAccess(
                values, literals, i, accessEnd, parameters, allowlist,
                unknownTypeValues, unverified);
            i = accessEnd - 1;
        }
    }

    /**
     * Matches a type-property access starting at {@code start}: {@code v.prop},
     * {@code `v`.prop} (whitespace around the dot tolerated), or a literal
     * subscript {@code v['prop']}. Dynamic subscripts cannot be attributed and
     * return -1.
     */
    private static int matchTypeAccess(
        String values, int start, String property, List<Literal> literals) {
        int length = values.length();
        int tokenEnd = readTokenEnd(values, start);
        if (tokenEnd < 0) {
            return -1;
        }
        int cursor = skipWhitespace(values, tokenEnd);
        if (cursor >= length) {
            return -1;
        }
        char next = values.charAt(cursor);
        if (next == '.') {
            int propertyStart = skipWhitespace(values, cursor + 1);
            int propertyEnd = readTokenEnd(values, propertyStart);
            if (propertyEnd < 0) {
                return -1;
            }
            return property.equals(tokenContent(values, propertyStart, propertyEnd))
                ? propertyEnd
                : -1;
        }
        if (next == '[') {
            int literalStart = skipWhitespace(values, cursor + 1);
            Literal literal = literalAt(literals, literalStart);
            if (literal == null
                || literal.start() != literalStart
                || !property.equals(literal.value())) {
                return -1;
            }
            int close = skipWhitespace(values, literal.end());
            if (close >= length || values.charAt(close) != ']') {
                return -1;
            }
            return close + 1;
        }
        return -1;
    }

    private static void inspectTypeAccess(
        String values,
        List<Literal> literals,
        int accessStart,
        int accessEnd,
        Map<String, Object> parameters,
        CypherOntologyAllowlist allowlist,
        LinkedHashSet<String> unknownTypeValues,
        LinkedHashSet<String> unverified
    ) {
        int length = values.length();

        // Walk out of any grouping / function parentheses wrapping the access.
        int wrapperEnd = skipWhitespaceBackwards(values, accessStart - 1);
        List<String> wrappers = new ArrayList<>();
        while (wrapperEnd >= 0 && values.charAt(wrapperEnd) == '(') {
            int nameStart = identifierStartBefore(values, wrapperEnd);
            if (nameStart >= 0) {
                String name = values.substring(nameStart, wrapperEnd);
                if (!NON_FUNCTION_WORDS.contains(name.toUpperCase())) {
                    wrappers.add(name.toUpperCase());
                }
                wrapperEnd = skipWhitespaceBackwards(values, nameStart - 1);
            } else {
                wrapperEnd = skipWhitespaceBackwards(values, wrapperEnd - 1);
            }
        }

        // Step over the matching closing parentheses on the right.
        int right = skipWhitespace(values, accessEnd);
        while (right < length && values.charAt(right) == ')') {
            right = skipWhitespace(values, right + 1);
        }

        boolean resolved = false;
        if (right < length) {
            char current = values.charAt(right);
            if (isOperatorChar(current)) {
                resolved = true;
                String operator = readOperator(values, right);
                if ("=".equals(operator)) {
                    verifyOperand(
                        values, literals, skipWhitespace(values, right + operator.length()),
                        "right-hand side", parameters, allowlist, unknownTypeValues, unverified);
                } else {
                    unverified.add(
                        "type condition is not statically verifiable: unsupported operator '"
                            + operator + "'");
                }
            } else if (isWordAt(values, right, "IN")) {
                resolved = true;
                verifyInOperand(
                    values, literals, skipWhitespace(values, right + 2),
                    parameters, allowlist, unknownTypeValues, unverified);
            } else if (isWordAt(values, right, "CONTAINS")
                || isWordAt(values, right, "STARTS")
                || isWordAt(values, right, "ENDS")) {
                resolved = true;
                unverified.add(
                    "type condition is not statically verifiable: unsupported operator '"
                        + readWord(values, right) + "'");
            } else if (current == ',' && !wrappers.isEmpty()) {
                // The access is a non-final argument of a wrapping call; the
                // enclosing call cannot be statically resolved.
                int callEnd = callEndAfter(values, literals, right);
                int afterCall = callEnd < 0 ? -1 : skipWhitespace(values, callEnd + 1);
                if (afterCall >= 0 && afterCall < length
                    && (isOperatorChar(values.charAt(afterCall))
                        || isWordAt(values, afterCall, "IN")
                        || isWordAt(values, afterCall, "CONTAINS")
                        || isWordAt(values, afterCall, "STARTS")
                        || isWordAt(values, afterCall, "ENDS"))) {
                    resolved = true;
                    unverified.add(
                        "type condition is not statically verifiable: unsupported function '"
                            + wrappers.get(0).toLowerCase() + "'");
                }
            }
        }
        if (!resolved) {
            // The access may sit on the right-hand side: '<operand> = n.type'
            if (wrapperEnd >= 0 && isOperatorChar(values.charAt(wrapperEnd))) {
                resolved = true;
                String operator = readOperatorBackwards(values, wrapperEnd);
                if ("=".equals(operator)) {
                    int operandEnd =
                        skipWhitespaceBackwards(values, wrapperEnd - operator.length());
                    verifyOperand(
                        values, literals, operandStart(values, operandEnd),
                        "left-hand side", parameters, allowlist, unknownTypeValues, unverified);
                } else {
                    unverified.add(
                        "type condition is not statically verifiable: unsupported operator '"
                            + operator + "'");
                }
            } else {
                // No comparison follows: a projection such as RETURN n.type, not a condition.
                return;
            }
        }
        for (String wrapper : wrappers) {
            if (!TRANSPARENT_WRAPPERS.contains(wrapper)) {
                unverified.add(
                    "type condition is not statically verifiable: unsupported function '"
                        + wrapper.toLowerCase() + "'");
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
            if (hasCompoundContinuationAfter(values, literal.end())
                || hasCompoundContinuationBefore(values, literal.start())) {
                unverified.add(
                    "type condition is not statically verifiable: compound expression on the "
                        + side);
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
            if (hasCompoundContinuationAfter(values, position + 1 + name.length())
                || hasCompoundContinuationBefore(values, position)) {
                unverified.add(
                    "type condition is not statically verifiable: compound expression on the "
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
                    if (hasCompoundContinuationAfter(values, cursor + 1)) {
                        unverified.add(
                            "type condition is not statically verifiable: compound expression on"
                                + " the IN list");
                    }
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
            if (hasCompoundContinuationAfter(values, position + 1 + name.length())) {
                unverified.add(
                    "type condition is not statically verifiable: compound expression on"
                        + " the IN list");
                return;
            }
            verifyParameterList(name, parameters, allowlist, unknownTypeValues, unverified);
            return;
        }
        unverified.add(
            "type condition is not statically verifiable: IN right-hand side is not a list"
                + " literal or parameter");
    }

    private static boolean hasCompoundContinuationAfter(String values, int after) {
        int cursor = skipWhitespace(values, after);
        return cursor < values.length() && isCompoundContinuation(values.charAt(cursor));
    }

    private static boolean hasCompoundContinuationBefore(String values, int start) {
        int cursor = skipWhitespaceBackwards(values, start - 1);
        return cursor >= 0 && isCompoundContinuation(values.charAt(cursor));
    }

    private static boolean isCompoundContinuation(char value) {
        return value == '+' || value == '-' || value == '*' || value == '/'
            || value == '%' || value == '[';
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
                    if (original.charAt(cursor + 1) == 'u' && cursor + 6 <= length) {
                        int code = hexCode(original, cursor + 2, cursor + 6);
                        if (code >= 0) {
                            value.append((char) code);
                            cursor += 6;
                            continue;
                        }
                    }
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

    /** Value of the 4 hex digits in {@code [start, end)}; -1 when malformed. */
    private static int hexCode(String text, int start, int end) {
        int value = 0;
        for (int i = start; i < end; i++) {
            int digit = Character.digit(text.charAt(i), 16);
            if (digit < 0) {
                return -1;
            }
            value = value * 16 + digit;
        }
        return value;
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
     * @param inBacktick      true at positions inside backtick-quoted identifiers
     * @param typePropertyName profile type property name carried for the access scan
     */
    private record Masks(
        String structure, String values, boolean[] inString, boolean[] inBacktick,
        String typePropertyName) {}

    private static Masks maskFor(String text, String typePropertyName) {
        int length = text.length();
        StringBuilder structure = new StringBuilder(length);
        StringBuilder values = new StringBuilder(length);
        boolean[] inString = new boolean[length];
        boolean[] inBacktick = new boolean[length];
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
            if (current == '`') {
                // Backtick-quoted identifier: comment and string syntax inside must
                // not start their own scan; the content is kept as identifier text.
                inBacktick[i] = true;
                structure.append('`');
                values.append('`');
                int cursor = i + 1;
                while (cursor < length) {
                    char inner = text.charAt(cursor);
                    if (inner == '`') {
                        if (cursor + 1 < length && text.charAt(cursor + 1) == '`') {
                            inBacktick[cursor] = true;
                            inBacktick[cursor + 1] = true;
                            structure.append("``");
                            values.append("``");
                            cursor += 2;
                            continue;
                        }
                        inBacktick[cursor] = true;
                        structure.append('`');
                        values.append('`');
                        cursor++;
                        break;
                    }
                    inBacktick[cursor] = true;
                    structure.append(inner);
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
        return new Masks(
            structure.toString(), values.toString(), inString, inBacktick, typePropertyName);
    }
}
