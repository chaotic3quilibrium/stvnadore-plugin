package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.psi.StvnTypes;

/**
 * Enforces the Zero-Tab Invariant (Rule STR-01 / ERR_TAB_CHARACTER_FORBIDDEN).
 * Prohibits tab characters (0x09) across structural whitespace, indentation, comments,
 * single-line strings, and fenced string delimiter lines. Permits raw tabs exclusively
 * within the verbatim body of fenced string literals (Rule STR-04).
 */
@NullMarked
public final class StvnTabCharacterInspection extends LocalInspectionTool {

    private static final int MAX_PROBLEMS_PER_FILE = 250;

    /**
     * Canonical quick-fix label for replacing forbidden horizontal tab characters with spaces.
     */
    public static final String QUICK_FIX_NAME = "Replace tab character(s) with 2 spaces";

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
                        if (leaf != null && leaf.getNode() != null
                                && leaf.getNode().getElementType() == StvnTypes.LITERAL_STRING_FENCED) {
                            TextRange leafRange = leaf.getTextRange();
                            String leafText = leaf.getText();
                            int firstNewline = leafText.indexOf('\n');
                            int lastNewline = leafText.lastIndexOf('\n');
                            int relOffset = i - leafRange.getStartOffset();
                            boolean inVerbatimBody = firstNewline >= 0
                                && lastNewline >= firstNewline
                                && relOffset > firstNewline
                                && relOffset < lastNewline;
                            if (inVerbatimBody) {
                                // Verbatim tab character exemption strictly inside fenced body (§ 5.3)
                                i = leafRange.getStartOffset() + lastNewline;
                                continue;
                            }
                        }
                        int runEnd = i + 1;
                        while (runEnd < len && text.charAt(runEnd) == '\t') {
                            runEnd++;
                        }
                        holder.registerProblem(
                            file,
                            new TextRange(i, runEnd),
                            StvnDiagnosticFormatter.formatPlugin(
                                "Tab characters are forbidden under Rule STR-01",
                                StvnDiagnosticCodes.ERR_TAB_CHARACTER_FORBIDDEN
                            ),
                            new ReplaceTabWithSpacesQuickFix()
                        );
                        count++;
                        i = runEnd - 1;
                    }
                }
            }
        };
    }

    /**
     * Quick-fix replacing all forbidden tab characters in the document buffer with two canonical spaces
     * while preserving verbatim fenced string bodies.
     */
    public static final class ReplaceTabWithSpacesQuickFix implements LocalQuickFix, IntentionAction, PriorityAction {

        /**
         * Constructs a new {@link ReplaceTabWithSpacesQuickFix}.
         */
        public ReplaceTabWithSpacesQuickFix() {}

        @Override
        public @NotNull String getName() {
            return QUICK_FIX_NAME;
        }

        @Override
        public @NotNull String getText() {
            return QUICK_FIX_NAME;
        }

        @Override
        public @NotNull String getFamilyName() {
            return QUICK_FIX_NAME;
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.TOP;
        }

        @Override
        public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
            return file instanceof StvnFile;
        }

        @Override
        public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
            if (file != null) {
                replaceForbiddenTabsInFile(project, file);
            }
        }

        @Override
        public boolean startInWriteAction() {
            return true;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (element == null) {
                return;
            }
            PsiFile file = element.getContainingFile();
            if (file == null) {
                return;
            }
            replaceForbiddenTabsInFile(project, file);
        }

        private static void replaceForbiddenTabsInFile(Project project, PsiFile file) {
            Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
            if (doc != null) {
                String content = doc.getText();
                StringBuilder sb = new StringBuilder(content.length());
                int len = content.length();
                for (int i = 0; i < len; i++) {
                    char c = content.charAt(i);
                    if (c == '\t') {
                        PsiElement leaf = file.findElementAt(i);
                        if (leaf != null && leaf.getNode() != null
                                && leaf.getNode().getElementType() == StvnTypes.LITERAL_STRING_FENCED) {
                            TextRange leafRange = leaf.getTextRange();
                            String leafText = leaf.getText();
                            int firstNewline = leafText.indexOf('\n');
                            int lastNewline = leafText.lastIndexOf('\n');
                            int relOffset = i - leafRange.getStartOffset();
                            boolean inVerbatimBody = firstNewline >= 0
                                && lastNewline >= firstNewline
                                && relOffset > firstNewline
                                && relOffset < lastNewline;
                            if (inVerbatimBody) {
                                int bodyEnd = Math.min(len, leafRange.getStartOffset() + lastNewline);
                                sb.append(content, i, bodyEnd);
                                i = bodyEnd - 1;
                                continue;
                            }
                        }
                        sb.append("  ");
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
