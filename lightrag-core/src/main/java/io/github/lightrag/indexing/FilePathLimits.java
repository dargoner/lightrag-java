package io.github.lightrag.indexing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relation {@code file_path} accumulation, a direct port of upstream's MAX_FILE_PATHS handling
 * ({@code operate.py:2634-2689} nodes, {@code :3067-3120} edges; {@code constants.py:84-88}).
 *
 * <p>Entity {@code file_path} is deliberately out of scope: the Java entity record has no such
 * column, so only relations keep the accumulated path list. The cap is display-only upstream
 * ({@code constants.py:85}); it never feeds query behaviour.</p>
 */
public final class FilePathLimits {
    public static final int DEFAULT_MAX_FILE_PATHS = 75;

    /** Upstream {@code DEFAULT_FILE_PATH_MORE_PLACEHOLDER} ({@code constants.py:88}). */
    public static final String PLACEHOLDER = "truncated";

    private static final String PLACEHOLDER_PREFIX = "..." + PLACEHOLDER;
    private static final Logger log = LoggerFactory.getLogger(FilePathLimits.class);

    private FilePathLimits() {
    }

    /**
     * Dedupes {@code filePaths} preserving order, drops pre-existing truncation placeholders so they
     * never accumulate, and when the result exceeds {@code limit} keeps the head ({@code KEEP}) or the
     * tail ({@code FIFO}) plus the upstream marker {@code "...truncated...(KEEP Old)"} /
     * {@code "...truncated...(FIFO)"}. Blank entries are dropped, matching upstream's truthiness
     * filter.
     */
    public static List<String> apply(List<String> filePaths, int limit, SourceIdLimits.Method method) {
        var paths = Objects.requireNonNull(filePaths, "filePaths");
        Objects.requireNonNull(method, "method");
        if (limit <= 0) {
            return List.of();
        }
        var collected = new ArrayList<String>(paths.size());
        var seen = new LinkedHashSet<String>();
        var hasPlaceholder = false;
        for (var path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            if (path.startsWith(PLACEHOLDER_PREFIX)) {
                hasPlaceholder = true;
                continue;
            }
            if (seen.add(path)) {
                collected.add(path);
            }
        }
        if (collected.size() <= limit) {
            return List.copyOf(collected);
        }
        var limited = method == SourceIdLimits.Method.FIFO
            ? new ArrayList<>(collected.subList(collected.size() - limit, collected.size()))
            : new ArrayList<>(collected.subList(0, limit));
        limited.add(method == SourceIdLimits.Method.FIFO ? "..." + PLACEHOLDER + "...(FIFO)" : "..." + PLACEHOLDER + "...(KEEP Old)");
        // Upstream marks the count with a trailing '+' when the input already carried a placeholder
        // ("the real total is higher than this list shows") — operate.py:2679-2683, :3111-3115.
        log.info(
            "file_path_limit_event=truncated originalCount={} limit={} method={}",
            hasPlaceholder ? collected.size() + "+" : Integer.toString(collected.size()),
            limit,
            method
        );
        return List.copyOf(limited);
    }
}
