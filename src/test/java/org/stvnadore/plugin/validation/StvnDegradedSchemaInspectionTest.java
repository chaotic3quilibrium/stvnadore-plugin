package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class StvnDegradedSchemaInspectionTest extends BasePlatformTestCase {

    public void testInspectionDisabledByDefault() {
        myFixture.enableInspections(new StvnDegradedSchemaInspection());
        myFixture.configureByText("degraded_default.stvn",
            "{\n" +
            "  :defs {\n" +
            "    :BrokenRegex { #regex \"[\" } :String\n" +
            "  }\n" +
            "  :type :BrokenRegex\n" +
            "  :body \"payload_value\"\n" +
            "}"
        );

        var highlights = myFixture.doHighlighting();
        var weakWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WEAK_WARNING)
            .toList();

        assertEquals("Zero weak warnings expected when inspection toggle is false", 0, weakWarnings.size());
    }

    public void testInspectionHighlightsPayloadWhenEnabled() {
        var inspection = new StvnDegradedSchemaInspection();
        inspection.highlightBodyValuesBoundToDegradedSchemas = true;
        myFixture.enableInspections(inspection);

        myFixture.configureByText("degraded_enabled.stvn",
            "{\n" +
            "  :defs {\n" +
            "    :BrokenRegex { #regex \"[\" } :String\n" +
            "  }\n" +
            "  :type :BrokenRegex\n" +
            "  :body \"payload_value\"\n" +
            "}"
        );

        var highlights = myFixture.doHighlighting();
        var weakWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WEAK_WARNING)
            .toList();

        assertEquals("Exactly 1 weak warning expected on body payload when enabled", 1, weakWarnings.size());
        assertTrue(weakWarnings.get(0).getDescription().contains("Value bound to degraded schema ':BrokenRegex'"));
        
        var text = myFixture.getEditor().getDocument().getText();
        var bodyOffset = text.indexOf("\"payload_value\"");
        assertEquals(bodyOffset, weakWarnings.get(0).getStartOffset());
    }
}
