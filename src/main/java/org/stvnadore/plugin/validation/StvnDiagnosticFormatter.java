package org.stvnadore.plugin.validation;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Standardizes diagnostic attribution formatting across {@code stvn-core} and {@code stvn-plugin}.
 * <p>
 * Enforces the canonical nominal format:
 * {@code <Human-readable description> [<enclave>:<DIAGNOSTIC_ID>]}
 */
@NullMarked
public final class StvnDiagnosticFormatter {

    public static final String ENCLAVE_CORE = "stvn-core";
    public static final String ENCLAVE_PLUGIN = "stvn-plugin";

    private StvnDiagnosticFormatter() {}

    /**
     * Formats an authoritative compiler diagnostic bridged from {@code stvnadore-core}.
     *
     * @param message human-readable description
     * @param diagnosticId core diagnostic error/warning code
     * @return standardized bracketed diagnostic string
     */
    public static String formatCore(@Nullable String message, String diagnosticId) {
        return format(ENCLAVE_CORE, message, diagnosticId);
    }

    /**
     * Formats an editor/IDE hygiene inspection diagnostic originating from {@code stvnadore-plugin}.
     *
     * @param message human-readable description
     * @param diagnosticId plugin diagnostic inspection code
     * @return standardized bracketed diagnostic string
     */
    public static String formatPlugin(@Nullable String message, String diagnosticId) {
        return format(ENCLAVE_PLUGIN, message, diagnosticId);
    }

    /**
     * Formats a diagnostic string with the specified enclave prefix and diagnostic identifier.
     * Removes trailing periods before appending the nominal tag to maintain clean punctuation.
     *
     * @param enclave origin enclave (e.g., "stvn-core" or "stvn-plugin")
     * @param message human-readable description
     * @param diagnosticId diagnostic code
     * @return standardized bracketed diagnostic string
     */
    public static String format(String enclave, @Nullable String message, String diagnosticId) {
        if (message == null || message.isBlank()) {
            return "[" + enclave + ":" + diagnosticId + "]";
        }
        var clean = message.stripTrailing();
        // Idempotent guard: avoid double formatting if string already contains nominal coordinates
        if (clean.endsWith("]") && (clean.contains("[stvn-core:") || clean.contains("[stvn-plugin:"))) {
            return clean;
        }
        // Remove trailing period if present so nominal suffix is clean
        if (clean.endsWith(".")) {
            clean = clean.substring(0, clean.length() - 1).stripTrailing();
        }
        return clean + " [" + enclave + ":" + diagnosticId + "]";
    }
}
