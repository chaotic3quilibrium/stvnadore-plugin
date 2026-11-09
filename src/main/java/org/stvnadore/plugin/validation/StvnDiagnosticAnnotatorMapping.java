package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.PsiFile;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnVocabulary;
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
        map.put(DiagnosticBag.WARN_DOCUMENT_DOMAIN_OMITTED_IN_META, "StvnStemMismatch");
        map.put(DiagnosticBag.WARN_DOCUMENT_NAME_MISMATCH, "StvnStemMismatch");
        map.put(StvnDiagnosticCodes.WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME, "StvnStemMismatch");
        map.put(StvnDiagnosticCodes.WARN_DOCUMENT_DOMAIN_OMITTED_IN_META, "StvnStemMismatch");
        map.put(StvnDiagnosticCodes.WARN_DOCUMENT_NAME_MISMATCH, "StvnStemMismatch");
        map.put("DOCUMENT_DOMAIN_OMITTED_IN_FILENAME", "StvnStemMismatch");
        map.put("DOCUMENT_DOMAIN_OMITTED_IN_META", "StvnStemMismatch");
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

        // 6. Redundant Metadata Facet Override Inspection
        map.put(StvnDiagnosticCodes.WARN_REDUNDANT_FACET_OVERRIDE, "StvnRedundantFacetOverride");

        // 7. Metadata Facet Governance Inspection
        map.put(DiagnosticBag.ERR_DUPLICATE_METADATA_FACET, "StvnMetadataFacet");
        map.put(DiagnosticBag.ERR_EMPTY_METADATA_BLOCK, "StvnMetadataFacet");
        map.put(DiagnosticBag.ERR_EMPTY_META_BLOCK, "StvnMetadataFacet");
        map.put(DiagnosticBag.ERR_EMPTY_DEFS_BLOCK, "StvnMetadataFacet");
        map.put(DiagnosticBag.ERR_EMPTY_DIRECTIVE_BLOCK, "StvnMetadataFacet");
        map.put(DiagnosticBag.ERR_INVALID_METADATA_FACET, "StvnMetadataFacet");
        map.put(DiagnosticBag.ERR_UNRECOGNIZED_METADATA_FACET, "StvnMetadataFacet");
        map.put(StvnDiagnosticCodes.ERR_UNRECOGNIZED_METADATA_FACET, "StvnMetadataFacet");
        map.put("E036", "StvnMetadataFacet");
        map.put("ERR_UNRECOGNIZED_METADATA_FACET", "StvnMetadataFacet");
        map.put(StvnDiagnosticCodes.ERR_DUPLICATE_METADATA_FACET, "StvnMetadataFacet");
        map.put(StvnDiagnosticCodes.ERR_EMPTY_METADATA_BLOCK, "StvnMetadataFacet");
        map.put(StvnDiagnosticCodes.ERR_EMPTY_META_BLOCK, "StvnMetadataFacet");
        map.put(StvnDiagnosticCodes.ERR_EMPTY_DEFS_BLOCK, "StvnMetadataFacet");
        map.put(StvnDiagnosticCodes.ERR_EMPTY_DIRECTIVE_BLOCK, "StvnMetadataFacet");
        map.put(StvnDiagnosticCodes.ERR_MUTUALLY_EXCLUSIVE, "StvnMetadataFacet");
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
        map.put(StvnDiagnosticCodes.ERR_EMPTY_INTERVAL_DOMAIN, "StvnDiscreteInterval");
        map.put(StvnDiagnosticCodes.ERR_INVERTED_RANGE, "StvnDiscreteInterval");
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

        // 11. Excessive Exact Float Precision Inspection
        map.put(StvnDiagnosticCodes.WARN_EXCESSIVE_EXACT_PRECISION, "StvnExcessiveExactFloatPrecision");

        // 12. String Capacity Governance Inspection
        map.put(DiagnosticBag.ERR_CAPACITY_OVERFLOW, "StvnStringCapacity");
        map.put("CAPACITY_OVERFLOW", "StvnStringCapacity");
        map.put(StvnDiagnosticCodes.WARN_NOMINAL_STRING_UNADORNED, "StvnStringCapacity");
        map.put(StvnDiagnosticCodes.WARN_NOMINAL_COLLECTION_UNADORNED, "StvnStringCapacity");
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

        // 15. Trailing Slash Inspection
        map.put(DiagnosticBag.ERR_TRAILING_SLASH_PROHIBITED, "StvnTrailingSlash");
        map.put(StvnDiagnosticCodes.ERR_TRAILING_SLASH_PROHIBITED, "StvnTrailingSlash");

        // 16. Constant Range Inspection
        map.put(DiagnosticBag.ERR_INTEGER_OVERFLOW, "StvnConstantRange");
        map.put(StvnDiagnosticCodes.ERR_CONSTANT_INTEGER_OVERFLOW, "StvnConstantRange");
        map.put(StvnDiagnosticCodes.ERR_CONSTANT_FLOAT_OVERFLOW, "StvnConstantRange");
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
        map.put(DiagnosticBag.ERR_FILTER_DUPLICATE_VARIANT, "StvnEnumSubset");
        map.put(StvnDiagnosticCodes.ERR_FILTER_ON_INLINE_ENUM, "StvnEnumSubset");
        map.put(StvnDiagnosticCodes.ERR_FILTER_ON_NON_ENUM, "StvnEnumSubset");
        map.put(StvnDiagnosticCodes.ERR_FILTER_MUTUALLY_EXCLUSIVE, "StvnEnumSubset");
        map.put(StvnDiagnosticCodes.ERR_FILTER_EMPTY_LIST, "StvnEnumSubset");
        map.put(StvnDiagnosticCodes.ERR_FILTER_DUPLICATE_VARIANT, "StvnEnumSubset");
        map.put(StvnDiagnosticCodes.ERR_FILTER_MONOTONIC_NARROWING_VIOLATION, "StvnEnumSubset");
        map.put(StvnDiagnosticCodes.ERR_FILTER_ROOT_ORDER_VIOLATION, "StvnEnumSubset");
        map.put(StvnDiagnosticCodes.ENUM_SUBSET_ORDER_VIOLATION, "StvnEnumSubset");
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

        // 22. Map Structural Inspection (Duplicate map keys E017 and duplicate inverted values E018 are governed by Core)
        map.put(StvnDiagnosticCodes.ERR_FLAT_LIST_IN_MAP_SLOT, "StvnMapStructural");

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
     * Resolves the governing local inspection shortName for {@code diag}, prioritizing
     * {@link StvnDiagnostic#symbolicId()} over {@link StvnDiagnostic#canonicalCode()} so
     * many-to-one canonical buckets (such as {@code E008} containing {@code ERR_TRAIT_VIOLATION})
     * never suppress diagnostics lacking a local PSI inspection.
     *
     * @param diag compiler diagnostic
     * @return governing inspection shortName, or {@code null} if ungoverned
     */
    public static @Nullable String getGoverningInspectionShortName(StvnDiagnostic diag) {
        var msg = diag.message();
        if (msg != null && msg.contains("ancestor")) {
            var symId = diag.symbolicId().orElse("");
            var canonId = diag.canonicalCode().orElse("");
            var errId = diag.errorCode().orElse("");
            if (DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE.equals(symId)
                    || StvnDiagnosticCodes.ERR_MUTUALLY_EXCLUSIVE.equals(symId)
                    || DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE.equals(errId)
                    || StvnDiagnosticCodes.ERR_MUTUALLY_EXCLUSIVE.equals(errId)
                    || "E008".equals(canonId)) {
                return null;
            }
        }
        if (diag.symbolicId().isPresent()) {
            String sym = diag.symbolicId().get();
            if (isExplicitlyUngovernedSymbolicId(sym)) {
                return null;
            }
            String mapped = CODE_TO_INSPECTION_MAP.get(sym);
            if (mapped != null) {
                return mapped;
            }
        }
        if (diag.canonicalCode().isPresent()) {
            String canon = diag.canonicalCode().get();
            if (isExplicitlyUngovernedSymbolicId(canon)) {
                return null;
            }
            String mapped = CODE_TO_INSPECTION_MAP.get(canon);
            if (mapped != null) {
                return mapped;
            }
        }
        if (diag.errorCode().isPresent()) {
            String err = diag.errorCode().get();
            if (isExplicitlyUngovernedSymbolicId(err)) {
                return null;
            }
            return CODE_TO_INSPECTION_MAP.get(err);
        }
        return null;
    }

    private static boolean isExplicitlyUngovernedSymbolicId(String code) {
        return DiagnosticBag.ERR_TRAIT_VIOLATION.equals(code)
            || "ERR_TRAIT_VIOLATION".equals(code)
            || DiagnosticBag.ERR_DUPLICATE_MAP_KEY.equals(code)
            || "DUPLICATE_MAP_KEY".equals(code)
            || DiagnosticBag.ERR_DUPLICATE_INVERTED_MAP_VALUE.equals(code)
            || "DUPLICATE_INVERTED_MAP_VALUE".equals(code)
            || "INVERTIBLE_MAP_DUPLICATE".equals(code)
            || "E017".equals(code)
            || "E018".equals(code)
            || DiagnosticBag.ERR_INCOMPATIBLE_TYPE.equals(code)
            || DiagnosticBag.ERR_INCOMPATIBLE_METADATA_TYPE.equals(code)
            || DiagnosticBag.ERR_NON_OVERRIDABLE_FACET.equals(code)
            || "NON_OVERRIDABLE_FACET".equals(code)
            || "E040".equals(code)
            || DiagnosticBag.ERR_PRELUDE_ALIAS_PURGED.equals(code)
            || "ERR_PRELUDE_ALIAS_PURGED".equals(code)
            || DiagnosticBag.ERR_MALFORMED_SCHEMA.equals(code)
            || "ERR_MALFORMED_SCHEMA".equals(code)
            || DiagnosticBag.ERR_CONSTRAINT_VIOLATION.equals(code)
            || "ERR_SECTION_PROHIBITED".equals(code)
            || "ERR_SECTION_REQUIRED".equals(code)
            || DiagnosticBag.ERR_INVALID_NUMERIC_LITERAL.equals(code)
            || DiagnosticBag.ERR_TEMPORAL_OFFSET_IN_ZONED.equals(code)
            || DiagnosticBag.ERR_TEMPORAL_ZONE_IN_EPOCH.equals(code)
            || DiagnosticBag.ERR_TEMPORAL_AUDITED_MISMATCH.equals(code)
            || DiagnosticBag.ERR_TEMPORAL_DST_GAP_LITERAL.equals(code)
            || DiagnosticBag.ERR_TEMPORAL_ZONE_IN_OFFSET.equals(code)
            || "TEMPORAL_LITERAL_FORMAT_INVALID".equals(code)
            || DiagnosticBag.ERR_DATETIME_FENCED_STRING_PROHIBITED.equals(code)
            || DiagnosticBag.ERR_DATETIME_MISSING_OFFSET.equals(code)
            || DiagnosticBag.ERR_DATETIME_MISSING_ZONE.equals(code)
            || DiagnosticBag.ERR_DATETIME_INVALID_FORMAT.equals(code)
            || DiagnosticBag.ERR_DATETIME_INVALID_CALENDAR.equals(code)
            || DiagnosticBag.ERR_DATETIME_INVALID_ZONE.equals(code)
            || "E019".equals(code)
            || "E020".equals(code)
            || "E021".equals(code)
            || "E022".equals(code)
            || "E028".equals(code);
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
        var message = diag.message();
        var sym = diag.symbolicId().orElse(null);
        var canon = diag.canonicalCode().orElse(null);
        var err = diag.errorCode().orElse(null);

        if (DiagnosticBag.ERR_CONSTRAINT_VIOLATION.equals(sym)
                || "E012".equals(canon)
                || DiagnosticBag.ERR_CONSTRAINT_VIOLATION.equals(err)) {
            if (message.contains("not permitted in enum subset")) {
                int offset = diag.startOffset();
                if (offset >= 0 && offset < file.getTextLength()) {
                    var elem = file.findElementAt(offset);
                    var vkw = com.intellij.psi.util.PsiTreeUtil.getParentOfType(elem, org.stvnadore.psi.ValueKeyword.class, false);
                    if (vkw != null && com.intellij.psi.util.PsiTreeUtil.getParentOfType(vkw, org.stvnadore.psi.ConstantDefinition.class) == null) {
                        var ref = vkw.getReference();
                        if (ref == null || !(ref.resolve() instanceof org.stvnadore.psi.ConstantDefinition)) {
                            if (!ApplicationManager.getApplication().isUnitTestMode()) {
                                return true;
                            }
                            return isInspectionActive(file, "StvnEnumSubset");
                        }
                    }
                }
            }
            if (message.contains("is outside allowable range")
                    || message.contains("Value must be greater than")
                    || message.contains("Value must be strictly")
                    || message.contains("Value must be less than")) {
                int offset = diag.startOffset();
                if (offset >= 0 && offset < file.getTextLength()) {
                    var elem = file.findElementAt(offset);
                    var constDef = com.intellij.psi.util.PsiTreeUtil.getParentOfType(elem, org.stvnadore.psi.ConstantDefinition.class);
                    var metaMap = com.intellij.psi.util.PsiTreeUtil.getParentOfType(elem, org.stvnadore.psi.MetadataMap.class);
                    if (constDef != null && metaMap == null && constDef.getValue() != null
                            && com.intellij.psi.util.PsiTreeUtil.isAncestor(constDef.getValue(), elem, false)) {
                        if (!ApplicationManager.getApplication().isUnitTestMode()) {
                            return true;
                        }
                        return isInspectionActive(file, "StvnConstantRange");
                    }
                }
            }
        }

        if ((DiagnosticBag.ERR_TYPE_MISMATCH.equals(sym)
                || "E028".equals(canon)
                || DiagnosticBag.ERR_TYPE_MISMATCH.equals(err))
                && message.contains("expected collection constructor " + StvnVocabulary.TYPE_MAP
                    + ", found collection constructor " + StvnVocabulary.TYPE_SEQ)) {
            if (!ApplicationManager.getApplication().isUnitTestMode()) {
                return true;
            }
            return isInspectionActive(file, "StvnMapStructural");
        }

        if ((sym != null && isExplicitlyUngovernedSymbolicId(sym))
                || (canon != null && isExplicitlyUngovernedSymbolicId(canon))
                || (err != null && isExplicitlyUngovernedSymbolicId(err))) {
            return false;
        }

        // Guard: structural section violations, unfenced block strings, and Layer-1 extension checks are ungoverned
        if (message.contains("cannot declare ':type'")
                || message.contains("cannot declare ':body'")
                || message.contains("Structural violation:")
                || message.contains("Unfenced block strings are prohibited")
                || message.startsWith("Unrecognized STVN file extension")) {
            return false;
        }

        // Guard: Schema facet numeric/capacity domain violations not covered by PSI inspections remain with core
        if (message.contains("is out of bounds for physical capacity")
                || message.contains("Mantissa precision ")
                || message.contains("significant digits")
                || message.contains(": Size ")
                || message.contains("must be >= 1")
                || message.contains("must be >= 0")
                || message.contains("must be 32 or 64")
                || message.contains("cannot exceed #maxSize")
                || message.contains("lexicographical")
                || message.contains("exceeds #maxSize")
                || message.contains("is below #minSize")) {
            return false;
        }

        // Guard: StvnConstantRange only inspects ConstantDefinition values inside :defs, not :body literals or metadata bounds
        if (DiagnosticBag.ERR_INTEGER_OVERFLOW.equals(sym)
                || DiagnosticBag.ERR_FLOAT_OVERFLOW.equals(sym)
                || "INT_OVERFLOW".equals(sym)
                || ("E009".equals(canon) && sym == null)
                || ("E010".equals(canon) && sym == null)) {
            int offset = diag.startOffset();
            if (offset < 0 || offset >= file.getTextLength()) {
                return false;
            }
            var elem = file.findElementAt(offset);
            if (com.intellij.psi.util.PsiTreeUtil.getParentOfType(elem, org.stvnadore.psi.ConstantDefinition.class) == null
                    || com.intellij.psi.util.PsiTreeUtil.getParentOfType(elem, org.stvnadore.psi.MetadataMap.class) != null) {
                return false;
            }
        }

        // Route shared mutual-exclusivity diagnostics to the specific domain inspection
        if (DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE.equals(sym)
                || StvnDiagnosticCodes.ERR_MUTUALLY_EXCLUSIVE.equals(sym)
                || DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE.equals(err)
                || StvnDiagnosticCodes.ERR_MUTUALLY_EXCLUSIVE.equals(err)
                || "E008".equals(canon)
                || "E011".equals(canon)
                || "E011".equals(err)) {
            if (message.contains("ancestor")) {
                return false;
            }
            if (DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE.equals(sym)
                    || StvnDiagnosticCodes.ERR_MUTUALLY_EXCLUSIVE.equals(sym)
                    || DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE.equals(err)
                    || StvnDiagnosticCodes.ERR_MUTUALLY_EXCLUSIVE.equals(err)) {
                String mutexOwner = "StvnDiscreteInterval";
                if (message.contains("Temporal scale facets")
                        || message.contains("Temporal mode facets")
                        || message.contains("Mutually exclusive temporal")) {
                    mutexOwner = "StvnTemporalMode";
                } else if (message.contains(StvnVocabulary.FACET_KW_EXACT) || message.contains(StvnVocabulary.FACET_KW_SIZE)) {
                    mutexOwner = "StvnMetadataFacet";
                } else if (message.contains(StvnVocabulary.FACET_KW_FILTER_INCL) || message.contains(StvnVocabulary.FACET_KW_FILTER_EXCL)) {
                    mutexOwner = "StvnEnumSubset";
                }
                if (!ApplicationManager.getApplication().isUnitTestMode()) {
                    return true;
                }
                return isInspectionActive(file, mutexOwner);
            }
        }

        if (DiagnosticBag.ERR_INVALID_METADATA_FACET.equals(sym)
                || StvnDiagnosticCodes.ERR_INVALID_METADATA_FACET.equals(sym)) {
            if (message.contains("cannot be applied to :Map directly")
                    || message.contains("does not accept arguments")) {
                return false;
            }
            int offset = diag.startOffset();
            if (offset >= 0 && offset < file.getTextLength()) {
                var elem = file.findElementAt(offset);
                if (com.intellij.psi.util.PsiTreeUtil.getParentOfType(elem, org.stvnadore.psi.MetadataMap.class, false) == null
                        && com.intellij.psi.util.PsiTreeUtil.getParentOfType(elem, org.stvnadore.psi.MetaBlock.class, false) == null) {
                    return false;
                }
            }
        }

        var shortName = getGoverningInspectionShortName(diag);
        if (shortName != null) {
            if (!ApplicationManager.getApplication().isUnitTestMode()) {
                return true;
            }
            return isInspectionActive(file, shortName);
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
        if (message.contains("Root ordering violation")
                || message.contains("Monotonic narrowing violation")
                || message.contains("enum filter variant list cannot be empty")
                || message.contains("Complete exclusion violation")
                || message.contains("filter facets cannot be applied")
                || message.contains("filter facets are not allowed")
                || message.contains("in filter list")
                || message.contains("duplicate variant in filter")) {
            return "StvnEnumSubset";
        }
        if (message.contains("is omitted from filename stem slot 1")
                || message.contains("omits #domain")
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
                || message.contains("requires root :meta block")
                || message.contains("requires #kind facet")
                || message.contains("does not match file extension")) {
            return "StvnMissingMeta";
        }
        if (message.contains("violates 3-tier monotonic progression")
                || message.contains("violates canonical 5-tier order")) {
            return "StvnMetadataOrder";
        }
        if (message.contains(":TimeEpoch requires explicit scale facet")
                || message.contains(":DateTime requires explicit mode facet")
                || message.contains("Mutually exclusive temporal scale")) {
            return "StvnTemporalMode";
        }
        if (message.contains("Storage sizing #size is prohibited on :String")) {
            return "StvnStringCardinality";
        }
        if (message.contains("Unrecognized constraint facet")
                || message.contains("Unrecognized metadata facet")
                || message.contains("unrecognized facet")
                || message.contains("Invalid metadata facet tag")
                || message.contains("Duplicate metadata facet tag")
                || message.contains("does not match required atomic POSIX pattern")
                || message.contains("Metadata block ':meta {}' must not be empty")
                || message.contains("Empty definition block ':defs {}' is redundant")
                || message.contains("Definition schema ':defs {}' must declare at least one definition")) {
            return "StvnMetadataFacet";
        }
        if (message.contains("Prohibited fenced string delimiter arrow")
                || message.contains("Fenced string closing tag mismatch")) {
            return "StvnFencedString";
        }
        if (message.contains("Nested packages are prohibited")
                || message.contains("Nested package enclosures are prohibited")) {
            return "StvnNestedPackage";
        }
        if (message.contains("Trailing slash prohibited")) {
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
                var vf = file.getVirtualFile();
                if (vf != null) {
                    var p = vf.getPath().replace('\\', '/');
                    if (p.contains("/syntax/") || p.contains("/metadata/") || p.contains("/binary/")) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }
}
