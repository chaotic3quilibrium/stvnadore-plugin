package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.MetaNameFacet;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.Visitor;

/**
 * Validates that declared #name in the root :meta block matches the file stem slot 0
 * (WARN_DOCUMENT_NAME_MISMATCH).
 */
@NullMarked
public final class StvnStemMismatchInspection extends LocalInspectionTool {

    /**
     * Constructs a new StvnStemMismatchInspection instance.
     */
    public StvnStemMismatchInspection() {}

    @Override
    public @NotNull String getShortName() {
        return "StvnStemMismatch";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Document stem mismatch inspection";
    }

    @Override
    public @NotNull String getGroupDisplayName() {
        return "STVN";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitMetaNameFacet(@NotNull MetaNameFacet facet) {
                super.visitMetaNameFacet(facet);
                var file = facet.getContainingFile();
                if (file == null) return;
                var fileName = file.getName();
                String[] parts = fileName.split("\\.");
                if (parts.length == 0) return;
                String stem = parts[0];

                StringLiteral stringLit = facet.getStringLiteral();
                if (stringLit == null) return;
                String rawText = stringLit.getText();
                String declared = stripQuotes(rawText);

                if (!stem.equals(declared)) {
                    holder.registerProblem(
                        stringLit,
                        "Document #name '" + declared + "' does not match file stem '" + stem + "' (WARN_DOCUMENT_NAME_MISMATCH).",
                        ProblemHighlightType.WARNING,
                        new AlignStemNameQuickFix(stem)
                    );
                }
            }
        };
    }

    private static String stripQuotes(String s) {
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * Quick-fix updating #name string literal to match the file stem.
     */
    public static final class AlignStemNameQuickFix implements LocalQuickFix {
        private final String stem;

        public AlignStemNameQuickFix(String stem) {
            this.stem = stem;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Align #name with file stem";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (element == null) return;
            Document doc = PsiDocumentManager.getInstance(project).getDocument(element.getContainingFile());
            if (doc != null) {
                var range = element.getTextRange();
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), "\"" + stem + "\"");
                PsiDocumentManager.getInstance(project).commitDocument(doc);
            }
        }
    }
}
