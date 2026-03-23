package io.github.lightragjava.indexing;

import io.github.lightragjava.types.RawDocumentSource;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MineruApiClientTest {
    @Test
    void submitsUrlTaskPollsZipAndParsesStructuredBlocks() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(jsonResponse("""
                {
                  "code": 0,
                  "msg": "ok",
                  "data": {
                    "task_id": "task-123"
                  }
                }
                """));
            server.enqueue(jsonResponse("""
                {
                  "code": 0,
                  "msg": "ok",
                  "data": {
                    "task_id": "task-123",
                    "state": "running",
                    "err_msg": ""
                  }
                }
                """));
            server.enqueue(jsonResponse("""
                {
                  "code": 0,
                  "msg": "ok",
                  "data": {
                    "task_id": "task-123",
                    "state": "done",
                    "err_msg": "",
                    "full_zip_url": "%sresult.zip"
                  }
                }
                """.formatted(server.url("/").toString())));
            server.enqueue(zipResponse(zipBytes(
                Map.of(
                    "content_list_v2.json",
                    """
                    [
                      [
                        {
                          "type": "title",
                          "content": {
                            "title_content": [
                              {
                                "type": "text",
                                "content": "Chapter 1"
                              }
                            ],
                            "level": 1
                          },
                          "bbox": [1, 2, 3, 4]
                        },
                        {
                          "type": "paragraph",
                          "content": {
                            "paragraph_content": [
                              {
                                "type": "text",
                                "content": "Body paragraph"
                              }
                            ]
                          },
                          "bbox": [5, 6, 7, 8]
                        }
                      ]
                    ]
                    """,
                    "full.md",
                    "# Chapter 1\n\nBody paragraph"
                )
            )));

            var client = new MineruApiClient(new MineruApiClient.HttpTransport(
                server.url("/api/v4/").toString(),
                "secret",
                Duration.ofSeconds(5),
                1,
                5
            ));
            var result = client.parse(sourceWithUrl("https://files.example.com/guide.pdf"));

            assertThat(result.markdown()).isEqualTo("# Chapter 1\n\nBody paragraph");
            assertThat(result.blocks()).hasSize(2);
            assertThat(result.blocks().get(0).blockType()).isEqualTo("title");
            assertThat(result.blocks().get(0).text()).isEqualTo("Chapter 1");
            assertThat(result.blocks().get(0).sectionPath()).isEqualTo("Chapter 1");
            assertThat(result.blocks().get(1).blockType()).isEqualTo("paragraph");
            assertThat(result.blocks().get(1).text()).isEqualTo("Body paragraph");
            assertThat(result.blocks().get(1).sectionPath()).isEqualTo("Chapter 1");

            var createRequest = server.takeRequest();
            assertThat(createRequest.getMethod()).isEqualTo("POST");
            assertThat(createRequest.getPath()).isEqualTo("/api/v4/extract/task");
            assertThat(createRequest.getHeader("Authorization")).isEqualTo("Bearer secret");
            assertThat(createRequest.getBody().readUtf8())
                .contains("\"url\":\"https://files.example.com/guide.pdf\"")
                .contains("\"model_version\":\"vlm\"");

            var statusRequest1 = server.takeRequest();
            assertThat(statusRequest1.getMethod()).isEqualTo("GET");
            assertThat(statusRequest1.getPath()).isEqualTo("/api/v4/extract/task/task-123");

            var statusRequest2 = server.takeRequest();
            assertThat(statusRequest2.getMethod()).isEqualTo("GET");
            assertThat(statusRequest2.getPath()).isEqualTo("/api/v4/extract/task/task-123");

            var zipRequest = server.takeRequest();
            assertThat(zipRequest.getMethod()).isEqualTo("GET");
            assertThat(zipRequest.getPath()).isEqualTo("/result.zip");
        }
    }

    @Test
    void rejectsSourcesWithoutPublicUrlMetadata() {
        var client = new MineruApiClient(new MineruApiClient.HttpTransport(
            "https://mineru.net/api/v4/",
            "secret",
            Duration.ofSeconds(5),
            1,
            2
        ));

        assertThatThrownBy(() -> client.parse(RawDocumentSource.bytes(
            "guide.pdf",
            "%PDF".getBytes(StandardCharsets.UTF_8),
            "application/pdf",
            Map.of()
        )))
            .isInstanceOf(MineruUnavailableException.class)
            .hasMessageContaining("public source URL");
    }

    @Test
    void fallsBackToMarkdownWhenZipHasNoStructuredContent() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(jsonResponse("""
                {
                  "code": 0,
                  "msg": "ok",
                  "data": {
                    "task_id": "task-md"
                  }
                }
                """));
            server.enqueue(jsonResponse("""
                {
                  "code": 0,
                  "msg": "ok",
                  "data": {
                    "task_id": "task-md",
                    "state": "done",
                    "err_msg": "",
                    "full_zip_url": "%sresult.zip"
                  }
                }
                """.formatted(server.url("/").toString())));
            server.enqueue(zipResponse(zipBytes(Map.of(
                "full.md",
                "# Title\n\nMarkdown fallback"
            ))));

            var client = new MineruApiClient(new MineruApiClient.HttpTransport(
                server.url("/api/v4/extract/task").toString(),
                "secret",
                Duration.ofSeconds(5),
                1,
                2
            ));

            var result = client.parse(sourceWithUrl("https://files.example.com/guide.pdf"));

            assertThat(result.blocks()).isEmpty();
            assertThat(result.markdown()).isEqualTo("# Title\n\nMarkdown fallback");
        }
    }

    private static RawDocumentSource sourceWithUrl(String url) {
        return RawDocumentSource.bytes(
            "guide.pdf",
            "%PDF".getBytes(StandardCharsets.UTF_8),
            "application/pdf",
            Map.of(MineruApiClient.SOURCE_URL_METADATA_KEY, url)
        );
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse()
            .addHeader("Content-Type", "application/json")
            .setBody(body);
    }

    private static MockResponse zipResponse(byte[] bytes) {
        return new MockResponse()
            .addHeader("Content-Type", "application/zip")
            .setBody(new Buffer().write(bytes));
    }

    private static byte[] zipBytes(Map<String, String> entries) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
