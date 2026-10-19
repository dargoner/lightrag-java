package io.github.lightrag.ontology;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OntologyStructureValidatorTest {

    @Test
    void rejectsKernelReservedEntityLabel() {
        var ontology = new Ontology(
            List.of(new OntologyEntityType("Chunk", "kernel concept", List.of(), null)),
            List.of(),
            null,
            Map.of()
        );

        assertThatThrownBy(() -> OntologyStructureValidator.validate(ontology))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Reserved entity label conflicts with a kernel structural concept: chunk");
    }

    @Test
    void rejectsKernelReservedRelationLabel() {
        var ontology = new Ontology(
            List.of(),
            List.of(new OntologyRelationType("Relation", "kernel concept", List.of(), List.of(), null)),
            null,
            Map.of()
        );

        assertThatThrownBy(() -> OntologyStructureValidator.validate(ontology))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Reserved relation label conflicts with a kernel structural concept: relation");
    }

    @Test
    void rejectsExtractionFallbackLabelsCaseInsensitively() {
        var ontology = new Ontology(
            List.of(new OntologyEntityType("UNKNOWN", "fallback", List.of(), null)),
            List.of(),
            null,
            Map.of()
        );

        assertThatThrownBy(() -> OntologyStructureValidator.validate(ontology))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Reserved entity label conflicts with the extraction fallback: unknown");
    }

    @Test
    void rejectsSameSourceTableBoundByTwoEntityLabels() {
        var ontology = new Ontology(
            List.of(
                new OntologyEntityType("User", "A user", List.of(), new SourceBinding("users", null, null, null, null)),
                new OntologyEntityType("Customer", "A customer", List.of(), new SourceBinding("\"USERS\"", null, null, null, null))
            ),
            List.of(),
            null,
            Map.of()
        );

        assertThatThrownBy(() -> OntologyStructureValidator.validate(ontology))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Source signature 'table:users' is bound by multiple ontology labels")
            .hasMessageContaining("entity 'user'")
            .hasMessageContaining("entity 'customer'");
    }

    @Test
    void rejectsQualifiedSourceTableBoundWithDifferentQuoting() {
        var ontology = new Ontology(
            List.of(
                new OntologyEntityType("User", "A user", List.of(), new SourceBinding("public.users", null, null, null, null)),
                new OntologyEntityType("Customer", "A customer", List.of(),
                    new SourceBinding("\"PUBLIC\".\"USERS\"", null, null, null, null))
            ),
            List.of(),
            null,
            Map.of()
        );

        assertThatThrownBy(() -> OntologyStructureValidator.validate(ontology))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Source signature 'table:public.users' is bound by multiple ontology labels")
            .hasMessageContaining("entity 'user'")
            .hasMessageContaining("entity 'customer'");
    }

    @Test
    void normalizeIdentityUnifiesQualifiedComponentsAcrossQuotingStyles() {
        assertThat(OntologyStructureValidator.normalizeIdentity("public.users")).isEqualTo("public.users");
        assertThat(OntologyStructureValidator.normalizeIdentity("\"PUBLIC\".\"USERS\"")).isEqualTo("public.users");
        assertThat(OntologyStructureValidator.normalizeIdentity(" \"Public\" . \"Users\" ")).isEqualTo("public.users");
        assertThat(OntologyStructureValidator.normalizeIdentity("[PUBLIC].[USERS]")).isEqualTo("public.users");
    }

    @Test
    void distinguishesQuotedDotsFromComponentBoundaries() {
        var distinct = new Ontology(
            List.of(
                new OntologyEntityType("Dotted", "Schema containing a dot", List.of(),
                    new SourceBinding("\"a.b\".c", null, null, null, null)),
                new OntologyEntityType("Inner", "Table containing a dot", List.of(),
                    new SourceBinding("a.\"b.c\"", null, null, null, null))
            ),
            List.of(),
            null,
            Map.of()
        );
        OntologyStructureValidator.validate(distinct);

        var colliding = new Ontology(
            List.of(
                new OntologyEntityType("Inner", "Table containing a dot", List.of(),
                    new SourceBinding("a.\"b.c\"", null, null, null, null)),
                new OntologyEntityType("InnerQuoted", "Same components, different quoting", List.of(),
                    new SourceBinding("\"A\".\"B.C\"", null, null, null, null))
            ),
            List.of(),
            null,
            Map.of()
        );

        assertThatThrownBy(() -> OntologyStructureValidator.validate(colliding))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("is bound by multiple ontology labels");
    }

    @Test
    void keepsComponentBoundariesForPropertyAndForeignKeySignatures() {
        var distinctProperties = new Ontology(
            List.of(
                new OntologyEntityType("Dotted", "d", List.of(new OntologyProperty(
                    "x", OntologyPropertyType.STRING, null,
                    new SourceBinding("\"a.b\"", "c", null, null, null))), null),
                new OntologyEntityType("Inner", "d", List.of(new OntologyProperty(
                    "y", OntologyPropertyType.STRING, null,
                    new SourceBinding("a", "\"b.c\"", null, null, null))), null)
            ),
            List.of(),
            null,
            Map.of()
        );
        OntologyStructureValidator.validate(distinctProperties);

        var collidingProperties = new Ontology(
            List.of(
                new OntologyEntityType("Inner", "d", List.of(new OntologyProperty(
                    "y", OntologyPropertyType.STRING, null,
                    new SourceBinding("a", "\"b.c\"", null, null, null))), null),
                new OntologyEntityType("InnerQuoted", "d", List.of(new OntologyProperty(
                    "z", OntologyPropertyType.STRING, null,
                    new SourceBinding("\"A\"", "\"B.C\"", null, null, null))), null)
            ),
            List.of(),
            null,
            Map.of()
        );
        assertThatThrownBy(() -> OntologyStructureValidator.validate(collidingProperties))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("is bound by multiple ontology labels");

        var distinctRelations = new Ontology(
            List.of(),
            List.of(
                new OntologyRelationType("r1", "d", List.of(), List.of(),
                    new SourceBinding("\"a.b\"", "c", "d", "e", null)),
                new OntologyRelationType("r2", "d", List.of(), List.of(),
                    new SourceBinding("a", "\"b.c\"", "d", "e", null))
            ),
            null,
            Map.of()
        );
        OntologyStructureValidator.validate(distinctRelations);

        var collidingRelations = new Ontology(
            List.of(),
            List.of(
                new OntologyRelationType("r2", "d", List.of(), List.of(),
                    new SourceBinding("a", "\"b.c\"", "d", "e", null)),
                new OntologyRelationType("r3", "d", List.of(), List.of(),
                    new SourceBinding("\"A\"", "\"B.C\"", "\"D\"", "\"E\"", null))
            ),
            null,
            Map.of()
        );
        assertThatThrownBy(() -> OntologyStructureValidator.validate(collidingRelations))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("is bound by multiple ontology labels");
    }

    @Test
    void rejectsQualifiedPropertySignaturesWithDifferentQuoting() {
        var ontology = new Ontology(
            List.of(
                new OntologyEntityType("User", "A user", List.of(new OntologyProperty(
                    "id", OntologyPropertyType.STRING, null,
                    new SourceBinding("\"public\".\"users\"", "\"ID\"", null, null, null)
                )), null),
                new OntologyEntityType("Account", "An account", List.of(new OntologyProperty(
                    "ownerId", OntologyPropertyType.STRING, null,
                    new SourceBinding("public.users", "id", null, null, null)
                )), null)
            ),
            List.of(),
            null,
            Map.of()
        );

        assertThatThrownBy(() -> OntologyStructureValidator.validate(ontology))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Source signature 'column:public.users.id' is bound by multiple ontology labels");
    }

    @Test
    void rejectsQualifiedForeignKeySignaturesWithDifferentQuoting() {
        var ontology = new Ontology(
            List.of(),
            List.of(
                new OntologyRelationType(
                    "owns",
                    "ownership",
                    List.of(),
                    List.of(),
                    new SourceBinding("public.orders", "user_id", "public.users", "id", null)
                ),
                new OntologyRelationType(
                    "belongs to",
                    "membership",
                    List.of(),
                    List.of(),
                    new SourceBinding("\"PUBLIC\".\"ORDERS\"", "\"USER_ID\"", "\"PUBLIC\".\"USERS\"", "\"ID\"", null)
                )
            ),
            null,
            Map.of()
        );

        assertThatThrownBy(() -> OntologyStructureValidator.validate(ontology))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                "Source signature 'fk:public.orders.user_id->public.users.id' is bound by multiple ontology labels"
            );
    }

    @Test
    void rejectsRelationForeignKeySignatureCollisions() {
        var ontology = new Ontology(
            List.of(),
            List.of(
                new OntologyRelationType(
                    "owns",
                    "ownership",
                    List.of(),
                    List.of(),
                    new SourceBinding("orders", "user_id", "users", "id", null)
                ),
                new OntologyRelationType(
                    "belongs to",
                    "membership",
                    List.of(),
                    List.of(),
                    new SourceBinding("ORDERS", "USER_ID", "USERS", "ID", null)
                )
            ),
            null,
            Map.of()
        );

        assertThatThrownBy(() -> OntologyStructureValidator.validate(ontology))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Source signature 'fk:orders.user_id->users.id' is bound by multiple ontology labels")
            .hasMessageContaining("relation 'owns'")
            .hasMessageContaining("relation 'belongs to'");
    }

    @Test
    void rejectsPropertyColumnSignatureCollisions() {
        var ontology = new Ontology(
            List.of(
                new OntologyEntityType(
                    "User",
                    "A user",
                    List.of(new OntologyProperty("id", OntologyPropertyType.STRING, null,
                        new SourceBinding("users", "id", null, null, null))),
                    null
                ),
                new OntologyEntityType(
                    "Account",
                    "An account",
                    List.of(new OntologyProperty("ownerId", OntologyPropertyType.STRING, null,
                        new SourceBinding("users", "id", null, null, null))),
                    null
                )
            ),
            List.of(),
            null,
            Map.of()
        );

        assertThatThrownBy(() -> OntologyStructureValidator.validate(ontology))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Source signature 'column:users.id' is bound by multiple ontology labels");
    }

    @Test
    void rejectsDuplicatePatternsOnSameRelation() {
        var ontology = new Ontology(
            List.of(
                new OntologyEntityType("Person", "A person", List.of(), null),
                new OntologyEntityType("Organization", "An organization", List.of(), null)
            ),
            List.of(new OntologyRelationType(
                "works at",
                "employment",
                List.of(
                    new OntologyPattern("Person", "Organization"),
                    new OntologyPattern("person", "organization")
                ),
                List.of(),
                null
            )),
            null,
            Map.of()
        );

        assertThatThrownBy(() -> OntologyStructureValidator.validate(ontology))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("contains duplicate pattern");
    }

    @Test
    void undeclaredPatternReferencesProduceWarningsWithoutThrowing() {
        var ontology = new Ontology(
            List.of(new OntologyEntityType("Person", "A person", List.of(), null)),
            List.of(new OntologyRelationType(
                "works at",
                "employment",
                List.of(new OntologyPattern("Person", "Organization")),
                List.of(),
                null
            )),
            null,
            Map.of()
        );

        var result = OntologyStructureValidator.validate(ontology, OntologyStructureValidator.Options.defaults());

        assertThat(result.ontology()).isSameAs(ontology);
        assertThat(result.warnings())
            .anyMatch(warning -> warning.contains("references undeclared entity type 'Organization' as target"));
        assertThat(OntologyStructureValidator.validate(ontology)).isSameAs(ontology);
    }

    @Test
    void unconnectedEntityWarnsByDefaultAndFailsInStrictMode() {
        var ontology = new Ontology(
            List.of(
                new OntologyEntityType("Person", "A person", List.of(), null),
                new OntologyEntityType("Standalone", "An unreferenced type", List.of(), null)
            ),
            List.of(),
            null,
            Map.of()
        );

        var defaultResult = OntologyStructureValidator.validate(ontology, OntologyStructureValidator.Options.defaults());
        assertThat(defaultResult.warnings())
            .anyMatch(warning -> warning.contains("Entity type 'person' is not referenced by any relation pattern"))
            .anyMatch(warning -> warning.contains("Entity type 'standalone' is not referenced by any relation pattern"));

        var standaloneResult = OntologyStructureValidator.validate(
            ontology,
            new OntologyStructureValidator.Options(false, Set.of("Standalone"))
        );
        assertThat(standaloneResult.warnings())
            .anyMatch(warning -> warning.contains("Entity type 'person' is not referenced by any relation pattern"))
            .noneMatch(warning -> warning.contains("Entity type 'standalone' is not referenced by any relation pattern"));

        assertThatThrownBy(() -> OntologyStructureValidator.validate(
            ontology,
            new OntologyStructureValidator.Options(true, Set.of())
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Entity type 'person' is not referenced by any relation pattern");
    }

    @Test
    void reservedEntityPropertyNamesWarnWithoutFailing() {
        var ontology = new Ontology(
            List.of(new OntologyEntityType(
                "Person",
                "A person",
                List.of(
                    new OntologyProperty("Name", OntologyPropertyType.STRING, null, null),
                    new OntologyProperty("description", OntologyPropertyType.STRING, null, null),
                    new OntologyProperty("Age", OntologyPropertyType.INTEGER, null, null)
                ),
                null
            )),
            List.of(),
            null,
            Map.of()
        );

        var result = OntologyStructureValidator.validate(ontology, OntologyStructureValidator.Options.defaults());

        assertThat(result.ontology()).isSameAs(ontology);
        assertThat(result.warnings())
            .anyMatch(warning -> warning.contains("Reserved property 'name'"))
            .anyMatch(warning -> warning.contains("Reserved property 'description'"))
            .noneMatch(warning -> warning.contains("Reserved property 'age'"));
    }

    @Test
    void reservedRelationPropertyNamesWarnWithoutFailing() {
        var ontology = new Ontology(
            List.of(
                new OntologyEntityType("Person", "A person", List.of(), null),
                new OntologyEntityType("Organization", "An organization", List.of(), null)
            ),
            List.of(new OntologyRelationType(
                "works at",
                "employment",
                List.of(new OntologyPattern("Person", "Organization")),
                List.of(
                    new OntologyProperty("Weight", OntologyPropertyType.FLOAT, null, null),
                    new OntologyProperty("source_entity", OntologyPropertyType.STRING, null, null),
                    new OntologyProperty("Score", OntologyPropertyType.FLOAT, null, null)
                ),
                null
            )),
            null,
            Map.of()
        );

        var result = OntologyStructureValidator.validate(ontology, OntologyStructureValidator.Options.defaults());

        assertThat(result.warnings())
            .anyMatch(warning -> warning.contains("Reserved property 'weight'"))
            .anyMatch(warning -> warning.contains("Reserved property 'source_entity'"))
            .noneMatch(warning -> warning.contains("Reserved property 'score'"));
    }

    @Test
    void cleanOntologyProducesNoWarnings() {
        var ontology = new Ontology(
            List.of(
                new OntologyEntityType("Person", "A person", List.of(), null),
                new OntologyEntityType("Organization", "An organization", List.of(), null)
            ),
            List.of(new OntologyRelationType(
                "works at",
                "employment",
                List.of(new OntologyPattern("Person", "Organization")),
                List.of(),
                null
            )),
            null,
            Map.of()
        );

        var result = OntologyStructureValidator.validate(ontology, OntologyStructureValidator.Options.defaults());

        assertThat(result.warnings()).isEmpty();
    }
}
