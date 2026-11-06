package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;

import java.util.List;

/**
 * Conformance tests for StvnTabCharacterInspection and ReplaceTabWithSpacesQuickFix.
 * Verifies the Strict Zero-Tab Invariant, non-destructive PSI parsing when tabs are present,
 * and the Verbatim Tab Character Exemption for fenced strings.
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
        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Structural indentation tab must report exactly one error highlight under Invariant 10", 1, errorHighlights.size());
        var error = errorHighlights.get(0);
        assertTrue("Highlight must originate from local inspection", error.getDescription().contains("[stvn-plugin:E001-ERR_TAB_CHARACTER_FORBIDDEN]"));
        assertFalse("External annotator duplicate must be suppressed", error.getDescription().contains("[stvn-core:E001-ERR_TAB_CHARACTER_FORBIDDEN]"));
    }

    public void testTrailingTabAfterTypeClauseReportsSingleProblemDescriptor() {
        String code = "{\n  :type :String\t\n  :body \"hello\"\n}";
        myFixture.configureByText("trailing_tab.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Trailing tab after type clause must report exactly one error highlight under Invariant 10", 1, errorHighlights.size());
        var error = errorHighlights.get(0);
        assertTrue("Highlight must originate from local inspection", error.getDescription().contains("[stvn-plugin:E001-ERR_TAB_CHARACTER_FORBIDDEN]"));
        assertFalse("External annotator duplicate must be suppressed", error.getDescription().contains("[stvn-core:E001-ERR_TAB_CHARACTER_FORBIDDEN]"));
    }

    public void testSecondaryCascadeSuppressionOnRootBraceWhenTabPresent() {
        String code = "{\n  :type :String\t\n  :body \"hello\"\n}";
        myFixture.configureByText("cascade_suppression.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        var rootErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR && h.getStartOffset() == 0)
            .toList();
        assertTrue("Root brace must have zero error highlights when tab character is present under Spec § 7.3.1", rootErrors.isEmpty());
    }

    public void testCommentTabReportsProblemDescriptor() {
        String code = "{\n  //\tForbidden tab in comment\n  :type :String\n  :body \"hello\"\n}";
        myFixture.configureByText("comment_tab.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Comment tab must report exactly one error highlight under Invariant 10", 1, errorHighlights.size());
        var error = errorHighlights.get(0);
        assertTrue("Highlight must originate from local inspection", error.getDescription().contains("[stvn-plugin:E001-ERR_TAB_CHARACTER_FORBIDDEN]"));
        assertFalse("External annotator duplicate must be suppressed", error.getDescription().contains("[stvn-core:E001-ERR_TAB_CHARACTER_FORBIDDEN]"));
    }

    public void testSimpleStringRawTabReportsProblemDescriptor() {
        String code = "{\n  :type :String\n  :body \"raw\ttab\"\n}";
        myFixture.configureByText("simple_string_tab.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        int tabOffset = code.indexOf('\t');
        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR && h.getStartOffset() == tabOffset && h.getEndOffset() == tabOffset + 1)
            .toList();
        assertEquals("Unescaped tab in simple string must report exactly one error highlight under Invariant 10", 1, errorHighlights.size());
        var error = errorHighlights.get(0);
        assertTrue("Highlight must originate from local inspection", error.getDescription().contains("[stvn-plugin:E001-ERR_TAB_CHARACTER_FORBIDDEN]"));
        assertFalse("External annotator duplicate must be suppressed", error.getDescription().contains("[stvn-core:E001-ERR_TAB_CHARACTER_FORBIDDEN]"));
    }

    public void testOpeningFenceLineTabReportsProblemDescriptor() {
        String code = "{\n  :type :String\n  :body \"\"\"[TAG]\t\ncontent\n[TAG]\"\"\"\n}";
        myFixture.configureByText("opening_fence_tab.stvn", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        int tabOffset = code.indexOf('\t');
        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR && h.getStartOffset() == tabOffset && h.getEndOffset() == tabOffset + 1)
            .toList();
        assertEquals("Opening fence delimiter tab must report exactly one error highlight under Invariant 10", 1, errorHighlights.size());
        var error = errorHighlights.get(0);
        assertTrue("Highlight must originate from local inspection", error.getDescription().contains("[stvn-plugin:E001-ERR_TAB_CHARACTER_FORBIDDEN]"));
        assertFalse("External annotator duplicate must be suppressed", error.getDescription().contains("[stvn-core:E001-ERR_TAB_CHARACTER_FORBIDDEN]"));
    }

    public void testQuickFixReplacesOnlyStructuralTabsAndPreservesFencedTabs() {
        String code = "{\n\t:type :String\n  :body \"\"\"[MAKEFILE]\nall:\n\tgcc main.c\n[MAKEFILE]\"\"\"\n}";
        myFixture.configureByText("mixed.stvn", code);
        myFixture.doHighlighting();
        var action = myFixture.getAllQuickFixes().stream()
            .filter(f -> f.getText().contains(StvnTabCharacterInspection.QUICK_FIX_NAME))
            .findFirst()
            .orElse(null);
        assertNotNull("Replace tabs quick-fix must be available", action);
        myFixture.launchAction(action);

        String text = myFixture.getEditor().getDocument().getText();
        assertTrue("Structural tab must be replaced with two spaces", text.contains("{\n  :type :String"));
        assertTrue("Fenced string recipe tab must be preserved intact", text.contains("all:\n\tgcc main.c"));
    }

    public void testTabsInAllStructuralAndCommentContextsPreservePsiTree() {
        String code = "\t{\n"
            + "  // Comment\twith tab\n"
            + "  :meta {\n"
            + "    #name \"tab_non_destructive_all_contexts\"\n"
            + "    #kind #BODY_INCLUDE\n"
            + "  }\n"
            + "  :defs {\n"
            + "    :Stage3Envelope :Tuple( :Int :Int :String :String )\n"
            + "  }\n"
            + "  :type\t:Stage3Envelope\n"
            + "  :body\t( 3\t\t1500 \"simple\tstring\" \"\"\"[TSV]\t\ncol1\tcol2\n\t[TSV]\"\"\" )\n"
            + "}\n";
        var psiFile = myFixture.configureByText("tab_non_destructive_all_contexts.stvn_i", code);
        List<HighlightInfo> highlights = myFixture.doHighlighting();

        var psiErrors = PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement.class);
        assertTrue("Grammar-Kit PSI parser must produce zero PsiErrorElement nodes when tabs are present: " + psiErrors,
            psiErrors.isEmpty());

        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .toList();
        assertEquals("Must report exactly 8 forbidden tab run highlights and zero secondary syntax errors", 8, errorHighlights.size());
        for (var err : errorHighlights) {
            assertTrue("Every error highlight must be E001-ERR_TAB_CHARACTER_FORBIDDEN, got: " + err.getDescription(),
                err.getDescription() != null && err.getDescription().contains("E001-ERR_TAB_CHARACTER_FORBIDDEN"));
        }

        // Verify hover documentation on #kind in :meta still works when a tab is at offset 0
        int kindOffset = code.indexOf("#kind") + 2;
        var kindLeaf = psiFile.findElementAt(kindOffset);
        assertNotNull(kindLeaf);
        var docProvider = new StvnDocumentationProvider();
        var docTarget = docProvider.getCustomDocumentationElement(myFixture.getEditor(), psiFile, kindLeaf, kindOffset);
        assertNotNull("Documentation target on #kind must resolve when tab is at offset 0", docTarget);
        String docHtml = docProvider.generateDoc(docTarget, kindLeaf);
        assertNotNull("Hover documentation on #kind must be generated when tab is at offset 0", docHtml);
    }
}
