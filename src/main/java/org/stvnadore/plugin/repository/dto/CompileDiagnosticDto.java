package org.stvnadore.plugin.repository.dto;

import org.jspecify.annotations.NullMarked;

/**
 * Diagnostic DTO returned on remote compilation/validation failure (422).
 *
 * @param message compiler error description
 * @param line 1-based source line index
 * @param column 0-based character column offset
 */
@NullMarked
public record CompileDiagnosticDto(String message, int line, int column) {}