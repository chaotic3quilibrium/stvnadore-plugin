package org.stvnadore.plugin.repository.dto;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Wire DTO representing a structured STVN 2.0.0 compilation diagnostic returned by {@code stvnadore-repository} on HTTP 422.
 *
 * @param message       compiler diagnostic description
 * @param line          1-based source line index
 * @param column        0-based character column offset
 * @param startOffset   0-based inclusive character start offset
 * @param endOffset     0-based exclusive character end offset
 * @param canonicalCode optional canonical specification code ({@code E001}–{@code E040}, {@code W001}–{@code W003})
 * @param symbolicId    optional symbolic diagnostic identifier ({@code ERR_*} or {@code WARN_*})
 */
@NullMarked
public record CompileDiagnosticDto(
    String message,
    int line,
    int column,
    int startOffset,
    int endOffset,
    @Nullable String canonicalCode,
    @Nullable String symbolicId
) {
    /**
     * Convenience constructor defaulting offset spans to zero and diagnostic identifiers to {@code null}.
     *
     * @param message compiler diagnostic description
     * @param line    1-based source line index
     * @param column  0-based character column offset
     */
    public CompileDiagnosticDto(String message, int line, int column) {
        this(message, line, column, 0, 0, null, null);
    }
}
