package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Platform integration test suite for StvnFloatPrecisionLossInspection.
 * <p>
 * Validates TC-FPL-01 through TC-FPL-09:
 * <ul>
 *   <li>TC-FPL-01: Clean compilation on 32-bit float with &le; 8 digits.</li>
 *   <li>TC-FPL-02: Warning on 32-bit float with &gt; 8 digits.</li>
 *   <li>TC-FPL-03: Clean compilation on 64-bit float with &le; 17 digits.</li>
 *   <li>TC-FPL-04: Warning on 64-bit float with &gt; 17 digits.</li>
 *   <li>TC-FPL-05: Discrete exact float { #exact } :Float bypass.</li>
 *   <li>TC-FPL-06: Automated Quick-Fix 1 (Round to nearest IEEE-754).</li>
 *   <li>TC-FPL-07: Automated Quick-Fix 2 (Switch variant to exact float branch).</li>
 *   <li>TC-FPL-08: Quick-Fix 2 suppression when no exact sibling branch exists.</li>
 *   <li>TC-FPL-09: Self-equivalence convergence guard on literals matching binary encoding.</li>
 * </ul>
 */
public final class StvnFloatPrecisionLossInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new StvnFloatPrecisionLossInspection());
    }

    /**
     * TC-FPL-01: Continuous 32-bit float with &le; 8 digits produces 0 precision loss warnings.
     */
    public void testClean32BitFloat() {
        var text = """
            {
              :defs {
                :Weight { #size 32 } :Float
              }
              :type :Weight
              :body 42.123455
            }
            """;
        myFixture.configureByText("clean_32bit.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_FLOAT_PRECISION_LOSS"))
            .toList();
        assertTrue("Expected 0 precision loss warnings for 8 digits, but found: " + warnings, warnings.isEmpty());
    }

    /**
     * TC-FPL-02: Continuous 32-bit float with &gt; 8 digits emits warning pinned to FloatLiteral.
     */
    public void testPrecisionLossOn32BitFloat() {
        var literal = "42.1234567890123456789";
        var text = """
            {
              :defs {
                :Weight { #size 32 } :Float
              }
              :type :Weight
              :body %s
            }
            """.formatted(literal);
        myFixture.configureByText("warn_32bit.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_FLOAT_PRECISION_LOSS"))
            .toList();

        assertEquals("Expected exactly 1 precision loss warning", 1, warnings.size());
        var warn = warnings.get(0);
        assertTrue("Warning message must mention Single (32-bit) capacity (~8 digits)",
            warn.getDescription().contains("Single (32-bit) capacity (~8 digits)"));
        assertTrue("Warning message must mention binary encoding truncation",
            warn.getDescription().contains("binary encoding truncates value to 42.123455"));

        int expectedStart = text.indexOf(literal);
        int expectedEnd = expectedStart + literal.length();
        assertEquals(expectedStart, warn.getStartOffset());
        assertEquals(expectedEnd, warn.getEndOffset());
    }

    /**
     * TC-FPL-03: Continuous 64-bit float with &le; 17 digits produces 0 precision loss warnings.
     */
    public void testClean64BitFloat() {
        var text = """
            {
              :defs {
                :Coord :Float
              }
              :type :Coord
              :body 3.141592653589793
            }
            """;
        myFixture.configureByText("clean_64bit.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_FLOAT_PRECISION_LOSS"))
            .toList();
        assertTrue("Expected 0 precision loss warnings for 16 digits, but found: " + warnings, warnings.isEmpty());
    }

    /**
     * TC-FPL-04: Continuous 64-bit float with &gt; 17 digits emits warning pinned to FloatLiteral.
     */
    public void testPrecisionLossOn64BitFloat() {
        var fiftyDigits = "3.14159265358979323846264338327950288419716939937510";
        var text = """
            {
              :defs {
                :Coord :Float
              }
              :type :Coord
              :body %s
            }
            """.formatted(fiftyDigits);
        myFixture.configureByText("warn_64bit.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_FLOAT_PRECISION_LOSS"))
            .toList();

        assertEquals("Expected exactly 1 precision loss warning", 1, warnings.size());
        var warn = warnings.get(0);
        assertTrue("Warning message must mention Double (64-bit) capacity (~16 digits)",
            warn.getDescription().contains("Double (64-bit) capacity (~16 digits)"));
        assertTrue("Warning message must mention binary encoding truncation",
            warn.getDescription().contains("binary encoding truncates value to 3.141592653589793"));

        int expectedStart = text.indexOf(fiftyDigits);
        int expectedEnd = expectedStart + fiftyDigits.length();
        assertEquals(expectedStart, warn.getStartOffset());
        assertEquals(expectedEnd, warn.getEndOffset());
    }

    /**
     * TC-FPL-05: Discrete exact float ({ #exact } :Float) is completely bypassed.
     */
    public void testDiscreteExactFloatBypass() {
        var fiftyDigits = "3.14159265358979323846264338327950288419716939937510";
        var text = """
            {
              :defs {
                :Precise { #exact } :Float
              }
              :type :Precise
              :body %s
            }
            """.formatted(fiftyDigits);
        myFixture.configureByText("exact_float_bypass.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_FLOAT_PRECISION_LOSS"))
            .toList();
        assertTrue("Exact float must be completely bypassed by precision loss inspection: " + warnings, warnings.isEmpty());
    }

    /**
     * TC-FPL-06: Quick-Fix 1 replaces authored literal with nearest IEEE-754 representation.
     */
    public void testQuickFix1Rounding() {
        var literal = "42.1234567890123456789";
        var text = """
            {
              :defs {
                :Weight { #size 32 } :Float
              }
              :type :Weight
              :body %s
            }
            """.formatted(literal);
        myFixture.configureByText("fix1_round.stvn", text);
        myFixture.doHighlighting();

        int offset = text.indexOf(literal);
        myFixture.getEditor().getCaretModel().moveToOffset(offset);

        var actions = myFixture.filterAvailableIntentions("Round literal to nearest IEEE-754 representation (42.123455)");
        assertFalse("Expected Round Quick-Fix to be available", actions.isEmpty());

        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("File text must be replaced with rounded 42.123455:\n" + result,
            result.contains(":body 42.123455"));
        assertFalse("Old 20-digit literal must be replaced", result.contains(literal));

        var postHighlights = myFixture.doHighlighting();
        var postWarnings = postHighlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_FLOAT_PRECISION_LOSS"))
            .toList();
        assertTrue("Post-fix highlighting must converge to 0 precision loss warnings, but found: " + postWarnings, postWarnings.isEmpty());
    }

    /**
     * TC-FPL-07: Quick-Fix 2 switches variant to exact float sibling branch.
     */
    public void testQuickFix2SwitchVariant() {
        var literal = "42.1234567890123456789";
        var text = """
            {
              :defs {
                :Measurement :Union( { #size 32 } :Float :Float { #exact } :Float )
              }
              :type :Measurement
              :body #1 %s
            }
            """.formatted(literal);
        myFixture.configureByText("fix2_switch.stvn", text);
        myFixture.doHighlighting();

        int offset = text.indexOf(literal);
        myFixture.getEditor().getCaretModel().moveToOffset(offset);

        var actions = myFixture.filterAvailableIntentions("Switch variant to exact float branch (#3)");
        assertFalse("Expected Switch Variant Quick-Fix to be available", actions.isEmpty());

        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Variant tag must be mutated to #3:\n" + result,
            result.contains(":body #3 " + literal));
        assertFalse("Variant tag must not remain #1", result.contains(":body #1 "));
    }

    /**
     * TC-FPL-08: Quick-Fix 2 is suppressed when union has no exact sibling branch.
     */
    public void testQuickFix2SuppressionWhenNoExactBranch() {
        var literal = "42.1234567890123456789";
        var text = """
            {
              :defs {
                :Measurement :Union( { #size 32 } :Float :Float )
              }
              :type :Measurement
              :body #1 %s
            }
            """.formatted(literal);
        myFixture.configureByText("no_fix2.stvn", text);
        myFixture.doHighlighting();

        int offset = text.indexOf(literal);
        myFixture.getEditor().getCaretModel().moveToOffset(offset);

        var switchActions = myFixture.filterAvailableIntentions("Switch variant to exact float branch");
        assertTrue("Switch variant quick-fix must not be offered when no exact branch exists", switchActions.isEmpty());

        var roundActions = myFixture.filterAvailableIntentions("Round literal to nearest IEEE-754 representation");
        assertFalse("Round literal quick-fix must still be offered", roundActions.isEmpty());
    }

    /**
     * TC-FPL-09: Self-equivalence convergence guard suppresses warnings when literal matches binary encoding.
     */
    public void testSelfEquivalenceConvergenceGuard() {
        var text = """
            {
              :defs {
                :Weight { #size 32 } :Float
              }
              :type :Weight
              :body 42.1234550000000000
            }
            """;
        myFixture.configureByText("self_equiv.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_FLOAT_PRECISION_LOSS"))
            .toList();
        assertTrue("Self-equivalent literal must produce 0 warnings: " + warnings, warnings.isEmpty());
    }
}
