package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.IntentionAction;
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
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.TypeEntry;

/**
 * Intention action that inserts an inferred or default schema type after an incomplete {@code :type} section.
 */
@NullMarked
public final class InsertSchemaTypeIntentionAction implements IntentionAction {

    private final TextRange targetRange;
    private final String suggestedSchemaType;

    public InsertSchemaTypeIntentionAction(TextRange targetRange) {
        this(targetRange, StvnVocabulary.TYPE_STRING);
    }

    public InsertSchemaTypeIntentionAction(TextRange targetRange, String suggestedSchemaType) {
        this.targetRange = targetRange;
        this.suggestedSchemaType = suggestedSchemaType;
    }

    @Override
    public @NotNull String getText() {
        return "Insert schema type '" + suggestedSchemaType + "'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Insert schema type";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        if (file == null) return false;
        int start = targetRange.getStartOffset();
        return start >= 0 && start <= file.getTextLength();
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) throws IncorrectOperationException {
        if (file == null) return;
        Document doc = file.getViewProvider().getDocument();
        if (doc == null && editor != null) {
            doc = editor.getDocument();
        }
        if (doc == null) return;

        int typeEnd = resolveInsertionOffset(file, targetRange);
        if (typeEnd < 0 || typeEnd > doc.getTextLength()) return;

        String schemaToInsert = inferSchemaTypeForRange(file, targetRange, suggestedSchemaType);
        CharSequence chars = doc.getCharsSequence();
        boolean hasTrailingSpace = typeEnd < chars.length() && (chars.charAt(typeEnd) == ' ' || chars.charAt(typeEnd) == '\t');
        String insertion = hasTrailingSpace ? schemaToInsert : " " + schemaToInsert;

        doc.insertString(typeEnd, insertion);
        PsiDocumentManager.getInstance(project).commitDocument(doc);

        repositionCaret(project, doc, typeEnd + insertion.length());
    }

    static String inferSchemaTypeForRange(@NotNull PsiFile file, @NotNull TextRange range) {
        return inferSchemaTypeForRange(file, range, StvnVocabulary.TYPE_STRING);
    }

    private static String inferSchemaTypeForRange(@NotNull PsiFile file, @NotNull TextRange range, String fallback) {
        PsiElement leaf = file.findElementAt(range.getStartOffset());
        TypeEntry typeEntry = PsiTreeUtil.getParentOfType(leaf, TypeEntry.class, false);
        if (typeEntry == null || typeEntry.getSchemaType() != null) {
            return fallback;
        }
        MetadataMap orphanMap = typeEntry.getMetadataMap();
        if (orphanMap == null || orphanMap.getMetadataEntryList().isEmpty()) {
            return fallback;
        }
        for (var entry : orphanMap.getMetadataEntryList()) {
            String name = StvnMetadataFacetInspection.extractFacetName(entry);
            if (StvnVocabulary.FACET_NAME_EXACT.equals(name)) {
                return StvnVocabulary.TYPE_FLOAT;
            }
            if (StvnVocabulary.FACET_NAME_SIZE.equals(name) || StvnVocabulary.FACET_NAME_UNSIGNED.equals(name)) {
                return StvnVocabulary.TYPE_INT;
            }
            if (StvnVocabulary.FACET_NAME_OFFSET.equals(name)
                    || StvnVocabulary.FACET_NAME_ZONED.equals(name)
                    || StvnVocabulary.FACET_NAME_AUDITED.equals(name)) {
                return StvnVocabulary.TYPE_DATE_TIME;
            }
            if (StvnVocabulary.SCALE_S.equals(name) || StvnVocabulary.SCALE_MS.equals(name)
                    || StvnVocabulary.SCALE_US.equals(name) || StvnVocabulary.SCALE_NS.equals(name)) {
                return StvnVocabulary.TYPE_TIME_EPOCH;
            }
            if (StvnVocabulary.FACET_NAME_INVERTIBLE.equals(name)) {
                return StvnVocabulary.TYPE_MAP + "(" + StvnVocabulary.TYPE_STRING + " " + StvnVocabulary.TYPE_STRING + ")";
            }
        }
        return fallback;
    }

    private static int resolveInsertionOffset(@NotNull PsiFile file, @NotNull TextRange range) {
        PsiElement leaf = file.findElementAt(range.getStartOffset());
        TypeEntry typeEntry = PsiTreeUtil.getParentOfType(leaf, TypeEntry.class, false);
        if (typeEntry != null && typeEntry.getSchemaType() == null && typeEntry.getMetadataMap() != null) {
            return typeEntry.getMetadataMap().getTextRange().getEndOffset();
        }
        return range.getEndOffset();
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }

    private static void repositionCaret(@NotNull Project project, @NotNull Document doc, int targetOffset) {
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null || editor.getDocument() != doc) {
            var editors = EditorFactory.getInstance().getEditors(doc, project);
            if (editors.length > 0) {
                editor = editors[0];
            }
        }
        if (editor != null && editor.getDocument() == doc && targetOffset <= doc.getTextLength()) {
            editor.getCaretModel().moveToOffset(targetOffset);
        }
    }
}
