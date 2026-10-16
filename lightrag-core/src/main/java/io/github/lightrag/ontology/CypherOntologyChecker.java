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
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lexical, pure-function validator for model-generated read-only Cypher,
 * scoped to an applied ontology snapshot plus a physical graph descriptor.
 *
 * <p>Checks, in order: (1) read-only - write or unsupported keywords
 * (CREATE/MERGE/SET/DELETE/REMOVE/DROP/DETACH/FOREACH/CALL/LOAD/INSERT/APOC)
 * are rejected; unicode escapes in code position are rejected as well, since
 * the backend pre-decodes them before lexing and they could split keywords or
 * re-open tokens; (2) the query must contain a RETURN clause; (3) physical label
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
        "CREATE", "MERGE", "SET", "DELETE", "REMOVE", "DROP", "DETACH", "FOREACH", "CALL", "LOAD",
        "INSERT"
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

    /**
     * Keywords that introduce an anonymous node pattern, so that an empty
     * paren header such as {@code MATCH (&#123;type: '...'&#125;)} is a pattern
     * while {@code RETURN (&#123;...&#125;)} or {@code count(&#123;...&#125;)} is plain data.
     */
    private static final Set<String> PATTERN_HEAD_KEYWORDS = Set.of("MATCH", "OPTIONAL");

    /** Wrappers that do not change which values can match under the case-insensitive allowlist. */
    private static final Set<String> TRANSPARENT_WRAPPERS = Set.of("TOLOWER", "TOUPPER");

    /**
     * Clause and connective keywords that end an expression at parenthesis
     * depth zero, so a compound chain terminated by one of these is a
     * projection and not a type condition.
     */
    private static final Set<String> EXPRESSION_BOUNDARY_KEYWORDS = Set.of(
        "AS", "WHERE", "RETURN", "WITH", "ORDER", "SKIP", "LIMIT", "UNION", "YIELD",
        "UNWIND", "MATCH", "OPTIONAL", "CALL", "AND", "OR", "XOR", "WHEN", "THEN", "ELSE", "END");

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
        checkUnicodeEscapesOutsideTokens(text, masks, unverified);
        if (firstMatchOutsideBackticks(RETURN_CLAUSE, masks) == null) {
            unverified.add("query must contain a RETURN clause");
        }
        scanLabels(masks, profile, unknownLabels, unknownRelTypes, warnings, unverified);
        scanPatternMapTypeValues(
            masks, literals, params, allowlist, unknownTypeValues, unverified);
        scanTypeConditions(
            masks, literals, params, allowlist, unknownTypeValues, unverified);
        scanTypeAliasUsages(
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

    /**
     * Rejects unicode escapes (&#92;uXXXX) outside string literals and quoted
     * identifiers. The backend pre-decodes such escapes before lexing, so a
     * code-position escape can split a keyword ({@code CRE&#92;u0041TE}) or turn
     * into an unattributable delimiter; nothing of that shape is verifiable.
     */
    private static void checkUnicodeEscapesOutsideTokens(
        String text, Masks masks, LinkedHashSet<String> unverified) {
        boolean[] inString = masks.inString();
        boolean[] inBacktick = masks.inBacktick();
        String values = masks.values();
        int length = text.length();
        for (int i = 0; i + 5 < length; i++) {
            if (inString[i] || inBacktick[i] || values.charAt(i) != '\\') {
                continue;
            }
            if (text.charAt(i + 1) == 'u' && hexCode(text, i + 2, i + 6) >= 0) {
                unverified.add(
                    "unicode escape outside a string literal or quoted identifier is not"
                        + " statically verifiable");
                return;
            }
        }
    }

    private static void scanLabels(
        Masks masks,
        PhysicalGraphProfile profile,
        LinkedHashSet<String> unknownLabels,
        LinkedHashSet<String> unknownRelTypes,
        LinkedHashSet<String> warnings,
        LinkedHashSet<String> unverified
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
                    Brace enclosing = brackets.peek();
                    char context = enclosing == null ? ' ' : enclosing.kind();
                    Consumer<String> branch =
                        labelBranch(context, profile, unknownLabels, unknownRelTypes);
                    int start = labelStart(structure, i);
                    if ((context == '(' || context == '[')
                        && start < length && structure.charAt(start) == '(') {
                        int groupEnd = scanLabelGroup(structure, start, branch, unverified);
                        if (groupEnd > start) {
                            i = scanUnionLabels(
                                structure, groupEnd + 1, branch, groupEnd, unverified);
                            continue;
                        }
                    }
                    LabelRead label = readLabel(structure, start);
                    if (label == null) {
                        if ((context != '{' || enclosing.subquery())
                            && start < length && structure.charAt(start) == '$') {
                            unverified.add("dynamic label is not statically verifiable");
                        }
                        continue;
                    }
                    i = label.end() - 1;
                    if (context == '{' && !enclosing.subquery()) {
                        // map literal key, not a pattern label
                        continue;
                    }
                    if (context == '{' || context == ' ') {
                        checkOutsidePattern(label.raw(), profile, unknownLabels, warnings);
                        i = scanUnionLabels(
                            structure, label.end(),
                            raw -> checkOutsidePattern(raw, profile, unknownLabels, warnings),
                            i, unverified);
                    } else {
                        branch.accept(label.raw());
                        i = scanUnionLabels(structure, label.end(), branch, i, unverified);
                    }
                }
                default -> {
                    i = scanIsLabelAt(
                        structure, i, brackets, profile, unknownLabels, unknownRelTypes,
                        unverified);
                }
            }
        }
    }

    /**
     * Handles an {@code IS} keyword at {@code position} as a label predicate
     * ({@code n IS Forbidden}), so its label expression is checked like any
     * pattern label. Comparison forms ({@code IS (NOT) NULL},
     * {@code IS TYPED STRING}, {@code IS NORMALIZED}) carry no label and are
     * skipped. Returns the last consumed character.
     */
    private static int scanIsLabelAt(
        String structure,
        int position,
        Deque<Brace> brackets,
        PhysicalGraphProfile profile,
        LinkedHashSet<String> unknownLabels,
        LinkedHashSet<String> unknownRelTypes,
        LinkedHashSet<String> unverified
    ) {
        if (structure.charAt(position) != 'I' && structure.charAt(position) != 'i') {
            return position;
        }
        if (position > 0 && isIdentifierChar(structure.charAt(position - 1))) {
            // Mid-token probe ("this", "axis"): the same word is handled from
            // its true start.
            return position;
        }
        int tokenEnd = readTokenEnd(structure, position);
        if (tokenEnd < 0 || !structure.substring(position, tokenEnd).equalsIgnoreCase("IS")) {
            return position;
        }
        int length = structure.length();
        int next = skipWhitespace(structure, tokenEnd);
        while (next < length && isLabelMarker(structure.charAt(next))) {
            next = skipWhitespace(structure, next + 1);
        }
        if (next >= length) {
            return position;
        }
        Brace enclosing = brackets.peek();
        char context = enclosing == null ? ' ' : enclosing.kind();
        Consumer<String> branch = labelBranch(context, profile, unknownLabels, unknownRelTypes);
        if (structure.charAt(next) == '(') {
            int groupEnd = scanLabelGroup(structure, next, branch, unverified);
            if (groupEnd <= next) {
                return position;
            }
            return scanUnionLabels(structure, groupEnd + 1, branch, groupEnd, unverified);
        }
        LabelRead label = readLabel(structure, next);
        if (label == null) {
            return position;
        }
        String upper = label.raw().toUpperCase();
        if ("NULL".equals(upper) || "NOT".equals(upper) || "TYPED".equals(upper)
            || "NORMALIZED".equals(upper)) {
            return position;
        }
        branch.accept(label.raw());
        return scanUnionLabels(structure, label.end(), branch, label.end() - 1, unverified);
    }

    private static Consumer<String> labelBranch(
        char context,
        PhysicalGraphProfile profile,
        LinkedHashSet<String> unknownLabels,
        LinkedHashSet<String> unknownRelTypes
    ) {
        return context == '['
            ? raw -> {
                if (!profile.relationshipTypes().contains(raw)) {
                    unknownRelTypes.add(raw);
                }
            }
            : raw -> {
                if (!profile.nodeLabels().contains(raw)) {
                    unknownLabels.add(raw);
                }
            };
    }

    /**
     * Reads a label expression tail such as {@code |Forbidden}, {@code &Forbidden},
     * {@code |!Forbidden}, {@code |:Forbidden} or {@code |(A|B)}, delivering every
     * branch to {@code branch}. Returns the index of the last consumed character.
     */
    private static int scanUnionLabels(
        String structure,
        int from,
        Consumer<String> branch,
        int consumed,
        LinkedHashSet<String> unverified
    ) {
        int length = structure.length();
        int cursor = from;
        while (true) {
            int separator = skipWhitespace(structure, cursor);
            if (separator >= length) {
                return consumed;
            }
            char current = structure.charAt(separator);
            if (current != '|' && current != '&') {
                return consumed;
            }
            int next = skipWhitespace(structure, separator + 1);
            while (next < length) {
                char marker = structure.charAt(next);
                if (isLabelMarker(marker)) {
                    next = skipWhitespace(structure, next + 1);
                    continue;
                }
                break;
            }
            if (next < length
                && (structure.charAt(next) == '|' || structure.charAt(next) == '&')) {
                // Markers such as "%" (any-label) sit between two separators;
                // the trailing separator still starts a union branch.
                cursor = next;
                continue;
            }
            if (next < length && structure.charAt(next) == '(') {
                int groupEnd = scanLabelGroup(structure, next, branch, unverified);
                if (groupEnd <= next) {
                    return consumed;
                }
                cursor = groupEnd + 1;
                consumed = groupEnd;
                continue;
            }
            if (next < length && structure.charAt(next) == '$') {
                unverified.add("dynamic label is not statically verifiable");
                return consumed;
            }
            LabelRead label = readLabel(structure, next);
            if (label == null) {
                return consumed;
            }
            branch.accept(label.raw());
            cursor = label.end();
            consumed = label.end() - 1;
        }
    }

    /**
     * Reads a parenthesized label expression such as {@code :(RELATION|FORBIDDEN)},
     * including negated and nested groups, checking every branch. Returns the index
     * of the closing parenthesis, or {@code openParen} when the group cannot be parsed.
     */
    private static int scanLabelGroup(
        String structure,
        int openParen,
        Consumer<String> branch,
        LinkedHashSet<String> unverified
    ) {
        int length = structure.length();
        int cursor = openParen + 1;
        while (cursor < length) {
            cursor = skipWhitespace(structure, cursor);
            if (cursor >= length) {
                return openParen;
            }
            char current = structure.charAt(cursor);
            if (current == ')') {
                return cursor;
            }
            if (current == '|' || current == ':' || current == '!' || current == '&'
                || current == '%') {
                cursor++;
                continue;
            }
            if (current == '$') {
                unverified.add("dynamic label is not statically verifiable");
                return openParen;
            }
            if (current == '(') {
                int nestedEnd = scanLabelGroup(structure, cursor, branch, unverified);
                if (nestedEnd <= cursor) {
                    return openParen;
                }
                cursor = nestedEnd + 1;
                continue;
            }
            LabelRead label = readLabel(structure, cursor);
            if (label == null) {
                return openParen;
            }
            branch.accept(label.raw());
            cursor = label.end();
        }
        return openParen;
    }

    private static int labelStart(String structure, int colon) {
        int start = skipWhitespace(structure, colon + 1);
        while (start < structure.length() && isLabelMarker(structure.charAt(start))) {
            start = skipWhitespace(structure, start + 1);
        }
        return start;
    }

    /** Punctuation preceding or separating label names in a label expression. */
    private static boolean isLabelMarker(char value) {
        return value == '!' || value == '%' || value == '|' || value == '&';
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
     * rather than a map literal. A leading {@code (} is a pattern, never a map
     * literal; otherwise the first word decides: a subquery clause keyword that
     * is not used as a map key (a key may have whitespace before its colon).
     */
    private static boolean isSubqueryBrace(String structure, int afterBrace) {
        int cursor = skipWhitespace(structure, afterBrace);
        if (cursor >= structure.length()) {
            return false;
        }
        if (structure.charAt(cursor) == '(') {
            return true;
        }
        Matcher matcher = IDENTIFIER.matcher(structure);
        if (!matcher.find(cursor) || matcher.start() != cursor) {
            return false;
        }
        int after = skipWhitespace(structure, matcher.end());
        if (after < structure.length() && structure.charAt(after) == ':') {
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
        int end = readTokenEnd(structure, start);
        if (end < 0) {
            return null;
        }
        return new LabelRead(tokenContent(structure, start, end), end);
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
                int unit = backtickUnitLength(values, cursor);
                if (unit > 0) {
                    int next = backtickUnitLength(values, cursor + unit);
                    if (next > 0) {
                        // A backtick followed by another backtick (raw or
                        // pre-decoded) is the doubled form: an escaped
                        // backtick inside the identifier.
                        cursor += unit + next;
                        continue;
                    }
                    return cursor + unit;
                }
                if (values.charAt(cursor) == '\\' && cursor + 6 <= length
                    && values.charAt(cursor + 1) == 'u') {
                    int code = hexCode(values, cursor + 2, cursor + 6);
                    if (code >= 0) {
                        cursor += 6;
                        continue;
                    }
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

    /**
     * Length of a backtick token unit starting at {@code position}: 1 for a raw
     * backtick, 6 for a pre-decoded {@code \u0060} escape, 0 when no backtick
     * starts there.
     */
    private static int backtickUnitLength(String values, int position) {
        if (position < 0 || position >= values.length()) {
            return 0;
        }
        if (values.charAt(position) == '`') {
            return 1;
        }
        if (values.charAt(position) == '\\' && position + 6 <= values.length()
            && values.charAt(position + 1) == 'u'
            && hexCode(values, position + 2, position + 6) == '`') {
            return 6;
        }
        return 0;
    }

    private static String tokenContent(String values, int start, int end) {
        if (values.charAt(start) == '`') {
            int contentEnd = values.charAt(end - 1) == '`' ? end - 1 : end - 6;
            return decodeBacktickContent(values.substring(start + 1, contentEnd));
        }
        return values.substring(start, end);
    }

    /** Decodes doubled backticks and unicode escapes inside a quoted identifier. */
    private static String decodeBacktickContent(String raw) {
        // The backend pre-decodes unicode escapes before lexing, so a decoded
        // backtick joins an adjacent backtick into the doubled escape form;
        // decoding therefore happens in two passes over the decoded text.
        StringBuilder preDecoded = new StringBuilder(raw.length());
        int cursor = 0;
        int length = raw.length();
        while (cursor < length) {
            char current = raw.charAt(cursor);
            if (current == '\\' && cursor + 1 < length && raw.charAt(cursor + 1) == 'u'
                && cursor + 6 <= length) {
                int code = hexCode(raw, cursor + 2, cursor + 6);
                if (code >= 0) {
                    preDecoded.append((char) code);
                    cursor += 6;
                    continue;
                }
            }
            preDecoded.append(current);
            cursor++;
        }
        StringBuilder decoded = new StringBuilder(preDecoded.length());
        cursor = 0;
        while (cursor < preDecoded.length()) {
            char current = preDecoded.charAt(cursor);
            if (current == '`' && cursor + 1 < preDecoded.length()
                && preDecoded.charAt(cursor + 1) == '`') {
                decoded.append('`');
                cursor += 2;
                continue;
            }
            decoded.append(current);
            cursor++;
        }
        return decoded.toString();
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
        Deque<Bracket> brackets = new ArrayDeque<>();
        int length = values.length();
        for (int i = 0; i < length; i++) {
            if (inString[i] || (inBacktick[i] && !isBacktickTokenStart(values, inBacktick, i))) {
                continue;
            }
            char current = values.charAt(i);
            switch (current) {
                case '(' -> brackets.push(new Bracket('(', i));
                case '[' -> brackets.push(new Bracket('[', i));
                case '{' -> {
                    Bracket enclosing = brackets.peek();
                    if (isPatternMapBrace(values, enclosing, i)) {
                        i = scanMapEntries(
                            masks, literals, i, parameters, allowlist,
                            unknownTypeValues, unverified);
                    }
                }
                case ')' -> popIfBracket(brackets, '(');
                case ']' -> popIfBracket(brackets, '[');
                default -> {
                    // only braces open maps and patterns
                }
            }
        }
    }

    private record Bracket(char kind, int position) {}

    /**
     * A {@code &#123;} brace directly inside a node paren or a relationship
     * bracket is a pattern property map and its entries are filters; the same
     * brace inside a function call, a grouping paren or a list literal is
     * plain data and carries no type condition.
     */
    private static boolean isPatternMapBrace(String values, Bracket enclosing, int brace) {
        if (enclosing == null || isSubqueryBrace(values, brace + 1)) {
            return false;
        }
        if (enclosing.kind() == '[') {
            int before = skipWhitespaceBackwards(values, enclosing.position() - 1);
            return before >= 0 && values.charAt(before) == '-';
        }
        return isPatternParen(values, enclosing.position(), brace);
    }

    /**
     * True when the parentheses opening at {@code openParen} hold a node
     * pattern header (variable, labels and label expressions only) rather than
     * a function call or a grouping expression. An empty header is a pattern
     * only when the word before the paren is a pattern-introducing clause
     * keyword, so that anonymous patterns such as
     * {@code MATCH (&#123;type: '...'&#125;)} are still checked while
     * {@code RETURN (&#123;...&#125;)} and {@code count(&#123;...&#125;)} are data.
     */
    private static boolean isPatternParen(String values, int openParen, int brace) {
        boolean empty = true;
        int cursor = openParen + 1;
        while (cursor < brace) {
            char current = values.charAt(cursor);
            if (isCypherWhitespace(current)) {
                cursor++;
                continue;
            }
            empty = false;
            if (current == ':' || current == '!' || current == '|' || current == '&'
                || current == '(' || current == ')') {
                cursor++;
                continue;
            }
            if (current == '%') {
                // % is a label atom inside a label expression only; after a
                // value it is the modulo operator and the paren is no pattern.
                int before = skipWhitespaceBackwards(values, cursor - 1);
                if (before < 0 || !isLabelExpressionChar(values.charAt(before))) {
                    return false;
                }
                cursor++;
                continue;
            }
            if (current == '\'' || current == '"') {
                int literalEnd = readStringEnd(values, cursor);
                if (literalEnd < 0) {
                    return false;
                }
                cursor = literalEnd;
                continue;
            }
            if (current == '$') {
                cursor++;
                continue;
            }
            int tokenEnd = readTokenEnd(values, cursor);
            if (tokenEnd < 0) {
                return false;
            }
            cursor = tokenEnd;
        }
        if (!empty) {
            return true;
        }
        // An empty header is a pattern only when the word before the paren is
        // a pattern-introducing clause keyword; grouping parens in between
        // ({@code RETURN (({...}))}) are walked over first.
        int before = skipWhitespaceBackwards(values, openParen - 1);
        while (before >= 0 && values.charAt(before) == '(') {
            before = skipWhitespaceBackwards(values, before - 1);
        }
        if (before < 0 || !isIdentifierChar(values.charAt(before))) {
            return true;
        }
        int nameStart = identifierStartBefore(values, before + 1);
        String name = values.substring(nameStart, before + 1).toUpperCase();
        return PATTERN_HEAD_KEYWORDS.contains(name);
    }

    /** Punctuation making a following {@code %} a label atom, not the modulo operator. */
    private static boolean isLabelExpressionChar(char value) {
        return value == ':' || value == '!' || value == '|' || value == '&' || value == '(';
    }

    /** End of the string literal starting at {@code start}; -1 when unterminated. */
    private static int readStringEnd(String values, int start) {
        char quote = values.charAt(start);
        int cursor = start + 1;
        int length = values.length();
        while (cursor < length) {
            char current = values.charAt(cursor);
            if (current == '\\' && cursor + 1 < length) {
                cursor += 2;
                continue;
            }
            if (current == quote) {
                if (cursor + 1 < length && values.charAt(cursor + 1) == quote) {
                    cursor += 2;
                    continue;
                }
                return cursor + 1;
            }
            cursor++;
        }
        return -1;
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
                if (depth == 0
                    && (values.charAt(cursor) == '\'' || values.charAt(cursor) == '"')) {
                    int after = skipWhitespace(values, literal.end());
                    if (after < length && values.charAt(after) == ':'
                        && property.equals(literal.value())) {
                        verifyOperand(
                            values, literals, skipWhitespace(values, after + 1),
                            "map property value", parameters, allowlist,
                            unknownTypeValues, unverified);
                    }
                }
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

    private static void popIfBracket(Deque<Bracket> brackets, char expected) {
        if (brackets.peek() != null && brackets.peek().kind() == expected) {
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
            int accessEnd = matchTypeAccess(values, i, property, literals, unverified);
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
     * Collects names bound to the type property through a projection alias
     * ({@code WITH n.type AS x}) and checks comparisons on those names, since
     * {@code WHERE x = 'Employee'} constrains the type property exactly like
     * a direct access.
     */
    private static void scanTypeAliasUsages(
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
        List<String> aliases = collectTypeAliases(
            values, masks.typePropertyName(), inString, inBacktick);
        if (aliases.isEmpty()) {
            return;
        }
        int length = values.length();
        for (int i = 0; i < length; i++) {
            if (inString[i] || (inBacktick[i] && !isBacktickTokenStart(values, inBacktick, i))) {
                continue;
            }
            int tokenEnd = readTokenEnd(values, i);
            if (tokenEnd < 0 || !aliases.contains(tokenContent(values, i, tokenEnd))) {
                continue;
            }
            if (i > 0) {
                char before = values.charAt(i - 1);
                if (before == '.' || before == '$' || isIdentifierChar(before)) {
                    // an attribute name or a mid-token probe, not the variable
                    continue;
                }
            }
            // The scan must advance past the alias token even when the usage
            // inspection consumes nothing (bare alias at the statement end).
            int consumed = inspectAliasUsage(
                values, literals, i, tokenEnd, parameters, allowlist,
                unknownTypeValues, unverified);
            i = Math.max(consumed, tokenEnd) - 1;
        }
    }

    /**
     * Names bound to the type property by an {@code AS} projection alias such
     * as {@code n.type AS x}, including aliases of those aliases.
     */
    private static List<String> collectTypeAliases(
        String values,
        String property,
        boolean[] inString,
        boolean[] inBacktick
    ) {
        int length = values.length();
        List<String> aliases = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            if (inString[i] || (inBacktick[i] && !isBacktickTokenStart(values, inBacktick, i))) {
                continue;
            }
            if (!isWordAt(values, i, "AS")) {
                continue;
            }
            int nameStart = skipWhitespace(values, i + 2);
            int nameEnd = readTokenEnd(values, nameStart);
            if (nameEnd < 0) {
                continue;
            }
            if (isTypeAliasSource(values, property, aliases, i)) {
                aliases.add(tokenContent(values, nameStart, nameEnd));
            }
            i = nameEnd - 1;
        }
        return aliases;
    }

    /** True when the expression projected by the AS at {@code asPosition} is the type property. */
    private static boolean isTypeAliasSource(
        String values, String property, List<String> aliases, int asPosition) {
        int before = skipWhitespaceBackwards(values, asPosition - 1);
        while (before >= 0 && values.charAt(before) == ')') {
            before = skipWhitespaceBackwards(values, before - 1);
        }
        int nameEnd = before + 1;
        int nameStart = identifierStartBefore(values, nameEnd);
        if (nameStart < 0) {
            return false;
        }
        String name = values.substring(nameStart, nameEnd);
        if (aliases.contains(name)) {
            return true;
        }
        if (!property.equals(name)) {
            return false;
        }
        int dot = skipWhitespaceBackwards(values, nameStart - 1);
        return dot >= 0 && values.charAt(dot) == '.';
    }

    /**
     * Checks one alias occurrence: {@code alias = <operand>},
     * {@code <operand> = alias} and {@code alias IN <list>} are type
     * conditions, and a compound chain feeding a comparison is unverifiable.
     * Returns the last consumed position.
     */
    private static int inspectAliasUsage(
        String values,
        List<Literal> literals,
        int start,
        int tokenEnd,
        Map<String, Object> parameters,
        CypherOntologyAllowlist allowlist,
        LinkedHashSet<String> unknownTypeValues,
        LinkedHashSet<String> unverified
    ) {
        int length = values.length();
        int right = skipWhitespace(values, tokenEnd);
        if (right < length) {
            char current = values.charAt(right);
            if (isOperatorChar(current)) {
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
                return right;
            }
            if (isWordAt(values, right, "IN")) {
                verifyInOperand(
                    values, literals, skipWhitespace(values, right + 2),
                    parameters, allowlist, unknownTypeValues, unverified);
                return right + 1;
            }
            if (isCompoundContinuation(current)
                && chainReachesComparison(
                    values, literals, right, enclosingDepthAt(values, literals, start))) {
                unverified.add(
                    "type condition is not statically verifiable: compound expression"
                        + " involving the type property");
                return right;
            }
        }
        int left = skipWhitespaceBackwards(values, start - 1);
        if (left >= 0 && isOperatorChar(values.charAt(left))) {
            String operator = readOperatorBackwards(values, left);
            if ("=".equals(operator)) {
                int operandEnd = skipWhitespaceBackwards(values, left - operator.length());
                verifyOperand(
                    values, literals, operandStart(values, operandEnd),
                    "left-hand side", parameters, allowlist, unknownTypeValues, unverified);
            } else {
                unverified.add(
                    "type condition is not statically verifiable: unsupported operator '"
                        + operator + "'");
            }
            return left;
        }
        return tokenEnd - 1;
    }

    /**
     * Matches a type-property access starting at {@code start}: {@code v.prop},
     * {@code `v`.prop}, {@code (v).prop} (whitespace around the dot tolerated),
     * or a literal subscript {@code v['prop']}. A keyword token before {@code [}
     * introduces a list literal, not a subscript. Dynamic subscripts on a
     * variable are reported as unverifiable since they may fold to the type
     * property; plain integer indexes name no property and are ignored.
     */
    private static int matchTypeAccess(
        String values,
        int start,
        String property,
        List<Literal> literals,
        LinkedHashSet<String> unverified
    ) {
        int length = values.length();
        if (start > 0 && values.charAt(start) != '`'
            && isIdentifierChar(values.charAt(start - 1))) {
            // Mid-token probe: the same access is matched from the token's true start.
            // A backtick always opens a token, even directly after a keyword.
            return -1;
        }
        int tokenEnd = readTokenEnd(values, start);
        if (tokenEnd < 0) {
            return -1;
        }
        int cursor = skipWhitespace(values, tokenEnd);
        while (cursor < length && values.charAt(cursor) == ')') {
            cursor = skipWhitespace(values, cursor + 1);
        }
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
            // Only a variable-like token can be subscripted; a keyword such as
            // RETURN or IN introduces a plain list literal instead. END is
            // exempt because it is also a legal variable name.
            if (values.charAt(start) != '`') {
                String token = tokenContent(values, start, tokenEnd).toUpperCase();
                if ((NON_FUNCTION_WORDS.contains(token) || SUBQUERY_KEYWORDS.contains(token))
                    && !"END".equals(token)) {
                    return -1;
                }
            }
            int literalStart = skipWhitespace(values, cursor + 1);
            Literal literal = literalAt(literals, literalStart);
            if (literal == null || literal.start() != literalStart) {
                if (isIntegerLiteral(values, literalStart)) {
                    // A plain list index such as "list[0]" names no property,
                    // but the chain may continue ("nodes[0].type").
                    int indexEnd = integerIndexEnd(values, literalStart);
                    return indexEnd < 0 ? -1 : afterIndexChain(values, indexEnd, property);
                }
                unverified.add(
                    "type condition is not statically verifiable: compound subscript expression");
                return -1;
            }
            int close = skipWhitespace(values, literal.end());
            if (close >= length || values.charAt(close) != ']') {
                unverified.add(
                    "type condition is not statically verifiable: compound subscript expression");
                return -1;
            }
            return property.equals(literal.value()) ? close + 1 : -1;
        }
        return -1;
    }

    /** True when a plain (optionally signed) integer literal starts at {@code position}. */
    private static boolean isIntegerLiteral(String values, int position) {
        int length = values.length();
        if (position < 0 || position >= length) {
            return false;
        }
        int cursor = position;
        char first = values.charAt(cursor);
        if (first == '-' || first == '+') {
            cursor++;
        }
        return cursor < length && Character.isDigit(values.charAt(cursor));
    }

    /**
     * End (exclusive) of the {@code ]} closing an integer index starting at
     * {@code position}; -1 when the index is malformed or unterminated.
     */
    private static int integerIndexEnd(String values, int position) {
        int length = values.length();
        if (!isIntegerLiteral(values, position)) {
            return -1;
        }
        int cursor = position;
        if (values.charAt(cursor) == '-' || values.charAt(cursor) == '+') {
            cursor++;
        }
        while (cursor < length && Character.isDigit(values.charAt(cursor))) {
            cursor++;
        }
        int close = skipWhitespace(values, cursor);
        return close < length && values.charAt(close) == ']' ? close + 1 : -1;
    }

    /**
     * Continues a subscript chain after an integer index such as
     * {@code nodes[0]}: further integer indexes keep walking, while a
     * {@code .prop} access yields the property end when it names the type
     * property and -1 otherwise. Anything else ends the chain unnamed.
     */
    private static int afterIndexChain(String values, int from, String property) {
        int length = values.length();
        int cursor = skipWhitespace(values, from);
        while (cursor < length) {
            char current = values.charAt(cursor);
            if (current == '.') {
                int propertyStart = skipWhitespace(values, cursor + 1);
                int propertyEnd = readTokenEnd(values, propertyStart);
                if (propertyEnd < 0) {
                    return -1;
                }
                return property.equals(tokenContent(values, propertyStart, propertyEnd))
                    ? propertyEnd
                    : -1;
            }
            if (current == '[') {
                int indexStart = skipWhitespace(values, cursor + 1);
                int indexEnd = integerIndexEnd(values, indexStart);
                if (indexEnd < 0) {
                    return -1;
                }
                cursor = skipWhitespace(values, indexEnd);
                continue;
            }
            return -1;
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
        // Whitespace between a function name and its '(' is legal Cypher.
        int wrapperEnd = skipWhitespaceBackwards(values, accessStart - 1);
        List<String> wrappers = new ArrayList<>();
        while (wrapperEnd >= 0 && values.charAt(wrapperEnd) == '(') {
            int nameEnd = skipWhitespaceBackwards(values, wrapperEnd - 1) + 1;
            int nameStart = identifierStartBefore(values, nameEnd);
            if (nameStart >= 0) {
                String name = values.substring(nameStart, nameEnd);
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
        int closeSteps = 0;
        while (right < length && values.charAt(right) == ')') {
            closeSteps++;
            right = skipWhitespace(values, right + 1);
        }
        // The chain scan needs the real nesting depth at the access so that a
        // comma separating outer arguments (not just directly wrapped ones)
        // does not truncate it.
        int chainDepth = Math.max(
            enclosingDepthAt(values, literals, accessStart) - closeSteps, 0);
        boolean compoundBefore =
            wrapperEnd >= 0 && isCompoundContinuation(values.charAt(wrapperEnd));

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
            } else if (isCompoundContinuation(current)) {
                // The access feeds a compound expression such as
                // "n.type + '' = ..."; a chain that reaches a comparison is a
                // condition and cannot be statically resolved, while the same
                // chain projected ("RETURN n.type + '' AS t") is harmless.
                if (chainReachesComparison(values, literals, right, chainDepth)) {
                    resolved = true;
                    unverified.add(
                        "type condition is not statically verifiable: compound expression"
                            + " involving the type property");
                }
            } else if (current == ',' && chainDepth > 0) {
                // The access is a non-final argument or element of an enclosing
                // call or list; the enclosing expression cannot be statically
                // resolved when it feeds a comparison, while a projected
                // argument is harmless.
                if (chainReachesComparison(values, literals, right, chainDepth)) {
                    resolved = true;
                    int afterHost = skipSiblingRegion(values, literals, right + 1);
                    int afterHostChar = afterHost < 0 ? -1 : skipWhitespace(values, afterHost);
                    boolean compoundFollows = afterHostChar >= 0 && afterHostChar < length
                        && isCompoundContinuation(values.charAt(afterHostChar));
                    if (compoundFollows || wrappers.isEmpty()) {
                        unverified.add(
                            "type condition is not statically verifiable: compound expression"
                                + " involving the type property");
                    } else {
                        unverified.add(
                            "type condition is not statically verifiable: unsupported function '"
                                + wrappers.get(0).toLowerCase() + "'");
                    }
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
        if (compoundBefore) {
            unverified.add(
                "type condition is not statically verifiable: compound expression"
                    + " involving the type property");
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
        return value == '+' || value == '-' || value == '*' || value == '/' || value == '%'
            || value == '[' || value == '|';
    }

    /**
     * Walks forward along a compound expression chain and reports whether it
     * ends in a comparison. A chain that is compared ({@code n.type + '' = 'x'})
     * is a condition, while a chain that is projected
     * ({@code RETURN n.type + '' AS t}) carries no type condition.
     *
     * <p>{@code initialDepth} carries the number of brackets still open at
     * {@code from}. A comma at depth zero separates projections and terminates
     * the chain, while a deeper comma separates arguments or elements of an
     * enclosing expression: the remaining siblings are skipped and the scan
     * continues after the enclosing bracket, so that
     * {@code substring(n.type + '', 0, 8) = 'x'} still reaches its comparison.
     * Clause keywords at depth zero ({@code AS}, {@code WHERE}, ...) also
     * terminate the chain as a projection boundary, unless they are part of an
     * expression (an attribute name or a CASE construct).
     */
    private static boolean chainReachesComparison(
        String values, List<Literal> literals, int from, int initialDepth) {
        int length = values.length();
        int cursor = from;
        int depth = initialDepth;
        while (cursor < length) {
            char current = values.charAt(cursor);
            if (isCypherWhitespace(current)) {
                cursor++;
                continue;
            }
            if (current == '(' || current == '[' || current == '{') {
                depth++;
                cursor++;
                continue;
            }
            if (current == ')' || current == ']' || current == '}') {
                if (depth == 0) {
                    return false;
                }
                depth--;
                cursor++;
                continue;
            }
            if (current == ';') {
                return false;
            }
            if (current == ',') {
                if (depth == 0) {
                    return false;
                }
                int afterSiblings = skipSiblingRegion(values, literals, cursor + 1);
                if (afterSiblings < 0) {
                    return false;
                }
                depth--;
                cursor = afterSiblings;
                continue;
            }
            if (isWordAt(values, cursor, "CASE")) {
                int afterCase = skipCaseExpression(values, literals, cursor);
                if (afterCase < 0) {
                    return false;
                }
                cursor = afterCase;
                continue;
            }
            if (isOperatorChar(current)
                || isWordAt(values, cursor, "IN")
                || isWordAt(values, cursor, "CONTAINS")
                || isWordAt(values, cursor, "STARTS")
                || isWordAt(values, cursor, "ENDS")) {
                return true;
            }
            if (depth == 0 && isExpressionBoundaryAt(values, cursor)) {
                return false;
            }
            Literal literal = literalAt(literals, cursor);
            if (literal != null && literal.start() == cursor) {
                cursor = literal.end();
                continue;
            }
            if (current == '$') {
                String name = readParameterName(values, cursor);
                if (name == null) {
                    return false;
                }
                cursor += 1 + name.length();
                continue;
            }
            int tokenEnd = readTokenEnd(values, cursor);
            if (tokenEnd > 0) {
                cursor = tokenEnd;
                continue;
            }
            cursor++;
        }
        return false;
    }

    /**
     * True when a clause or connective keyword ending an expression starts at
     * {@code position}. A keyword used as an attribute name ({@code n.end}) or
     * adjacent to an operator ({@code end = 5}) belongs to the expression and
     * is not a boundary.
     */
    private static boolean isExpressionBoundaryAt(String values, int position) {
        int tokenEnd = readTokenEnd(values, position);
        if (tokenEnd <= 0 || values.charAt(position) == '`') {
            return false;
        }
        String token = values.substring(position, tokenEnd).toUpperCase();
        if (!EXPRESSION_BOUNDARY_KEYWORDS.contains(token)) {
            return false;
        }
        int before = skipWhitespaceBackwards(values, position - 1);
        if (before >= 0
            && (values.charAt(before) == '.' || isCompoundContinuation(values.charAt(before)))) {
            return false;
        }
        int after = skipWhitespace(values, tokenEnd);
        if (after < values.length()
            && (isOperatorChar(values.charAt(after)) || values.charAt(after) == '.')) {
            return false;
        }
        return true;
    }

    /**
     * Skips the siblings and the closing bracket of the bracket level a comma
     * at {@code from - 1} belongs to: scanning resumes at the first position
     * after the bracket that closes the comma's level ({@code )}, {@code ]} or
     * {@code }}); -1 when unbalanced.
     */
    private static int skipSiblingRegion(String values, List<Literal> literals, int from) {
        int length = values.length();
        int cursor = from;
        int depth = 0;
        while (cursor < length) {
            Literal literal = literalAt(literals, cursor);
            if (literal != null && literal.start() == cursor) {
                cursor = literal.end();
                continue;
            }
            char current = values.charAt(cursor);
            if (current == '`') {
                int tokenEnd = readTokenEnd(values, cursor);
                if (tokenEnd > 0) {
                    cursor = tokenEnd;
                    continue;
                }
            }
            if (current == '(' || current == '[' || current == '{') {
                depth++;
            } else if (current == ')' || current == ']' || current == '}') {
                if (depth == 0) {
                    return cursor + 1;
                }
                depth--;
            }
            cursor++;
        }
        return -1;
    }

    /**
     * Position right after the {@code END} closing the CASE construct starting
     * at {@code caseStart}; -1 when the construct is unterminated.
     */
    private static int skipCaseExpression(String values, List<Literal> literals, int caseStart) {
        int length = values.length();
        int cursor = caseStart;
        int bracketDepth = 0;
        int caseDepth = 0;
        while (cursor < length) {
            Literal literal = literalAt(literals, cursor);
            if (literal != null && literal.start() == cursor) {
                cursor = literal.end();
                continue;
            }
            char current = values.charAt(cursor);
            if (current == '`') {
                int tokenEnd = readTokenEnd(values, cursor);
                if (tokenEnd > 0) {
                    cursor = tokenEnd;
                    continue;
                }
            }
            if (current == '(') {
                bracketDepth++;
                cursor++;
                continue;
            }
            if (current == ')') {
                bracketDepth--;
                cursor++;
                continue;
            }
            if (bracketDepth == 0 && isWordAt(values, cursor, "CASE")) {
                caseDepth++;
                cursor += 4;
                continue;
            }
            if (bracketDepth == 0 && isWordAt(values, cursor, "END")) {
                caseDepth--;
                if (caseDepth == 0) {
                    return cursor + 3;
                }
                cursor += 3;
                continue;
            }
            cursor++;
        }
        return -1;
    }

    /** Number of brackets still open just before {@code position}. */
    private static int enclosingDepthAt(String values, List<Literal> literals, int position) {
        int length = values.length();
        int cursor = 0;
        int limit = Math.min(position, length);
        int depth = 0;
        while (cursor < limit) {
            Literal literal = literalAt(literals, cursor);
            if (literal != null && literal.start() == cursor) {
                cursor = literal.end();
                continue;
            }
            char current = values.charAt(cursor);
            if (current == '`') {
                int tokenEnd = readTokenEnd(values, cursor);
                if (tokenEnd > 0) {
                    cursor = tokenEnd;
                    continue;
                }
            }
            if (current == '(' || current == '[' || current == '{') {
                depth++;
            } else if ((current == ')' || current == ']' || current == '}')
                && depth > 0) {
                depth--;
            }
            cursor++;
        }
        return depth;
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
                            cursor += 6;
                            if ((char) code == quote) {
                                // pre-decoded closing quote, matching the masks
                                terminated = true;
                                break;
                            }
                            if ((char) code == '\\') {
                                // the decoded backslash escapes the next decoded character
                                int escapedEnd = decodedCharEnd(original, cursor);
                                appendDecoded(original, cursor, escapedEnd, value);
                                cursor = escapedEnd;
                                continue;
                            }
                            value.append((char) code);
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

    /** End of the single decoded character at {@code position}: a unicode escape spans 6 chars. */
    private static int decodedCharEnd(String text, int position) {
        int length = text.length();
        if (position < length && text.charAt(position) == '\\' && position + 6 <= length
            && text.charAt(position + 1) == 'u'
            && hexCode(text, position + 2, position + 6) >= 0) {
            return position + 6;
        }
        return Math.min(position + 1, length);
    }

    private static void appendDecoded(String text, int start, int end, StringBuilder value) {
        if (end > start + 1) {
            value.append(decodeEscape((char) hexCode(text, start + 2, start + 6)));
        } else {
            value.append(decodeEscape(text.charAt(start)));
        }
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
        while (cursor < text.length() && isCypherWhitespace(text.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static int skipWhitespaceBackwards(String text, int position) {
        int cursor = position;
        while (cursor >= 0 && isCypherWhitespace(text.charAt(cursor))) {
            cursor--;
        }
        return cursor;
    }

    /**
     * Whitespace as Cypher defines it, which is wider than
     * {@link Character#isWhitespace}: no-break spaces such as U+202F only pass
     * {@link Character#isSpaceChar}, and U+0085 passes neither.
     */
    private static boolean isCypherWhitespace(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value) || value == '\u0085';
    }

    /**
     * Line terminators that end a line comment: Cypher 5 defines
     * {@code SINGLE_LINE_COMMENT} to end only at CR or LF, so separators such
     * as U+2028 stay inside the comment.
     */
    private static boolean isLineTerminator(char value) {
        return value == '\n' || value == '\r';
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
                        int code = -1;
                        if (text.charAt(cursor + 1) == 'u' && cursor + 6 <= length) {
                            code = hexCode(text, cursor + 2, cursor + 6);
                        }
                        if (code >= 0) {
                            // The backend pre-decodes unicode escapes before
                            // lexing, so a decoded quote is a real delimiter.
                            for (int k = cursor; k < cursor + 6; k++) {
                                inString[k] = true;
                                structure.append(' ');
                                values.append(text.charAt(k));
                            }
                            cursor += 6;
                            if ((char) code == quote) {
                                break;
                            }
                            if ((char) code == '\\') {
                                // The decoded backslash escapes the next decoded
                                // character, so a following quote does not close
                                // the literal.
                                int escapedEnd = decodedCharEnd(text, cursor);
                                for (int k = cursor; k < escapedEnd; k++) {
                                    inString[k] = true;
                                    structure.append(' ');
                                    values.append(text.charAt(k));
                                }
                                cursor = escapedEnd;
                            }
                            continue;
                        }
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
                    int unit = backtickUnitLength(text, cursor);
                    if (unit > 0) {
                        int next = backtickUnitLength(text, cursor + unit);
                        int consumed = next > 0 ? unit + next : unit;
                        for (int k = cursor; k < cursor + consumed; k++) {
                            inBacktick[k] = true;
                            structure.append(text.charAt(k));
                            values.append(text.charAt(k));
                        }
                        cursor += consumed;
                        if (next == 0) {
                            // a lone backtick (raw or pre-decoded) ends the identifier
                            break;
                        }
                        continue;
                    }
                    if (text.charAt(cursor) == '\\' && cursor + 1 < length
                        && text.charAt(cursor + 1) == 'u' && cursor + 6 <= length) {
                        int code = hexCode(text, cursor + 2, cursor + 6);
                        if (code >= 0) {
                            for (int k = cursor; k < cursor + 6; k++) {
                                inBacktick[k] = true;
                                structure.append(text.charAt(k));
                                values.append(text.charAt(k));
                            }
                            cursor += 6;
                            continue;
                        }
                    }
                    inBacktick[cursor] = true;
                    structure.append(text.charAt(cursor));
                    values.append(text.charAt(cursor));
                    cursor++;
                }
                i = cursor;
                continue;
            }
            if (current == '/' && i + 1 < length && text.charAt(i + 1) == '/') {
                // A line comment ends at any line terminator, including one
                // written as a unicode escape that the backend pre-decodes.
                while (i < length) {
                    char inner = text.charAt(i);
                    if (isLineTerminator(inner)) {
                        break;
                    }
                    if (inner == '\\' && i + 6 <= length && text.charAt(i + 1) == 'u') {
                        int code = hexCode(text, i + 2, i + 6);
                        if (code >= 0) {
                            for (int k = i; k < i + 6; k++) {
                                structure.append(' ');
                                values.append(' ');
                            }
                            i += 6;
                            if (isLineTerminator((char) code)) {
                                break;
                            }
                            continue;
                        }
                    }
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
                boolean star = false;
                while (i < length) {
                    char inner = text.charAt(i);
                    if (inner == '\\' && i + 6 <= length && text.charAt(i + 1) == 'u') {
                        int code = hexCode(text, i + 2, i + 6);
                        if (code >= 0) {
                            for (int k = i; k < i + 6; k++) {
                                structure.append(' ');
                                values.append(' ');
                            }
                            i += 6;
                            // The decoded character can complete the closing
                            // "*/" across the escape boundary.
                            if (star && (char) code == '/') {
                                break;
                            }
                            star = (char) code == '*';
                            continue;
                        }
                    }
                    if (star && inner == '/') {
                        structure.append(' ');
                        values.append(' ');
                        i++;
                        break;
                    }
                    star = inner == '*';
                    structure.append(' ');
                    values.append(' ');
                    i++;
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
