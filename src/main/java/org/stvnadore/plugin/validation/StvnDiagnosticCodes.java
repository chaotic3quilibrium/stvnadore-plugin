package org.stvnadore.plugin.validation;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.validation.DiagnosticBag;

import java.util.regex.Pattern;

/**
 * Centralized registry of canonical dual-attributed diagnostic identifiers ({@code <CODE>-<SYMBOLIC_ID>})
 * originating from {@code stvn-plugin} inspections and editor hygiene validators.
 */
@NullMarked
public final class StvnDiagnosticCodes {

    /**
     * Normative pattern matching a complete prefixed STVN diagnostic identifier
     * ({@code E001}–{@code E040}, {@code W001}–{@code W003}, or {@code PW001}–{@code PW099}).
     */
    public static final Pattern PREFIXED_CODE_PATTERN =
        Pattern.compile("^(?:E0[0-9]{2}|W00[1-3]|PW0[0-9]{2})-[A-Z0-9_]+$");

    private StvnDiagnosticCodes() {}

    // Document & Stem Governance
    public static final String WARN_DOCUMENT_NAME_MISMATCH = "W001-WARN_DOCUMENT_NAME_MISMATCH";
    public static final String WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME = "W002-WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME";
    public static final String WARN_DOCUMENT_DOMAIN_OMITTED_IN_META = "W002-WARN_DOCUMENT_DOMAIN_OMITTED_IN_META";
    public static final String ERR_DOCUMENT_KIND_MISMATCH = "E031-ERR_DOCUMENT_KIND_MISMATCH";
    /** Error code emitted when a filename lacks a visible stem or violates stem cardinality ({@code 1 <= |stems| <= 2}, multi-stem {@code >= 3}) (SPEC § 3.10.2, E032). */
    public static final String ERR_INVALID_FILENAME_STEM = "E032-ERR_INVALID_FILENAME_STEM";

    // Syntax & Lexical Invariants
    public static final String ERR_TAB_CHARACTER_FORBIDDEN = "E001-ERR_TAB_CHARACTER_FORBIDDEN";
    public static final String ERR_PROHIBITED_FENCE_ARROW = "E026-ERR_PROHIBITED_FENCE_ARROW";
    public static final String ERR_FENCE_TAG_EMPTY = "E025-ERR_FENCE_TAG_EMPTY";
    public static final String ERR_FENCE_TAG_WHITESPACE = "E025-ERR_FENCE_TAG_WHITESPACE";
    public static final String ERR_FENCE_TAG_LENGTH_OVERFLOW = "E025-ERR_FENCE_TAG_LENGTH_OVERFLOW";
    public static final String ERR_FENCE_TAG_SYNTAX_INVALID = "E025-ERR_FENCE_TAG_SYNTAX_INVALID";
    public static final String ERR_FENCE_CLOSING_TAG_MISMATCH = "E025-ERR_FENCE_CLOSING_TAG_MISMATCH";
    public static final String ERR_FENCE_OPENING_TAG_MISMATCH = "E025-ERR_FENCE_OPENING_TAG_MISMATCH";
    public static final String ERR_FENCE_BLOCK_UNCLOSED = "E025-ERR_FENCE_BLOCK_UNCLOSED";
    public static final String ERR_TRAILING_SLASH_PROHIBITED = "E002-ERR_TRAILING_SLASH_PROHIBITED";
    public static final String ERR_NESTED_PACKAGE_PROHIBITED = "E002-ERR_NESTED_PACKAGE_PROHIBITED";
    public static final String ERR_MODULE_IMPORT = "E005-" + DiagnosticBag.ERR_MODULE_IMPORT;
    public static final String ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT = "E005-ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT";
    /** Error code emitted when a scoped {@code :use} in a hermetic document targets an external package (SPEC § 3.9, E035). */
    public static final String ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT = "E035-ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT";

    // Reserved Namespace & System Domain Invariants (§ 3.9 Invariant 8, § 3.10.1 Rule 6)
    public static final String ERR_RESERVED_PACKAGE_PROHIBITED = "E037-ERR_RESERVED_PACKAGE_PROHIBITED";
    public static final String ERR_RESERVED_DOMAIN_PROHIBITED = "E038-ERR_RESERVED_DOMAIN_PROHIBITED";

    // Schema, Type & LHS Invariants
    public static final String ERR_RESERVED_KEYWORD_ON_LHS = "E027-ERR_RESERVED_KEYWORD_ON_LHS";
    public static final String ERR_INCOMPATIBLE_NOMINAL_TYPE = "E027-" + DiagnosticBag.ERR_INCOMPATIBLE_NOMINAL_TYPE;
    public static final String ERR_TYPE_MISMATCH = "E028-" + DiagnosticBag.ERR_TYPE_MISMATCH;
    public static final String ERR_UNION_BRANCH_OVERFLOW = "E028-" + DiagnosticBag.ERR_UNION_BRANCH_OVERFLOW;
    public static final String ERR_UNRESOLVED_TYPE = "E030-ERR_UNRESOLVED_TYPE";
    public static final String ERR_CONSTANT_INTEGER_OVERFLOW = "E009-ERR_CONSTANT_INTEGER_OVERFLOW";
    /** Continuous {@code :Float} {@code #size} bit-width violation ({@code 32} or {@code 64} only). */
    public static final String ERR_FLOAT_INVALID_SIZE = "E010-" + DiagnosticBag.ERR_FLOAT_INVALID_SIZE;
    public static final String ERR_CONSTANT_FLOAT_OVERFLOW = "E010-ERR_CONSTANT_FLOAT_OVERFLOW";
    public static final String ERR_DISCRETE_BOUND_KIND_PROHIBITED = "E011-ERR_DISCRETE_BOUND_KIND_PROHIBITED";
    public static final String ERR_CONSTRAINT_VIOLATION = "E012-" + DiagnosticBag.ERR_CONSTRAINT_VIOLATION;
    public static final String ERR_EMPTY_INTERVAL_DOMAIN = "E009-ERR_EMPTY_INTERVAL_DOMAIN";
    public static final String ERR_INVERTED_RANGE = "E009-ERR_INVERTED_RANGE";
    public static final String ERR_STRING_CARDINALITY_PROHIBITED = "E008-ERR_STRING_CARDINALITY_PROHIBITED";
    public static final String WARN_NOMINAL_STRING_UNADORNED = "PW002-WARN_NOMINAL_STRING_UNADORNED";
    public static final String WARN_NOMINAL_COLLECTION_UNADORNED = "PW001-WARN_NOMINAL_COLLECTION_UNADORNED";
    public static final String WARN_NOMINAL_STRING_CAPACITY_OVERFLOW = "PW003-WARN_NOMINAL_STRING_CAPACITY_OVERFLOW";
    public static final String WARN_STRING_LITERAL_CAPACITY_OVERFLOW = "PW004-WARN_STRING_LITERAL_CAPACITY_OVERFLOW";
    public static final String WARN_EXCESSIVE_EXACT_PRECISION = "PW005-WARN_EXCESSIVE_EXACT_PRECISION";
    public static final String WARN_FLOAT_PRECISION_LOSS = "PW006-WARN_FLOAT_PRECISION_LOSS";

    // Metadata Facets & Ordering
    public static final String ERR_FACET_ORDER_VIOLATION = "E008-ERR_FACET_ORDER_VIOLATION";
    public static final String ERR_DUPLICATE_METADATA_FACET = "E008-ERR_DUPLICATE_METADATA_FACET";
    public static final String ERR_MUTUALLY_EXCLUSIVE = "E008-" + DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE;
    public static final String ERR_INCOMPLETE_TRAIT_FACET = "E008-ERR_INCOMPLETE_TRAIT_FACET";
    /** Error code emitted when an empty type or constant metadata constraint annotation block '{}' is encountered (SPEC § 3.5.1, E003). */
    public static final String ERR_EMPTY_METADATA_BLOCK = "E003-ERR_EMPTY_METADATA_BLOCK";
    /** Error code emitted when an empty root document metadata block ':meta {}' is encountered (SPEC § 3.10.1, E039). */
    public static final String ERR_EMPTY_META_BLOCK = "E039-ERR_EMPTY_META_BLOCK";
    public static final String ERR_METADATA_FACET_ON_CONSTANT = "E008-ERR_METADATA_FACET_ON_CONSTANT";
    public static final String ERR_EMPTY_DIRECTIVE_BLOCK = "E004-ERR_EMPTY_DIRECTIVE_BLOCK";
    public static final String ERR_EMPTY_DEFS_BLOCK = "E002-ERR_EMPTY_DEFS_BLOCK";
    public static final String ERR_DUPLICATE_ROOT_SECTION = "E002-ERR_DUPLICATE_ROOT_SECTION";
    public static final String ERR_SECTION_ORDER_VIOLATION = "E002-ERR_SECTION_ORDER_VIOLATION";
    public static final String ERR_MALFORMED_SECTION_STRUCTURE = "E002-ERR_MALFORMED_SECTION_STRUCTURE";
    public static final String ERR_STRIP_ON_TYPE_DEF = "E008-ERR_STRIP_ON_TYPE_DEF";
    public static final String ERR_INVALID_METADATA_FACET = "E008-ERR_INVALID_METADATA_FACET";
    public static final String ERR_UNRECOGNIZED_METADATA_FACET = "E036-ERR_UNRECOGNIZED_METADATA_FACET";
    public static final String WARN_REDUNDANT_METADATA_FACET = "W003-WARN_REDUNDANT_METADATA_FACET";
    public static final String WARN_REDUNDANT_FACET_OVERRIDE = "W003-WARN_REDUNDANT_FACET_OVERRIDE";

    // Temporal Mode & Scale
    public static final String ERR_MISSING_TEMPORAL_FACET = "E008-ERR_MISSING_TEMPORAL_FACET";
    public static final String ERR_TEMPORAL_SCALE_MUTUALLY_EXCLUSIVE = "E008-ERR_TEMPORAL_SCALE_MUTUALLY_EXCLUSIVE";
    public static final String ERR_TEMPORAL_SCALE_MISSING = "E008-ERR_TEMPORAL_SCALE_MISSING";
    public static final String ERR_DATETIME_MODE_MUTUALLY_EXCLUSIVE = "E008-ERR_DATETIME_MODE_MUTUALLY_EXCLUSIVE";
    public static final String ERR_DATETIME_MODE_MISSING = "E008-ERR_DATETIME_MODE_MISSING";
    public static final String WARN_SUSPICIOUS_EPOCH_MAGNITUDE = "PW006-WARN_SUSPICIOUS_EPOCH_MAGNITUDE";

    // Enum & Enum Subsets
    public static final String ERR_FILTER_ON_INLINE_ENUM = "E008-ERR_FILTER_ON_INLINE_ENUM";
    public static final String ERR_FILTER_ON_NON_ENUM = "E008-ERR_FILTER_ON_NON_ENUM";
    public static final String ERR_FILTER_MUTUALLY_EXCLUSIVE = "E008-ERR_FILTER_MUTUALLY_EXCLUSIVE";
    public static final String ERR_FILTER_EMPTY_LIST = "E016-ERR_FILTER_EMPTY_LIST";
    public static final String ERR_FILTER_DUPLICATE_VARIANT = "E008-ERR_FILTER_DUPLICATE_VARIANT";
    public static final String ERR_FILTER_MONOTONIC_NARROWING_VIOLATION = "E014-ERR_FILTER_MONOTONIC_NARROWING_VIOLATION";
    public static final String ERR_ENUM_SUBSET_NOT_SUBSET = "E014-" + DiagnosticBag.ERR_ENUM_SUBSET_NOT_SUBSET;
    public static final String ERR_ENUM_SUBSET_ORDER_VIOLATION = "E015-" + DiagnosticBag.ERR_ENUM_SUBSET_ORDER_VIOLATION;
    public static final String ENUM_SUBSET_ORDER_VIOLATION = ERR_ENUM_SUBSET_ORDER_VIOLATION;
    public static final String ERR_FILTER_ROOT_ORDER_VIOLATION = ERR_ENUM_SUBSET_ORDER_VIOLATION;
    public static final String ERR_ENUM_SUBSET_EMPTY = "E016-" + DiagnosticBag.ERR_ENUM_SUBSET_EMPTY;
    public static final String ERR_FILTER_COMPLETE_EXCLUSION_VIOLATION = "E016-ERR_FILTER_COMPLETE_EXCLUSION_VIOLATION";
    public static final String ERR_FILTER_ON_CONSTANT = "E008-ERR_FILTER_ON_CONSTANT";
    public static final String ERR_ENUM_SUBSET_PAYLOAD_MEMBERSHIP_VIOLATION = "E012-ERR_ENUM_SUBSET_PAYLOAD_MEMBERSHIP_VIOLATION";

    // Composite & Map Structures
    public static final String ERR_FLAT_LIST_IN_MAP_SLOT = "E028-ERR_FLAT_LIST_IN_MAP_SLOT";
    public static final String ERR_DUPLICATE_MAP_KEY = "E017-ERR_DUPLICATE_MAP_KEY";
    public static final String ERR_INVERTIBLE_MAP_DUPLICATE_VALUE = "E018-ERR_INVERTIBLE_MAP_DUPLICATE_VALUE";
    public static final String WARN_DEGENERATE_TUPLE = "PW006-WARN_DEGENERATE_TUPLE";
    public static final String WARN_DEGENERATE_ENUM = "PW006-WARN_DEGENERATE_ENUM";
    public static final String WARN_DEGENERATE_UNION = "PW006-WARN_DEGENERATE_UNION";
    public static final String WARN_DEGRADED_SCHEMA_USAGE = "PW006-WARN_DEGRADED_SCHEMA_USAGE";

    // Literals & Style
    public static final String ERR_INVALID_BOOLEAN_CASING = "E028-ERR_INVALID_BOOLEAN_CASING";
    public static final String ERR_INVALID_BOOLEAN_LITERAL = "E028-ERR_INVALID_BOOLEAN_LITERAL";
    public static final String WARN_REDUNDANT_VARIANT_TAG = "PW006-WARN_REDUNDANT_VARIANT_TAG";
    public static final String WARN_VARIANT_FORM_DISCREPANCY = "PW006-WARN_VARIANT_FORM_DISCREPANCY";
    public static final String WARN_EXCESSIVE_EXACT_FLOAT_PRECISION = WARN_EXCESSIVE_EXACT_PRECISION;

    /**
     * Strips the leading canonical code prefix ({@code E0XX-}, {@code W00X-}, or {@code PW0XX-})
     * when present, returning the bare symbolic diagnostic identifier.
     *
     * @param diagnosticCode prefixed or bare diagnostic code
     * @return bare symbolic diagnostic identifier
     */
    public static String stripCanonicalPrefix(String diagnosticCode) {
        var clean = diagnosticCode.trim();
        if (PREFIXED_CODE_PATTERN.matcher(clean).matches()) {
            int dash = clean.indexOf('-');
            if (dash > 0 && dash + 1 < clean.length()) {
                return clean.substring(dash + 1);
            }
        }
        return clean;
    }

    /**
     * Checks whether {@code candidate} equals {@code prefixedConstant} or its bare symbolic suffix.
     *
     * @param candidate        candidate diagnostic identifier (prefixed or bare), or {@code null}
     * @param prefixedConstant canonical {@code <CODE>-<SYMBOLIC_ID>} constant from {@link StvnDiagnosticCodes}
     * @return {@code true} when {@code candidate} matches {@code prefixedConstant}
     */
    public static boolean matchesSymbolicOrPrefixed(@Nullable String candidate, String prefixedConstant) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String trimmed = candidate.trim();
        return prefixedConstant.equals(trimmed)
            || stripCanonicalPrefix(prefixedConstant).equals(trimmed);
    }

    /**
     * Resolves the normative {@code STVN_LANGUAGE_SPEC.md} canonical code prefix ({@code E001}–{@code E040},
     * {@code W001}–{@code W003}) or IDE-only advisory warning prefix ({@code PW001}–{@code PW006})
     * for a plugin diagnostic identifier.
     *
     * @param pluginDiagnosticId the plugin diagnostic symbolic or prefixed identifier
     * @return the canonical code prefix
     */
    public static String resolveCanonicalPrefix(@Nullable String pluginDiagnosticId) {
        if (pluginDiagnosticId == null || pluginDiagnosticId.isBlank()) {
            return "E002";
        }
        var clean = pluginDiagnosticId.trim();
        if (PREFIXED_CODE_PATTERN.matcher(clean).matches()) {
            return clean.substring(0, clean.indexOf('-'));
        }
        var bareId = stripCanonicalPrefix(clean);
        var fromCore = DiagnosticBag.resolveCanonicalCode(bareId);
        if (fromCore.isPresent()) {
            return fromCore.get();
        }
        return switch (bareId) {
            case "ERR_METADATA_FACET_ON_CONSTANT",
                 "ERR_STRIP_ON_TYPE_DEF",
                 "ERR_TEMPORAL_SCALE_MUTUALLY_EXCLUSIVE",
                 "ERR_DATETIME_MODE_MUTUALLY_EXCLUSIVE",
                 "ERR_DATETIME_MODE_MISSING",
                 "ERR_FILTER_ON_INLINE_ENUM",
                 "ERR_FILTER_ON_NON_ENUM",
                 "ERR_FILTER_MUTUALLY_EXCLUSIVE",
                 "ERR_FILTER_ON_CONSTANT",
                 "ERR_MUTUALLY_EXCLUSIVE" -> "E008";
            case "ERR_CONSTANT_INTEGER_OVERFLOW" -> "E009";
            case "ERR_CONSTANT_FLOAT_OVERFLOW" -> "E010";
            case "ERR_ENUM_SUBSET_PAYLOAD_MEMBERSHIP_VIOLATION" -> "E012";
            case "ERR_FILTER_MONOTONIC_NARROWING_VIOLATION" -> "E014";
            case "ERR_FILTER_EMPTY_LIST",
                 "ERR_FILTER_COMPLETE_EXCLUSION_VIOLATION" -> "E016";
            case "ERR_DUPLICATE_MAP_KEY" -> "E017";
            case "ERR_INVERTIBLE_MAP_DUPLICATE_VALUE" -> "E018";
            case "ERR_FENCE_TAG_EMPTY",
                 "ERR_FENCE_TAG_WHITESPACE",
                 "ERR_FENCE_TAG_LENGTH_OVERFLOW",
                 "ERR_FENCE_TAG_SYNTAX_INVALID",
                 "ERR_FENCE_CLOSING_TAG_MISMATCH",
                 "ERR_FENCE_OPENING_TAG_MISMATCH",
                 "ERR_FENCE_BLOCK_UNCLOSED" -> "E025";
            case "ERR_FLAT_LIST_IN_MAP_SLOT",
                 "ERR_INVALID_BOOLEAN_CASING",
                 "ERR_INVALID_BOOLEAN_LITERAL" -> "E028";
            case "ERR_UNRESOLVED_TYPE" -> "E030";
            case "WARN_REDUNDANT_FACET_OVERRIDE" -> "W003";
            case "WARN_NOMINAL_COLLECTION_UNADORNED" -> "PW001";
            case "WARN_NOMINAL_STRING_UNADORNED" -> "PW002";
            case "WARN_NOMINAL_STRING_CAPACITY_OVERFLOW" -> "PW003";
            case "WARN_STRING_LITERAL_CAPACITY_OVERFLOW" -> "PW004";
            case "WARN_EXCESSIVE_EXACT_PRECISION" -> "PW005";
            case "WARN_FLOAT_PRECISION_LOSS",
                 "WARN_SUSPICIOUS_EPOCH_MAGNITUDE",
                 "WARN_DEGENERATE_TUPLE",
                 "WARN_DEGENERATE_ENUM",
                 "WARN_DEGENERATE_UNION",
                 "WARN_DEGRADED_SCHEMA_USAGE",
                 "WARN_REDUNDANT_VARIANT_TAG",
                 "WARN_VARIANT_FORM_DISCREPANCY" -> "PW006";
            default -> bareId.startsWith("WARN_") ? "PW006" : "E002";
        };
    }

    /**
     * Resolves the canonical {@code STVN_LANGUAGE_SPEC.md} code prefix ({@code E001}–{@code E040},
     * {@code W001}–{@code W003}) for a core compiler diagnostic symbolic identifier.
     *
     * @param coreDiagnosticId the core diagnostic symbolic identifier
     * @return the canonical code prefix
     */
    public static String resolveCoreCanonicalPrefix(@Nullable String coreDiagnosticId) {
        if (coreDiagnosticId == null || coreDiagnosticId.isBlank()) {
            return "E002";
        }
        var bareId = stripCanonicalPrefix(coreDiagnosticId.trim());
        var fromCore = DiagnosticBag.resolveCanonicalCode(bareId);
        if (fromCore.isPresent()) {
            return fromCore.get();
        }
        return switch (bareId) {
            case "ERR_COMPILATION_ERROR", "STVN_SYNTAX_ERROR" -> "E002";
            case "ERR_MODULE_IMPORT" -> "E005";
            default -> resolveCanonicalPrefix(bareId);
        };
    }
}
