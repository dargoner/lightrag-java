package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedQueryIntentClassifierTest {
    @Test
    void classifiesIndirectPathQuestionAsMultiHop() {
        var classifier = new RuleBasedQueryIntentClassifier();

        var intent = classifier.classify(QueryRequest.builder()
            .query("Atlas 通过谁影响知识图谱组？")
            .build());

        assertThat(intent).isEqualTo(QueryIntent.MULTI_HOP);
    }

    @Test
    void classifiesEnglishIndirectPathQuestionAsMultiHop() {
        var classifier = new RuleBasedQueryIntentClassifier();

        var intent = classifier.classify(QueryRequest.builder()
            .query("Through which service does Atlas reach the knowledge graph team?")
            .build());

        assertThat(intent).isEqualTo(QueryIntent.MULTI_HOP);
    }

    @Test
    void classifiesRelationQuestionAsRelation() {
        var classifier = new RuleBasedQueryIntentClassifier();

        var intent = classifier.classify(QueryRequest.builder()
            .query("Atlas 和 GraphStore 是什么关系？")
            .build());

        assertThat(intent).isEqualTo(QueryIntent.RELATION);
    }

    @Test
    void defaultsToFactForSimpleQuestion() {
        var classifier = new RuleBasedQueryIntentClassifier();

        var intent = classifier.classify(QueryRequest.builder()
            .query("谁负责 GraphStore？")
            .build());

        assertThat(intent).isEqualTo(QueryIntent.FACT);
    }
}
