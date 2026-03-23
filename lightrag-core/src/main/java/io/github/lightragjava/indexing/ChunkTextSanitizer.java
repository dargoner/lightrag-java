package io.github.lightragjava.indexing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

final class ChunkTextSanitizer {
    private static final Pattern PLAIN_PAGE_NUMBER_PATTERN = Pattern.compile(
        "^(?:[-_\\u2013\\u2014\\u2015\\s]*)\\d+(?:\\s*/\\s*\\d+)?(?:[-_\\u2013\\u2014\\u2015\\s]*)$"
    );
    private static final Pattern CHINESE_PAGE_NUMBER_PATTERN = Pattern.compile(
        "^第\\s*\\d+\\s*页(?:\\s*/\\s*共?\\s*\\d+\\s*页)?$"
    );
    private static final Pattern TOC_TITLE_PATTERN = Pattern.compile("^目\\s*录$");
    private static final Pattern MINERU_NOISE_PATTERN = Pattern.compile("^text[_\\s-]*list\\s+text$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOC_ENTRY_PATTERN = Pattern.compile(
        "^[\\p{IsHan}A-Za-z0-9一二三四五六七八九十百千万章节篇部卷条款目()（）《》【】、，,:：;；\\-\\s]+[.．·•…]{2,}\\s*\\d+$"
    );
    private static final Pattern SEPARATOR_PATTERN = Pattern.compile("^[._·•…\\-\\u2013\\u2014\\u2015\\s]{3,}$");

    List<String> sanitizeDocumentLines(String text) {
        var sanitized = new ArrayList<String>();
        boolean previousBlank = true;
        for (var rawLine : text.split("\\R", -1)) {
            var line = rawLine.stripTrailing();
            if (isNoiseLine(line)) {
                continue;
            }
            if (line.isBlank()) {
                if (!previousBlank) {
                    sanitized.add("");
                }
                previousBlank = true;
                continue;
            }
            sanitized.add(line);
            previousBlank = false;
        }
        if (!sanitized.isEmpty() && sanitized.get(sanitized.size() - 1).isBlank()) {
            sanitized.remove(sanitized.size() - 1);
        }
        return List.copyOf(sanitized);
    }

    String sanitizeBlockText(String text) {
        var sanitizedLines = sanitizeDocumentLines(text);
        if (sanitizedLines.isEmpty()) {
            return "";
        }
        return repairConservativeOcrArtifacts(String.join("\n", sanitizedLines).strip());
    }

    Set<String> repeatedShortPageChromeTexts(List<ParsedBlock> blocks) {
        return blocks.stream()
            .map(block -> new PageTextCandidate(block.pageNo(), sanitizeBlockText(block.text())))
            .filter(candidate -> candidate.pageNo() != null)
            .filter(candidate -> !candidate.text().isBlank())
            .filter(candidate -> looksLikeRepeatedPageChrome(candidate.text()))
            .collect(Collectors.groupingBy(
                candidate -> candidate.text(),
                Collectors.mapping(PageTextCandidate::pageNo, Collectors.toSet())
            ))
            .entrySet().stream()
            .filter(entry -> entry.getValue().size() >= 3)
            .map(Map.Entry::getKey)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    boolean isNoiseLine(String line) {
        var normalized = line.strip();
        if (normalized.isEmpty()) {
            return false;
        }
        if (MINERU_NOISE_PATTERN.matcher(normalized).matches()) {
            return true;
        }
        if (TOC_TITLE_PATTERN.matcher(normalized).matches()) {
            return true;
        }
        if (PLAIN_PAGE_NUMBER_PATTERN.matcher(normalized).matches()) {
            return true;
        }
        if (CHINESE_PAGE_NUMBER_PATTERN.matcher(compact(normalized)).matches()) {
            return true;
        }
        if (TOC_ENTRY_PATTERN.matcher(normalized).matches()) {
            return true;
        }
        return SEPARATOR_PATTERN.matcher(normalized).matches();
    }

    private static String repairConservativeOcrArtifacts(String text) {
        var repaired = text
            .replace("\\%", "%")
            .replaceAll("(?<=\\d)\\s+%", "%")
            .replaceAll("(?<=[A-Za-z]{2})-\\n(?=[A-Za-z]{2})", "");
        return repaired;
    }

    private static boolean looksLikeRepeatedPageChrome(String text) {
        var normalized = text.strip();
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > 32) {
            return false;
        }
        if (normalized.contains("\n")) {
            return false;
        }
        return !normalized.contains("。")
            && !normalized.contains("！")
            && !normalized.contains("？")
            && !normalized.contains(".")
            && !normalized.contains("!")
            && !normalized.contains("?")
            && !normalized.contains(":")
            && !normalized.contains("：")
            && !normalized.contains(",")
            && !normalized.contains("，")
            && !normalized.contains(";")
            && !normalized.contains("；");
    }

    private static String compact(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private record PageTextCandidate(Integer pageNo, String text) {
    }
}
