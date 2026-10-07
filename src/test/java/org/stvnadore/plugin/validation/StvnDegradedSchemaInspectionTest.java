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

    public void testDegradedSchemaInspectionStrictTargetAttributionUhohPayloads() {
        var inspection = new StvnDegradedSchemaInspection();
        inspection.highlightBodyValuesBoundToDegradedSchemas = true;
        myFixture.enableInspections(inspection);

        var content = """
            {
              :defs {
                :FloatExactA { #exact } :Float
                :Float32A { #size 32 } :Float
                :FloatExactAToExact { #exact } :FloatExactA
                :FloatExactATo32 { #size 32 } :FloatExactA
              }
              :type :Tuple( :FloatExactA :FloatExactAToExact :FloatExactATo32 )
              :body (
                0.1
                1.23
                -4.56
              )
            }
            """;
        myFixture.configureByText("uhoh_inspection.stvn", content);
        var highlights = myFixture.doHighlighting();

        var weakWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WEAK_WARNING)
            .toList();

        // Must produce exactly 2 weak warnings: for 1.23 and -4.56
        assertEquals("Must produce exactly 2 weak warnings on degraded payload literals", 2, weakWarnings.size());

        // Assert 0.1 has ZERO weak warnings
        var text = myFixture.getEditor().getDocument().getText();
        int offset01 = text.indexOf("0.1");
        int end01 = offset01 + "0.1".length();
        boolean hasWarningOn01 = weakWarnings.stream()
            .anyMatch(w -> w.getStartOffset() >= offset01 && w.getEndOffset() <= end01);
        assertFalse("Clean ancestor literal 0.1 must receive zero degraded schema warnings", hasWarningOn01);

        // Assert 1.23 has degraded schema warning for :FloatExactAToExact
        int offset123 = text.indexOf("1.23");
        var warning123 = weakWarnings.stream()
            .filter(w -> w.getStartOffset() == offset123)
            .findFirst()
            .orElse(null);
        assertNotNull("Literal 1.23 must receive degraded warning", warning123);
        assertNotNull(warning123.getDescription());
        assertTrue(warning123.getDescription().contains("Value bound to degraded schema ':FloatExactAToExact'"));

        // Assert -4.56 has degraded schema warning for :FloatExactATo32
        int offsetNeg456 = text.indexOf("-4.56");
        var warningNeg456 = weakWarnings.stream()
            .filter(w -> w.getStartOffset() == offsetNeg456)
            .findFirst()
            .orElse(null);
        assertNotNull("Literal -4.56 must receive degraded warning", warningNeg456);
        assertNotNull(warningNeg456.getDescription());
        assertTrue(warningNeg456.getDescription().contains("Value bound to degraded schema ':FloatExactATo32'"));
    }
}
