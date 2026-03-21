package io.github.lightragjava.demo;

import io.github.lightragjava.types.Document;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
final class UploadedDocumentMapper {
    private static final int ID_PREFIX_MAX_LENGTH = 48;
    private static final int MAX_FILES = 20;
    private static final long MAX_FILE_BYTES = 1_048_576L;
    private static final long MAX_TOTAL_BYTES = 4_194_304L;
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".txt", ".md", ".markdown");

    List<Document> toDocuments(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("files must not be empty");
        }
        if (files.size() > MAX_FILES) {
            throw new IllegalArgumentException("too many files in a single upload");
        }
        var totalBytes = files.stream()
            .filter(file -> file != null)
            .mapToLong(MultipartFile::getSize)
            .sum();
        if (totalBytes > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("total upload too large");
        }
        var documents = files.stream()
            .map(this::toDocument)
            .toList();
        var duplicateId = documents.stream()
            .collect(java.util.stream.Collectors.groupingBy(Document::id, java.util.stream.Collectors.counting()))
            .entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .map(entry -> entry.getKey())
            .findFirst();
        if (duplicateId.isPresent()) {
            throw new IllegalArgumentException("duplicate uploaded document id: " + duplicateId.get());
        }
        return documents;
    }

    private Document toDocument(MultipartFile file) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }

        var filename = normalizeFilename(file.getOriginalFilename());
        validateSupportedExtension(filename);

        var bytes = readBytes(file, filename);
        var content = decodeUtf8(bytes, filename).strip();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("file content must not be blank: " + filename);
        }

        return new Document(
            buildDocumentId(filename, bytes),
            filename,
            content,
            Map.of(
                "source", "upload",
                "filename", filename,
                "contentType", normalizeContentType(file.getContentType())
            )
        );
    }

    private String normalizeFilename(String originalFilename) {
        if (originalFilename == null) {
            throw new IllegalArgumentException("file name must not be blank");
        }
        var normalized = originalFilename.replace('\\', '/').strip();
        var basename = normalized.substring(normalized.lastIndexOf('/') + 1).strip();
        if (basename.isEmpty()) {
            throw new IllegalArgumentException("file name must not be blank");
        }
        return basename;
    }

    private void validateSupportedExtension(String filename) {
        var lowerCaseName = filename.toLowerCase(Locale.ROOT);
        if (SUPPORTED_EXTENSIONS.stream().noneMatch(lowerCaseName::endsWith)) {
            throw new IllegalArgumentException("unsupported file type: " + filename);
        }
    }

    private byte[] readBytes(MultipartFile file, String filename) {
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("file too large: " + filename);
        }
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to read uploaded file: " + filename, exception);
        }
    }

    private String buildDocumentId(String filename, byte[] bytes) {
        var stem = filename;
        var extensionIndex = filename.lastIndexOf('.');
        if (extensionIndex > 0) {
            stem = filename.substring(0, extensionIndex);
        }

        var slug = stem.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            slug = "document";
        }
        if (slug.length() > ID_PREFIX_MAX_LENGTH) {
            slug = slug.substring(0, ID_PREFIX_MAX_LENGTH).replaceAll("-+$", "");
        }

        return slug + "-" + shortHash(filename, bytes);
    }

    private String shortHash(String filename, byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(filename.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(bytes);
            return toHex(digest.digest()).substring(0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    private String toHex(byte[] bytes) {
        var builder = new StringBuilder(bytes.length * 2);
        for (var value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    private String normalizeContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
    }

    private String decodeUtf8(byte[] bytes, String filename) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("file content must be valid UTF-8: " + filename, exception);
        }
    }
}
