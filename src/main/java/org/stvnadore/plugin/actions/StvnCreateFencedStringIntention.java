package org.stvnadore.plugin.actions;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
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
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.StvnBundle;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.plugin.intentions.StvnConvertEscapedStringToFencedIntention;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.StvnTypes;

/**
 * Intention action that converts an escape-free double-quoted simple string literal (or an incomplete
 * opening triple-quote recovery token) into a canonical STVN 2.0.0 Rule {@code STR-04} fenced string block.
 */
@NullMarked
public final class StvnCreateFencedStringIntention
        extends PsiElementBaseIntentionAction
        implements PriorityAction {

    private static final String DEFAULT_RECOVERY_TAG = "FENCE";

    /**
     * Constructs a new {@code StvnCreateFencedStringIntention} instance.
     */
    public StvnCreateFencedStringIntention() {
        setText(StvnBundle.message("stvn.intention.convert.string.to.fenced.text"));
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.TOP;
    }

    @Override
    public @NotNull String getText() {
        return StvnBundle.message("stvn.intention.convert.string.to.fenced.text");
    }

    @Override
    public @NotNull String getFamilyName() {
        return StvnBundle.message("stvn.intention.convert.string.to.fenced.text");
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element) {
        if (!element.isValid()) {
            return false;
        }
        var file = element.getContainingFile();
        if (file == null
                || !file.getLanguage().isKindOf(StvnLanguage.INSTANCE)
                || StvnMetaTemplateUtil.isInSecondaryRootSection(element)) {
            return false;
        }
        if (StvnConvertEscapedStringToFencedIntention.canConvertUnescapedSimpleStringToFenced(element, editor)) {
            return true;
        }
        if (isSimpleOrFencedStringElement(element, editor, file)) {
            return false;
        }
        if (editor == null) {
            return false;
        }

        var doc = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        if (offset < 0 || offset > doc.getTextLength()) {
            return false;
        }

        int lineNum = doc.getLineNumber(offset);
        int lineStart = doc.getLineStartOffset(lineNum);
        int lineEnd = doc.getLineEndOffset(lineNum);
        String lineText = doc.getText(new TextRange(lineStart, lineEnd));

        int tripleIdx = lineText.indexOf("\"\"\"");
        if (tripleIdx < 0) {
            return false;
        }

        int tripleStart = lineStart + tripleIdx;
        int tripleEnd = tripleStart + 3;
        if (offset < tripleStart || offset > tripleEnd + 1) {
            return false;
        }

        String afterTriple = lineText.substring(tripleIdx + 3).trim();
        if (afterTriple.startsWith(StvnVocabulary.DELIM_OPEN_BRACKET)) {
            return false;
        }

        String beforeTriple = lineText.substring(0, tripleIdx).trim();
        return !beforeTriple.endsWith(StvnVocabulary.DELIM_CLOSE_BRACKET);
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        PsiElement element = StvnConvertEscapedStringToFencedIntention.findSimpleStringElementAtOrBeforeCaret(
            file, editor.getCaretModel().getOffset()
        );
        if (element == null) {
            element = getElement(editor, file);
        }
        if (element == null || !isAvailable(project, editor, element)) {
            return IntentionPreviewInfo.EMPTY;
        }
        invoke(project, editor, element);
        return IntentionPreviewInfo.DIFF;
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element)
            throws IncorrectOperationException {
        if (editor == null) {
            return;
        }
        var file = element.getContainingFile();
        if (file == null) {
            return;
        }

        if (StvnConvertEscapedStringToFencedIntention.invokeUnescapedSimpleStringToFenced(project, editor, element)) {
            return;
        }
        if (isSimpleOrFencedStringElement(element, editor, file)) {
            return;
        }

        Document doc = file.getViewProvider().getDocument();
        if (doc == null) {
            doc = editor.getDocument();
        }
        int offset = editor.getCaretModel().getOffset();
        int lineNum = doc.getLineNumber(offset);
        int lineStart = doc.getLineStartOffset(lineNum);
        int lineEnd = doc.getLineEndOffset(lineNum);
        String lineText = doc.getText(new TextRange(lineStart, lineEnd));

        int tripleIdx = lineText.indexOf("\"\"\"");
        if (tripleIdx < 0) {
            return;
        }

        StringBuilder baseIndentSb = new StringBuilder();
        for (int i = lineStart; i < lineEnd; i++) {
            char c = doc.getCharsSequence().charAt(i);
            if (c == ' ' || c == '\t') {
                baseIndentSb.append(c);
            } else {
                break;
            }
        }
        String baseIndent = baseIndentSb.toString();
        String innerIndent = baseIndent + "  ";
        String lineSep = doc.getText().contains("\r\n") ? "\r\n" : "\n";
        String tagToken = StvnVocabulary.DELIM_OPEN_BRACKET + DEFAULT_RECOVERY_TAG + StvnVocabulary.DELIM_CLOSE_BRACKET;

        int insertOffset = lineStart + tripleIdx + 3;
        String insertion = tagToken + lineSep + innerIndent + lineSep + baseIndent + tagToken + "\"\"\"";
        doc.insertString(insertOffset, insertion);
        if (file.isPhysical() && !IntentionPreviewUtils.isPreviewElement(file)) {
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }

        int caretPos = insertOffset + tagToken.length() + lineSep.length() + innerIndent.length();
        editor.getCaretModel().moveToOffset(caretPos);
    }

    private static boolean isSimpleOrFencedStringElement(
            PsiElement element,
            @Nullable Editor editor,
            PsiFile file
    ) {
        if (isSimpleOrFencedNode(element)) {
            return true;
        }
        if (editor != null) {
            int offset = editor.getCaretModel().getOffset();
            if (offset > 0 && offset <= file.getTextLength()) {
                PsiElement prev = file.findElementAt(offset - 1);
                if (prev != null && prev.getTextRange().getEndOffset() == offset && isSimpleOrFencedNode(prev)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSimpleOrFencedNode(PsiElement element) {
        var node = element.getNode();
        if (node != null
                && (node.getElementType() == StvnTypes.LITERAL_STRING_SIMPLE
                    || node.getElementType() == StvnTypes.LITERAL_STRING_FENCED)) {
            return true;
        }
        StringLiteral sl = element instanceof StringLiteral s
            ? s
            : PsiTreeUtil.getParentOfType(element, StringLiteral.class, false);
        return sl != null
            && sl.getNode() != null
            && (sl.getNode().findChildByType(StvnTypes.LITERAL_STRING_SIMPLE) != null
                || sl.getNode().findChildByType(StvnTypes.LITERAL_STRING_FENCED) != null);
    }
}
