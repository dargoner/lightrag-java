package io.github.lightragjava.indexing;

import java.util.List;

record StructuredDocument(String documentId, String title, List<StructuredBlock> blocks) {
}
