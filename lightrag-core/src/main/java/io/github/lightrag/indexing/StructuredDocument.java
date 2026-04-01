package io.github.lightrag.indexing;

import java.util.List;

record StructuredDocument(String documentId, String title, List<StructuredBlock> blocks) {
}
