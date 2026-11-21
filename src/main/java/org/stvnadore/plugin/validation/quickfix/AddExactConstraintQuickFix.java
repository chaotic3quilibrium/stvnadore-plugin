package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.validation.StvnMetadataFacetInsertionUtil;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.TypeDefinition;

/**
 * Quick-fix attaching the {@code #exact} constraint to continuous {@code :Float} or a nominal
 * {@code :Float} definition in {@code :defs}.
 * Elevates continuous machine float to discrete arbitrary-precision decimal representation,
 * restoring {@code #equatable #TRUE} under STVN Spec § 6.2.
 */
@NullMarked
public final class AddExactConstraintQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement {

    @SafeFieldForPreview
    private final @Nullable String nominalTypeName;

    /**
     * Constructs a quick-fix targeting an inline continuous {@code :Float} {@link SchemaType}.
     *
     * @param element the {@link SchemaType} representing continuous {@code :Float}
     */
    public AddExactConstraintQuickFix(SchemaType element) {
        super(element);
        this.nominalTypeName = null;
    }

    /**
     * Constructs a quick-fix targeting a nominal continuous {@code :Float} {@link TypeDefinition} in {@code :defs}.
     *
     * @param nominalFloatDef the {@link TypeDefinition} declaring continuous {@code :Float}
     * @param nominalTypeName display name of the nominal type (for example {@code :SampleReading})
     */
    public AddExactConstraintQuickFix(TypeDefinition nominalFloatDef, String nominalTypeName) {
        super(nominalFloatDef);
        this.nominalTypeName = nominalTypeName;
    }

    @Override
    public @Nls @NotNull String getText() {
        if (nominalTypeName != null) {
            return "Add '" + StvnVocabulary.FACET_KW_EXACT + "' to " + nominalTypeName
                + " in " + StvnVocabulary.KEYWORD_DEFS;
        }
        return "Add '{ " + StvnVocabulary.FACET_KW_EXACT + " }' constraint to " + StvnVocabulary.TYPE_FLOAT;
    }

    @Override
    public @Nls @NotNull String getFamilyName() {
        return "Add '{ " + StvnVocabulary.FACET_KW_EXACT + " }' constraint";
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
        if (startElement instanceof TypeDefinition td) {
            var st = td.getSchemaType();
            if (st == null || !st.isValid()) {
                return false;
            }
            return st.getText().contains(StvnVocabulary.TYPE_FLOAT)
                && !containsExact(td.getMetadataMap())
                && !containsExact(st.getMetadataMap());
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
        SchemaType schemaType;
        MetadataMap metaMap;
        PsiFile targetFile;
        if (startElement instanceof TypeDefinition td) {
            if (!td.isValid() || td.getSchemaType() == null) {
                return;
            }
            schemaType = td.getSchemaType();
            metaMap = td.getMetadataMap() != null ? td.getMetadataMap() : schemaType.getMetadataMap();
            targetFile = td.getContainingFile();
        } else {
            schemaType = (startElement instanceof SchemaType st) ? st
                    : PsiTreeUtil.getParentOfType(startElement, SchemaType.class, false);
            if (schemaType == null || !schemaType.isValid()) {
                return;
            }
            metaMap = schemaType.getMetadataMap();
            targetFile = schemaType.getContainingFile();
        }

        if (metaMap != null && metaMap.isValid()) {
            if (containsExact(metaMap)) {
                return;
            }
            boolean hasSizeFacet = metaMap.getMetadataEntryList().stream()
                .anyMatch(e -> e.getText().trim().startsWith(StvnVocabulary.FACET_KW_SIZE));
            if (!hasSizeFacet && targetFile != null) {
                var psiDocManager = PsiDocumentManager.getInstance(project);
                var doc = targetFile.getViewProvider().getDocument();
                if (doc == null) {
                    doc = psiDocManager.getDocument(targetFile);
                }
                if (doc != null) {
                    if (targetFile.isPhysical()) {
                        psiDocManager.doPostponedOperationsAndUnblockDocument(doc);
                    }
                    StvnMetadataFacetInsertionUtil.insertOrReplaceSingleFacet(
                        doc,
                        metaMap,
                        StvnVocabulary.FACET_KW_EXACT,
                        StvnVocabulary.FACET_KW_EXACT
                    );
                    psiDocManager.commitDocument(doc);
                    StvnTraitViolationQuickFixProvider.postProcessFileMutation(project, file, targetFile);
                    return;
                }
            }

            var newEntriesText = new StringBuilder();
            newEntriesText.append(StvnVocabulary.FACET_KW_EXACT);
            for (var entry : metaMap.getMetadataEntryList()) {
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

    private static boolean containsExact(@Nullable MetadataMap map) {
        if (map == null) {
            return false;
        }
        for (var entry : map.getMetadataEntryList()) {
            if (entry.getText().trim().startsWith(StvnVocabulary.FACET_KW_EXACT)) {
                return true;
            }
        }
        return false;
    }
}
