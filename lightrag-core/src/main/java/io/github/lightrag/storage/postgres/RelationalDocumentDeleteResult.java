package io.github.lightrag.storage.postgres;

record RelationalDocumentDeleteResult(
    int documentsDeleted,
    int chunksDeleted,
    int statusesDeleted,
    int graphSnapshotsDeleted,
    int graphJournalsDeleted
) {
}
