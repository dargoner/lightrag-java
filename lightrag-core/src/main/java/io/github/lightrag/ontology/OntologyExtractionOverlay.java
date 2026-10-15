package io.github.lightrag.ontology;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Soft-guidance overlay injected into the knowledge extraction prompt.
 *
 * <p>This record is independent of {@link io.github.lightrag.api.GraphExtractionOptions}
 * so that existing consumers of that record keep their constructor/binary
 * compatibility. The host converts an approved {@link Ontology} into this overlay
 * plus the standard options (entity types / relation types / examples).
 *
 * @param patterns        relation type label → directed source/target patterns.
 *                        The key is the display relation type label; patterns use
 *                        display entity type labels for readability in the prompt.
 * @param attributeBlocks entity or relation type label → human-readable attribute
 *                        description block rendered as soft guidance.
 */
public record OntologyExtractionOverlay(
    Map<String, List<OntologyPattern>> patterns,
    Map<String, String> attributeBlocks,
    ExtractionMode mode
) {
    public OntologyExtractionOverlay {
        patterns = patterns == null ? Map.of() : deepCopyPatterns(patterns);
        attributeBlocks = attributeBlocks == null ? Map.of() : Map.copyOf(attributeBlocks);
        mode = mode == null ? ExtractionMode.PREFER : mode;
    }

    /** Returns an empty overlay. */
    public static OntologyExtractionOverlay empty() {
        return new OntologyExtractionOverlay(Map.of(), Map.of(), ExtractionMode.PREFER);
    }

    /** Returns an overlay with the requested mode. */
    public OntologyExtractionOverlay withMode(ExtractionMode newMode) {
        return new OntologyExtractionOverlay(patterns, attributeBlocks, newMode);
    }

    /**
     * Builds an overlay from an ontology using {@link ExtractionMode#PREFER}.
     *
     * <p>Relation type patterns are grouped by the relation type's display label.
     * Attribute blocks are generated for entity and relation types that declare
     * properties.
     */
    public static OntologyExtractionOverlay from(Ontology ontology) {
        return from(ontology, ExtractionMode.PREFER);
    }

    /**
     * Builds an overlay from an ontology with the requested extraction mode.
     *
     * <p>Relation type patterns are grouped by the relation type's display label.
     * Attribute blocks are generated for entity and relation types that declare
     * properties.
     */
    public static OntologyExtractionOverlay from(Ontology ontology, ExtractionMode mode) {
        Objects.requireNonNull(ontology, "ontology");

        Map<String, List<OntologyPattern>> patterns = new LinkedHashMap<>();
        for (OntologyRelationType relation : ontology.relationTypes()) {
            if (!relation.patterns().isEmpty()) {
                patterns.put(relation.label(), List.copyOf(relation.patterns()));
            }
        }

        Map<String, String> attributeBlocks = new LinkedHashMap<>();
        for (OntologyEntityType entity : ontology.entityTypes()) {
            if (!entity.properties().isEmpty()) {
                attributeBlocks.put(entity.label(), renderAttributeBlock(entity.label(), entity.properties()));
            }
        }
        for (OntologyRelationType relation : ontology.relationTypes()) {
            if (!relation.properties().isEmpty()) {
                attributeBlocks.put(relation.label(), renderAttributeBlock(relation.label(), relation.properties()));
            }
        }

        return new OntologyExtractionOverlay(patterns, attributeBlocks, mode);
    }

    private static String renderAttributeBlock(String label, List<OntologyProperty> properties) {
        StringBuilder builder = new StringBuilder();
        builder.append(label).append(" attributes:");
        for (OntologyProperty property : properties) {
            builder.append("\n  - ").append(property.name());
            if (property.type() != null) {
                builder.append(" (").append(property.type().name().toLowerCase()).append(")");
            }
            if (property.description() != null && !property.description().isEmpty()) {
                builder.append(": ").append(property.description());
            }
        }
        return builder.toString();
    }

    private static Map<String, List<OntologyPattern>> deepCopyPatterns(Map<String, List<OntologyPattern>> patterns) {
        Map<String, List<OntologyPattern>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<OntologyPattern>> entry : patterns.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }
}
