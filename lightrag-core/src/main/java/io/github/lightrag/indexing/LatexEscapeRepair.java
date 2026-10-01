package io.github.lightrag.indexing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Restores LaTeX backslashes destroyed by JSON escape decoding, a port of upstream
 * {@code repair_vlm_json_escape_damage} / {@code repair_vlm_json_escape_damage_nested}
 * ({@code lightrag/utils.py}, contract {@code docs/design/LatexEscapeRepairContract.md}).
 *
 * <p>An LLM writing LaTeX inside a JSON string routinely emits a single backslash:
 * {@code "\frac"} is <em>valid</em> JSON meaning form feed + {@code rac}, so every JSON parser
 * silently decodes it and the command is destroyed before any code sees the value. Only the two
 * zero-risk cases are repaired unconditionally (form feed / backspace + letter). Tab, CR and LF
 * are legitimate whitespace, so whitespace-class damage is repaired only inside paired dollar
 * math and merely logged elsewhere.</p>
 */
public final class LatexEscapeRepair {
    private static final Logger log = LoggerFactory.getLogger(LatexEscapeRepair.class);

    // Form feed and backspace followed by a letter have no legitimate use in
    // LLM-generated prose, so restoring the backslash is unconditionally safe.
    private static final Pattern FORMFEED_LATEX_PATTERN = Pattern.compile("\\x0c(?=[A-Za-z])");
    private static final Pattern BACKSPACE_LATEX_PATTERN = Pattern.compile("\\x08(?=[A-Za-z])");

    // Whitespace + residue spelling that completes a common LaTeX command. Two variants of one
    // whitelist: WS_LATEX_SUSPECT_PATTERN (a word boundary OR a following non-ASCII character)
    // only warns, about prose; WS_LATEX_MATH_PATTERN ((?![A-Za-z])) is the one that rewrites, and
    // only inside a confirmed math span. Never swap their guards.
    // "__END__" is substituted with the trailing guard; a plain placeholder rather than a format
    // call so that adding a quantifier to the residue alternation cannot break the substitution.
    private static final String WS_LATEX_RESIDUES =
        "\\t(?=(?:au|heta|imes|ext|ilde|herefore|riangle)__END__)"
            + "|\\r(?=(?:ho|ight|angle|ceil)__END__)"
            + "|\\n(?=(?:abla|otin)__END__)";
    // A bare \b reports nothing when a word character follows, and Python's re counts a CJK
    // ideograph as a word character -- so a damaged command sitting in Chinese prose with no
    // dollar math around it was neither repaired nor warned about. Hence the second alternative:
    // a Latin fragment pressed against a non-ASCII character with no space between them is
    // essentially only produced by this damage. ASCII word characters stay excluded -- in
    // tab-separated data "col<tab>ext_id" and "<tab>au2" are plausible values.
    // Package-private so the contract's superset invariant (a repaired span can never re-trigger
    // the prose warning afterwards) stays directly assertable from tests.
    static final Pattern WS_LATEX_SUSPECT_PATTERN = Pattern.compile(
        WS_LATEX_RESIDUES.replace("__END__", "(?:\\b|(?=[^\\x00-\\x7F]))"),
        Pattern.UNICODE_CHARACTER_CLASS);
    static final Pattern WS_LATEX_MATH_PATTERN = Pattern.compile(
        WS_LATEX_RESIDUES.replace("__END__", "(?![A-Za-z])"));

    // Markdown regions whose content is verbatim: a fenced block (closed, or running to the end
    // of the text when the model never closed it) and a closed inline code span. An unclosed
    // single backtick matches nothing, so it cannot suppress repairs in the rest of the text.
    //
    // Fences and inline spans are matched in SEPARATE passes, fences first, because CommonMark
    // settles block structure before it looks for inline spans: an inline span can never cross a
    // fence boundary. The fence pattern has one branch per fence character because their info
    // strings differ: a backtick fence may not carry a backtick in its info string, a tilde fence
    // may. A closing fence may carry only whitespace after it and may be longer than the opener.
    // The optional \r keeps CRLF text working (MULTILINE "$" matches before the \n, with the \r
    // still ahead of it). Fence indentation is SPACES only -- a leading tab advances to the
    // fourth column and is code content. Java's UNIX_LINES pins "^"/"$" to \n exactly like
    // Python's MULTILINE, and \z is Python's \Z (absolute end of input).
    private static final Pattern MD_FENCE_REGION_PATTERN = Pattern.compile(
        "^ {0,3}(?<bfence>`{3,})[^\\n`]*$[\\s\\S]*?"
            + "(?:^ {0,3}\\k<bfence>`*[ \\t]*\\r?$|\\z)"
            + "|^ {0,3}(?<tfence>~{3,})[^\\n]*$[\\s\\S]*?"
            + "(?:^ {0,3}\\k<tfence>~*[ \\t]*\\r?$|\\z)",
        Pattern.MULTILINE | Pattern.UNIX_LINES);
    // A blank line ends a paragraph, and a code span is an inline inside ONE leaf block, so
    // backtick runs in two different paragraphs cannot pair. The blank line must tolerate a \r
    // or CRLF text keeps the exposure.
    private static final Pattern MD_BLANK_LINE_PATTERN = Pattern.compile("\\n[ \\t\\r]*\\n");
    // Inline span, searched only within one paragraph of one gap between fences. Opening and
    // closing runs must be the same length, so BOTH are bounded on BOTH sides. The opener also
    // honours backslash escapes, by parity; the CLOSER deliberately does NOT -- CommonMark gives
    // backslash escapes no effect inside a code span, so a backslash-backtick closes it.
    private static final Pattern MD_INLINE_CODE_PATTERN = Pattern.compile(
        "(?<![\\\\`])(?:\\\\\\\\)*(?<ticks>`+)(?!`)[\\s\\S]*?(?<!`)\\k<ticks>(?!`)");

    // A span whose body carries these is code, not math: a double quote or a backtick is
    // ordinary in shell and ~absent from LaTeX math.
    private static final List<String> CODE_MARKS_IN_MATH_SPAN = List.of("\"", "`");
    // No formula in a VLM description runs this long on one line; a shell command between two
    // "$VAR" expansions frequently does. Display math is exempt.
    private static final int MAX_INLINE_MATH_CHARS = 200;
    // How much of a rewritten span reaches the log.
    private static final int LOGGED_SPAN_CHARS = 80;
    private static final int SUSPECT_SNIPPET_CHARS = 30;

    private LatexEscapeRepair() {
    }

    /**
     * Restore LaTeX backslashes destroyed by JSON escape decoding. Non-null input only; empty
     * input is returned unchanged (upstream returns falsy text untouched).
     */
    public static String repair(String text) {
        return repair(text, "");
    }

    /**
     * Restore LaTeX backslashes destroyed by JSON escape decoding.
     *
     * <p>Form feed + letter and backspace + letter are restored unconditionally. Isolated control
     * characters (not followed by a letter) are left alone for downstream sanitization to drop.
     * Whitespace-class damage (tab / CR / LF + residue) is repaired only inside paired dollar-math
     * spans; outside explicit math it remains ambiguous with legitimate whitespace and is only
     * logged, never rewritten.</p>
     *
     * @param text parsed string value to repair
     * @param context optional label included in the log lines (upstream passes the chunk key)
     */
    public static String repair(String text, String context) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        var repaired = FORMFEED_LATEX_PATTERN.matcher(text)
            .replaceAll(Matcher.quoteReplacement("\\f"));
        repaired = BACKSPACE_LATEX_PATTERN.matcher(repaired)
            .replaceAll(Matcher.quoteReplacement("\\b"));
        if (!repaired.equals(text)) {
            log.warn("Repaired LaTeX escape damage (\\f/\\b decoded by JSON parser){}", contextSuffix(context));
        }

        var scanned = repairWhitespaceClassDamageInDollarMath(repaired);
        if (scanned.replacements() > 0) {
            log.warn(
                "Repaired whitespace-class LaTeX escape damage inside dollar math{} ({} occurrence{}): {}",
                contextSuffix(context),
                scanned.replacements(),
                scanned.replacements() == 1 ? "" : "s",
                String.join(" | ", scanned.spans())
            );
        }

        var suspect = WS_LATEX_SUSPECT_PATTERN.matcher(scanned.text());
        if (suspect.find()) {
            var start = suspect.start();
            var snippet = scanned.text().substring(
                Math.max(0, start - SUSPECT_SNIPPET_CHARS),
                Math.min(scanned.text().length(), start + SUSPECT_SNIPPET_CHARS)
            );
            log.warn(
                "Suspected whitespace-class LaTeX escape damage{} (not auto-repaired): '{}'",
                contextSuffix(context),
                snippet
            );
        }

        return scanned.text();
    }

    private static String contextSuffix(String context) {
        return context == null || context.isEmpty() ? "" : " in " + context;
    }

    private record RepairScan(String text, int replacements, List<String> spans) {
    }

    private record Region(int start, int end) {
    }

    /**
     * Repair dollar math outside Markdown code, which is read verbatim. Code quotes dollars for
     * its own reasons -- {@code echo "$HOME" ... "$PATH"} pairs as neatly as a formula does -- so
     * a fenced block or an inline code span is copied through untouched and no span may cross one.
     */
    private static RepairScan repairWhitespaceClassDamageInDollarMath(String text) {
        var pieces = new StringBuilder(text.length());
        var spans = new ArrayList<String>();
        var replacements = 0;
        var last = 0;
        for (var region : markdownCodeRegions(text)) {
            var scanned = scanDollarSpans(text.substring(last, region.start()));
            pieces.append(scanned.text());
            replacements += scanned.replacements();
            spans.addAll(scanned.spans());
            pieces.append(text, region.start(), region.end());
            last = region.end();
        }
        var scanned = scanDollarSpans(text.substring(last));
        pieces.append(scanned.text());
        replacements += scanned.replacements();
        spans.addAll(scanned.spans());
        return new RepairScan(pieces.toString(), replacements, List.copyOf(spans));
    }

    /**
     * Restore whitespace-class LaTeX escapes inside paired dollar math: {@code $...$} /
     * {@code $$...$$}. Rules a change here must keep:
     *
     * <ul>
     *   <li><b>Never rewrite text a Pandoc-style parser would not call math.</b> Dollar delimiters
     *       are ambiguous, so the bias is one-sided: a missed repair leaves damage that was
     *       already there, a wrong one corrupts prose on its way to storage.</li>
     *   <li>An inline {@code $} opens only before a non-whitespace character, EXCEPT when the
     *       damage itself sits there (removing that exception rejects tab-after-opener, the shape
     *       this function exists to repair).</li>
     *   <li>Only the very next unescaped delimiter may close a span, and a delimiter that cannot
     *       pair is skipped as an ordinary character (a failed {@code $$} whole, not one dollar at
     *       a time) rather than ending the scan.</li>
     *   <li>The function operates on already-decoded strings, so a correct LaTeX command still
     *       contains a real backslash and cannot match the damage. Repairing twice equals
     *       repairing once.</li>
     * </ul>
     */
    private static RepairScan scanDollarSpans(String text) {
        var pieces = new StringBuilder(text.length());
        var repairedSpans = new ArrayList<String>();
        var replacements = 0;
        var cursor = 0;
        while (cursor < text.length()) {
            if (text.charAt(cursor) != '$' || isEscaped(text, cursor)) {
                pieces.append(text.charAt(cursor));
                cursor++;
                continue;
            }

            var delimiter = text.startsWith("$$", cursor) ? "$$" : "$";
            var close = -1;
            if (delimiter.equals("$$") || opensInlineMath(text, cursor)) {
                // Only the very next unescaped delimiter may close: a span that has to reach
                // over another dollar is not one span but a stray dollar plus a real span.
                close = nextDelimiter(text, cursor + delimiter.length(), delimiter);
                if (delimiter.equals("$") && close >= 0 && !closesInlineMath(text, close)) {
                    close = -1;
                }
            }
            if (close < 0) {
                // Not a usable delimiter here: emit it and keep scanning, so a later well-formed
                // span is still reached.
                pieces.append(delimiter);
                cursor += delimiter.length();
                continue;
            }

            var spanEnd = close + delimiter.length();
            var mathSpan = text.substring(cursor, spanEnd);
            var repairedSpan = mathSpan;
            var count = 0;
            if (spanIsPlausiblyMath(mathSpan, delimiter)) {
                var matcher = WS_LATEX_MATH_PATTERN.matcher(mathSpan);
                var builder = new StringBuilder(mathSpan.length());
                while (matcher.find()) {
                    matcher.appendReplacement(builder, Matcher.quoteReplacement(switch (matcher.group()) {
                        case "\t" -> "\\t";
                        case "\r" -> "\\r";
                        default -> "\\n";
                    }));
                    count++;
                }
                matcher.appendTail(builder);
                repairedSpan = builder.toString();
            }
            pieces.append(repairedSpan);
            if (count > 0) {
                replacements += count;
                repairedSpans.add(repairedSpan.substring(0, Math.min(LOGGED_SPAN_CHARS, repairedSpan.length())));
            }
            cursor = spanEnd;
        }

        return new RepairScan(pieces.toString(), replacements, List.copyOf(repairedSpans));
    }

    private static boolean isEscaped(String text, int index) {
        var backslashes = 0;
        var cursor = index - 1;
        while (cursor >= 0 && text.charAt(cursor) == '\\') {
            backslashes++;
            cursor--;
        }
        return backslashes % 2 == 1;
    }

    /**
     * Non-whitespace after the opener, or the damage itself there. Python's {@code str.isspace}
     * is the union of Java's {@code isWhitespace} and {@code isSpaceChar}.
     */
    private static boolean opensInlineMath(String text, int index) {
        if (index + 1 >= text.length()) {
            return false;
        }
        return !isPythonWhitespace(text.charAt(index + 1))
            || matchesAt(WS_LATEX_MATH_PATTERN, text, index + 1);
    }

    /** Pandoc's closer rule: no whitespace before, no digit after. */
    private static boolean closesInlineMath(String text, int index) {
        if (index == 0 || isPythonWhitespace(text.charAt(index - 1))) {
            return false;
        }
        return index + 1 >= text.length() || !isPythonDigit(text.charAt(index + 1));
    }

    private static int nextDelimiter(String text, int start, String delimiter) {
        var cursor = start;
        while (cursor < text.length()) {
            if (text.startsWith(delimiter, cursor) && !isEscaped(text, cursor)) {
                return cursor;
            }
            cursor++;
        }
        return -1;
    }

    private static boolean matchesAt(Pattern pattern, String text, int offset) {
        var matcher = pattern.matcher(text);
        matcher.region(offset, text.length()).useTransparentBounds(true);
        return matcher.lookingAt();
    }

    private static boolean isPythonWhitespace(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value);
    }

    private static boolean isPythonDigit(char value) {
        return Character.isDigit(value) || Character.getType(value) == Character.OTHER_NUMBER;
    }

    /**
     * Content gate: pairing says <em>where</em> a span is, this says whether it is math. A body
     * containing a double quote or a backtick is code; an inline span whose body crosses a line
     * break, or runs past {@link #MAX_INLINE_MATH_CHARS}, is not a formula. Display math is exempt
     * from both. A break the residue pattern matches is NOT a break -- a decoded {@code \nabla} is
     * a newline followed by {@code abla}, so a blanket veto would reject the damage itself.
     */
    private static boolean spanIsPlausiblyMath(String span, String delimiter) {
        var body = span.substring(delimiter.length(), span.length() - delimiter.length());
        for (var mark : CODE_MARKS_IN_MATH_SPAN) {
            if (body.contains(mark)) {
                return false;
            }
        }
        if (!delimiter.equals("$")) {
            return true;
        }
        if (body.length() > MAX_INLINE_MATH_CHARS) {
            return false;
        }
        // Line breaks are rejected by CHARACTER CLASS, not by example: any CR or LF, whichever
        // line ending the text uses.
        for (var offset = 0; offset < body.length(); offset++) {
            var current = body.charAt(offset);
            if ((current == '\r' || current == '\n')
                && !matchesAt(WS_LATEX_MATH_PATTERN, body, offset)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Every Markdown code region, left to right. Block structure first: fences, then inline spans
     * within each paragraph of the text the fences leave behind. Both boundaries are there for the
     * same reason -- an inline span cannot cross either, and letting one cross lets a stray
     * backtick eat a real span's opener and expose that span's own code.
     */
    private static List<Region> markdownCodeRegions(String text) {
        var regions = new ArrayList<Region>();
        var cursor = 0;
        var fence = MD_FENCE_REGION_PATTERN.matcher(text);
        while (fence.find()) {
            collectInlineCodeRegions(text, cursor, fence.start(), regions);
            regions.add(new Region(fence.start(), fence.end()));
            cursor = fence.end();
        }
        collectInlineCodeRegions(text, cursor, text.length(), regions);
        return regions;
    }

    /**
     * Inline code spans in {@code text[start:end]}, one paragraph at a time. Searched in place
     * rather than on a sliced copy so the opener's lookbehind still sees the character before
     * each range.
     */
    private static void collectInlineCodeRegions(String text, int start, int end, List<Region> regions) {
        var cursor = start;
        var blank = MD_BLANK_LINE_PATTERN.matcher(text);
        blank.region(start, end);
        while (blank.find()) {
            collectInlineSpans(text, cursor, blank.start(), regions);
            cursor = blank.end();
        }
        collectInlineSpans(text, cursor, end, regions);
    }

    private static void collectInlineSpans(String text, int start, int end, List<Region> regions) {
        var matcher = MD_INLINE_CODE_PATTERN.matcher(text);
        matcher.region(start, end).useTransparentBounds(true);
        while (matcher.find()) {
            regions.add(new Region(matcher.start(), matcher.end()));
        }
    }
}
