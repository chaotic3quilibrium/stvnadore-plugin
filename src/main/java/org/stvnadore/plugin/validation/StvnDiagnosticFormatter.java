package org.stvnadore.plugin.validation;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnDiagnostic;

import java.util.regex.Pattern;

/**
 * Standardizes diagnostic attribution formatting across {@code stvn-core} and {@code stvn-plugin}.
 * <p>
 * Enforces the canonical support-ready nominal format:
 * {@code <Human-readable description> [<enclave>:<CANONICAL_CODE>-<SYMBOLIC_ID>]}
 */
@NullMarked
public final class StvnDiagnosticFormatter {

    public static final String ENCLAVE_CORE = "stvn-core";
    public static final String ENCLAVE_PLUGIN = "stvn-plugin";

    private static final Pattern ALREADY_PREFIXED_PATTERN = Pattern.compile("^[A-Z]{1,2}[0-9]{3}-.+");

    private StvnDiagnosticFormatter() {}

    /**
     * Formats an authoritative compiler {@link StvnDiagnostic} bridged from {@code stvnadore-core}.
     *
     * @param diagnostic the compiler diagnostic instance
     * @return standardized bracketed diagnostic string
     */
    public static String formatCore(StvnDiagnostic diagnostic) {
        var symbolicId = diagnostic.symbolicId().or(diagnostic::errorCode).orElse("ERR_COMPILATION_ERROR");
        var canonicalCode = diagnostic.canonicalCode().orElse(null);
        return formatCore(diagnostic.message(), canonicalCode, symbolicId);
    }

    /**
     * Formats an authoritative compiler diagnostic bridged from {@code stvnadore-core}
     * with an explicit canonical code and symbolic identifier.
     *
     * @param message human-readable description
     * @param canonicalCode optional canonical specification code (e.g. {@code E012}, {@code E017})
     * @param symbolicId core diagnostic symbolic identifier
     * @return standardized bracketed diagnostic string
     */
    public static String formatCore(@Nullable String message, @Nullable String canonicalCode, @Nullable String symbolicId) {
        var resolvedId = (symbolicId == null || symbolicId.isBlank())
            ? "ERR_COMPILATION_ERROR"
            : symbolicId.trim();
        if (ALREADY_PREFIXED_PATTERN.matcher(resolvedId).matches()) {
            return format(ENCLAVE_CORE, message, resolvedId);
        }
        var resolvedCanonical = (canonicalCode != null && !canonicalCode.isBlank())
            ? canonicalCode.trim()
            : StvnDiagnosticCodes.resolveCoreCanonicalPrefix(resolvedId);
        if (resolvedCanonical.equals(resolvedId)) {
            return format(ENCLAVE_CORE, message, resolvedId);
        }
        return format(ENCLAVE_CORE, message, resolvedCanonical + "-" + resolvedId);
    }

    /**
     * Formats an authoritative compiler diagnostic bridged from {@code stvnadore-core}.
     *
     * @param message human-readable description
     * @param diagnosticId core diagnostic error/warning code
     * @return standardized bracketed diagnostic string
     */
    public static String formatCore(@Nullable String message, @Nullable String diagnosticId) {
        return formatCore(message, null, diagnosticId);
    }

    /**
     * Formats an editor/IDE hygiene inspection diagnostic originating from {@code stvnadore-plugin}.
     *
     * @param message human-readable description
     * @param diagnosticId plugin diagnostic inspection code
     * @return standardized bracketed diagnostic string
     */
    public static String formatPlugin(@Nullable String message, String diagnosticId) {
        var cleanId = diagnosticId.trim();
        if (ALREADY_PREFIXED_PATTERN.matcher(cleanId).matches()) {
            return format(ENCLAVE_PLUGIN, message, cleanId);
        }
        var prefix = StvnDiagnosticCodes.resolveCanonicalPrefix(cleanId);
        return format(ENCLAVE_PLUGIN, message, prefix + "-" + cleanId);
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
