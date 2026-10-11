package org.stvnadore.plugin.validation;

import org.jspecify.annotations.NullMarked;

/**
 * Centralized registry of diagnostic nominal identifiers originating from {@code stvn-plugin}
 * inspections and editor hygiene validators.
 */
@NullMarked
public final class StvnDiagnosticCodes {
    private StvnDiagnosticCodes() {}

    // Document & Stem Governance
    public static final String WARN_DOCUMENT_NAME_MISMATCH = "WARN_DOCUMENT_NAME_MISMATCH";
    public static final String WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME = "WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME";
    public static final String ERR_DOCUMENT_KIND_MISMATCH = "ERR_DOCUMENT_KIND_MISMATCH";

    // Syntax & Lexical Invariants
    public static final String ERR_TAB_CHARACTER_FORBIDDEN = "ERR_TAB_CHARACTER_FORBIDDEN";
    public static final String ERR_PROHIBITED_FENCE_ARROW = "ERR_PROHIBITED_FENCE_ARROW";
    public static final String ERR_FENCE_TAG_EMPTY = "ERR_FENCE_TAG_EMPTY";
    public static final String ERR_FENCE_TAG_WHITESPACE = "ERR_FENCE_TAG_WHITESPACE";
    public static final String ERR_FENCE_TAG_LENGTH_OVERFLOW = "ERR_FENCE_TAG_LENGTH_OVERFLOW";
    public static final String ERR_FENCE_TAG_SYNTAX_INVALID = "ERR_FENCE_TAG_SYNTAX_INVALID";
    public static final String ERR_FENCE_CLOSING_TAG_MISMATCH = "ERR_FENCE_CLOSING_TAG_MISMATCH";
    public static final String ERR_FENCE_OPENING_TAG_MISMATCH = "ERR_FENCE_OPENING_TAG_MISMATCH";
    public static final String ERR_FENCE_BLOCK_UNCLOSED = "ERR_FENCE_BLOCK_UNCLOSED";
    public static final String ERR_TRAILING_SLASH_PROHIBITED = "ERR_TRAILING_SLASH_PROHIBITED";
    public static final String ERR_NESTED_PACKAGE_PROHIBITED = "ERR_NESTED_PACKAGE_PROHIBITED";
    public static final String ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT = "ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT";

    // Schema, Type & LHS Invariants
    public static final String ERR_RESERVED_KEYWORD_ON_LHS = "ERR_RESERVED_KEYWORD_ON_LHS";
    public static final String ERR_UNRESOLVED_TYPE = "ERR_UNRESOLVED_TYPE";
    public static final String ERR_CONSTANT_INTEGER_OVERFLOW = "ERR_CONSTANT_INTEGER_OVERFLOW";
    public static final String ERR_DISCRETE_BOUND_KIND_PROHIBITED = "ERR_DISCRETE_BOUND_KIND_PROHIBITED";
    public static final String ERR_STRING_CARDINALITY_PROHIBITED = "ERR_STRING_CARDINALITY_PROHIBITED";
    public static final String WARN_NOMINAL_STRING_UNADORNED = "WARN_NOMINAL_STRING_UNADORNED";
    public static final String WARN_NOMINAL_STRING_CAPACITY_OVERFLOW = "WARN_NOMINAL_STRING_CAPACITY_OVERFLOW";
    public static final String WARN_STRING_LITERAL_CAPACITY_OVERFLOW = "WARN_STRING_LITERAL_CAPACITY_OVERFLOW";

    // Metadata Facets & Ordering
    public static final String ERR_FACET_ORDER_VIOLATION = "ERR_FACET_ORDER_VIOLATION";
    public static final String ERR_DUPLICATE_METADATA_FACET = "ERR_DUPLICATE_METADATA_FACET";
    public static final String ERR_INCOMPLETE_TRAIT_FACET = "ERR_INCOMPLETE_TRAIT_FACET";
    public static final String ERR_EMPTY_METADATA_BLOCK = "ERR_EMPTY_METADATA_BLOCK";
    public static final String ERR_METADATA_FACET_ON_CONSTANT = "ERR_METADATA_FACET_ON_CONSTANT";
    public static final String ERR_EMPTY_DIRECTIVE_BLOCK = "ERR_EMPTY_DIRECTIVE_BLOCK";
    public static final String ERR_STRIP_ON_TYPE_DEF = "ERR_STRIP_ON_TYPE_DEF";
    public static final String ERR_INVALID_METADATA_FACET = "ERR_INVALID_METADATA_FACET";
    public static final String WARN_REDUNDANT_FACET_OVERRIDE = "WARN_REDUNDANT_FACET_OVERRIDE";

    // Temporal Mode & Scale
    public static final String ERR_TEMPORAL_SCALE_MUTUALLY_EXCLUSIVE = "ERR_TEMPORAL_SCALE_MUTUALLY_EXCLUSIVE";
    public static final String ERR_TEMPORAL_SCALE_MISSING = "ERR_TEMPORAL_SCALE_MISSING";
    public static final String ERR_DATETIME_MODE_MUTUALLY_EXCLUSIVE = "ERR_DATETIME_MODE_MUTUALLY_EXCLUSIVE";
    public static final String ERR_DATETIME_MODE_MISSING = "ERR_DATETIME_MODE_MISSING";

    // Enum & Enum Subsets
    public static final String ERR_FILTER_ON_INLINE_ENUM = "ERR_FILTER_ON_INLINE_ENUM";
    public static final String ERR_FILTER_ON_NON_ENUM = "ERR_FILTER_ON_NON_ENUM";
    public static final String ERR_FILTER_MUTUALLY_EXCLUSIVE = "ERR_FILTER_MUTUALLY_EXCLUSIVE";
    public static final String ERR_FILTER_EMPTY_LIST = "ERR_FILTER_EMPTY_LIST";
    public static final String ERR_FILTER_DUPLICATE_VARIANT = "ERR_FILTER_DUPLICATE_VARIANT";
    public static final String ERR_FILTER_MONOTONIC_NARROWING_VIOLATION = "ERR_FILTER_MONOTONIC_NARROWING_VIOLATION";
    public static final String ERR_FILTER_ROOT_ORDER_VIOLATION = "ERR_FILTER_ROOT_ORDER_VIOLATION";
    public static final String ERR_FILTER_COMPLETE_EXCLUSION_VIOLATION = "ERR_FILTER_COMPLETE_EXCLUSION_VIOLATION";
    public static final String ERR_FILTER_ON_CONSTANT = "ERR_FILTER_ON_CONSTANT";
    public static final String ERR_ENUM_SUBSET_PAYLOAD_MEMBERSHIP_VIOLATION = "ERR_ENUM_SUBSET_PAYLOAD_MEMBERSHIP_VIOLATION";

    // Composite & Map Structures
    public static final String ERR_FLAT_LIST_IN_MAP_SLOT = "ERR_FLAT_LIST_IN_MAP_SLOT";
    public static final String ERR_DUPLICATE_MAP_KEY = "ERR_DUPLICATE_MAP_KEY";
    public static final String ERR_INVERTIBLE_MAP_DUPLICATE_VALUE = "ERR_INVERTIBLE_MAP_DUPLICATE_VALUE";
    public static final String WARN_DEGENERATE_TUPLE = "WARN_DEGENERATE_TUPLE";
    public static final String WARN_DEGENERATE_ENUM = "WARN_DEGENERATE_ENUM";
    public static final String WARN_DEGENERATE_UNION = "WARN_DEGENERATE_UNION";
    public static final String WARN_DEGRADED_SCHEMA_USAGE = "WARN_DEGRADED_SCHEMA_USAGE";

    // Literals & Style
    public static final String ERR_INVALID_BOOLEAN_CASING = "ERR_INVALID_BOOLEAN_CASING";
    public static final String ERR_INVALID_BOOLEAN_LITERAL = "ERR_INVALID_BOOLEAN_LITERAL";
    public static final String WARN_REDUNDANT_VARIANT_TAG = "WARN_REDUNDANT_VARIANT_TAG";
    public static final String WARN_VARIANT_FORM_DISCREPANCY = "WARN_VARIANT_FORM_DISCREPANCY";
}
