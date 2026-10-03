package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

import java.util.List;

/**
 * Automated test suite validating {@link StvnStringCapacityInspection}.
 * Tests unadorned types, threshold enforcement, severity gating, and QuickFix transformations.
 */
@NullMarked
public final class StvnStringCapacityInspectionTest extends BasePlatformTestCase {

    private StvnStringCapacityInspection inspection = new StvnStringCapacityInspection();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        inspection = new StvnStringCapacityInspection();
        myFixture.enableInspections(new InspectionProfileEntry[]{inspection});
    }

    public void testUnadornedStringWarning() {
        myFixture.configureByText("test_unadorned.stvn_inclf",
            """
            {
              :defs {
                :Unadorned :String
              }
            }
            """
        );
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("unadorned"))
            .toList();
        assertFalse("Expected unadorned string warning", warnings.isEmpty());
        assertTrue(warnings.get(0).getDescription().contains("default capacity is 16777216"));
    }

    public void testThresholdExceededWarning() {
        myFixture.configureByText("test_oversized.stvn_inclf",
            """
            {
              :defs {
                :Oversized { #maxSize 8192 } :String
              }
            }
            """
        );
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("exceeding configured threshold"))
            .toList();
        assertFalse("Expected threshold exceeded warning", warnings.isEmpty());
        assertTrue(warnings.get(0).getDescription().contains("specifies capacity 8192"));
    }

    public void testCompliantCapacitiesProduceNoWarnings() {
        myFixture.configureByText("test_compliant.stvn_inclf",
            """
            {
              :defs {
                :Small { #minSize 1 #maxSize 64 } :String
                :ThresholdBoundary { #minSize 1 #maxSize 4096 } :String
              }
            }
            """
        );
        var highlights = myFixture.doHighlighting();
        var capacityProblems = highlights.stream()
            .filter(h -> h.getDescription() != null && (h.getDescription().contains("unadorned") || h.getDescription().contains("exceeding configured threshold")))
            .toList();
        assertTrue("Compliant string capacities must produce zero problems", capacityProblems.isEmpty());
    }

    public void testPrimaryQuickFixApplication() {
        var text = """
            {
              :defs {
                :Target :String
              }
            }
            """;
        myFixture.configureByText("primary_fix.stvn_inclf", text);
        int offset = text.indexOf(":String");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Set nominal capacity");
        assertFalse("Expected capacity quick-fixes to be available", actions.isEmpty());
        assertEquals("Index 0 must strictly be primary action (4096)",
            "Set nominal capacity to 4096", actions.get(0).getText());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :Target { #maxSize 4096 } :String
              }
            }
            """);
    }

    public void testSecondaryQuickFixApplicationUnderWarning() {
        var text = """
            {
              :defs {
                :Target :String
              }
            }
            """;
        myFixture.configureByText("secondary_fix.stvn_inclf", text);
        int offset = text.indexOf(":String");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Set nominal capacity to 16777216 (default allocation cap)");
        assertFalse("Expected secondary quick-fix under warning", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :Target { #maxSize 16777216 } :String
              }
            }
            """);
    }

    public void testSecondaryQuickFixSuppressionUnderErrorSeverity() {
        inspection.configuredSeverity = "ERROR";

        var text = """
            {
              :defs {
                :Target :String
              }
            }
            """;
        myFixture.configureByText("error_suppress.stvn_inclf", text);
        int offset = text.indexOf(":String");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        myFixture.doHighlighting();

        var primaryActions = myFixture.filterAvailableIntentions("Set nominal capacity to 4096");
        assertFalse("Primary quick-fix must remain available under ERROR severity", primaryActions.isEmpty());

        var secondaryActions = myFixture.filterAvailableIntentions("Set nominal capacity to 16777216 (default allocation cap)");
        assertTrue("Secondary quick-fix must be suppressed under ERROR severity", secondaryActions.isEmpty());
    }

    public void testPayloadScopeDisabledByDefault() {
        myFixture.configureByText("payload_default.stvn_f",
            """
            {
              :defs {
                :ShortStr { #maxSize 8 } :String
              }
              :type :ShortStr
              :body "123456789012"
            }
            """
        );
        var highlights = myFixture.doHighlighting();
        var payloadErrors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("exceeds declared schema capacity"))
            .toList();
        assertTrue("Payload inspection must not run when disabled", payloadErrors.isEmpty());
    }

    public void testPayloadScopeEnabledAndTruncateQuickFix() {
        inspection.inspectPayload = true;

        var text = """
            {
              :defs {
                :ShortStr { #maxSize 8 } :String
              }
              :type :ShortStr
              :body "123456789012"
            }
            """;
        myFixture.configureByText("payload_truncate.stvn_f", text);
        int offset = text.indexOf("\"123456789012\"");
        myFixture.getEditor().getCaretModel().moveToOffset(offset + 1);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Truncate string literal to 8 characters");
        assertFalse("Expected truncate quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :ShortStr { #maxSize 8 } :String
              }
              :type :ShortStr
              :body "12345678"
            }
            """);
    }

    public void testPayloadScopeWidenQuickFix() {
        inspection.inspectPayload = true;

        var text = """
            {
              :defs {
                :ShortStr { #maxSize 8 } :String
              }
              :type :ShortStr
              :body "123456789012"
            }
            """;
        myFixture.configureByText("payload_widen.stvn_f", text);
        int offset = text.indexOf("\"123456789012\"");
        myFixture.getEditor().getCaretModel().moveToOffset(offset + 1);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Widen schema string capacity to 12");
        assertFalse("Expected widen schema quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :ShortStr { #maxSize 12 } :String
              }
              :type :ShortStr
              :body "123456789012"
            }
            """);
    }

    /**
     * Verifies that idiomatic 2.0.0 nominal definitions compile cleanly with zero inspection errors,
     * zero false facet rejections, and zero misplaced coordinate squigglies.
     */
    public void testIdiomaticNominalDefinitionsCompileCleanlyWithoutErrors() {
        myFixture.configureByText("nominal_definitions.stvn",
            """
            {
              :defs {
                :Int64 { #size 64 } :Int
                :Uint64 { #unsigned #size 64 } :Int
                :StringFixed4 { #minSize 4 #maxSize 4 } :String
              }
              :type :Tuple( :Int64 :Uint64 :StringFixed4 )
              :body ( 42 100 "TEST" )
            }
            """
        );
        var highlights = myFixture.doHighlighting();
        var inspectionErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> "StvnStringCapacity".equals(h.getInspectionToolId())
                || (h.getDescription() != null && h.getDescription().contains("ERR_COMPOUND_TYPE_OBSOLETE")))
            .toList();
        assertTrue("Idiomatic nominal definitions must produce zero inspection errors: " + inspectionErrors, inspectionErrors.isEmpty());

        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> "StvnStringCapacity".equals(h.getInspectionToolId())
                || (h.getDescription() != null && h.getDescription().contains("capacity")))
            .toList();
        assertTrue("Idiomatic nominal definitions must produce zero string capacity warnings: " + warnings, warnings.isEmpty());
    }

    /**
     * Verifies that undeclared 1.x compound strings do not offer ConvertObsoleteCompoundStringQuickFix
     * and fail closed under standard nominal type resolution.
     */
    public void testUndeclaredCompoundStringDoesNotOfferObsoleteQuickFix() {
        myFixture.configureByText("test_undeclared.stvn_inclf",
            """
            {
              :defs {
                :Target :String4096
              }
            }
            """
        );
        var highlights = myFixture.doHighlighting();
        for (var h : highlights) {
            var desc = h.getDescription();
            if (desc != null) {
                assertFalse("ERR_COMPOUND_TYPE_OBSOLETE must be purged", desc.contains("ERR_COMPOUND_TYPE_OBSOLETE"));
            }
        }

        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf(":String4096"));
        var actions = myFixture.filterAvailableIntentions("Convert obsolete string syntax");
        assertTrue("Convert obsolete string quick-fix must not be offered", actions.isEmpty());
    }
}
