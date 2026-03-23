package io.github.lightragjava.indexing;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SentenceBoundaryAnalyzer {
    List<String> split(String content) {
        var iterator = BreakIterator.getSentenceInstance(Locale.ROOT);
        iterator.setText(content);

        var sentences = new ArrayList<String>();
        var start = iterator.first();
        for (var end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            var sentence = content.substring(start, end).trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }
        if (sentences.isEmpty() && !content.isBlank()) {
            sentences.add(content.strip());
        }
        return List.copyOf(sentences);
    }
}
