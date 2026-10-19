package io.github.lightrag.rdf;

import java.util.Set;

/**
 * Vocabulary constants used by the importer.
 *
 * <p>The {@code lg:} extension terms are not bound to a fixed namespace: the
 * core exporter writes them below the configured vocabulary base, so the
 * importer discovers the namespace from the document (or accepts an explicit
 * {@link RdfImportOptions#extensionNamespace()}). Only the local names are
 * fixed.
 */
final class RdfVocabulary {

    static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    static final String RDFS_NS = "http://www.w3.org/2000/01/rdf-schema#";
    static final String OWL_NS = "http://www.w3.org/2002/07/owl#";
    static final String XSD_NS = "http://www.w3.org/2001/XMLSchema#";
    static final String SH_NS = "http://www.w3.org/ns/shacl#";

    static final String OWNER = "owner";
    static final String VALUE_TYPE = "valueType";
    static final String RELATION_PATTERN = "relationPattern";
    static final String RELATION = "relation";
    static final String SOURCE_CLASS = "sourceClass";
    static final String TARGET_CLASS = "targetClass";
    static final String ATTRIBUTE_NAME = "attributeName";
    static final String DESCRIPTION = "description";
    static final String SOURCE_BINDING = "sourceBinding";
    static final String RELATION_ATTRIBUTE_TYPE = "RelationAttribute";
    static final String SOURCE_BINDING_TYPE = "SourceBinding";
    static final String RELATION_PATTERN_TYPE = "RelationPattern";
    static final String TABLE_NAME = "tableName";
    static final String COLUMN = "column";
    static final String REF_TABLE_NAME = "refTableName";
    static final String REF_COLUMN = "refColumn";
    static final String NULLABLE = "nullable";
    static final String DEFINITION_HASH = "definitionHash";
    static final String VERSION = "version";

    static final Set<String> EXTENSION_LOCAL_NAMES = Set.of(
        OWNER,
        VALUE_TYPE,
        RELATION_PATTERN,
        RELATION,
        SOURCE_CLASS,
        TARGET_CLASS,
        ATTRIBUTE_NAME,
        DESCRIPTION,
        SOURCE_BINDING,
        RELATION_ATTRIBUTE_TYPE,
        SOURCE_BINDING_TYPE,
        RELATION_PATTERN_TYPE,
        TABLE_NAME,
        COLUMN,
        REF_TABLE_NAME,
        REF_COLUMN,
        NULLABLE,
        DEFINITION_HASH,
        VERSION
    );

    private RdfVocabulary() {}
}
