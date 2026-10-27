package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Platform test suite validating {@link StvnConstantRangeInspection} for integer and floating-point constants.
 */
@NullMarked
public final class StvnConstantRangeInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new InspectionProfileEntry[]{new StvnConstantRangeInspection()});
    }

    public void testFloat32ConstantOverflow() {
        var text = """
            {
              :defs {
                #OVERFLOW { #size 32 } :Float 3.5e38
                #VALID { #size 32 } :Float 3.4e38
              }
              :type :String
              :body "ok"
            }
            """;
        myFixture.configureByText("float32_const.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_CONSTANT_FLOAT_OVERFLOW"))
            .toList();

        assertEquals("Expected 1 float overflow error for 3.5e38", 1, errors.size());
        assertTrue(errors.get(0).getDescription().contains("Float literal 3.5e38 out of range for { #size 32 } :Float"));
    }

    public void testFloat64ConstantExtremeExponentWithoutCrash() {
        var text = """
            {
              :defs {
                #OVERFLOW_POS :Float 1.0e400
                #OVERFLOW_NEG :Float -1.0e400
                #VALID :Float 1.0e100
              }
              :type :String
              :body "ok"
            }
            """;
        myFixture.configureByText("float64_const.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_CONSTANT_FLOAT_OVERFLOW"))
            .toList();

        assertEquals("Expected 2 float overflow errors for +/- 1.0e400", 2, errors.size());
        assertTrue(errors.stream().anyMatch(e -> e.getDescription().contains("Float literal 1.0e400 out of range for :Float")));
        assertTrue(errors.stream().anyMatch(e -> e.getDescription().contains("Float literal -1.0e400 out of range for :Float")));
    }

    public void testExactFloatConstantBypassesContinuousBounds() {
        var text = """
            {
              :defs {
                #EXACT_HUGE { #exact } :Float 1.0e400
              }
              :type :String
              :body "ok"
            }
            """;
        myFixture.configureByText("exact_huge.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_CONSTANT_FLOAT_OVERFLOW"))
            .toList();

        assertTrue("Exact float constants have arbitrary capacity and must not trigger ERR_CONSTANT_FLOAT_OVERFLOW", errors.isEmpty());
    }
}
