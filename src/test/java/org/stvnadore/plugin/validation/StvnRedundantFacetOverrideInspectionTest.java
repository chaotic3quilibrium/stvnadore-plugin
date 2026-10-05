package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Platform integration test suite validating {@link StvnRedundantFacetOverrideInspection}
 * and {@link StvnRedundantFacetOverrideInspection.RemoveRedundantFacetQuickFix}.
 */
@NullMarked
public final class StvnRedundantFacetOverrideInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new InspectionProfileEntry[]{new StvnRedundantFacetOverrideInspection()});
    }

    public void testRedundantDefaultFacetWarningAndFix() {
        var text = """
            {
              :defs {
                :Text { #preserveIndent #FALSE } :String
              }
              :type :Text
              :body "sample"
            }
            """;
        myFixture.configureByText("redundant_default.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Redundant facet override"))
            .toList();
        assertFalse("Expected warning on redundant facet override '#preserveIndent #FALSE'", warnings.isEmpty());
        assertTrue("Warning message must mention effective value '#FALSE'",
            warnings.get(0).getDescription().contains("'#FALSE'"));

        int offset = text.indexOf("#preserveIndent");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Remove redundant facet");
        assertFalse("Expected quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Applying quick-fix must remove single-facet map entirely:\n" + result,
            result.contains(":Text :String"));
        assertFalse("Result must not contain metadata map on :Text", result.contains(":Text {"));
    }

    public void testRedundantInheritedFacetWarningAndFix() {
        var text = """
            {
              :defs {
                :Parent { #preserveIndent #TRUE } :String
                :Child { #preserveIndent #TRUE #maxSize 500 } :Parent
              }
              :type :Child
              :body "sample"
            }
            """;
        myFixture.configureByText("redundant_inherited.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Redundant facet override"))
            .toList();
        assertEquals("Expected exactly 1 warning on child definition", 1, warnings.size());
        assertTrue("Warning message must mention effective value '#TRUE'",
            warnings.get(0).getDescription().contains("'#TRUE'"));

        int offset = text.indexOf("#preserveIndent #TRUE #maxSize");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Remove redundant facet");
        assertFalse("Expected quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Expected '#maxSize 500' preserved in child metadata map:\n" + result,
            result.contains(":Child { #maxSize 500 } :Parent"));
        assertFalse("Child must not contain #preserveIndent", result.contains(":Child { #preserveIndent"));
    }

    public void testActiveOverrideProducesZeroWarnings() {
        var text = """
            {
              :defs {
                :Parent { #preserveIndent #TRUE } :String
                :Child { #preserveIndent #FALSE } :Parent
              }
              :type :Child
              :body "sample"
            }
            """;
        myFixture.configureByText("active_override.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Redundant facet override"))
            .toList();
        assertTrue("Active intentional override must produce zero redundant warnings", warnings.isEmpty());
    }

    public void testRedundantNumericFacetWarning() {
        var text = """
            {
              :defs {
                :Text { #maxSize 16777216 } :String
              }
              :type :Text
              :body "sample"
            }
            """;
        myFixture.configureByText("redundant_numeric.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Redundant facet override"))
            .toList();
        assertFalse("Expected warning on redundant #maxSize default", warnings.isEmpty());
        assertTrue("Warning message must mention effective value '16777216'",
            warnings.get(0).getDescription().contains("'16777216'"));
    }
}
