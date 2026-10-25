package org.stvnadore.plugin.inspection;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.stvnadore.plugin.validation.StvnSuspiciousEpochMagnitudeInspection;

/**
 * Platform integration test suite for StvnSuspiciousEpochMagnitudeInspection.
 */
public final class StvnSuspiciousEpochMagnitudeInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new StvnSuspiciousEpochMagnitudeInspection());
    }

    public void testCleanEpochMagnitude() {
        var text = """
            {
              :defs {
                :SecEpoch { #s } :TimeEpoch
                :MsEpoch { #ms } :TimeEpoch
              }
              :type :Tuple( :SecEpoch :MsEpoch )
              :body ( 1704067200 1704067200000 )
            }
            """;
        myFixture.configureByText("clean_epoch.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_SUSPICIOUS_EPOCH_MAGNITUDE"))
            .toList();
        assertTrue("Expected 0 suspicious magnitude warnings on clean document: " + warnings, warnings.isEmpty());
    }

    public void testMismatchedEpochMagnitudeWarning() {
        var text = """
            {
              :defs {
                :SecEpoch { #s } :TimeEpoch
              }
              :type :SecEpoch
              :body 1704067200000
            }
            """;
        myFixture.configureByText("warn_epoch.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_SUSPICIOUS_EPOCH_MAGNITUDE"))
            .toList();
        assertEquals("Expected exactly 1 suspicious magnitude warning", 1, warnings.size());

        var w = warnings.get(0);
        int expectedStart = text.indexOf("1704067200000");
        int expectedEnd = expectedStart + "1704067200000".length();
        assertEquals(expectedStart, w.getStartOffset());
        assertEquals(expectedEnd, w.getEndOffset());
    }

    public void testRescaleLiteralQuickFix() {
        var text = """
            {
              :defs {
                :SecEpoch { #s } :TimeEpoch
              }
              :type :SecEpoch
              :body 1704067200000
            }
            """;
        myFixture.configureByText("rescale_fix.stvn", text);
        myFixture.doHighlighting();

        int offset = text.indexOf("1704067200000");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);

        var actions = myFixture.filterAvailableIntentions("Rescale literal to 1704067200 (match declared '#s' scale)");
        assertFalse("Expected rescale quick-fix to be available", actions.isEmpty());

        myFixture.launchAction(actions.get(0));
        assertTrue("Document must contain rescaled value 1704067200",
            myFixture.getFile().getText().contains(":body 1704067200"));
    }

    public void testUpdateSchemaScaleQuickFix() {
        var text = """
            {
              :defs {
                :CustomEpoch { #s } :TimeEpoch
              }
              :type :CustomEpoch
              :body 1704067200000
            }
            """;
        myFixture.configureByText("update_schema_fix.stvn", text);
        myFixture.doHighlighting();

        int offset = text.indexOf("1704067200000");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);

        var actions = myFixture.filterAvailableIntentions("Update schema scale flag to '#ms'");
        assertFalse("Expected update schema quick-fix to be available", actions.isEmpty());

        myFixture.launchAction(actions.get(0));
        assertTrue("Schema definition must now declare #ms",
            myFixture.getFile().getText().contains(":CustomEpoch { #ms } :TimeEpoch"));
    }
}
