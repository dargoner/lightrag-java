package io.github.lightrag.model;

import java.util.List;

public interface EmbeddingModel {
    List<List<Double>> embedAll(List<String> texts);
}
