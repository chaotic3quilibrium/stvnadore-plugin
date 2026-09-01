package org.stvnadore.plugin.repository;

import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.repository.dto.CompileDiagnosticDto;
import org.stvnadore.plugin.repository.dto.SchemaMetadataDto;

import java.util.List;

/**
 * Sealed hierarchy modeling all possible typed outcomes of publishing a schema.
 */
@NullMarked
public sealed interface PublishResponse {

    /**
     * HTTP 201 Created: New schema successfully registered and indexed in CAS.
     *
     * @param metadata the published schema metadata
     */
    record Success(SchemaMetadataDto metadata) implements PublishResponse {}

    /**
     * HTTP 200 OK: Schema already registered with identical content/hash (Idempotent).
     *
     * @param metadata the existing schema metadata
     */
    record Idempotent(SchemaMetadataDto metadata) implements PublishResponse {}

    /**
     * HTTP 409 Conflict: Schema name exists with a different hash (Mutations prohibited).
     *
     * @param message descriptive conflict message
     */
    record Conflict(String message) implements PublishResponse {}

    /**
     * HTTP 415 Unsupported Media Type: Request Content-Type was not application/stvn.
     *
     * @param message error description
     */
    record UnsupportedMediaType(String message) implements PublishResponse {}

    /**
     * HTTP 422 Unprocessable Entity: Remote AST or validation errors detected.
     *
     * @param diagnostics compiler diagnostics describing the errors
     */
    record ValidationError(List<CompileDiagnosticDto> diagnostics) implements PublishResponse {}

    /**
     * Network, socket timeout, or HTTP transport failure.
     *
     * @param message error description
     * @param cause underlying cause
     */
    record TransportError(String message, Throwable cause) implements PublishResponse {}
}