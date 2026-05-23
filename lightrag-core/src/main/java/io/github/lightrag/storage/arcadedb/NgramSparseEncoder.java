package io.github.lightrag.storage.arcadedb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class NgramSparseEncoder implements SparseEncoder {
    private static final int DEFAULT_MAX_SPARSE_TOKENS = 256;
    private static final int MAX_CJK_NGRAM = 3;

    private final int maxSparseTokens;

    public NgramSparseEncoder() {
        this(DEFAULT_MAX_SPARSE_TOKENS);
    }

    public NgramSparseEncoder(int maxSparseTokens) {
        if (maxSparseTokens <= 0) {
            throw new IllegalArgumentException("maxSparseTokens must be positive");
        }
        this.maxSparseTokens = maxSparseTokens;
    }

    @Override
    public SparseVector encodeDocument(String text, List<String> keywords) {
        return encode(composeText(text, keywords));
    }

    @Override
    public SparseVector encodeQuery(String text, List<String> keywords) {
        return encode(composeText(text, keywords));
    }

    List<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        var tokens = new ArrayList<String>();
        var latin = new StringBuilder();
        var cjk = new StringBuilder();
        text.toLowerCase(java.util.Locale.ROOT).codePoints().forEach(codePoint -> {
            if (isCjk(codePoint)) {
                flushLatin(latin, tokens);
                cjk.appendCodePoint(codePoint);
            } else if (Character.isLetterOrDigit(codePoint)) {
                flushCjk(cjk, tokens);
                latin.appendCodePoint(codePoint);
            } else {
                flushLatin(latin, tokens);
                flushCjk(cjk, tokens);
            }
        });
        flushLatin(latin, tokens);
        flushCjk(cjk, tokens);
        return List.copyOf(tokens);
    }

    private SparseVector encode(String text) {
        if (text == null || text.isBlank()) {
            return new SparseVector(List.of(), List.of());
        }
        var frequencies = new LinkedHashMap<Integer, Double>();
        for (var token : tokens(text)) {
            frequencies.merge(stableTokenId(token), 1.0d, Double::sum);
        }
        if (frequencies.isEmpty()) {
            return new SparseVector(List.of(), List.of());
        }
        var weighted = new ArrayList<Map.Entry<Integer, Double>>();
        for (var entry : frequencies.entrySet()) {
            weighted.add(Map.entry(entry.getKey(), 1.0d + Math.log(entry.getValue())));
        }
        weighted.sort(Comparator.<Map.Entry<Integer, Double>>comparingDouble(Map.Entry::getValue).reversed()
            .thenComparingInt(Map.Entry::getKey));
        var selected = new TreeMap<Integer, Double>();
        weighted.stream()
            .limit(maxSparseTokens)
            .forEach(entry -> selected.put(entry.getKey(), entry.getValue()));
        return new SparseVector(
            selected.keySet().stream().toList(),
            selected.values().stream().toList()
        );
    }

    private static String composeText(String text, List<String> keywords) {
        var values = new LinkedHashSet<String>();
        if (text != null && !text.isBlank()) {
            values.add(text.strip());
        }
        for (var keyword : Objects.requireNonNull(keywords, "keywords")) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            values.add(keyword.strip());
        }
        return String.join(" ", values);
    }

    private static int stableTokenId(String token) {
        var hash = 0x811c9dc5;
        for (int index = 0; index < token.length(); index++) {
            hash ^= token.charAt(index);
            hash *= 0x01000193;
        }
        return hash & 0x7fffffff;
    }

    private static void flushLatin(StringBuilder latin, List<String> tokens) {
        if (latin.isEmpty()) {
            return;
        }
        tokens.add(latin.toString());
        latin.setLength(0);
    }

    private static void flushCjk(StringBuilder cjk, List<String> tokens) {
        if (cjk.isEmpty()) {
            return;
        }
        var codePoints = cjk.codePoints().toArray();
        for (var n = 1; n <= Math.min(MAX_CJK_NGRAM, codePoints.length); n++) {
            for (var start = 0; start <= codePoints.length - n; start++) {
                tokens.add(new String(codePoints, start, n));
            }
        }
        cjk.setLength(0);
    }

    private static boolean isCjk(int codePoint) {
        var block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_G
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }
}
