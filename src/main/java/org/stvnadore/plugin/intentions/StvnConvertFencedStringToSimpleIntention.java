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
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnUnicodeEscapeCodec;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.StvnTypes;

import java.util.Optional;

/**
 * Intention action that converts a valid Rule {@code STR-04} fenced string block
 * ({@code """[TAG]\n...[TAG]"""}) into a canonically escaped single-line STVN simple
 * string literal ({@code "..."}) with lossless context-sensitive {@code #preserveIndent} decoding.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnConvertFencedStringToSimpleIntention
        extends PsiElementBaseIntentionAction
        implements PriorityAction {

    /**
     * Constructs a new {@code StvnConvertFencedStringToSimpleIntention}.
     */
    public StvnConvertFencedStringToSimpleIntention() {
        setText(StvnBundle.message("stvn.intention.convert.fenced.to.simple.text"));
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.TOP;
    }

    @Override
    public @NotNull String getText() {
        return StvnBundle.message("stvn.intention.convert.fenced.to.simple.text");
    }

    @Override
    public @NotNull String getFamilyName() {
        return StvnBundle.message("stvn.intention.convert.fenced.to.simple.text");
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
        StringLiteral target = resolveTargetFencedStringLiteral(element, editor);
        if (target == null || StvnMetaTemplateUtil.isInSecondaryRootSection(target)) {
            return false;
        }
        boolean preserveIndent = StvnPsiLiteralEvaluator.resolvePreserveIndentForStringLiteral(target);
        return StvnPsiLiteralEvaluator.decodeStringLiteral(target.getText(), preserveIndent).isPresent();
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element)
            throws IncorrectOperationException {
        StringLiteral target = resolveTargetFencedStringLiteral(element, editor);
        if (target == null || StvnMetaTemplateUtil.isInSecondaryRootSection(target)) {
            return;
        }
        boolean preserveIndent = StvnPsiLiteralEvaluator.resolvePreserveIndentForStringLiteral(target);
        Optional<String> decodedOpt = StvnPsiLiteralEvaluator.decodeStringLiteral(target.getText(), preserveIndent);
        if (decodedOpt.isEmpty()) {
            return;
        }
        String quoted = StvnPsiLiteralEvaluator.formatCanonicalQuotedString(decodedOpt.get());
        if (StvnSettings.getInstance(project).isEscapeNonAsciiInSimpleStrings()) {
            quoted = StvnUnicodeEscapeCodec.escapeNonAsciiInSimpleStringLiteral(quoted).orElse(quoted);
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
        document.replaceString(range.getStartOffset(), range.getEndOffset(), quoted);
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
        PsiElement element = findFencedLeafAtOrBeforeCaret(file, editor.getCaretModel().getOffset());
        if (element == null) {
            element = getElement(editor, file);
        }
        if (element == null || !isAvailable(project, editor, element)) {
            return IntentionPreviewInfo.EMPTY;
        }
        invoke(project, editor, element);
        return IntentionPreviewInfo.DIFF;
    }

    private static @Nullable StringLiteral resolveTargetFencedStringLiteral(
            PsiElement element,
            @Nullable Editor editor
    ) {
        StringLiteral direct = extractValidFencedStringLiteral(element);
        if (direct != null) {
            return direct;
        }
        PsiFile file = element.getContainingFile();
        if (editor != null && file != null) {
            PsiElement leaf = findFencedLeafAtOrBeforeCaret(file, editor.getCaretModel().getOffset());
            if (leaf != null) {
                return extractValidFencedStringLiteral(leaf);
            }
        }
        return null;
    }

    private static @Nullable StringLiteral extractValidFencedStringLiteral(PsiElement candidate) {
        StringLiteral strLit = candidate instanceof StringLiteral sl
            ? sl
            : PsiTreeUtil.getParentOfType(candidate, StringLiteral.class, false);
        if (strLit == null || strLit.getNode() == null) {
            return null;
        }
        if (strLit.getNode().findChildByType(StvnTypes.LITERAL_STRING_FENCED) == null
                || strLit.getNode().findChildByType(StvnTypes.MALFORMED_FENCE) != null) {
            return null;
        }
        return strLit;
    }

    private static @Nullable PsiElement findFencedLeafAtOrBeforeCaret(PsiFile file, int caretOffset) {
        int len = file.getTextLength();
        if (caretOffset >= 0 && caretOffset < len) {
            PsiElement at = file.findElementAt(caretOffset);
            if (at != null && at.getNode() != null && at.getNode().getElementType() == StvnTypes.LITERAL_STRING_FENCED) {
                return at;
            }
        }
        if (caretOffset > 0 && caretOffset <= len) {
            PsiElement prev = file.findElementAt(caretOffset - 1);
            if (prev != null
                    && prev.getNode() != null
                    && prev.getNode().getElementType() == StvnTypes.LITERAL_STRING_FENCED
                    && prev.getTextRange().getEndOffset() == caretOffset) {
                return prev;
            }
        }
        return null;
    }
}
