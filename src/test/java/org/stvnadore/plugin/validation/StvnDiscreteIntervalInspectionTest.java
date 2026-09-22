package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Automated platform test suite validating {@link StvnDiscreteIntervalInspection}.
 * Verifies half-open interval governance [minIncl, maxExcl) on discrete types (:Int and { #exact } :Float).
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
}
