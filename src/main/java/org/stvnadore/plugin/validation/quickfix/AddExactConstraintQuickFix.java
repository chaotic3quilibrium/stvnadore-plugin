package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.SchemaType;

/**
 * Quick-fix attaching the '{ #exact }' constraint to continuous ':Float'.
 * Elevates continuous machine float to discrete arbitrary-precision decimal representation,
 * restoring '#equatable #TRUE' under STVN Spec § 6.2 without requiring nominal branding.
 */
@NullMarked
public final class AddExactConstraintQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement {

    /**
     * Constructs a quick-fix targeting the non-equatable ':Float' SchemaType.
     *
     * @param element the SchemaType representing continuous ':Float'
     */
    public AddExactConstraintQuickFix(SchemaType element) {
        super(element);
    }

    @Override
    public @Nls @NotNull String getText() {
        return "Add '{ #exact }' constraint to :Float";
    }

    @Override
    public @Nls @NotNull String getFamilyName() {
        return "Add '{ #exact }' constraint";
    }

    @Override
    public boolean isAvailable(
            @NotNull Project project,
            @NotNull PsiFile file,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        if (!startElement.isValid()) {
            return false;
        }
        var schemaType = (startElement instanceof SchemaType st) ? st
                : PsiTreeUtil.getParentOfType(startElement, SchemaType.class, false);
        if (schemaType == null || !schemaType.isValid()) {
            return false;
        }
        var text = schemaType.getText();
        return text.contains(StvnVocabulary.TYPE_FLOAT) && !text.contains(StvnVocabulary.FACET_KW_EXACT);
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        var schemaType = (startElement instanceof SchemaType st) ? st
                : PsiTreeUtil.getParentOfType(startElement, SchemaType.class, false);
        if (schemaType == null || !schemaType.isValid()) {
            return;
        }
        var targetFile = schemaType.getContainingFile();

        var metaMap = schemaType.getMetadataMap();
        if (metaMap != null && metaMap.isValid()) {
            var entries = metaMap.getMetadataEntryList();
            for (var entry : entries) {
                if (entry.getText().trim().startsWith(StvnVocabulary.FACET_KW_EXACT)) {
                    return; // Already exact
                }
            }

            var newEntriesText = new StringBuilder();
            newEntriesText.append(StvnVocabulary.FACET_KW_EXACT);
            for (var entry : entries) {
                var entryText = entry.getText().trim();
                // Purge conflicting #size facet per STVN Spec § 6.1 (mutually exclusive)
                if (!entryText.startsWith(StvnVocabulary.FACET_KW_SIZE)) {
                    newEntriesText.append(" ").append(entryText);
                }
            }
            var newMap = StvnElementFactory.createMetadataMap(project, "{ " + newEntriesText + " }");
            metaMap.replace(newMap);
        } else {
            var newSchema = StvnElementFactory.createSchemaType(
                    project, "{ " + StvnVocabulary.FACET_KW_EXACT + " } " + schemaType.getText());
            schemaType.replace(newSchema);
        }

        StvnTraitViolationQuickFixProvider.postProcessFileMutation(project, file, targetFile);
    }
}
