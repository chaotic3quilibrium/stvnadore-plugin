package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.psi.StvnTypes;

/**
 * Enforces the Zero-Tab Invariant (Rule STR-01 / ERR_TAB_CHARACTER_FORBIDDEN).
 * Prohibits tab characters (0x09) across structural whitespace, indentation, comments,
 * and single-line strings. Permits raw tabs exclusively within fenced string literals (Rule STR-04).
 */
@NullMarked
public final class StvnTabCharacterInspection extends LocalInspectionTool {

    private static final int MAX_PROBLEMS_PER_FILE = 250;

    /**
     * Constructs a new StvnTabCharacterInspection instance.
     */
    public StvnTabCharacterInspection() {}

    @Override
    public @NotNull String getShortName() {
        return "StvnTabCharacter";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Tab character forbidden inspection";
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
        return new PsiElementVisitor() {
            @Override
            public void visitFile(@NotNull PsiFile file) {
                if (!(file instanceof StvnFile)) {
                    return;
                }
                CharSequence text = file.getViewProvider().getContents();
                int len = text.length();
                int count = 0;
                for (int i = 0; i < len && count < MAX_PROBLEMS_PER_FILE; i++) {
                    if (text.charAt(i) == '\t') {
                        PsiElement leaf = file.findElementAt(i);
                        if (leaf != null && leaf.getNode() != null && leaf.getNode().getElementType() == StvnTypes.LITERAL_STRING_FENCED) {
                            // Verbatim tab character exemption within fenced string literals (§ 5.3)
                            int end = leaf.getTextRange().getEndOffset();
                            if (end > i) {
                                i = end - 1;
                            }
                            continue;
                        }
                        holder.registerProblem(
                            file,
                            new TextRange(i, i + 1),
                            StvnDiagnosticFormatter.formatPlugin("Tab characters are forbidden under Rule STR-01", StvnDiagnosticCodes.ERR_TAB_CHARACTER_FORBIDDEN),
                            new ReplaceTabWithSpacesQuickFix()
                        );
                        count++;
                    }
                }
            }
        };
    }

    /**
     * Quick-fix replacing all tab characters in the document buffer with two canonical spaces.
     */
    public static final class ReplaceTabWithSpacesQuickFix implements LocalQuickFix {
        @Override
        public @NotNull String getFamilyName() {
            return "Replace tabs with 2 spaces (Rule STR-01)";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (element == null) return;
            PsiFile file = element.getContainingFile();
            if (file == null) return;
            Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
            if (doc != null) {
                String content = doc.getText();
                StringBuilder sb = new StringBuilder(content.length());
                int len = content.length();
                for (int i = 0; i < len; i++) {
                    char c = content.charAt(i);
                    if (c == '\t') {
                        PsiElement leaf = file.findElementAt(i);
                        if (leaf != null && leaf.getNode() != null && leaf.getNode().getElementType() == StvnTypes.LITERAL_STRING_FENCED) {
                            int end = Math.min(len, leaf.getTextRange().getEndOffset());
                            sb.append(content, i, end);
                            i = end - 1;
                        } else {
                            sb.append("  ");
                        }
                    } else {
                        sb.append(c);
                    }
                }
                doc.setText(sb.toString());
                PsiDocumentManager.getInstance(project).commitDocument(doc);
            }
        }
    }
}
