package io.github.lightrag.model;

import java.util.List;

public interface EmbeddingModel {
    List<List<Double>> embedAll(List<String> texts);

    /**
     * Identity of the embedding space this model produces, recorded with the workspace vectors so a write
     * from a different model can be refused. Implementations backed by a named model should override this;
     * the default keeps anonymous and test models usable.
     */
    default String cacheIdentity() {
        return "unknown";
    }
}
