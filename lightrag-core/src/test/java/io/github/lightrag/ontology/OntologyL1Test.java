package io.github.lightrag.ontology;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OntologyL1Test {

    @Test
    void ontologyModelPreservesDisplayLabelsAndUsesIdentityForComparison() {
        var person = new OntologyEntityType("  Person  ", "A person", List.of(), null);
        var person2 = new OntologyEntityType("PERSON", "A human", List.of(), null);

        assertThat(person.identityId()).isEqualTo("person");
        assertThat(person2.identityId()).isEqualTo("person");
        assertThat(person.label()).isEqualTo("Person");
        assertThat(person2.label()).isEqualTo("PERSON");

        assertThatThrownBy(() -> new Ontology(List.of(person, person2), List.of(), null, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Duplicate entity identity");
    }

    @Test
    void ontologyMergeUnionsByIdentityKeepingLongerDescription() {
        var person = new OntologyEntityType(
            "Person",
            "Human",
            List.of(new OntologyProperty("age", OntologyPropertyType.INTEGER, "years", null)),
            null
        );
        var person2 = new OntologyEntityType(
            "PERSON",
            "A human being",
            List.of(new OntologyProperty("name", OntologyPropertyType.STRING, "full name", null)),
            null
        );
        var org = new OntologyEntityType("Organization", "Org", List.of(), null);

        var base = new Ontology(List.of(person, org), List.of(), null, Map.of("k", "v"));
        var draft = new Ontology(List.of(person2), List.of(), null, Map.of("k2", "v2"));

        var merged = base.merge(draft);

        assertThat(merged.entityTypes()).hasSize(2);
        var mergedPerson = merged.entityTypes().get(0);
        assertThat(mergedPerson.identityId()).isEqualTo("person");
        assertThat(mergedPerson.description()).isEqualTo("A human being");
        assertThat(mergedPerson.properties()).hasSize(2);

        assertThat(merged.metadata()).containsEntry("k", "v").containsEntry("k2", "v2");
        assertThat(merged.version()).isNull();
    }

    @Test
    void toCanonicalNormalizesLabelsForHashing() {
        var person = new OntologyEntityType(
            "Person",
            "Human",
            List.of(new OntologyProperty("AGE", OntologyPropertyType.INTEGER, "years", null)),
            null
        );
        var rel = new OntologyRelationType(
            "WORKS_AT",
            "works at",
            List.of(new OntologyPattern("Person", "Organization")),
            List.of(),
            null
        );
        var ontology = new Ontology(List.of(person), List.of(rel), "v1", Map.of());

        var canonical = ontology.toCanonical();

        assertThat(canonical.entityTypes().get(0).label()).isEqualTo("person");
        assertThat(canonical.entityTypes().get(0).properties().get(0).name()).isEqualTo("age");
        assertThat(canonical.relationTypes().get(0).label()).isEqualTo("works_at");
        assertThat(canonical.relationTypes().get(0).patterns().get(0).sourceLabel()).isEqualTo("person");
    }

    @Test
    void sourceBindingValidationRequiresTableNameForColumn() {
        assertThatThrownBy(() -> new SourceBinding(null, "col", null, null, true))
            .isInstanceOf(IllegalArgumentException.class);

        var binding = new SourceBinding("employees", "title", null, null, true);
        assertThat(binding.tableName()).isEqualTo("employees");
        assertThat(binding.column()).isEqualTo("title");
    }

    @Test
    void sourceBindingRelationRequiresAllFourFields() {
        assertThatThrownBy(() -> new SourceBinding("employees", "dept_id", "departments", null, false))
            .isInstanceOf(IllegalArgumentException.class);

        var binding = new SourceBinding("employees", "dept_id", "departments", "id", false);
        assertThat(binding.refTableName()).isEqualTo("departments");
        assertThat(binding.refColumn()).isEqualTo("id");
    }

    @Test
    void governanceIsSubsetAllowsEqualAndRejectsNewElements() {
        var base = sampleOntology();
        assertThat(OntologyGovernance.isSubset(base, base)).isTrue();

        var extraEntity = new Ontology(
            List.of(new OntologyEntityType("Company", "A company", List.of(), null)),
            List.of(),
            null,
            Map.of()
        );
        assertThat(OntologyGovernance.isSubset(extraEntity, base)).isFalse();
    }

    @Test
    void governanceIsSubsetAllowsSubSetOfProperties() {
        var base = sampleOntology();
        var subset = new Ontology(
            List.of(new OntologyEntityType(
                "Person",
                "A person",
                List.of(new OntologyProperty("name", OntologyPropertyType.STRING, "name", null)),
                null
            )),
            List.of(),
            null,
            Map.of()
        );
        assertThat(OntologyGovernance.isSubset(subset, base)).isTrue();
    }

    @Test
    void governanceDetectsPropertyTypeContradiction() {
        var base = sampleOntology();
        var changed = new Ontology(
            List.of(new OntologyEntityType(
                "Person",
                "A person",
                List.of(new OntologyProperty("age", OntologyPropertyType.STRING, "years", null)),
                null
            )),
            List.of(),
            null,
            Map.of()
        );
        var contradictions = OntologyGovernance.detectContradictions(changed, base);
        assertThat(contradictions).hasSize(1);
        assertThat(contradictions.get(0)).contains("age").contains("INTEGER").contains("STRING");
    }

    @Test
    void governanceSynthesizeCandidateIncrementalMergesNewEntityTypes() {
        var base = sampleOntology();
        var draft = new Ontology(
            List.of(new OntologyEntityType("Company", "A company", List.of(), null)),
            List.of(),
            null,
            Map.of()
        );

        var candidate = OntologyGovernance.synthesizeCandidate(base, draft, SynthesizeMode.REGISTER_INCREMENTAL);

        assertThat(candidate.entityTypes()).hasSize(2);
    }

    @Test
    void governanceSynthesizeCandidateIncrementalRejectsNewPropertyOnExistingType() {
        var base = sampleOntology();
        var draft = new Ontology(
            List.of(new OntologyEntityType(
                "Person",
                "A person",
                List.of(new OntologyProperty("email", OntologyPropertyType.STRING, "email", null)),
                null
            )),
            List.of(),
            null,
            Map.of()
        );

        assertThatThrownBy(() -> OntologyGovernance.synthesizeCandidate(base, draft, SynthesizeMode.REGISTER_INCREMENTAL))
            .isInstanceOf(OntologyModificationNotAllowedException.class);
    }

    @Test
    void governanceSynthesizeCandidateReplaceReturnsDraft() {
        var base = sampleOntology();
        var draft = new Ontology(
            List.of(new OntologyEntityType("Company", "A company", List.of(), null)),
            List.of(),
            null,
            Map.of()
        );

        var candidate = OntologyGovernance.synthesizeCandidate(base, draft, SynthesizeMode.EVOLUTION_REPLACE);

        assertThat(candidate.entityTypes()).hasSize(1);
        assertThat(candidate.entityTypes().get(0).identityId()).isEqualTo("company");
    }

    @Test
    void governanceCandidateHashIsStableAcrossDisplayVariations() {
        var ontologyA = sampleOntology();
        var ontologyB = new Ontology(
            List.of(new OntologyEntityType(
                "PERSON",
                "A person",
                List.of(
                    new OntologyProperty("NAME", OntologyPropertyType.STRING, "name", null),
                    new OntologyProperty("AGE", OntologyPropertyType.INTEGER, "years", null)
                ),
                null
            )),
            List.of(new OntologyRelationType(
                "WORKS_AT",
                "works at",
                List.of(new OntologyPattern("PERSON", "ORGANIZATION")),
                List.of(),
                null
            )),
            "different-version",
            Map.of()
        );

        var hashA = OntologyGovernance.candidateHash(SynthesizeMode.REGISTER_INCREMENTAL, ontologyA);
        var hashB = OntologyGovernance.candidateHash(SynthesizeMode.REGISTER_INCREMENTAL, ontologyB);

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    void governanceCandidateHashDiffersByMode() {
        var ontology = sampleOntology();
        var hashRegister = OntologyGovernance.candidateHash(SynthesizeMode.REGISTER_INCREMENTAL, ontology);
        var hashEvolution = OntologyGovernance.candidateHash(SynthesizeMode.EVOLUTION_REPLACE, ontology);

        assertThat(hashRegister).isNotEqualTo(hashEvolution);
    }

    @Test
    void governanceDiffProducesFullyExpandedManifest() {
        var before = sampleOntology();
        var after = new Ontology(
            List.of(new OntologyEntityType(
                "Person",
                "A person",
                List.of(new OntologyProperty("name", OntologyPropertyType.STRING, "name", null)),
                null
            )),
            List.of(),
            null,
            Map.of()
        );

        var manifest = OntologyGovernance.diff(before, after);

        assertThat(manifest.entries()).hasSize(3);
        assertThat(manifest.entries().stream().filter(e -> e instanceof DeletionEntry.Relation).count()).isEqualTo(1);
        assertThat(manifest.entries().stream().filter(e -> e instanceof DeletionEntry.Property).count()).isEqualTo(1);
        assertThat(manifest.entries().stream().filter(e -> e instanceof DeletionEntry.Pattern).count()).isEqualTo(1);
    }

    @Test
    void relationTypeMatchesPatternsToleratesBothDirections() {
        var rel = new OntologyRelationType(
            "works_at",
            "works at",
            List.of(new OntologyPattern("Person", "Organization")),
            List.of(),
            null
        );

        assertThat(OntologyGovernance.relationTypeMatchesPatterns(rel, "person", "organization")).isTrue();
        assertThat(OntologyGovernance.relationTypeMatchesPatterns(rel, "organization", "person")).isTrue();
        assertThat(OntologyGovernance.relationTypeMatchesPatterns(rel, "person", "company")).isFalse();
    }

    @Test
    void codecRoundTripPreservesDisplayLabels() {
        var ontology = sampleOntology();
        var json = OntologyJsonCodec.write(ontology);
        var parsed = OntologyJsonCodec.read(json);

        assertThat(parsed.entityTypes().get(0).label()).isEqualTo("Person");
        assertThat(parsed.entityTypes().get(0).properties().get(0).name()).isEqualTo("name");
        assertThat(parsed.relationTypes().get(0).label()).isEqualTo("works_at");
    }

    @Test
    void codecRejectsUnsupportedSchemaVersion() {
        assertThatThrownBy(() -> OntologyJsonCodec.read("{\"schemaVersion\": 99, \"kind\": \"ontology\"}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported ontology schema version");
    }

    @Test
    void codecRejectsUnsupportedKind() {
        assertThatThrownBy(() -> OntologyJsonCodec.read("{\"schemaVersion\": 1, \"kind\": \"other\"}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported ontology kind");
    }

    @Test
    void canonicalJsonExcludesVersionAndAuditMetadata() {
        var ontology = new Ontology(
            List.of(new OntologyEntityType("Person", "A person", List.of(), null)),
            List.of(),
            "v1",
            Map.of("hash", "abc", "contentHash", "def", "owner", "team")
        );

        var canonical = OntologyJsonCodec.canonicalJson(ontology);

        assertThat(canonical).doesNotContain("\"version\"");
        assertThat(canonical).doesNotContain("\"hash\"");
        assertThat(canonical).doesNotContain("\"contentHash\"");
        assertThat(canonical).contains("\"owner\"");
    }

    @Test
    void canonicalJsonNormalizesMetadataKeysAcrossUnicodeForms() {
        var precomposed = new Ontology(List.of(), List.of(), null, Map.of("\u00e9", "value"));
        var decomposed = new Ontology(List.of(), List.of(), null, Map.of("e\u0301", "value"));

        assertThat(OntologyJsonCodec.canonicalJson(precomposed))
            .isEqualTo(OntologyJsonCodec.canonicalJson(decomposed));
        assertThat(OntologyJsonCodec.contentHash(precomposed))
            .isEqualTo(OntologyJsonCodec.contentHash(decomposed));
    }

    @Test
    void canonicalJsonRejectsMetadataKeysCollidingAfterUnicodeNormalization() {
        var ontology = new Ontology(List.of(), List.of(), null, Map.of("\u00e9", "a", "e\u0301", "b"));

        assertThatThrownBy(() -> OntologyJsonCodec.canonicalJson(ontology))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate object key after NFC normalization");
    }

    @Test
    void contentHashMatchesGoldenDigestForNonAsciiFixture() {
        var ontology = new Ontology(
            List.of(new OntologyEntityType(
                "政策",
                "公积金政策",
                List.of(new OntologyProperty("利率", OntologyPropertyType.FLOAT, "存款利率", null)),
                null
            )),
            List.of(),
            null,
            Map.of("来源", "常州市")
        );

        assertThat(OntologyJsonCodec.contentHash(ontology))
            .isEqualTo("0fc23efd68428082f319787d67904036d9986be7491118449d79ef0455043cad");
    }

    @Test
    void contentHashIsStableAcrossDisplayAndVersionVariations() {
        var ontologyA = sampleOntology();
        var ontologyB = new Ontology(
            List.of(new OntologyEntityType(
                "PERSON",
                "A person",
                List.of(
                    new OntologyProperty("NAME", OntologyPropertyType.STRING, "name", null),
                    new OntologyProperty("AGE", OntologyPropertyType.INTEGER, "years", null)
                ),
                null
            )),
            List.of(new OntologyRelationType(
                "WORKS_AT",
                "works at",
                List.of(new OntologyPattern("PERSON", "ORGANIZATION")),
                List.of(),
                null
            )),
            "different-version",
            Map.of()
        );

        assertThat(OntologyJsonCodec.contentHash(ontologyA))
            .isEqualTo(OntologyJsonCodec.contentHash(ontologyB));
    }

    @Test
    void inMemoryStoreRoundTrip() {
        var store = new InMemoryOntologyStore();
        var ontology = sampleOntology();

        assertThat(store.load("key")).isEmpty();
        store.save("key", ontology);
        assertThat(store.load("key")).hasValue(ontology);
    }

    @Test
    void noopStoreDoesNotPersist() {
        var store = new NoopOntologyStore();
        store.save("key", sampleOntology());
        assertThat(store.load("key")).isEmpty();
    }

    @Test
    void propertyTypeNormalizationAcceptsAliasesAndWhitespace() {
        assertThat(OntologyPropertyType.normalize(" int ")).isEqualTo(OntologyPropertyType.INTEGER);
        assertThat(OntologyPropertyType.normalize("integer")).isEqualTo(OntologyPropertyType.INTEGER);
        assertThat(OntologyPropertyType.normalize("BoOlEaN")).isEqualTo(OntologyPropertyType.BOOLEAN);
        assertThat(OntologyPropertyType.normalize("bool")).isEqualTo(OntologyPropertyType.BOOLEAN);
        assertThat(OntologyPropertyType.normalize("   ")).isEqualTo(OntologyPropertyType.STRING);
        assertThat(OntologyPropertyType.normalize(null)).isEqualTo(OntologyPropertyType.STRING);
        assertThatThrownBy(() -> OntologyPropertyType.normalize("bogus"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown ontology property type");
    }

    @Test
    void codecAcceptsMissingOrNullPropertyType() {
        var json = """
            {
              "schemaVersion": 1,
              "kind": "ontology",
              "entityTypes": [
                {
                  "label": "Person",
                  "description": "A person",
                  "properties": [
                    {"name": "name"},
                    {"name": "age", "type": null}
                  ]
                }
              ],
              "relationTypes": [],
              "version": null,
              "metadata": {}
            }
            """;
        var ontology = OntologyJsonCodec.read(json);
        assertThat(ontology.entityTypes().get(0).properties()).hasSize(2);
        assertThat(ontology.entityTypes().get(0).properties().get(0).type()).isEqualTo(OntologyPropertyType.STRING);
        assertThat(ontology.entityTypes().get(0).properties().get(1).type()).isEqualTo(OntologyPropertyType.STRING);
    }

    private Ontology sampleOntology() {
        return new Ontology(
            List.of(new OntologyEntityType(
                "Person",
                "A person",
                List.of(
                    new OntologyProperty("name", OntologyPropertyType.STRING, "name", null),
                    new OntologyProperty("age", OntologyPropertyType.INTEGER, "years", null)
                ),
                null
            )),
            List.of(new OntologyRelationType(
                "works_at",
                "works at",
                List.of(new OntologyPattern("Person", "Organization")),
                List.of(),
                null
            )),
            "v1",
            Map.of()
        );
    }
}
