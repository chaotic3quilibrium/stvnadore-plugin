package org.stvnadore.plugin.intentions;

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
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.plugin.StvnBundle;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.plugin.psi.StvnUnicodeEscapeCodec;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.StvnTypes;

import java.util.Optional;

/**
 * Intention action that encodes raw non-ASCII characters ({@code > 0x7F}) inside a single-line
 * STVN simple string literal ({@code "..."}) as uppercase 4-hex {@code \u005CuXXXX} sequences
 * while preserving all existing escape sequences verbatim.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnEscapeNonAsciiInStringIntention
        extends PsiElementBaseIntentionAction
        implements PriorityAction {

    /**
     * Constructs a new {@code StvnEscapeNonAsciiInStringIntention}.
     */
    public StvnEscapeNonAsciiInStringIntention() {
        setText(StvnBundle.message("stvn.intention.escape.non.ascii.text"));
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.NORMAL;
    }

    @Override
    public @NotNull String getText() {
        return StvnBundle.message("stvn.intention.escape.non.ascii.text");
    }

    @Override
    public @NotNull String getFamilyName() {
        return StvnBundle.message("stvn.intention.escape.non.ascii.text");
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element) {
        if (!element.isValid()) {
            return false;
        }
        PsiFile file = element.getContainingFile();
        if (file == null || !file.getLanguage().isKindOf(StvnLanguage.INSTANCE)) {
            return false;
        }
        StringLiteral target = resolveTargetSimpleStringLiteral(element, editor);
        if (target == null || StvnMetaTemplateUtil.isInSecondaryRootSection(target)) {
            return false;
        }
        return StvnUnicodeEscapeCodec.hasEscapableRawNonAscii(target.getText());
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element)
            throws IncorrectOperationException {
        StringLiteral target = resolveTargetSimpleStringLiteral(element, editor);
        if (target == null || StvnMetaTemplateUtil.isInSecondaryRootSection(target)) {
            return;
        }
        Optional<String> escapedOpt = StvnUnicodeEscapeCodec.escapeNonAsciiInSimpleStringLiteral(target.getText());
        if (escapedOpt.isEmpty() || escapedOpt.get().equals(target.getText())) {
            return;
        }
        PsiFile file = target.getContainingFile();
        Document document = file != null ? file.getViewProvider().getDocument() : null;
        if (document == null && editor != null) {
            document = editor.getDocument();
        }
        if (document == null) {
            return;
        }
        PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
        boolean commitPhysical = file != null
            && file.isPhysical()
            && !IntentionPreviewUtils.isPreviewElement(target);
        if (commitPhysical) {
            docManager.doPostponedOperationsAndUnblockDocument(document);
        }
        TextRange range = target.getTextRange();
        document.replaceString(range.getStartOffset(), range.getEndOffset(), escapedOpt.get());
        if (commitPhysical) {
            docManager.commitDocument(document);
        }
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

    static @Nullable StringLiteral resolveTargetSimpleStringLiteral(
            PsiElement element,
            @Nullable Editor editor
    ) {
        StringLiteral direct = extractValidSimpleStringLiteral(element);
        if (direct != null) {
            return direct;
        }
        PsiFile file = element.getContainingFile();
        if (editor != null && file != null) {
            PsiElement leaf = StvnConvertEscapedStringToFencedIntention.findSimpleStringElementAtOrBeforeCaret(
                file, editor.getCaretModel().getOffset()
            );
            if (leaf != null) {
                return extractValidSimpleStringLiteral(leaf);
            }
        }
        return null;
    }

    private static @Nullable StringLiteral extractValidSimpleStringLiteral(PsiElement candidate) {
        StringLiteral strLit = candidate instanceof StringLiteral sl
            ? sl
            : PsiTreeUtil.getParentOfType(candidate, StringLiteral.class, false);
        if (strLit == null || strLit.getNode() == null) {
            return null;
        }
        if (strLit.getNode().findChildByType(StvnTypes.LITERAL_STRING_SIMPLE) == null
                || strLit.getNode().findChildByType(StvnTypes.LITERAL_STRING_FENCED) != null
                || strLit.getNode().findChildByType(StvnTypes.MALFORMED_FENCE) != null) {
            return null;
        }
        return strLit;
    }
}
