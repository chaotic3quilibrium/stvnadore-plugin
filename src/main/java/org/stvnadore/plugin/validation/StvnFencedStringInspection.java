package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileEditorManager;
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
    private static final Pattern DELIMITER_BOUNDARY_PATTERN = Pattern.compile("(\"\"\"(?:->)?\\[([a-zA-Z0-9_-]{1,256})\\])|(\\[([a-zA-Z0-9_-]{1,256})\\]\"\"\")");

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

    private static String computeOpeningIndent(CharSequence docText, int startOffset) {
        int lineStart = 0;
        for (int i = startOffset - 1; i >= 0; i--) {
            char c = docText.charAt(i);
            if (c == '\n') {
                lineStart = i + 1;
                break;
            }
        }
        boolean pureWhitespace = true;
        for (int i = lineStart; i < startOffset; i++) {
            char c = docText.charAt(i);
            if (c != ' ' && c != '\t') {
                pureWhitespace = false;
                break;
            }
        }
        if (pureWhitespace) {
            return docText.subSequence(lineStart, startOffset).toString();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = lineStart; i < startOffset; i++) {
            char c = docText.charAt(i);
            if (c == ' ' || c == '\t') {
                sb.append(c);
            } else {
                break;
            }
        }
        return sb.toString();
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
                new SupplyDefaultTagQuickFix("FENCE")
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

        // 2. Validate Closing Delimiter via Sequential Depth-Counter Scan
        var delimiterMatcher = DELIMITER_BOUNDARY_PATTERN.matcher(text);
        int depth = 0;
        int searchStart = openDelimiterEnd;
        String candidateClosingTag = null;
        TextRange candidateClosingRange = null;
        int closingRelativeOffset = -1;

        while (delimiterMatcher.find(searchStart)) {
            if (delimiterMatcher.group(1) != null) {
                // Opening delimiter: increment nesting depth
                depth++;
            } else if (delimiterMatcher.group(3) != null) {
                // Closing delimiter: decrement if nested, evaluate if at depth 0
                if (depth > 0) {
                    depth--;
                } else {
                    candidateClosingTag = delimiterMatcher.group(4);
                    candidateClosingRange = new TextRange(delimiterMatcher.start(3), delimiterMatcher.end(3));
                    closingRelativeOffset = delimiterMatcher.start(3);
                    break;
                }
            }
            searchStart = delimiterMatcher.end();
        }

        if (candidateClosingTag != null) {
            if (candidateClosingTag.equals(openTag)) {
                return; // Symmetrically closed block
            }

            // Tag mismatch at depth 0
            holder.registerProblem(
                element,
                candidateClosingRange,
                "Rule STR-04 violation: Mismatched closing fence tag '[" + candidateClosingTag + "]', expected '[" + openTag + "]'",
                new BalanceClosingTagQuickFix(openTag, candidateClosingTag, closingRelativeOffset),
                new BalanceOpeningTagQuickFix(openTag, candidateClosingTag)
            );
            holder.registerProblem(
                element,
                openRange,
                "Rule STR-04 violation: Mismatched opening fence tag '[" + openTag + "]', closing fence has '[" + candidateClosingTag + "]'",
                new AppendClosingFenceQuickFix(openTag),
                new BalanceClosingTagQuickFix(openTag, candidateClosingTag, closingRelativeOffset),
                new BalanceOpeningTagQuickFix(openTag, candidateClosingTag)
            );
            return;
        }

        // If no candidate closing delimiter exists at depth 0, classify as Unclosed
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
            if (openBracket < 0) return;

            int closeEmptyIdx = text.lastIndexOf("[]\"\"\"");
            int startOffset = element.getTextRange().getStartOffset();

            if (closeEmptyIdx >= 0 && closeEmptyIdx > openBracket) {
                String updated = text.substring(0, openBracket)
                        + "[" + defaultTag + "]"
                        + text.substring(openBracket + 2, closeEmptyIdx)
                        + "[" + defaultTag + "]\"\"\""
                        + text.substring(closeEmptyIdx + 5);
                if (doc != null) {
                    doc.replaceString(startOffset, startOffset + text.length(), updated);
                    docManager.commitDocument(doc);
                } else {
                    var dummy = StvnElementFactory.createValue(project, updated);
                    var newLiteral = dummy.getStringLiteral();
                    element.replace(newLiteral != null ? newLiteral : dummy);
                }
            } else {
                int newlineIndex = text.indexOf('\n');
                if (doc != null) {
                    var docChars = doc.getCharsSequence();
                    String indent = computeOpeningIndent(docChars, startOffset);
                    if (newlineIndex >= 0) {
                        String lineSep = (newlineIndex > 0 && text.charAt(newlineIndex - 1) == '\r') ? "\r\n" : "\n";
                        String openingLine = text.substring(0, newlineIndex + 1).replaceFirst("\\[\\]", "[" + defaultTag + "]");
                        String closingLine = indent + "[" + defaultTag + "]\"\"\"" + lineSep;
                        doc.replaceString(startOffset, startOffset + newlineIndex + 1, openingLine + closingLine);
                    } else {
                        String lineSep = doc.getText().contains("\r\n") ? "\r\n" : "\n";
                        String updatedOpening = text.replaceFirst("\\[\\]", "[" + defaultTag + "]");
                        String closingLine = lineSep + indent + "[" + defaultTag + "]\"\"\"";
                        doc.replaceString(startOffset, startOffset + text.length(), updatedOpening + closingLine);
                    }
                    docManager.commitDocument(doc);
                } else {
                    if (newlineIndex >= 0) {
                        String lineSep = (newlineIndex > 0 && text.charAt(newlineIndex - 1) == '\r') ? "\r\n" : "\n";
                        String openingLine = text.substring(0, newlineIndex + 1).replaceFirst("\\[\\]", "[" + defaultTag + "]");
                        String closingLine = "  [" + defaultTag + "]\"\"\"" + lineSep;
                        String rest = text.substring(newlineIndex + 1);
                        var dummy = StvnElementFactory.createValue(project, openingLine + closingLine + rest);
                        var newLiteral = dummy.getStringLiteral();
                        element.replace(newLiteral != null ? newLiteral : dummy);
                    } else {
                        String updated = text.replaceFirst("\\[\\]", "[" + defaultTag + "]") + "\n  [" + defaultTag + "]\"\"\"";
                        var dummy = StvnElementFactory.createValue(project, updated);
                        var newLiteral = dummy.getStringLiteral();
                        element.replace(newLiteral != null ? newLiteral : dummy);
                    }
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

    private static void repositionCaretToBodyLine(@NotNull Project project, @NotNull Document doc, int openOffset) {
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null || editor.getDocument() != doc) {
            var editors = com.intellij.openapi.editor.EditorFactory.getInstance().getEditors(doc, project);
            if (editors.length > 0) {
                editor = editors[0];
            }
        }
        if (editor != null && editor.getDocument() == doc) {
            int openLine = doc.getLineNumber(openOffset);
            int targetLine = openLine + 1;
            if (targetLine < doc.getLineCount()) {
                int targetOffset = doc.getLineStartOffset(targetLine);
                CharSequence seq = doc.getCharsSequence();
                int lineEnd = doc.getLineEndOffset(targetLine);
                while (targetOffset < lineEnd && (seq.charAt(targetOffset) == ' ' || seq.charAt(targetOffset) == '\t')) {
                    targetOffset++;
                }
                editor.getCaretModel().moveToOffset(targetOffset);
            }
        }
    }

    private static final class BalanceClosingTagQuickFix implements LocalQuickFix {
        private final String expectedTag;
        private final String currentClosingTag;
        private final int closingRelativeOffset;

        public BalanceClosingTagQuickFix(String expectedTag, String currentClosingTag, int closingRelativeOffset) {
            this.expectedTag = expectedTag;
            this.currentClosingTag = currentClosingTag;
            this.closingRelativeOffset = closingRelativeOffset;
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
            int elementStart = element.getTextRange().getStartOffset();
            String targetClosing = "[" + currentClosingTag + "]\"\"\"";
            String balancedClosing = "[" + expectedTag + "]\"\"\"";
            if (closingRelativeOffset >= 0 && closingRelativeOffset + targetClosing.length() <= text.length()) {
                if (doc != null) {
                    int startOffset = elementStart + closingRelativeOffset;
                    doc.replaceString(startOffset, startOffset + targetClosing.length(), balancedClosing);
                    docManager.commitDocument(doc);
                    repositionCaretToBodyLine(project, doc, elementStart);
                } else {
                    String updated = text.substring(0, closingRelativeOffset) + balancedClosing + text.substring(closingRelativeOffset + targetClosing.length());
                    var dummy = StvnElementFactory.createValue(project, updated);
                    var newLiteral = dummy.getStringLiteral();
                    element.replace(newLiteral != null ? newLiteral : dummy);
                }
            }
        }
    }

    private static final class BalanceOpeningTagQuickFix implements LocalQuickFix {
        private final String currentOpeningTag;
        private final String expectedOpeningTag;

        public BalanceOpeningTagQuickFix(String currentOpeningTag, String expectedOpeningTag) {
            this.currentOpeningTag = currentOpeningTag;
            this.expectedOpeningTag = expectedOpeningTag;
        }

        @Override
        public @NotNull String getName() {
            return "Replace '[" + currentOpeningTag + "]' with '[" + expectedOpeningTag + "]'";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Balance opening fence delimiter";
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
            int elementStart = element.getTextRange().getStartOffset();
            String targetOpening = "[" + currentOpeningTag + "]";
            String balancedOpening = "[" + expectedOpeningTag + "]";
            int firstIdx = text.indexOf(targetOpening);
            if (firstIdx >= 0) {
                if (doc != null) {
                    int startOffset = elementStart + firstIdx;
                    doc.replaceString(startOffset, startOffset + targetOpening.length(), balancedOpening);
                    docManager.commitDocument(doc);
                    repositionCaretToBodyLine(project, doc, elementStart);
                } else {
                    String updated = text.substring(0, firstIdx) + balancedOpening + text.substring(firstIdx + targetOpening.length());
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
            String text = element.getText();
            int newlineIndex = text.indexOf('\n');
            int startOffset = element.getTextRange().getStartOffset();
            if (doc != null) {
                var docChars = doc.getCharsSequence();
                String indent = computeOpeningIndent(docChars, startOffset);
                if (newlineIndex >= 0) {
                    String lineSep = (newlineIndex > 0 && text.charAt(newlineIndex - 1) == '\r') ? "\r\n" : "\n";
                    int insertOffset = startOffset + newlineIndex + 1;
                    String fence = indent + "[" + tag + "]\"\"\"" + lineSep;
                    doc.insertString(insertOffset, fence);
                } else {
                    String lineSep = doc.getText().contains("\r\n") ? "\r\n" : "\n";
                    int insertOffset = startOffset + text.length();
                    String fence = lineSep + indent + "[" + tag + "]\"\"\"";
                    doc.insertString(insertOffset, fence);
                }
                docManager.commitDocument(doc);
                repositionCaretToBodyLine(project, doc, startOffset);
            } else {
                if (newlineIndex >= 0) {
                    String lineSep = (newlineIndex > 0 && text.charAt(newlineIndex - 1) == '\r') ? "\r\n" : "\n";
                    String firstLine = text.substring(0, newlineIndex + 1);
                    String rest = text.substring(newlineIndex + 1);
                    String updated = firstLine + "  [" + tag + "]\"\"\"" + lineSep + rest;
                    var dummy = StvnElementFactory.createValue(project, updated);
                    var newLiteral = dummy.getStringLiteral();
                    element.replace(newLiteral != null ? newLiteral : dummy);
                } else {
                    String updated = text + "\n  [" + tag + "]\"\"\"";
                    var dummy = StvnElementFactory.createValue(project, updated);
                    var newLiteral = dummy.getStringLiteral();
                    element.replace(newLiteral != null ? newLiteral : dummy);
                }
            }
        }
    }
}
