package org.stvnadore.plugin.inspection;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.validation.StvnMetadataFacetInspection;

import java.util.List;

/**
 * Platform verification test suite validating syntax resilience, lexical fallback guards,
 * and quick-fix idempotency for {@link StvnMissingNominalCapacityInspection}.
 */
@NullMarked
public final class StvnMissingNominalCapacityInspectionTest extends BasePlatformTestCase {

    private StvnMissingNominalCapacityInspection inspection = new StvnMissingNominalCapacityInspection();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        inspection = new StvnMissingNominalCapacityInspection();
        myFixture.enableInspections(new InspectionProfileEntry[]{inspection, new StvnMetadataFacetInspection()});
    }

    /**
     * Scenario 1: Document ':Type { #maxSize 4096 } :String' produces zero warnings.
     */
    public void testNominalTypeWithMaxSizeProducesZeroWarnings() {
        myFixture.configureByText("valid_max_size.stvn",
            """
            {
              :defs {
                :Type { #maxSize 4096 } :String
              }
            }
            """
        );
        var highlights = myFixture.doHighlighting();
        var capacityWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("capacity"))
            .toList();

        assertTrue("Nominal type with explicit #maxSize 4096 must produce zero capacity warnings",
            capacityWarnings.isEmpty());
    }

    /**
     * Scenario 2: Document ':Type {# #maxSize 4096 } :String' with in-flight bare '#'
     * does not offer 'Set nominal capacity...' quick-fixes and produces zero capacity warnings.
     */
    public void testInFlightBareHashSuppressesCapacityWarningsAndQuickFixes() {
        var text = """
            {
              :defs {
                :Type {# #maxSize 4096 } :String
              }
            }
            """;
        myFixture.configureByText("inflight_bare_hash.stvn", text);
        int offset = text.indexOf(":Type");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var highlights = myFixture.doHighlighting();

        // 1. Assert zero capacity warnings are emitted on the type definition
        var capacityWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && (h.getDescription().contains("unadorned") || h.getDescription().contains("capacity")))
            .toList();
        assertTrue("In-flight bare '#' inside metadata map must suppress all capacity warnings",
            capacityWarnings.isEmpty());

        // 2. Assert zero 'Set nominal capacity' quick-fixes are offered
        var actions = myFixture.filterAvailableIntentions("Set nominal capacity");
        assertTrue("In-flight bare '#' must not offer 'Set nominal capacity...' quick-fixes",
            actions.isEmpty());
    }

    /**
     * Scenario 3: Document ':Type { #size 64 } :Int' produces zero capacity warnings.
     */
    public void testNumericTypeWithSizeProducesZeroWarnings() {
        myFixture.configureByText("numeric_size.stvn",
            """
            {
              :defs {
                :Type { #size 64 } :Int
              }
            }
            """
        );
        var highlights = myFixture.doHighlighting();
        var capacityWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("capacity"))
            .toList();

        assertTrue("Numeric type :Int with #size 64 must produce zero capacity warnings",
            capacityWarnings.isEmpty());
    }

    /**
     * Scenario 4: Document ':Type { } :String' cleanly reports missing capacity and offers valid quick-fixes.
     */
    public void testEmptyBlockCleanlyReportsMissingCapacityAndOffersQuickFixes() {
        var text = """
            {
              :defs {
                :Type { } :String
              }
            }
            """;
        myFixture.configureByText("empty_block_string.stvn", text);
        int offset = text.indexOf(":Type");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var highlights = myFixture.doHighlighting();

        // 1. Assert unadorned capacity warning is reported
        var capacityWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("unadorned"))
            .toList();
        assertEquals("Expected exactly 1 unadorned capacity warning for :Type { } :String",
            1, capacityWarnings.size());
        assertTrue("Warning description must mention unadorned nominal string type",
            capacityWarnings.get(0).getDescription().contains("Nominal string type ':Type' is unadorned"));

        // 2. Assert valid 'Set nominal capacity' quick-fixes are available
        var actions = myFixture.filterAvailableIntentions("Set nominal capacity");
        assertFalse("Valid quick-fixes must be offered for unadorned :Type { } :String",
            actions.isEmpty());
        assertEquals("Primary quick-fix must set nominal capacity to 4096",
            "Set nominal capacity to 4096", actions.get(0).getText());

        // 3. Launch primary quick-fix and verify idempotent result
        myFixture.launchAction(actions.get(0));
        myFixture.checkResult(
            """
            {
              :defs {
                :Type { #maxSize 4096 } :String
              }
            }
            """
        );
    }
}
