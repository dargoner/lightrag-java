package io.github.lightrag.storage.arcadedb;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ArcadeDbClientTest {
    @Test
    void sendsSqlCommandToArcadeEndpoint() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {"result":[{"id":"doc-1","title":"Title"}]}
                    """));
            server.start();

            var client = new ArcadeDbClient(new ArcadeDbConfig(
                URI.create(server.url("/").toString()),
                "rag-db",
                "root",
                "secret",
                3,
                Duration.ofSeconds(5),
                false
            ));

            var rows = client.query("SELECT id, title FROM Document WHERE id = ?", "doc-1");

            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row.get("id")).isEqualTo("doc-1");
                assertThat(row.get("title")).isEqualTo("Title");
            });
            var request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/api/v1/command/rag-db");
            assertThat(request.getHeader("Authorization")).startsWith("Basic ");
            assertThat(request.getBody().readUtf8())
                .contains("\"language\":\"sql\"")
                .contains("\"command\":\"SELECT id, title FROM Document WHERE id = :p0\"")
                .contains("\"params\":{\"p0\":\"doc-1\"}");
        }
    }
}
