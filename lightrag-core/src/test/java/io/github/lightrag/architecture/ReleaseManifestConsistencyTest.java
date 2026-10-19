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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the release manifest: every module wired for publication in the root
 * build must also be published by the release workflow, and vice versa.
 */
class ReleaseManifestConsistencyTest {

    private static final Pattern PUBLISHED_PROJECT =
        Pattern.compile("project\\(\":([^\"]+)\"\\)");
    private static final Pattern RELEASE_TASK =
        Pattern.compile(":([A-Za-z0-9_-]+):publishAndReleaseToMavenCentral(?![A-Za-z0-9_-])");
    private static final Pattern SETTINGS_INCLUDE =
        Pattern.compile("include\\(\"([^\"]+)\"\\)");
    private static final String PUBLISH_STEP = "- name: Publish to Maven Central";

    @Test
    void releaseWorkflowPublishesExactlyTheConfiguredProjects() throws IOException {
        Path root = repositoryRoot();
        assertManifestConsistent(
            parsePublicationProjects(read(root.resolve("build.gradle.kts"))),
            parseReleaseTasks(read(root.resolve(".github/workflows/release.yml"))),
            parseSettingsIncludes(read(root.resolve("settings.gradle.kts"))));
    }

    @Test
    void gateFailsWhenThePublishTaskIsOnlyMentionedInAWorkflowComment() {
        String buildScript = publicationBlock(
            "project(\":lightrag-core\")",
            "project(\":lightrag-rdf\")",
            "project(\":lightrag-spring-boot-starter\")");
        String workflow = publishStep("""
            ./gradlew
            :lightrag-core:publishAndReleaseToMavenCentral
            # :lightrag-rdf:publishAndReleaseToMavenCentral
            :lightrag-spring-boot-starter:publishAndReleaseToMavenCentral
            """);

        assertThatThrownBy(() -> assertManifestConsistent(
            parsePublicationProjects(buildScript),
            parseReleaseTasks(workflow),
            parseSettingsIncludes(settingsIncludes("lightrag-core", "lightrag-rdf",
                "lightrag-spring-boot-starter"))))
            .isInstanceOf(AssertionError.class);
    }

    @Test
    void gateFailsWhenThePublishTaskIsSuffixed() {
        String workflow = publishStep("""
            ./gradlew
            :lightrag-core:publishAndReleaseToMavenCentral
            :lightrag-rdf:publishAndReleaseToMavenCentralDisabled
            :lightrag-spring-boot-starter:publishAndReleaseToMavenCentral
            """);

        assertThat(parseReleaseTasks(workflow))
            .containsExactly("lightrag-core", "lightrag-spring-boot-starter");
    }

    @Test
    void gateFailsWhenThePublicationEntryIsOnlyMentionedInAScriptComment() {
        String buildScript = """
            configure(
                listOf(
                    project(":lightrag-core"),
                    // project(":lightrag-rdf"),
                    project(":lightrag-spring-boot-starter"),
                ),
            ) {
                apply(plugin = "com.vanniktech.maven.publish")
            }
            """;
        String workflow = publishStep("""
            ./gradlew
            :lightrag-core:publishAndReleaseToMavenCentral
            :lightrag-rdf:publishAndReleaseToMavenCentral
            :lightrag-spring-boot-starter:publishAndReleaseToMavenCentral
            """);

        assertThatThrownBy(() -> assertManifestConsistent(
            parsePublicationProjects(buildScript),
            parseReleaseTasks(workflow),
            parseSettingsIncludes(settingsIncludes("lightrag-core", "lightrag-rdf",
                "lightrag-spring-boot-starter"))))
            .isInstanceOf(AssertionError.class);
    }

    @Test
    void parsersIgnoreReferencesOutsideThePublishingSections() {
        String buildScript = publicationBlock("project(\":lightrag-core\")") + """
            subprojects {
                project(":lightrag-rdf")
            }
            """;
        String workflow = """
            - name: Build
              run: >
                ./gradlew
                :lightrag-rdf:publishAndReleaseToMavenCentral
            """ + "\n" + publishStep("""
            ./gradlew
            :lightrag-core:publishAndReleaseToMavenCentral
            """);

        assertThat(parsePublicationProjects(buildScript)).containsExactly("lightrag-core");
        assertThat(parseReleaseTasks(workflow)).containsExactly("lightrag-core");
    }

    private static void assertManifestConsistent(
        Set<String> configured,
        Set<String> released,
        Set<String> included
    ) {
        assertThat(configured).isNotEmpty();
        assertThat(released)
            .as("release workflow publish tasks must match the root build publication list")
            .containsExactlyInAnyOrderElementsOf(configured);
        assertThat(included)
            .as("every published project must be included by settings.gradle.kts")
            .containsAll(configured);
    }

    static Set<String> parsePublicationProjects(String buildScript) {
        String clean = stripKotlinComments(buildScript);
        Set<String> projects = new TreeSet<>();
        int searchFrom = 0;
        while (true) {
            int configureIndex = clean.indexOf("configure(", searchFrom);
            if (configureIndex < 0) {
                return projects;
            }
            int open = clean.indexOf('(', configureIndex);
            int close = matchingDelimiter(clean, open, '(', ')');
            if (close < 0) {
                return projects;
            }
            int end = close;
            int cursor = close + 1;
            while (cursor < clean.length() && Character.isWhitespace(clean.charAt(cursor))) {
                cursor++;
            }
            if (cursor < clean.length() && clean.charAt(cursor) == '{') {
                int lambdaClose = matchingDelimiter(clean, cursor, '{', '}');
                if (lambdaClose > 0) {
                    end = lambdaClose;
                }
            }
            String block = clean.substring(configureIndex, end + 1);
            if (block.contains("com.vanniktech.maven.publish")) {
                Matcher matcher = PUBLISHED_PROJECT.matcher(block);
                while (matcher.find()) {
                    projects.add(matcher.group(1));
                }
            }
            searchFrom = end + 1;
        }
    }

    static Set<String> parseReleaseTasks(String workflow) {
        Set<String> tasks = new TreeSet<>();
        Matcher matcher = RELEASE_TASK.matcher(publishStepRun(workflow));
        while (matcher.find()) {
            tasks.add(matcher.group(1));
        }
        return tasks;
    }

    static Set<String> parseSettingsIncludes(String settings) {
        Set<String> included = new TreeSet<>();
        Matcher matcher = SETTINGS_INCLUDE.matcher(stripKotlinComments(settings));
        while (matcher.find()) {
            included.add(matcher.group(1));
        }
        return included;
    }

    private static String publishStepRun(String workflow) {
        String[] lines = workflow.split("\\R", -1);
        int stepStart = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals(PUBLISH_STEP)) {
                stepStart = i;
                break;
            }
        }
        if (stepStart < 0) {
            throw new IllegalArgumentException("Missing step: " + PUBLISH_STEP);
        }

        int stepIndent = indentOf(lines[stepStart]);
        int runLine = -1;
        for (int i = stepStart + 1; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("- ") && indentOf(lines[i]) <= stepIndent) {
                break;
            }
            if (trimmed.startsWith("run:")) {
                runLine = i;
                break;
            }
        }
        if (runLine < 0) {
            throw new IllegalArgumentException("Missing run block in step: " + PUBLISH_STEP);
        }

        int runIndent = indentOf(lines[runLine]);
        StringBuilder body = new StringBuilder();
        for (int i = runLine + 1; i < lines.length; i++) {
            String line = lines[i];
            if (!line.isBlank() && indentOf(line) <= runIndent) {
                break;
            }
            body.append(stripShellComment(line)).append('\n');
        }
        return body.toString();
    }

    private static String stripShellComment(String line) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
            } else if (current == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
            } else if (current == '#' && !singleQuoted && !doubleQuoted
                && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String stripKotlinComments(String source) {
        StringBuilder stripped = new StringBuilder(source.length());
        boolean lineComment = false;
        boolean blockComment = false;
        boolean inString = false;
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (lineComment) {
                if (current == '\n') {
                    lineComment = false;
                    stripped.append(current);
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    i++;
                } else if (current == '\n') {
                    stripped.append(current);
                }
                continue;
            }
            if (inString) {
                stripped.append(current);
                if (current == '\\' && next != '\0') {
                    stripped.append(next);
                    i++;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
                stripped.append(current);
            } else if (current == '/' && next == '/') {
                lineComment = true;
                i++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                i++;
            } else {
                stripped.append(current);
            }
        }
        return stripped.toString();
    }

    private static int matchingDelimiter(String text, int open, char opening, char closing) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == opening) {
                depth++;
            } else if (current == closing) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int indentOf(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        return indent;
    }

    private static String publicationBlock(String... projects) {
        StringBuilder script = new StringBuilder("configure(\n    listOf(\n");
        for (String project : projects) {
            script.append("        ").append(project).append(",\n");
        }
        script.append("    ),\n) {\n");
        script.append("    apply(plugin = \"com.vanniktech.maven.publish\")\n}\n");
        return script.toString();
    }

    private static String publishStep(String commands) {
        return "- name: Publish to Maven Central\n  run: >\n"
            + commands.indent(4);
    }

    private static String settingsIncludes(String... projects) {
        StringBuilder settings = new StringBuilder();
        for (String project : projects) {
            settings.append("include(\"").append(project).append("\")\n");
        }
        return settings.toString();
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
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
