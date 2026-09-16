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
                :Oversized :String8192
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
                :Small :String64
                :ThresholdBoundary :String4096
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

        var actions = myFixture.filterAvailableIntentions("Set nominal string capacity to 4096");
        assertFalse("Expected primary quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :Target :String4096
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

        var actions = myFixture.filterAvailableIntentions("Set nominal string capacity to default capacity (16777216)");
        assertFalse("Expected secondary quick-fix under warning", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :Target :String16777216
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

        var primaryActions = myFixture.filterAvailableIntentions("Set nominal string capacity to 4096");
        assertFalse("Primary quick-fix must remain available under ERROR severity", primaryActions.isEmpty());

        var secondaryActions = myFixture.filterAvailableIntentions("Set nominal string capacity to default capacity (16777216)");
        assertTrue("Secondary quick-fix must be suppressed under ERROR severity", secondaryActions.isEmpty());
    }

    public void testPayloadScopeDisabledByDefault() {
        myFixture.configureByText("payload_default.stvn_f",
            """
            {
              :type :String8
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
              :type :String8
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
              :type :String8
              :body "12345678"
            }
            """);
    }

    public void testPayloadScopeWidenQuickFix() {
        inspection.inspectPayload = true;

        var text = """
            {
              :type :String8
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
              :type :String12
              :body "123456789012"
            }
            """);
    }
}
