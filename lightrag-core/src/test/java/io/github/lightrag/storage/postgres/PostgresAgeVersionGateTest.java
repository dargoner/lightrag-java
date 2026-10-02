package io.github.lightrag.storage.postgres;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.lightrag.storage.postgres.PostgresAgeBootstrap.ALLOW_UNSUPPORTED_VERSION_ENV;
import static io.github.lightrag.storage.postgres.PostgresAgeBootstrap.VersionProbe;
import static io.github.lightrag.storage.postgres.PostgresAgeBootstrap.VersionProbe.ABSENT;
import static io.github.lightrag.storage.postgres.PostgresAgeBootstrap.decide;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PostgresAgeVersionGateTest {
    @Test
    void proceedsSilentlyWhenBothSourcesReportSupportedVersions() {
        var decision = decide(VersionProbe.readable("1.7.0"), VersionProbe.readable("1.7.0"), false);

        assertThat(decision.proceed()).isTrue();
        assertThat(decision.unverified()).isFalse();
        assertThat(decision.message()).isNull();
    }

    @Test
    void proceedsWhenTheHigherOfTwoSupportedVersionsDecides() {
        var decision = decide(VersionProbe.readable("1.6.0"), VersionProbe.readable("1.7.0"), false);

        assertThat(decision.proceed()).isTrue();
        assertThat(decision.message()).isNull();
    }

    @Test
    void proceedsWhenOnlyOneCatalogNamesAgeWithASupportedVersion() {
        assertThat(decide(VersionProbe.readable("1.7.0"), ABSENT, false).proceed()).isTrue();
        assertThat(decide(VersionProbe.readable("1.7.0"), ABSENT, false).message()).isNull();
        assertThat(decide(ABSENT, VersionProbe.readable("1.6.0"), false).proceed()).isTrue();
        assertThat(decide(ABSENT, VersionProbe.readable("1.6.0"), false).message()).isNull();
    }

    @Test
    void proceedsWithAnExplanatoryMessageWhenNeitherCatalogNamesAge() {
        var decision = decide(ABSENT, ABSENT, false);

        assertThat(decision.proceed()).isTrue();
        assertThat(decision.unverified()).isFalse();
        assertThat(decision.message()).contains("No Apache AGE version check");
    }

    @Test
    void refusesUnsupportedVersionsAtOrAboveTheCutoff() {
        var decision = decide(VersionProbe.readable("1.8.0"), ABSENT, false);

        assertThat(decision.proceed()).isFalse();
        assertThat(decision.message())
            .contains("1.8.0")
            .contains(ALLOW_UNSUPPORTED_VERSION_ENV)
            .contains("graph-backend=table")
            .contains("https://github.com/apache/age/issues/2500");
    }

    @Test
    void letsTheNewerVersionDecideEvenWhenTheOlderOneIsSafe() {
        var decision = decide(VersionProbe.readable("1.7.0"), VersionProbe.readable("1.9.0"), false);

        assertThat(decision.proceed()).isFalse();
        assertThat(decision.message()).contains("1.9.0");
    }

    @Test
    void explainsTheReadBrokenStateWhenInstalledIsNewerThanAvailable() {
        var decision = decide(VersionProbe.readable("1.8.0"), VersionProbe.readable("1.7.0"), false);

        assertThat(decision.proceed()).isFalse();
        assertThat(decision.message())
            .contains("1.8.0")
            .contains("ag function does not exist")
            .contains("DROP EXTENSION age CASCADE");
    }

    @Test
    void refusesPresentButUnparseableVersions() {
        var unparseable = decide(VersionProbe.readable("1.7"), ABSENT, false);
        assertThat(unparseable.proceed()).isFalse();
        assertThat(unparseable.message())
            .contains("'1.7'")
            .contains(ALLOW_UNSUPPORTED_VERSION_ENV)
            .contains("graph-backend=table");

        var blank = decide(VersionProbe.readable(""), ABSENT, false);
        assertThat(blank.proceed()).isFalse();
        assertThat(blank.message()).contains("''");
    }

    @Test
    void refusesPresentButUnreadableVersions() {
        assertThat(decide(VersionProbe.withoutVersion(), ABSENT, false).proceed()).isFalse();
        assertThat(decide(ABSENT, VersionProbe.withoutVersion(), false).proceed()).isFalse();
        assertThat(decide(VersionProbe.withoutVersion(), VersionProbe.withoutVersion(), false).proceed()).isFalse();
    }

    @Test
    void anUnreadableSiblingDoesNotMaskAKnownSafeVersion() {
        var decision = decide(VersionProbe.readable("1.7.0"), VersionProbe.withoutVersion(), false);

        assertThat(decision.proceed()).isFalse();
        assertThat(decision.message()).contains("'1.7.0'").contains("<unreadable>");
    }

    @Test
    void overrideAllowsAnUnsupportedVersionWithAWarning() {
        var decision = decide(VersionProbe.readable("1.8.0"), VersionProbe.readable("1.9.0"), true);

        assertThat(decision.proceed()).isTrue();
        assertThat(decision.unverified()).isTrue();
        assertThat(decision.message())
            .contains("1.9.0")
            .contains(ALLOW_UNSUPPORTED_VERSION_ENV)
            .doesNotContain("graph-backend=table");
    }

    @Test
    void overrideAllowsAnUnverifiedVersionWithAWarning() {
        var decision = decide(VersionProbe.readable("1.7"), ABSENT, true);

        assertThat(decision.proceed()).isTrue();
        assertThat(decision.unverified()).isTrue();
        assertThat(decision.message()).contains(ALLOW_UNSUPPORTED_VERSION_ENV);
    }

    @Test
    void readsTheOverrideFromSystemPropertyWhenTheEnvironmentIsAbsent() {
        assumeTrue(System.getenv(ALLOW_UNSUPPORTED_VERSION_ENV) == null, "environment override is set");
        var previous = System.getProperty(ALLOW_UNSUPPORTED_VERSION_ENV);
        try {
            System.clearProperty(ALLOW_UNSUPPORTED_VERSION_ENV);
            assertThat(PostgresAgeBootstrap.allowUnsupportedVersion()).isFalse();

            for (var truthy : List.of("true", "TRUE", " 1 ", "yes", "T", "on")) {
                System.setProperty(ALLOW_UNSUPPORTED_VERSION_ENV, truthy);
                assertThat(PostgresAgeBootstrap.allowUnsupportedVersion()).as("property [%s]", truthy).isTrue();
            }
            for (var falsy : List.of("", "false", "0", "no", "off", "2")) {
                System.setProperty(ALLOW_UNSUPPORTED_VERSION_ENV, falsy);
                assertThat(PostgresAgeBootstrap.allowUnsupportedVersion()).as("property [%s]", falsy).isFalse();
            }
        } finally {
            if (previous == null) {
                System.clearProperty(ALLOW_UNSUPPORTED_VERSION_ENV);
            } else {
                System.setProperty(ALLOW_UNSUPPORTED_VERSION_ENV, previous);
            }
        }
    }
}
