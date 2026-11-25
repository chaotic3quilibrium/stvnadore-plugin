package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.util.List;

/**
 * IntelliJ Platform tests for {@link StvnStringEscapeScrutinyInspection} (#27), verifying
 * hazardous escape scrutiny ({@code PW007}), configurable default-on shorthand {@code \r}
 * scrutiny ({@code PW008}), {@code \u005Cu000D} quick-fixes with diff previews, clean exemptions,
 * and zero duplicate squigglies alongside {@link StvnExternalAnnotator}.
 */
@NullMarked
public final class StvnStringEscapeScrutinyInspectionTest extends BasePlatformTestCase {

    public void testHazardousEscapesFlaggedAcrossAllFiveSectionsWithExactSubRanges() {
        myFixture.enableInspections(new StvnStringEscapeScrutinyInspection());
        String source = """
            {
              :meta {
                #name "doc_ok"
                #domain "dom_ok"
                #kind #BODY_INCLUDE
                #note "meta_bidi_\\u202E_end"
              }
              :include [ "inc_\\u200B_mod.stvn_d" ]
              :defs {
                :Pat { #regex "a\\bb" } :String
                #C_VAL :String "const_\\uFEFF_bom"
              }
              :type { #minIncl "min_\\u007F_del" } :String
              :body "body_\\f_and_\\u2028_sep"
            }
            """;
        myFixture.configureByText("doc_ok.dom_ok.stvn_i", source);

        var warnings = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("[stvn-plugin:PW007-WARN_HAZARDOUS_STRING_ESCAPE]"))
            .toList();

        assertEquals("Expected 7 hazardous escape warnings across all 5 sections: " + warnings, 7, warnings.size());

        assertWarningSpanAndText(source, warnings.get(0), "\\u202E", "U+202E", "RIGHT-TO-LEFT OVERRIDE");
        assertWarningSpanAndText(source, warnings.get(1), "\\u200B", "U+200B", "ZERO WIDTH SPACE");
        assertWarningSpanAndText(source, warnings.get(2), "\\b", "U+0008", "BACKSPACE");
        assertWarningSpanAndText(source, warnings.get(3), "\\uFEFF", "U+FEFF", "ZERO WIDTH NO-BREAK SPACE");
        assertWarningSpanAndText(source, warnings.get(4), "\\u007F", "U+007F", "DELETE");
        assertWarningSpanAndText(source, warnings.get(5), "\\f", "U+000C", "FORM FEED");
        assertWarningSpanAndText(source, warnings.get(6), "\\u2028", "U+2028", "LINE SEPARATOR");
    }

    public void testShorthandCrCheckboxQuickFixesPreviewAndExplicitU000DExemption() {
        var inspection = new StvnStringEscapeScrutinyInspection();
        assertTrue("Shorthand \\r scrutiny must default to true", inspection.warnOnShorthandCrEscape);

        JComponent panel = inspection.createOptionsPanel();
        assertNotNull("Inspection options panel must not be null", panel);
        JCheckBox checkBox = findCheckBox(panel);
        assertNotNull("Inspection options panel must contain JCheckBox", checkBox);
        assertEquals("Warn on shorthand \\r escape (use explicit \\u000D when intentional)", checkBox.getText());
        assertTrue(checkBox.isSelected());

        myFixture.enableInspections(inspection);

        String source = """
            {
              :meta {
                #name "cr_test"
                #domain "hygiene"
                #kind #BODY
              }
              :type :Tuple(:String :String :String)
              :body (
                "line1\\r\\nline2"
                "first\\r\\nsecond\\r\\n"
                "explicit\\u000D\\nand_lower\\u000d\\nand_joiners_\\u200C_\\u200D_\\u0041_\\u2705_\\uD83D\\uDE80_\\n_\\t_\\\"_\\\\_\\/"
              )
            }
            """;
        myFixture.configureByText("cr_test.hygiene.stvn", source);

        var pwWarnings = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("[stvn-plugin:PW00"))
            .toList();

        // Strictly 3 shorthand \r warnings (1 in slot 1, 2 in slot 2), 0 warnings in slot 3!
        assertEquals("Expected strictly 3 PW008 shorthand \\r warnings and 0 false positives: " + pwWarnings,
            3, pwWarnings.size());
        int firstCr = source.indexOf("\\r");
        assertEquals(firstCr, pwWarnings.get(0).getStartOffset());
        assertEquals(firstCr + 2, pwWarnings.get(0).getEndOffset());
        assertTrue(pwWarnings.get(0).getDescription().contains("U+000D CARRIAGE RETURN"));

        // Verify single quick-fix and IntentionPreviewInfo.DIFF on slot 1
        myFixture.getEditor().getCaretModel().moveToOffset(firstCr + 1);
        List<IntentionAction> availableAtFirst = myFixture.filterAvailableIntentions(
            StvnStringEscapeScrutinyInspection.REPLACE_SINGLE_CR_QUICK_FIX_NAME
        );
        assertEquals(1, availableAtFirst.size());
        String previewSingle = myFixture.getIntentionPreviewText(availableAtFirst.get(0));
        assertTrue("Diff preview must replace \\r with \\u000D", previewSingle.contains("\"line1\\u000D\\nline2\""));
        myFixture.launchAction(availableAtFirst.get(0));
        assertTrue(myFixture.getEditor().getDocument().getText().contains("\"line1\\u000D\\nline2\""));

        // Verify batch literal quick-fix on slot 2 (which has 2 shorthand \r escapes)
        String updatedDoc = myFixture.getEditor().getDocument().getText();
        int batchTargetOffset = updatedDoc.indexOf("\"first\\r\\nsecond\\r\\n\"") + 7;
        myFixture.getEditor().getCaretModel().moveToOffset(batchTargetOffset);
        List<IntentionAction> batchFixes = myFixture.filterAvailableIntentions(
            StvnStringEscapeScrutinyInspection.REPLACE_ALL_CR_IN_LITERAL_QUICK_FIX_NAME
        );
        assertEquals(1, batchFixes.size());
        String previewBatch = myFixture.getIntentionPreviewText(batchFixes.get(0));
        assertTrue(previewBatch.contains("\"first\\u000D\\nsecond\\u000D\\n\""));
        myFixture.launchAction(batchFixes.get(0));

        // After replacing all shorthand \r with explicit U+000D escapes, 0 warnings remain!
        var remainingWarnings = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("[stvn-plugin:PW00"))
            .toList();
        assertTrue("Explicit \\u000D must produce 0 warnings: " + remainingWarnings, remainingWarnings.isEmpty());

        // Toggling checkbox off suppresses PW008 while keeping PW007 active
        checkBox.doClick();
        assertFalse(inspection.warnOnShorthandCrEscape);
        myFixture.configureByText("cr_disabled.hygiene.stvn", """
            {
              :meta {
                #name "cr_disabled"
                #domain "hygiene"
                #kind #BODY
              }
              :type :String
              :body "shorthand_\\r_quiet_but_bidi_\\u202E_warns"
            }
            """);
        var afterToggle = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("[stvn-plugin:PW00"))
            .toList();
        assertEquals("Only PW007 must warn when shorthand \\r checkbox is unchecked: " + afterToggle, 1, afterToggle.size());
        assertTrue(afterToggle.get(0).getDescription().contains("PW007-WARN_HAZARDOUS_STRING_ESCAPE"));
    }

    public void testMalformedAndUnpairedEscapesProduceZeroScrutinyDuplicates() {
        myFixture.enableInspections(new StvnStringEscapeScrutinyInspection());
        String source = """
            {
              :meta {
                #name "malformed_guard"
                #domain "hygiene"
                #kind #BODY
              }
              :type :String
              :body "bad_\\z_hex_\\u12G4_unpaired_\\uD800"
            }
            """;
        myFixture.configureByText("malformed_guard.hygiene.stvn", source);

        var highlights = myFixture.doHighlighting();
        var scrutinyWarnings = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_HAZARDOUS_STRING_ESCAPE"))
            .toList();
        assertTrue("Malformed and unpaired escapes must not emit scrutiny warnings: " + scrutinyWarnings,
            scrutinyWarnings.isEmpty());

        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Core E001/E002 must own the 3 lexical errors cleanly: " + errors, 3, errors.size());
    }

    private static void assertWarningSpanAndText(
            String source,
            com.intellij.codeInsight.daemon.impl.HighlightInfo warning,
            String expectedSlice,
            String expectedScalarId,
            String expectedCharName
    ) {
        int expectedStart = source.indexOf(expectedSlice);
        assertEquals(expectedStart, warning.getStartOffset());
        assertEquals(expectedStart + expectedSlice.length(), warning.getEndOffset());
        assertNotNull(warning.getDescription());
        assertTrue(warning.getDescription().contains(expectedScalarId));
        assertTrue(warning.getDescription().contains(expectedCharName));
    }

    private static JCheckBox findCheckBox(Container root) {
        for (Component comp : root.getComponents()) {
            if (comp instanceof JCheckBox cb) {
                return cb;
            }
            if (comp instanceof Container child) {
                JCheckBox nested = findCheckBox(child);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
