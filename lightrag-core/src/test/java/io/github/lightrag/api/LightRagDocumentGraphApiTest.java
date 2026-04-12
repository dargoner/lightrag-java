package io.github.lightrag.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class LightRagDocumentGraphApiTest {
    @Test
    void exposesDocumentAndChunkGraphMaterializationApis() {
        var methods = Arrays.stream(LightRag.class.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());

        assertThat(methods).contains(
            "inspectDocumentGraph",
            "materializeDocumentGraph",
            "getDocumentChunkGraphStatus",
            "listDocumentChunkGraphStatuses",
            "resumeChunkGraph",
            "repairChunkGraph",
            "submitDocumentGraphMaterialization",
            "submitChunkGraphMaterialization"
        );
    }
}
