package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

import java.util.List;

/**
 * Conformance tests for StvnTabCharacterInspection and ReplaceTabWithSpacesQuickFix.
 * Verifies the Strict Zero-Tab Invariant and the Verbatim Tab Character Exemption for fenced strings.
 */
@NullMarked
public final class StvnTabCharacterInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new StvnTabCharacterInspection());
    }

    public void testValidFencedStringWithMakefileTabsProducesZeroProblems() {
        String code = """
            {
              :type :String
              :body \"\"\"[MAKEFILE]
            all:
            \tgcc -O2 main.c -o app
            [MAKEFILE]\"\"\"
            }
            """;
        myFixture.configureByText("makefile.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        var tabErrors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Rule STR-01"))
            .toList();
        assertTrue("Fenced string with Makefile tabs must produce zero inspection problems", tabErrors.isEmpty());
    }

    public void testValidFencedStringWithTsvTabsProducesZeroProblems() {
        String code = """
            {
              :type :String
              :body \"\"\"[TSV]
            id\tname\tscore
            101\tAlice\t98
            [TSV]\"\"\"
            }
            """;
        myFixture.configureByText("tsv.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        var tabErrors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Rule STR-01"))
            .toList();
        assertTrue("Fenced string with TSV tabs must produce zero inspection problems", tabErrors.isEmpty());
    }

    public void testStructuralTabReportsProblemDescriptor() {
        String code = "{\n\t:type :String\n  :body \"hello\"\n}";
        myFixture.configureByText("structural_tab.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        var tabErrors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Rule STR-01"))
            .toList();
        assertEquals("Structural indentation tab must report exactly one problem", 1, tabErrors.size());
    }

    public void testCommentTabReportsProblemDescriptor() {
        String code = "{\n  //\tForbidden tab in comment\n  :type :String\n  :body \"hello\"\n}";
        myFixture.configureByText("comment_tab.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        var tabErrors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Rule STR-01"))
            .toList();
        assertEquals("Comment tab must report exactly one problem", 1, tabErrors.size());
    }

    public void testSimpleStringRawTabReportsProblemDescriptor() {
        String code = "{\n  :type :String\n  :body \"raw\ttab\"\n}";
        myFixture.configureByText("simple_string_tab.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        var tabErrors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Rule STR-01"))
            .toList();
        assertEquals("Unescaped tab in simple string must report exactly one problem", 1, tabErrors.size());
    }

    public void testOpeningFenceLineTabReportsProblemDescriptor() {
        String code = "{\n  :type :String\n  :body \"\"\"[TAG]\t\ncontent\n[TAG]\"\"\"\n}";
        myFixture.configureByText("opening_fence_tab.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        var tabErrors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Rule STR-01"))
            .toList();
        assertEquals("Opening fence delimiter tab must report problem descriptor", 1, tabErrors.size());
    }

    public void testQuickFixReplacesOnlyStructuralTabsAndPreservesFencedTabs() {
        String code = "{\n\t:type :String\n  :body \"\"\"[MAKEFILE]\nall:\n\tgcc main.c\n[MAKEFILE]\"\"\"\n}";
        myFixture.configureByText("mixed.stvn", code);
        myFixture.doHighlighting();
        var action = myFixture.getAllQuickFixes().stream()
            .filter(f -> f.getText().contains("Replace tabs with 2 spaces"))
            .findFirst()
            .orElse(null);
        assertNotNull("Replace tabs quick-fix must be available", action);
        myFixture.launchAction(action);

        String text = myFixture.getEditor().getDocument().getText();
        assertTrue("Structural tab must be replaced with two spaces", text.contains("{\n  :type :String"));
        assertTrue("Fenced string recipe tab must be preserved intact", text.contains("all:\n\tgcc main.c"));
    }
}
