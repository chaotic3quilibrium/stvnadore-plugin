package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.plugin.settings.StvnSettings;

import java.util.List;

/**
 * Platform integration suite verifying full-spine Pure-IR sum-tag redundancy soundness,
 * batch {@code Fix all} safety, {@code IntentionPreviewInfo.DIFF} rendering, comment preservation,
 * {@code :defs} {@code ConstantDefinition} coverage, unspooled container keyword safety, and
 * poisoned/degraded schema suppression.
 */
@NullMarked
public final class StvnSumTagRedundancySoundnessTest extends BasePlatformTestCase {

    private static final String REDUNDANT_DESC =
        "Redundant variant tag [stvn-plugin:PW006-WARN_REDUNDANT_VARIANT_TAG]";

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

    public void testSumTypeTortureBatchFixAllPreservesExactStvnValue() {
        String torture = """
            {
              :type :Seq(:Option(:Either(:String :Option(:Either(:String :Float)))))
              :body [
                // EXPLICITLY TAGGED PERMUTATIONS
                #None
                #Some #Left "Explicit Outer Left"
                #Some #Right #None
                #Some #Right #Some #Left "Explicit Inner Left"
                #Some #Right #Some #Right 3.14159

                // VALID SPEC-COMPLIANT INFERENCE PATHWAYS (Asymmetric Happy-Paths)
                #None
                #Some #None
                #Right #None

                // Partially inferred leaf float permutations
                #Some #Right #Some #Right 1.234
                #Some #Right #Some 1.234
                #Some #Right #Right 1.234
                #Right #Some #Right 1.234
                #Some #Some 1.234
                #Right #Right 1.234
                #Some #Right 1.234
                #Right #Some 1.234
                #Some 1.234
                #Right 1.234

                // Total Fall-Through Inference
                1.234
              ]
            }
            """;
        StvnValue origPayload = StvnCompiler.compile(torture).document().orElseThrow().payload().orElseThrow();
        myFixture.configureByText("sum_type_torture.stvn", torture);
        myFixture.getEditor().getCaretModel().moveToOffset(torture.indexOf("#Some #Left"));
        List<HighlightInfo> initialWarnings = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && REDUNDANT_DESC.equals(h.getDescription()))
            .toList();

        // 0 + 1 + 1 + 2 + 4 + 0 + 0 + 0 + (4 + 3 + 3 + 3 + 2 + 2 + 2 + 2 + 1 + 1) + 0 = 31 warnings
        assertEquals("Expected exact composable redundant tag count on sum_type_torture.stvn", 31, initialWarnings.size());

        List<IntentionAction> batchActions = myFixture.filterAvailableIntentions(
            "Fix all 'Variant tag style inspection' problems in file"
        );
        if (batchActions.isEmpty()) {
            batchActions = myFixture.filterAvailableIntentions("Fix all 'Variant tag style' problems in file");
        }
        assertFalse("Batch Fix-all action must be available on sum_type_torture.stvn", batchActions.isEmpty());
        myFixture.launchAction(batchActions.getFirst());
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        String postText = myFixture.getEditor().getDocument().getText();
        var recomp = StvnCompiler.compile(postText);
        assertFalse("Batch quick-fix output must compile with 0 errors: " + recomp.diagnostics(), recomp.hasErrors());
        StvnDocument postDoc = recomp.document().orElseThrow();
        assertEquals("Batch quick-fix must preserve 100% Pure-IR StvnValue identity", origPayload, postDoc.payload().orElseThrow());

        // Second pass must reach 0 redundant-tag warnings and 0 errors (1-pass fixed point)
        List<HighlightInfo> postHighlights = myFixture.doHighlighting();
        assertEquals(0, postHighlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).count());
        assertEquals(
            0,
            postHighlights.stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && REDUNDANT_DESC.equals(h.getDescription()))
                .count()
        );
    }

    public void testIntentionPreviewDiffAndInteriorCommentPreservation() {
        String src = """
            {
              :type :Option(:Float)
              :body #Some // preserve inline note
              1.234
            }
            """;
        myFixture.configureByText("comment_preview.stvn", src);
        myFixture.getEditor().getCaretModel().moveToOffset(src.indexOf("#Some"));
        myFixture.doHighlighting();

        IntentionAction fix = myFixture.findSingleIntention("Remove redundant tag");
        String preview = myFixture.getIntentionPreviewText(fix);
        assertTrue("IntentionPreviewInfo.DIFF must preserve interior comment", preview.contains("// preserve inline note"));
        assertFalse("IntentionPreviewInfo.DIFF must strip #Some", preview.contains("#Some"));

        myFixture.launchAction(fix);
        String after = myFixture.getEditor().getDocument().getText();
        assertTrue(after.contains("// preserve inline note"));
        assertFalse(StvnCompiler.compile(after).hasErrors());
    }

    public void testDefsConstantDefinitionAndDegradedSchemaIsolation() {
        String defsAndDegraded = """
            {
              :defs {
                :BrokenInt { #minIncl 10 #maxExcl 5 } :Int
                #SAFE_NONE :Option(:Either(:String :Option(:Int))) #Some #Right #None
                #REDUNDANT_OPT :Option(:Int) #Some 99
              }
              :type :Option(:BrokenInt)
              :body #Some 7
            }
            """;
        myFixture.configureByText("defs_and_degraded.stvn", defsAndDegraded);
        var doc = myFixture.getEditor().getDocument();
        List<HighlightInfo> redundant = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && REDUNDANT_DESC.equals(h.getDescription()))
            .toList();

        // 1 warning on #Right in #SAFE_NONE, 1 warning on #Some in #REDUNDANT_OPT, and 0 on poisoned :body #Some 7
        assertEquals(2, redundant.size());
        List<String> flaggedSlices = redundant.stream()
            .map(h -> doc.getText().substring(h.getStartOffset(), h.getEndOffset()))
            .toList();
        assertEquals(List.of("#Right", "#Some"), flaggedSlices);
    }

    public void testShortFormIrreducibleTagsEmitFormDiscrepancyOnlyNeverRedundantTag() {
        String shortIrreducible = """
            {
              :type :Seq(:Option(:Either(:String :Option(:Either(:String :Float)))))
              :body [
                #S #N
                #R #N
              ]
            }
            """;
        myFixture.configureByText("short_irreducible.stvn", shortIrreducible);
        List<HighlightInfo> warnings = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .toList();

        assertEquals(
            "Irreducible #S #N and #R #N must emit zero redundant tag warnings",
            0,
            warnings.stream().filter(h -> REDUNDANT_DESC.equals(h.getDescription())).count()
        );
        assertEquals(
            "Expected 4 form-discrepancy warnings (#S, #N, #R, #N)",
            4,
            warnings.stream()
                .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_VARIANT_FORM_DISCREPANCY"))
                .count()
        );
    }

    public void testUnspooledControlEnumTupleBatchFixPreservesExactValue() {
        String src = """
            {
              :defs {
                :ControlEnum :Enum [ #None #Left #1 #OK ]
                :BoolWordEnum :Enum [ #TRUE #T #OTHER ]
              }
              :type :Tuple(
                :Option(:Either(:Int :ControlEnum))
                :Option(:Union(:String :ControlEnum))
                :Either(:BoolWordEnum :Boolean)
              )
              :body ( #Some #Right #None #Some #2 #1 #Right #TRUE )
            }
            """;
        StvnValue origPayload = StvnCompiler.compile(src).document().orElseThrow().payload().orElseThrow();
        var psiFile = myFixture.configureByText("unspooled_control_enum.stvn", src);
        List<HighlightInfo> redundant = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && REDUNDANT_DESC.equals(h.getDescription()))
            .toList();

        assertEquals("Expected only the two outer #Some tags to be flagged", 2, redundant.size());

        StvnVariantStyleInspection tool = new StvnVariantStyleInspection();
        ProblemsHolder holder = new ProblemsHolder(InspectionManager.getInstance(getProject()), psiFile, true);
        PsiElementVisitor visitor = tool.buildVisitor(holder, true);
        psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                element.accept(visitor);
                super.visitElement(element);
            }
        });
        List<ProblemDescriptor> descriptors = holder.getResults();
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            for (ProblemDescriptor d : descriptors) {
                var fixes = d.getFixes();
                if (fixes != null && fixes.length > 0 && fixes[0] instanceof LocalQuickFix lqf) {
                    lqf.applyFix(getProject(), d);
                }
            }
        });
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        String postText = myFixture.getEditor().getDocument().getText();
        var recomp = StvnCompiler.compile(postText);
        assertFalse("Post-fix tuple must compile cleanly: " + recomp.diagnostics(), recomp.hasErrors());
        assertEquals(origPayload, recomp.document().orElseThrow().payload().orElseThrow());
    }
}
