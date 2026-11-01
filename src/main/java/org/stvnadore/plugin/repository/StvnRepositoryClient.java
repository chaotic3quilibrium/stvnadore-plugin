package org.stvnadore.plugin.repository;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.plugin.repository.dto.CompileDiagnosticDto;
import org.stvnadore.plugin.repository.dto.SchemaMetadataDto;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-performance HTTP client service executing on Java 21 Virtual Threads.
 */
@NullMarked
public final class StvnRepositoryClient {

    private static final ExecutorService VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .executor(VIRTUAL_THREAD_EXECUTOR)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private StvnRepositoryClient() {}

    /**
     * Derives the canonical STVN 2.0.0 repository schema artifact name preserving the {@code .stvn_d}
     * or {@code .stvn_bd} suffix and mapping flattened {@code .stvn_di} modules to {@code .stvn_d}.
     *
     * @param fileName active source file name
     * @return canonical target schema name for repository publication
     */
    public static String deriveSchemaName(String fileName) {
        if (fileName.endsWith(".stvn_d")) {
            return fileName;
        }
        if (fileName.endsWith(".stvn_di")) {
            return fileName.substring(0, fileName.length() - ".stvn_di".length()) + ".stvn_d";
        }
        if (fileName.endsWith(".stvn_bd")) {
            return fileName;
        }
        return fileName + ".stvn_d";
    }

    /**
     * Publishes schema text asynchronously to the repository on a Virtual Thread.
     *
     * @param baseRepoUrl base URL of the remote schema repository server
     * @param schemaName nominal schema identifier
     * @param sourceText raw STVN schema source code
     * @param timeoutMs HTTP socket/connect timeout duration in milliseconds
     * @return CompletableFuture completing with the typed PublishResponse
     */
    public static CompletableFuture<PublishResponse> publishSchemaAsync(
            String baseRepoUrl,
            String schemaName,
            String sourceText,
            int timeoutMs
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String cleanBase = baseRepoUrl.endsWith("/") ? baseRepoUrl.substring(0, baseRepoUrl.length() - 1) : baseRepoUrl;
                String encodedName = URLEncoder.encode(schemaName, StandardCharsets.UTF_8);
                URI targetUri = URI.create(cleanBase + "/api/v1/schemas/" + encodedName);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(targetUri)
                        .timeout(Duration.ofMillis(Math.max(1, timeoutMs)))
                        .header("Content-Type", "application/stvn; charset=utf-8")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(sourceText, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int statusCode = response.statusCode();
                String body = response.body() != null ? response.body() : "";
                int headerWarnings = response.headers()
                        .firstValue("X-STVN-Warnings-Count")
                        .map(val -> {
                            try {
                                return Integer.parseInt(val.trim());
                            } catch (NumberFormatException ignored) {
                                return 0;
                            }
                        })
                        .orElse(0);

                return switch (statusCode) {
                    case 201 -> new PublishResponse.Success(parseMetadata(body, schemaName, headerWarnings));
                    case 200 -> new PublishResponse.Idempotent(parseMetadata(body, schemaName, headerWarnings));
                    case 409 -> new PublishResponse.Conflict(parseJsonField(body, "message", "Schema conflict: Name already registered with different hash."));
                    case 415 -> new PublishResponse.UnsupportedMediaType(parseJsonField(body, "message", "Invalid Content-Type header."));
                    case 422 -> new PublishResponse.ValidationError(parseDiagnostics(body));
                    default -> new PublishResponse.TransportError("Unexpected HTTP status: " + statusCode + " (" + body + ")", new RuntimeException());
                };
            } catch (Exception ex) {
                return new PublishResponse.TransportError("Network transport error: " + ex.getMessage(), ex);
            }
        }, VIRTUAL_THREAD_EXECUTOR);
    }

    private static SchemaMetadataDto parseMetadata(String json, String fallbackName, int defaultWarningsCount) {
        String name = parseJsonField(json, "schemaName", fallbackName);
        String domain = parseNullableJsonField(json, "domain");
        String shape = parseJsonField(json, "shapeSignature", "");
        String hash = parseJsonField(json, "casHash", "");
        int warningsCount = parseIntField(json, "warningsCount", defaultWarningsCount);
        return new SchemaMetadataDto(name, domain, shape, hash, warningsCount);
    }

    private static String parseJsonField(String json, String fieldName, String defaultValue) {
        String val = parseNullableJsonField(json, fieldName);
        return val != null ? val : defaultValue;
    }

    private static @Nullable String parseNullableJsonField(String json, String fieldName) {
        Pattern nullPattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*null\\b");
        if (nullPattern.matcher(json).find()) {
            return null;
        }
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        return null;
    }

    private static List<CompileDiagnosticDto> parseDiagnostics(String json) {
        List<CompileDiagnosticDto> result = new ArrayList<>();
        Pattern objectPattern = Pattern.compile("\\{[^{}]*\\}");
        Matcher objMatcher = objectPattern.matcher(json);
        while (objMatcher.find()) {
            String obj = objMatcher.group();
            String msg = parseJsonField(obj, "message", "Validation error");
            int line = parseIntField(obj, "line", 0);
            int column = parseIntField(obj, "column", 0);
            int startOffset = parseIntField(obj, "startOffset", 0);
            int endOffset = parseIntField(obj, "endOffset", 0);
            String canonicalCode = parseNullableJsonField(obj, "canonicalCode");
            String symbolicId = parseNullableJsonField(obj, "symbolicId");
            result.add(new CompileDiagnosticDto(msg, line, column, startOffset, endOffset, canonicalCode, symbolicId));
        }
        if (result.isEmpty() && !json.isBlank()) {
            result.add(new CompileDiagnosticDto(json, 0, 0));
        }
        return result;
    }

    private static int parseIntField(String json, String fieldName, int defaultValue) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(-?\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }
}
