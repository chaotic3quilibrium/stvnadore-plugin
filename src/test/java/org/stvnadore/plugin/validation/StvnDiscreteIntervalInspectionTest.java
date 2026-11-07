package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Automated platform test suite validating {@link StvnDiscreteIntervalInspection}.
 * Verifies half-open interval governance [minIncl, maxExcl) on discrete types (:Int and { #exact } :Float)
 * and in-place swapping of inverted bounds via {@link StvnDiscreteIntervalInspection.SwapInvertedBoundsQuickFix}.
 */
@NullMarked
public final class StvnDiscreteIntervalInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new InspectionProfileEntry[]{new StvnDiscreteIntervalInspection()});
    }

    public void testMaxInclOnIntTriggersErrorAndQuickFix() {
        var text = """
            {
              :defs {
                :Port { #minIncl 1 #maxIncl 65535 } :Int
              }
              :type :Port
              :body 8080
            }
            """;
        myFixture.configureByText("discrete_max_incl.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("reject closed upper bound '#maxIncl'"))
            .toList();
        assertFalse("Expected discrete interval error for #maxIncl on :Int", errors.isEmpty());

        int offset = text.indexOf("#maxIncl");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Convert '#maxIncl <N>' to '#maxExcl <N+1>'");
        assertFalse("Expected #maxIncl conversion quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Expected #maxExcl 65536 in converted text", result.contains("#maxExcl 65536"));
        assertFalse("Converted text must not contain #maxIncl", result.contains("#maxIncl"));
    }

    public void testMinExclOnIntTriggersErrorAndQuickFix() {
        var text = """
            {
              :defs {
                :PositiveInt { #minExcl 0 #maxExcl 100 } :Int
              }
              :type :PositiveInt
              :body 50
            }
            """;
        myFixture.configureByText("discrete_min_excl.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("reject open lower bound '#minExcl'"))
            .toList();
        assertFalse("Expected discrete interval error for #minExcl on :Int", errors.isEmpty());

        int offset = text.indexOf("#minExcl");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Convert '#minExcl <N>' to '#minIncl <N+1>'");
        assertFalse("Expected #minExcl conversion quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Expected #minIncl 1 in converted text", result.contains("#minIncl 1"));
        assertFalse("Converted text must not contain #minExcl", result.contains("#minExcl"));
    }

    public void testExactFloatRejectsMaxInclAndMinExcl() {
        var text = """
            {
              :defs {
                :ExactCurrency { #exact #minExcl 0 #maxIncl 1000 } :Float
              }
              :type :ExactCurrency
              :body 100.00
            }
            """;
        myFixture.configureByText("exact_float_bounds.stvn", text);
        var highlights = myFixture.doHighlighting();
        var intervalErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && (h.getDescription().contains("reject closed upper bound") || h.getDescription().contains("reject open lower bound")))
            .toList();
        assertEquals("Expected 2 discrete bound errors on exact float", 2, intervalErrors.size());
    }

    public void testContinuousFloatPermitsClosedUpperAndOpenLowerBounds() {
        var text = """
            {
              :defs {
                :ContinuousRange { #minExcl 0.0 #maxIncl 1.0 } :Float
              }
              :type :ContinuousRange
              :body 0.5
            }
            """;
        myFixture.configureByText("continuous_float.stvn", text);
        var highlights = myFixture.doHighlighting();
        var intervalErrors = highlights.stream()
            .filter(h -> h.getDescription() != null && (h.getDescription().contains("reject closed upper bound") || h.getDescription().contains("reject open lower bound")))
            .toList();
        assertTrue("Continuous float must permit #minExcl and #maxIncl", intervalErrors.isEmpty());
    }

    public void testTimeEpochRejectsMaxInclAndMinExcl() {
        var text = """
            {
              :defs {
                :BadEpoch { #s #minExcl 0 #maxIncl 1000 } :TimeEpoch
              }
              :type :BadEpoch
              :body 500
            }
            """;
        myFixture.configureByText("epoch_bounds.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_DISCRETE_BOUND_KIND_PROHIBITED"))
            .toList();
        assertEquals("Expected 2 discrete bound errors on :TimeEpoch", 2, errors.size());

        int offset = text.indexOf("#maxIncl");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Convert '#maxIncl <N>' to '#maxExcl <N+1>'");
        assertFalse("Expected #maxIncl conversion quick-fix to be available on :TimeEpoch", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Expected #maxExcl 1001 in converted text", result.contains("#maxExcl 1001"));
    }

    public void testDateTimeRejectsMaxInclAndMinExcl() {
        var text = """
            {
              :defs {
                :BadDate { #offset #minExcl "2026-01-01T00:00:00Z" #maxIncl "2026-12-31T23:59:59Z" } :DateTime
              }
              :type :BadDate
              :body "2026-06-01T12:00:00Z"
            }
            """;
        myFixture.configureByText("datetime_bounds.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_DISCRETE_BOUND_KIND_PROHIBITED"))
            .toList();
        assertEquals("Expected 2 discrete bound errors on :DateTime", 2, errors.size());
    }

    public void testValidDiscreteBoundsAcceptedOnTimeEpoch() {
        var text = """
            {
              :defs {
                :ValidEpoch { #s #minIncl 0 #maxExcl 100 } :TimeEpoch
              }
              :type :ValidEpoch
              :body 50
            }
            """;
        myFixture.configureByText("valid_epoch.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_DISCRETE_BOUND_KIND_PROHIBITED"))
            .toList();
        assertTrue("{ #s #minIncl 0 #maxExcl 100 } on :TimeEpoch must produce zero discrete interval errors", errors.isEmpty());
    }

    public void testValidDiscreteBoundsAcceptedOnDateTime() {
        var text = """
            {
              :defs {
                :ValidDateTime { #offset #minIncl "2026-01-01T00:00:00Z" #maxExcl "2027-01-01T00:00:00Z" } :DateTime
              }
              :type :ValidDateTime
              :body "2026-06-01T12:00:00Z"
            }
            """;
        myFixture.configureByText("valid_datetime.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_DISCRETE_BOUND_KIND_PROHIBITED"))
            .toList();
        assertTrue("{ #offset #minIncl ... #maxExcl ... } on :DateTime must produce zero discrete interval errors", errors.isEmpty());
    }

    public void testScaleDivergentEmptyIntervalDomain() {
        var text = """
            {
              :defs {
                :ScaleDivergentEmpty { #exact #minIncl 0.1 #maxExcl 0.10 } :Float
              }
              :type :ScaleDivergentEmpty
              :body 0.1
            }
            """;
        myFixture.configureByText("scale_divergent_empty.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_EMPTY_INTERVAL_DOMAIN"))
            .toList();
        assertEquals("Expected 1 empty interval domain error for 0.1 vs 0.10", 1, errors.size());
        assertTrue(errors.get(0).getDescription().contains("Discrete interval defines an empty domain [0.1, 0.10)"));
    }

    public void testInvertedIntervalRange() {
        var text = """
            {
              :defs {
                :InvertedFloat { #exact #minIncl 0.11 #maxExcl 0.10 } :Float
              }
              :type :InvertedFloat
              :body 0.105
            }
            """;
        myFixture.configureByText("inverted_float.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_INVERTED_RANGE"))
            .toList();
        assertEquals("Expected 1 inverted range error for 0.11 vs 0.10", 1, errors.size());
        assertTrue(errors.get(0).getDescription().contains("Discrete interval lower bound 0.11 is greater than upper bound 0.10"));
    }

    public void testNominalExactFloatIntervalInheritance() {
        var text = """
            {
              :defs {
                :BaseExact { #exact } :Float
                :ProductPrice { #minIncl 10.0 #maxExcl 10.00 } :BaseExact
              }
              :type :ProductPrice
              :body 10.0
            }
            """;
        myFixture.configureByText("nominal_exact_interval.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_EMPTY_INTERVAL_DOMAIN"))
            .toList();
        assertEquals("Expected 1 empty interval domain error on nominal exact float", 1, errors.size());
    }

    public void testSwapInvertedBoundsQuickFixOnLiteralAndConstBounds() {
        var literalText = """
            {
              :defs {
                :InvertedBoundsTrap { #minIncl 20<caret>00 #maxExcl 1000 } :Int
              }
              :type :InvertedBoundsTrap
              :body 1500
            }
            """;
        myFixture.configureByText("inverted_bounds_swap.stvn", literalText);
        myFixture.doHighlighting();

        String expectedLiteralSwap = "Swap inverted bounds ('#minIncl 1000' and '#maxExcl 2000')";
        var swapActions = myFixture.filterAvailableIntentions(expectedLiteralSwap);
        assertFalse("Expected '" + expectedLiteralSwap + "' quick-fix", swapActions.isEmpty());
        myFixture.launchAction(swapActions.get(0));
        assertTrue(
            "Expected bounds to be swapped in-place to '#minIncl 1000 #maxExcl 2000'",
            myFixture.getFile().getText().contains(":InvertedBoundsTrap { #minIncl 1000 #maxExcl 2000 } :Int")
        );

        var constText = """
            {
              :defs {
                #PORT_MIN_INCL { #unsigned #size 16 } :Int 1024
                #PORT_MAX_EXCL { #unsigned #size 16 } :Int 4096
                :InvertedConstBounds { #minIncl #PORT_MAX_<caret>EXCL #maxExcl #PORT_MIN_INCL } :Int
              }
              :type :InvertedConstBounds
              :body 2048
            }
            """;
        myFixture.configureByText("inverted_const_bounds_swap.stvn", constText);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_INVERTED_RANGE"))
            .toList();
        assertFalse("Expected ERR_INVERTED_RANGE on #CONST-referenced inverted bounds", errors.isEmpty());

        String expectedConstSwap = "Swap inverted bounds ('#minIncl #PORT_MIN_INCL' and '#maxExcl #PORT_MAX_EXCL')";
        var constSwapActions = myFixture.filterAvailableIntentions(expectedConstSwap);
        assertFalse("Expected '" + expectedConstSwap + "' quick-fix", constSwapActions.isEmpty());
        myFixture.launchAction(constSwapActions.get(0));
        assertTrue(
            "Expected #CONST bounds to be swapped in-place",
            myFixture.getFile().getText().contains(":InvertedConstBounds { #minIncl #PORT_MIN_INCL #maxExcl #PORT_MAX_EXCL } :Int")
        );
    }

    public void testCrossHopPartialBoundOverrideDetectsInvertedRangeWithoutSwapFix() {
        var text = """
            {
              :defs {
                :BaseCluster { #unsigned #size 16 #minIncl 500 #maxExcl 5000 } :Int
                :InvertedChild { <caret>#minIncl 6000 } :BaseCluster
                :EmptyChild { #minIncl 5000 } :BaseCluster
              }
              :type :BaseCluster
              :body 1000
            }
            """;
        myFixture.configureByText("cross_hop_partial_bounds.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();

        assertTrue("Expected ERR_INVERTED_RANGE on :InvertedChild { #minIncl 6000 }",
            errors.stream().anyMatch(e -> e.getDescription() != null
                && e.getDescription().contains("ERR_INVERTED_RANGE")
                && e.getDescription().contains("6000")
                && e.getDescription().contains("5000")));
        assertTrue("Expected ERR_EMPTY_INTERVAL_DOMAIN on :EmptyChild { #minIncl 5000 }",
            errors.stream().anyMatch(e -> e.getDescription() != null
                && e.getDescription().contains("ERR_EMPTY_INTERVAL_DOMAIN")
                && e.getDescription().contains("[5000, 5000)")));

        var swapFixes = myFixture.filterAvailableIntentions("Swap inverted bounds");
        assertTrue("SwapInvertedBoundsQuickFix must not be offered when only one bound is in the local MetadataMap",
            swapFixes.isEmpty());
    }
}
