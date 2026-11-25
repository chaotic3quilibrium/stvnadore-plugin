package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnStringCharacterPolicy;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.validation.quickfix.StvnPayloadValueQuickFixProvider;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.Value;

/**
 * Intention action that converts untagged triple-quoted strings (both multi-line and single-line inline)
 * into canonical Rule STR-04 fenced string blocks ({@code """[TAG]\n...[TAG]"""}) with collision-safe
 * tag selection and {@code #preserveIndent}-aware body indentation.
 */
@NullMarked
public final class ConvertToFencedStringIntentionAction implements IntentionAction, PriorityAction {

    private static final String DEFAULT_TAG = "FENCE";
    @SafeFieldForPreview
    private final TextRange targetRange;
    @SafeFieldForPreview
    private final String safeTag;

    /**
     * Constructs a new intention action bound to the opening delimiter coordinate span.
     *
     * @param targetRange the text range of the opening triple quotes
     */
    public ConvertToFencedStringIntentionAction(TextRange targetRange) {
        this(targetRange, DEFAULT_TAG);
    }

    /**
     * Constructs a new intention action bound to the opening delimiter span with a precomputed collision-safe tag.
     *
     * @param targetRange the text range of the opening triple quotes
     * @param safeTag     collision-safe delimiter tag (for example, {@code FENCE} or {@code FENCE_1})
     */
    public ConvertToFencedStringIntentionAction(TextRange targetRange, String safeTag) {
        this.targetRange = targetRange;
        this.safeTag = safeTag;
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.HIGH;
    }

    @Override
    public @NotNull String getText() {
        return "Convert to fenced string '\"\"\"[" + safeTag + "]...[" + safeTag + "]\"\"\"'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Convert to fenced string";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        if (file == null) return false;
        int start = targetRange.getStartOffset();
        return start >= 0 && start + 3 <= file.getTextLength() && isCleanUntaggedCandidate(file, start);
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) throws IncorrectOperationException {
        if (file == null) return;
        applyConversion(project, editor, file, file.isPhysical());
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        applyConversion(project, editor, file, false);
        return IntentionPreviewInfo.DIFF;
    }

    private void applyConversion(
            @NotNull Project project,
            @Nullable Editor editor,
            @NotNull PsiFile file,
            boolean moveCaret
    ) {
        Document doc = file.getViewProvider().getDocument();
        if (doc == null && editor != null) {
            doc = editor.getDocument();
        }
        if (doc == null) return;

        int startOffset = targetRange.getStartOffset();
        if (startOffset < 0 || startOffset + 3 > doc.getTextLength()) return;

        var element = file.findElementAt(startOffset);
        String text = (element != null && element.getText().startsWith("\"\"\"")) ? element.getText() : null;
        CharSequence chars = doc.getCharsSequence();
        boolean preserveIndent = resolveEffectivePreserveIndent(file, startOffset);
        String lineSep = doc.getText().contains("\r\n") ? "\r\n" : "\n";

        String updated;
        int replaceEndOffset;

        if (text != null && text.startsWith("\"\"\"") && text.endsWith("\"\"\"") && text.length() >= 6) {
            int elemStart = element.getTextRange().getStartOffset();
            String body = text.substring(3, text.length() - 3);
            String indent = computeOpeningIndent(chars, elemStart);
            String tag = StvnPsiLiteralEvaluator.chooseSafeFenceTag(body, safeTag);
            updated = formatFencedReplacement(body, tag, indent, preserveIndent, lineSep);
            replaceEndOffset = elemStart + text.length();
            startOffset = elemStart;
        } else {
            // Scan forward in document buffer for matching closing delimiter
            int closingIdx = -1;
            for (int i = startOffset + 3; i + 2 < chars.length(); i++) {
                if (chars.charAt(i) == '"' && chars.charAt(i + 1) == '"' && chars.charAt(i + 2) == '"') {
                    closingIdx = i;
                    break;
                }
            }

            if (closingIdx >= 0) {
                String body = doc.getText(new TextRange(startOffset + 3, closingIdx));
                String indent = computeOpeningIndent(chars, startOffset);
                String tag = StvnPsiLiteralEvaluator.chooseSafeFenceTag(body, safeTag);
                updated = formatFencedReplacement(body, tag, indent, preserveIndent, lineSep);
                replaceEndOffset = closingIdx + 3;
            } else {
                // Fallback for unclosed block string: append closing fence with newline and indentation
                String indent = computeOpeningIndent(chars, startOffset).replace("\t", "  ");
                String rest = doc.getText(new TextRange(startOffset + 3, doc.getTextLength()));
                String tag = StvnPsiLiteralEvaluator.chooseSafeFenceTag(rest, safeTag);
                String headerSep = startsWithHorizontalPlusNewline(rest)
                    ? ""
                    : lineSep + (preserveIndent ? "" : indent + "  ");
                updated = "\"\"\"[" + tag + "]" + headerSep + rest + lineSep + (preserveIndent ? "" : indent) + "[" + tag + "]\"\"\"";
                replaceEndOffset = doc.getTextLength();
            }
        }

        doc.replaceString(startOffset, replaceEndOffset, updated);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
        if (moveCaret) {
            repositionCaretToBodyLine(project, doc, startOffset);
        }
    }

    /**
     * Formats an untagged inner body into a syntactically valid Rule STR-04 fenced string literal.
     *
     * @param rawBody        content strictly between opening {@code """} and closing {@code """}
     * @param tag            collision-safe fence tag
     * @param lineIndent     spaces preceding the opening {@code """} on its line
     * @param preserveIndent whether {@code #preserveIndent #TRUE} governs this literal
     * @param lineSep        line separator ({@code "\n"} or {@code "\r\n"})
     * @return valid {@code """[TAG]\n...[TAG]"""} source text
     */
    public static String formatFencedReplacement(
            String rawBody,
            String tag,
            String lineIndent,
            boolean preserveIndent,
            String lineSep
    ) {
        String cleanIndent = lineIndent.replace("\t", "  ");
        if (startsWithHorizontalPlusNewline(rawBody)) {
            return "\"\"\"[" + tag + "]" + rawBody + "[" + tag + "]\"\"\"";
        }
        String normalized = StvnStringCharacterPolicy.normalizeLineEndingsToLf(rawBody);
        if (normalized.isEmpty()) {
            String closePrefix = preserveIndent ? "" : cleanIndent;
            return "\"\"\"[" + tag + "]" + lineSep + closePrefix + "[" + tag + "]\"\"\"";
        }
        String bodyMargin = preserveIndent ? "" : cleanIndent + "  ";
        boolean endsWithNewline = normalized.endsWith("\n");
        String core = endsWithNewline ? normalized.substring(0, normalized.length() - 1) : normalized;
        String[] lines = core.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("\"\"\"[").append(tag).append("]").append(lineSep);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append(lineSep);
            }
            String line = lines[i];
            if (!line.isEmpty()) {
                sb.append(bodyMargin).append(line);
            }
        }
        if (endsWithNewline) {
            sb.append(lineSep).append(preserveIndent ? "" : cleanIndent).append("[").append(tag).append("]\"\"\"");
        } else {
            sb.append("[").append(tag).append("]\"\"\"");
        }
        return sb.toString();
    }

    private static boolean startsWithHorizontalPlusNewline(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return i < s.length() && (s.charAt(i) == '\n' || s.charAt(i) == '\r');
    }

    private static boolean isCleanUntaggedCandidate(PsiFile file, int startOffset) {
        CharSequence chars = file.getViewProvider().getContents();
        if (startOffset + 3 > chars.length()) {
            return false;
        }
        int endIdx = chars.length();
        for (int i = startOffset + 3; i + 2 < chars.length(); i++) {
            if (chars.charAt(i) == '"' && chars.charAt(i + 1) == '"' && chars.charAt(i + 2) == '"') {
                endIdx = i;
                break;
            }
        }
        String rawSlice = chars.subSequence(startOffset + 3, endIdx).toString();
        if (StvnStringCharacterPolicy.containsUnpairedSurrogate(rawSlice)) {
            return false;
        }
        for (int i = 0; i < rawSlice.length(); ) {
            int cp = rawSlice.codePointAt(i);
            if (StvnStringCharacterPolicy.isForbiddenRawSourceCodePoint(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    /**
     * Resolves whether {@code #preserveIndent #TRUE} governs the string literal at {@code offset}.
     *
     * @param file   the enclosing PSI file
     * @param offset character offset of the string literal
     * @return {@code true} if {@code #preserveIndent #TRUE} applies; {@code false} otherwise
     */
    public static boolean resolveEffectivePreserveIndent(PsiFile file, int offset) {
        PsiElement elem = file.findElementAt(offset);
        if (elem == null) {
            return false;
        }
        MetadataEntry metaEntry = PsiTreeUtil.getParentOfType(elem, MetadataEntry.class, false);
        if (metaEntry != null) {
            String kw = StvnMetadataOrderInspection.extractFacetKeyword(metaEntry);
            if (StvnVocabulary.FACET_KW_REGEX.equals(kw)) {
                return false;
            }
            SchemaType enclosingSchema = PsiTreeUtil.getParentOfType(metaEntry, SchemaType.class);
            return enclosingSchema != null && StvnPsiLiteralEvaluator.extractPreserveIndentFromSchema(enclosingSchema);
        }
        Value val = PsiTreeUtil.getParentOfType(elem, Value.class, false);
        if (val != null) {
            SchemaType govSchema = StvnPayloadValueQuickFixProvider.resolveGoverningSchemaForValue(val);
            if (govSchema != null) {
                StvnSchema ir = StvnTypeResolver.resolveIrSchema(govSchema);
                if (ir instanceof StvnSchema.StringSchema ss) {
                    return ss.preserveIndent();
                }
                return StvnPsiLiteralEvaluator.extractPreserveIndentFromSchema(govSchema);
            }
        }
        return false;
    }

    @Override
    public boolean startInWriteAction() {
        return true;
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
        var sb = new StringBuilder();
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

    private static void repositionCaretToBodyLine(@NotNull Project project, @NotNull Document doc, int openOffset) {
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null || editor.getDocument() != doc) {
            var editors = EditorFactory.getInstance().getEditors(doc, project);
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
}
