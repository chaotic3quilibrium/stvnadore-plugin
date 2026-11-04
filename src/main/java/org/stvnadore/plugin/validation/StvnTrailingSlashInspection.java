package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiWhiteSpace;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.PackagePath;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.UseTarget;
import org.stvnadore.psi.Visitor;

/**
 * Validates that :use target paths and :package paths do not contain trailing slash characters.
 */
@NullMarked
public final class StvnTrailingSlashInspection extends LocalInspectionTool {

    /**
     * Constructs a new StvnTrailingSlashInspection instance.
     */
    public StvnTrailingSlashInspection() {
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitUseTarget(@NotNull UseTarget element) {
                if (hasTrailingSlash(element)) {
                    holder.registerProblem(
                        element,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Trailing slash prohibited in :use target",
                            StvnDiagnosticCodes.ERR_TRAILING_SLASH_PROHIBITED
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        new RemoveTrailingSlashQuickFix()
                    );
                }
            }

            @Override
            public void visitPackagePath(@NotNull PackagePath element) {
                if (hasTrailingSlash(element)) {
                    holder.registerProblem(
                        element,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Trailing slash prohibited in :package path",
                            StvnDiagnosticCodes.ERR_TRAILING_SLASH_PROHIBITED
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        new RemoveTrailingSlashQuickFix()
                    );
                }
            }
        };
    }

    private static boolean hasTrailingSlash(@NotNull PsiElement targetElement) {
        if (targetElement instanceof UseTarget useTarget && useTarget.getUseTargetIllegal() != null) {
            return true;
        }
        String nonCommentText = extractNonCommentText(targetElement);
        if (nonCommentText.endsWith("/")) {
            return true;
        }
        PsiElement sibling = targetElement.getNextSibling();
        while (sibling instanceof PsiWhiteSpace || sibling instanceof PsiComment) {
            sibling = sibling.getNextSibling();
        }
        if (sibling == null) {
            return false;
        }
        if (sibling.getNode().getElementType() == StvnTypes.FSLASH) {
            return true;
        }
        String siblingText = sibling.getText().trim();
        return siblingText.startsWith("/") && !siblingText.startsWith("//") && !siblingText.startsWith("/*");
    }

    private static @NotNull String extractNonCommentText(@NotNull PsiElement element) {
        StringBuilder sb = new StringBuilder();
        for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof PsiComment) && !(child instanceof PsiWhiteSpace)) {
                sb.append(child.getText());
            }
        }
        return sb.length() > 0 ? sb.toString() : element.getText().trim();
    }

    /**
     * Quick-fix that strips prohibited trailing slash characters from a {@code :use} target or {@code :package} path.
     */
    public static final class RemoveTrailingSlashQuickFix implements LocalQuickFix {

        /**
         * Constructs a new RemoveTrailingSlashQuickFix instance.
         */
        public RemoveTrailingSlashQuickFix() {
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Remove trailing slash";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (element == null) {
                return;
            }
            Document document = PsiDocumentManager.getInstance(project).getDocument(element.getContainingFile());
            if (document != null) {
                int startOffset = element.getTextRange().getStartOffset();
                int endOffset = element.getTextRange().getEndOffset();
                PsiElement sibling = element.getNextSibling();
                while (sibling != null && sibling.getNode().getElementType() == StvnTypes.FSLASH) {
                    endOffset = sibling.getTextRange().getEndOffset();
                    sibling = sibling.getNextSibling();
                }
                String rawSpan = document.getText().substring(startOffset, endOffset);
                String cleaned = rawSpan.replaceAll("/+$", "");
                PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(document);
                document.replaceString(startOffset, endOffset, cleaned);
                PsiDocumentManager.getInstance(project).commitDocument(document);
            }
        }
    }
}
