package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Platform integration test suite for StvnExcessiveExactFloatPrecisionInspection.
 * <p>
 * Validates TC-PREC-01 through TC-PREC-07:
 * <ul>
 *   <li>TC-PREC-01: Clean compilation on valid sizing (#maxSize 18, 38).</li>
 *   <li>TC-PREC-02: Warning on threshold overflow (#maxSize 39, 100, 10000).</li>
 *   <li>TC-PREC-03: Suppression of warning on tautology ceiling (#maxSize 16777216 -> Core E036).</li>
 *   <li>TC-PREC-04: Automated clamp Quick-Fix execution.</li>
 *   <li>TC-PREC-04B: Automated bound Quick-Fix execution.</li>
 *   <li>TC-PREC-05: Continuous :Float isolation (governed strictly by Core Rule E008).</li>
 *   <li>TC-PREC-06: Transitive exact float derivation warning.</li>
 *   <li>TC-PREC-07: String and collection isolation.</li>
 * </ul>
 */
public final class StvnExcessiveExactFloatPrecisionInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new StvnExcessiveExactFloatPrecisionInspection());
    }

    /**
     * TC-PREC-01 (Clean Valid Sizing):
     * Assert that { #exact #maxSize 18 } :Float and { #exact #maxSize 38 } :Float compile cleanly
     * with 0 warnings and 0 errors.
     */
    public void testCleanValidExactFloatSizing() {
        var text = """
            {
              :defs {
                :Fine18 { #exact #maxSize 18 } :Float
                :Fine38 { #exact #maxSize 38 } :Float
              }
              :type :Fine38
              :body 123.456
            }
            """;
        myFixture.configureByText("clean_exact_float.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_EXCESSIVE_EXACT_PRECISION"))
            .toList();
        assertTrue("Expected 0 excessive precision warnings for precision <= 38, but found: " + warnings, warnings.isEmpty());
    }

    /**
     * TC-PREC-02 (Threshold Warning):
     * Assert that #maxSize 39, 100, and 10000 produce warnings pinned strictly to '#maxSize <N>'.
     */
    public void testExactFloatThresholdWarning() {
        var text = """
            {
              :defs {
                :Warn39 { #exact #maxSize 39 } :Float
                :Warn100 { #exact #maxSize 100 } :Float
                :Warn10000 { #exact #maxSize 10000 } :Float
              }
              :type :Warn39
              :body 1.0
            }
            """;
        myFixture.configureByText("warn_exact_float.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_EXCESSIVE_EXACT_PRECISION"))
            .toList();

        assertEquals("Expected exactly 3 excessive precision warnings", 3, warnings.size());

        // Verify pinning coordinates for #maxSize 39
        var w39 = warnings.get(0);
        int expectedStart39 = text.indexOf("#maxSize 39");
        int expectedEnd39 = expectedStart39 + "#maxSize 39".length();
        assertEquals(expectedStart39, w39.getStartOffset());
        assertEquals(expectedEnd39, w39.getEndOffset());

        // Verify pinning coordinates for #maxSize 100
        var w100 = warnings.get(1);
        int expectedStart100 = text.indexOf("#maxSize 100");
        int expectedEnd100 = expectedStart100 + "#maxSize 100".length();
        assertEquals(expectedStart100, w100.getStartOffset());
        assertEquals(expectedEnd100, w100.getEndOffset());

        // Verify pinning coordinates for #maxSize 10000
        var w10000 = warnings.get(2);
        int expectedStart10000 = text.indexOf("#maxSize 10000");
        int expectedEnd10000 = expectedStart10000 + "#maxSize 10000".length();
        assertEquals(expectedStart10000, w10000.getStartOffset());
        assertEquals(expectedEnd10000, w10000.getEndOffset());
    }

    /**
     * TC-PREC-03 (Tautology Collision Suppression):
     * Assert that { #exact #maxSize 16777216 } :Float emits strictly fatal compiler error E036,
     * cleanly suppressing the precision warning.
     */
    public void testTautologyCollisionSuppression() {
        var text = """
            {
              :defs {
                :TautologyFloat { #exact #maxSize 16777216 } :Float
              }
              :type :TautologyFloat
              :body 1.0
            }
            """;
        myFixture.configureByText("tautology_exact_float.stvn", text);
        var highlights = myFixture.doHighlighting();

        // 1. Assert excessive precision warning is suppressed
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_EXCESSIVE_EXACT_PRECISION"))
            .toList();
        assertTrue("Warning must be suppressed on default ceiling 16777216: " + warnings, warnings.isEmpty());

        // 2. Assert fatal Core Rule E036 error is present
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("facet '#maxSize 16777216' is redundant"))
            .toList();
        assertFalse("Expected fatal Core Rule E036 error for #maxSize 16777216", errors.isEmpty());
    }

    /**
     * TC-PREC-04 (Quick-Fix Verification):
     * Assert that applying the clamp quick-fix cleanly mutates { #exact #maxSize 1000 } :Float
     * to { #exact #maxSize 38 } :Float.
     */
    public void testClampQuickFixVerification() {
        var text = """
            {
              :defs {
                :FixTarget { #exact #maxSize 1000 } :Float
              }
              :type :FixTarget
              :body 1.0
            }
            """;
        myFixture.configureByText("clamp_quick_fix.stvn", text);
        myFixture.doHighlighting();

        int offset = text.indexOf("#maxSize 1000");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);

        var actions = myFixture.filterAvailableIntentions("Clamp #maxSize to 38 (Standard Financial / Decimal128 Precision)");
        assertFalse("Expected clamp Quick-Fix to be available", actions.isEmpty());

        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Quick-fix must mutate to '{ #exact #maxSize 38 } :Float':\n" + result,
            result.contains(":FixTarget { #exact #maxSize 38 } :Float"));
        assertFalse("Result must not contain '#maxSize 1000'", result.contains("#maxSize 1000"));
    }

    /**
     * TC-PREC-04B (Convert to Numeric Bound Quick-Fix Verification):
     * Assert that applying the convert quick-fix cleanly mutates { #exact #maxSize 1000 } :Float
     * to { #exact #maxExcl 1000.0 } :Float.
     */
    public void testConvertToNumericBoundQuickFixVerification() {
        var text = """
            {
              :defs {
                :BoundTarget { #exact #maxSize 1000 } :Float
              }
              :type :BoundTarget
              :body 1.0
            }
            """;
        myFixture.configureByText("bound_quick_fix.stvn", text);
        myFixture.doHighlighting();

        int offset = text.indexOf("#maxSize 1000");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);

        var actions = myFixture.filterAvailableIntentions("Convert to numeric bound '#maxExcl 1000.0'");
        assertFalse("Expected convert to numeric bound Quick-Fix to be available", actions.isEmpty());

        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Quick-fix must mutate to '{ #exact #maxExcl 1000.0 } :Float':\n" + result,
            result.contains(":BoundTarget { #exact #maxExcl 1000.0 } :Float"));
        assertFalse("Result must not contain '#maxSize 1000'", result.contains("#maxSize 1000"));
    }

    /**
     * TC-PREC-05 (Continuous Float Isolation):
     * Assert that continuous :Float is unaffected by this inspection (governed strictly by Core Rule E008).
     */
    public void testContinuousFloatIsolation() {
        var text = """
            {
              :defs {
                :ContinuousFloat { #maxSize 100 } :Float
              }
              :type :ContinuousFloat
              :body 1.0
            }
            """;
        myFixture.configureByText("continuous_float.stvn", text);
        var highlights = myFixture.doHighlighting();

        // 1. Precision warning must NOT be emitted on continuous float
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_EXCESSIVE_EXACT_PRECISION"))
            .toList();
        assertTrue("Precision warning must not trigger on continuous float: " + warnings, warnings.isEmpty());

        // 2. Fatal Core Rule E008 must be present
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("prohibited on continuous ':Float'"))
            .toList();
        assertFalse("Expected fatal Core Rule E008 error on continuous float", errors.isEmpty());
    }

    /**
     * TC-PREC-06 (Transitive Exact Float Sizing):
     * Assert that derived nominal types inherit #exact and trigger the warning.
     */
    public void testTransitiveExactFloatWarning() {
        var text = """
            {
              :defs {
                :BaseExact { #exact } :Float
                :DerivedHigh { #maxSize 100 } :BaseExact
              }
              :type :DerivedHigh
              :body 1.0
            }
            """;
        myFixture.configureByText("transitive_exact_float.stvn", text);
        var highlights = myFixture.doHighlighting();

        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_EXCESSIVE_EXACT_PRECISION"))
            .toList();
        assertEquals("Expected 1 warning on transitive exact float", 1, warnings.size());
    }

    /**
     * TC-PREC-07 (String and Collection Isolation):
     * Assert that string and collection types with #maxSize are not flagged by this inspection.
     */
    public void testStringAndCollectionIsolation() {
        var text = """
            {
              :defs {
                :MyString { #maxSize 100 } :String
                :MySeq { #maxSize 100 } :Seq( :Int )
              }
              :type :MyString
              :body "hello"
            }
            """;
        myFixture.configureByText("string_collection_isolation.stvn", text);
        var highlights = myFixture.doHighlighting();

        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_EXCESSIVE_EXACT_PRECISION"))
            .toList();
        assertTrue("String and collection types must not trigger exact float precision warning", warnings.isEmpty());
    }
}
