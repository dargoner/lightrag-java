package io.github.lightrag.ontology.bootstrap;

import io.github.lightrag.ontology.AttributeJsonCodec;
import io.github.lightrag.ontology.Identity;
import io.github.lightrag.ontology.Ontology;
import io.github.lightrag.ontology.OntologyEntityType;
import io.github.lightrag.ontology.OntologyJsonCodec;
import io.github.lightrag.ontology.OntologyPattern;
import io.github.lightrag.ontology.OntologyProperty;
import io.github.lightrag.ontology.OntologyPropertyType;
import io.github.lightrag.ontology.OntologyRelationType;
import io.github.lightrag.ontology.SourceBinding;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapTest {

    private static final OntologyBootstrap BOOTSTRAP = new OntologyBootstrap();

    @Test
    void defaultsMatchThePlanAndNullListsAreEmpty() {
        BootstrapOptions options = new BootstrapOptions();

        assertThat(options.minOccurrences()).isEqualTo(2);
        assertThat(options.minDocuments()).isEqualTo(1);
        assertThat(options.minAttributeSupport()).isEqualTo(1);
        assertThat(options.includeAttributes()).isTrue();
        assertThat(options.maxSamples()).isEqualTo(200_000);
        assertThat(options.baseOntology()).isNull();
        assertThat(OntologyBootstrap.ALGORITHM_VERSION).isEqualTo(1);

        BootstrapResult result = BOOTSTRAP.bootstrap(null, null, options);

        assertThat(result.draft().entityTypes()).isEmpty();
        assertThat(result.draft().relationTypes()).isEmpty();
        assertThat(result.draft().version()).isNull();
        assertThat(result.conflicts()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.sampleFingerprint()).matches("[0-9a-f]{64}");
        assertThat(result.algorithmVersion()).isEqualTo(1);
        assertThat(result.stats()).isEqualTo(new BootstrapStats(0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void optionsRejectNonPositiveValues() {
        assertThatThrownBy(() -> new BootstrapOptions(0, 1, 1, true, 10, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minOccurrences");
        assertThatThrownBy(() -> new BootstrapOptions(1, 0, 1, true, 10, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minDocuments");
        assertThatThrownBy(() -> new BootstrapOptions(1, 1, 0, true, 10, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minAttributeSupport");
        assertThatThrownBy(() -> new BootstrapOptions(1, 1, 1, true, 0, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxSamples");
    }

    @Test
    void typeGateUsesDeduplicatedOccurrencesAndDistinctDocuments() {
        BootstrapResult oneSample = BOOTSTRAP.bootstrap(
            List.of(entity("Alice", "Person", "d1", "c1")),
            List.of(),
            options(2, 1)
        );
        assertThat(oneSample.draft().entityTypes()).isEmpty();

        BootstrapResult twoGroups = BOOTSTRAP.bootstrap(
            List.of(
                entity("Alice", "Person", "d1", "c1"),
                entity("Bob", "Person", "d1", "c2")
            ),
            List.of(),
            options(2, 1)
        );
        assertThat(findEntity(twoGroups.draft(), "Person")).isNotNull();

        BootstrapResult duplicateRows = BOOTSTRAP.bootstrap(
            List.of(
                entity("Alice", "Person", "d1", "c1"),
                entity("Alice", "Person", "d1", "c1")
            ),
            List.of(),
            options(2, 1)
        );
        assertThat(duplicateRows.draft().entityTypes()).isEmpty();
        assertThat(duplicateRows.stats().duplicateSamples()).isEqualTo(1);

        BootstrapResult sameDocument = BOOTSTRAP.bootstrap(
            List.of(
                entity("Alice", "Person", "d1", "c1"),
                entity("Bob", "Person", "d1", "c2")
            ),
            List.of(),
            options(1, 2)
        );
        assertThat(sameDocument.draft().entityTypes()).isEmpty();

        BootstrapResult twoDocuments = BOOTSTRAP.bootstrap(
            List.of(
                entity("Alice", "Person", "d1", "c1"),
                entity("Bob", "Person", "d2", "c2")
            ),
            List.of(),
            options(1, 2)
        );
        assertThat(findEntity(twoDocuments.draft(), "Person").label()).isEqualTo("Person");
    }

    @Test
    void reservedAndFallbackTypesAndKeywordsAreExcluded() {
        BootstrapResult result = BOOTSTRAP.bootstrap(
            List.of(
                entity("A", "entity", "d1", "c1"),
                entity("B", "Other", "d1", "c2"),
                entity("C", "Unknown", "d2", "c3"),
                entity("D", "Person", "d1", "c3")
            ),
            List.of(relation("D", "D", "relation,Other", "d1", "c4")),
            options(1, 1)
        );

        assertThat(identities(result.draft().entityTypes()))
            .containsExactly("person");
        assertThat(result.draft().relationTypes()).isEmpty();
    }

    @Test
    void duplicateSamplesMergeAttributesAndCountOneConflictPerKey() {
        BootstrapResult result = BOOTSTRAP.bootstrap(
            List.of(
                entity("Alice", "Person", "d1", "c1", attrs("age", 1, "email", "a@x")),
                entity("Alice", "Person", "d1", "c1", attrs("age", 2)),
                entity("Alice", "Person", "d1", "c1", attrs("age", 3))
            ),
            List.of(),
            options(1, 1)
        );

        assertThat(result.stats().entitySamples()).isEqualTo(3);
        assertThat(result.stats().duplicateSamples()).isEqualTo(2);
        assertThat(result.stats().conflictingDuplicateAttributes()).isEqualTo(1);
        OntologyEntityType person = findEntity(result.draft(), "Person");
        assertThat(identities(person.properties())).containsExactly("email");
        assertThat(result.conflicts()).anySatisfy(conflict -> {
            assertThat(conflict.ownerLabel()).isEqualTo("alice");
            assertThat(conflict.item()).isEqualTo("age");
            assertThat(conflict.detail()).contains("duplicate attribute values");
        });
    }

    @Test
    void missingDuplicateAttributeDoesNotOverridePresentValue() {
        BootstrapResult result = BOOTSTRAP.bootstrap(
            List.of(
                entity("Alice", "Person", "d1", "c1", attrs("email", "a@x")),
                entity("Alice", "Person", "d1", "c1", Map.of())
            ),
            List.of(),
            options(1, 1)
        );

        assertThat(identities(findEntity(result.draft(), "Person").properties()))
            .containsExactly("email");
        assertThat(result.stats().conflictingDuplicateAttributes()).isZero();
    }

    @Test
    void nullDuplicateAttributeDoesNotOverridePresentValue() {
        BootstrapResult result = BOOTSTRAP.bootstrap(
            List.of(
                entity("Alice", "Person", "d1", "c1", attrs("age", 7)),
                entity("Alice", "Person", "d1", "c1", attrs("age", null))
            ),
            List.of(),
            options(1, 1)
        );

        assertThat(identities(findEntity(result.draft(), "Person").properties()))
            .containsExactly("age");
        assertThat(property(findEntity(result.draft(), "Person"), "age").type())
            .isEqualTo(OntologyPropertyType.INTEGER);
        assertThat(result.stats().conflictingDuplicateAttributes()).isZero();
    }

    @Test
    void identicalDuplicateValuesAreRetained() {
        BootstrapResult result = BOOTSTRAP.bootstrap(
            List.of(
                entity("Alice", "Person", "d1", "c1", attrs("age", 1)),
                entity("Alice", "Person", "d1", "c1", attrs("age", 1))
            ),
            List.of(),
            options(1, 1)
        );

        assertThat(identities(findEntity(result.draft(), "Person").properties()))
            .containsExactly("age");
        assertThat(result.stats().conflictingDuplicateAttributes()).isZero();
    }

    @Test
    void trimCollisionDropsOnlyThatKeyAndIgnoresMapIterationOrder() {
        Map<String, Object> firstOrder = new LinkedHashMap<>();
        firstOrder.put("Age", 1);
        firstOrder.put(" Age ", 2);
        firstOrder.put("City", "X");
        Map<String, Object> secondOrder = new LinkedHashMap<>();
        secondOrder.put("City", "X");
        secondOrder.put(" Age ", 2);
        secondOrder.put("Age", 1);

        BootstrapOptions options = options(1, 1);
        BootstrapResult first = BOOTSTRAP.bootstrap(
            List.of(entity("Alice", "Person", "d1", "c1", firstOrder)),
            List.of(),
            options
        );
        BootstrapResult second = BOOTSTRAP.bootstrap(
            List.of(entity("Alice", "Person", "d1", "c1", secondOrder)),
            List.of(),
            options
        );

        assertThat(first).isEqualTo(second);
        assertThat(identities(findEntity(first.draft(), "Person").properties()))
            .containsExactly("city");
        assertThat(first.warnings()).anySatisfy(warning ->
            assertThat(warning).contains("attribute=age"));
    }

    @Test
    void spellingVoteUsesOneRepresentativePerDeduplicationGroup() {
        // Group A has two identical rows and its representative is "person".
        // Group B has one row with "Person". The tie must be decided by
        // lexicographic order, not by the extra row in group A.
        BootstrapResult result = BOOTSTRAP.bootstrap(
            List.of(
                entity("Alice", "person", "d1", "c1"),
                entity("Alice", "person", "d1", "c1"),
                entity("Bob", "Person", "d2", "c2")
            ),
            List.of(),
            options(1, 1)
        );

        assertThat(findEntity(result.draft(), "Person").label()).isEqualTo("Person");
    }

    @Test
    void spellingVoteCountsDistinctGroups() {
        BootstrapResult result = BOOTSTRAP.bootstrap(
            List.of(
                entity("Alice", "Person", "d1", "c1"),
                entity("Bob", "Person", "d2", "c2"),
                entity("Carol", "person", "d3", "c3")
            ),
            List.of(),
            options(1, 1)
        );

        assertThat(findEntity(result.draft(), "Person").label()).isEqualTo("Person");
    }

    @Test
    void keywordSpellingVoteIsDeterministic() {
        BootstrapResult result = BOOTSTRAP.bootstrap(
            List.of(
                entity("Alice", "Person", "d1", "c1"),
                entity("Bob", "Person", "d2", "c2"),
                entity("Carol", "Person", "d3", "c3"),
                entity("Dave", "Person", "d4", "c4")
            ),
            List.of(
                relation("Alice", "Bob", "KNOWS", "d1", "c1"),
                relation("Carol", "Dave", "knows", "d2", "c2")
            ),
            options(1, 1)
        );

        assertThat(result.draft().relationTypes()).hasSize(1);
        assertThat(result.draft().relationTypes().get(0).label()).isEqualTo("KNOWS");
    }

    @Test
    void attributeSupportCountsDistinctDeduplicationKeys() {
        BootstrapResult twoChunks = BOOTSTRAP.bootstrap(
            List.of(
                entity("Alice", "Person", "d1", "c1", attrs("age", 1)),
                entity("Alice", "Person", "d1", "c2", attrs("age", 2))
            ),
            List.of(),
            options(1, 1, 2)
        );
        assertThat(identities(findEntity(twoChunks.draft(), "Person").properties()))
            .containsExactly("age");

        BootstrapResult duplicateRows = BOOTSTRAP.bootstrap(
            List.of(
                entity("Alice", "Person", "d1", "c1", attrs("age", 1)),
                entity("Alice", "Person", "d1", "c1", attrs("age", 1))
            ),
            List.of(),
            options(1, 1, 2)
        );
        assertThat(findEntity(duplicateRows.draft(), "Person").properties()).isEmpty();
    }

    @Test
    void relationAttributesCountForEveryMatchingKeywordOwner() {
        BootstrapResult result = BOOTSTRAP.bootstrap(
            List.of(
                entity("Alice", "Person", "d1", "c1"),
                entity("Bob", "Person", "d1", "c1")
            ),
            List.of(relation(
                "Alice",
                "Bob",
                "knows,likes",
                "d1",
                "c1",
                attrs("since", "2024-01-02")
            )),
            options(1, 1)
        );

        assertThat(identities(result.draft().relationTypes())).containsExactly("knows", "likes");
        for (OntologyRelationType type : result.draft().relationTypes()) {
            assertThat(identities(type.properties())).containsExactly("since");
            assertThat(type.properties().get(0).type()).isEqualTo(OntologyPropertyType.DATE);
        }
    }

    @Test
    void ambiguousAndUngatedEndpointsAreCountedAndSuppressPatterns() {
        List<EntitySample> entities = List.of(
            entity("Alice", "Person", "d1", "c1"),
            entity("Alice", "Robot", "d2", "c2"),
            entity("Bob", "Person", "d1", "c1"),
            entity("Bob", "Person", "d2", "c2"),
            entity("Ghost", "Phantom", "d1", "c1"),
            entity("R2", "Robot", "d3", "c3")
        );
        List<RelationSample> relations = List.of(
            relation("Alice", "Bob", "knows", "d4", "c4"),
            relation("Alice", "Bob", "knows", "d5", "c5"),
            relation("Ghost", "Bob", "sees", "d4", "c4"),
            relation("Ghost", "Bob", "sees", "d5", "c5")
        );

        BootstrapResult result = BOOTSTRAP.bootstrap(entities, relations, options(2, 1));

        assertThat(result.draft().relationTypes()).isEmpty();
        assertThat(result.stats().droppedAmbiguousEndpoints()).isEqualTo(2);
        assertThat(result.stats().droppedUngatedEndpoints()).isEqualTo(2);
        assertThat(result.stats().droppedUnconstrainedRelationTypes()).isEqualTo(2);
        assertThat(result.conflicts()).anySatisfy(conflict -> {
            assertThat(conflict.item()).isEqualTo("alice");
            assertThat(conflict.detail()).contains("ambiguous source endpoint");
        });
    }

    @Test
    void endpointResolutionPrefersSameDocumentAndChunk() {
        List<EntitySample> entities = List.of(
            entity("Alice", "Person", "d1", "c1"),
            entity("Alice", "Robot", "d2", "c2"),
            entity("R2", "Robot", "d3", "c3"),
            entity("Bob", "Person", "d1", "c1"),
            entity("Bob", "Person", "d2", "c2")
        );
        List<RelationSample> relations = List.of(
            relation("Alice", "Bob", "knows", "d1", "c1"),
            relation("Alice", "Bob", "knows", "d3", "c3")
        );

        BootstrapResult result = BOOTSTRAP.bootstrap(entities, relations, options(1, 1));

        assertThat(result.stats().droppedAmbiguousEndpoints()).isEqualTo(1);
        OntologyRelationType knows = findRelation(result.draft(), "knows");
        assertThat(knows.patterns()).hasSize(1);
        assertThat(knows.patterns().get(0).identityId())
            .isEqualTo(Identity.identityId("Person") + "\u0000" + Identity.identityId("Person"));
    }

    @Test
    void relationTypeWithoutPassingPatternIsNotOutput() {
        List<EntitySample> entities = List.of(
            entity("Alice", "Person", "d1", "c1"),
            entity("Alice", "Robot", "d2", "c2"),
            entity("R2", "Robot", "d4", "c4"),
            entity("Bob", "Person", "d1", "c1"),
            entity("Bob", "Person", "d2", "c2")
        );
        List<RelationSample> relations = List.of(
            relation("Alice", "Bob", "knows", "d1", "c1"),
            relation("Alice", "Bob", "knows", "d3", "c3")
        );

        BootstrapResult result = BOOTSTRAP.bootstrap(entities, relations, options(2, 1));

        assertThat(result.draft().relationTypes()).isEmpty();
        assertThat(result.stats().droppedAmbiguousEndpoints()).isEqualTo(1);
        assertThat(result.stats().droppedUnconstrainedRelationTypes()).isEqualTo(1);
    }

    @Test
    void relationSamplesWithoutKeywordsProduceNoRelationTypes() {
        BootstrapResult result = BOOTSTRAP.bootstrap(
            List.of(
                entity("Alice", "Person", "d1", "c1"),
                entity("Bob", "Person", "d1", "c1")
            ),
            List.of(relation("Alice", "Bob", " , ", "d1", "c1")),
            options(1, 1)
        );

        assertThat(result.draft().relationTypes()).isEmpty();
        assertThat(result.stats().droppedUnconstrainedRelationTypes()).isZero();
    }

    @Test
    void typeInferenceMatrixCoversBooleanNumbersListsDatesAndMixedValues() {
        BootstrapResult result = BOOTSTRAP.bootstrap(
            List.of(
                entity("M1", "Metric", "d1", "c1", attrs(
                    "boolTrue", true,
                    "boolString", "TRUE",
                    "intOnly", 1,
                    "floatOnly", 1.5,
                    "listMixed", List.of("a"),
                    "dateOnly", "2024-01-02",
                    "dateTime", "2024-01-02T03:04:05Z",
                    "mixedBoolNumber", true,
                    "mixedIntString", 1,
                    "mixedBoolString", true,
                    "intFloat", 1
                )),
                entity("M2", "Metric", "d1", "c2", attrs(
                    "boolTrue", false,
                    "boolString", "false",
                    "intOnly", 2,
                    "floatOnly", 2.5,
                    "listMixed", "scalar",
                    "dateOnly", "2024-02-03",
                    "dateTime", "2024-02-03T00:00:00+01:00",
                    "mixedBoolNumber", 2,
                    "mixedIntString", "two",
                    "mixedBoolString", "true",
                    "intFloat", 2.5
                )),
                entity("M3", "Metric", "d2", "c1", attrs("allNull", null)),
                entity("M4", "Metric", "d2", "c2", attrs("allNull", null))
            ),
            List.of(),
            options(1, 1)
        );

        OntologyEntityType metric = findEntity(result.draft(), "Metric");
        Map<String, OntologyPropertyType> types = new TreeMap<>();
        for (OntologyProperty property : metric.properties()) {
            types.put(property.identityId(), property.type());
        }

        assertThat(types)
            .containsEntry("booltrue", OntologyPropertyType.BOOLEAN)
            .containsEntry("boolstring", OntologyPropertyType.BOOLEAN)
            .containsEntry("intonly", OntologyPropertyType.INTEGER)
            .containsEntry("floatonly", OntologyPropertyType.FLOAT)
            .containsEntry("listmixed", OntologyPropertyType.LIST)
            .containsEntry("dateonly", OntologyPropertyType.DATE)
            .containsEntry("datetime", OntologyPropertyType.DATE)
            .containsEntry("mixedboolnumber", OntologyPropertyType.STRING)
            .containsEntry("mixedintstring", OntologyPropertyType.STRING)
            .containsEntry("mixedboolstring", OntologyPropertyType.STRING)
            .containsEntry("intfloat", OntologyPropertyType.FLOAT)
            .doesNotContainKey("allnull");

        assertThat(result.conflicts()).anySatisfy(conflict -> {
            assertThat(conflict.item()).isEqualTo("allnull");
            assertThat(conflict.detail()).contains("all attribute values");
        });
    }

    @Test
    void maxSamplesRejectsInsteadOfTruncating() {
        BootstrapOptions options = new BootstrapOptions(1, 1, 1, true, 2, null);

        assertThatThrownBy(() -> BOOTSTRAP.bootstrap(
            List.of(
                entity("A", "Thing", "d1", "c1"),
                entity("B", "Thing", "d2", "c2")
            ),
            List.of(relation("A", "B", "rel", "d3", "c3")),
            options
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxSamples")
            .hasMessageContaining("3");
    }

    @Test
    void baseOntologyProducesACompleteCandidateAndRetainsBaseDefinitionsOnConflict() {
        SourceBinding entityBinding = new SourceBinding("people", null, null, null, null);
        SourceBinding relationBinding = new SourceBinding("employment", "person_id", "companies", "id", true);
        OntologyProperty baseAge = new OntologyProperty("age", OntologyPropertyType.INTEGER, "years", null);
        OntologyEntityType basePerson = new OntologyEntityType(
            "PERSON",
            "base person",
            List.of(baseAge),
            entityBinding
        );
        OntologyEntityType baseCompany = new OntologyEntityType(
            "Company",
            "base company",
            List.of(),
            null
        );
        OntologyProperty baseSince = new OntologyProperty("since", OntologyPropertyType.DATE, "start", null);
        OntologyRelationType baseWorksAt = new OntologyRelationType(
            "WORKS_AT",
            "base works",
            List.of(new OntologyPattern("PERSON", "Company")),
            List.of(baseSince),
            relationBinding
        );
        OntologyRelationType baseLegacy = new OntologyRelationType(
            "legacy",
            "base legacy",
            List.of(),
            List.of(),
            null
        );
        Ontology base = new Ontology(
            List.of(basePerson, baseCompany),
            List.of(baseWorksAt, baseLegacy),
            "v1",
            Map.of("owner", "host")
        );

        BootstrapResult result = BOOTSTRAP.bootstrap(
            List.of(
                entity("Alice", "Person", "d1", "c1", attrs("age", "old", "email", "a@x", "department", "eng")),
                entity("Bob", "Person", "d1", "c2", attrs("age", "young", "email", "b@x")),
                entity("Acme", "Company", "d1", "c1"),
                entity("Org", "Organization", "d1", "c1")
            ),
            List.of(
                relation("Alice", "Acme", "works_at", "d3", "c3", attrs("role", "lead")),
                relation("Bob", "Org", "works_at", "d4", "c4", attrs("role", "dev"))
            ),
            new BootstrapOptions(1, 1, 1, true, 100, base)
        );

        assertThat(result.draft().version()).isNull();
        assertThat(result.draft().metadata()).containsEntry("owner", "host");
        assertThat(base.version()).isEqualTo("v1");
        assertThat(base.entityTypes()).hasSize(2);

        OntologyEntityType person = findEntity(result.draft(), "PERSON");
        assertThat(person.label()).isEqualTo("PERSON");
        assertThat(person.description()).isEqualTo("base person");
        assertThat(person.sourceBinding()).isEqualTo(entityBinding);
        assertThat(identities(person.properties()))
            .containsExactly("age", "department", "email");
        OntologyProperty age = property(person, "age");
        assertThat(age.type()).isEqualTo(OntologyPropertyType.INTEGER);
        assertThat(age.description()).isEqualTo("years");
        assertThat(property(person, "email").type()).isEqualTo(OntologyPropertyType.STRING);
        assertThat(result.conflicts()).anySatisfy(conflict -> {
            assertThat(conflict.ownerLabel()).isEqualTo("person");
            assertThat(conflict.item()).isEqualTo("age");
            assertThat(conflict.detail()).contains("base definition retained");
        });

        assertThat(findEntity(result.draft(), "Company").description()).isEqualTo("base company");
        assertThat(findEntity(result.draft(), "Organization").description()).isNull();

        OntologyRelationType worksAt = findRelation(result.draft(), "WORKS_AT");
        assertThat(worksAt.label()).isEqualTo("WORKS_AT");
        assertThat(worksAt.description()).isEqualTo("base works");
        assertThat(worksAt.sourceBinding()).isEqualTo(relationBinding);
        assertThat(worksAt.patterns()).extracting(OntologyPattern::identityId)
            .containsExactly(
                Identity.identityId("PERSON") + "\u0000" + Identity.identityId("Company"),
                Identity.identityId("Person") + "\u0000" + Identity.identityId("Organization")
            );
        assertThat(identities(worksAt.properties())).containsExactly("role", "since");
        assertThat(property(worksAt, "since").type()).isEqualTo(OntologyPropertyType.DATE);
        assertThat(property(worksAt, "since").description()).isEqualTo("start");
        assertThat(property(worksAt, "role").type()).isEqualTo(OntologyPropertyType.STRING);

        OntologyRelationType legacy = findRelation(result.draft(), "legacy");
        assertThat(legacy.description()).isEqualTo("base legacy");
        assertThat(legacy.patterns()).isEmpty();
    }

    @Test
    void includeAttributesFalseOmitsInferredPropertiesButKeepsBaseProperties() {
        Ontology base = new Ontology(
            List.of(new OntologyEntityType(
                "Person",
                null,
                List.of(new OntologyProperty("age", OntologyPropertyType.INTEGER, null, null)),
                null
            )),
            List.of(),
            null,
            Map.of()
        );

        BootstrapResult result = BOOTSTRAP.bootstrap(
            List.of(entity("Alice", "Person", "d1", "c1", attrs("email", "a@x"))),
            List.of(),
            new BootstrapOptions(1, 1, 1, false, 100, base)
        );

        assertThat(identities(findEntity(result.draft(), "Person").properties()))
            .containsExactly("age");
    }

    @Test
    void completeResultIsIndependentOfSampleOrderAndAttributeMapOrder() {
        Map<String, Object> aliceAttributes = new LinkedHashMap<>();
        aliceAttributes.put("Age", 30);
        aliceAttributes.put("City", "X");
        Map<String, Object> aliceDuplicate = attrs("Age", 31);
        Map<String, Object> robotAttributes = new LinkedHashMap<>();
        robotAttributes.put("Score", 1);
        robotAttributes.put(" Score ", 2);
        robotAttributes.put("Tag", "x");
        Map<String, Object> relationAttributes = new LinkedHashMap<>();
        relationAttributes.put("since", "2024-01-01");

        List<EntitySample> entities = new ArrayList<>(List.of(
            entity("Alice", "Person", "d1", "c1", aliceAttributes),
            entity("Alice", "Person", "d1", "c1", aliceDuplicate),
            entity("Bob", "Person", "d1", "c1", attrs("City", "Y")),
            entity("Alice", "Robot", "d2", "c2", robotAttributes),
            entity("R2", "Robot", "d5", "c5"),
            entity("Carol", "person", "d4", "c4", attrs("Age", "forty"))
        ));
        List<RelationSample> relations = new ArrayList<>(List.of(
            relation("Alice", "Bob", "knows", "d3", "c3", attrs("score", "high")),
            relation("Carol", "Bob", "likes", "d6", "c6", relationAttributes)
        ));

        BootstrapResult first = BOOTSTRAP.bootstrap(entities, relations, options(1, 1));

        List<EntitySample> shuffledEntities = new ArrayList<>(entities);
        java.util.Collections.reverse(shuffledEntities);
        List<RelationSample> shuffledRelations = new ArrayList<>(relations);
        java.util.Collections.reverse(shuffledRelations);
        Map<String, Object> reversedAlice = new LinkedHashMap<>();
        reversedAlice.put("City", "X");
        reversedAlice.put("Age", 30);
        Map<String, Object> reversedRobot = new LinkedHashMap<>();
        reversedRobot.put("Tag", "x");
        reversedRobot.put(" Score ", 2);
        reversedRobot.put("Score", 1);
        List<EntitySample> reorderedAttributes = new ArrayList<>(List.of(
            entity("Alice", "Person", "d1", "c1", reversedAlice),
            entity("Alice", "Person", "d1", "c1", aliceDuplicate),
            entity("Bob", "Person", "d1", "c1", attrs("City", "Y")),
            entity("Alice", "Robot", "d2", "c2", reversedRobot),
            entity("R2", "Robot", "d5", "c5"),
            entity("Carol", "person", "d4", "c4", attrs("Age", "forty"))
        ));

        BootstrapResult second = BOOTSTRAP.bootstrap(
            reorderedAttributes,
            shuffledRelations,
            options(1, 1)
        );

        assertThat(second).isEqualTo(first);
        assertThat(OntologyJsonCodec.canonicalJson(second.draft()))
            .isEqualTo(OntologyJsonCodec.canonicalJson(first.draft()));
        assertThat(second.sampleFingerprint()).isEqualTo(first.sampleFingerprint());

        assertThat(first.stats().entitySamples()).isEqualTo(6);
        assertThat(first.stats().relationSamples()).isEqualTo(2);
        assertThat(first.stats().duplicateSamples()).isEqualTo(1);
        assertThat(first.stats().conflictingDuplicateAttributes()).isEqualTo(1);
        assertThat(first.stats().droppedAmbiguousEndpoints()).isEqualTo(1);
        assertThat(first.stats().droppedUnconstrainedRelationTypes()).isEqualTo(1);
        assertThat(identities(findEntity(first.draft(), "Person").properties()))
            .containsExactly("age", "city");
        assertThat(identities(findEntity(first.draft(), "Robot").properties()))
            .containsExactly("tag");
        OntologyRelationType likes = findRelation(first.draft(), "likes");
        assertThat(likes.patterns()).hasSize(1);
        assertThat(identities(likes.properties())).containsExactly("since");
    }

    @Test
    void numericInferenceDoesNotDependOnAttributeMapIterationOrder() {
        Map<String, Object> forward = new LinkedHashMap<>();
        forward.put("Age", 1.0d);
        forward.put(" Age ", new BigDecimal("1.0"));
        Map<String, Object> reverse = new LinkedHashMap<>();
        reverse.put(" Age ", new BigDecimal("1.0"));
        reverse.put("Age", 1.0d);

        BootstrapResult first = BOOTSTRAP.bootstrap(
            List.of(entity("Alice", "Person", "d1", "c1", forward)),
            List.of(),
            options(1, 1)
        );
        BootstrapResult second = BOOTSTRAP.bootstrap(
            List.of(entity("Alice", "Person", "d1", "c1", reverse)),
            List.of(),
            options(1, 1)
        );

        assertThat(second).isEqualTo(first);
        assertThat(findEntity(first.draft(), "Person").properties())
            .singleElement()
            .satisfies(property -> assertThat(property.type())
                .isEqualTo(OntologyPropertyType.INTEGER));
        assertThat(second.sampleFingerprint()).isEqualTo(first.sampleFingerprint());
    }

    @Test
    void fingerprintIsContentSensitiveAndBaseCanonicalizationInsensitive() {
        BootstrapOptions options = options(1, 1);
        BootstrapResult low = BOOTSTRAP.bootstrap(
            List.of(entity("Alice", "Person", "d1", "c1", attrs("age", 1))),
            List.of(),
            options
        );
        BootstrapResult high = BOOTSTRAP.bootstrap(
            List.of(entity("Alice", "Person", "d1", "c1", attrs("age", 2))),
            List.of(),
            options
        );
        assertThat(low.sampleFingerprint()).isNotEqualTo(high.sampleFingerprint());

        Ontology baseA = new Ontology(
            List.of(new OntologyEntityType(
                "Person",
                null,
                List.of(new OntologyProperty("AGE", OntologyPropertyType.INTEGER, null, null)),
                null
            )),
            List.of(),
            null,
            Map.of()
        );
        Ontology baseB = new Ontology(
            List.of(new OntologyEntityType(
                "person",
                null,
                List.of(new OntologyProperty("age", OntologyPropertyType.INTEGER, null, null)),
                null
            )),
            List.of(),
            null,
            Map.of()
        );
        BootstrapResult withBaseA = BOOTSTRAP.bootstrap(
            List.of(entity("Alice", "Person", "d1", "c1")),
            List.of(),
            new BootstrapOptions(1, 1, 1, true, 100, baseA)
        );
        BootstrapResult withBaseB = BOOTSTRAP.bootstrap(
            List.of(entity("Alice", "Person", "d1", "c1")),
            List.of(),
            new BootstrapOptions(1, 1, 1, true, 100, baseB)
        );
        assertThat(withBaseA.sampleFingerprint()).isEqualTo(withBaseB.sampleFingerprint());
        assertThat(withBaseA.sampleFingerprint()).isNotEqualTo(low.sampleFingerprint());

        Ontology otherBase = new Ontology(
            List.of(new OntologyEntityType(
                "Person",
                null,
                List.of(new OntologyProperty("email", OntologyPropertyType.STRING, null, null)),
                null
            )),
            List.of(),
            null,
            Map.of()
        );
        BootstrapResult withOtherBase = BOOTSTRAP.bootstrap(
            List.of(entity("Alice", "Person", "d1", "c1")),
            List.of(),
            new BootstrapOptions(1, 1, 1, true, 100, otherBase)
        );
        assertThat(withOtherBase.sampleFingerprint())
            .isNotEqualTo(withBaseA.sampleFingerprint());
    }

    @Test
    void fingerprintCoversBaseMetadataThatDisplayFieldsCannotRepresent() {
        Ontology baseA = new Ontology(List.of(), List.of(), null, Map.of("definitionHash", "a"));
        Ontology baseB = new Ontology(List.of(), List.of(), null, Map.of("definitionHash", "b"));

        BootstrapResult withBaseA = BOOTSTRAP.bootstrap(
            List.of(entity("Alice", "Person", "d1", "c1")),
            List.of(),
            new BootstrapOptions(1, 1, 1, true, 100, baseA)
        );
        BootstrapResult withBaseB = BOOTSTRAP.bootstrap(
            List.of(entity("Alice", "Person", "d1", "c1")),
            List.of(),
            new BootstrapOptions(1, 1, 1, true, 100, baseB)
        );

        assertThat(withBaseA.sampleFingerprint()).isNotEqualTo(withBaseB.sampleFingerprint());
    }

    @Test
    void fingerprintCoversAlgorithmVersionAndCanonicalSamples() {
        BootstrapOptions options = new BootstrapOptions(1, 1, 1, true, 100, null);
        BootstrapResult result = BOOTSTRAP.bootstrap(
            List.of(entity("Person", "Person", "d1", "c1", attrs("age", 1))),
            List.of(),
            options
        );

        Map<String, Object> attributesView = new TreeMap<>();
        attributesView.put("age", AttributeJsonCodec.toJson(Map.of("value", 1L)));
        Map<String, Object> sample = new TreeMap<>();
        sample.put("kind", "entity");
        sample.put("documentId", "d1");
        sample.put("chunkId", "c1");
        sample.put("name", "Person");
        sample.put("type", "Person");
        sample.put("attributes", attributesView);
        String sampleJson = AttributeJsonCodec.toJson(sample);

        Map<String, Object> optionView = new TreeMap<>();
        optionView.put("includeAttributes", true);
        optionView.put("maxSamples", 100);
        optionView.put("minAttributeSupport", 1);
        optionView.put("minDocuments", 1);
        optionView.put("minOccurrences", 1);

        String versionOne = fingerprintWith(1, "", optionView, List.of(sampleJson));
        String versionTwo = fingerprintWith(2, "", optionView, List.of(sampleJson));

        assertThat(result.sampleFingerprint())
            .isEqualTo(versionOne)
            .isNotEqualTo(versionTwo);
    }

    @Test
    void conflictsAreSortedDeduplicatedAndWarningsAreSorted() {
        BootstrapResult nullConflict = BOOTSTRAP.bootstrap(
            List.of(
                entity("A", "Person", "d1", "c1", attrs("x", null)),
                entity("B", "Person", "d2", "c2", attrs("x", null))
            ),
            List.of(),
            options(1, 1)
        );
        assertThat(nullConflict.conflicts()).hasSize(1);

        BootstrapResult trimWarnings = BOOTSTRAP.bootstrap(
            List.of(
                entity("A", "Person", "d1", "c1", attrs("Age", 1, " Age ", 2)),
                entity("B", "Person", "d2", "c2", attrs("Age", 3, " Age ", 4))
            ),
            List.of(),
            options(1, 1)
        );
        assertThat(trimWarnings.warnings()).isSorted();
        assertThat(trimWarnings.conflicts())
            .isSortedAccordingTo(java.util.Comparator
                .comparing((BootstrapConflict conflict) -> Identity.identityId(conflict.ownerLabel()))
                .thenComparing(conflict -> Identity.identityId(conflict.item()))
                .thenComparing(BootstrapConflict::detail));
    }

    @Test
    void relationDeduplicationIgnoresKeywordOrderAndIdentitySpelling() {
        BootstrapResult result = BOOTSTRAP.bootstrap(
            List.of(
                entity("Alice", "Person", "d1", "c1"),
                entity("Bob", "Person", "d1", "c1")
            ),
            List.of(
                relation("Alice", "Bob", "knows,likes", "d1", "c1"),
                relation("Alice", "Bob", "likes, KNOWS", "d1", "c1")
            ),
            options(1, 1)
        );

        assertThat(result.stats().duplicateSamples()).isEqualTo(1);
        assertThat(identities(result.draft().relationTypes())).containsExactly("knows", "likes");
    }

    @Test
    void entityDeduplicationKeyIncludesTypeDocumentAndChunk() {
        BootstrapResult result = BOOTSTRAP.bootstrap(
            List.of(
                entity("Alice", "Person", "d1", "c1"),
                entity("Alice", "Person", "d2", "c1"),
                entity("Alice", "Robot", "d1", "c1")
            ),
            List.of(),
            options(1, 1)
        );

        assertThat(result.stats().duplicateSamples()).isZero();
        assertThat(identities(result.draft().entityTypes())).containsExactly("person", "robot");
    }

    private static BootstrapOptions options(int minOccurrences, int minDocuments) {
        return new BootstrapOptions(minOccurrences, minDocuments, 1, true, 200_000, null);
    }

    private static BootstrapOptions options(
        int minOccurrences,
        int minDocuments,
        int minAttributeSupport
    ) {
        return new BootstrapOptions(
            minOccurrences,
            minDocuments,
            minAttributeSupport,
            true,
            200_000,
            null
        );
    }

    private static EntitySample entity(String name, String type, String documentId, String chunkId) {
        return new EntitySample(name, type, Map.of(), documentId, chunkId);
    }

    private static EntitySample entity(
        String name,
        String type,
        String documentId,
        String chunkId,
        Map<String, Object> attributes
    ) {
        return new EntitySample(name, type, attributes, documentId, chunkId);
    }

    private static RelationSample relation(
        String source,
        String target,
        String keywords,
        String documentId,
        String chunkId
    ) {
        return new RelationSample(source, target, keywords, Map.of(), documentId, chunkId);
    }

    private static RelationSample relation(
        String source,
        String target,
        String keywords,
        String documentId,
        String chunkId,
        Map<String, Object> attributes
    ) {
        return new RelationSample(source, target, keywords, attributes, documentId, chunkId);
    }

    private static Map<String, Object> attrs(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put((String) pairs[index], pairs[index + 1]);
        }
        return values;
    }

    private static OntologyEntityType findEntity(Ontology ontology, String label) {
        String identity = Identity.identityId(label);
        return ontology.entityTypes().stream()
            .filter(type -> type.identityId().equals(identity))
            .findFirst()
            .orElseThrow();
    }

    private static OntologyRelationType findRelation(Ontology ontology, String label) {
        String identity = Identity.identityId(label);
        return ontology.relationTypes().stream()
            .filter(type -> type.identityId().equals(identity))
            .findFirst()
            .orElseThrow();
    }

    private static OntologyProperty property(OntologyEntityType type, String name) {
        String identity = Identity.identityId(name);
        return type.properties().stream()
            .filter(property -> property.identityId().equals(identity))
            .findFirst()
            .orElseThrow();
    }

    private static OntologyProperty property(OntologyRelationType type, String name) {
        String identity = Identity.identityId(name);
        return type.properties().stream()
            .filter(property -> property.identityId().equals(identity))
            .findFirst()
            .orElseThrow();
    }

    private static List<String> identities(List<?> items) {
        return items.stream().map(BootstrapTest::identityOf).toList();
    }

    private static String identityOf(Object item) {
        if (item instanceof OntologyEntityType type) {
            return type.identityId();
        }
        if (item instanceof OntologyRelationType type) {
            return type.identityId();
        }
        if (item instanceof OntologyProperty property) {
            return property.identityId();
        }
        throw new IllegalArgumentException("unsupported identity carrier: " + item);
    }

    private static String fingerprintWith(
        int algorithmVersion,
        String base,
        Map<String, Object> options,
        List<String> samples
    ) {
        Map<String, Object> root = new TreeMap<>();
        root.put("algorithmVersion", algorithmVersion);
        root.put("base", base);
        root.put("options", options);
        root.put("samples", samples);
        return sha256(AttributeJsonCodec.toJson(root));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
