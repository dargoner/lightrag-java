package io.github.lightragjava.indexing;

final class SmartChunkMetadata {
    static final String SECTION_PATH = "smart_chunker.section_path";
    static final String CONTENT_TYPE = "smart_chunker.content_type";
    static final String SOURCE_BLOCK_IDS = "smart_chunker.source_block_ids";
    static final String TABLE_PART_INDEX = "smart_chunker.table_part_index";
    static final String TABLE_PART_TOTAL = "smart_chunker.table_part_total";
    static final String PREV_CHUNK_ID = "smart_chunker.prev_chunk_id";
    static final String NEXT_CHUNK_ID = "smart_chunker.next_chunk_id";
    static final String IMAGE_PATHS = "smart_chunker.image_paths";
    static final String PRIMARY_IMAGE_PATH = "smart_chunker.primary_image_path";
    static final String IMAGE_REF_MODE = "smart_chunker.image_ref_mode";
    static final String PARSE_QUALITY = "smart_chunker.parse_quality";
    static final String IMAGE_PATH_UNAVAILABLE = "smart_chunker.image_path_unavailable";

    private SmartChunkMetadata() {
    }
}
