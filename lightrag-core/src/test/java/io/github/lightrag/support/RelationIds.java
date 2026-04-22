package io.github.lightrag.support;

import io.github.lightrag.indexing.RelationCanonicalizer;

public final class RelationIds {
    private RelationIds() {
    }

    public static String relationId(String srcId, String tgtId) {
        return RelationCanonicalizer.canonicalize(srcId, tgtId).relationId();
    }
}
