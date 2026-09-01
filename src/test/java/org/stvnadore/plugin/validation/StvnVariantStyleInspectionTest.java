package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.plugin.settings.StvnSettings;

/**
 * Platform integration and unit tests for {@link StvnVariantStyleInspection}.
 * Verifies redundant tag highlighting, quick-fix stripping for algebraic sum types
 * (:Option, :Either, :Union), and form discrepancy inspections.
 */
@NullMarked
public final class StvnVariantStyleInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new StvnVariantStyleInspection());
        var projSettings = StvnProjectSettings.getInstance(getProject());
        if (projSettings != null) {
            projSettings.getState().enableRedundantTagInspection = true;
            projSettings.getState().enableFormDiscrepancyInspection = true;
            projSettings.getState().preferImpliedSumTypes = true;
        }
        var appSettings = StvnSettings.getInstance(getProject());
        if (appSettings != null) {
            appSettings.getState().useLongFormSumTypes = true;
        }
    }

    public void testRedundantUnionTagHighlightingAndQuickFixStringBranch() {
        var text = """
                {
                  :type :Union( :String :Int32 )
                  :body #1 "hello"
                }
                """;
        myFixture.configureByText("union_redundant_str.stvn", text);
        var caretOffset = text.indexOf("#1");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Remove redundant tag");
        assertFalse("Expected 'Remove redundant tag' quick-fix to be available on #1", actions.isEmpty());
        myFixture.launchAction(actions.get(0));
        myFixture.checkResult("""
                {
                  :type :Union( :String :Int32 )
                  :body "hello"
                }
                """);
    }

    public void testRedundantUnionTagHighlightingAndQuickFixIntBranch() {
        var text = """
                {
                  :type :Union( :String :Int32 )
                  :body #2 42
                }
                """;
        myFixture.configureByText("union_redundant_int.stvn", text);
        var caretOffset = text.indexOf("#2");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Remove redundant tag");
        assertFalse("Expected 'Remove redundant tag' quick-fix to be available on #2", actions.isEmpty());
        myFixture.launchAction(actions.get(0));
        myFixture.checkResult("""
                {
                  :type :Union( :String :Int32 )
                  :body 42
                }
                """);
    }

    public void testComprehensiveMultiVariantTupleRedundantTagsAndFormDiscrepancies() {
        var text = """
                {
                  :type :Tuple(
                    :Option( :String )
                    :Either( :String :Int32 )
                    :Union( :String :Int32 )
                    :Boolean
                  )
                  :body (
                    #S "active"
                    #Right 42
                    #1 "payload"
                    #T
                  )
                }
                """;
        myFixture.configureByText("multi_variant_tuple.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .toList();

        assertFalse("Expected style warnings to be present on multi-variant tuple elements", warnings.isEmpty());
        var descriptions = warnings.stream().map(com.intellij.codeInsight.daemon.impl.HighlightInfo::getDescription).toList();
        assertTrue("Expected redundant tag warning", descriptions.contains("Redundant variant tag"));
        assertTrue("Expected form discrepancy warning for #S", descriptions.contains("Use long-form tag '#Some'"));
        assertTrue("Expected form discrepancy warning for #T", descriptions.contains("Use long-form tag '#TRUE'"));
    }

    public void testRedundantTagDisabledWhenSettingFalse() {
        var projSettings = StvnProjectSettings.getInstance(getProject());
        if (projSettings != null) {
            projSettings.getState().preferImpliedSumTypes = false;
        }

        var text = """
                {
                  :type :Union( :String :Int32 )
                  :body #1 "hello"
                }
                """;
        myFixture.configureByText("union_redundant_disabled.stvn", text);
        var highlights = myFixture.doHighlighting();
        var redundantWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && "Redundant variant tag".equals(h.getDescription()))
            .toList();

        assertEquals("Expected zero redundant tag warnings when preferImpliedSumTypes is false", 0, redundantWarnings.size());
    }

    public void testNestedSumRedundantTagHighlightingWithUhohFixture() {
        var text = """
                {
                  :type :Seq(:Option(:Either(:String :Option(:Either(:String :Float)))))
                  :body [
                    #Some #Right #Some #Right 1.234
                    #Right #Some #Right 1.234
                    #Some #Right 1.234
                    #Right 1.234
                    1.234
                  ]
                }
                """;
        myFixture.configureByText("uhoh_redundant_tags.stvn", text);
        var highlights = myFixture.doHighlighting();
        var redundantWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && "Redundant variant tag".equals(h.getDescription()))
            .toList();

        assertEquals("Expected exactly 10 redundant tag warnings across uhoh fixture (4 + 3 + 2 + 1 + 0)", 10, redundantWarnings.size());

        var doc = myFixture.getEditor().getDocument();
        var lineWarnings = new java.util.HashMap<Integer, java.util.List<String>>();
        for (var warning : redundantWarnings) {
            int line = doc.getLineNumber(warning.getStartOffset()) + 1;
            var tagText = doc.getText().substring(warning.getStartOffset(), warning.getEndOffset());
            lineWarnings.computeIfAbsent(line, k -> new java.util.ArrayList<>()).add(tagText);
        }

        // Line 4: 4 yellow squigglies (#Some, #Right, #Some, #Right)
        assertEquals(java.util.List.of("#Some", "#Right", "#Some", "#Right"), lineWarnings.get(4));

        // Line 5: 3 yellow squigglies (#Right, #Some, #Right)
        assertEquals(java.util.List.of("#Right", "#Some", "#Right"), lineWarnings.get(5));

        // Line 6: 2 yellow squigglies (#Some, #Right)
        assertEquals(java.util.List.of("#Some", "#Right"), lineWarnings.get(6));

        // Line 7: 1 yellow squiggly (#Right)
        assertEquals(java.util.List.of("#Right"), lineWarnings.get(7));

        // Line 8: 0 yellow squigglies
        assertNull("Line 8 must have zero warnings", lineWarnings.get(8));
    }

    public void testOverlappingNumericUnionBranchesSuppressRedundantTagWarning() {
        var text = """
                {
                  :defs {
                    :UnionRepeat :Union( :Int32 :String :Uint32 )
                  }
                  :type :UnionRepeat
                  :body #1 1
                }
                """;
        myFixture.configureByText("union_overlap_int_uint.stvn", text);
        var highlights = myFixture.doHighlighting();
        var redundantWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && "Redundant variant tag".equals(h.getDescription()))
            .toList();

        assertEquals("Expected 0 redundant tag warnings because 1 matches both :Int32 and :Uint32", 0, redundantWarnings.size());
        var actions = myFixture.filterAvailableIntentions("Remove redundant tag");
        assertTrue("Expected 'Remove redundant tag' quick-fix to NOT be offered when tag is mandatory", actions.isEmpty());
    }

    public void testDisjointUnionBranchesEmitRedundantTagWarningAndUnwrapQuickFix() {
        var text = """
                {
                  :defs {
                    :DisjointUnion :Union( :Int32 :String :Boolean )
                  }
                  :type :DisjointUnion
                  :body #1 1
                }
                """;
        myFixture.configureByText("union_disjoint_int.stvn", text);
        var caretOffset = text.indexOf("#1");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        var highlights = myFixture.doHighlighting();
        var redundantWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && "Redundant variant tag".equals(h.getDescription()))
            .toList();

        assertEquals("Expected exactly 1 redundant tag warning for disjoint branch #1 1", 1, redundantWarnings.size());

        var actions = myFixture.filterAvailableIntentions("Remove redundant tag");
        assertFalse("Expected 'Remove redundant tag' quick-fix to be available on #1", actions.isEmpty());
        myFixture.launchAction(actions.get(0));
        myFixture.checkResult("""
                {
                  :defs {
                    :DisjointUnion :Union( :Int32 :String :Boolean )
                  }
                  :type :DisjointUnion
                  :body 1
                }
                """);

        // Re-verify that after quick-fix application, 0 warnings and 0 errors exist
        var postHighlights = myFixture.doHighlighting();
        var errors = postHighlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).toList();
        var warnings = postHighlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.WARNING).toList();
        assertEquals("Expected 0 errors after stripping redundant tag", 0, errors.size());
        assertEquals("Expected 0 warnings after stripping redundant tag", 0, warnings.size());
    }

    public void testOverlappingFloatUnionBranchesSuppressRedundantTag() {
        var text = """
                {
                  :defs {
                    :FloatUnion :Union( :Float32 :Float64 )
                  }
                  :type :FloatUnion
                  :body #1 1.234
                }
                """;
        myFixture.configureByText("union_overlap_float.stvn", text);
        var highlights = myFixture.doHighlighting();
        var redundantWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && "Redundant variant tag".equals(h.getDescription()))
            .toList();

        assertEquals("Expected 0 redundant tag warnings because 1.234 matches both :Float32 and :Float64", 0, redundantWarnings.size());
        var actions = myFixture.filterAvailableIntentions("Remove redundant tag");
        assertTrue("Expected 'Remove redundant tag' quick-fix to NOT be offered when tag is mandatory for float union", actions.isEmpty());
    }

    public void testNominalAliasOverlappingUnionBranchesSuppressRedundantTag() {
        var text = """
                {
                  :defs {
                    :UserId :String
                    :UserEmail :String
                    :UserIdentifier :Union( :UserId :UserEmail )
                  }
                  :type :UserIdentifier
                  :body #1 "alice"
                }
                """;
        myFixture.configureByText("union_overlap_nominal.stvn", text);
        var highlights = myFixture.doHighlighting();
        var redundantWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && "Redundant variant tag".equals(h.getDescription()))
            .toList();

        assertEquals("Expected 0 redundant tag warnings for overlapping nominal aliases", 0, redundantWarnings.size());
        var actions = myFixture.filterAvailableIntentions("Remove redundant tag");
        assertTrue("Expected 'Remove redundant tag' quick-fix to NOT be offered when tag is mandatory for nominal aliases", actions.isEmpty());
    }

    public void testMixedDisjointAndOverlappingUnionInTuple() {
        var text = """
                {
                  :defs {
                    :Disjoint :Union( :Int32 :String )
                    :Overlapping :Union( :Int32 :Uint32 )
                  }
                  :type :Tuple( :Disjoint :Overlapping )
                  :body (
                    #1 42
                    #1 42
                  )
                }
                """;
        myFixture.configureByText("tuple_mixed_unions.stvn", text);
        var highlights = myFixture.doHighlighting();
        var redundantWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && "Redundant variant tag".equals(h.getDescription()))
            .toList();

        assertEquals("Expected exactly 1 redundant tag warning (on disjoint element, but not overlapping element)", 1, redundantWarnings.size());
    }

    public void testOverlappingNumericEitherBranchesSuppressRedundantTagWarning() {
        var text = """
                {
                  :defs {
                    :EitherRepeat :Either( :Int32 :Uint32 )
                  }
                  :type :EitherRepeat
                  :body #Left 1
                }
                """;
        myFixture.configureByText("either_overlap_int_uint_left.stvn", text);
        var highlights = myFixture.doHighlighting();
        var redundantWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && "Redundant variant tag".equals(h.getDescription()))
            .toList();

        assertEquals("Expected 0 redundant tag warnings because 1 matches both :Int32 and :Uint32", 0, redundantWarnings.size());
        var actions = myFixture.filterAvailableIntentions("Remove redundant tag");
        assertTrue("Expected 'Remove redundant tag' quick-fix to NOT be offered when tag is mandatory for #Left", actions.isEmpty());

        var rightText = """
                {
                  :defs {
                    :EitherRepeat :Either( :Int32 :Uint32 )
                  }
                  :type :EitherRepeat
                  :body #Right 1
                }
                """;
        myFixture.configureByText("either_overlap_int_uint_right.stvn", rightText);
        var rightHighlights = myFixture.doHighlighting();
        var rightWarnings = rightHighlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && "Redundant variant tag".equals(h.getDescription()))
            .toList();
        assertEquals("Expected 0 redundant tag warnings because 1 matches both :Int32 and :Uint32", 0, rightWarnings.size());
        var rightActions = myFixture.filterAvailableIntentions("Remove redundant tag");
        assertTrue("Expected 'Remove redundant tag' quick-fix to NOT be offered when tag is mandatory for #Right", rightActions.isEmpty());
    }

    public void testOverlappingFloatEitherBranchesSuppressRedundantTag() {
        var text = """
                {
                  :defs {
                    :FloatEither :Either( :Float32 :Float64 )
                  }
                  :type :FloatEither
                  :body #Left 1.2
                }
                """;
        myFixture.configureByText("either_overlap_float_left.stvn", text);
        var highlights = myFixture.doHighlighting();
        var redundantWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && "Redundant variant tag".equals(h.getDescription()))
            .toList();

        assertEquals("Expected 0 redundant tag warnings because #Left is non-inferable", 0, redundantWarnings.size());
        var actions = myFixture.filterAvailableIntentions("Remove redundant tag");
        assertTrue("Expected 'Remove redundant tag' quick-fix to NOT be offered for #Left", actions.isEmpty());

        var rightText = """
                {
                  :defs {
                    :FloatEither :Either( :Float32 :Float64 )
                  }
                  :type :FloatEither
                  :body #Right 1.2
                }
                """;
        myFixture.configureByText("either_overlap_float_right.stvn", rightText);
        var rightHighlights = myFixture.doHighlighting();
        var rightWarnings = rightHighlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && "Redundant variant tag".equals(h.getDescription()))
            .toList();

        assertEquals("Expected 0 redundant tag warnings because 1.2 matches both :Float32 and :Float64", 0, rightWarnings.size());
        var rightActions = myFixture.filterAvailableIntentions("Remove redundant tag");
        assertTrue("Expected 'Remove redundant tag' quick-fix to NOT be offered when tag is mandatory for float #Right", rightActions.isEmpty());
    }

    public void testDisjointEitherLeftBranchRetainsTagUnderRuleE() {
        var text = """
                {
                  :defs {
                    :DisjointEither :Either( :Int32 :String )
                  }
                  :type :DisjointEither
                  :body #Left 1
                }
                """;
        myFixture.configureByText("either_disjoint_int.stvn", text);
        var caretOffset = text.indexOf("#Left");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        var highlights = myFixture.doHighlighting();
        var redundantWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && "Redundant variant tag".equals(h.getDescription()))
            .toList();

        assertEquals("Expected 0 redundant tag warnings because #Left is non-inferable under Rule E", 0, redundantWarnings.size());

        var actions = myFixture.filterAvailableIntentions("Remove redundant tag");
        assertTrue("Expected 'Remove redundant tag' quick-fix to NOT be offered on #Left", actions.isEmpty());
    }

    public void testDisjointEitherRightBranchEmitsRedundantTagWarningAndUnwraps() {
        var text = """
                {
                  :defs {
                    :DisjointEither :Either( :Int32 :String )
                  }
                  :type :DisjointEither
                  :body #Right "text"
                }
                """;
        myFixture.configureByText("either_disjoint_str.stvn", text);
        var caretOffset = text.indexOf("#Right");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        var highlights = myFixture.doHighlighting();
        var redundantWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && "Redundant variant tag".equals(h.getDescription()))
            .toList();

        assertEquals("Expected exactly 1 redundant tag warning for disjoint right branch #Right \"text\"", 1, redundantWarnings.size());

        var actions = myFixture.filterAvailableIntentions("Remove redundant tag");
        assertFalse("Expected 'Remove redundant tag' quick-fix to be available on #Right", actions.isEmpty());
        myFixture.launchAction(actions.get(0));
        myFixture.checkResult("""
                {
                  :defs {
                    :DisjointEither :Either( :Int32 :String )
                  }
                  :type :DisjointEither
                  :body "text"
                }
                """);

        var postHighlights = myFixture.doHighlighting();
        assertEquals("Expected 0 errors after stripping redundant #Right tag", 0, postHighlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).count());
        assertEquals("Expected 0 warnings after stripping redundant #Right tag", 0, postHighlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.WARNING).count());
    }

    public void testMixedDisjointAndOverlappingEitherInTuple() {
        var text = """
                {
                  :defs {
                    :Disjoint :Either( :Int32 :String )
                    :Overlapping :Either( :Int32 :Uint32 )
                  }
                  :type :Tuple( :Disjoint :Overlapping )
                  :body (
                    #Right "hello"
                    #Right 42
                  )
                }
                """;
        myFixture.configureByText("tuple_mixed_eithers.stvn", text);
        var highlights = myFixture.doHighlighting();
        var redundantWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && "Redundant variant tag".equals(h.getDescription()))
            .toList();

        assertEquals("Expected exactly 1 redundant tag warning (on disjoint #Right element, but not overlapping #Right element)", 1, redundantWarnings.size());
    }
}

