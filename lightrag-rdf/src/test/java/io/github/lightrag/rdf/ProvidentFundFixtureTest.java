package io.github.lightrag.rdf;

import io.github.lightrag.ontology.ExtractionMode;
import io.github.lightrag.ontology.Ontology;
import io.github.lightrag.ontology.OntologyAttributeProjector;
import io.github.lightrag.ontology.OntologyEntityType;
import io.github.lightrag.ontology.OntologyPattern;
import io.github.lightrag.ontology.OntologyProperty;
import io.github.lightrag.ontology.OntologyPropertyType;
import io.github.lightrag.ontology.OntologyRelationType;
import io.github.lightrag.ontology.UnmatchedLabel;
import io.github.lightrag.ontology.OntologyConformanceValidator;
import io.github.lightrag.ontology.bootstrap.BootstrapOptions;
import io.github.lightrag.ontology.bootstrap.BootstrapResult;
import io.github.lightrag.ontology.bootstrap.EntitySample;
import io.github.lightrag.ontology.bootstrap.OntologyBootstrap;
import io.github.lightrag.ontology.bootstrap.RelationSample;
import io.github.lightrag.ontology.rdf.OntologyRdfExporter;
import io.github.lightrag.ontology.rdf.RdfExportOptions;
import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
import io.github.lightrag.types.ExtractionResult;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * End-to-end validation on a housing provident fund corpus: import the domain
 * ontology, bootstrap a draft from corpus-derived samples, project and validate
 * attributes, and round-trip the ontology through Turtle and SHACL.
 *
 * <p>Extraction is deterministic corpus parsing rather than an LLM call, so the
 * fixture exercises the ontology pipeline without network or model variability.
 */
class ProvidentFundFixtureTest {

    private static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    private static final String SH = "http://www.w3.org/ns/shacl#";
    private static final String XSD_DOUBLE = "http://www.w3.org/2001/XMLSchema#double";

    private static final Pattern EMPLOYEE_NAME = Pattern.compile(
        "([\\u4e00-\\u9fa5]{2,3})是华东科技有限公司的缴存职工"
    );
    private static final Pattern IDENTITY_NUMBER = Pattern.compile("身份证号 (\\d{18})");
    private static final Pattern PHONE_NUMBERS = Pattern.compile("联系电话 ([\\d、]+)");
    private static final Pattern ENTRY_DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}) 入职");
    private static final Pattern ACCOUNT_OPENED = Pattern.compile(
        "(\\d{4}-\\d{2}-\\d{2}) 开立住房公积金账户"
    );
    private static final Pattern CONTRIBUTION_BASE = Pattern.compile("缴存基数为 ([\\d.]+) 元");
    private static final Pattern MONTHLY_CONTRIBUTION = Pattern.compile(
        "缴存额为 ([\\d.]+) 元"
    );
    private static final Pattern ACCOUNT_BALANCE = Pattern.compile("账户余额为 ([\\d.]+) 元");
    private static final Pattern CONTRIBUTION_RATIO = Pattern.compile(
        "缴存比例(?:均为)? ([\\d.]+)%"
    );
    private static final Pattern CREDIT_CODE = Pattern.compile("统一社会信用代码 (\\w+)");
    private static final Pattern LOAN = Pattern.compile(
        "(\\d{4}-\\d{2}-\\d{2})，李娜申请公积金贷款 ([\\d.]+) 元，期限 (\\d+) 年，年利率 ([\\d.]+)%"
    );
    private static final Pattern WITHDRAWAL = Pattern.compile(
        "(\\d{4}-\\d{2}-\\d{2})，李娜因租房提取住房公积金 ([\\d.]+) 元"
    );
    private static final Pattern TRUSTEE_BANK = Pattern.compile("(中国建设银行上海分行)作为受托银行");

    @Test
    void importsProvidentFundOntology() {
        ImportResult result = OntologyRdfImporter.importOntology(readFixture("ontology.ttl"));
        Ontology ontology = result.ontology();

        assertThat(result.warnings()).isEmpty();
        assertThat(ontology.version()).isEqualTo("1.0");
        assertThat(ontology.metadata()).containsEntry(
            "definitionHash", "provident-fund-fixture-v1"
        );
        assertThat(entityLabels(ontology)).containsExactlyInAnyOrder(
            "缴存职工", "缴存单位", "公积金账户", "公积金贷款", "提取记录"
        );
        assertThat(relationLabels(ontology)).containsExactlyInAnyOrder(
            "就职于", "缴存于", "申请", "办理"
        );
        assertThat(ontology.entityTypes().stream().mapToInt(type -> type.properties().size()).sum())
            .isEqualTo(15);

        assertThat(entityPropertyTypes(ontology, "缴存职工")).isEqualTo(Map.of(
            "姓名", OntologyPropertyType.STRING,
            "身份证号", OntologyPropertyType.STRING,
            "缴存基数", OntologyPropertyType.FLOAT,
            "月缴存额", OntologyPropertyType.FLOAT,
            "联系电话", OntologyPropertyType.LIST
        ));
        assertThat(entityPropertyTypes(ontology, "公积金账户")).isEqualTo(Map.of(
            "账户余额", OntologyPropertyType.FLOAT,
            "开户日期", OntologyPropertyType.DATE
        ));
        assertThat(entityPropertyTypes(ontology, "公积金贷款")).isEqualTo(Map.of(
            "贷款金额", OntologyPropertyType.FLOAT,
            "贷款期限", OntologyPropertyType.INTEGER,
            "贷款利率", OntologyPropertyType.FLOAT,
            "申请日期", OntologyPropertyType.DATE
        ));
        assertThat(entityPropertyTypes(ontology, "提取记录")).isEqualTo(Map.of(
            "提取金额", OntologyPropertyType.FLOAT,
            "提取日期", OntologyPropertyType.DATE,
            "提取原因", OntologyPropertyType.STRING
        ));

        assertThat(pattern(ontology, "就职于"))
            .isEqualTo(new OntologyPattern("缴存职工", "缴存单位"));
        assertThat(pattern(ontology, "缴存于"))
            .isEqualTo(new OntologyPattern("缴存单位", "公积金账户"));
        assertThat(pattern(ontology, "申请"))
            .isEqualTo(new OntologyPattern("缴存职工", "公积金贷款"));
        assertThat(pattern(ontology, "办理"))
            .isEqualTo(new OntologyPattern("缴存职工", "提取记录"));

        assertThat(relation(ontology, "就职于").properties())
            .extracting(OntologyProperty::name, OntologyProperty::type)
            .containsExactly(tuple("入职日期", OntologyPropertyType.DATE));
        assertThat(relation(ontology, "缴存于").properties())
            .extracting(OntologyProperty::name, OntologyProperty::type)
            .containsExactly(tuple("缴存月份", OntologyPropertyType.DATE));
        assertThat(relation(ontology, "申请").properties())
            .extracting(OntologyProperty::name, OntologyProperty::type)
            .containsExactly(tuple("申请日期", OntologyPropertyType.DATE));
    }

    @Test
    void bootstrapsFromCorpusAndRetainsBaseOntology() {
        Ontology base = OntologyRdfImporter.importOntology(readFixture("ontology.ttl")).ontology();
        FundFacts facts = parseCorpus(readFixture("corpus.txt"));
        BootstrapOptions options = new BootstrapOptions(1, 1, 1, true, 200_000, base);

        BootstrapResult result = new OntologyBootstrap().bootstrap(
            facts.entities(), facts.relations(), options
        );

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.draft().version()).isNull();
        assertThat(entityLabels(result.draft())).containsAll(entityLabels(base)).contains("受托银行");
        assertThat(relationLabels(result.draft())).containsAll(relationLabels(base));
        assertThat(entityPropertyTypes(result.draft(), "缴存单位"))
            .containsEntry("缴存比例", OntologyPropertyType.FLOAT)
            .containsEntry("统一社会信用代码", OntologyPropertyType.STRING);
        assertThat(entityPropertyTypes(result.draft(), "缴存职工"))
            .containsAllEntriesOf(entityPropertyTypes(base, "缴存职工"));
        assertThat(pattern(result.draft(), "就职于"))
            .isEqualTo(new OntologyPattern("缴存职工", "缴存单位"));

        List<EntitySample> reversedEntities = new ArrayList<>(facts.entities());
        Collections.reverse(reversedEntities);
        List<RelationSample> reversedRelations = new ArrayList<>(facts.relations());
        Collections.reverse(reversedRelations);
        BootstrapResult reversed = new OntologyBootstrap().bootstrap(
            reversedEntities, reversedRelations, options
        );

        assertThat(reversed.sampleFingerprint()).isEqualTo(result.sampleFingerprint());
        assertThat(reversed.draft().toCanonical()).isEqualTo(result.draft().toCanonical());
        assertThat(reversed.stats()).isEqualTo(result.stats());
    }

    @Test
    void projectsProvidentFundAttributesOntoDeclaredTypes() {
        Ontology ontology = OntologyRdfImporter.importOntology(readFixture("ontology.ttl")).ontology();

        Map<String, Object> contributor = new LinkedHashMap<>();
        contributor.put("姓名", "张伟");
        contributor.put("身份证号", 310101199003071234L);
        contributor.put("缴存基数", new BigDecimal("18000.50"));
        contributor.put("月缴存额", "4320.50");
        contributor.put("联系电话", List.of("13800138000", "13900139000"));
        contributor.put("婚姻状况", "已婚");

        Map<String, Object> projected = OntologyAttributeProjector.projectEntity(
            "缴存职工", contributor, ontology
        );

        assertThat(projected).containsOnlyKeys(
            "姓名", "身份证号", "缴存基数", "月缴存额", "联系电话"
        );
        assertThat(projected.get("姓名")).isEqualTo("张伟");
        assertThat(projected.get("身份证号")).isEqualTo("310101199003071234");
        assertThat(projected.get("缴存基数")).isEqualTo(18000.5);
        assertThat(projected.get("月缴存额")).isEqualTo(4320.5);
        assertThat(projected.get("联系电话"))
            .isEqualTo(List.of("13800138000", "13900139000"));

        Map<String, Object> invalidLoan = OntologyAttributeProjector.projectEntity(
            "公积金贷款",
            Map.of(
                "贷款金额", new BigDecimal("800000.50"),
                "贷款期限", "二十年"
            ),
            ontology
        );
        assertThat(invalidLoan).containsOnlyKeys("贷款金额");
        assertThat(invalidLoan.get("贷款金额")).isEqualTo(800000.5);

        Map<String, Object> relation = OntologyAttributeProjector.projectRelation(
            "就职于",
            Map.of("入职日期", "2019-07-01", "工号", "E1001"),
            ontology
        );
        assertThat(relation).containsOnlyKeys("入职日期");
        assertThat(relation.get("入职日期")).isEqualTo("2019-07-01");

        assertThat(OntologyAttributeProjector.projectRelation(
            "认识", Map.of("备注", "同事"), ontology
        )).isEmpty();
    }

    @Test
    void validatesCorpusExtractionInStrictAndPreferModes() {
        Ontology ontology = OntologyRdfImporter.importOntology(readFixture("ontology.ttl")).ontology();
        ExtractionResult extraction = corpusExtraction();

        List<UnmatchedLabel> strictDiagnostics = new ArrayList<>();
        ExtractionResult strict = new OntologyConformanceValidator(
            ontology, strictDiagnostics::add, false, ExtractionMode.STRICT
        ).validate("doc2", "", extraction);

        assertThat(strict.entities())
            .extracting(ExtractedEntity::name)
            .containsExactlyInAnyOrder("张伟", "李娜", "华东科技有限公司");
        assertThat(strict.relations())
            .extracting(ExtractedRelation::keywords)
            .containsExactlyInAnyOrder("就职于", "就职于");
        assertThat(strictDiagnostics)
            .extracting(UnmatchedLabel::kind, UnmatchedLabel::reason)
            .contains(
                tuple(UnmatchedLabel.Kind.ENTITY, UnmatchedLabel.Reason.UNKNOWN_ENTITY_TYPE),
                tuple(UnmatchedLabel.Kind.RELATION, UnmatchedLabel.Reason.PATTERN_MISMATCH)
            );

        List<UnmatchedLabel> preferDiagnostics = new ArrayList<>();
        ExtractionResult prefer = new OntologyConformanceValidator(
            ontology, preferDiagnostics::add, false, ExtractionMode.PREFER
        ).validate("doc2", "", extraction);

        assertThat(prefer.entities())
            .extracting(ExtractedEntity::name, ExtractedEntity::type)
            .containsExactlyInAnyOrder(
                tuple("张伟", "缴存职工"),
                tuple("李娜", "缴存职工"),
                tuple("华东科技有限公司", "缴存单位"),
                tuple("中国建设银行上海分行", "Other")
            );
        assertThat(prefer.relations()).hasSize(5);
        assertThat(preferDiagnostics)
            .filteredOn(diagnostic ->
                diagnostic.reason() == UnmatchedLabel.Reason.PATTERN_MISMATCH
            )
            .hasSize(2);
        assertThat(preferDiagnostics).anyMatch(diagnostic ->
            diagnostic.kind() == UnmatchedLabel.Kind.RELATION_KEYWORD
                && "认识".equals(diagnostic.rawLabel())
                && diagnostic.reason() == UnmatchedLabel.Reason.UNKNOWN_RELATION_KEYWORD
        );
    }

    @Test
    void exportsAndRoundTripsProvidentFundTurtleAndShacl() {
        Ontology ontology = OntologyRdfImporter.importOntology(readFixture("ontology.ttl")).ontology();
        RdfExportOptions options = RdfExportOptions.builder("https://example.com/provident-fund")
            .vocabularyBase("https://example.com/provident-fund/vocab/")
            .shapesBase("https://example.com/provident-fund/shapes/")
            .includeShacl(false)
            .build();

        String turtle = OntologyRdfExporter.exportTurtle(ontology, options);
        ImportResult roundTrip = OntologyRdfImporter.importOntology(turtle);
        Ontology imported = roundTrip.ontology();

        assertThat(imported.version()).isEqualTo("1.0");
        assertThat(entityLabels(imported))
            .containsExactlyInAnyOrderElementsOf(entityLabels(ontology));
        assertThat(relationLabels(imported))
            .containsExactlyInAnyOrderElementsOf(relationLabels(ontology));
        for (String label : entityLabels(ontology)) {
            assertThat(entityPropertyTypes(imported, label))
                .as("entity properties of %s", label)
                .isEqualTo(entityPropertyTypes(ontology, label));
        }
        for (String label : relationLabels(ontology)) {
            assertThat(relation(imported, label).patterns())
                .as("patterns of %s", label)
                .containsExactlyInAnyOrderElementsOf(relation(ontology, label).patterns());
            assertThat(relation(imported, label).properties())
                .as("relation attributes of %s", label)
                .extracting(OntologyProperty::name, OntologyProperty::type)
                .containsExactlyInAnyOrderElementsOf(
                    relation(ontology, label).properties().stream()
                        .map(property -> tuple(property.name(), property.type()))
                        .collect(Collectors.toList())
                );
        }

        String shacl = OntologyRdfExporter.exportShaclTurtle(ontology, options);
        ValueFactory values = SimpleValueFactory.getInstance();
        IRI shPath = values.createIRI(SH, "path");
        IRI shDatatype = values.createIRI(SH, "datatype");
        IRI shMaxCount = values.createIRI(SH, "maxCount");
        Model shapes = parse(shacl);
        Model vocabulary = parse(turtle);

        Map<String, Resource> shapeByProperty = new HashMap<>();
        for (Statement statement : shapes.filter(null, shPath, null)) {
            shapeByProperty.put(labelOf(vocabulary, statement.getObject()), statement.getSubject());
        }
        List<String> allEntityProperties = ontology.entityTypes().stream()
            .flatMap(type -> type.properties().stream())
            .map(OntologyProperty::name)
            .collect(Collectors.toList());
        assertThat(shapeByProperty.keySet())
            .containsExactlyInAnyOrderElementsOf(allEntityProperties);
        for (Resource shape : shapeByProperty.values()) {
            assertThat(shapes.filter(shape, shMaxCount, null))
                .allSatisfy(statement -> assertThat(statement.getObject().stringValue()).isEqualTo("1"));
        }

        Resource phoneShape = shapeByProperty.get("联系电话");
        assertThat(shapes.contains(phoneShape, shDatatype, null)).isFalse();
        Resource baseShape = shapeByProperty.get("缴存基数");
        assertThat(shapes.filter(baseShape, shDatatype, null))
            .extracting(statement -> statement.getObject().stringValue())
            .containsExactly(XSD_DOUBLE);
    }

    private static ExtractionResult corpusExtraction() {
        return new ExtractionResult(
            List.of(
                new ExtractedEntity("张伟", "缴存职工", "", List.of()),
                new ExtractedEntity("李娜", "缴存职工", "", List.of()),
                new ExtractedEntity("华东科技有限公司", "缴存单位", "", List.of()),
                new ExtractedEntity("中国建设银行上海分行", "受托银行", "", List.of())
            ),
            List.of(
                new ExtractedRelation("张伟", "华东科技有限公司", "就职于", "", 1.0),
                new ExtractedRelation("李娜", "华东科技有限公司", "就职于", "", 1.0),
                new ExtractedRelation("李娜", "华东科技有限公司", "申请", "", 1.0),
                new ExtractedRelation("张伟", "中国建设银行上海分行", "办理", "", 1.0),
                new ExtractedRelation("李娜", "中国建设银行上海分行", "认识", "", 1.0)
            ),
            List.of()
        );
    }

    private static FundFacts parseCorpus(String corpus) {
        String documentOne = section(corpus, "【文档一】", "【文档二】");
        String documentTwo = section(corpus, "【文档二】", "【文档三】");
        String documentThree = section(corpus, "【文档三】", null);

        String firstEmployee = match(EMPLOYEE_NAME, documentOne, 1);
        String secondEmployee = match(EMPLOYEE_NAME, documentTwo, 1);
        String employer = "华东科技有限公司";
        String firstAccount = firstEmployee + "的公积金账户";
        String loan = secondEmployee + "的公积金贷款";
        String withdrawal = secondEmployee + "的租房提取记录";
        String trusteeBank = match(TRUSTEE_BANK, documentTwo, 1);
        String entryDate = match(ENTRY_DATE, documentOne, 1);
        String secondEntryDate = match(ENTRY_DATE, documentTwo, 1);
        String openedOn = match(ACCOUNT_OPENED, documentOne, 1);
        String loanAppliedOn = match(LOAN, documentTwo, 1);
        String loanAmount = match(LOAN, documentTwo, 2);
        String loanTerm = match(LOAN, documentTwo, 3);
        String loanRate = match(LOAN, documentTwo, 4);
        String withdrawalDate = match(WITHDRAWAL, documentTwo, 1);
        String withdrawalAmount = match(WITHDRAWAL, documentTwo, 2);
        String creditCode = match(CREDIT_CODE, documentThree, 1);
        BigDecimal ratio = percentage(match(CONTRIBUTION_RATIO, documentOne, 1));

        List<String> phones = List.of(match(PHONE_NUMBERS, documentOne, 1).split("、"));

        List<EntitySample> entities = new ArrayList<>();
        entities.add(new EntitySample(
            firstEmployee,
            "缴存职工",
            Map.of(
                "姓名", firstEmployee,
                "身份证号", match(IDENTITY_NUMBER, documentOne, 1),
                "缴存基数", new BigDecimal(match(CONTRIBUTION_BASE, documentOne, 1)),
                "月缴存额", new BigDecimal(match(MONTHLY_CONTRIBUTION, documentOne, 1)),
                "联系电话", phones
            ),
            "doc1",
            "doc1-c1"
        ));
        entities.add(new EntitySample(
            secondEmployee,
            "缴存职工",
            Map.of(
                "姓名", secondEmployee,
                "身份证号", match(IDENTITY_NUMBER, documentTwo, 1),
                "缴存基数", new BigDecimal(match(CONTRIBUTION_BASE, documentTwo, 1)),
                "月缴存额", new BigDecimal(match(MONTHLY_CONTRIBUTION, documentTwo, 1)),
                "联系电话", List.of(match(PHONE_NUMBERS, documentTwo, 1))
            ),
            "doc2",
            "doc2-c1"
        ));
        entities.add(new EntitySample(
            employer, "缴存单位", Map.of("缴存比例", ratio), "doc1", "doc1-c1"
        ));
        entities.add(new EntitySample(
            employer,
            "缴存单位",
            Map.of("统一社会信用代码", creditCode, "缴存比例", percentage(
                match(CONTRIBUTION_RATIO, documentThree, 1)
            )),
            "doc3",
            "doc3-c1"
        ));
        entities.add(new EntitySample(
            firstAccount,
            "公积金账户",
            Map.of(
                "账户余额", new BigDecimal(match(ACCOUNT_BALANCE, documentOne, 1)),
                "开户日期", openedOn
            ),
            "doc1",
            "doc1-c1"
        ));
        entities.add(new EntitySample(
            loan,
            "公积金贷款",
            Map.of(
                "贷款金额", new BigDecimal(loanAmount),
                "贷款期限", Integer.valueOf(loanTerm),
                "贷款利率", percentage(loanRate),
                "申请日期", loanAppliedOn
            ),
            "doc2",
            "doc2-c1"
        ));
        entities.add(new EntitySample(
            withdrawal,
            "提取记录",
            Map.of(
                "提取金额", new BigDecimal(withdrawalAmount),
                "提取日期", withdrawalDate,
                "提取原因", "租房"
            ),
            "doc2",
            "doc2-c1"
        ));
        entities.add(new EntitySample(trusteeBank, "受托银行", Map.of(), "doc2", "doc2-c1"));
        entities.add(new EntitySample(
            match(TRUSTEE_BANK, documentThree, 1),
            "受托银行",
            Map.of(),
            "doc3",
            "doc3-c1"
        ));

        List<RelationSample> relations = List.of(
            new RelationSample(
                firstEmployee, employer, "就职于", Map.of("入职日期", entryDate), "doc1", "doc1-c1"
            ),
            new RelationSample(
                secondEmployee,
                employer,
                "就职于",
                Map.of("入职日期", secondEntryDate),
                "doc2",
                "doc2-c1"
            ),
            new RelationSample(
                employer,
                firstAccount,
                "缴存于",
                Map.of("缴存月份", "2026-09-01"),
                "doc1",
                "doc1-c1"
            ),
            new RelationSample(
                secondEmployee,
                loan,
                "申请",
                Map.of("申请日期", loanAppliedOn),
                "doc2",
                "doc2-c1"
            ),
            new RelationSample(
                secondEmployee, withdrawal, "办理", Map.of(), "doc2", "doc2-c1"
            )
        );
        return new FundFacts(List.copyOf(entities), List.copyOf(relations));
    }

    private static String section(String corpus, String start, String end) {
        int from = corpus.indexOf(start);
        if (from < 0) {
            throw new IllegalArgumentException("Missing corpus section: " + start);
        }
        int to = end == null ? corpus.length() : corpus.indexOf(end);
        if (to < 0) {
            throw new IllegalArgumentException("Missing corpus section: " + end);
        }
        return corpus.substring(from, to);
    }

    private static String match(Pattern pattern, String text, int group) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Corpus does not match " + pattern.pattern());
        }
        return matcher.group(group);
    }

    private static BigDecimal percentage(String value) {
        return new BigDecimal(value).divide(BigDecimal.valueOf(100));
    }

    private static Model parse(String turtle) {
        try {
            return Rio.parse(new StringReader(turtle), "", RDFFormat.TURTLE);
        } catch (IOException | org.eclipse.rdf4j.rio.RDFParseException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String labelOf(Model vocabulary, org.eclipse.rdf4j.model.Value resource) {
        IRI label = SimpleValueFactory.getInstance().createIRI(RDFS_LABEL);
        return vocabulary.filter((Resource) resource, label, null).stream()
            .map(statement -> statement.getObject().stringValue())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No rdfs:label for " + resource.stringValue()
            ));
    }

    private static List<String> entityLabels(Ontology ontology) {
        return ontology.entityTypes().stream()
            .map(OntologyEntityType::label)
            .collect(Collectors.toList());
    }

    private static List<String> relationLabels(Ontology ontology) {
        return ontology.relationTypes().stream()
            .map(OntologyRelationType::label)
            .collect(Collectors.toList());
    }

    private static OntologyEntityType entity(Ontology ontology, String label) {
        return ontology.entityTypes().stream()
            .filter(type -> type.label().equals(label))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown entity type: " + label));
    }

    private static OntologyRelationType relation(Ontology ontology, String label) {
        return ontology.relationTypes().stream()
            .filter(type -> type.label().equals(label))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown relation type: " + label));
    }

    private static OntologyPattern pattern(Ontology ontology, String label) {
        List<OntologyPattern> patterns = relation(ontology, label).patterns();
        assertThat(patterns).as("patterns of %s", label).hasSize(1);
        return patterns.get(0);
    }

    private static Map<String, OntologyPropertyType> entityPropertyTypes(
        Ontology ontology,
        String label
    ) {
        return entity(ontology, label).properties().stream()
            .collect(Collectors.toMap(OntologyProperty::name, OntologyProperty::type));
    }

    private static String readFixture(String name) {
        try (InputStream stream = ProvidentFundFixtureTest.class.getResourceAsStream(
            "/provident-fund/" + name
        )) {
            assertThat(stream).as("fixture resource %s", name).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record FundFacts(List<EntitySample> entities, List<RelationSample> relations) {}
}
