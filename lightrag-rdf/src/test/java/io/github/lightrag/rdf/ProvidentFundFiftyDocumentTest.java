package io.github.lightrag.rdf;

import io.github.lightrag.indexing.KnowledgeExtractor;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.ontology.ExtractionMode;
import io.github.lightrag.ontology.Ontology;
import io.github.lightrag.ontology.OntologyAttributeSchema;
import io.github.lightrag.ontology.OntologyConformanceValidator;
import io.github.lightrag.ontology.OntologyEntityType;
import io.github.lightrag.ontology.OntologyExtractionOverlay;
import io.github.lightrag.ontology.OntologyProperty;
import io.github.lightrag.ontology.OntologyPropertyType;
import io.github.lightrag.ontology.OntologyRelationType;
import io.github.lightrag.ontology.UnmatchedLabel;
import io.github.lightrag.ontology.bootstrap.BootstrapOptions;
import io.github.lightrag.ontology.bootstrap.BootstrapResult;
import io.github.lightrag.ontology.bootstrap.EntitySample;
import io.github.lightrag.ontology.bootstrap.OntologyBootstrap;
import io.github.lightrag.ontology.bootstrap.RelationSample;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
import io.github.lightrag.types.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fifty-document provident-fund corpus: deterministic bootstrap at scale,
 * extraction through the real {@link KnowledgeExtractor} prompt/parse/bind/
 * validate pipeline with a scripted model, and conformance behaviour in STRICT
 * and PREFER modes.
 *
 * <p>The scripted model returns corpus-derived JSON so extraction remains
 * offline and deterministic while still exercising prompt construction, JSON
 * parsing, attribute materialization, and ontology validation.
 */
class ProvidentFundFiftyDocumentTest {

    private static final Pattern EMPLOYEE_LINE = Pattern.compile(
        "^([\\u4e00-\\u9fa5]{2,3})是([\\u4e00-\\u9fa5]+有限公司)的缴存职工",
        Pattern.MULTILINE
    );
    private static final Pattern IDENTITY_NUMBER = Pattern.compile("身份证号 (\\d{18})");
    private static final Pattern PHONE_NUMBERS = Pattern.compile("联系电话 ([\\d、]+)");
    private static final Pattern ENTRY_OPENED = Pattern.compile(
        "^([\\u4e00-\\u9fa5]{2,3})于 (\\d{4}-\\d{2}-\\d{2}) 入职"
            + "([\\u4e00-\\u9fa5]+有限公司)，于 (\\d{4}-\\d{2}-\\d{2}) 开立住房公积金账户",
        Pattern.MULTILINE
    );
    private static final Pattern CONTRIBUTION = Pattern.compile(
        "^([\\u4e00-\\u9fa5]{2,3})的缴存基数为 ([\\d.]+) 元，"
            + "单位和个人的缴存比例均为 ([\\d.]+)%，2026 年 9 月缴存额为 ([\\d.]+) 元，"
            + "账户余额为 ([\\d.]+) 元。",
        Pattern.MULTILINE
    );
    private static final Pattern LOAN = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2})，([\\u4e00-\\u9fa5]{2,3})申请公积金贷款 ([\\d.]+) 元，"
            + "期限 (\\d+) 年，年利率 ([\\d.]+)%。",
        Pattern.MULTILINE
    );
    private static final Pattern WITHDRAWAL = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2})，([\\u4e00-\\u9fa5]{2,3})因租房提取住房公积金 ([\\d.]+) 元。",
        Pattern.MULTILINE
    );
    private static final Pattern EMPLOYER_LINE = Pattern.compile(
        "^([\\u4e00-\\u9fa5]+有限公司)是缴存单位，统一社会信用代码 (\\w+)，缴存比例 ([\\d.]+)%。",
        Pattern.MULTILINE
    );
    private static final Pattern TRUSTEE_BANK = Pattern.compile(
        "中国建设银行上海分行作为受托银行"
    );

    @Test
    void bootstrapsFiftyDocumentsDeterministically() {
        Ontology base = OntologyRdfImporter.importOntology(readFixture("ontology.ttl")).ontology();
        List<DocumentRecord> documents = parseCorpus(readFixture("corpus-50.txt"));

        assertThat(documents).hasSize(50);
        assertThat(documents.stream().map(DocumentRecord::employee).distinct()).hasSize(50);
        assertThat(documents.stream().map(DocumentRecord::employer).distinct()).hasSize(10);
        assertThat(documents.stream().filter(document -> document.loan() != null)).hasSize(25);
        assertThat(documents.stream().filter(document -> document.withdrawal() != null)).hasSize(10);
        assertThat(documents.stream().filter(DocumentRecord::trusteeBankMentioned)).hasSize(25);

        Samples samples = toSamples(documents);
        assertThat(samples.entities()).hasSize(210);
        assertThat(samples.relations()).hasSize(135);

        BootstrapOptions options = new BootstrapOptions(3, 2, 2, true, 200_000, base);
        BootstrapResult result = new OntologyBootstrap().bootstrap(
            samples.entities(), samples.relations(), options
        );

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.draft().version()).isNull();
        assertThat(result.stats().entitySamples()).isEqualTo(210);
        assertThat(result.stats().relationSamples()).isEqualTo(135);
        assertThat(entityLabels(result.draft())).containsAll(entityLabels(base)).contains("受托银行");
        assertThat(relationLabels(result.draft())).containsAll(relationLabels(base));
        assertThat(entityPropertyTypes(result.draft(), "缴存单位"))
            .containsEntry("缴存比例", OntologyPropertyType.FLOAT)
            .containsEntry("统一社会信用代码", OntologyPropertyType.STRING);
        assertThat(entityPropertyTypes(result.draft(), "缴存职工"))
            .containsAllEntriesOf(entityPropertyTypes(base, "缴存职工"));

        List<EntitySample> reversedEntities = new ArrayList<>(samples.entities());
        Collections.reverse(reversedEntities);
        List<RelationSample> reversedRelations = new ArrayList<>(samples.relations());
        Collections.reverse(reversedRelations);
        BootstrapResult reversed = new OntologyBootstrap().bootstrap(
            reversedEntities, reversedRelations, options
        );
        assertThat(reversed.sampleFingerprint()).isEqualTo(result.sampleFingerprint());
        assertThat(reversed.draft().toCanonical()).isEqualTo(result.draft().toCanonical());
        assertThat(reversed.stats()).isEqualTo(result.stats());

        BootstrapResult gated = new OntologyBootstrap().bootstrap(
            samples.entities(),
            samples.relations(),
            new BootstrapOptions(30, 2, 2, true, 200_000, base)
        );
        assertThat(entityLabels(gated.draft()))
            .containsAll(entityLabels(base))
            .doesNotContain("受托银行");
    }

    @Test
    void extractsFiftyDocumentsThroughTheOntologyPipeline() {
        Ontology base = OntologyRdfImporter.importOntology(readFixture("ontology.ttl")).ontology();
        List<DocumentRecord> documents = parseCorpus(readFixture("corpus-50.txt"));
        Ontology ontology = bootstrapDraft(documents, base);

        ExtractionRun run = runExtraction(documents, ontology, ExtractionMode.PREFER);

        assertThat(run.model().requests()).hasSize(50);
        assertThat(run.model().requests().get(0).systemPrompt())
            .contains("PREFER ONTOLOGY MODE")
            .contains("Preferred Relationship Patterns:")
            .contains("就职于 may occur between:")
            .contains("缴存职工 -> 缴存单位")
            .contains("Ontology Attributes:")
            .contains("缴存基数")
            .contains("联系电话")
            .contains("统一社会信用代码");
        assertThat(run.model().requests().get(0).userPrompt())
            .contains("【文档01】")
            .contains("张伟");
        assertThat(run.model().requests().get(49).userPrompt())
            .contains("【文档50】")
            .contains("姚远");

        assertThat(totalEntities(run.results())).isEqualTo(210);
        assertThat(totalRelations(run.results())).isEqualTo(160);
        assertThat(allEntities(run.results()))
            .filteredOn(entity -> "中国建设银行上海分行".equals(entity.name()))
            .hasSize(25)
            .allSatisfy(entity -> assertThat(entity.type()).isEqualTo("受托银行"));
        assertThat(run.diagnostics())
            .filteredOn(diagnostic -> diagnostic.reason() == UnmatchedLabel.Reason.PATTERN_MISMATCH)
            .hasSize(25);
        assertThat(run.diagnostics())
            .filteredOn(diagnostic -> diagnostic.reason() == UnmatchedLabel.Reason.INVALID_ATTRIBUTE_VALUE)
            .hasSize(1);
        assertThat(run.diagnostics())
            .filteredOn(diagnostic -> diagnostic.reason() == UnmatchedLabel.Reason.UNKNOWN_ENTITY_TYPE)
            .isEmpty();

        ExtractionResult first = run.results().get(0);
        assertThat(entity(first, "张伟").attributes()).containsOnly(
            Map.entry("姓名", "张伟"),
            Map.entry("身份证号", "310101197001011000"),
            Map.entry("缴存基数", 10000.5d),
            Map.entry("月缴存额", 1200.06d),
            Map.entry("联系电话", List.of("13810000000", "13920000000"))
        );
        assertThat(entity(first, "华东科技有限公司").attributes()).containsOnly(
            Map.entry("统一社会信用代码", "91310000MA1FL00001"),
            Map.entry("缴存比例", 0.12d)
        );
        assertThat(entity(first, "张伟的公积金账户").attributes()).containsOnly(
            Map.entry("账户余额", 50000.75d),
            Map.entry("开户日期", "2015-01-15")
        );
        assertThat(entity(first, "张伟的公积金贷款").attributes()).containsOnly(
            Map.entry("贷款金额", 300000.5d),
            Map.entry("贷款利率", 0.0285d),
            Map.entry("申请日期", "2026-09-01")
        );
        assertThat(entity(first, "张伟的租房提取记录").attributes()).containsOnly(
            Map.entry("提取金额", 20000.25d),
            Map.entry("提取日期", "2026-10-01"),
            Map.entry("提取原因", "租房")
        );
        assertThat(relation(first, "就职于").attributes())
            .containsOnly(Map.entry("入职日期", "2015-01-01"));
        assertThat(relation(first, "缴存于").attributes())
            .containsOnly(Map.entry("缴存月份", "2026-09-01"));
        assertThat(relation(first, "申请").attributes())
            .containsOnly(Map.entry("申请日期", "2026-09-01"));
    }

    @Test
    void validatesFiftyDocumentExtractionInStrictMode() {
        Ontology base = OntologyRdfImporter.importOntology(readFixture("ontology.ttl")).ontology();
        List<DocumentRecord> documents = parseCorpus(readFixture("corpus-50.txt"));
        Ontology ontology = bootstrapDraft(documents, base);

        ExtractionRun run = runExtraction(documents, ontology, ExtractionMode.STRICT);

        assertThat(totalEntities(run.results())).isEqualTo(210);
        assertThat(totalRelations(run.results())).isEqualTo(135);
        assertThat(allEntities(run.results()))
            .filteredOn(entity -> "中国建设银行上海分行".equals(entity.name()))
            .hasSize(25);
        assertThat(run.diagnostics())
            .filteredOn(diagnostic -> diagnostic.reason() == UnmatchedLabel.Reason.PATTERN_MISMATCH)
            .hasSize(25);
        assertThat(run.diagnostics())
            .filteredOn(diagnostic -> diagnostic.reason() == UnmatchedLabel.Reason.UNKNOWN_ENTITY_TYPE)
            .isEmpty();
        assertThat(run.diagnostics())
            .filteredOn(diagnostic -> diagnostic.reason() == UnmatchedLabel.Reason.INVALID_ATTRIBUTE_VALUE)
            .hasSize(1);
        assertThat(entity(run.results().get(0), "张伟的公积金贷款").attributes())
            .doesNotContainKey("贷款期限")
            .containsEntry("贷款金额", 300000.5d);
    }

    private static Ontology bootstrapDraft(List<DocumentRecord> documents, Ontology base) {
        Samples samples = toSamples(documents);
        return new OntologyBootstrap().bootstrap(
            samples.entities(),
            samples.relations(),
            new BootstrapOptions(3, 2, 2, true, 200_000, base)
        ).draft();
    }

    private static ExtractionRun runExtraction(
        List<DocumentRecord> documents,
        Ontology ontology,
        ExtractionMode mode
    ) {
        List<String> responses = documents.stream()
            .map(document -> extractionJson(document, document.number() == 1))
            .toList();
        ScriptedChatModel model = new ScriptedChatModel(responses);
        List<UnmatchedLabel> diagnostics = new ArrayList<>();
        KnowledgeExtractor extractor = new KnowledgeExtractor(
            model,
            0,
            KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS,
            "Chinese",
            entityLabels(ontology),
            relationLabels(ontology),
            List.of(),
            false,
            KnowledgeExtractor.DEFAULT_MAX_EXTRACTION_RECORDS,
            KnowledgeExtractor.DEFAULT_MAX_EXTRACTION_ENTITIES,
            new OntologyConformanceValidator(ontology, diagnostics::add, false, mode),
            false,
            OntologyExtractionOverlay.from(ontology, mode),
            OntologyAttributeSchema.from(ontology)
        );

        List<ExtractionResult> results = new ArrayList<>();
        for (int index = 0; index < documents.size(); index++) {
            DocumentRecord document = documents.get(index);
            results.add(extractor.extract(new Chunk(
                "chunk-" + (index + 1),
                "doc-" + (index + 1),
                document.text(),
                document.text().length(),
                index,
                Map.of()
            )));
        }
        return new ExtractionRun(model, List.copyOf(diagnostics), List.copyOf(results));
    }

    private static String extractionJson(DocumentRecord document, boolean invalidLoanTerm) {
        List<String> entities = new ArrayList<>();
        entities.add(entityJson(
            document.employee(),
            "缴存职工",
            attributes(
                string("姓名", document.employee()),
                string("身份证号", document.identityNumber()),
                string("缴存基数", document.contributionBase().toPlainString()),
                string("月缴存额", document.monthlyContribution().toPlainString()),
                list("联系电话", document.phones().stream().map(ProvidentFundFiftyDocumentTest::quote).toList())
            )
        ));
        entities.add(entityJson(
            document.employer(),
            "缴存单位",
            attributes(
                string("统一社会信用代码", document.creditCode()),
                number("缴存比例", document.ratio())
            )
        ));
        entities.add(entityJson(
            document.employee() + "的公积金账户",
            "公积金账户",
            attributes(
                string("账户余额", document.balance().toPlainString()),
                string("开户日期", document.openedOn())
            )
        ));
        if (document.loan() != null) {
            LoanRecord loan = document.loan();
            entities.add(entityJson(
                document.employee() + "的公积金贷款",
                "公积金贷款",
                attributes(
                    string("贷款金额", loan.amount().toPlainString()),
                    invalidLoanTerm ? string("贷款期限", "二十年") : number("贷款期限", loan.termYears()),
                    number("贷款利率", loan.rate()),
                    string("申请日期", loan.appliedOn())
                )
            ));
        }
        if (document.withdrawal() != null) {
            WithdrawalRecord withdrawal = document.withdrawal();
            entities.add(entityJson(
                document.employee() + "的租房提取记录",
                "提取记录",
                attributes(
                    string("提取金额", withdrawal.amount().toPlainString()),
                    string("提取日期", withdrawal.date()),
                    string("提取原因", withdrawal.reason())
                )
            ));
        }
        if (document.trusteeBankMentioned()) {
            entities.add(entityJson("中国建设银行上海分行", "受托银行", ""));
        }

        List<String> relations = new ArrayList<>();
        relations.add(relationJson(
            document.employee(),
            document.employer(),
            "就职于",
            attributes(string("入职日期", document.entryDate()))
        ));
        relations.add(relationJson(
            document.employer(),
            document.employee() + "的公积金账户",
            "缴存于",
            attributes(string("缴存月份", "2026-09-01"))
        ));
        if (document.loan() != null) {
            relations.add(relationJson(
                document.employee(),
                document.employee() + "的公积金贷款",
                "申请",
                attributes(string("申请日期", document.loan().appliedOn()))
            ));
        }
        if (document.withdrawal() != null) {
            relations.add(relationJson(
                document.employee(),
                document.employee() + "的租房提取记录",
                "办理",
                ""
            ));
        }
        if (document.trusteeBankMentioned()) {
            relations.add(relationJson(
                document.employee(),
                "中国建设银行上海分行",
                "办理",
                ""
            ));
        }

        return "{\"entities\":[" + String.join(",", entities)
            + "],\"relations\":[" + String.join(",", relations) + "]}";
    }

    private static String entityJson(String name, String type, String attributesJson) {
        return "{\"name\":" + quote(name)
            + ",\"type\":" + quote(type)
            + ",\"description\":\"公积金业务抽取\",\"aliases\":[]"
            + (attributesJson.isEmpty() ? "" : ",\"attributes\":" + attributesJson)
            + "}";
    }

    private static String relationJson(
        String source,
        String target,
        String keywords,
        String attributesJson
    ) {
        return "{\"source_entity\":" + quote(source)
            + ",\"target_entity\":" + quote(target)
            + ",\"relationship_keywords\":" + quote(keywords)
            + ",\"relationship_description\":\"公积金业务关系\""
            + (attributesJson.isEmpty() ? "" : ",\"attributes\":" + attributesJson)
            + "}";
    }

    private static String attributes(String... entries) {
        return "{" + String.join(",", entries) + "}";
    }

    private static String string(String key, String value) {
        return quote(key) + ":" + quote(value);
    }

    private static String number(String key, Number value) {
        return quote(key) + ":" + value;
    }

    private static String list(String key, List<String> values) {
        return quote(key) + ":[" + String.join(",", values) + "]";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Samples toSamples(List<DocumentRecord> documents) {
        List<EntitySample> entities = new ArrayList<>();
        List<RelationSample> relations = new ArrayList<>();
        for (DocumentRecord document : documents) {
            String chunkId = "doc" + document.number() + "-c1";
            String documentId = "doc" + document.number();
            entities.add(new EntitySample(
                document.employee(),
                "缴存职工",
                Map.of(
                    "姓名", document.employee(),
                    "身份证号", document.identityNumber(),
                    "缴存基数", document.contributionBase(),
                    "月缴存额", document.monthlyContribution(),
                    "联系电话", document.phones()
                ),
                documentId,
                chunkId
            ));
            entities.add(new EntitySample(
                document.employer(),
                "缴存单位",
                Map.of(
                    "统一社会信用代码", document.creditCode(),
                    "缴存比例", document.ratio()
                ),
                documentId,
                chunkId
            ));
            entities.add(new EntitySample(
                document.employee() + "的公积金账户",
                "公积金账户",
                Map.of(
                    "账户余额", document.balance(),
                    "开户日期", document.openedOn()
                ),
                documentId,
                chunkId
            ));
            if (document.loan() != null) {
                LoanRecord loan = document.loan();
                entities.add(new EntitySample(
                    document.employee() + "的公积金贷款",
                    "公积金贷款",
                    Map.of(
                        "贷款金额", loan.amount(),
                        "贷款期限", loan.termYears(),
                        "贷款利率", loan.rate(),
                        "申请日期", loan.appliedOn()
                    ),
                    documentId,
                    chunkId
                ));
            }
            if (document.withdrawal() != null) {
                entities.add(new EntitySample(
                    document.employee() + "的租房提取记录",
                    "提取记录",
                    Map.of(
                        "提取金额", document.withdrawal().amount(),
                        "提取日期", document.withdrawal().date(),
                        "提取原因", document.withdrawal().reason()
                    ),
                    documentId,
                    chunkId
                ));
            }
            if (document.trusteeBankMentioned()) {
                entities.add(new EntitySample(
                    "中国建设银行上海分行", "受托银行", Map.of(), documentId, chunkId
                ));
            }
            relations.add(new RelationSample(
                document.employee(),
                document.employer(),
                "就职于",
                Map.of("入职日期", document.entryDate()),
                documentId,
                chunkId
            ));
            relations.add(new RelationSample(
                document.employer(),
                document.employee() + "的公积金账户",
                "缴存于",
                Map.of("缴存月份", "2026-09-01"),
                documentId,
                chunkId
            ));
            if (document.loan() != null) {
                relations.add(new RelationSample(
                    document.employee(),
                    document.employee() + "的公积金贷款",
                    "申请",
                    Map.of("申请日期", document.loan().appliedOn()),
                    documentId,
                    chunkId
                ));
            }
            if (document.withdrawal() != null) {
                relations.add(new RelationSample(
                    document.employee(),
                    document.employee() + "的租房提取记录",
                    "办理",
                    Map.of(),
                    documentId,
                    chunkId
                ));
            }
        }
        return new Samples(List.copyOf(entities), List.copyOf(relations));
    }

    private static List<DocumentRecord> parseCorpus(String corpus) {
        String[] blocks = corpus.split("(?=【文档\\d{2}】)");
        List<DocumentRecord> documents = new ArrayList<>();
        for (String block : blocks) {
            String text = block.strip();
            if (text.isEmpty()) {
                continue;
            }
            Matcher employee = matcher(EMPLOYEE_LINE, text);
            Matcher entry = matcher(ENTRY_OPENED, text);
            Matcher contribution = matcher(CONTRIBUTION, text);
            Matcher employer = matcher(EMPLOYER_LINE, text);
            int number = Integer.parseInt(text.substring(3, 5));
            List<String> phones = List.of(group(PHONE_NUMBERS, text, 1).split("、"));
            LoanRecord loan = null;
            if (LOAN.matcher(text).find()) {
                loan = new LoanRecord(
                    group(LOAN, text, 1),
                    new BigDecimal(group(LOAN, text, 3)),
                    Integer.parseInt(group(LOAN, text, 4)),
                    percentage(group(LOAN, text, 5))
                );
            }
            WithdrawalRecord withdrawal = null;
            if (WITHDRAWAL.matcher(text).find()) {
                withdrawal = new WithdrawalRecord(
                    group(WITHDRAWAL, text, 1),
                    new BigDecimal(group(WITHDRAWAL, text, 3)),
                    "租房"
                );
            }
            documents.add(new DocumentRecord(
                number,
                text,
                employee.group(1),
                employee.group(2),
                group(IDENTITY_NUMBER, text, 1),
                phones,
                entry.group(2),
                entry.group(4),
                new BigDecimal(contribution.group(2)),
                new BigDecimal(contribution.group(4)),
                new BigDecimal(contribution.group(5)),
                employer.group(2),
                percentage(employer.group(3)),
                loan,
                withdrawal,
                TRUSTEE_BANK.matcher(text).find()
            ));
        }
        return List.copyOf(documents);
    }

    private static BigDecimal percentage(String value) {
        return new BigDecimal(value).divide(BigDecimal.valueOf(100));
    }

    private static Matcher matcher(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Corpus does not match " + pattern.pattern());
        }
        return matcher;
    }

    private static String group(Pattern pattern, String text, int group) {
        return matcher(pattern, text).group(group);
    }

    private static ExtractedEntity entity(ExtractionResult result, String name) {
        return result.entities().stream()
            .filter(entity -> entity.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Missing entity: " + name));
    }

    private static ExtractedRelation relation(ExtractionResult result, String keywords) {
        return result.relations().stream()
            .filter(relation -> relation.keywords().equals(keywords))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Missing relation: " + keywords));
    }

    private static int totalEntities(List<ExtractionResult> results) {
        return results.stream().mapToInt(result -> result.entities().size()).sum();
    }

    private static int totalRelations(List<ExtractionResult> results) {
        return results.stream().mapToInt(result -> result.relations().size()).sum();
    }

    private static List<ExtractedEntity> allEntities(List<ExtractionResult> results) {
        return results.stream().flatMap(result -> result.entities().stream()).toList();
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

    private static Map<String, OntologyPropertyType> entityPropertyTypes(
        Ontology ontology,
        String label
    ) {
        return ontology.entityTypes().stream()
            .filter(type -> type.label().equals(label))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown entity type: " + label))
            .properties().stream()
            .collect(Collectors.toMap(OntologyProperty::name, OntologyProperty::type));
    }

    private static String readFixture(String name) {
        try (InputStream stream = ProvidentFundFiftyDocumentTest.class.getResourceAsStream(
            "/provident-fund/" + name
        )) {
            assertThat(stream).as("fixture resource %s", name).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record LoanRecord(String appliedOn, BigDecimal amount, int termYears, BigDecimal rate) {}

    private record WithdrawalRecord(String date, BigDecimal amount, String reason) {}

    private record DocumentRecord(
        int number,
        String text,
        String employee,
        String employer,
        String identityNumber,
        List<String> phones,
        String entryDate,
        String openedOn,
        BigDecimal contributionBase,
        BigDecimal monthlyContribution,
        BigDecimal balance,
        String creditCode,
        BigDecimal ratio,
        LoanRecord loan,
        WithdrawalRecord withdrawal,
        boolean trusteeBankMentioned
    ) {}

    private record Samples(List<EntitySample> entities, List<RelationSample> relations) {}

    private record ExtractionRun(
        ScriptedChatModel model,
        List<UnmatchedLabel> diagnostics,
        List<ExtractionResult> results
    ) {}

    private static final class ScriptedChatModel implements ChatModel {
        private final List<String> responses;
        private final List<ChatRequest> requests = new ArrayList<>();

        private ScriptedChatModel(List<String> responses) {
            this.responses = List.copyOf(responses);
        }

        @Override
        public String generate(ChatRequest request) {
            requests.add(request);
            return responses.get(requests.size() - 1);
        }

        @Override
        public String cacheIdentity() {
            return "provident-fund-50-scripted";
        }

        List<ChatRequest> requests() {
            return List.copyOf(requests);
        }
    }
}
