package io.github.lightrag.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the release manifest: every module wired for publication in the root
 * build must also be published by the release workflow, and vice versa.
 */
class ReleaseManifestConsistencyTest {

    private static final Pattern PUBLISHED_PROJECT =
        Pattern.compile("project\\(\":([^\"]+)\"\\)");
    private static final Pattern RELEASE_TASK =
        Pattern.compile(":([A-Za-z0-9_-]+):publishAndReleaseToMavenCentral");
    private static final Pattern SETTINGS_INCLUDE =
        Pattern.compile("include\\(\"([^\"]+)\"\\)");

    @Test
    void releaseWorkflowPublishesExactlyTheConfiguredProjects() throws IOException {
        Path root = repositoryRoot();
        Set<String> configured = matches(root.resolve("build.gradle.kts"), PUBLISHED_PROJECT);
        Set<String> released = matches(
            root.resolve(".github/workflows/release.yml"), RELEASE_TASK);
        Set<String> included = matches(root.resolve("settings.gradle.kts"), SETTINGS_INCLUDE);

        assertThat(configured).isNotEmpty();
        assertThat(released)
            .as("release workflow publish tasks must match the root build publication list")
            .containsExactlyInAnyOrderElementsOf(configured);
        assertThat(included)
            .as("every published project must be included by settings.gradle.kts")
            .containsAll(configured);
    }

    private static Set<String> matches(Path file, Pattern pattern) throws IOException {
        Matcher matcher = pattern.matcher(Files.readString(file, StandardCharsets.UTF_8));
        Set<String> values = new TreeSet<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the repository root");
    }
}
