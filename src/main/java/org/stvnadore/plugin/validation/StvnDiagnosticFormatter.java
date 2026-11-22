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

    /**
     * Normative suffix pattern matching a complete {@code [stvn-(core|plugin):<CODE>-<SYMBOLIC_ID>]} attribution tag.
     */
    public static final Pattern COMPLETE_ATTRIBUTION_SUFFIX_PATTERN =
        Pattern.compile("\\[stvn-(?:core|plugin):(?:E0[0-9]{2}|W00[1-3]|PW0[0-9]{2})-[A-Z0-9_]+\\]$");

    private static final Pattern ALREADY_PREFIXED_PATTERN =
        Pattern.compile("^(?:E0[0-9]{2}|W00[1-3]|PW0[0-9]{2})-[A-Z0-9_]+$");

    private static final Pattern TRAILING_PARTIAL_SUFFIX_PATTERN =
        Pattern.compile("\\s*\\[stvn-(?:core|plugin):[^\\]]*\\]$");

    private StvnDiagnosticFormatter() {}

    /**
     * Formats an authoritative compiler {@link StvnDiagnostic} bridged from {@code stvnadore-core}.
     *
     * @param diagnostic the compiler diagnostic instance
     * @return standardized bracketed diagnostic string
     */
    public static String formatCore(StvnDiagnostic diagnostic) {
        var symbolicId = diagnostic.symbolicId().or(diagnostic::errorCode).orElse("ERR_COMPILATION_ERROR");
        var canonicalCode = diagnostic.code().or(diagnostic::canonicalCode).orElse(null);
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
            var defaultSym = org.stvnadore.core.validation.DiagnosticBag.resolveDefaultSymbolicId(resolvedCanonical)
                .orElse("ERR_COMPILATION_ERROR");
            return format(ENCLAVE_CORE, message, resolvedCanonical + "-" + defaultSym);
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
     * @param diagnosticId plugin diagnostic inspection code (prefixed or bare symbolic)
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
     * Strips any incomplete or malformed {@code [stvn-...]} suffix and trailing periods before
     * appending the canonical nominal tag.
     *
     * @param enclave origin enclave ({@code stvn-core} or {@code stvn-plugin})
     * @param message human-readable description
     * @param diagnosticId canonical prefixed diagnostic code ({@code <CODE>-<SYMBOLIC_ID>})
     * @return standardized bracketed diagnostic string
     */
    public static String format(String enclave, @Nullable String message, String diagnosticId) {
        if (message == null || message.isBlank()) {
            return "[" + enclave + ':' + diagnosticId + "]";
        }
        var clean = message.stripTrailing();
        if (COMPLETE_ATTRIBUTION_SUFFIX_PATTERN.matcher(clean).find()) {
            return clean;
        }
        if (TRAILING_PARTIAL_SUFFIX_PATTERN.matcher(clean).find()) {
            clean = TRAILING_PARTIAL_SUFFIX_PATTERN.matcher(clean).replaceFirst("").stripTrailing();
        }
        if (clean.endsWith(".")) {
            clean = clean.substring(0, clean.length() - 1).stripTrailing();
        }
        if (clean.isEmpty()) {
            return "[" + enclave + ':' + diagnosticId + "]";
        }
        return clean + " [" + enclave + ':' + diagnosticId + "]";
    }
}
