package io.github.lightrag.ontology;

import java.util.Objects;

/**
 * Unified structured-source binding contract.
 *
 * <p>Structured schema elements (tables, columns, foreign keys) and textual
 * sources both flow into the same ontology model. For textual sources this
 * value is {@code null}. For structured sources the fields are populated as
 * follows:
 *
 * <ul>
 *   <li><b>Entity</b>: {@code tableName} identifies the source table.</li>
 *   <li><b>Property</b>: {@code tableName} + {@code column} identify the source column.</li>
 *   <li><b>Relation</b> (foreign key): all four fields are populated,
 *       where {@code (tableName, column)} points to {@code (refTableName, refColumn)}.</li>
 * </ul>
 */
public record SourceBinding(
    String tableName,
    String column,
    String refTableName,
    String refColumn,
    Boolean nullable
) {
    public SourceBinding {
        boolean hasTableName = tableName != null && !tableName.isBlank();
        boolean hasColumn = column != null && !column.isBlank();
        boolean hasRefTableName = refTableName != null && !refTableName.isBlank();
        boolean hasRefColumn = refColumn != null && !refColumn.isBlank();

        if (hasColumn && !hasTableName) {
            throw new IllegalArgumentException("column requires tableName");
        }
        if ((hasRefTableName || hasRefColumn) && !(hasTableName && hasColumn && hasRefTableName && hasRefColumn)) {
            throw new IllegalArgumentException(
                "relation binding requires tableName, column, refTableName, and refColumn"
            );
        }

        tableName = hasTableName ? tableName.strip() : null;
        column = hasColumn ? column.strip() : null;
        refTableName = hasRefTableName ? refTableName.strip() : null;
        refColumn = hasRefColumn ? refColumn.strip() : null;
    }
}
