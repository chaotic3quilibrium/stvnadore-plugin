package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Platform test suite for {@link StvnDegenerateCompositeInspection}.
 * Verifies detection of degenerate arity-1 composites (:Enum, :Union, :Tuple),
 * multi-arity immunity, and execution of unwrapping quick-fixes.
 */
@NullMarked
public final class StvnDegenerateCompositeInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new StvnDegenerateCompositeInspection());
    }

    public void testSingleVariantEnumWarningAndMultiVariantImmunity() {
        // 1. Single-variant enum emits warning
        myFixture.configureByText("enum_single.stvn",
            """
            {
              :defs {
                :Mode :Enum [ #Active ]
              }
              :type :Mode
              :body #Active
            }
            """
        );
        var highlightsSingle = myFixture.doHighlighting();
        var warningsSingle = highlightsSingle.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .toList();
        assertEquals("Expected 1 warning for single-variant enum", 1, warningsSingle.size());
        assertTrue(warningsSingle.get(0).getDescription().contains("Degenerate 1-variant enum: ':Enum [ #Active ]' provides zero branching entropy"));

        // 2. Multi-variant enum emits zero warnings
        myFixture.configureByText("enum_multi.stvn",
            """
            {
              :defs {
                :Mode :Enum [ #Active #Inactive ]
              }
              :type :Mode
              :body #Active
            }
            """
        );
        var highlightsMulti = myFixture.doHighlighting();
        var warningsMulti = highlightsMulti.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .toList();
        assertEquals("Expected 0 warnings for multi-variant enum", 0, warningsMulti.size());
    }

    public void testSingleBranchUnionDetectionAndUnwrapQuickFix() {
        var beforeText = """
            {
              :defs {
                :UnwrappedField :Union( :String )
              }
              :type :UnwrappedField
              :body "data"
            }
            """;
        myFixture.configureByText("union_single.stvn", beforeText);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .toList();
        assertEquals("Expected 1 warning for single-branch union", 1, warnings.size());
        assertTrue(warnings.get(0).getDescription().contains("Degenerate 1-branch union: ':Union( :String )' is isomorphic to its inner type"));

        // Move caret to :Union token and launch quick-fix
        var offset = beforeText.indexOf(":Union");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Unwrap degenerate composite");
        assertFalse("Expected 'Unwrap degenerate composite' quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :UnwrappedField :String
              }
              :type :UnwrappedField
              :body "data"
            }
            """);

        // Verify multi-branch union emits 0 warnings
        myFixture.configureByText("union_multi.stvn",
            """
            {
              :type :Union( :String :Int32 )
              :body #1 "data"
            }
            """
        );
        var highlightsMulti = myFixture.doHighlighting();
        var warningsMulti = highlightsMulti.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .toList();
        assertEquals("Expected 0 warnings for multi-branch union", 0, warningsMulti.size());
    }

    public void testSingleElementTupleDetectionAndUnwrapQuickFix() {
        var beforeText = """
            {
              :type :Tuple( :Int32 )
              :body ( 42 )
            }
            """;
        myFixture.configureByText("tuple_single.stvn", beforeText);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .toList();
        assertEquals("Expected 1 warning for single-element tuple", 1, warnings.size());
        assertTrue(warnings.get(0).getDescription().contains("Degenerate 1-element tuple: ':Tuple( :Int32 )' provides redundant container wrapping"));

        // Launch quick-fix
        var offset = beforeText.indexOf(":Tuple");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Unwrap degenerate composite");
        assertFalse("Expected unwrap quick-fix for tuple", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :type :Int32
              :body ( 42 )
            }
            """);

        // Verify multi-element tuple emits 0 warnings
        myFixture.configureByText("tuple_multi.stvn",
            """
            {
              :type :Tuple( :Int32 :String )
              :body ( 42 "alpha" )
            }
            """
        );
        var highlightsMulti = myFixture.doHighlighting();
        var warningsMulti = highlightsMulti.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .toList();
        assertEquals("Expected 0 warnings for multi-element tuple", 0, warningsMulti.size());
    }

    public void testNestedDegenerateCompositesInDefsAndCollections() {
        myFixture.configureByText("nested_composites.stvn",
            """
            {
              :defs {
                :NestedMap :Map( :String :Tuple( :Int32 ) )
                :NestedSeq :Seq( :Union( :Boolean ) )
              }
              :type :Tuple( :NestedMap :NestedSeq )
              :body ( {} [] )
            }
            """
        );
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .toList();
        assertEquals("Expected 2 warnings for nested degenerate composites in map and seq", 2, warnings.size());
    }
}
