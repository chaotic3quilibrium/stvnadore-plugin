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

    public void testExactFloatWithEquatableTrueEmitsRedundantWarning() {
        var text = """
            {
              :defs {
                :FloatExactACorrect { #exact #equatable #TRUE } :Float
              }
              :type :FloatExactACorrect
              :body 1.0
            }
            """;
        myFixture.configureByText("exact_float_equatable_true.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Redundant facet override"))
            .toList();
        assertEquals("Expected exactly 1 warning on redundant facet override '#equatable #TRUE'", 1, warnings.size());
        assertTrue("Warning message must mention effective value '#TRUE'",
            warnings.get(0).getDescription().contains("'#TRUE'"));

        int offset = text.indexOf("#equatable #TRUE");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Remove redundant facet");
        assertFalse("Expected quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Applying quick-fix must preserve #exact in metadata map:\n" + result,
            result.contains(":FloatExactACorrect { #exact } :Float"));
        assertFalse("Result must not contain #equatable", result.contains("#equatable"));
    }

    public void testExactFloatWithEquatableFalseProducesZeroWarnings() {
        var text = """
            {
              :defs {
                :FloatExactAOverride { #exact #equatable #FALSE } :Float
              }
              :type :FloatExactAOverride
              :body 1.0
            }
            """;
        myFixture.configureByText("exact_float_equatable_false.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Redundant facet override"))
            .toList();
        assertTrue("Active intentional override {#exact #equatable #FALSE} must produce zero warnings", warnings.isEmpty());
    }

    public void testContinuousFloatWithEquatableTrueProducesZeroWarnings() {
        var text = """
            {
              :defs {
                :Float32A { #size 32 #equatable #TRUE } :Float
              }
              :type :Float32A
              :body 1.0
            }
            """;
        myFixture.configureByText("continuous_float_equatable_true.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Redundant facet override"))
            .toList();
        assertTrue("Declaring {#size 32 #equatable #TRUE} on :Float is an active override and must produce zero warnings", warnings.isEmpty());
    }

    public void testContinuousFloatWithEquatableFalseEmitsRedundantWarning() {
        var text = """
            {
              :defs {
                :Float32Redundant { #size 32 #equatable #FALSE } :Float
              }
              :type :Float32Redundant
              :body 1.0
            }
            """;
        myFixture.configureByText("continuous_float_equatable_false.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Redundant facet override"))
            .toList();
        assertEquals("Expected exactly 1 warning on redundant continuous float equatable false", 1, warnings.size());
        assertTrue("Warning message must mention effective value '#FALSE'",
            warnings.get(0).getDescription().contains("'#FALSE'"));
    }

    public void testTupleAllEquatableFieldsWithEquatableFalseProducesZeroWarnings() {
        var text = """
            {
              :defs {
                :FloatExactACorrect { #exact } :Float
                :Float32A { #size 32 #equatable #TRUE } :Float
                :Float64A { #size 64 #equatable #TRUE } :Float
                :Values { #equatable #FALSE } :Tuple(:FloatExactACorrect :Float32A :Float64A)
              }
              :type :Values
              :body (1.0 2.0 3.0)
            }
            """;
        myFixture.configureByText("tuple_all_equatable_false.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Redundant facet override"))
            .toList();
        assertTrue("Active intentional override {#equatable #FALSE} on naturally equatable tuple must produce zero warnings", warnings.isEmpty());
    }

    public void testTupleAllEquatableFieldsWithEquatableTrueEmitsRedundantWarning() {
        var text = """
            {
              :defs {
                :FloatExactACorrect { #exact } :Float
                :Float32A { #size 32 #equatable #TRUE } :Float
                :Float64A { #size 64 #equatable #TRUE } :Float
                :Values { #equatable #TRUE } :Tuple(:FloatExactACorrect :Float32A :Float64A)
              }
              :type :Values
              :body (1.0 2.0 3.0)
            }
            """;
        myFixture.configureByText("tuple_all_equatable_true.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Redundant facet override"))
            .toList();
        assertEquals("Expected exactly 1 warning on redundant tuple equatable true", 1, warnings.size());
        assertTrue("Warning message must mention effective value '#TRUE'",
            warnings.get(0).getDescription().contains("'#TRUE'"));

        int offset = text.indexOf("#equatable #TRUE } :Tuple");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Remove redundant facet");
        assertFalse("Expected quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Applying quick-fix must remove single-facet map entirely:\n" + result,
            result.contains(":Values :Tuple(:FloatExactACorrect :Float32A :Float64A)"));
        assertFalse("Result must not contain metadata map on :Values", result.contains(":Values {"));
    }

    public void testTupleWithNonEquatableFieldAndEquatableTrueProducesZeroWarnings() {
        var text = """
            {
              :defs {
                :FloatExactACorrect { #exact } :Float
                :FloatContinuous { #size 32 } :Float
                :Values { #equatable #TRUE } :Tuple(:FloatExactACorrect :FloatContinuous)
              }
              :type :Values
              :body (1.0 2.0)
            }
            """;
        myFixture.configureByText("tuple_non_equatable_field_equatable_true.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Redundant facet override"))
            .toList();
        assertTrue("Active override restoring capability to tuple containing continuous float must produce zero warnings", warnings.isEmpty());
    }

    public void testFloatPlainEquatableTrueProducesZeroWarnings() {
        var text = """
            {
              :defs {
                :FloatPlain { #equatable #TRUE } :Float
              }
              :type :FloatPlain
              :body 1.0
            }
            """;
        myFixture.configureByText("float_plain_equatable.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Redundant facet override"))
            .toList();
        assertTrue("Declaring {#equatable #TRUE} on unadorned :Float is an active override and must produce zero warnings", warnings.isEmpty());
    }

    public void testTupleEquatableTrueProducesZeroWarnings() {
        var text = """
            {
              :defs {
                :MyTuple { #equatable #TRUE } :Tuple(:Int :Float)
              }
              :type :MyTuple
              :body (42 1.0)
            }
            """;
        myFixture.configureByText("tuple_equatable.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Redundant facet override"))
            .toList();
        assertTrue("Declaring {#equatable #TRUE} on :Tuple must produce zero redundant warnings", warnings.isEmpty());
    }
}
