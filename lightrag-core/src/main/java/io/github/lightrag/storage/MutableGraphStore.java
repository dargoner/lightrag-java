package io.github.lightrag.storage;

import java.util.List;

public interface MutableGraphStore extends GraphStore {
    int deleteEntities(List<String> entityIds);

    int deleteRelations(List<String> relationIds);
}
