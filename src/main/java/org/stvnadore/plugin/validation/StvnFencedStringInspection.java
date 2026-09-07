package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.StvnTypes;

import java.util.regex.Pattern;

/**
 * Validates STVN Fenced String Delimiter Invariants (Rule STR-04):
 * 1. Opening delimiter must match """->[TAG] or """[TAG] followed by newline.
 * 2. Tag must match ^[a-zA-Z0-9_-]{1,256}$.
 * 3. Prohibits empty tags, whitespace, quotes, and punctuation.
 * 4. Closing delimiter must match opening tag identically ([TAG]""").
 * 5. Supports arbitrary recursive nesting without premature collapse.
 * 6. Provides quick-fixes to sanitize tags and balance closing fences using resilient lastIndexOf targeting.
 */
@NullMarked
public final class StvnFencedStringInspection extends LocalInspectionTool {

    private static final Pattern VALID_TAG_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,256}$");

    public StvnFencedStringInspection() {}

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new org.stvnadore.psi.Visitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                super.visitElement(element);
                var node = element.getNode();
                if (node != null) {
                    if (element instanceof StringLiteral && node.findChildByType(StvnTypes.LITERAL_STRING_FENCED) != null) {
                        validateFencedString(element, holder);
                    } else if (node.getElementType() == StvnTypes.LITERAL_STRING_FENCED && !(element.getParent() instanceof StringLiteral)) {
                        validateFencedString(element, holder);
                    }
                }
            }

            @Override
            public void visitStringLiteral(@NotNull StringLiteral element) {
                super.visitStringLiteral(element);
                var node = element.getNode();
                if (node != null && node.findChildByType(StvnTypes.LITERAL_STRING_FENCED) != null) {
                    validateFencedString(element, holder);
                }
            }
        };
    }

    private static void validateFencedString(PsiElement element, ProblemsHolder holder) {
        String text = element.getText();
        if (!text.startsWith("\"\"\"")) {
            return;
        }

        int openBracket = text.indexOf('[');
        int closeBracket = text.indexOf(']', openBracket >= 0 ? openBracket : 0);
        if (openBracket < 0 || closeBracket <= openBracket) {
            return;
        }

        String openTag = text.substring(openBracket + 1, closeBracket);
        int openDelimiterEnd = text.indexOf('\n', closeBracket);
        if (openDelimiterEnd < 0) {
            openDelimiterEnd = text.length();
        } else {
            openDelimiterEnd += 1;
        }

        TextRange openRange = new TextRange(0, Math.min(openDelimiterEnd, text.length()));

        // 1. Validate Opening Delimiter Tag
        if (openTag.isEmpty()) {
            holder.registerProblem(
                element,
                openRange,
                "Rule STR-04 violation: Fenced string delimiter tag must not be empty",
                new SupplyDefaultTagQuickFix("TEXT")
            );
            return;
        }

        if (openTag.contains(" ") || openTag.contains("\t") || openTag.contains("\r") || openTag.contains("\n")) {
            holder.registerProblem(
                element,
                openRange,
                "Rule STR-04 violation: Fenced string delimiter tag must not contain whitespace, got '" + openTag + "'",
                new SanitizeFenceTagQuickFix(openTag)
            );
            return;
        }

        if (openTag.length() > 256) {
            holder.registerProblem(
                element,
                openRange,
                "Rule STR-04 violation: Fenced string delimiter tag length exceeds maximum of 256 characters, got " + openTag.length(),
                new TruncateTagQuickFix(openTag)
            );
            return;
        }

        if (!VALID_TAG_PATTERN.matcher(openTag).matches()) {
            holder.registerProblem(
                element,
                openRange,
                "Rule STR-04 violation: Fenced string delimiter tag '" + openTag + "' contains invalid characters; must match positive character class ^[a-zA-Z0-9_-]{1,256}$",
                new SanitizeFenceTagQuickFix(openTag)
            );
            return;
        }

        // 2. Validate Closing Delimiter
        String expectedCloseFence = "[" + openTag + "]\"\"\"";
        int lastExpectedIdx = text.lastIndexOf(expectedCloseFence);
        if (lastExpectedIdx >= 0 && lastExpectedIdx >= text.length() - expectedCloseFence.length() - 5) {
            return; // Symmetrically and validly closed fence
        }

        // Check for mismatched closing fence candidate near terminal position
        int lastCloseTriple = text.lastIndexOf("\"\"\"");
        if (lastCloseTriple > closeBracket) {
            int lastOpenBracket = text.lastIndexOf('[', lastCloseTriple);
            int lastCloseBracket = text.lastIndexOf(']', lastCloseTriple);
            if (lastOpenBracket >= 0 && lastCloseBracket > lastOpenBracket && lastCloseBracket == lastCloseTriple - 1) {
                String closingTag = text.substring(lastOpenBracket + 1, lastCloseBracket);
                if (!closingTag.equals(openTag)) {
                    TextRange closingRange = new TextRange(lastOpenBracket, text.length());
                    holder.registerProblem(
                        element,
                        closingRange,
                        "Rule STR-04 violation: Mismatched closing fence tag '[" + closingTag + "]', expected '[" + openTag + "]'",
                        new BalanceClosingTagQuickFix(openTag, closingTag)
                    );
                    return;
                }
            }
        }

        // If no matching or candidate closing fence exists at the terminal boundary
        holder.registerProblem(
            element,
            openRange,
            "Rule STR-04 violation: Unclosed fenced string block; expected closing delimiter '[" + openTag + "]\"\"\"'",
            new AppendClosingFenceQuickFix(openTag)
        );
    }

    private static final class SupplyDefaultTagQuickFix implements LocalQuickFix {
        private final String defaultTag;

        public SupplyDefaultTagQuickFix(String defaultTag) {
            this.defaultTag = defaultTag;
        }

        @Override
        public @NotNull String getName() {
            return "Supply default tag '[" + defaultTag + "]'";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Supply default fenced string tag";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null) return;
            var file = element.getContainingFile();
            if (file == null) return;
            var docManager = PsiDocumentManager.getInstance(project);
            var doc = file.getViewProvider().getDocument();
            String text = element.getText();
            int openBracket = text.indexOf("[]");
            if (openBracket >= 0) {
                String replaced = text.replaceFirst("\\[\\]", "[" + defaultTag + "]");
                int closeEmptyIdx = replaced.lastIndexOf("[]\"\"\"");
                if (closeEmptyIdx >= 0) {
                    replaced = replaced.substring(0, closeEmptyIdx) + "[" + defaultTag + "]\"\"\"" + replaced.substring(closeEmptyIdx + 5);
                }
                if (doc != null) {
                    var range = element.getTextRange();
                    doc.replaceString(range.getStartOffset(), range.getEndOffset(), replaced);
                    docManager.commitDocument(doc);
                } else {
                    var dummy = StvnElementFactory.createValue(project, replaced);
                    var newLiteral = dummy.getStringLiteral();
                    element.replace(newLiteral != null ? newLiteral : dummy);
                }
            }
        }
    }

    private static final class SanitizeFenceTagQuickFix implements LocalQuickFix {
        private final String rawTag;

        public SanitizeFenceTagQuickFix(String rawTag) {
            this.rawTag = rawTag;
        }

        @Override
        public @NotNull String getName() {
            return "Strip invalid characters from tag";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Sanitize fenced string delimiter tag";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null) return;
            var file = element.getContainingFile();
            if (file == null) return;
            var docManager = PsiDocumentManager.getInstance(project);
            var doc = file.getViewProvider().getDocument();
            String sanitized = rawTag.replaceAll("[^a-zA-Z0-9_-]", "");
            if (sanitized.isEmpty()) sanitized = "RAW";
            String text = element.getText();
            String replaced = text.replace("[" + rawTag + "]", "[" + sanitized + "]");
            if (doc != null) {
                var range = element.getTextRange();
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), replaced);
                docManager.commitDocument(doc);
            } else {
                var dummy = StvnElementFactory.createValue(project, replaced);
                var newLiteral = dummy.getStringLiteral();
                element.replace(newLiteral != null ? newLiteral : dummy);
            }
        }
    }

    private static final class TruncateTagQuickFix implements LocalQuickFix {
        private final String rawTag;

        public TruncateTagQuickFix(String rawTag) {
            this.rawTag = rawTag;
        }

        @Override
        public @NotNull String getName() {
            return "Truncate tag to 256 characters";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Truncate fenced string delimiter tag";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null) return;
            var file = element.getContainingFile();
            if (file == null) return;
            var docManager = PsiDocumentManager.getInstance(project);
            var doc = file.getViewProvider().getDocument();
            String truncated = rawTag.substring(0, 256);
            String text = element.getText();
            String replaced = text.replace("[" + rawTag + "]", "[" + truncated + "]");
            if (doc != null) {
                var range = element.getTextRange();
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), replaced);
                docManager.commitDocument(doc);
            } else {
                var dummy = StvnElementFactory.createValue(project, replaced);
                var newLiteral = dummy.getStringLiteral();
                element.replace(newLiteral != null ? newLiteral : dummy);
            }
        }
    }

    private static final class BalanceClosingTagQuickFix implements LocalQuickFix {
        private final String expectedTag;
        private final String currentClosingTag;

        public BalanceClosingTagQuickFix(String expectedTag, String currentClosingTag) {
            this.expectedTag = expectedTag;
            this.currentClosingTag = currentClosingTag;
        }

        @Override
        public @NotNull String getName() {
            return "Replace '[" + currentClosingTag + "]\"\"\"' with '[" + expectedTag + "]\"\"\"'";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Balance closing fence delimiter";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null) return;
            var file = element.getContainingFile();
            if (file == null) return;
            var docManager = PsiDocumentManager.getInstance(project);
            var doc = file.getViewProvider().getDocument();
            String text = element.getText();
            String targetClosing = "[" + currentClosingTag + "]\"\"\"";
            String balancedClosing = "[" + expectedTag + "]\"\"\"";
            int lastIdx = text.lastIndexOf(targetClosing);
            if (lastIdx >= 0) {
                if (doc != null) {
                    int startOffset = element.getTextRange().getStartOffset() + lastIdx;
                    doc.replaceString(startOffset, startOffset + targetClosing.length(), balancedClosing);
                    docManager.commitDocument(doc);
                } else {
                    String updated = text.substring(0, lastIdx) + balancedClosing + text.substring(lastIdx + targetClosing.length());
                    var dummy = StvnElementFactory.createValue(project, updated);
                    var newLiteral = dummy.getStringLiteral();
                    element.replace(newLiteral != null ? newLiteral : dummy);
                }
            }
        }
    }

    private static final class AppendClosingFenceQuickFix implements LocalQuickFix {
        private final String tag;

        public AppendClosingFenceQuickFix(String tag) {
            this.tag = tag;
        }

        @Override
        public @NotNull String getName() {
            return "Append closing delimiter '[" + tag + "]\"\"\"'";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Append closing fence delimiter";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null) return;
            var file = element.getContainingFile();
            if (file == null) return;
            var docManager = PsiDocumentManager.getInstance(project);
            var doc = file.getViewProvider().getDocument();
            String fence = "\n[" + tag + "]\"\"\"";
            if (doc != null) {
                int endOffset = element.getTextRange().getEndOffset();
                doc.insertString(endOffset, fence);
                docManager.commitDocument(doc);
            } else {
                String updated = element.getText() + fence;
                var dummy = StvnElementFactory.createValue(project, updated);
                var newLiteral = dummy.getStringLiteral();
                element.replace(newLiteral != null ? newLiteral : dummy);
            }
        }
    }
}
