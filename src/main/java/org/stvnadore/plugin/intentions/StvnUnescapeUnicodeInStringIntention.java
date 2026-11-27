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
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.plugin.StvnBundle;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.plugin.psi.StvnUnicodeEscapeCodec;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.StringLiteral;

import java.util.Optional;

/**
 * Intention action that decodes lexically safe {@code \u005CuXXXX} escape sequences (including
 * paired UTF-16 surrogate escapes and {@code \u005Cu200D} ZWJ / {@code \u005CuFE0F} variation
 * selectors) inside a single-line STVN simple string literal ({@code "..."}) to UTF-8 while
 * keeping lone surrogates, controls, line breaks, quotes, backslashes, and forbidden raw scalars
 * safely escaped.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnUnescapeUnicodeInStringIntention
        extends PsiElementBaseIntentionAction
        implements PriorityAction {

    /**
     * Constructs a new {@code StvnUnescapeUnicodeInStringIntention}.
     */
    public StvnUnescapeUnicodeInStringIntention() {
        setText(StvnBundle.message("stvn.intention.unescape.unicode.text"));
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.NORMAL;
    }

    @Override
    public @NotNull String getText() {
        return StvnBundle.message("stvn.intention.unescape.unicode.text");
    }

    @Override
    public @NotNull String getFamilyName() {
        return StvnBundle.message("stvn.intention.unescape.unicode.text");
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
        StringLiteral target = StvnEscapeNonAsciiInStringIntention.resolveTargetSimpleStringLiteral(element, editor);
        if (target == null || StvnMetaTemplateUtil.isInSecondaryRootSection(target)) {
            return false;
        }
        return StvnUnicodeEscapeCodec.hasSafeUnescapableUnicode(target.getText());
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element)
            throws IncorrectOperationException {
        StringLiteral target = StvnEscapeNonAsciiInStringIntention.resolveTargetSimpleStringLiteral(element, editor);
        if (target == null || StvnMetaTemplateUtil.isInSecondaryRootSection(target)) {
            return;
        }
        Optional<String> unescapedOpt = StvnUnicodeEscapeCodec.unescapeUnicodeInSimpleStringLiteral(target.getText());
        if (unescapedOpt.isEmpty() || unescapedOpt.get().equals(target.getText())) {
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
        document.replaceString(range.getStartOffset(), range.getEndOffset(), unescapedOpt.get());
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
}
