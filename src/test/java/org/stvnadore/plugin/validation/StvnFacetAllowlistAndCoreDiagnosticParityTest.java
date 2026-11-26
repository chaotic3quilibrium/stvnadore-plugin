package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

import java.util.List;

/**
 * Platform integration tests verifying facet allowlist governance,
 * core diagnostic parity, and exact coordinate fault isolation.
 */
@NullMarked
public final class StvnFacetAllowlistAndCoreDiagnosticParityTest extends BasePlatformTestCase {

    @Override
    protected String getTestDataPath() {
        return "src/test/resources/shared-fixtures";
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.copyDirectoryToProject("", "");
        myFixture.enableInspections(
            new StvnVariantStyleInspection(),
            new StvnBooleanValidityInspection(),
            new StvnMapStructuralInspection(),
            new StvnDegradedSchemaInspection(),
            new StvnDegenerateCompositeInspection(),
            new StvnEnumSubsetInspection(),
            new StvnFencedStringInspection(),
            new StvnLhsReservedTypeInspection(),
            new StvnConstantRangeInspection(),
            new StvnFlatDocumentIncludeInspection(),
            new StvnNestedPackageInspection(),
            new StvnTrailingSlashInspection(),
            new StvnStringCapacityInspection(),
            new StvnExcessiveExactFloatPrecisionInspection(),
            new StvnMetadataFacetInspection(),
            new StvnStringCardinalityInspection(),
            new StvnDiscreteIntervalInspection(),
            new StvnTemporalModeInspection(),
            new StvnMetadataOrderInspection(),
            new StvnRedundantFacetOverrideInspection(),
            new StvnUnresolvedTypeInspection(),
            new StvnTabCharacterInspection(),
            new StvnMissingMetaInspection(),
            new StvnStemMismatchInspection(),
            new StvnSuspiciousEpochMagnitudeInspection(),
            new StvnFloatPrecisionLossInspection()
        );
    }

    /**
     * Problem 1 &amp; 3 Scenario 1:
     * Declaring :TradeTimestamp { #audited #us } :DateTime must emit ERR_INVALID_METADATA_FACET on #us,
     * must NOT emit ERR_FACET_ORDER_VIOLATION, and must NOT offer reordering.
     */
    public void testDisorderedInvalidFacetReportsFacetErrorWithoutOrderViolation() {
        var text = """
            {
              :meta { #name "trade_defs" #kind #DEFS }
              :defs {
                :TradeTimestamp { #audited #us } :DateTime
              }
            }
            """;
        myFixture.configureByText("trade_defs.stvn_d", text);
        var highlights = myFixture.doHighlighting();

        var facetErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_INVALID_METADATA_FACET"))
            .toList();
        assertEquals("Expected exactly 1 ERR_INVALID_METADATA_FACET", 1, facetErrors.size());
        assertEquals(text.indexOf("#us"), facetErrors.get(0).getStartOffset());
        assertEquals(text.indexOf("#us") + 3, facetErrors.get(0).getEndOffset());

        var orderErrors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_FACET_ORDER_VIOLATION"))
            .toList();
        assertTrue("ERR_FACET_ORDER_VIOLATION must NOT be emitted: " + orderErrors, orderErrors.isEmpty());

        int offset = text.indexOf("#us");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var reorderFixes = myFixture.filterAvailableIntentions("Reorder metadata facets");
        assertTrue("Reorder quick-fix must NOT be offered on invalid facets", reorderFixes.isEmpty());

        var duplicateInvalidFixes = myFixture.filterAvailableIntentions("Remove invalid facet '#us'");
        assertTrue("Duplicate 'Remove invalid facet '#us'' intention must NOT be offered", duplicateInvalidFixes.isEmpty());
        var removeFixes = myFixture.filterAvailableIntentions("Remove inapplicable facet '#us'");
        assertEquals("Expected 'Remove inapplicable facet '#us'' intention", 1, removeFixes.size());
        myFixture.launchAction(removeFixes.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Target text must heal to '{ #audited } :DateTime':\n" + result,
            result.contains("{ #audited } :DateTime"));
    }

    /**
     * Problem 1 &amp; 2 Scenario 2:
     * Declaring :TradeTimestamp { #ns #us #ms #s #audited } :DateTime must emit 4 errors
     * on scale facets and 0 errors on #audited and {}.
     */
    public void testOrderedInvalidFacetsReportIndividualErrorsAndZeroDelimErrors() {
        var text = """
            {
              :meta { #name "trade_defs_multi" #kind #DEFS }
              :defs {
                :TradeTimestamp { #ns #us #ms #s #audited } :DateTime
              }
            }
            """;
        myFixture.configureByText("trade_defs_multi.stvn_d", text);
        var highlights = myFixture.doHighlighting();

        var facetErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_INVALID_METADATA_FACET"))
            .toList();
        assertEquals("Expected exactly 4 ERR_INVALID_METADATA_FACET errors", 4, facetErrors.size());

        List<String> offendingTags = List.of("#ns", "#us", "#ms", "#s");
        for (String tag : offendingTags) {
            int start = text.indexOf(tag);
            int end = start + tag.length();
            boolean matched = facetErrors.stream().anyMatch(h -> h.getStartOffset() == start && h.getEndOffset() == end);
            assertTrue("Expected error pinned strictly to " + tag, matched);
        }

        int auditedStart = text.indexOf("#audited");
        boolean auditedErrored = highlights.stream().anyMatch(h -> h.getStartOffset() == auditedStart);
        assertFalse("Valid facet #audited must have 0 errors", auditedErrored);
    }

    /**
     * Problem 4 Scenario 3:
     * Root ordering violation in enum subset filter must pin strictly to out-of-order variant.
     */
    public void testEnumSubsetRootOrderViolationPinsStrictlyToOffendingVariant() {
        var text = """
            {
              :meta { #name "status_defs" #kind #DEFS }
              :defs {
                :Status :Enum [ #PENDING #REJECTED #CANCELLED #FILLED ]
                :TerminalStatus { #filterIncl [ #REJECTED #FILLED #CANCELLED ] } :Status
              }
            }
            """;
        myFixture.configureByText("status_defs.stvn_d", text);
        var highlights = myFixture.doHighlighting();

        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ENUM_SUBSET_ORDER_VIOLATION"))
            .toList();
        assertEquals("Expected exactly 1 ENUM_SUBSET_ORDER_VIOLATION error", 1, errors.size());

        int cancelledStart = text.lastIndexOf("#CANCELLED");
        int cancelledEnd = cancelledStart + "#CANCELLED".length();
        assertEquals(cancelledStart, errors.get(0).getStartOffset());
        assertEquals(cancelledEnd, errors.get(0).getEndOffset());

        int rejectedStart = text.lastIndexOf("#REJECTED");
        int filledStart = text.lastIndexOf("#FILLED");
        boolean compliantErrored = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .anyMatch(h -> h.getStartOffset() == rejectedStart || h.getStartOffset() == filledStart);
        assertFalse("Compliant variants must have 0 error highlights", compliantErrored);

        // Verify quick-fix available at compliant variant position
        myFixture.getEditor().getCaretModel().moveToOffset(rejectedStart + 1);
        var actions = myFixture.filterAvailableIntentions("Sort variants to match root enum declaration order");
        assertFalse("Quick-fix must be available on sibling variants", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Reordered list must match canonical declaration order:\n" + result,
            result.contains("[ #REJECTED #CANCELLED #FILLED ]"));
    }

    /**
     * Mirrored Shared Fixture: invalid_datetime_scale_facet.stvn_d
     */
    public void testSharedFixtureInvalidDateTimeScaleFacet() {
        myFixture.configureByFile("syntax/invalid/temporal/invalid_datetime_scale_facet.stvn_d");
        var highlights = myFixture.doHighlighting();

        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_INVALID_METADATA_FACET"))
            .toList();
        assertEquals("Expected 1 ERR_INVALID_METADATA_FACET on invalid_datetime_scale_facet", 1, errors.size());
        assertTrue(errors.get(0).getDescription().contains("facet '#us' is not permitted on :DateTime"));
    }

    /**
     * Mirrored Shared Fixture: invalid_timeepoch_mode_facet.stvn_d
     */
    public void testSharedFixtureInvalidTimeEpochModeFacet() {
        myFixture.configureByFile("syntax/invalid/temporal/invalid_timeepoch_mode_facet.stvn_d");
        var highlights = myFixture.doHighlighting();

        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_INVALID_METADATA_FACET"))
            .toList();
        assertEquals("Expected 1 ERR_INVALID_METADATA_FACET on invalid_timeepoch_mode_facet", 1, errors.size());
        assertTrue(errors.get(0).getDescription().contains("facet '#audited' is not permitted on :TimeEpoch"));
    }

    /**
     * Mirrored Shared Fixture: enum_subset_root_order_violation.stvn_d
     */
    public void testSharedFixtureEnumSubsetRootOrderViolation() {
        myFixture.configureByFile("syntax/invalid/enums/enum_subset_root_order_violation.stvn_d");
        var highlights = myFixture.doHighlighting();

        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ENUM_SUBSET_ORDER_VIOLATION"))
            .toList();
        assertEquals("Expected 1 ENUM_SUBSET_ORDER_VIOLATION on enum_subset_root_order_violation", 1, errors.size());
    }

    /**
     * Mirrored Shared Fixture: tuple_sibling_sets_non_equatable.stvn_i
     */
    public void testSharedFixtureTupleSiblingSetsNonEquatable() {
        myFixture.configureByFile("syntax/invalid/traits/tuple_sibling_sets_non_equatable.stvn_i");
        var highlights = myFixture.doHighlighting();

        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("TRAIT_VIOLATION"))
            .toList();
        assertFalse("Expected TRAIT_VIOLATION on tuple_sibling_sets_non_equatable", errors.isEmpty());
    }

    /**
     * Mirrored Shared Fixture: unrecognized_facet_with_conflicting_modes.stvn_d
     */
    public void testSharedFixtureUnrecognizedFacetWithConflictingModes() {
        myFixture.configureByFile("syntax/invalid/temporal/unrecognized_facet_with_conflicting_modes.stvn_d");
        var highlights = myFixture.doHighlighting();

        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_UNRECOGNIZED_METADATA_FACET"))
            .toList();
        assertEquals("Expected 1 ERR_UNRECOGNIZED_METADATA_FACET", 1, errors.size());
        assertTrue(errors.get(0).getDescription().contains("#milli"));
    }

    /**
     * Mirrored Shared Fixture: discrete_max_incl_and_inverted_range.stvn_d
     */
    public void testSharedFixtureDiscreteMaxInclAndInvertedRange() {
        myFixture.configureByFile("syntax/invalid/intervals/discrete_max_incl_and_inverted_range.stvn_d");
        var highlights = myFixture.doHighlighting();

        var boundErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_DISCRETE_BOUND_KIND_PROHIBITED"))
            .toList();
        assertEquals("Expected 1 ERR_DISCRETE_BOUND_KIND_PROHIBITED", 1, boundErrors.size());

        var rangeErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_INVERTED_RANGE"))
            .toList();
        assertEquals("Expected 1 ERR_INVERTED_RANGE", 1, rangeErrors.size());
    }

    /**
     * Mirrored Shared Fixture: invertible_map_dual_trait_violation.stvn
     */
    public void testSharedFixtureInvertibleMapDualTraitViolation() {
        myFixture.configureByFile("syntax/invalid/traits/invertible_map_dual_trait_violation.stvn");
        var highlights = myFixture.doHighlighting();

        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && (h.getDescription().contains("TRAIT_VIOLATION") || h.getDescription().contains("ERR_TRAIT_VIOLATION")))
            .toList();
        assertFalse("Expected TRAIT_VIOLATION on invertible_map_dual_trait_violation", errors.isEmpty());
    }

    /**
     * Mirrored Shared Fixture: defs_composite_sibling_trait_violations.stvn_d
     */
    public void testSharedFixtureDefsCompositeSiblingTraitViolations() {
        myFixture.configureByFile("syntax/invalid/traits/defs_composite_sibling_trait_violations.stvn_d");
        var highlights = myFixture.doHighlighting();

        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && (h.getDescription().contains("TRAIT_VIOLATION") || h.getDescription().contains("ERR_TRAIT_VIOLATION")))
            .toList();
        assertFalse("Expected TRAIT_VIOLATION on defs_composite_sibling_trait_violations", errors.isEmpty());
    }

    /**
     * Mirrored Shared Fixture: tuple_arity_underflow_closing_paren.stvn
     */
    public void testSharedFixtureTupleArityUnderflowClosingParen() {
        myFixture.configureByFile("syntax/invalid/containers/tuple_arity_underflow_closing_paren.stvn");
        var highlights = myFixture.doHighlighting();

        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("TUPLE_ARITY_MISMATCH"))
            .toList();
        assertFalse("Expected TUPLE_ARITY_MISMATCH on tuple_arity_underflow_closing_paren", errors.isEmpty());
        var text = myFixture.getFile().getText();
        int rparenOffset = text.lastIndexOf(')');
        assertEquals(rparenOffset, errors.get(0).getStartOffset());
        assertEquals(rparenOffset + 1, errors.get(0).getEndOffset());
    }
}
