package io.github.lightrag.ontology;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Renders the business-level (logical) schema segment of an ontology for
 * retrieval prompts.
 *
 * <p>The output carries only queryable business values - entity type labels,
 * relationship keywords, and directed relationship patterns. It never carries
 * physical labels or the type-property note: those belong to the host, which
 * appends them from a {@link PhysicalGraphProfile} so the logical and
 * physical views stay separate and do not substitute for each other.
 *
 * <p>Declared properties are materialized on each entity or relation as a
 * canonical JSON attribute bag; they are <em>not</em> physical fields, columns,
 * or independently indexed values. Rendering them as queryable paths would
 * invite Cypher predicates that cannot work. That is why
 * {@link RenderOptions#includeDeclaredProperties()} still defaults to
 * {@code false}: properties are materialized into a bag, not made physically
 * queryable. The option only adds descriptive lines when a host explicitly asks
 * for them; it never changes the physical schema contract.
 */
public final class OntologySchemaRenderer {

    private OntologySchemaRenderer() {}

    /**
     * Render options.
     *
     * <p>{@code includeDeclaredProperties} defaults to {@code false} and stays
     * that way after attribute materialization: declared properties live in a
     * JSON bag and cannot be filtered physically. When enabled, the renderer
     * only adds descriptive property lines for humans and prompts; it does not
     * imply that a property is a queryable field.
     */
    public record RenderOptions(boolean includeDeclaredProperties) {

        /** Defaults: no declared-property lines. */
        public static RenderOptions defaults() {
            return new RenderOptions(false);
        }
    }

    /**
     * Renders the logical schema segment.
     *
     * @param ontology the applied ontology; must not be null
     * @param options  render options; null means {@link RenderOptions#defaults()}
     * @return the segment text, or an empty string when the ontology declares nothing
     */
    public static String render(Ontology ontology, RenderOptions options) {
        Objects.requireNonNull(ontology, "ontology");
        RenderOptions effective = options == null ? RenderOptions.defaults() : options;

        List<String> lines = new ArrayList<>();
        List<String> entityLabels = labels(ontology.entityTypes(), OntologyEntityType::label);
        List<String> relationLabels =
            labels(ontology.relationTypes(), OntologyRelationType::label);
        if (!entityLabels.isEmpty()) {
            lines.add("Business entity types: " + String.join(", ", entityLabels));
        }
        if (!relationLabels.isEmpty()) {
            lines.add("Business relationship keywords: " + String.join(", ", relationLabels));
        }
        List<String> patternLines = patternLines(ontology);
        if (!patternLines.isEmpty()) {
            lines.add("Business relationship patterns:");
            for (String pattern : patternLines) {
                lines.add("- " + pattern);
            }
            lines.add(
                "Note: the physical graph merges relationships by endpoint pair, so pattern"
                    + " direction is a reference only.");
        }
        if (effective.includeDeclaredProperties()) {
            lines.addAll(propertyLines(ontology.entityTypes(), OntologyEntityType::label,
                OntologyEntityType::properties));
            lines.addAll(propertyLines(ontology.relationTypes(), OntologyRelationType::label,
                OntologyRelationType::properties));
        }
        return String.join("\n", lines);
    }

    private static <T> List<String> labels(List<T> types, Function<T, String> label) {
        List<String> labels = new ArrayList<>(types.size());
        for (T type : types) {
            String value = label.apply(type);
            if (value != null && !value.isBlank()) {
                labels.add(value);
            }
        }
        return labels;
    }

    private static List<String> patternLines(Ontology ontology) {
        List<String> patterns = new ArrayList<>();
        for (OntologyRelationType relation : ontology.relationTypes()) {
            for (OntologyPattern pattern : relation.patterns()) {
                patterns.add(
                    pattern.sourceLabel() + " -[" + relation.label() + "]-> "
                        + pattern.targetLabel());
            }
        }
        return patterns;
    }

    private static <T> List<String> propertyLines(
        List<T> types,
        Function<T, String> label,
        Function<T, List<OntologyProperty>> properties
    ) {
        List<String> lines = new ArrayList<>();
        for (T type : types) {
            List<OntologyProperty> declared = properties.apply(type);
            if (declared == null || declared.isEmpty()) {
                continue;
            }
            List<String> rendered = new ArrayList<>(declared.size());
            for (OntologyProperty property : declared) {
                if (property.name() == null || property.name().isBlank()) {
                    continue;
                }
                String suffix = property.type() == null
                    ? ""
                    : " (" + property.type().name().toLowerCase(Locale.ROOT) + ")";
                rendered.add(property.name() + suffix);
            }
            if (!rendered.isEmpty()) {
                lines.add(label.apply(type) + " properties: " + String.join(", ", rendered));
            }
        }
        return lines;
    }
}
