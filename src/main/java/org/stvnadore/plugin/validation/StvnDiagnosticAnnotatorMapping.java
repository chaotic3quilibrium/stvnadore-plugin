package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.PsiFile;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.validation.DiagnosticBag;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Centralized translation gate and deconfliction registry mapping {@code stvnadore-core}
 * diagnostic codes to their governing {@code stvn-plugin} {@link com.intellij.codeInspection.LocalInspectionTool}.
 * <p>
 * Enforces the Single Source of Truth architectural mandate by suppressing external annotator
 * annotations when a diagnostic condition is elevated to a registered inspection.
 */
@NullMarked
public final class StvnDiagnosticAnnotatorMapping {

    private StvnDiagnosticAnnotatorMapping() {}

    /**
     * Immutable mapping of diagnostic error/warning codes to the shortName of their governing inspection.
     */
    private static final Map<String, String> CODE_TO_INSPECTION_MAP;

    /**
     * Immutable set of all diagnostic codes governed by plugin local inspections.
     */
    public static final Set<String> INSPECTION_GOVERNED_CODES;

    static {
        var map = new HashMap<String, String>();

        // 1. Stem Mismatch Inspection
        map.put(DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME, "StvnStemMismatch");
        map.put(DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH, "StvnStemMismatch");
        map.put(StvnDiagnosticCodes.WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME, "StvnStemMismatch");
        map.put(StvnDiagnosticCodes.WARN_DOCUMENT_NAME_MISMATCH, "StvnStemMismatch");
        map.put("DOCUMENT_DOMAIN_OMITTED_IN_FILENAME", "StvnStemMismatch");
        map.put("DOCUMENT_NAME_MISMATCH", "StvnStemMismatch");
        map.put("W001", "StvnStemMismatch");
        map.put("W002", "StvnStemMismatch");
        map.put(DiagnosticBag.ERR_INVALID_FILENAME_STEM, "StvnStemMismatch");
        map.put(StvnDiagnosticCodes.ERR_INVALID_FILENAME_STEM, "StvnStemMismatch");
        map.put("INVALID_FILENAME_STEM", "StvnStemMismatch");
        map.put("E032", "StvnStemMismatch");
        map.put(DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED, "StvnStemMismatch");
        map.put(StvnDiagnosticCodes.ERR_RESERVED_DOMAIN_PROHIBITED, "StvnStemMismatch");
        map.put("ERR_RESERVED_DOMAIN_PROHIBITED", "StvnStemMismatch");
        map.put("E038", "StvnStemMismatch");

        // 2. Suspicious Epoch Magnitude Inspection
        map.put(StvnDiagnosticCodes.WARN_SUSPICIOUS_EPOCH_MAGNITUDE, "StvnSuspiciousEpochMagnitude");
        map.put("WARN_SUSPICIOUS_EPOCH_MAGNITUDE", "StvnSuspiciousEpochMagnitude");

        // 3. Tab Character Forbidden Inspection
        map.put(DiagnosticBag.ERR_TAB_CHARACTER_FORBIDDEN, "StvnTabCharacter");
        map.put(StvnDiagnosticCodes.ERR_TAB_CHARACTER_FORBIDDEN, "StvnTabCharacter");
        map.put("TAB_DISCIPLINE_VIOLATION", "StvnTabCharacter");
        map.put("E001", "StvnTabCharacter");

        // 4. Missing Root Metadata Block Inspection
        map.put(DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH, "StvnMissingMeta");
        map.put(StvnDiagnosticCodes.ERR_DOCUMENT_KIND_MISMATCH, "StvnMissingMeta");
        map.put("DOCUMENT_KIND_MISMATCH", "StvnMissingMeta");
        map.put("E031", "StvnMissingMeta");
        map.put(DiagnosticBag.ERR_META_POSITION_INVALID, "StvnMissingMeta");
        map.put("META_POSITION_INVALID", "StvnMissingMeta");
        map.put("E033", "StvnMissingMeta");

        // 5. Metadata Facet Canonical Order Inspection
        map.put(DiagnosticBag.ERR_FACET_ORDER_VIOLATION, "StvnMetadataOrder");
        map.put(StvnDiagnosticCodes.ERR_FACET_ORDER_VIOLATION, "StvnMetadataOrder");
        map.put("FACET_ORDER_VIOLATION", "StvnMetadataOrder");
        map.put("E036", "StvnMetadataOrder");

        // 6. Redundant Metadata Facet Override Inspection
        map.put(StvnDiagnosticCodes.WARN_REDUNDANT_FACET_OVERRIDE, "StvnRedundantFacetOverride");
        map.put(DiagnosticBag.ERR_NON_OVERRIDABLE_FACET, "StvnRedundantFacetOverride");

        // 7. Metadata Facet Governance Inspection
        map.put(DiagnosticBag.ERR_DUPLICATE_METADATA_FACET, "StvnMetadataFacet");
        map.put(DiagnosticBag.ERR_EMPTY_METADATA_BLOCK, "StvnMetadataFacet");
        map.put(DiagnosticBag.ERR_EMPTY_META_BLOCK, "StvnMetadataFacet");
        map.put(DiagnosticBag.ERR_EMPTY_DEFS_BLOCK, "StvnMetadataFacet");
        map.put(DiagnosticBag.ERR_EMPTY_DIRECTIVE_BLOCK, "StvnMetadataFacet");
        map.put(DiagnosticBag.ERR_INVALID_METADATA_FACET, "StvnMetadataFacet");
        map.put(DiagnosticBag.ERR_INCOMPATIBLE_TYPE, "StvnMetadataFacet");
        map.put(DiagnosticBag.ERR_INCOMPATIBLE_METADATA_TYPE, "StvnMetadataFacet");
        map.put(StvnDiagnosticCodes.ERR_DUPLICATE_METADATA_FACET, "StvnMetadataFacet");
        map.put(StvnDiagnosticCodes.ERR_EMPTY_METADATA_BLOCK, "StvnMetadataFacet");
        map.put(StvnDiagnosticCodes.ERR_EMPTY_META_BLOCK, "StvnMetadataFacet");
        map.put(StvnDiagnosticCodes.ERR_EMPTY_DEFS_BLOCK, "StvnMetadataFacet");
        map.put(StvnDiagnosticCodes.ERR_EMPTY_DIRECTIVE_BLOCK, "StvnMetadataFacet");
        map.put(StvnDiagnosticCodes.ERR_METADATA_FACET_ON_CONSTANT, "StvnMetadataFacet");
        map.put(StvnDiagnosticCodes.ERR_STRIP_ON_TYPE_DEF, "StvnMetadataFacet");
        map.put(StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET, "StvnMetadataFacet");
        map.put(StvnDiagnosticCodes.ERR_INCOMPLETE_TRAIT_FACET, "StvnMetadataFacet");
        map.put("INVALID_FACET_TARGET", "StvnMetadataFacet");
        map.put("EMPTY_METADATA_BLOCK", "StvnMetadataFacet");
        map.put("EMPTY_META_BLOCK", "StvnMetadataFacet");
        map.put("EMPTY_DIRECTIVE_BLOCK", "StvnMetadataFacet");
        map.put("FLOAT_INVALID_SIZE", "StvnMetadataFacet");
        map.put("E003", "StvnMetadataFacet");
        map.put("E004", "StvnMetadataFacet");
        map.put("E008", "StvnMetadataFacet");
        map.put("E010", "StvnMetadataFacet");
        map.put("E039", "StvnMetadataFacet");

        // 8. String Cardinality Inspection
        map.put(DiagnosticBag.ERR_STRING_CARDINALITY_PROHIBITED, "StvnStringCardinality");
        map.put(StvnDiagnosticCodes.ERR_STRING_CARDINALITY_PROHIBITED, "StvnStringCardinality");
        map.put("STRING_BOUND_VIOLATION", "StvnStringCardinality");
        map.put("E012", "StvnStringCardinality");

        // 9. Discrete Interval Bound Inspection
        map.put(DiagnosticBag.ERR_DISCRETE_BOUND_KIND_PROHIBITED, "StvnDiscreteInterval");
        map.put(StvnDiagnosticCodes.ERR_DISCRETE_BOUND_KIND_PROHIBITED, "StvnDiscreteInterval");
        map.put(DiagnosticBag.ERR_EMPTY_INTERVAL_DOMAIN, "StvnDiscreteInterval");
        map.put(DiagnosticBag.ERR_INVERTED_RANGE, "StvnDiscreteInterval");
        map.put(DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE, "StvnDiscreteInterval");
        map.put("FLOAT_EXACT_RANGE_CONFLICT", "StvnDiscreteInterval");
        map.put("E011", "StvnDiscreteInterval");

        // 10. Temporal Mode & Scale Inspection
        map.put(DiagnosticBag.ERR_TEMPORAL_SCALE_MISSING, "StvnTemporalMode");
        map.put(DiagnosticBag.ERR_DATETIME_MODE_INVALID, "StvnTemporalMode");
        map.put(DiagnosticBag.ERR_MISSING_TEMPORAL_FACET, "StvnTemporalMode");
        map.put("ERR_LEGACY_UNIT_FACET_PURGED", "StvnTemporalMode");
        map.put(StvnDiagnosticCodes.ERR_TEMPORAL_SCALE_MUTUALLY_EXCLUSIVE, "StvnTemporalMode");
        map.put(StvnDiagnosticCodes.ERR_TEMPORAL_SCALE_MISSING, "StvnTemporalMode");
        map.put(StvnDiagnosticCodes.ERR_DATETIME_MODE_MUTUALLY_EXCLUSIVE, "StvnTemporalMode");
        map.put(StvnDiagnosticCodes.ERR_DATETIME_MODE_MISSING, "StvnTemporalMode");
        map.put(DiagnosticBag.ERR_PRELUDE_ALIAS_PURGED, "StvnTemporalMode");
        map.put("TEMPORAL_OFFSET_IN_ZONED", "StvnTemporalMode");
        map.put("TEMPORAL_ZONE_IN_EPOCH", "StvnTemporalMode");
        map.put("TEMPORAL_AUDITED_MISMATCH", "StvnTemporalMode");
        map.put("TEMPORAL_DST_GAP_LITERAL", "StvnTemporalMode");
        map.put("E019", "StvnTemporalMode");
        map.put("E020", "StvnTemporalMode");
        map.put("E021", "StvnTemporalMode");
        map.put("E022", "StvnTemporalMode");

        // 11. Excessive Exact Float Precision Inspection
        map.put(StvnDiagnosticCodes.WARN_EXCESSIVE_EXACT_PRECISION, "StvnExcessiveExactFloatPrecision");

        // 12. String Capacity Governance Inspection
        map.put(DiagnosticBag.ERR_CAPACITY_OVERFLOW, "StvnStringCapacity");
        map.put("CAPACITY_OVERFLOW", "StvnStringCapacity");
        map.put(StvnDiagnosticCodes.WARN_NOMINAL_STRING_UNADORNED, "StvnStringCapacity");
        map.put(StvnDiagnosticCodes.WARN_NOMINAL_STRING_CAPACITY_OVERFLOW, "StvnStringCapacity");
        map.put(StvnDiagnosticCodes.WARN_STRING_LITERAL_CAPACITY_OVERFLOW, "StvnStringCapacity");

        // 13. Flat Document Include Inspection
        map.put(DiagnosticBag.ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT, "StvnFlatDocumentInclude");
        map.put(StvnDiagnosticCodes.ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT, "StvnFlatDocumentInclude");
        map.put(DiagnosticBag.ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT, "StvnFlatDocumentInclude");
        map.put("UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT", "StvnFlatDocumentInclude");
        map.put("E035", "StvnFlatDocumentInclude");

        // 14. Nested Package Enclosures Inspection
        map.put(DiagnosticBag.ERR_NESTED_PACKAGE_PROHIBITED, "StvnNestedPackage");
        map.put(StvnDiagnosticCodes.ERR_NESTED_PACKAGE_PROHIBITED, "StvnNestedPackage");
        map.put(DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED, "StvnNestedPackage");
        map.put(StvnDiagnosticCodes.ERR_RESERVED_PACKAGE_PROHIBITED, "StvnNestedPackage");
        map.put("ERR_RESERVED_PACKAGE_PROHIBITED", "StvnNestedPackage");
        map.put("E037", "StvnNestedPackage");

        // 15. Trailing Slash Inspection
        map.put(DiagnosticBag.ERR_TRAILING_SLASH_PROHIBITED, "StvnTrailingSlash");
        map.put(StvnDiagnosticCodes.ERR_TRAILING_SLASH_PROHIBITED, "StvnTrailingSlash");

        // 16. Constant Integer Range Inspection
        map.put(DiagnosticBag.ERR_INTEGER_OVERFLOW, "StvnConstantRange");
        map.put(StvnDiagnosticCodes.ERR_CONSTANT_INTEGER_OVERFLOW, "StvnConstantRange");
        map.put("INT_OVERFLOW", "StvnConstantRange");
        map.put("E009", "StvnConstantRange");

        // 17. LHS Reserved Type Inspection
        map.put(DiagnosticBag.ERR_RESERVED_KEYWORD_ON_LHS, "StvnLhsReservedType");
        map.put(StvnDiagnosticCodes.ERR_RESERVED_KEYWORD_ON_LHS, "StvnLhsReservedType");

        // 18. Fenced String Delimiter Inspection
        map.put(DiagnosticBag.ERR_PROHIBITED_FENCE_ARROW, "StvnFencedString");
        map.put(StvnDiagnosticCodes.ERR_PROHIBITED_FENCE_ARROW, "StvnFencedString");
        map.put(StvnDiagnosticCodes.ERR_FENCE_TAG_EMPTY, "StvnFencedString");
        map.put(StvnDiagnosticCodes.ERR_FENCE_TAG_WHITESPACE, "StvnFencedString");
        map.put(StvnDiagnosticCodes.ERR_FENCE_TAG_LENGTH_OVERFLOW, "StvnFencedString");
        map.put(StvnDiagnosticCodes.ERR_FENCE_TAG_SYNTAX_INVALID, "StvnFencedString");
        map.put(StvnDiagnosticCodes.ERR_FENCE_CLOSING_TAG_MISMATCH, "StvnFencedString");
        map.put(StvnDiagnosticCodes.ERR_FENCE_OPENING_TAG_MISMATCH, "StvnFencedString");
        map.put(StvnDiagnosticCodes.ERR_FENCE_BLOCK_UNCLOSED, "StvnFencedString");
        map.put("FENCE_DELIMITER_MISMATCH", "StvnFencedString");
        map.put("FENCE_PROHIBITED_ARROW", "StvnFencedString");
        map.put("E025", "StvnFencedString");
        map.put("E026", "StvnFencedString");

        // 19. Enum Subset Filtering Inspection
        map.put(StvnDiagnosticCodes.ERR_FILTER_ON_INLINE_ENUM, "StvnEnumSubset");
        map.put(StvnDiagnosticCodes.ERR_FILTER_ON_NON_ENUM, "StvnEnumSubset");
        map.put(StvnDiagnosticCodes.ERR_FILTER_MUTUALLY_EXCLUSIVE, "StvnEnumSubset");
        map.put(StvnDiagnosticCodes.ERR_FILTER_EMPTY_LIST, "StvnEnumSubset");
        map.put(StvnDiagnosticCodes.ERR_FILTER_DUPLICATE_VARIANT, "StvnEnumSubset");
        map.put(StvnDiagnosticCodes.ERR_FILTER_MONOTONIC_NARROWING_VIOLATION, "StvnEnumSubset");
        map.put(StvnDiagnosticCodes.ERR_FILTER_ROOT_ORDER_VIOLATION, "StvnEnumSubset");
        map.put(StvnDiagnosticCodes.ERR_FILTER_COMPLETE_EXCLUSION_VIOLATION, "StvnEnumSubset");
        map.put(StvnDiagnosticCodes.ERR_FILTER_ON_CONSTANT, "StvnEnumSubset");
        map.put(StvnDiagnosticCodes.ERR_ENUM_SUBSET_PAYLOAD_MEMBERSHIP_VIOLATION, "StvnEnumSubset");
        map.put("ENUM_SUBSET_NOT_SUBSET", "StvnEnumSubset");
        map.put("ENUM_SUBSET_ORDER_VIOLATION", "StvnEnumSubset");
        map.put("ENUM_SUBSET_EMPTY", "StvnEnumSubset");
        map.put("E014", "StvnEnumSubset");
        map.put("E015", "StvnEnumSubset");
        map.put("E016", "StvnEnumSubset");

        // 20. Degenerate Composite Schema Inspection
        map.put(StvnDiagnosticCodes.WARN_DEGENERATE_TUPLE, "StvnDegenerateComposite");
        map.put(StvnDiagnosticCodes.WARN_DEGENERATE_ENUM, "StvnDegenerateComposite");
        map.put(StvnDiagnosticCodes.WARN_DEGENERATE_UNION, "StvnDegenerateComposite");

        // 21. Degraded Schema Payload Evaluation Inspection
        map.put(StvnDiagnosticCodes.WARN_DEGRADED_SCHEMA_USAGE, "StvnDegradedSchema");

        // 22. Map Structural Inspection
        map.put(DiagnosticBag.ERR_DUPLICATE_MAP_KEY, "StvnMapStructural");
        map.put(DiagnosticBag.ERR_DUPLICATE_INVERTED_MAP_VALUE, "StvnMapStructural");
        map.put(StvnDiagnosticCodes.ERR_FLAT_LIST_IN_MAP_SLOT, "StvnMapStructural");
        map.put(StvnDiagnosticCodes.ERR_DUPLICATE_MAP_KEY, "StvnMapStructural");
        map.put(StvnDiagnosticCodes.ERR_INVERTIBLE_MAP_DUPLICATE_VALUE, "StvnMapStructural");
        map.put("DUPLICATE_MAP_KEY", "StvnMapStructural");
        map.put("DUPLICATE_INVERTED_MAP_VALUE", "StvnMapStructural");
        map.put("INVERTIBLE_MAP_DUPLICATE", "StvnMapStructural");
        map.put("E018", "StvnMapStructural");

        // 23. Boolean Validity Inspection
        map.put(StvnDiagnosticCodes.ERR_INVALID_BOOLEAN_CASING, "StvnBooleanValidity");
        map.put(StvnDiagnosticCodes.ERR_INVALID_BOOLEAN_LITERAL, "StvnBooleanValidity");

        // 24. Variant Tag Style Inspection
        map.put(StvnDiagnosticCodes.WARN_REDUNDANT_VARIANT_TAG, "StvnVariantStyle");
        map.put(StvnDiagnosticCodes.WARN_VARIANT_FORM_DISCREPANCY, "StvnVariantStyle");

        // 25. Unresolved Type Reference Inspection
        map.put(StvnDiagnosticCodes.ERR_UNRESOLVED_TYPE, "StvnUnresolvedType");

        CODE_TO_INSPECTION_MAP = Collections.unmodifiableMap(map);
        INSPECTION_GOVERNED_CODES = Collections.unmodifiableSet(map.keySet());
    }

    /**
     * Resolves the governing inspection tool shortName for a given diagnostic code.
     *
     * @param diagnosticCode the error or warning code identifier
     * @return the shortName of the governing inspection, or {@code null} if ungoverned
     */
    public static @Nullable String getGoverningInspectionShortName(String diagnosticCode) {
        return CODE_TO_INSPECTION_MAP.get(diagnosticCode);
    }

    /**
     * Resolves the primary diagnostic identity string from a compiler diagnostic.
     *
     * @param diag the compiler diagnostic
     * @return the diagnostic identifier or error code, or {@code null} if unassigned
     */
    private static @Nullable String resolveDiagnosticKey(StvnDiagnostic diag) {
        var errorCode = diag.errorCode().orElse(null);
        if (errorCode != null && !errorCode.isBlank()) {
            return errorCode;
        }
        try {
            var idMethod = diag.getClass().getMethod("id");
            var res = idMethod.invoke(diag);
            if (res instanceof java.util.Optional<?> opt) {
                var val = opt.orElse(null);
                if (val != null) return val.toString();
            } else if (res instanceof String s && !s.isBlank()) {
                return s;
            }
        } catch (ReflectiveOperationException ignored) {}
        return null;
    }

    /**
     * Evaluates whether a diagnostic is governed by an active inspection and must be suppressed by the annotator.
     * <p>
     * In production mode, local inspections have unconditional primacy over all governed codes.
     * In unit test mode, suppression gates dynamically on whether the governing inspection tool
     * is enabled in the active project inspection profile.
     *
     * @param file the PSI file being annotated
     * @param diag the diagnostic emitted by the compiler
     * @return {@code true} if the diagnostic must be suppressed; {@code false} otherwise
     */
    public static boolean isDiagnosticGovernedAndActive(PsiFile file, StvnDiagnostic diag) {
        var errorCode = resolveDiagnosticKey(diag);
        if (errorCode != null) {
            // Guard: structural section violations (cannot declare ':type' or ':body') are ungoverned by StvnMissingMeta
            if (DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH.equals(errorCode) || StvnDiagnosticCodes.ERR_DOCUMENT_KIND_MISMATCH.equals(errorCode)) {
                var msg = diag.message();
                if (msg.contains("cannot declare ':type'") || msg.contains("cannot declare ':body'") || msg.contains("Structural violation:")) {
                    return false;
                }
            }

            var shortName = CODE_TO_INSPECTION_MAP.get(errorCode);
            if (shortName != null) {
                if (!ApplicationManager.getApplication().isUnitTestMode()) {
                    return true;
                }
                return isInspectionActive(file, shortName);
            }
        }

        // Defensive fallback: inspect prose message content against known inspection domains
        var message = diag.message();
        if (message.contains("cannot declare ':type'") || message.contains("cannot declare ':body'") || message.contains("Structural violation:")) {
            return false;
        }

        var proseGoverningShortName = resolveGoverningInspectionByMessageContent(message);
        if (proseGoverningShortName != null) {
            if (!ApplicationManager.getApplication().isUnitTestMode()) {
                return true;
            }
            return isInspectionActive(file, proseGoverningShortName);
        }

        for (var entry : CODE_TO_INSPECTION_MAP.entrySet()) {
            if (message.contains(entry.getKey())) {
                if (!ApplicationManager.getApplication().isUnitTestMode()) {
                    return true;
                }
                return isInspectionActive(file, entry.getValue());
            }
        }

        return false;
    }

    /**
     * Defensively maps prose compiler messages to their governing inspection shortName.
     *
     * @param message the descriptive error or warning message
     * @return the shortName of the governing inspection, or {@code null} if ungoverned
     */
    public static @Nullable String resolveGoverningInspectionByMessageContent(String message) {
        if (message.contains("is omitted from filename stem slot 1")
                || message.contains("does not match filename stem slot")
                || message.contains("does not match file stem")
                || message.contains("Invalid filename stem")
                || message.contains("Reserved system domain prohibited")) {
            return "StvnStemMismatch";
        }
        if (message.contains("Suspicious epoch timestamp magnitude")
                || message.contains("epoch timestamp magnitude")) {
            return "StvnSuspiciousEpochMagnitude";
        }
        if (message.contains("Tab character")
                || message.contains("TAB_DISCIPLINE_VIOLATION")
                || message.contains("ERR_TAB_CHARACTER_FORBIDDEN")) {
            return "StvnTabCharacter";
        }
        if (message.contains("Mandatory :meta block missing")
                || message.contains("does not match file extension")) {
            return "StvnMissingMeta";
        }
        if (message.contains(":TimeEpoch requires explicit scale facet")
                || message.contains(":DateTime requires explicit mode facet")
                || message.contains("Mutually exclusive temporal scale")) {
            return "StvnTemporalMode";
        }
        if (message.contains("Storage sizing #size is prohibited on :String")) {
            return "StvnStringCardinality";
        }
        if (message.contains("Prohibited fenced string delimiter arrow")
                || message.contains("Fenced string closing tag mismatch")) {
            return "StvnFencedString";
        }
        if (message.contains("Nested package enclosures are prohibited")
                || message.contains("Reserved package namespace prohibited")) {
            return "StvnNestedPackage";
        }
        if (message.contains("Trailing slash prohibited in package path")) {
            return "StvnTrailingSlash";
        }
        if (message.contains("Reserved type keyword cannot be declared on LHS")
                || message.contains("Reserved type keyword cannot be used on left-hand side")) {
            return "StvnLhsReservedType";
        }
        return null;
    }

    /**
     * Determines whether an inspection tool is active and enabled for the given file profile.
     *
     * @param file the target PSI file
     * @param shortName the shortName of the local inspection tool
     * @return {@code true} if the tool is enabled in the current profile; {@code false} otherwise
     */
    public static boolean isInspectionActive(PsiFile file, String shortName) {
        var project = file.getProject();
        if (project.isDisposed()) {
            return false;
        }
        try {
            var profileManager = InspectionProjectProfileManager.getInstance(project);
            var profile = profileManager.getCurrentProfile();
            var key = HighlightDisplayKey.find(shortName);
            if (key != null) {
                return profile.isToolEnabled(key, file);
            }
            if ("StvnMissingMeta".equals(shortName)) {
                return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }
}
