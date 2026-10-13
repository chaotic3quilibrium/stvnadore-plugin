package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.MockWolfTheProblemSolver;
import com.intellij.codeInsight.daemon.impl.WolfTheProblemSolverImpl;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.problems.WolfTheProblemSolver;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class StvnMetaVisibilityAndWolfTest extends BasePlatformTestCase {

    public void testMissingMetaMarksProblemInWolfTheProblemSolver() {
        var content = """
            {
              :defs {
                :package :com/example {
                  :OrderId :Int
                }
              }
            }
            """;
        var psiFile = myFixture.configureByText("primitives.stvn_d", content);
        var virtualFile = psiFile.getVirtualFile();
        assertNotNull(virtualFile);

        var wolf = WolfTheProblemSolver.getInstance(getProject());
        if (wolf instanceof MockWolfTheProblemSolver mockWolf) {
            mockWolf.setDelegate(WolfTheProblemSolverImpl.createTestInstance(getProject()));
        }

        try {
            myFixture.doHighlighting();
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

            assertTrue("WolfTheProblemSolver must flag primitives.stvn_d with error badge",
                wolf.isProblemFile(virtualFile));
        } finally {
            if (wolf instanceof MockWolfTheProblemSolver mockWolf) {
                mockWolf.resetDelegate();
            }
        }
    }

    public void testLineOneOpeningBraceDisplaysErrorSquiggleAndQuickFix() {
        myFixture.enableInspections(new StvnMissingMetaInspection());
        var content = """
            {
              :defs {
                :package :com/example {
                  :OrderId :Int
                }
              }
            }
            """;
        myFixture.configureByText("primitives.stvn_d", content);
        var highlights = myFixture.doHighlighting();

        var lineOneErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getStartOffset() == 0 || "{".equals(h.getText()))
            .toList();

        assertEquals("Expected exactly one error highlight on line 1 '{'", 1, lineOneErrors.size());
        assertTrue("Error must report missing :meta block",
            lineOneErrors.get(0).getDescription().contains("requires root :meta block"));

        var fixes = myFixture.filterAvailableIntentions("Insert missing root :meta block");
        assertEquals("Quick-fix must be available on line 1 '{'", 1, fixes.size());
    }
}
