package io.github.lightrag.storage.postgres;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresAgeBootstrapSearchPathTest {

    @Test
    void prependsAgCatalogToTheSessionSearchPath() {
        assertThat(PostgresAgeBootstrap.agCatalogFirstSearchPath("\"$user\", public"))
            .isEqualTo("ag_catalog, \"$user\", public");
    }

    @Test
    void keepsAgCatalogFirstWhenItIsAlreadyOnThePath() {
        // Duplicates are harmless in a search_path and keep the mapping single-branched.
        assertThat(PostgresAgeBootstrap.agCatalogFirstSearchPath("ag_catalog, public"))
            .isEqualTo("ag_catalog, ag_catalog, public");
    }

    @Test
    void fallsBackToTheDefaultPathWhenTheOriginalIsBlank() {
        assertThat(PostgresAgeBootstrap.agCatalogFirstSearchPath(null))
            .isEqualTo("ag_catalog, \"$user\", public");
        assertThat(PostgresAgeBootstrap.agCatalogFirstSearchPath("  "))
            .isEqualTo("ag_catalog, \"$user\", public");
    }
}
