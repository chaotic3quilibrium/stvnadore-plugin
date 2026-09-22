package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Automated platform test suite validating {@link StvnTemporalModeInspection}.
 * Verifies temporal mode requirements, missing facet diagnostics, bare scale flags,
 * and mutual exclusivity governance.
 */
@NullMarked
public final class StvnTemporalModeInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new InspectionProfileEntry[]{new StvnTemporalModeInspection()});
    }

    public void testBareTimeEpochRequiresUnitFacetAndQuickFixApplies() {
        var text = """
            {
              :defs {
                :CreationEpoch :TimeEpoch
              }
              :type :CreationEpoch
              :body 1700000000
            }
            """;
        myFixture.configureByText("bare_epoch.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("requires a scale facet"))
            .toList();
        assertFalse("Expected missing temporal facet error on bare :TimeEpoch", errors.isEmpty());

        int offset = text.indexOf(":TimeEpoch");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Add temporal facet '#ms'");
        assertFalse("Expected quick-fix to add '#ms' to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Expected #ms in converted text:\n" + result, result.contains("#ms"));
    }

    public void testBareDateTimeRequiresModeFacetAndQuickFixApplies() {
        var text = """
            {
              :defs {
                :EventTimestamp :DateTime
              }
              :type :EventTimestamp
              :body "2026-09-21T12:00:00Z"
            }
            """;
        myFixture.configureByText("bare_datetime.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("requires explicit mode or unit facet"))
            .toList();
        assertFalse("Expected missing temporal facet error on bare :DateTime", errors.isEmpty());

        int offset = text.indexOf(":DateTime");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Add temporal facet '#offset'");
        assertFalse("Expected quick-fix to add '#offset' to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Expected #offset in converted text", result.contains("#offset"));
    }

    public void testConflictingTemporalModesTriggersError() {
        var text = """
            {
              :defs {
                :ConflictedTime { #offset #zoned } :DateTime
              }
              :type :ConflictedTime
              :body "2026-09-21T12:00:00+00:00[UTC]"
            }
            """;
        myFixture.configureByText("conflicted_temporal.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("mutually exclusive"))
            .toList();
        assertFalse("Expected mutually exclusive temporal modes error", errors.isEmpty());
    }

    public void testConflictingTemporalScalesTriggersError() {
        var text = """
            {
              :defs {
                :ConflictedEpoch { #s #ms } :TimeEpoch
              }
              :type :ConflictedEpoch
              :body 1700000000
            }
            """;
        myFixture.configureByText("conflicted_scales.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("mutually exclusive"))
            .toList();
        assertFalse("Expected mutually exclusive temporal scales error on #s #ms", errors.isEmpty());
    }

    public void testCompliantTemporalDeclarationsProduceZeroErrors() {
        var text = """
            {
              :defs {
                :CompliantEpoch { #s } :TimeEpoch
                :CompliantMillis { #ms } :TimeEpoch
                :CompliantNanos { #ns } :TimeEpoch
                :CompliantZoned { #zoned } :DateTime
                :CompliantAudited { #audited } :DateTime
              }
              :type :CompliantEpoch
              :body 1700000000
            }
            """;
        myFixture.configureByText("compliant_temporal.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && (h.getDescription().contains("requires") || h.getDescription().contains("mutually exclusive")))
            .toList();
        assertTrue("Compliant temporal definitions must produce zero errors", errors.isEmpty());
    }
}
