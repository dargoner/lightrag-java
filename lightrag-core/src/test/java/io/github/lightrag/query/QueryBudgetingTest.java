package io.github.lightrag.query;

import io.github.lightrag.types.Entity;
import io.github.lightrag.types.Relation;
import io.github.lightrag.types.ScoredEntity;
import io.github.lightrag.types.ScoredRelation;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    private static ScoredEntity entity(String name) {
        return new ScoredEntity(name, new Entity(name, name, "t", "", List.of(), List.of("chunk-1")), 0.9d);
    }
}
