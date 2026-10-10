package io.github.lightrag.query;

import io.github.lightrag.model.HeuristicTokenCounter;
import io.github.lightrag.model.TokenCounter;
import io.github.lightrag.types.Entity;
import io.github.lightrag.types.Relation;
import io.github.lightrag.types.ScoredEntity;
import io.github.lightrag.types.ScoredRelation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class QueryBudgetingTest {

    @Test
    void formatsEntityAndRelationRowsAsUpstreamJson() {
        var entity = new Entity("alice", "Alice", "person", "Researcher", List.of(), List.of("chunk-1"));
        var relation = new Relation(
            "rel-1", "alice", "bob", "works_with", "Alice works with Bob", 0.8d, List.of("chunk-1")
        );

        assertThat(QueryBudgeting.formatEntity(new ScoredEntity("alice", entity, 0.9d)))
            .isEqualTo("{\"entity\":\"Alice\",\"type\":\"person\",\"description\":\"Researcher\"}");
        assertThat(QueryBudgeting.formatRelation(new ScoredRelation("rel-1", relation, 0.8d)))
            .isEqualTo("{\"entity1\":\"alice\",\"entity2\":\"bob\",\"description\":\"Alice works with Bob\"}");
    }

    @Test
    void countsTheNewlineSeparatorAgainstTheBudget() {
        // Exact character counting keeps the boundary arithmetic readable: the two rows joined by
        // "\n" are one token longer than the rows alone, so the second row must fall out at the
        // joined budget while the old per-row sum still admitted it.
        var budgeting = new QueryBudgeting(String::length);
        var first = entity("alpha");
        var second = entity("bravo");
        var rowLength = QueryBudgeting.formatEntity(first).length();
        assertThat(QueryBudgeting.formatEntity(second)).hasSize(rowLength);

        assertThat(budgeting.limitEntities(List.of(first, second), 2 * rowLength))
            .extracting(ScoredEntity::entityId)
            .containsExactly("alpha");
        assertThat(budgeting.limitEntities(List.of(first, second), 2 * rowLength + 1))
            .extracting(ScoredEntity::entityId)
            .containsExactly("alpha", "bravo");
    }

    @Test
    void keepsAMaximalPrefixWhoseJoinedRowsFitTheBudget() {
        var budgeting = new QueryBudgeting(String::length);
        var entities = List.of(entity("alpha"), entity("bravo"), entity("charlie"));
        var budget = String.join(
            "\n",
            QueryBudgeting.formatEntity(entities.get(0)),
            QueryBudgeting.formatEntity(entities.get(1))
        ).length();

        var limited = budgeting.limitEntities(entities, budget);

        assertThat(limited).extracting(ScoredEntity::entityId).containsExactly("alpha", "bravo");
        var joined = String.join("\n", limited.stream().map(QueryBudgeting::formatEntity).toList());
        assertThat(joined).hasSizeLessThanOrEqualTo(budget);
    }

    @Test
    void nonPositiveBudgetOrNoItemsYieldsNothing() {
        var budgeting = new QueryBudgeting(String::length);
        assertThat(budgeting.limitEntities(List.of(entity("alpha")), 0)).isEmpty();
        assertThat(budgeting.limitEntities(List.of(), 100)).isEmpty();
        assertThat(budgeting.limitRelations(List.of(), 100)).isEmpty();
    }

    @Test
    void binarySearchKeepsTheSameMaximalPrefixAsTheLinearScanDown() {
        var counter = new HeuristicTokenCounter();
        var budgeting = new QueryBudgeting(counter);
        var random = new Random(20261010L);
        var fragments = List.of(
            "alpha", "bravo", "中文实体", "コンニチハ", "résumé", "x9",
            "long-word-abcdefghijklmnop", "混合 mixed 文本"
        );
        for (var round = 0; round < 300; round++) {
            var size = random.nextInt(14);
            var entities = new ArrayList<ScoredEntity>();
            for (var index = 0; index < size; index++) {
                var fragment = fragments.get(random.nextInt(fragments.size()));
                var id = "e" + round + "-" + index;
                entities.add(new ScoredEntity(
                    id, new Entity(id, fragment, "t", fragment, List.of(), List.of("chunk-1")), 0.9d));
            }
            var rendered = entities.stream().map(QueryBudgeting::formatEntity).toList();
            var budget = 1 + random.nextInt(160);
            var expected = referenceKept(rendered, budget, counter);

            assertThat(budgeting.limitEntities(entities, budget))
                .as("round %s budget %s", round, budget)
                .extracting(ScoredEntity::entityId)
                .isEqualTo(entities.subList(0, expected).stream().map(ScoredEntity::entityId).toList());
        }
    }

    @Test
    void exactFitBudgetKeepsEveryRowAndOneTokenLessDropsTheTail() {
        var counter = new HeuristicTokenCounter();
        var budgeting = new QueryBudgeting(counter);
        var entities = List.of(entity("alpha"), entity("中文实体"), entity("bravo"));
        var rendered = entities.stream().map(QueryBudgeting::formatEntity).toList();
        var fullTokens = counter.countTokens(String.join("\n", rendered));

        assertThat(budgeting.limitEntities(entities, fullTokens))
            .extracting(ScoredEntity::entityId)
            .containsExactlyElementsOf(entities.stream().map(ScoredEntity::entityId).toList());
        assertThat(budgeting.limitEntities(entities, fullTokens - 1))
            .hasSize(referenceKept(rendered, fullTokens - 1, counter));
        assertThat(budgeting.limitEntities(entities, 1))
            .hasSize(referenceKept(rendered, 1, counter));
    }

    /**
     * The pre-optimization algorithm, kept as the differential oracle: scan down from the full list
     * and stop at the first prefix whose join fits the budget.
     */
    private static int referenceKept(List<String> rendered, int maxTokens, TokenCounter counter) {
        var kept = rendered.size();
        while (kept > 0 && approximate(counter, String.join("\n", rendered.subList(0, kept))) > maxTokens) {
            kept--;
        }
        return kept;
    }

    private static int approximate(TokenCounter counter, String text) {
        var normalized = text.trim();
        return normalized.isEmpty() ? 0 : counter.countTokens(normalized);
    }

    private static ScoredEntity entity(String name) {
        return new ScoredEntity(name, new Entity(name, name, "t", "", List.of(), List.of("chunk-1")), 0.9d);
    }
}
